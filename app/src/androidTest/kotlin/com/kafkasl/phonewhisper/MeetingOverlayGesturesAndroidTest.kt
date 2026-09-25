package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityServiceInfo
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingDocumentRead
import com.kafkasl.phonewhisper.meeting.MeetingDraftStore
import com.kafkasl.phonewhisper.meeting.MeetingHypothesis
import com.kafkasl.phonewhisper.meeting.MeetingMicrophoneFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingMicrophonePort
import com.kafkasl.phonewhisper.meeting.MeetingModelArtifact
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailability
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailabilityPort
import com.kafkasl.phonewhisper.meeting.MeetingModelCatalog
import com.kafkasl.phonewhisper.meeting.MeetingModelPaths
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import com.kafkasl.phonewhisper.meeting.MeetingNativeAdmission
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationPort
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationRequest
import com.kafkasl.phonewhisper.meeting.MeetingNativeRuntimeLease
import com.kafkasl.phonewhisper.meeting.MeetingSession
import com.kafkasl.phonewhisper.meeting.MeetingSessionFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPhase
import com.kafkasl.phonewhisper.meeting.MeetingRecordingState
import com.kafkasl.phonewhisper.meeting.MeetingWord
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real overlay service, pill touch listener, meeting panel, dialogs and note store.
 * Only the model/native/microphone boundaries are simulated; no real audio or model is loaded.
 */
@RunWith(AndroidJUnit4::class)
class MeetingOverlayGesturesAndroidTest {
    private val fixtureService = AtomicReference<OverlayService?>()

    @Test
    fun realPillGesturesKeepEditsAndProfilesAcrossPauseThenSaveBeforeANewSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("gesture fixture requires the isolated meeting prototype", BuildConfig.MEETING_PROTOTYPE)
        assertEquals("com.uhama.whisperpin.meetingtest", target.packageName)
        assertEquals(
            "microphone permission must be granted externally around this runner",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            target.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )
        assertTrue("overlay permission must be granted externally around this runner", Settings.canDrawOverlays(target))

        val preferences = target.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        val previousOnboarding = preferences.all["onb_complete"]
        val previousTheme = preferences.all["theme_mode"]
        val coordinator = TranscriptionModeCoordinator.process(target)
        val previousMode = coordinator.snapshot().mode
        val beforeModeChange = coordinator.snapshot()
        assertEquals("the fixture starts without a recording run", null, beforeModeChange.activeRunMode)
        assertFalse("a poisoned process cannot safely run this fixture", beforeModeChange.poisoned)

        val fixtureId = UUID.randomUUID().toString()
        val fixtureRoot = File(target.cacheDir, "meeting-overlay-gestures-$fixtureId")
        val draftDirectory = File(target.filesDir, "meeting-gesture-draft-$fixtureId")
        val draftFile = File(draftDirectory, "meeting.json")
        var modelStoreForCleanup: MeetingModelStore? = null
        var modelRootForCleanup: File? = null
        var microphoneFactoryForCleanup: FakeMicrophoneFactory? = null
        var serviceStartAttempted = false
        var modeChanged = false
        var testFactoryInstalled = false
        var serviceShutdownSafe = false
        var primaryFailure: Throwable? = null
        val createdFixtureNoteIds = linkedSetOf<String>()
        var scenario: ActivityScenario<MainActivity>? = null
        var captureDirectoryForDiagnostics: File? = null
        val uiAutomation = instrumentation.uiAutomation
        val originalUiAutomationFlags = requireNotNull(uiAutomation.serviceInfo).flags
        var interactiveWindowsFlagConfigured = false
        try {
            val interactiveWindowServiceInfo = requireNotNull(uiAutomation.serviceInfo).apply {
                flags = originalUiAutomationFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            interactiveWindowsFlagConfigured = true
            uiAutomation.setServiceInfo(interactiveWindowServiceInfo)
            check(coordinator.changeMode(TranscriptionMode.MEETING)) {
                "meeting mode is admitted before the visible Activity starts"
            }
            modeChanged = true
            check(fixtureRoot.mkdirs() || fixtureRoot.isDirectory)
            check(draftDirectory.mkdirs() || draftDirectory.isDirectory)

            val (modelStore, modelRoot, networkCalls) = createReadyFixtureStore(fixtureRoot)
            modelStoreForCleanup = modelStore
            modelRootForCleanup = modelRoot
            val modelReady = AtomicReference<MeetingModelPaths?>()
            assertTrue("the two tiny fixture artifacts pass the real store's SHA checks", awaitCondition {
                (modelStore.currentState as? MeetingModelStoreState.Ready)?.paths?.also(modelReady::set) != null ||
                    modelStore.currentState is MeetingModelStoreState.Error
            })
            assertNotNull("the local fixture pair is ready", modelReady.get())

            val sessionFactory = FakeSessionFactory()
            val microphoneFactory = FakeMicrophoneFactory()
            microphoneFactoryForCleanup = microphoneFactory
            val admission = MeetingNativeAdmission(
                coordinator = coordinator,
                closeResidentDictation = { CompletableFuture.completedFuture(Unit) },
            )
            val reservation = ObservedReservationPort(admission)
            val overrides = OverlayService.MeetingTestOverrides(
                draftOwnership = MeetingDraftOwnership(),
                draftFile = draftFile,
                modelStore = modelStore,
                modelAvailability = MeetingModelAvailabilityPort { MeetingModelAvailability.READY },
                reservation = reservation,
                sessionFactory = sessionFactory,
                microphoneFactory = microphoneFactory,
            )
            val factoryInstall = OverlayService.setMeetingTestOverridesFactoryForTest { service ->
                fixtureService.set(service)
                overrides
            }
            testFactoryInstalled = factoryInstall
            assertTrue("the one-shot service fixture factory installs before service creation", factoryInstall)

            val originalMeetingNoteIds = readPersistedMeetingNoteIds(target)
            val captureDirectory = File(
                target.getExternalFilesDir(null) ?: target.filesDir,
                "meeting-overlay-gestures-$fixtureId",
            ).apply { check(mkdirs() || isDirectory) }
            captureDirectoryForDiagnostics = captureDirectory
            check(preferences.edit().putBoolean("onb_complete", true).commit())
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity ->
                serviceStartAttempted = true
                activity.startForegroundService(
                    Intent(activity, OverlayService::class.java).setAction(OverlayService.ACTION_ARM_MIC),
                )
            }
            assertTrue("the factory receives the real OverlayService instance", awaitCondition {
                fixtureService.get() != null
            })
            assertTrue("the meeting fixtures are installed once during service creation", fixtureService.get() != null)
            assertTrue("UiAutomation returns interactive windows after opting in", awaitCondition {
                accessibleWindowSummary(instrumentation).isNotEmpty()
            })
            awaitBubble(instrumentation)

            swipeBubble(instrumentation, target, -dp(target, 150))
            awaitVisibleText(instrumentation, "Ouvrir la réunion")
            assertEquals("opening the mode menu does not create a meeting session", 0, sessionFactory.sessions.size)
            assertEquals("opening the mode menu does not start a microphone", 0, microphoneFactory.startCount)
            capture(instrumentation, activeScenario, captureDirectory, "01-menu-no-start.png", "Ouvrir la réunion")
            tapText(instrumentation, "Ouvrir la réunion")
            awaitVisibleText(instrumentation, "Prête")
            assertEquals("opening the panel still does not start a session", 0, sessionFactory.sessions.size)
            assertEquals("opening the panel still does not start a microphone", 0, microphoneFactory.startCount)

            tapBubble(instrumentation)
            awaitConditionOrFail("one tap starts one fake native session and one fake microphone") {
                sessionFactory.sessions.size == 1 && microphoneFactory.startCount == 1
            }
            awaitVisibleText(instrumentation, "Écoute en cours")
            assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
            assertFalse("the real coordinator rejects a mode switch while this run owns the lease",
                coordinator.changeMode(TranscriptionMode.DICTATION))

            val firstSession = sessionFactory.sessions.single()
            val firstLiveState = requireNotNull(awaitMeetingRecordingState(instrumentation) {
                it.phase == MeetingRecordingPhase.LISTENING
            }) {
                "the service retains the live meeting controller after start"
            }
            assertEquals(MeetingRecordingPhase.LISTENING, firstLiveState.phase)
            val firstSessionId = firstLiveState.document.sessionId
            assertFalse("the unique fixture session id did not exist before this test",
                originalMeetingNoteIds.contains(firstSessionId))
            createdFixtureNoteIds += firstSessionId
            firstSession.emit(utteranceId = 1L, revision = 1L, text = "La séance aura lieu mardi.", channel = 1, startMs = 1_000L)
            awaitVisibleText(instrumentation, "La séance aura lieu mardi.")
            firstSession.emit(utteranceId = 2L, revision = 1L, text = "Je prépare les documents.", channel = 2, startMs = 4_000L)
            awaitVisibleText(instrumentation, "Je prépare les documents.")
            firstSession.emit(utteranceId = 3L, revision = 1L, text = "Nous vérifierons ensemble le matériel.", channel = 1, startMs = 7_000L)
            awaitVisibleText(instrumentation, "Nous vérifierons ensemble le matériel.")

            tapTextContains(instrumentation, "Ouvrir les 2 intervenants")
            tapText(instrumentation, "Personne 1")
            tapText(instrumentation, "Renommer")
            val renameDialog = AtomicReference<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>?>(null)
            awaitConditionOrFail("the exact rename dialog exposes its own title, editor, and confirm button") {
                val found = findRenameDialogControls(instrumentation)
                if (found == null) {
                    false
                } else if (renameDialog.compareAndSet(null, found)) {
                    true
                } else {
                    found.first.recycle()
                    found.second.recycle()
                    true
                }
            }
            val (renameEditor, renameConfirm) = requireNotNull(renameDialog.get())
            try {
                tapBounds(instrumentation, accessibilityBounds(renameEditor))
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Sophie")
                }
                assertTrue("the exact rename dialog editor accepts the participant name",
                    renameEditor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            } finally {
                renameEditor.recycle()
                renameConfirm.recycle()
            }
            val confirmedDialog = AtomicReference<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>?>(null)
            awaitConditionOrFail("the exact rename dialog reports the freshly edited name") {
                val found = findRenameDialogControls(instrumentation)
                when {
                    found == null -> false
                    found.first.text?.toString()?.trim() == "Sophie" -> {
                        if (confirmedDialog.compareAndSet(null, found)) true else {
                            found.first.recycle()
                            found.second.recycle()
                            true
                        }
                    }
                    else -> {
                        found.first.recycle()
                        found.second.recycle()
                        false
                    }
                }
            }
            val (confirmedEditor, confirmedButton) = requireNotNull(confirmedDialog.get())
            try {
                assertEquals("the freshly reacquired dialog editor contains the exact name", "Sophie",
                    confirmedEditor.text?.toString()?.trim())
                assertTrue("the exact rename dialog confirms the edited participant name",
                    confirmedButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            } finally {
                confirmedEditor.recycle()
                confirmedButton.recycle()
            }
            awaitConditionOrFail("the rename dialog closes after confirmation") {
                !isRenameDialogVisible(instrumentation)
            }
            val renamedState = requireNotNull(awaitMeetingRecordingState(instrumentation) { state ->
                state.document.participants.any { it.channel == 1 && it.name == "Sophie" }
            }) { "channel 1 is renamed in the actual meeting state" }
            assertEquals("the confirmed name belongs to participant channel 1", "Sophie",
                renamedState.document.participants.single { it.channel == 1 }.name)

            val firstSpeechEditor = requireNotNull(
                findSpeechEditorByExactText(
                    instrumentation = instrumentation,
                    speakerName = "Sophie",
                    expectedText = "La séance aura lieu mardi.",
                ),
            ) { "the visible real editor belongs to Sophie’s exact Tuesday utterance" }
            try {
                assertEquals(
                    "the selected speech editor is the first matching Sophie turn",
                    "La séance aura lieu mardi.",
                    firstSpeechEditor.text?.toString()?.trim(),
                )
                assertEquals(
                    "the selected editor exposes the expected participant",
                    "Paroles de Sophie",
                    firstSpeechEditor.contentDescription?.toString()?.trim(),
                )
                tapBounds(instrumentation, accessibilityBounds(firstSpeechEditor))
            } finally {
                firstSpeechEditor.recycle()
            }
            setEditText(
                instrumentation,
                "La séance aura lieu jeudi.",
                descriptionContains = "Paroles de Sophie",
                expectedExactText = "La séance aura lieu mardi.",
            )
            firstSession.emit(
                utteranceId = 1L,
                revision = 2L,
                text = "La séance aura lieu mardi matin.",
                channel = 1,
                startMs = 1_000L,
            )
            awaitVisibleText(instrumentation, "La séance aura lieu jeudi.")
            awaitVisibleText(instrumentation, "Sophie")
            assertEquals("late revisions continue in the original native session", 1, sessionFactory.sessions.size)
            val durablyEditedDraft = awaitDurableEditedDraft(draftFile, firstSessionId)
            assertEquals("speech edits become durable after utterances are received", firstSessionId,
                durablyEditedDraft.sessionId)
            assertEquals("the persisted first utterance keeps its manual edit",
                "La séance aura lieu jeudi.",
                durablyEditedDraft.turns.single { it.utteranceId == 1L }.editedText)
            capture(instrumentation, activeScenario, captureDirectory, "02-renamed-and-edited-turns.png",
                "La séance aura lieu jeudi.")

            swipeBubbleWhileHeld(instrumentation, target, dp(target, 120)) {
                awaitVisibleText(instrumentation, "Mettre en pause pour enregistrer")
                assertEquals("a downward listening preview does not stop the microphone", 0, microphoneFactory.stopCount)
                assertEquals("a downward listening preview does not checkpoint", 0, firstSession.checkpointCount)
                assertEquals("a downward listening preview never finishes the session", 0, firstSession.finishCount)
            }
            awaitVisibleText(instrumentation, "Écoute en cours")
            assertEquals(0, microphoneFactory.stopCount)
            assertEquals(0, firstSession.finishCount)

            tapBubble(instrumentation)
            awaitVisibleText(instrumentation, "Pause en cours")
            assertEquals("the first tap requests exactly one recorder stop", 1, microphoneFactory.stopCount)
            assertFalse("the first stop remains held for the PAUSING interaction", microphoneFactory.firstStop.isDone)
            tapBubble(instrumentation)
            SystemClock.sleep(100L)
            assertEquals("a second tap during PAUSING cannot start another microphone", 1, microphoneFactory.startCount)
            assertEquals("a second tap during PAUSING cannot issue another stop", 1, microphoneFactory.stopCount)
            assertEquals("a second tap during PAUSING cannot checkpoint or finish early", 0, firstSession.checkpointCount)
            assertEquals("a second tap during PAUSING cannot finish", 0, firstSession.finishCount)
            microphoneFactory.firstStop.complete(Unit)
            awaitVisibleText(instrumentation, "En pause")
            awaitConditionOrFail("pause checkpoints after stopAndJoin") { firstSession.checkpointCount == 1 }
            assertEquals("pause retains the existing native session", 1, sessionFactory.sessions.size)

            swipeBubble(instrumentation, target, dp(target, 120))
            awaitVisibleText(instrumentation, "Enregistrer la transcription ?")
            assertEquals("the first paused downward gesture opens one confirmation", 1,
                visibleLabelCount(instrumentation, "Enregistrer la transcription ?"))
            tapBubble(instrumentation)
            SystemClock.sleep(100L)
            assertEquals("tapping beneath the open confirmation cannot resume the microphone", 1,
                microphoneFactory.startCount)
            assertEquals("tapping beneath the open confirmation cannot finish the session", 0,
                firstSession.finishCount)
            swipeBubble(instrumentation, target, dp(target, 120))
            assertEquals("taps and repeated swipes cannot stack confirmations or resume", 1,
                visibleLabelCount(instrumentation, "Enregistrer la transcription ?"))
            assertEquals("the confirmation leaves the microphone stopped", 1, microphoneFactory.startCount)
            capture(instrumentation, activeScenario, captureDirectory, "03-paused-confirmation.png",
                "Enregistrer la transcription ?")
            tapText(instrumentation, "Continuer la réunion")
            awaitVisibleText(instrumentation, "En pause")
            assertEquals("Continuer keeps the original session paused", 1, microphoneFactory.startCount)
            assertEquals(1, sessionFactory.sessions.size)

            tapBubble(instrumentation)
            awaitVisibleText(instrumentation, "Écoute en cours")
            awaitConditionOrFail("resume creates a new microphone but keeps the same native session") {
                microphoneFactory.startCount == 2
            }
            assertEquals("resume retains the exact session object", firstSession, sessionFactory.sessions.single())
            assertEquals("resume retains the two learned speaker profiles", 2, firstSession.latestSpeakerChannels.size)

            tapBubble(instrumentation)
            awaitVisibleText(instrumentation, "En pause")
            awaitConditionOrFail("the resumed recorder is stopped for the final pause") {
                microphoneFactory.stopCount == 2 && firstSession.checkpointCount == 2
            }
            swipeBubble(instrumentation, target, dp(target, 120))
            awaitVisibleText(instrumentation, "Enregistrer la transcription ?")
            tapText(instrumentation, "Enregistrer et terminer")
            awaitVisibleText(instrumentation, "Réunion terminée")
            awaitConditionOrFail("the final pause finishes the native session exactly once") {
                firstSession.finishCount == 1 && firstSession.closed.isDone
            }
            val savedNote = awaitMeetingNote(target, firstSessionId)
            val savedDocument = requireNotNull(savedNote.meeting)
            assertTrue("the durable library note is structured and finished", savedDocument.finished)
            assertEquals("the first profile keeps its manual name", "Sophie",
                savedDocument.participants.single { it.channel == 1 }.name)
            assertEquals("speaker 2 remains in the same document", 2, savedDocument.participants.size)
            val savedFirstTurn = savedDocument.turns.single { it.utteranceId == 1L }
            val returningTurn = savedDocument.turns.single { it.utteranceId == 3L }
            assertTrue("the ASR revision remains available as the recognized source",
                savedFirstTurn.recognizedText.contains("mardi"))
            assertEquals("the manual Thursday edit survives the late revision",
                "La séance aura lieu jeudi.", savedFirstTurn.editedText)
            assertEquals("the returning first speaker remains assigned", "Sophie",
                savedDocument.participants.single { it.id == returningTurn.automaticParticipantId }.name)
            assertEquals("the returning utterance resolves to the same profile as the first",
                savedFirstTurn.automaticParticipantId, returningTurn.automaticParticipantId)
            assertEquals("the reservation safely releases after persistence", 1, reservation.releaseCount)
            assertEquals("the active run ends only after native and microphone closure", null,
                coordinator.snapshot().activeRunMode)
            assertEquals("the fixture model store never performs HTTP", 0, networkCalls.get())
            capture(instrumentation, activeScenario, captureDirectory, "04-finished-structured-note.png",
                "Réunion terminée")

            tapBubble(instrumentation)
            awaitVisibleText(instrumentation, "Écoute en cours")
            awaitConditionOrFail("the next tap creates a fresh native session and microphone") {
                sessionFactory.sessions.size == 2 && microphoneFactory.startCount == 3
            }
            assertTrue("a new session has a different run id",
                firstSession.runId != sessionFactory.sessions.last().runId)
            assertTrue("the previous structured note remains durable while the next session starts",
                readPersistedMeetingNoteIds(target).contains(savedNote.id))
            val nextLiveState = requireNotNull(awaitMeetingRecordingState(instrumentation) {
                it.phase == MeetingRecordingPhase.LISTENING && it.document.sessionId != savedDocument.sessionId
            }) {
                "the new run has a live in-memory meeting controller"
            }
            assertEquals("a tap after finish creates a listening session", MeetingRecordingPhase.LISTENING,
                nextLiveState.phase)
            assertTrue("the new session gets a new document identity",
                nextLiveState.document.sessionId != savedDocument.sessionId)
            assertTrue("the new session starts with no profiles", nextLiveState.document.participants.isEmpty())
            capture(instrumentation, activeScenario, captureDirectory, "05-new-session-empty-profiles.png",
                "Écoute en cours")
            assertEquals("the real coordinator still owns the fresh meeting run", TranscriptionMode.MEETING,
                coordinator.snapshot().activeRunMode)
            assertEquals("the menu/profile/edit flow made no request to the network", 0, networkCalls.get())

            android.util.Log.i(TAG, "status=pass captureDir=${captureDirectory.absolutePath} " +
                "sessions=${sessionFactory.sessions.size} microphoneStarts=${microphoneFactory.startCount} " +
                "microphoneStops=${microphoneFactory.stopCount} nativeFinishes=${sessionFactory.finishCount} " +
                "noteId=${savedNote.id} fixtureModelsVerified=true externalPermissionsUnmodified=true")
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching {
                captureFailureDiagnostics(instrumentation, target, captureDirectoryForDiagnostics, fixtureId)
            }.onFailure { diagnosticFailure ->
                failure.addSuppressed(AssertionError("numeric overlay failure diagnostics unavailable", diagnosticFailure))
            }
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(label: String, action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    cleanupFailures += AssertionError("fixture cleanup failed: $label", failure)
                }
            }

            if (testFactoryInstalled) {
                cleanup("clear pending test factory") { OverlayService.clearMeetingTestOverridesFactoryForTest() }
            }
            cleanup("release controlled microphone stop") { microphoneFactoryForCleanup?.firstStop?.complete(Unit) }
            if (serviceStartAttempted) {
                cleanup("stop fixture service") { target.stopService(Intent(target, OverlayService::class.java)) }
                serviceShutdownSafe = runCatching {
                    awaitStableCondition {
                        coordinator.snapshot().activeRunMode == null &&
                            pillFieldIsCleared(InstrumentationRegistry.getInstrumentation())
                    }
                }.getOrDefault(false)
                if (!serviceShutdownSafe) {
                    cleanupFailures += AssertionError("fixture cleanup failed: safe service shutdown was not confirmed")
                }
            } else {
                serviceShutdownSafe = true
            }
            cleanup("close visible Activity") { scenario?.close() }
            if (interactiveWindowsFlagConfigured) cleanup("restore UIAutomation service flags") {
                val restoredServiceInfo = requireNotNull(uiAutomation.serviceInfo).apply {
                    flags = originalUiAutomationFlags
                }
                uiAutomation.setServiceInfo(restoredServiceInfo)
            }
            if (modeChanged) cleanup("restore transcription mode") {
                check(coordinator.changeMode(previousMode)) { "the transcription mode must be restored after service shutdown" }
            }
            cleanup("restore preferences") {
                val restore = preferences.edit()
                when (previousOnboarding) {
                    null -> restore.remove("onb_complete")
                    is Boolean -> restore.putBoolean("onb_complete", previousOnboarding)
                    else -> error("unexpected onboarding preference type")
                }
                when (previousTheme) {
                    null -> restore.remove("theme_mode")
                    is String -> restore.putString("theme_mode", previousTheme)
                    else -> error("unexpected theme preference type")
                }
                check(restore.commit())
                check(preferences.all["theme_mode"] == previousTheme) { "fixture leaves theme unchanged" }
            }
            if (serviceShutdownSafe) {
                cleanup("remove only fixture-created meeting notes") {
                    val notes = TranscriptNotes(AndroidTranscriptNoteStorage(target))
                    createdFixtureNoteIds.forEach(notes::delete)
                    val remainingIds = readPersistedMeetingNoteIds(target)
                    check(createdFixtureNoteIds.none(remainingIds::contains)) {
                        "fixture-created notes remain after cleanup"
                    }
                }
                cleanup("shut down fixture model store") { modelStoreForCleanup?.shutdownForTests() }
                cleanup("delete fixture model files") { modelRootForCleanup?.deleteRecursively() }
                cleanup("delete fixture draft and sidecars") {
                    draftFile.delete()
                    File(draftFile.path + ".bak").delete()
                    File(draftFile.path + ".new").delete()
                    draftDirectory.delete()
                    fixtureRoot.deleteRecursively()
                }
            } else {
                cleanupFailures += AssertionError(
                    "fixture resources retained because the service may still own the runtime",
                )
            }
            fixtureService.set(null)
            if (primaryFailure != null) {
                cleanupFailures.forEach { primaryFailure!!.addSuppressed(it) }
            } else if (cleanupFailures.isNotEmpty()) {
                throw AssertionError("meeting overlay fixture cleanup failed").apply {
                    cleanupFailures.forEach { addSuppressed(it) }
                }
            }
        }
    }

    private fun createReadyFixtureStore(
        fixtureRoot: File,
    ): Triple<MeetingModelStore, File, AtomicInteger> {
        val networkCalls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            networkCalls.incrementAndGet()
            throw IOException("network disabled by meeting overlay gesture fixture")
        }.build()
        val asrBytes = "meeting-overlay-fixture-asr-v1".toByteArray(Charsets.UTF_8)
        val diarBytes = "meeting-overlay-fixture-diar-v1".toByteArray(Charsets.UTF_8)
        val catalog = MeetingModelCatalog(
            packageName = "meeting-overlay-fixture",
            version = "v1",
            asr = artifact("asr", "asr/model.bin", "https://fixture.invalid/asr.bin", asrBytes),
            diarization = artifact("diar", "diar/model.bin", "https://fixture.invalid/diar.bin", diarBytes),
        )
        val modelRoot = File(fixtureRoot, "models").apply { check(mkdirs() || isDirectory) }
        val packageDirectory = File(modelRoot, catalog.packageDirectoryName(UUID.randomUUID().toString()))
        val asrFile = File(packageDirectory, catalog.asr.relativePath)
        val diarFile = File(packageDirectory, catalog.diarization.relativePath)
        check(asrFile.parentFile!!.mkdirs() || asrFile.parentFile!!.isDirectory)
        check(diarFile.parentFile!!.mkdirs() || diarFile.parentFile!!.isDirectory)
        asrFile.writeBytes(asrBytes)
        diarFile.writeBytes(diarBytes)
        check(sha256(asrFile.readBytes()) == catalog.asr.sha256)
        check(sha256(diarFile.readBytes()) == catalog.diarization.sha256)
        val store = MeetingModelStore(filesDirectory = modelRoot, catalog = catalog, httpClient = client)
        store.refresh()
        return Triple(store, modelRoot, networkCalls)
    }

    private fun artifact(id: String, path: String, url: String, bytes: ByteArray) = MeetingModelArtifact(
        id = id,
        relativePath = path,
        url = url,
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun readPersistedMeetingNoteIds(context: Context): Set<String> =
        TranscriptNotes(AndroidTranscriptNoteStorage(context)).all()
            .mapNotNull { note -> note.meeting?.sessionId }
            .toSet()

    private fun awaitMeetingNote(context: Context, sessionId: String): TranscriptNote {
        val result = AtomicReference<TranscriptNote?>()
        awaitConditionOrFail("a new structured meeting note reaches persistent storage") {
            result.set(
                TranscriptNotes(AndroidTranscriptNoteStorage(context)).all()
                    .firstOrNull { note -> note.meeting?.sessionId == sessionId },
            )
            result.get() != null
        }
        return requireNotNull(result.get())
    }

    private fun awaitDraft(file: File): MeetingDocument {
        val document = AtomicReference<MeetingDocument?>()
        awaitConditionOrFail("the fresh session writes its initial document snapshot") {
            document.set((MeetingDraftStore(file).load() as? MeetingDocumentRead.Ready)?.document)
            document.get() != null
        }
        return requireNotNull(document.get())
    }

    private fun awaitDurableEditedDraft(file: File, sessionId: String): MeetingDocument {
        val document = AtomicReference<MeetingDocument?>()
        try {
            awaitConditionOrFail("the edited turn is published by the draft writer") {
                val candidate = (MeetingDraftStore(file).load() as? MeetingDocumentRead.Ready)?.document
                if (candidate != null) document.set(candidate)
                if (candidate != null && candidate.sessionId == sessionId &&
                    candidate.participants.any { it.name == "Sophie" } &&
                    candidate.turns.singleOrNull { it.utteranceId == 1L }?.editedText == "La séance aura lieu jeudi."
                ) {
                    true
                } else {
                    false
                }
            }
        } catch (failure: AssertionError) {
            val liveState = runCatching {
                val service = fixtureService.get() ?: return@runCatching null
                val result = AtomicReference<MeetingRecordingState?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val field = OverlayService::class.java.getDeclaredField("meetingRecordingController")
                        .apply { isAccessible = true }
                    result.set((field.get(service) as? MeetingRecordingController)?.state)
                }
                result.get()
            }.getOrNull()
            val observed = document.get()
            val turnFlags = observed?.turns?.joinToString(",") { turn ->
                "${turn.utteranceId}:edited=${turn.editedText != null}"
            } ?: "none"
            android.util.Log.e(
                TAG,
                "DRAFT_TIMEOUT sessionId=$sessionId observedSession=${observed?.sessionId ?: "none"} " +
                    "phase=${liveState?.phase ?: "unavailable"} " +
                    "captureActive=${liveState?.captureActive ?: "unavailable"} turnFlags=$turnFlags",
            )
            throw failure
        }
        return requireNotNull(document.get())
    }

    private fun awaitMeetingRecordingState(
        instrumentation: android.app.Instrumentation,
        predicate: (MeetingRecordingState) -> Boolean,
    ): MeetingRecordingState? {
        val state = AtomicReference<MeetingRecordingState?>()
        val reached = awaitCondition {
            val service = fixtureService.get() ?: return@awaitCondition false
            instrumentation.runOnMainSync {
                val field = OverlayService::class.java.getDeclaredField("meetingRecordingController")
                    .apply { isAccessible = true }
                val controller = field.get(service) as? MeetingRecordingController
                state.set(controller?.state)
            }
            state.get()?.let(predicate) == true
        }
        return if (reached) state.get() else null
    }

    private class FakeSessionFactory : MeetingSessionFactoryPort {
        val sessions = CopyOnWriteArrayList<FakeSession>()
        val finishCount: Int get() = sessions.sumOf { it.finishCount }

        override fun start(
            runId: String,
            language: String,
            onReady: () -> Unit,
            onUpdate: (MeetingHypothesis) -> Unit,
            onFailure: (String) -> Unit,
        ): MeetingSession {
            val session = FakeSession(runId, onUpdate)
            sessions += session
            Handler(Looper.getMainLooper()).postDelayed(Runnable { onReady() }, 20L)
            return session
        }
    }

    private class FakeSession(
        val runId: String,
        private val onUpdate: (MeetingHypothesis) -> Unit,
    ) : MeetingSession {
        override val closed = CompletableFuture<Unit>()
        private val checkpoints = AtomicInteger()
        private val finishes = AtomicInteger()
        private val cancels = AtomicInteger()
        private val pcmBlocks = AtomicInteger()
        private val speakerChannels = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

        val checkpointCount: Int get() = checkpoints.get()
        val finishCount: Int get() = finishes.get()
        val latestSpeakerChannels: Set<Int> get() = synchronized(speakerChannels) { speakerChannels.toSet() }
        override val queuedAudioMs: Long get() = 0L

        override fun acceptPcm16(buffer: ByteArray, length: Int): Boolean {
            if (length < 0 || length > buffer.size || length % 2 != 0) return false
            pcmBlocks.incrementAndGet()
            return true
        }

        override fun checkpoint(): CompletableFuture<Unit> {
            checkpoints.incrementAndGet()
            return CompletableFuture.completedFuture(Unit)
        }

        override fun finish() {
            finishes.incrementAndGet()
            closed.complete(Unit)
        }

        override fun cancel() {
            cancels.incrementAndGet()
            closed.complete(Unit)
        }

        override fun close() {
            closed.complete(Unit)
        }

        fun emit(utteranceId: Long, revision: Long, text: String, channel: Int, startMs: Long) {
            val words = tokenize(text).mapIndexed { index, word ->
                val start = startMs + index * 160L
                MeetingWord(word, start, start + 120L, channel)
            }
            val processedThrough = startMs + words.size * 160L + 40L
            speakerChannels += channel
            onUpdate(
                MeetingHypothesis(
                    runId = runId,
                    utteranceId = utteranceId,
                    revision = revision,
                    words = words,
                    transcript = text,
                    isFinal = true,
                    stableSpeakerThroughMs = processedThrough,
                    audioProcessedMs = processedThrough,
                ),
            )
        }

        private fun tokenize(text: String): List<String> =
            Regex("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}\\s]").findAll(text).map { it.value }.toList()
    }

    private class FakeMicrophoneFactory : MeetingMicrophoneFactoryPort {
        val microphones = CopyOnWriteArrayList<FakeMicrophone>()
        val firstStop = CompletableFuture<Unit>()
        val startCount: Int get() = microphones.sumOf(FakeMicrophone::startCount)
        val stopCount: Int get() = microphones.sumOf(FakeMicrophone::stopCount)

        override fun create(): MeetingMicrophonePort {
            val microphone = FakeMicrophone(this, controlled = microphones.isEmpty())
            microphones += microphone
            return microphone
        }
    }

    private class FakeMicrophone(
        private val owner: FakeMicrophoneFactory,
        private val controlled: Boolean,
    ) : MeetingMicrophonePort {
        private val starts = AtomicInteger()
        private val stops = AtomicInteger()
        val startCount: Int get() = starts.get()
        val stopCount: Int get() = stops.get()

        override fun start(onPcm16: (ByteArray, Int) -> Boolean, onFailure: (String) -> Unit): Boolean {
            starts.incrementAndGet()
            // Synthetic zeroed PCM exercises only the service/controller queue plumbing.
            return onPcm16(ByteArray(320), 320)
        }

        override fun stopAndJoin(): CompletableFuture<Unit> {
            stops.incrementAndGet()
            return if (controlled) owner.firstStop else CompletableFuture.completedFuture(Unit)
        }
    }

    private class ObservedReservationPort(
        private val delegate: MeetingNativeReservationPort,
    ) : MeetingNativeReservationPort {
        private val requests = AtomicInteger()
        private val releases = AtomicInteger()
        private val poisons = AtomicInteger()
        val releaseCount: Int get() = releases.get()
        val requestCount: Int get() = requests.get()
        val poisonCount: Int get() = poisons.get()

        override fun request(runId: String): MeetingNativeReservationRequest {
            requests.incrementAndGet()
            val request = delegate.request(runId)
            val lease = CompletableFuture<MeetingNativeRuntimeLease>()
            request.lease.whenComplete { granted, failure ->
                if (failure != null) lease.completeExceptionally(failure)
                else lease.complete(ObservedLease(requireNotNull(granted), releases, poisons))
            }
            return object : MeetingNativeReservationRequest {
                override val lease: CompletableFuture<MeetingNativeRuntimeLease> = lease
                override fun cancel() = request.cancel()
            }
        }
    }

    private class ObservedLease(
        private val delegate: MeetingNativeRuntimeLease,
        private val releases: AtomicInteger,
        private val poisons: AtomicInteger,
    ) : MeetingNativeRuntimeLease {
        override fun isCurrentAndUsable(): Boolean = delegate.isCurrentAndUsable()
        override fun release() {
            releases.incrementAndGet()
            delegate.release()
        }
        override fun poison() {
            poisons.incrementAndGet()
            delegate.poison()
        }
    }

    private fun awaitBubble(instrumentation: android.app.Instrumentation) {
        val visible = awaitCondition {
            actualPillBoundsInScreen(instrumentation) != null
        }
        assertTrue(
            "the real floating pill is visible in its service window; ${pillBoundsDiagnostic(instrumentation)}",
            visible,
        )
    }

    private fun tapBubble(instrumentation: android.app.Instrumentation) {
        val bounds = requireNotNull(actualPillBoundsInScreen(instrumentation)) {
            "the actual attached pill view supplies touch bounds"
        }
        tapBounds(instrumentation, bounds)
    }

    private fun swipeBubble(
        instrumentation: android.app.Instrumentation,
        context: Context,
        deltaY: Int,
    ) = swipeBubbleWhileHeld(instrumentation, context, deltaY) {}

    private fun swipeBubbleWhileHeld(
        instrumentation: android.app.Instrumentation,
        context: Context,
        deltaY: Int,
        whileHeld: () -> Unit,
    ) {
        val bounds = requireNotNull(actualPillBoundsInScreen(instrumentation)) {
            "the actual attached pill view supplies swipe bounds"
        }
        val point = Point(bounds.centerX(), bounds.centerY())
        val size = Point()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(size)
        val endY = (point.y + deltaY).coerceIn(2, size.y - 2)
        injectSwipe(instrumentation, point.x, point.y, point.x, endY, whileHeld)
    }

    private fun tapText(instrumentation: android.app.Instrumentation, label: String) {
        awaitVisibleLabel(instrumentation, label)
        val node = requireNotNull(findVisibleNode(instrumentation) { candidate -> nodeHasExactLabel(candidate, label) }) {
            "visible UI exposes '$label'"
        }
        try {
            tapBounds(instrumentation, accessibilityBounds(node))
        } finally {
            node.recycle()
        }
    }

    private fun tapTextContains(instrumentation: android.app.Instrumentation, fragment: String) {
        awaitVisibleText(instrumentation, fragment)
        val node = requireNotNull(findVisibleNode(instrumentation) { candidate ->
            nodeHasLabelFragment(candidate, fragment)
        }) { "visible UI exposes an accessibility label containing '$fragment'" }
        try {
            tapBounds(instrumentation, accessibilityBounds(node))
        } finally {
            node.recycle()
        }
    }

    private fun tapNode(instrumentation: android.app.Instrumentation, descriptionFragment: String) {
        val node = requireNotNull(findVisibleNode(instrumentation) { candidate ->
            candidate.contentDescription?.toString()?.contains(descriptionFragment, ignoreCase = true) == true
        }) { "visible UI exposes '$descriptionFragment'" }
        try {
            tapBounds(instrumentation, accessibilityBounds(node))
        } finally {
            node.recycle()
        }
    }

    private fun setEditText(
        instrumentation: android.app.Instrumentation,
        text: String,
        descriptionContains: String? = null,
        expectedExactText: String? = null,
    ) {
        val focusedEditor = AtomicReference<AccessibilityNodeInfo?>()
        awaitConditionOrFail("the focused real dialog/turn editor is available to accessibility text input") {
            val candidate = findVisibleNode(instrumentation) { candidate ->
                val editor = candidate.isEditable || candidate.className?.toString()?.contains("EditText") == true
                editor && candidate.isFocused && (descriptionContains == null ||
                    candidate.contentDescription?.toString()?.contains(descriptionContains, ignoreCase = true) == true) &&
                    (expectedExactText == null || candidate.text?.toString()?.trim() == expectedExactText)
            } ?: return@awaitConditionOrFail false
            if (focusedEditor.compareAndSet(null, candidate)) true else {
                candidate.recycle()
                true
            }
        }
        val node = requireNotNull(focusedEditor.get())
        try {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            assertTrue("the real edit field accepts accessibility text input",
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        } finally {
            node.recycle()
        }
    }

    private fun findSpeechEditorByExactText(
        instrumentation: android.app.Instrumentation,
        speakerName: String,
        expectedText: String,
    ): AccessibilityNodeInfo? {
        fun findTarget(): AccessibilityNodeInfo? = findVisibleNode(instrumentation) { node ->
            val editor = node.isEditable || node.className?.toString()?.contains("EditText") == true
            editor && node.contentDescription?.toString()?.trim() == "Paroles de $speakerName" &&
                node.text?.toString()?.trim() == expectedText
        }

        val deadline = SystemClock.uptimeMillis() +
            MAX_SPEECH_EDITOR_SCROLLS * SPEECH_EDITOR_SCROLL_WAIT_MS
        var stableBounds: Rect? = null
        var stableSince = 0L
        var scrollAttempts = 0
        var nextScrollAt = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            val target = findTarget()
            val now = SystemClock.uptimeMillis()
            if (target != null) {
                val bounds = accessibilityBounds(target)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    if (stableBounds == bounds) {
                        if (now - stableSince >= SPEECH_EDITOR_STABLE_MS) return target
                    } else {
                        stableBounds = Rect(bounds)
                        stableSince = now
                    }
                } else {
                    stableBounds = null
                    stableSince = 0L
                }
                target.recycle()
            } else {
                stableBounds = null
                stableSince = 0L
            }

            if (target == null && scrollAttempts < MAX_SPEECH_EDITOR_SCROLLS && now >= nextScrollAt) {
                val scroller = findMeetingPanelScroller(instrumentation)
                if (scroller != null) {
                    try {
                        scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    } finally {
                        scroller.recycle()
                    }
                }
                scrollAttempts++
                nextScrollAt = now + SPEECH_EDITOR_SCROLL_WAIT_MS
            }
            val remainingMs = deadline - SystemClock.uptimeMillis()
            if (remainingMs > 0L) SystemClock.sleep(minOf(SPEECH_EDITOR_POLL_MS, remainingMs))
        }
        return null
    }

    private fun findMeetingPanelScroller(
        instrumentation: android.app.Instrumentation,
    ): AccessibilityNodeInfo? {
        for (window in instrumentation.uiAutomation.windows) {
            val root = try { window.root } catch (_: Throwable) { null } ?: continue
            try {
                val panelMarker = findNodeRecursive(root) { node ->
                    node.isVisibleToUser &&
                        (nodeHasLabelFragment(node, "Écoute en cours") ||
                            nodeHasLabelFragment(node, "Actions de la réunion"))
                }
                if (panelMarker != null) {
                    panelMarker.recycle()
                    findNodeRecursive(root) { node ->
                        node.isVisibleToUser && node.isScrollable &&
                            node.className?.toString()?.contains("RecyclerView", ignoreCase = true) == true
                    }?.let { return it }
                }
            } finally {
                root.recycle()
                window.recycle()
            }
        }
        return null
    }

    private fun findRenameDialogControls(
        instrumentation: android.app.Instrumentation,
    ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        for (window in instrumentation.uiAutomation.windows) {
            val root = try { window.root } catch (_: Throwable) { null } ?: continue
            try {
                val title = findNodeRecursive(root) { node ->
                    node.isVisibleToUser && (
                        node.text?.toString()?.trim() == "Renommer Personne 1" ||
                            node.contentDescription?.toString()?.trim() == "Renommer Personne 1"
                        )
                } ?: continue
                title.recycle()

                val editor = findNodeRecursive(root) { node ->
                    node.isVisibleToUser &&
                        (node.isEditable || node.className?.toString()?.contains("EditText") == true)
                }
                val confirm = findNodeRecursive(root) { node ->
                    node.isVisibleToUser && nodeHasExactLabel(node, "Valider")
                }
                if (editor != null && confirm != null) return editor to confirm
                editor?.recycle()
                confirm?.recycle()
            } finally {
                root.recycle()
                window.recycle()
            }
        }
        return null
    }

    private fun isRenameDialogVisible(instrumentation: android.app.Instrumentation): Boolean =
        findVisibleNode(instrumentation) { node ->
            node.text?.toString()?.trim() == "Renommer Personne 1" ||
                node.contentDescription?.toString()?.trim() == "Renommer Personne 1"
        }?.let { node -> node.recycle(); true } ?: false

    private fun accessibilityBounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun awaitVisibleText(instrumentation: android.app.Instrumentation, label: String) {
        awaitConditionOrFail("visible UI shows '$label'") {
            findVisibleNode(instrumentation) { node -> nodeHasLabelFragment(node, label) }
                ?.let { node -> node.recycle(); true } ?: false
        }
    }

    private fun awaitVisibleLabel(instrumentation: android.app.Instrumentation, label: String) {
        awaitConditionOrFail("visible UI exposes '$label'") {
            findVisibleNode(instrumentation) { node -> nodeHasExactLabel(node, label) }
                ?.let { node -> node.recycle(); true } ?: false
        }
    }

    private fun visibleLabelCount(instrumentation: android.app.Instrumentation, label: String): Int =
        collectVisibleNodes(instrumentation).let { nodes ->
            try {
                nodes.count { node -> nodeHasExactLabel(node, label) }
            } finally {
                nodes.forEach(AccessibilityNodeInfo::recycle)
            }
        }

    /** Reads only this service's own pill field; the returned on-screen bounds drive system touch events. */
    private fun actualPillBoundsInScreen(instrumentation: android.app.Instrumentation): Rect? {
        val service = fixtureService.get() ?: return null
        val result = AtomicReference<Rect?>()
        instrumentation.runOnMainSync {
            val field = OverlayService::class.java.getDeclaredField("pill").apply { isAccessible = true }
            val pill = field.get(service) as? View ?: return@runOnMainSync
            if (!pill.isAttachedToWindow || !pill.isShown || pill.visibility != View.VISIBLE ||
                pill.width <= 0 || pill.height <= 0
            ) return@runOnMainSync
            val visibleLocal = Rect()
            if (!pill.getLocalVisibleRect(visibleLocal) || visibleLocal.isEmpty) return@runOnMainSync
            val location = IntArray(2)
            pill.getLocationOnScreen(location)
            val bounds = Rect(visibleLocal).apply { offset(location[0], location[1]) }
            val screenSize = Point()
            val windowManager = instrumentation.targetContext
                .getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(screenSize)
            if (!bounds.intersect(Rect(0, 0, screenSize.x, screenSize.y)) || bounds.isEmpty) return@runOnMainSync
            result.set(bounds)
        }
        return result.get()
    }

    /** Returns only numeric window metadata so failures never log transcript or view text. */
    private fun accessibleWindowSummary(instrumentation: android.app.Instrumentation): List<String> {
        val windows = instrumentation.uiAutomation.windows
        return try {
            windows.map { window ->
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                "type=${window.type},layer=${window.layer},focused=${window.isFocused},bounds=${bounds.flattenToString()}"
            }
        } finally {
            windows.forEach { window -> window.recycle() }
        }
    }

    private fun captureFailureDiagnostics(
        instrumentation: android.app.Instrumentation,
        context: Context,
        captureDirectory: File?,
        fixtureId: String,
    ) {
        val windows = accessibleWindowSummary(instrumentation)
        android.util.Log.e(TAG, "FAIL_DIAGNOSTIC windowCount=${windows.size} windows=$windows " +
            "pill=${pillBoundsDiagnostic(instrumentation)}")
        val destinationDirectory = captureDirectory ?: File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "meeting-overlay-gestures-$fixtureId",
        ).apply { check(mkdirs() || isDirectory) }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: run {
            android.util.Log.e(TAG, "FAIL_CAPTURE unavailable=true")
            return
        }
        val file = File(destinationDirectory, "failure-diagnostic.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        android.util.Log.e(TAG, "FAIL_CAPTURE path=${file.absolutePath} bytes=${file.length()}")
    }

    /** Numeric-only diagnostics for overlay geometry; never reads UI text or transcript content. */
    private fun pillBoundsDiagnostic(instrumentation: android.app.Instrumentation): String {
        val service = fixtureService.get() ?: return "service=null"
        val result = AtomicReference("pill=null")
        instrumentation.runOnMainSync {
            val field = OverlayService::class.java.getDeclaredField("pill").apply { isAccessible = true }
            val pill = field.get(service) as? View ?: return@runOnMainSync
            val location = IntArray(2)
            pill.getLocationOnScreen(location)
            val visible = Rect()
            val hasVisibleLocal = pill.getLocalVisibleRect(visible)
            result.set(
                "attached=${pill.isAttachedToWindow},shown=${pill.isShown},visibility=${pill.visibility}," +
                    "size=${pill.width}x${pill.height},screen=${location[0]},${location[1]}," +
                    "localVisible=$hasVisibleLocal:${visible.flattenToString()}"
            )
        }
        return result.get()
    }

    private fun actualPillView(instrumentation: android.app.Instrumentation): View? {
        val service = fixtureService.get() ?: return null
        val result = AtomicReference<View?>()
        instrumentation.runOnMainSync {
            val field = OverlayService::class.java.getDeclaredField("pill").apply { isAccessible = true }
            val pill = field.get(service) as? View ?: return@runOnMainSync
            if (pill.isAttachedToWindow && pill.isShown && pill.visibility == View.VISIBLE &&
                pill.width > 0 && pill.height > 0
            ) result.set(pill)
        }
        return result.get()
    }

    private fun pillFieldIsCleared(instrumentation: android.app.Instrumentation): Boolean {
        val service = fixtureService.get() ?: return true
        val result = AtomicReference(false)
        instrumentation.runOnMainSync {
            val field = OverlayService::class.java.getDeclaredField("pill").apply { isAccessible = true }
            result.set(field.get(service) == null)
        }
        return result.get()
    }

    private fun findVisibleNode(
        instrumentation: android.app.Instrumentation,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        for (window in instrumentation.uiAutomation.windows) {
            val root = try { window.root } catch (_: Throwable) { null } ?: continue
            try {
                findNodeRecursive(root) { node -> node.isVisibleToUser && predicate(node) }?.let { return it }
            } finally {
                root.recycle()
                window.recycle()
            }
        }
        return null
    }

    private fun collectVisibleNodes(instrumentation: android.app.Instrumentation): List<AccessibilityNodeInfo> =
        buildList {
            for (window in instrumentation.uiAutomation.windows) {
                val root = try { window.root } catch (_: Throwable) { null } ?: continue
                try { collectNodeRecursive(root, this) } finally {
                    root.recycle()
                    window.recycle()
                }
            }
        }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = try { node.getChild(index) } catch (_: Throwable) { null } ?: continue
            val found = try { findNodeRecursive(child, predicate) } finally { child.recycle() }
            if (found != null) return found
        }
        return null
    }

    private fun collectNodeRecursive(node: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        if (node.isVisibleToUser) output += AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = try { node.getChild(index) } catch (_: Throwable) { null } ?: continue
            try { collectNodeRecursive(child, output) } finally { child.recycle() }
        }
    }

    private fun nodeHasExactLabel(node: AccessibilityNodeInfo, label: String): Boolean =
        node.text?.toString()?.trim()?.equals(label, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.trim()?.equals(label, ignoreCase = true) == true

    private fun nodeHasLabelFragment(node: AccessibilityNodeInfo, fragment: String): Boolean =
        node.text?.toString()?.contains(fragment, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(fragment, ignoreCase = true) == true

    private fun tapBounds(instrumentation: android.app.Instrumentation, bounds: Rect) {
        check(bounds.width() > 0 && bounds.height() > 0) { "a real touch target must have visible bounds" }
        val now = SystemClock.uptimeMillis()
        injectTouch(instrumentation, now, now, MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        SystemClock.sleep(55L)
        injectTouch(instrumentation, now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            bounds.centerX().toFloat(), bounds.centerY().toFloat())
        SystemClock.sleep(70L)
    }

    private fun injectSwipe(
        instrumentation: android.app.Instrumentation,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        whileHeld: () -> Unit,
    ) {
        val downTime = SystemClock.uptimeMillis()
        injectTouch(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat())
        val steps = 8
        repeat(steps) { index ->
            val progress = (index + 1).toFloat() / steps
            SystemClock.sleep(22L)
            injectTouch(
                instrumentation,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                startX + (endX - startX) * progress,
                startY + (endY - startY) * progress,
            )
        }
        whileHeld()
        SystemClock.sleep(70L)
        injectTouch(instrumentation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            endX.toFloat(), endY.toFloat())
        SystemClock.sleep(70L)
    }

    private fun injectTouch(
        instrumentation: android.app.Instrumentation,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue("the system accepts the real overlay touch event", instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun capture(
        instrumentation: android.app.Instrumentation,
        scenario: ActivityScenario<MainActivity>,
        directory: File,
        name: String,
        expectedVisibleOverlayText: String,
    ) {
        awaitVisibleText(instrumentation, expectedVisibleOverlayText)
        val decor = AtomicReference<View>()
        scenario.onActivity { activity -> decor.set(activity.window.decorView) }
        val pill = requireNotNull(actualPillView(instrumentation)) {
            "the attached pill overlay is the frame clock for this real service capture"
        }
        val frames = java.util.concurrent.CountDownLatch(4)
        instrumentation.runOnMainSync {
            val activityRoot = requireNotNull(decor.get())
            check(activityRoot.isAttachedToWindow && activityRoot.isShown)
            check(pill.isAttachedToWindow && pill.isShown)
            activityRoot.invalidate()
            pill.invalidate()
            activityRoot.postOnAnimation {
                frames.countDown()
                activityRoot.postOnAnimation { frames.countDown() }
            }
            pill.postOnAnimation {
                frames.countDown()
                pill.postOnAnimation { frames.countDown() }
            }
        }
        assertTrue("two Activity and two real overlay frames are presented before capture",
            frames.await(3, TimeUnit.SECONDS))
        awaitVisibleText(instrumentation, expectedVisibleOverlayText)
        SystemClock.sleep(120L)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(directory, name)
        try {
            FileOutputStream(file).use { output ->
                assertTrue("the presented frame writes as PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue("the real overlay capture is non-empty", file.length() > 10_000L)
        android.util.Log.i(TAG, "CAPTURE=${file.absolutePath} bytes=${file.length()} " +
            "frames=activity:2,pill-overlay:2 visible=$expectedVisibleOverlayText")
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(8)
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(40L)
        }
        return runCatching(condition).getOrDefault(false)
    }

    private fun awaitStableCondition(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(8)
        var stableSamples = 0
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) {
                stableSamples++
                if (stableSamples >= 4) return true
            } else {
                stableSamples = 0
            }
            SystemClock.sleep(50L)
        }
        return false
    }

    private fun awaitConditionOrFail(message: String, condition: () -> Boolean) {
        assertTrue(message, awaitCondition(condition))
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "MeetingOverlayGesturesTest"
        const val MAX_SPEECH_EDITOR_SCROLLS = 8
        const val SPEECH_EDITOR_SCROLL_WAIT_MS = 750L
        const val SPEECH_EDITOR_POLL_MS = 40L
        const val SPEECH_EDITOR_STABLE_MS = 200L
    }
}
