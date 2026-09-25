package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingDocumentRead
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipClearer
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipExecutor
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipWriterFactory
import com.kafkasl.phonewhisper.meeting.MeetingDraftPersistence
import com.kafkasl.phonewhisper.meeting.MeetingDraftScheduledTask
import com.kafkasl.phonewhisper.meeting.MeetingDraftScheduler
import com.kafkasl.phonewhisper.meeting.MeetingDraftStore
import com.kafkasl.phonewhisper.meeting.MeetingDraftWriter
import com.kafkasl.phonewhisper.meeting.MeetingHypothesis
import com.kafkasl.phonewhisper.meeting.MeetingMicrophoneFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingMicrophonePort
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailability
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailabilityPort
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationPort
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationRequest
import com.kafkasl.phonewhisper.meeting.MeetingNativeRuntimeLease
import com.kafkasl.phonewhisper.meeting.MeetingParticipant
import com.kafkasl.phonewhisper.meeting.MeetingPanelController
import com.kafkasl.phonewhisper.meeting.MeetingPanelActions
import com.kafkasl.phonewhisper.meeting.MeetingPanelDocumentAction
import com.kafkasl.phonewhisper.meeting.MeetingProjection
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPhase
import com.kafkasl.phonewhisper.meeting.MeetingSession
import com.kafkasl.phonewhisper.meeting.MeetingSessionFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingTurn
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceMeetingModeTransitionRobolectricTest {
    private lateinit var context: Context
    private lateinit var coordinator: TranscriptionModeCoordinator
    private lateinit var notesStorage: AndroidTranscriptNoteStorage
    private lateinit var notesPreferences: android.content.SharedPreferences
    private lateinit var draftPreferences: android.content.SharedPreferences
    private lateinit var draftPreferencesBefore: Map<String, Any?>
    private lateinit var draftDirectory: File
    private lateinit var draftFile: File
    private lateinit var imageStore: NoteImageStore
    private lateinit var nativeCalls: NativeCallCounts
    private lateinit var reservation: TestReservationPort
    private lateinit var sessions: TestSessionFactory
    private lateinit var microphones: TestMicrophoneFactory
    private var previousMode = TranscriptionMode.DICTATION
    private var previousAnimatorDurationScale = 1f
    private var serviceController: ServiceController<OverlayService>? = null
    private var service: OverlayService? = null
    private var ownedController: MeetingRecordingController? = null
    private var failingDraftWrites: AtomicInteger? = null
    private var exportExecutorToAwait: ExecutorService? = null
    private var ownershipExecutor: ExecutorService? = null
    private val ownedNotes = linkedMapOf<String, String?>()
    private val ownedImageIds = linkedSetOf<String>()
    private val ownedExportDirectories = CopyOnWriteArrayList<File>()
    private val schedulers = CopyOnWriteArrayList<TestDraftScheduler>()
    private val releaseGates = CopyOnWriteArrayList<CountDownLatch>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        previousAnimatorDurationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        check(Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)) {
            "Could not disable animator scheduling for this fixture"
        }

        val prefs = PersistencePrefs(context)
        previousMode = prefs.transcriptionMode
        TranscriptionModeCoordinator.clearProcessForTest()
        prefs.transcriptionMode = TranscriptionMode.DICTATION
        coordinator = TranscriptionModeCoordinator.process(context)
        notesStorage = AndroidTranscriptNoteStorage(context)
        notesPreferences = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
        draftPreferences = context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE)
        draftPreferencesBefore = draftPreferences.all.toMap()
        draftPreferences.edit().clear().commit()
        imageStore = NoteImageStore(context)
        nativeCalls = NativeCallCounts()
        reservation = TestReservationPort(nativeCalls)
        sessions = TestSessionFactory(nativeCalls)
        microphones = TestMicrophoneFactory(nativeCalls)
        ShadowSettings.setCanDrawOverlays(true)

        draftDirectory = File(context.cacheDir, "meeting-mode-transition-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Could not create the isolated draft directory" }
        }
        draftFile = File(draftDirectory, "meeting-draft.json")
    }

    @After
    fun tearDown() {
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val prior = cleanupFailure
                if (prior == null) cleanupFailure = failure else prior.addSuppressed(failure)
            }
        }

        releaseGates.forEach { it.countDown() }
        failingDraftWrites?.set(0)
        val currentService = service
        var claimRequest: com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipRequest? = null
        if (currentService != null) cleanup {
            onMain {
                if (ownedController == null) {
                    ownedController = field(currentService, "meetingRecordingController")
                }
                claimRequest = field(currentService, "meetingDraftClaimRequest")
                val exportDelegate = field<Lazy<*>>(currentService, "exportController\$delegate")
                if (exportDelegate.isInitialized()) {
                    exportExecutorToAwait = field<ExecutorService>(exportDelegate.value!!, "executor")
                }
                serviceController?.destroy()
                ownedController?.destroy()
                Unit
            }
        }
        cleanup {
            ownedController?.destroy()?.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        cleanup {
            claimRequest?.future?.let { future -> runCatching { future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) } }
        }
        cleanup {
            exportExecutorToAwait?.let { executor ->
                check(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "Overlay export worker did not terminate after Service destruction"
                }
            }
        }
        cleanup { awaitMainCondition { coordinator.snapshot().activeRunMode == null } }

        ownedNotes.forEach { (id, previous) -> cleanup {
            val editor = notesPreferences.edit()
            if (previous == null) editor.remove(id) else editor.putString(id, previous)
            check(editor.commit()) { "Could not restore fixture note $id" }
        } }
        ownedImageIds.forEach { id -> cleanup { imageStore.delete(id) } }
        ownedExportDirectories.forEach { directory -> cleanup {
            if (directory.exists()) check(directory.deleteRecursively()) {
                "Could not remove this fixture's export directory"
            }
        } }
        cleanup { restorePreferences("dictation_draft", draftPreferencesBefore) }
        cleanup { PersistencePrefs(context).transcriptionMode = previousMode }
        cleanup { TranscriptionModeCoordinator.clearProcessForTest() }
        cleanup {
            schedulers.forEach(TestDraftScheduler::shutdown)
            ownershipExecutor?.shutdown()
            ownershipExecutor?.let { executor ->
                check(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "Draft ownership executor did not terminate"
                }
            }
        }
        cleanup {
            check(Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                previousAnimatorDurationScale,
            )) { "Could not restore animator duration scale" }
        }
        cleanup {
            if (draftDirectory.exists()) check(draftDirectory.deleteRecursively()) {
                "Could not remove the isolated draft directory after writer cleanup"
            }
        }
        cleanup { ShadowSettings.reset() }

        cleanupFailure?.let { throw AssertionError("Meeting mode transition fixture cleanup failed", it) }
    }

    @Test
    fun `external Dictation selection saves the edited structured note before detaching the Meeting panel`() {
        val previousDictationNote = createPriorDictationContext()
        val (note, image, originalDocument) = createSavedMeetingNote()
        val draftOwnership = newDraftOwnership()
        startMeetingService(draftOwnership)
        openSavedMeeting(note)
        val controller = requireNotNull(ownedController)
        val turnId = originalDocument.turns.single().id
        editTurn(controller, turnId, "Bonjour [[Image 1]] jeudi")
        val expectedDocument = onMain { controller.state.document }
        val oldPanel = requireNotNull(field<MeetingPanelController?>(requireNotNull(service), "meetingPanelController"))

        assertEquals("the edit is made on the real controller while no run is active", "Bonjour [[Image 1]] jeudi",
            expectedDocument.turns.single { it.id == turnId }.editedText)
        onMain { invoke(requireNotNull(service), "launchNoteExport", note, false) }
        val meetingExportPanel = requireNotNull(field<OverlayExportPanel?>(requireNotNull(service), "exportPanel"))
        val exportResume = requireNotNull(field<Any?>(requireNotNull(service), "exportResume"))
        assertEquals("the export return snapshot belongs to the Meeting note", note.id,
            field<String?>(exportResume, "activeNoteId"))
        assertNotNull(field<Any?>(requireNotNull(service), "exportPanel"))
        assertTrue("the external coordinator change is accepted for a documentary note",
            coordinator.changeMode(TranscriptionMode.DICTATION))

        awaitMainCondition {
            coordinator.snapshot().mode == TranscriptionMode.DICTATION &&
                field<Boolean>(requireNotNull(service), "meetingSurfaceOpen").not() &&
                field<MeetingPanelController?>(requireNotNull(service), "meetingPanelController") == null &&
                field<Any?>(requireNotNull(service), "meetingRecordingController") == null &&
                field<Any?>(requireNotNull(service), "exportPanel") == null &&
                field<Any?>(requireNotNull(service), "exportResume") == null &&
                field<String?>(requireNotNull(service), "activeNoteId") == previousDictationNote.id &&
                notesStorage.all().firstOrNull { it.id == note.id }?.meeting == expectedDocument
        }
        field<OverlayExportResult?>(meetingExportPanel, "currentResult")?.directory
            ?.let(ownedExportDirectories::add)

        val published = requireNotNull(notesStorage.all().firstOrNull { it.id == note.id })
        assertEquals("participant identity and the live retouch are published together", expectedDocument, published.meeting)
        assertEquals("the image metadata is preserved", listOf(image), published.images)
        assertEquals("the stored projection uses the edited body", "Sophie\nBonjour [[Image 1]] jeudi", published.text)
        assertNull("the old Meeting view is detached", oldPanel.view.parent)
        assertEquals(View.VISIBLE, field<View?>(requireNotNull(service), "liveScroll")?.visibility)
        assertEquals("Pastille Dictée", field<View>(requireNotNull(service), "pill").contentDescription)
        assertFalse(field<Boolean>(requireNotNull(service), "panelHidden"))
        assertEquals("the previous Dictation destination is restored", previousDictationNote.id,
            field<String?>(requireNotNull(service), "activeNoteId"))
        assertEquals(DictationPurpose.NOTE, field<DictationPurpose>(requireNotNull(service), "purpose"))
        val dictationDraft = DictationDraftStore(context)
        assertEquals(previousDictationNote.id, dictationDraft.noteId)
        assertEquals(previousDictationNote.text, dictationDraft.load())
        assertEquals(previousDictationNote.text, field<String?>(requireNotNull(service), "recoveredDraft"))
        onMain { invokeNoArgs(requireNotNull(service), "closeNoteExport") }
        assertEquals("a late export return cannot restore the Meeting note identity", previousDictationNote.id,
            field<String?>(requireNotNull(service), "activeNoteId"))
        onMain { invoke(requireNotNull(service), "persistDraft", "Nouvelle dictée") }
        assertEquals("a later Dictation save targets only its prior flat note", "Nouvelle dictée",
            notesStorage.all().firstOrNull { it.id == previousDictationNote.id }?.text)
        assertEquals("the structured Meeting note remains structured after Dictation save", expectedDocument,
            notesStorage.all().firstOrNull { it.id == note.id }?.meeting)
        assertEquals("Sophie\nBonjour [[Image 1]] jeudi",
            notesStorage.all().firstOrNull { it.id == note.id }?.text)
        assertEquals("the selected mode has no native run", null, coordinator.snapshot().activeRunMode)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `mode transition during blocked note transfer saves the old edit without installing the stale incoming note`() {
        val (note, image, originalDocument) = createSavedMeetingNote()
        val gate = PersistenceGate()
        releaseGates += gate.release
        val draftOwnership = newDraftOwnership(persist = { path, document ->
            gate.entered.countDown()
            check(gate.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Test did not release the blocked draft write" }
            MeetingDraftStore(path).save(document)
        })
        startMeetingService(draftOwnership)
        openSavedMeeting(note)
        val controller = requireNotNull(ownedController)
        val turnId = originalDocument.turns.single().id
        editTurn(controller, turnId, "Bonjour [[Image 1]] jeudi")
        val expectedDocument = onMain { controller.state.document }

        val incomingDocument = documentWithText("entrant", "Nouvelle note")
        val incoming = persistMeetingNote(incomingDocument, staleText = "ancienne projection entrante")
        val incomingRecord = requireNotNull(notesPreferences.getString(incoming.id, null))
        onMain { invoke(requireNotNull(service), "openNote", incoming) }
        assertTrue("the replacement flush reaches the deliberately held writer",
            gate.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertNotNull("the asynchronous transfer is pending", field<Any?>(requireNotNull(service), "meetingReplacementOperation"))

        assertTrue("Dictation can be selected while the note transfer is pending",
            coordinator.changeMode(TranscriptionMode.DICTATION))
        awaitMainCondition { coordinator.snapshot().mode == TranscriptionMode.DICTATION }
        gate.release.countDown()

        awaitMainCondition {
            coordinator.snapshot().mode == TranscriptionMode.DICTATION &&
                field<Any?>(requireNotNull(service), "meetingReplacementOperation") == null &&
                notesStorage.all().firstOrNull { it.id == note.id }?.meeting == expectedDocument
        }

        val storedSource = requireNotNull(notesStorage.all().firstOrNull { it.id == note.id })
        assertEquals(expectedDocument, storedSource.meeting)
        assertEquals(listOf(image), storedSource.images)
        assertEquals("Sophie\nBonjour [[Image 1]] jeudi", storedSource.text)
        assertNull("an old transfer callback cannot reactivate the Meeting note in Dictation",
            field<String?>(requireNotNull(service), "activeNoteId"))
        assertEquals("the incoming note is not rewritten", incomingRecord, notesPreferences.getString(incoming.id, null))
        assertFalse("the overlay is not left hidden", field<Boolean>(requireNotNull(service), "panelHidden"))
        assertEquals(View.VISIBLE, field<View?>(requireNotNull(service), "liveScroll")?.visibility)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `a newer Meeting choice during a blocked transfer restores the current document instead of installing the incoming note`() {
        val (note, image, originalDocument) = createSavedMeetingNote()
        val gate = PersistenceGate()
        releaseGates += gate.release
        val draftOwnership = newDraftOwnership(persist = { path, document ->
            gate.entered.countDown()
            check(gate.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Test did not release the blocked draft write" }
            MeetingDraftStore(path).save(document)
        })
        startMeetingService(draftOwnership)
        openSavedMeeting(note)
        val controller = requireNotNull(ownedController)
        val turnId = originalDocument.turns.single().id
        editTurn(controller, turnId, "Bonjour [[Image 1]] jeudi")
        val expectedDocument = onMain { controller.state.document }
        val oldPanel = requireNotNull(field<MeetingPanelController?>(requireNotNull(service), "meetingPanelController"))

        val incoming = persistMeetingNote(documentWithText("rapid-mode", "Ne pas installer"), "projection obsolète")
        val incomingRecord = requireNotNull(notesPreferences.getString(incoming.id, null))
        onMain { invoke(requireNotNull(service), "openNote", incoming) }
        assertTrue("replacement is waiting on the old document's writer",
            gate.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertTrue("Dictation is selected briefly", coordinator.changeMode(TranscriptionMode.DICTATION))
        assertTrue("the newer Meeting selection wins", coordinator.changeMode(TranscriptionMode.MEETING))
        gate.release.countDown()

        awaitMainCondition {
            coordinator.snapshot().mode == TranscriptionMode.MEETING &&
                field<Any?>(requireNotNull(service), "meetingReplacementOperation") == null &&
                field<MeetingRecordingController?>(requireNotNull(service), "meetingRecordingController") === controller &&
                field<MeetingPanelController?>(requireNotNull(service), "meetingPanelController") === oldPanel
        }

        assertTrue("the current Meeting document remains attached",
            field<Boolean>(requireNotNull(service), "meetingSurfaceOpen"))
        assertEquals("the outgoing edit was durably published", expectedDocument,
            notesStorage.all().firstOrNull { it.id == note.id }?.meeting)
        assertEquals("the incoming note was not rewritten", incomingRecord,
            notesPreferences.getString(incoming.id, null))
        assertEquals("the original note identity remains current", note.id,
            field<String?>(requireNotNull(service), "activeNoteId"))
        assertEquals("Pastille Réunion", field<View>(requireNotNull(service), "pill").contentDescription)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `failed draft persistence during a mode transition keeps the live document and durable checkpoint without installing the incoming note`() {
        val (note, image, originalDocument) = createSavedMeetingNote()
        MeetingDraftStore(draftFile).save(originalDocument)
        val writeGate = PersistenceGate()
        releaseGates += writeGate.release
        val shouldFailPersistence = AtomicInteger(1)
        failingDraftWrites = shouldFailPersistence
        val draftOwnership = newDraftOwnership(persist = { path, document ->
            writeGate.entered.countDown()
            check(writeGate.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Test did not release the blocked draft write" }
            if (shouldFailPersistence.get() != 0) throw IOException("fixture-only storage refusal")
            MeetingDraftStore(path).save(document)
        })
        startMeetingService(draftOwnership)
        openSavedMeeting(note)
        val controller = requireNotNull(ownedController)
        val turnId = originalDocument.turns.single().id
        editTurn(controller, turnId, "Bonjour [[Image 1]] jeudi")
        val expectedDocument = onMain { controller.state.document }

        val incoming = persistMeetingNote(documentWithText("failure-incoming", "À ne pas installer"), "texte initial")
        val incomingRecord = requireNotNull(notesPreferences.getString(incoming.id, null))
        onMain { invoke(requireNotNull(service), "openNote", incoming) }
        assertTrue("replacement reaches the injected draft persistence barrier",
            writeGate.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertTrue("the requested mode change remains a coordinator operation",
            coordinator.changeMode(TranscriptionMode.DICTATION))
        writeGate.release.countDown()

        awaitMainCondition {
            field<Any?>(requireNotNull(service), "meetingReplacementOperation") == null
        }

        val published = requireNotNull(notesStorage.all().firstOrNull { it.id == note.id })
        assertEquals("a failed flush is not falsely published over the previous note", originalDocument, published.meeting)
        assertEquals("the image attachment remains available", listOf(image), published.images)
        assertEquals("the live controller retains the user's edit for recovery", expectedDocument, controller.state.document)
        val durableDraft = MeetingDraftStore(draftFile).load() as? MeetingDocumentRead.Ready
        assertNotNull("failed persistence leaves the prior durable checkpoint available", durableDraft)
        assertEquals(originalDocument, requireNotNull(durableDraft).document)
        assertEquals("the incoming note is not installed", note.id, field<String?>(requireNotNull(service), "activeNoteId"))
        assertEquals(incomingRecord, notesPreferences.getString(incoming.id, null))
        assertTrue("the failure is surfaced instead of an unqualified success",
            ShadowToast.getTextOfLatestToast().toString().contains("Échec de sauvegarde"))
        assertNull("no run is active regardless of the coordinator's valid post-transition mode",
            coordinator.snapshot().activeRunMode)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `a poisoned Dictation notification after opening a Meeting document does not detach it and it remains copyable`() {
        val (note, _, document) = createSavedMeetingNote()
        val staleLoad = requireNotNull(coordinator.beginDictationLoad())
        coordinator.reportUncertainClose()
        val transferGate = PersistenceGate()
        releaseGates += transferGate.release

        startMeetingService(newDraftOwnership(persist = { path, checkpoint ->
            transferGate.entered.countDown()
            check(transferGate.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Test did not release the gated Meeting checkpoint"
            }
            MeetingDraftStore(path).save(checkpoint)
        }), selectMeeting = false)
        openSavedMeeting(note)
        val currentService = requireNotNull(service)
        val controller = requireNotNull(ownedController)
        val panel = requireNotNull(field<MeetingPanelController?>(currentService, "meetingPanelController"))
        assertTrue(coordinator.snapshot().poisoned)
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)

        // Completing an already-invalidated preload dispatches another D/poison snapshot.
        staleLoad.completeWithoutPublish()
        awaitMainCondition {
            coordinator.snapshot().pendingDictationLoads == 0 &&
                field<Boolean>(currentService, "meetingSurfaceOpen") &&
                field<MeetingRecordingController?>(currentService, "meetingRecordingController") === controller &&
                field<MeetingPanelController?>(currentService, "meetingPanelController") === panel &&
                !field<AtomicBoolean>(currentService, "localLoading").get()
        }
        assertNull("a poison notification in the already-selected mode must not start a document transfer",
            field<Any?>(currentService, "meetingReplacementOperation"))
        assertTrue(field<Boolean>(currentService, "meetingDocumentRestored"))
        assertEquals(MeetingRecordingPhase.DOCUMENT, controller.state.phase)
        transferGate.release.countDown()

        val actions = onMain { invoke(currentService, "meetingPanelActions") as MeetingPanelActions }
        onMain { actions.documentAction(MeetingPanelDocumentAction.COPY, null) }
        awaitMainCondition {
            field<Any?>(currentService, "meetingDocumentActionOperation") == null
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            "the still-open poisoned document remains available through its structured projection",
            MeetingProjection.text(document, note.images.mapTo(mutableSetOf()) { it.number }),
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString(),
        )
        assertTrue(field<Boolean>(currentService, "meetingSurfaceOpen"))
        assertSame(controller, field<MeetingRecordingController?>(currentService, "meetingRecordingController"))
        assertSame(panel, field<MeetingPanelController?>(currentService, "meetingPanelController"))
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `a newer Meeting choice during Dictation identity restoration reopens the saved document without audio`() {
        val (note, image, originalDocument) = createSavedMeetingNote()
        startMeetingService(newDraftOwnership())
        openSavedMeeting(note)
        val previousController = requireNotNull(ownedController)

        // restoreDictationDraftContext changes NOTE to MESSAGE because this fixture has no
        // Dictation note id. The preference callback deterministically selects Meeting at that
        // exact point, after the old Meeting controller and claim have been closed/relinquished.
        check(draftPreferences.edit()
            .remove("note_id")
            .putString("purpose", DictationPurpose.NOTE.name)
            .commit())
        val restoreObservedAfterDetach = AtomicInteger()
        val modeChangeAccepted = AtomicInteger()
        val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "purpose" &&
                draftPreferences.getString("purpose", null) == DictationPurpose.MESSAGE.name &&
                coordinator.snapshot().mode == TranscriptionMode.DICTATION &&
                restoreObservedAfterDetach.compareAndSet(0, 1)
            ) {
                val currentService = requireNotNull(service)
                if (!field<Boolean>(currentService, "meetingSurfaceOpen") &&
                    field<MeetingRecordingController?>(currentService, "meetingRecordingController") == null &&
                    field<Any?>(currentService, "meetingDraftClaim") == null
                ) {
                    restoreObservedAfterDetach.set(2)
                }
                if (coordinator.changeMode(TranscriptionMode.MEETING)) modeChangeAccepted.set(1)
            }
        }
        draftPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        try {
            assertTrue(coordinator.changeMode(TranscriptionMode.DICTATION))
            awaitMainCondition {
                restoreObservedAfterDetach.get() == 2 && modeChangeAccepted.get() == 1 &&
                    coordinator.snapshot().mode == TranscriptionMode.MEETING &&
                    field<Boolean>(requireNotNull(service), "meetingSurfaceOpen") &&
                    field<MeetingRecordingController?>(requireNotNull(service), "meetingRecordingController") != null
            }
        } finally {
            draftPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }

        val reopened = requireNotNull(field<MeetingRecordingController?>(requireNotNull(service), "meetingRecordingController"))
        assertNotSame("the closed controller is restored from the durable structured note", previousController, reopened)
        assertEquals(originalDocument, reopened.state.document)
        assertEquals(note.id, field<String?>(requireNotNull(service), "activeNoteId"))
        assertEquals(listOf(image), notesStorage.all().firstOrNull { it.id == note.id }?.images)
        assertEquals(originalDocument, notesStorage.all().firstOrNull { it.id == note.id }?.meeting)
        assertEquals("Pastille Réunion", field<View>(requireNotNull(service), "pill").contentDescription)
        assertNoNativeSessionOrMicrophone()
    }

    private fun createSavedMeetingNote(): Triple<TranscriptNote, NoteImage, MeetingDocument> {
        val imageId = UUID.randomUUID().toString()
        val image = NoteImage(imageId, 1, NoteImageKind.CAMERA, System.currentTimeMillis(), 24, 16)
        ownedImageIds += imageId
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(40, 120, 80))
        }
        try {
            check(imageStore.file(imageId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) })
            check(imageStore.thumbnail(imageId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) })
        } finally {
            bitmap.recycle()
        }

        val sessionId = UUID.randomUUID().toString()
        val document = MeetingDocument(
            sessionId = sessionId,
            runId = UUID.randomUUID().toString(),
            participants = listOf(MeetingParticipant(id = "speaker-sophie", ordinal = 1, channel = 1, name = "Sophie")),
            turns = listOf(MeetingTurn(
                id = "$sessionId:utterance:1:turn:1",
                utteranceId = 1L,
                startMs = 0,
                endMs = 1_200,
                recognizedText = "Bonjour [[Image 1]] mardi",
                automaticParticipantId = "speaker-sophie",
                attributionStable = true,
            )),
            finished = true,
        )
        return Triple(persistMeetingNote(document, "CACHE PLAT OBSOLÈTE : mardi", listOf(image)), image, document)
    }

    private fun createPriorDictationContext(): TranscriptNote {
        val text = "Brouillon Dictée à reprendre"
        val note = TranscriptNote(
            id = UUID.randomUUID().toString(),
            title = "Note Dictée précédente",
            text = text,
            updatedAt = System.currentTimeMillis(),
        )
        ownedNotes.putIfAbsent(note.id, notesPreferences.getString(note.id, null))
        notesStorage.put(note)
        check(draftPreferences.edit()
            .putString("purpose", DictationPurpose.NOTE.name)
            .putString("note_id", note.id)
            .putString("text", text)
            .putString("captures", "[]")
            .commit()) { "Could not install the prior Dictation draft fixture" }
        return note
    }

    private fun documentWithText(label: String, body: String): MeetingDocument {
        val sessionId = UUID.randomUUID().toString()
        return MeetingDocument(
            sessionId = sessionId,
            runId = UUID.randomUUID().toString(),
            participants = listOf(MeetingParticipant(id = "speaker-$label", ordinal = 1, channel = 1, name = "$label")),
            turns = listOf(MeetingTurn(
                id = "$sessionId:utterance:1:turn:1",
                utteranceId = 1L,
                startMs = 0,
                endMs = 500,
                recognizedText = body,
                automaticParticipantId = "speaker-$label",
                attributionStable = true,
            )),
            finished = true,
        )
    }

    private fun persistMeetingNote(
        document: MeetingDocument,
        staleText: String,
        images: List<NoteImage> = emptyList(),
    ): TranscriptNote {
        val note = TranscriptNote(
            id = document.sessionId,
            title = "Réunion de transition",
            text = staleText,
            updatedAt = System.currentTimeMillis(),
            images = images,
            meeting = document,
        )
        ownedNotes.putIfAbsent(note.id, notesPreferences.getString(note.id, null))
        notesStorage.put(note)
        return note
    }

    private fun newDraftOwnership(
        persist: (File, MeetingDocument) -> Unit = { path, document -> MeetingDraftStore(path).save(document) },
        clear: ((File) -> Unit)? = null,
    ): MeetingDraftOwnership {
        val worker = Executors.newSingleThreadExecutor()
        ownershipExecutor = worker
        return MeetingDraftOwnership(
            executor = MeetingDraftOwnershipExecutor { task -> worker.execute(task) },
            writerFactory = MeetingDraftOwnershipWriterFactory { path, relay ->
                TestDraftScheduler().also(schedulers::add).let { scheduler ->
                    MeetingDraftWriter(
                        persistence = MeetingDraftPersistence { document -> persist(path, document) },
                        scheduler = scheduler,
                        debounceMs = 60_000,
                        onErrorChanged = relay::publish,
                    )
                }
            },
            clearer = MeetingDraftOwnershipClearer { path ->
                if (clear == null) MeetingDraftStore(path).clear() else clear(path)
            },
        )
    }

    private fun startMeetingService(draftOwnership: MeetingDraftOwnership, selectMeeting: Boolean = true) {
        if (selectMeeting) {
            assertTrue("Meeting is selected before the real Service is created", coordinator.changeMode(TranscriptionMode.MEETING))
        }
        val overrides = OverlayService.MeetingTestOverrides(
            draftOwnership = draftOwnership,
            draftFile = draftFile,
            modelStore = MeetingModelStore.shared(context),
            modelAvailability = MeetingModelAvailabilityPort { MeetingModelAvailability.READY },
            reservation = reservation,
            sessionFactory = sessions,
            microphoneFactory = microphones,
        )
        assertTrue("the one-shot native-boundary override is installed before onCreate",
            OverlayService.setMeetingTestOverridesFactoryForTest { overrides })
        val created = Robolectric.buildService(OverlayService::class.java).create()
        serviceController = created
        service = created.get()
        OverlayService.clearMeetingTestOverridesFactoryForTest()
        onMain { invokeNoArgs(requireNotNull(service), "showButton") }
    }

    private fun openSavedMeeting(note: TranscriptNote) {
        onMain { invoke(requireNotNull(service), "openNote", note) }
        awaitMainCondition {
            val current = requireNotNull(service)
            field<MeetingRecordingController?>(current, "meetingRecordingController")?.also { ownedController = it } != null &&
                field<MeetingPanelController?>(current, "meetingPanelController") != null
        }
    }

    private fun editTurn(controller: MeetingRecordingController, turnId: String, text: String) {
        onMain { controller.editTurn(turnId, text) }
        awaitMainCondition {
            onMain { controller.state.document.turns.firstOrNull { it.id == turnId }?.editedText == text }
        }
    }

    private fun assertNoNativeSessionOrMicrophone() {
        assertEquals(0, nativeCalls.reservations.get())
        assertEquals(0, nativeCalls.sessions.get())
        assertEquals(0, nativeCalls.microphones.get())
        assertTrue(sessions.sessions.isEmpty())
        assertTrue(microphones.microphones.isEmpty())
        assertNull(coordinator.snapshot().activeRunMode)
        assertFalse(OverlayService.micArmed)
        assertNull(field<Any?>(requireNotNull(service), "activeRun"))
        assertNull(field<Any?>(requireNotNull(service), "audioRecord"))
    }

    private fun awaitMainCondition(timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val looper = org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            looper.idle()
            if (condition()) return
            Thread.sleep(5L)
        }
        looper.idle()
        assertTrue("condition should complete within $timeoutMs ms", condition())
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val result = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        val completed = AtomicInteger()
        Handler(Looper.getMainLooper()).post {
            try {
                result.set(action())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                completed.set(1)
            }
        }
        awaitMainCondition { completed.get() == 1 }
        failure.get()?.let { throw AssertionError("main-thread fixture action failed", it) }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private fun invoke(target: Any, methodName: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { candidate ->
            candidate.name == methodName && candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.zip(args).all { (type, argument) ->
                    argument == null || type.isAssignableFrom(argument.javaClass) ||
                        (type.isPrimitive && argument is Boolean && type == Boolean::class.javaPrimitiveType)
                }
        }.apply { isAccessible = true }
        return method.invoke(target, *args)
    }

    private fun invokeNoArgs(target: Any, methodName: String): Unit {
        target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(target)
    }

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(target) as T
        }

    private fun restorePreferences(name: String, values: Map<String, Any?>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "Could not restore $name preferences" }
    }

    private class TestDraftScheduler : MeetingDraftScheduler {
        private val executor = ScheduledThreadPoolExecutor(1)

        override fun schedule(delayMs: Long, task: () -> Unit): MeetingDraftScheduledTask {
            val scheduled = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            return MeetingDraftScheduledTask { scheduled.cancel(false); Unit }
        }

        override fun execute(task: () -> Unit): Unit {
            executor.execute(task)
        }

        override fun shutdown(): Unit {
            executor.shutdown()
        }
    }

    private data class PersistenceGate(
        val entered: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1),
    )

    private class NativeCallCounts {
        val reservations = AtomicInteger()
        val sessions = AtomicInteger()
        val microphones = AtomicInteger()
    }

    private class TestReservationPort(private val calls: NativeCallCounts) : MeetingNativeReservationPort {
        override fun request(runId: String): MeetingNativeReservationRequest {
            calls.reservations.incrementAndGet()
            return TestReservationRequest()
        }
    }

    private class TestReservationRequest : MeetingNativeReservationRequest {
        override val lease = CompletableFuture<MeetingNativeRuntimeLease>()
        override fun cancel(): Unit { lease.completeExceptionally(java.util.concurrent.CancellationException()) }
    }

    private class TestRuntimeLease : MeetingNativeRuntimeLease {
        override fun isCurrentAndUsable(): Boolean = true
        override fun release(): Unit = Unit
        override fun poison(): Unit = Unit
    }

    private class TestSessionFactory(private val calls: NativeCallCounts) : MeetingSessionFactoryPort {
        val sessions = CopyOnWriteArrayList<TestSession>()
        override fun start(
            runId: String,
            language: String,
            onReady: () -> Unit,
            onUpdate: (MeetingHypothesis) -> Unit,
            onFailure: (String) -> Unit,
        ): MeetingSession {
            calls.sessions.incrementAndGet()
            return TestSession(runId).also { sessions += it; onReady() }
        }
    }

    private class TestSession(val runId: String) : MeetingSession {
        override val closed = CompletableFuture<Unit>()
        override val queuedAudioMs: Long = 0L
        override fun acceptPcm16(buffer: ByteArray, length: Int): Boolean = true
        override fun checkpoint(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        override fun finish(): Unit { closed.complete(Unit) }
        override fun cancel(): Unit { closed.complete(Unit) }
        override fun close(): Unit = cancel()
    }

    private class TestMicrophoneFactory(private val calls: NativeCallCounts) : MeetingMicrophoneFactoryPort {
        val microphones = CopyOnWriteArrayList<TestMicrophone>()
        override fun create(): MeetingMicrophonePort {
            calls.microphones.incrementAndGet()
            return TestMicrophone().also(microphones::add)
        }
    }

    private class TestMicrophone : MeetingMicrophonePort {
        override fun start(onPcm16: (ByteArray, Int) -> Boolean, onFailure: (String) -> Unit): Boolean = true
        override fun stopAndJoin(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val TIMEOUT_MS = TIMEOUT_SECONDS * 1_000L
    }
}
