package com.kafkasl.phonewhisper

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.meeting.MeetingDocumentRead
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingDraftStore
import com.kafkasl.phonewhisper.meeting.MeetingModelCatalog
import com.kafkasl.phonewhisper.meeting.MeetingModelPaths
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPhase
import com.kafkasl.phonewhisper.meeting.MeetingRecordingState
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production model store, admission, JNI engine, AudioRecord, and service wiring. */
@RunWith(AndroidJUnit4::class)
class MeetingOverlayDefaultRuntimeAndroidTest {
    private val observedService = AtomicReference<OverlayService?>()

    @Test
    fun defaultServiceRecordsPausesResumesAndSafelyFinishesWithPinnedModels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        val originalUiAutomationFlags = uiAutomation.serviceInfo.flags
        val target = instrumentation.targetContext
        assertTrue("the real-runtime smoke requires the isolated prototype", BuildConfig.MEETING_PROTOTYPE)
        assertEquals("com.uhama.whisperpin.meetingtest", target.packageName)
        assertEquals(
            "microphone permission must be granted externally for this run",
            PackageManager.PERMISSION_GRANTED,
            target.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )
        assertTrue("overlay permission must be granted externally for this run", Settings.canDrawOverlays(target))

        val instrumentationArguments = InstrumentationRegistry.getArguments()
        val modelGenerationId = requireNotNull(instrumentationArguments.getString(MODEL_GENERATION_ARGUMENT)) {
            "the runner must stage a unique verified production model package and pass its generation id"
        }
        assertTrue(
            "the staged generation id is safe for the production package naming contract",
            modelGenerationId.matches(Regex("[A-Fa-f0-9-]{1,80}")),
        )
        val catalog = MeetingModelCatalog.production
        val modelRoot = File(target.filesDir, MODEL_DIRECTORY_NAME)
        val fixturePackage = File(modelRoot, catalog.packageDirectoryName(modelGenerationId))
        val modelPaths = verifyStagedModelPackage(fixturePackage, catalog)
        assertEquals(fixturePackage.canonicalFile, modelPaths.packageDirectory.canonicalFile)
        val modelStore = MeetingModelStore.shared(target)
        modelStore.refresh()
        awaitConditionOrFail("the production shared store accepts the staged hash-verified package", MODEL_CHECK_TIMEOUT_MS) {
            val ready = modelStore.currentState as? MeetingModelStoreState.Ready
            ready?.paths?.packageDirectory?.canonicalFile == fixturePackage.canonicalFile
        }
        val readyPaths = (modelStore.currentState as? MeetingModelStoreState.Ready)?.paths
        assertNotNull("the package remains available through the production shared store", readyPaths)
        assertEquals(fixturePackage.canonicalFile, requireNotNull(readyPaths).packageDirectory.canonicalFile)

        val standardDraft = File(target.filesDir, STANDARD_DRAFT_RELATIVE_PATH)
        assertFalse(
            "the standard production draft must be absent; this fixture never overwrites or removes one",
            standardDraft.exists(),
        )
        val notesBefore = readAllNotes(target).mapTo(mutableSetOf()) { it.id }
        val preferences = target.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        val hadOnboardingValue = preferences.contains("onb_complete")
        val oldOnboardingValue = preferences.getBoolean("onb_complete", false)
        val coordinator = TranscriptionModeCoordinator.process(target)
        val modeBefore = coordinator.snapshot()
        assertNull("no unrelated runtime lease may be active", modeBefore.activeRunMode)
        assertFalse("a poisoned process cannot safely run the real-runtime smoke", modeBefore.poisoned)
        assertFalse("an earlier service instance must not be reused", isOverlayServiceRunning(target))
        val evidenceDirectory = createGestureEvidenceDirectory(target, modelGenerationId)
        val formats = PostProcessingFormats(target)
        val formatBeforeGesture = formats.selected().id

        var modeChanged = false
        var onboardingChanged = false
        var serviceStartAttempted = false
        var testFactoryInstalled = false
        var safeNativeClose = true
        var scenario: ActivityScenario<MainActivity>? = null
        var controller: MeetingRecordingController? = null
        var sessionId: String? = null
        var runId: String? = null
        var testFailure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()

        try {
            val automationInfo = uiAutomation.serviceInfo
            automationInfo.flags = originalUiAutomationFlags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            uiAutomation.serviceInfo = automationInfo

            if (modeBefore.mode != TranscriptionMode.DICTATION) {
                check(coordinator.changeMode(TranscriptionMode.DICTATION)) {
                    "Dictation mode must be admitted before opening the gesture menu"
                }
                modeChanged = true
            }
            assertEquals("the smoke starts in Dictation", TranscriptionMode.DICTATION, coordinator.snapshot().mode)
            onboardingChanged = true
            check(preferences.edit().putBoolean("onb_complete", true).commit())
            testFactoryInstalled = OverlayService.setMeetingTestOverridesFactoryForTest { service ->
                observedService.set(service)
                null
            }
            assertTrue("the one-shot observer factory is installed before service creation", testFactoryInstalled)

            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity ->
                serviceStartAttempted = true
                activity.startForegroundService(
                    Intent(activity, OverlayService::class.java).setAction(OverlayService.ACTION_ARM_MIC),
                )
            }
            awaitConditionOrFail("the observer receives the real OverlayService instance", SERVICE_START_TIMEOUT_MS) {
                observedService.get() != null
            }
            val service = requireNotNull(observedService.get())
            assertNull("the one-shot observer returned no native overrides", readServiceField(service, "meetingTestOverrides"))
            awaitPill(instrumentation)

            swipePillUp(instrumentation, target)
            awaitDictationFormatMenu(instrumentation, service)
            assertEquals(
                "opening the mode menu by swipe preserves the selected format",
                formatBeforeGesture,
                formats.selected().id,
            )
            captureGestureEvidence(instrumentation, evidenceDirectory, "dictation-swipe-menu-open.png")
            tapBounds(instrumentation, modeRowBounds(instrumentation, service, TranscriptionMode.MEETING))
            awaitConditionOrFail("the real overlay mode-row tap selects Meeting", SERVICE_START_TIMEOUT_MS) {
                coordinator.snapshot().mode == TranscriptionMode.MEETING
            }
            modeChanged = coordinator.snapshot().mode != modeBefore.mode
            assertEquals("choosing Meeting preserves Dictation's format", formatBeforeGesture, formats.selected().id)

            swipePillUp(instrumentation, target)
            awaitVisibleLabel(instrumentation, "Ouvrir la réunion", SERVICE_START_TIMEOUT_MS)
            tapLabel(instrumentation, "Ouvrir la réunion")
            awaitVisibleLabel(instrumentation, "Prête", SERVICE_START_TIMEOUT_MS)
            val serviceStore = readServiceField(service, "meetingModelStore") as? MeetingModelStore
            assertSame("the service uses the app's production shared model store", modelStore, serviceStore)
            assertNull("panel opening cannot reserve the native runtime", coordinator.snapshot().activeRunMode)

            val initial = awaitControllerState(instrumentation, service, SERVICE_START_TIMEOUT_MS) {
                it.phase == MeetingRecordingPhase.DOCUMENT
            }
            controller = initial.first
            val originalController = requireNotNull(controller)
            val initialSessionId = initial.second.document.sessionId
            val initialRunId = initial.second.document.runId
            sessionId = initialSessionId
            runId = initialRunId
            assertFalse("the new native session id was not already a stored note", notesBefore.contains(initialSessionId))
            assertFalse("a document-only panel has not started capture", initial.second.captureActive)
            captureGestureEvidence(instrumentation, evidenceDirectory, "meeting-panel-ready-before-microphone.png")

            safeNativeClose = false
            tapBounds(instrumentation, requireNotNull(pillBounds(instrumentation)) {
                "the real pill is the start target after the panel becomes Ready"
            })
            val listening = awaitControllerState(instrumentation, service, ENGINE_START_TIMEOUT_MS) {
                it.phase == MeetingRecordingPhase.LISTENING && it.captureActive
            }
            assertSame("the live state belongs to the original service controller", originalController, listening.first)
            assertEquals(sessionId, listening.second.document.sessionId)
            assertEquals(runId, listening.second.document.runId)
            assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
            assertMicrophoneForeground(target)
            Log.i(
                TAG,
                "event=listening session=$sessionId run=$runId adapter=production-jni+AudioRecord " +
                    "modelPackage=${fixturePackage.name} captureContentRetainedByTest=false",
            )

            // The recorder feeds the real engine during these bounded windows. The test stores no PCM.
            SystemClock.sleep(INITIAL_CAPTURE_MS)
            assertTrue("the host Activity initially has focus", activityHasWindowFocus(activeScenario))
            assertTrue("Android accepts the real HOME action", instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
            ))
            awaitConditionOrFail("the Activity is backgrounded while the overlay and runtime remain alive", 10_000L) {
                !activityHasWindowFocus(activeScenario) && pillBounds(instrumentation) != null &&
                    hasVisibleLabel(instrumentation, "Actions de la réunion")
            }
            val stillListening = readControllerState(instrumentation, service)
            assertEquals(MeetingRecordingPhase.LISTENING, stillListening.second?.phase)
            assertTrue(stillListening.second?.captureActive == true)
            assertEquals(sessionId, stillListening.second?.document?.sessionId)
            assertEquals(runId, stillListening.second?.document?.runId)
            assertMicrophoneForeground(target)

            tapMeetingAction(instrumentation, "Mettre en pause")
            val paused = awaitControllerState(instrumentation, service, CONTROLLER_TRANSITION_TIMEOUT_MS) {
                it.phase == MeetingRecordingPhase.PAUSED && !it.captureActive
            }
            assertSame(originalController, paused.first)
            assertEquals(sessionId, paused.second.document.sessionId)
            assertEquals(runId, paused.second.document.runId)
            assertEquals("pause retains the real process admission until native shutdown", TranscriptionMode.MEETING,
                coordinator.snapshot().activeRunMode)
            Log.i(TAG, "event=paused session=$sessionId run=$runId captureActive=false leaseHeld=true")

            tapMeetingAction(instrumentation, "Reprendre la réunion")
            val resumed = awaitControllerState(instrumentation, service, CONTROLLER_TRANSITION_TIMEOUT_MS) {
                it.phase == MeetingRecordingPhase.LISTENING && it.captureActive
            }
            assertSame("resume reuses the same controller and native session owner", originalController, resumed.first)
            assertEquals(sessionId, resumed.second.document.sessionId)
            assertEquals(runId, resumed.second.document.runId)
            assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
            assertMicrophoneForeground(target)
            SystemClock.sleep(RESUMED_CAPTURE_MS)

            tapMeetingAction(instrumentation, "Mettre en pause")
            val finishingPause = awaitControllerState(instrumentation, service, CONTROLLER_TRANSITION_TIMEOUT_MS) {
                it.phase == MeetingRecordingPhase.PAUSED && !it.captureActive
            }
            assertSame(originalController, finishingPause.first)
            assertEquals(sessionId, finishingPause.second.document.sessionId)
            assertEquals(runId, finishingPause.second.document.runId)

            tapMeetingAction(instrumentation, "Terminer la réunion")
            awaitVisibleLabel(instrumentation, "Enregistrer la transcription ?", CONTROLLER_TRANSITION_TIMEOUT_MS)
            tapLabel(instrumentation, "Enregistrer et terminer")
            val finished = awaitControllerState(instrumentation, service, ENGINE_FINISH_TIMEOUT_MS) {
                it.phase == MeetingRecordingPhase.FINISHED && !it.captureActive
            }
            assertSame(originalController, finished.first)
            val document = finished.second.document
            val finishedSessionId = requireNotNull(sessionId)
            val finishedRunId = requireNotNull(runId)
            assertEquals(finishedSessionId, document.sessionId)
            assertEquals(finishedRunId, document.runId)
            assertTrue("the native engine returns a finished document after finish", document.finished)
            assertNull("a saved note or empty result has no storage error", finished.second.saveError)
            val released = coordinator.snapshot()
            assertNull("the real admission lease releases only after reader/native closure", released.activeRunMode)
            assertFalse("native closure did not poison the process", released.poisoned)
            safeNativeClose = true

            awaitConditionOrFail("the finished document reached its durable draft snapshot", CONTROLLER_TRANSITION_TIMEOUT_MS) {
                val draft = (MeetingDraftStore(standardDraft).load() as? MeetingDocumentRead.Ready)?.document
                draft?.sessionId == finishedSessionId && draft.runId == finishedRunId && draft.finished
            }
            val persistedNote = readAllNotes(target).firstOrNull { it.meeting?.sessionId == finishedSessionId }
            val hasTranscript = document.turns.any { turn ->
                (turn.editedText ?: turn.recognizedText).isNotBlank()
            }
            if (hasTranscript) {
                assertNotNull("recognized content is durably published as a structured meeting note", persistedNote)
                assertEquals(finishedSessionId, persistedNote?.id)
                assertEquals(document, persistedNote?.meeting)
            } else {
                assertNull("a truly empty recording does not create a blank meeting note", persistedNote)
            }
            Log.i(
                TAG,
                "event=finished session=$sessionId run=$runId turns=${document.turns.size} " +
                    "structuredNote=${persistedNote != null} nativeAndReaderClosed=true leaseReleased=true",
            )
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            runCatching {
                val automationInfo = uiAutomation.serviceInfo
                automationInfo.flags = originalUiAutomationFlags
                uiAutomation.serviceInfo = automationInfo
            }.onFailure(cleanupFailures::add)
            if (testFactoryInstalled) {
                runCatching { OverlayService.clearMeetingTestOverridesFactoryForTest() }
                    .onFailure(cleanupFailures::add)
            }

            val service = observedService.get()
            var resourcesClosed = safeNativeClose
            val ownedController = controller ?: service?.let { readController(instrumentation, it) }
            val observedState = ownedController?.let { current ->
                runCatching { readControllerStateOnMain(instrumentation, current) }
                    .onFailure { cleanupFailures += it }
                    .getOrNull()
            }
            if (sessionId == null) sessionId = observedState?.document?.sessionId
            if (runId == null) runId = observedState?.document?.runId

            if (!resourcesClosed && ownedController != null) {
                resourcesClosed = runCatching {
                    val phaseAtCancel = AtomicReference<MeetingRecordingPhase?>()
                    val cancellation = AtomicReference<CompletableFuture<Unit>?>()
                    instrumentation.runOnMainSync {
                        val currentState = ownedController.state
                        phaseAtCancel.set(currentState.phase)
                        if (currentState.phase != MeetingRecordingPhase.FINISHED) {
                            cancellation.set(ownedController.cancel())
                        }
                    }
                    if (phaseAtCancel.get() != MeetingRecordingPhase.FINISHED) {
                        requireNotNull(cancellation.get()) { "controller cancellation was not requested" }
                            .get(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                    val finalState = readControllerStateOnMain(instrumentation, ownedController)
                    val snapshot = coordinator.snapshot()
                    finalState.phase == MeetingRecordingPhase.FINISHED && !finalState.captureActive &&
                        snapshot.activeRunMode == null && !snapshot.poisoned
                }.getOrDefault(false)
            }
            if (!resourcesClosed) {
                cleanupFailures += AssertionError(
                    "native closure or microphone release was not confirmed; fixture files are retained",
                )
            }

            var controllerDestroyed = ownedController == null
            if (resourcesClosed && ownedController != null) {
                controllerDestroyed = runCatching {
                    val destruction = AtomicReference<CompletableFuture<Unit>?>()
                    instrumentation.runOnMainSync { destruction.set(ownedController.destroy()) }
                    requireNotNull(destruction.get()) { "controller destruction was not requested" }
                        .get(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    true
                }.getOrDefault(false)
                if (!controllerDestroyed) cleanupFailures += AssertionError("the controller did not finish destroy() safely")
            }

            if (serviceStartAttempted) {
                runCatching { target.stopService(Intent(target, OverlayService::class.java)) }
                    .onFailure(cleanupFailures::add)
            }
            runCatching { scenario?.close() }.onFailure(cleanupFailures::add)
            val serviceStopped = !serviceStartAttempted || awaitCondition(10_000L) {
                !OverlayService.micArmed && observedPillIsDetached(instrumentation) &&
                    coordinator.snapshot().let { it.activeRunMode == null && !it.poisoned }
            }
            if (!serviceStopped) {
                cleanupFailures += AssertionError("service teardown or safe runtime release was not confirmed")
            }

            if (resourcesClosed && controllerDestroyed && serviceStopped) {
                val currentSessionId = sessionId
                if (currentSessionId != null) {
                    runCatching {
                        MeetingDraftOwnership.processWide
                            .clear(standardDraft, currentSessionId)
                            .get(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }.onFailure(cleanupFailures::add)
                    if (!notesBefore.contains(currentSessionId)) {
                        runCatching {
                            TranscriptNotes(AndroidTranscriptNoteStorage(target)).delete(currentSessionId)
                        }.onFailure(cleanupFailures::add)
                    }
                }
                if (fixturePackage.parentFile?.canonicalFile == modelRoot.canonicalFile && fixturePackage.exists()) {
                    runCatching { check(fixturePackage.deleteRecursively()) { "fixture package cleanup failed" } }
                        .onFailure { cleanupFailures += it }
                    modelStore.refresh()
                    val storeReleasedFixture = awaitCondition(MODEL_CHECK_TIMEOUT_MS) {
                        val state = modelStore.currentState
                        state !is MeetingModelStoreState.Ready ||
                            state.paths.packageDirectory.canonicalFile != fixturePackage.canonicalFile
                    }
                    if (!storeReleasedFixture) cleanupFailures += AssertionError("the shared store retained the removed fixture package")
                }
            } else if (fixturePackage.exists()) {
                Log.e(TAG, "cleanup=retained modelPackage=${fixturePackage.name} reason=native close not confirmed")
            }

            if (modeChanged) {
                val snapshot = coordinator.snapshot()
                if (resourcesClosed && controllerDestroyed && serviceStopped &&
                    snapshot.activeRunMode == null && !snapshot.poisoned
                ) {
                    runCatching { check(coordinator.changeMode(modeBefore.mode)) { "the selected mode could not be restored" } }
                        .onFailure(cleanupFailures::add)
                } else {
                    Log.e(TAG, "cleanup=retained meeting mode active=${snapshot.activeRunMode} poisoned=${snapshot.poisoned}")
                }
            }
            if (onboardingChanged) {
                runCatching {
                    val edit = preferences.edit()
                    if (hadOnboardingValue) edit.putBoolean("onb_complete", oldOnboardingValue)
                    else edit.remove("onb_complete")
                    check(edit.commit()) { "onboarding preference restoration failed" }
                }.onFailure(cleanupFailures::add)
            }
            if (cleanupFailures.isNotEmpty()) {
                val cleanupFailure = AssertionError("real-runtime fixture cleanup did not complete")
                cleanupFailures.forEach { cleanupFailure.addSuppressed(it) }
                val previousFailure = testFailure
                if (previousFailure != null) previousFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun verifyStagedModelPackage(directory: File, catalog: MeetingModelCatalog): MeetingModelPaths {
        assertTrue("the host stages the exact unique package directory", directory.isDirectory)
        val asrFile = File(directory, catalog.asr.relativePath)
        val diarizationFile = File(directory, catalog.diarization.relativePath)
        assertTrue("the staged ASR model exists", asrFile.isFile)
        assertTrue("the staged diarization model exists", diarizationFile.isFile)
        assertEquals("the ASR model size matches the production pin", catalog.asr.sizeBytes, asrFile.length())
        assertEquals("the diarization model size matches the production pin", catalog.diarization.sizeBytes, diarizationFile.length())
        val asrSha = sha256(asrFile)
        val diarizationSha = sha256(diarizationFile)
        assertEquals("the ASR model matches the production SHA-256", catalog.asr.sha256, asrSha)
        assertEquals("the diarization model matches the production SHA-256", catalog.diarization.sha256, diarizationSha)
        Log.i(
            TAG,
            "modelPackage=${directory.name} asrBytes=${asrFile.length()} asrSha256=$asrSha " +
                "diarizationBytes=${diarizationFile.length()} diarizationSha256=$diarizationSha",
        )
        return MeetingModelPaths(directory, asrFile, diarizationFile)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun readAllNotes(context: Context): List<TranscriptNote> =
        TranscriptNotes(AndroidTranscriptNoteStorage(context)).all()

    private fun readServiceField(service: OverlayService, name: String): Any? =
        OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun readController(
        instrumentation: android.app.Instrumentation,
        service: OverlayService,
    ): MeetingRecordingController? {
        val result = AtomicReference<MeetingRecordingController?>()
        instrumentation.runOnMainSync {
            result.set(readServiceField(service, "meetingRecordingController") as? MeetingRecordingController)
        }
        return result.get()
    }

    private fun readControllerStateOnMain(
        instrumentation: android.app.Instrumentation,
        controller: MeetingRecordingController,
    ): MeetingRecordingState {
        val state = AtomicReference<MeetingRecordingState?>()
        instrumentation.runOnMainSync { state.set(controller.state) }
        return requireNotNull(state.get())
    }

    private fun readControllerState(
        instrumentation: android.app.Instrumentation,
        service: OverlayService,
    ): Pair<MeetingRecordingController?, MeetingRecordingState?> {
        val controller = AtomicReference<MeetingRecordingController?>()
        val state = AtomicReference<MeetingRecordingState?>()
        instrumentation.runOnMainSync {
            val current = readServiceField(service, "meetingRecordingController") as? MeetingRecordingController
            controller.set(current)
            state.set(current?.state)
        }
        return controller.get() to state.get()
    }

    private fun awaitControllerState(
        instrumentation: android.app.Instrumentation,
        service: OverlayService,
        timeoutMs: Long,
        predicate: (MeetingRecordingState) -> Boolean,
    ): Pair<MeetingRecordingController, MeetingRecordingState> {
        val latest = AtomicReference<Pair<MeetingRecordingController, MeetingRecordingState>?>()
        try {
            awaitConditionOrFail("the real controller reaches the requested lifecycle phase", timeoutMs) {
                val (controller, state) = readControllerState(instrumentation, service)
                if (controller != null && state != null) latest.set(controller to state)
                controller != null && state != null && predicate(state)
            }
        } catch (failure: AssertionError) {
            runCatching { writeControllerTimeoutDiagnostics(instrumentation, service, latest.get()) }
                .onFailure(failure::addSuppressed)
            throw failure
        }
        return requireNotNull(latest.get())
    }

    private fun writeControllerTimeoutDiagnostics(
        instrumentation: android.app.Instrumentation,
        service: OverlayService,
        latest: Pair<MeetingRecordingController, MeetingRecordingState>?,
    ) {
        val target = instrumentation.targetContext
        val diagnosticDirectory = File(target.filesDir, "meeting-default-runtime-diagnostics")
        check(diagnosticDirectory.isDirectory || diagnosticDirectory.mkdirs()) {
            "could not create private controller diagnostic directory"
        }
        val suffix = SystemClock.elapsedRealtime().toString()
        val current = latest ?: readControllerState(instrumentation, service).let { (controller, state) ->
            if (controller != null && state != null) controller to state else null
        }
        val mode = TranscriptionModeCoordinator.process(target).snapshot()
        val state = current?.second
        val summary = buildString {
            appendLine("phase=${state?.phase ?: "unavailable"}")
            appendLine("captureActive=${state?.captureActive ?: "unavailable"}")
            appendLine("recordingError=${state?.recordingError ?: "none"}")
            appendLine("saveError=${state?.saveError ?: "none"}")
            appendLine("activeRunMode=${mode.activeRunMode ?: "none"}")
            appendLine("poisoned=${mode.poisoned}")
            appendLine("micArmed=${OverlayService.micArmed}")
        }
        Log.e(TAG, "controller-timeout $summary")
        File(diagnosticDirectory, "controller-timeout-$suffix.txt").writeText(summary)

        val windows = buildString {
            for (window in instrumentation.uiAutomation.windows) {
                try {
                    val bounds = Rect().also(window::getBoundsInScreen)
                    val root = try { window.root } catch (_: Throwable) { null }
                    try {
                        appendLine(
                            "type=${window.type} layer=${window.layer} focused=${window.isFocused} " +
                                "active=${window.isActive} bounds=${bounds.left},${bounds.top}," +
                                "${bounds.right},${bounds.bottom} rootChildren=${root?.childCount ?: -1}",
                        )
                    } finally {
                        root?.recycle()
                    }
                } finally {
                    window.recycle()
                }
            }
        }
        File(diagnosticDirectory, "controller-timeout-$suffix-windows.txt").writeText(windows)

        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not provide a timeout screenshot"
        }
        try {
            FileOutputStream(File(diagnosticDirectory, "controller-timeout-$suffix.png")).use { output ->
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "could not encode controller timeout screenshot"
                }
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun assertMicrophoneForeground(context: Context) {
        assertTrue("the service confirms that microphone foreground promotion succeeded", OverlayService.micArmed)
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, OverlayService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(
            "the service manifest declares the microphone foreground type",
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0,
        )
        @Suppress("DEPRECATION")
        val service = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == OverlayService::class.java.name }
        assertNotNull("the OverlayService is registered with ActivityManager", service)
        assertTrue("the real OverlayService remains foreground while AudioRecord is active", requireNotNull(service).foreground)
    }

    private fun isOverlayServiceRunning(context: Context): Boolean {
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == OverlayService::class.java.name }
    }

    private fun activityHasWindowFocus(scenario: ActivityScenario<MainActivity>): Boolean {
        val focused = AtomicBoolean(false)
        scenario.onActivity { activity -> focused.set(activity.window.decorView.hasWindowFocus()) }
        return focused.get()
    }

    private fun awaitPill(instrumentation: android.app.Instrumentation) {
        awaitConditionOrFail("the service attaches its real floating pill", SERVICE_START_TIMEOUT_MS) {
            pillBounds(instrumentation) != null
        }
    }

    private fun awaitDictationFormatMenu(
        instrumentation: android.app.Instrumentation,
        service: OverlayService,
    ) {
        awaitConditionOrFail("the Dictation swipe leaves the real overlay menu open and touchable", SERVICE_START_TIMEOUT_MS) {
            val open = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                val menu = readServiceField(service, "floatingMenu") as? View
                val params = readServiceField(service, "formatMenuParams") as? android.view.WindowManager.LayoutParams
                val rows = readServiceField(service, "transcriptionModeRows") as? List<*>
                val meetingRow = rows?.filterIsInstance<android.widget.TextView>()
                    ?.singleOrNull { it.text.toString() == "Réunion" }
                open.set(
                    menu != null && menu.isAttachedToWindow && menu.isShown &&
                        params != null &&
                        params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 &&
                        meetingRow != null && meetingRow.isAttachedToWindow && meetingRow.isShown && meetingRow.isClickable,
                )
            }
            open.get()
        }
    }

    private fun modeRowBounds(
        instrumentation: android.app.Instrumentation,
        service: OverlayService,
        mode: TranscriptionMode,
    ): Rect {
        val result = AtomicReference<Rect?>()
        instrumentation.runOnMainSync {
            val menu = requireNotNull(readServiceField(service, "floatingMenu") as? View) {
                "the mode row belongs to the real floating menu"
            }
            val rows = requireNotNull(readServiceField(service, "transcriptionModeRows") as? List<*>)
            val row = rows.filterIsInstance<android.widget.TextView>().single { it.tag == mode }
            val expectedLabel = if (mode == TranscriptionMode.MEETING) "Réunion" else "Dictée"
            check(row.text.toString() == expectedLabel && row.isClickable && row.isShown && row.isAttachedToWindow) {
                "the requested mode is an actionable row in the attached floating menu"
            }
            val menuGlobalBounds = Rect()
            val rowGlobalBounds = Rect()
            check(menu.getGlobalVisibleRect(menuGlobalBounds) && row.getGlobalVisibleRect(rowGlobalBounds)) {
                "the actual menu and mode row have bounds in the same window coordinate space"
            }
            check(!rowGlobalBounds.isEmpty && menuGlobalBounds.contains(rowGlobalBounds)) {
                "the tapped mode row is inside the floating menu, not the Activity"
            }
            val visibleLocal = Rect()
            check(row.getLocalVisibleRect(visibleLocal) && !visibleLocal.isEmpty) {
                "the real mode row has a visible local touch target"
            }
            val locationOnScreen = IntArray(2)
            row.getLocationOnScreen(locationOnScreen)
            val screenBounds = Rect(visibleLocal).apply { offset(locationOnScreen[0], locationOnScreen[1]) }
            val displaySize = android.graphics.Point()
            val windowManager = instrumentation.targetContext
                .getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(displaySize)
            check(screenBounds.intersect(Rect(0, 0, displaySize.x, displaySize.y)) && !screenBounds.isEmpty) {
                "the visible mode-row tap target intersects the physical screen"
            }
            result.set(Rect(screenBounds))
        }
        return requireNotNull(result.get())
    }

    private fun createGestureEvidenceDirectory(context: Context, generationId: String): File {
        val externalFiles = requireNotNull(context.getExternalFilesDir(null)) {
            "the host exposes app-specific external files for native screenshot extraction"
        }
        val directory = File(
            File(externalFiles, "meeting-default-runtime-evidence"),
            "$generationId-${SystemClock.elapsedRealtime()}",
        )
        check(directory.isDirectory || directory.mkdirs()) { "could not create the dedicated evidence directory" }
        Log.i(TAG, "gestureEvidenceDirectory=${directory.absolutePath}")
        return directory
    }

    private fun captureGestureEvidence(
        instrumentation: android.app.Instrumentation,
        directory: File,
        filename: String,
    ) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not provide the requested synthetic gesture evidence screenshot"
        }
        val outputFile = File(directory, filename)
        try {
            FileOutputStream(outputFile).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "could not encode the synthetic gesture evidence screenshot"
                }
            }
        } finally {
            bitmap.recycle()
        }
        Log.i(TAG, "gestureEvidence=${outputFile.absolutePath} bytes=${outputFile.length()} sha256=${sha256(outputFile)}")
    }

    private fun pillBounds(instrumentation: android.app.Instrumentation): Rect? {
        val service = observedService.get() ?: return null
        val result = AtomicReference<Rect?>()
        instrumentation.runOnMainSync {
            val pill = readServiceField(service, "pill") as? View ?: return@runOnMainSync
            if (!pill.isAttachedToWindow || !pill.isShown || pill.visibility != View.VISIBLE ||
                pill.width <= 0 || pill.height <= 0
            ) return@runOnMainSync
            val visibleLocal = Rect()
            if (!pill.getLocalVisibleRect(visibleLocal) || visibleLocal.isEmpty) return@runOnMainSync
            val location = IntArray(2)
            pill.getLocationOnScreen(location)
            val bounds = Rect(visibleLocal).apply { offset(location[0], location[1]) }
            val screenSize = android.graphics.Point()
            val windowManager = instrumentation.targetContext
                .getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(screenSize)
            if (!bounds.intersect(Rect(0, 0, screenSize.x, screenSize.y)) || bounds.isEmpty) return@runOnMainSync
            result.set(bounds)
        }
        return result.get()
    }

    private fun observedPillIsDetached(instrumentation: android.app.Instrumentation): Boolean {
        val service = observedService.get() ?: return true
        val detached = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            val pill = readServiceField(service, "pill") as? View
            detached.set(pill == null || !pill.isAttachedToWindow)
        }
        return detached.get()
    }

    private fun swipePillUp(instrumentation: android.app.Instrumentation, context: Context) {
        val start = requireNotNull(pillBounds(instrumentation)) { "the visible pill is the real swipe target" }
        val startX = start.centerX()
        val startY = start.centerY()
        val distance = (160 * context.resources.displayMetrics.density).toInt()
        injectSwipe(instrumentation, startX, startY, startX, startY - distance)
    }

    private fun tapMeetingAction(instrumentation: android.app.Instrumentation, action: String) {
        tapLabel(instrumentation, "Actions de la réunion")
        awaitVisibleLabel(instrumentation, action, CONTROLLER_TRANSITION_TIMEOUT_MS)
        tapLabel(instrumentation, action)
    }

    private fun tapLabel(instrumentation: android.app.Instrumentation, label: String) {
        val node = requireNotNull(findVisibleNode(instrumentation) { hasExactLabel(it, label) }) {
            "visible app or overlay window exposes the touch target '$label'"
        }
        try {
            val bounds = Rect().also(node::getBoundsInScreen)
            tapBounds(instrumentation, bounds)
        } finally {
            node.recycle()
        }
    }

    private fun awaitVisibleLabel(instrumentation: android.app.Instrumentation, label: String, timeoutMs: Long) {
        awaitConditionOrFail("a real window displays '$label'", timeoutMs) { hasVisibleLabel(instrumentation, label) }
    }

    private fun hasVisibleLabel(instrumentation: android.app.Instrumentation, label: String): Boolean =
        findVisibleNode(instrumentation) { hasLabelFragment(it, label) }?.let { node ->
            node.recycle()
            true
        } ?: false

    private fun findVisibleNode(
        instrumentation: android.app.Instrumentation,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        for (window in instrumentation.uiAutomation.windows) {
            val root = try { window.root } catch (_: Throwable) { null } ?: continue
            try {
                findNodeRecursive(root, predicate)?.let { return it }
            } finally {
                root.recycle()
                window.recycle()
            }
        }
        return null
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = try { node.getChild(index) } catch (_: Throwable) { null } ?: continue
            val match = try { findNodeRecursive(child, predicate) } finally { child.recycle() }
            if (match != null) return match
        }
        return null
    }

    private fun hasExactLabel(node: AccessibilityNodeInfo, expected: String): Boolean =
        node.text?.toString()?.trim()?.equals(expected, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.trim()?.equals(expected, ignoreCase = true) == true

    private fun hasLabelFragment(node: AccessibilityNodeInfo, expected: String): Boolean =
        node.text?.toString()?.contains(expected, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(expected, ignoreCase = true) == true

    private fun tapBounds(instrumentation: android.app.Instrumentation, bounds: Rect) {
        check(!bounds.isEmpty) { "a real UI element must have non-empty screen bounds" }
        val downTime = SystemClock.uptimeMillis()
        injectTouch(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        SystemClock.sleep(60L)
        injectTouch(
            instrumentation,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            bounds.centerX().toFloat(),
            bounds.centerY().toFloat(),
        )
        SystemClock.sleep(100L)
    }

    private fun injectSwipe(
        instrumentation: android.app.Instrumentation,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
    ) {
        val downTime = SystemClock.uptimeMillis()
        injectTouch(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat())
        repeat(SWIPE_STEPS) { index ->
            val progress = (index + 1).toFloat() / SWIPE_STEPS
            SystemClock.sleep(SWIPE_STEP_MS)
            injectTouch(
                instrumentation,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                startX + (endX - startX) * progress,
                startY + (endY - startY) * progress,
            )
        }
        injectTouch(instrumentation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX.toFloat(), endY.toFloat())
        SystemClock.sleep(100L)
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
            assertTrue("Android accepts the real screen input", instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun awaitConditionOrFail(message: String, timeoutMs: Long, condition: () -> Boolean) {
        assertTrue(message, awaitCondition(timeoutMs, condition))
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return runCatching(condition).getOrDefault(false)
    }

    private companion object {
        const val TAG = "MeetingOverlayDefaultRuntime"
        const val MODEL_GENERATION_ARGUMENT = "modelGenerationId"
        const val MODEL_DIRECTORY_NAME = "meeting-models"
        const val STANDARD_DRAFT_RELATIVE_PATH = "meeting/meeting-draft.json"
        const val HASH_BUFFER_BYTES = 128 * 1024
        const val POLL_INTERVAL_MS = 100L
        const val SWIPE_STEPS = 8
        const val SWIPE_STEP_MS = 22L
        const val MODEL_CHECK_TIMEOUT_MS = 180_000L
        const val SERVICE_START_TIMEOUT_MS = 20_000L
        const val ENGINE_START_TIMEOUT_MS = 180_000L
        const val CONTROLLER_TRANSITION_TIMEOUT_MS = 45_000L
        const val ENGINE_FINISH_TIMEOUT_MS = 180_000L
        const val CLEANUP_TIMEOUT_SECONDS = 180L
        const val INITIAL_CAPTURE_MS = 3_000L
        const val RESUMED_CAPTURE_MS = 2_000L
    }
}
