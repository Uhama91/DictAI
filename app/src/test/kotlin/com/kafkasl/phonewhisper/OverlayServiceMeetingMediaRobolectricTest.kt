package com.kafkasl.phonewhisper

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipExecutor
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipRequest
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipWriterFactory
import com.kafkasl.phonewhisper.meeting.MeetingDraftPersistence
import com.kafkasl.phonewhisper.meeting.MeetingDraftScheduledTask
import com.kafkasl.phonewhisper.meeting.MeetingDraftScheduler
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingDocumentRead
import com.kafkasl.phonewhisper.meeting.MeetingDraftStore
import com.kafkasl.phonewhisper.meeting.MeetingDraftWriter
import com.kafkasl.phonewhisper.meeting.MeetingImageAnchor
import com.kafkasl.phonewhisper.meeting.MeetingHypothesis
import com.kafkasl.phonewhisper.meeting.MeetingMicrophoneFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingMicrophonePort
import com.kafkasl.phonewhisper.meeting.MeetingWord
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailability
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailabilityPort
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationPort
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationRequest
import com.kafkasl.phonewhisper.meeting.MeetingNativeRuntimeLease
import com.kafkasl.phonewhisper.meeting.MeetingPanelActions
import com.kafkasl.phonewhisper.meeting.MeetingPanelController
import com.kafkasl.phonewhisper.meeting.MeetingPanelDocumentAction
import com.kafkasl.phonewhisper.meeting.MeetingParticipant
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPhase
import com.kafkasl.phonewhisper.meeting.MeetingSession
import com.kafkasl.phonewhisper.meeting.MeetingSessionFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingTurn
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceMeetingMediaRobolectricTest {
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
    private lateinit var reservation: ControlledReservationPort
    private lateinit var sessions: ControlledSessionFactory
    private lateinit var microphones: ControlledMicrophoneFactory
    private var previousMode = TranscriptionMode.DICTATION
    private var previousAnimatorDurationScale = 1f
    private var previousClipboard: ClipData? = null
    private var serviceController: ServiceController<OverlayService>? = null
    private var service: OverlayService? = null
    private var accessibilityFixtureController: ServiceController<WhisperAccessibilityService>? = null
    private var previousAccessibilityService: WhisperAccessibilityService? = null
    private var ownedController: MeetingRecordingController? = null
    private var fixtureDraftOwnership: MeetingDraftOwnership? = null
    private var ownedPendingCaptureId: String? = null
    private val ownedNotes = linkedMapOf<String, String?>()
    private val ownedImageIds = linkedSetOf<String>()
    private val ownedExportDirectories = linkedSetOf<File>()
    private var manualDraftScheduler: ManualDraftScheduler? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        previousAccessibilityService = WhisperAccessibilityService.connected
        val globalSettings = context.contentResolver
        previousAnimatorDurationScale = Settings.Global.getFloat(
            globalSettings,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        check(Settings.Global.putFloat(globalSettings, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)) {
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
        assertNull("the fixture must not take ownership of another test's pending capture", NoteImageStore(context).pending())
        imageStore = NoteImageStore(context)
        previousClipboard = context.getSystemService(ClipboardManager::class.java)?.primaryClip

        draftDirectory = File(context.cacheDir, "meeting-media-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Could not create an isolated meeting-media draft directory" }
        }
        draftFile = File(draftDirectory, "draft.json")
        nativeCalls = NativeCallCounts()
        reservation = ControlledReservationPort(nativeCalls)
        sessions = ControlledSessionFactory(nativeCalls)
        microphones = ControlledMicrophoneFactory(nativeCalls)
        ShadowSettings.setCanDrawOverlays(true)
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

        cleanup { OverlayService.clearMeetingTestOverridesFactoryForTest() }
        cleanup { manualDraftScheduler?.releaseHeldWrites() }
        microphones.microphones.forEach { microphone ->
            cleanup { microphone.stopCompletion.complete(Unit) }
        }
        sessions.sessions.forEach { session -> cleanup { session.closed.complete(Unit) } }

        var controllerToClose: MeetingRecordingController? = ownedController
        var claimRequest: MeetingDraftOwnershipRequest? = null
        val currentService = service
        if (currentService != null) cleanup {
            onMain {
                if (controllerToClose == null) {
                    controllerToClose = field(currentService, "meetingRecordingController")
                }
                claimRequest = field(currentService, "meetingDraftClaimRequest")
                serviceController?.destroy()
                controllerToClose?.destroy()
                Unit
            }
        }

        cleanup {
            accessibilityFixtureController?.destroy()
            setConnectedAccessibilityServiceForFixture(previousAccessibilityService)
            accessibilityFixtureController = null
            check(WhisperAccessibilityService.connected === previousAccessibilityService) {
                "The accessibility service connection must be restored after the fixture"
            }
        }

        var draftCloseConfirmed = controllerToClose == null && claimRequest == null
        cleanup {
            val close = controllerToClose?.destroy()
            if (close != null) {
                close.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                draftCloseConfirmed = true
            }
        }
        cleanup {
            claimRequest?.future?.let { future ->
                runCatching { future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
        }
        cleanup {
            awaitMainCondition { coordinator.snapshot().activeRunMode == null }
        }

        ownedPendingCaptureId?.let { captureId ->
            cleanup {
                if (imageStore.pending()?.id == captureId) imageStore.clearPending(captureId)
            }
        }
        ownedNotes.forEach { (id, previous) -> cleanup {
            val editor = notesPreferences.edit()
            if (previous == null) editor.remove(id) else editor.putString(id, previous)
            check(editor.commit()) { "Could not restore fixture note $id" }
        } }
        ownedImageIds.forEach { imageId -> cleanup { imageStore.delete(imageId) } }
        ownedExportDirectories.forEach { directory -> cleanup {
            if (directory.exists()) check(directory.deleteRecursively()) {
                "Could not remove this fixture's export directory"
            }
        } }
        cleanup { restorePreferences("dictation_draft", draftPreferencesBefore) }
        cleanup { PersistencePrefs(context).transcriptionMode = previousMode }
        cleanup { TranscriptionModeCoordinator.clearProcessForTest() }
        cleanup {
            check(Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                previousAnimatorDurationScale,
            )) { "Could not restore the animator duration scale" }
        }
        cleanup {
            if (draftDirectory.exists()) {
                check(draftCloseConfirmed) { "Refusing to remove the fixture draft before writer closure is confirmed" }
                check(draftDirectory.deleteRecursively()) { "Could not remove the isolated meeting-media draft directory" }
            }
        }
        cleanup {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            if (previousClipboard == null) clipboard?.clearPrimaryClip() else clipboard?.setPrimaryClip(previousClipboard!!)
        }
        cleanup { ShadowSettings.reset() }

        cleanupFailure?.let { throw AssertionError("Meeting media fixture cleanup failed", it) }
    }

    @Test
    fun `copy uses the live structured projection and omits an ignored participant`() {
        val document = editedMeetingDocument(withImage = false)
        val note = persistMeetingNote(document, staleText = "CACHE PLAT OBSOLÈTE : mardi. Voix masquée : secret.")
        startMeetingService()
        openSavedMeeting(note)
        assertNull("the saved turn starts as recognized text, without an edit", onMain {
            requireNotNull(ownedController).state.document.turns.first { it.utteranceId > 0L }.editedText
        })
        val editedDocument = editFirstMeetingTurn("jeudi")

        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "ancien presse-papiers"))
        dispatchDocumentAction(MeetingPanelDocumentAction.COPY)

        awaitMainCondition {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "Sophie\njeudi"
        }
        assertEquals("Sophie\njeudi", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals("the post-open edit is in the live document", "jeudi",
            editedDocument.turns.first { it.utteranceId > 0L }.editedText)
        assertFalse("the stale flat cache is not copied", clipboard.primaryClip?.getItemAt(0)?.text?.contains("CACHE PLAT OBSOLÈTE") == true)
        assertFalse("the ignored participant is omitted from copied text", clipboard.primaryClip?.getItemAt(0)?.text?.contains("secret") == true)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `a known meeting remains copyable when the poisoned coordinator stays in Dictation`() {
        val document = editedMeetingDocument(withImage = false)
        val note = persistMeetingNote(document, staleText = "Texte plat obsolète")
        startMeetingService(selectMeeting = false)
        coordinator.reportUncertainClose()
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        assertTrue(coordinator.snapshot().poisoned)
        assertNull(coordinator.snapshot().activeRunMode)

        openSavedMeeting(note)
        editFirstMeetingTurn("Bonjour jeudi")
        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "ancien contenu"))
        dispatchDocumentAction(MeetingPanelDocumentAction.COPY)

        awaitMainCondition { field<Any?>(requireNotNull(service), "meetingDocumentActionOperation") == null }
        assertEquals("a restored document may be copied without changing poisoned Dictation mode",
            "Sophie\nBonjour jeudi", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        assertTrue(coordinator.snapshot().poisoned)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `copy of an empty projection leaves the clipboard unchanged and reports no text`() {
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            finished = true,
        )
        val note = persistMeetingNote(document, staleText = "")
        startMeetingService()
        openSavedMeeting(note)
        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "ne pas remplacer"))

        dispatchDocumentAction(MeetingPanelDocumentAction.COPY)

        awaitMainCondition { field<Any?>(requireNotNull(service), "meetingDocumentActionOperation") == null }
        assertEquals("an empty projection must not erase or replace prior clipboard content",
            "ne pas remplacer", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue("empty copy receives a visible non-success response",
            ShadowToast.getTextOfLatestToast()?.toString()?.let { it != "Réunion copiée." } == true)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `export saves the structured projection and opens the real export panel with its images`() {
        val image = createFixtureImage(number = 1)
        val document = editedMeetingDocument(withImage = true)
        val note = persistMeetingNote(
            document,
            staleText = "CACHE PLAT OBSOLÈTE : mardi.",
            images = listOf(image),
        )
        startMeetingService()
        openSavedMeeting(note)
        assertNull("the saved turn starts as recognized text, without an edit", onMain {
            requireNotNull(ownedController).state.document.turns.first { it.utteranceId > 0L }.editedText
        })
        val editedDocument = editFirstMeetingTurn("Bonjour [[Image 1]] jeudi")

        dispatchDocumentAction(MeetingPanelDocumentAction.EXPORT)

        awaitMainCondition {
            val current = requireNotNull(service)
            field<OverlayExportPanel?>(current, "exportPanel") != null &&
                field<String?>(current, "exportNoteId") == editedDocument.sessionId
        }
        val current = requireNotNull(service)
        val panel = requireNotNull(field<OverlayExportPanel?>(current, "exportPanel"))
        assertTrue("the real panel is attached to the service's live content", panel.parent is ViewGroup)
        assertEquals(editedDocument.sessionId, field<String?>(current, "exportNoteId"))

        val panelNote = field<TranscriptNote>(panel, "note")
        assertEquals("the panel receives the complete live structured meeting document", editedDocument, panelNote.meeting)
        assertEquals("the panel keeps the exact image metadata", listOf(image), panelNote.images)
        assertTrue("the exported image bytes belong to this fixture", imageStore.file(image.id).isFile)

        awaitMainCondition {
            val saved = notesStorage.all().firstOrNull { it.id == editedDocument.sessionId }
            saved?.meeting == editedDocument && saved.text == "Sophie\nBonjour [[Image 1]] jeudi"
        }
        val saved = requireNotNull(notesStorage.all().firstOrNull { it.id == editedDocument.sessionId })
        assertEquals(editedDocument, saved.meeting)
        assertEquals(listOf(image), saved.images)
        assertEquals("Sophie\nBonjour [[Image 1]] jeudi", saved.text)
        val rawRecord = requireNotNull(notesPreferences.getString(editedDocument.sessionId, null))
        assertTrue("the durable export source remains a structured record", JSONObject(rawRecord).has("meeting"))
        assertFalse("the stale flattened note is never published", saved.text.contains("CACHE PLAT OBSOLÈTE"))
        assertFalse("the ignored participant is absent from the durable projection", saved.text.contains("secret"))

        awaitMainCondition {
            val activePanel = field<OverlayExportPanel?>(current, "exportPanel") ?: return@awaitMainCondition true
            val result = field<OverlayExportResult?>(activePanel, "currentResult")
            val status = field<android.widget.TextView>(activePanel, "status").text.toString()
            if (result != null) ownedExportDirectories += result.directory
            result != null || !status.startsWith("Préparation de ")
        }
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `export of an empty new meeting does not create a note or an export panel`() {
        startMeetingService()
        openNewMeetingPanel()
        val sessionId = requireNotNull(ownedController).state.document.sessionId

        dispatchDocumentAction(MeetingPanelDocumentAction.EXPORT)

        awaitMainCondition { field<Any?>(requireNotNull(service), "meetingDocumentActionOperation") == null }
        assertNull("an empty new meeting must not create a note", notesStorage.all().firstOrNull { it.id == sessionId })
        assertNull(field<OverlayExportPanel?>(requireNotNull(service), "exportPanel"))
        assertEquals("Aucune transcription à exporter.", ShadowToast.getTextOfLatestToast()?.toString())
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `export of a known meeting publishes an intentional empty edit without opening an empty export`() {
        val document = editedMeetingDocument(withImage = false)
        val note = persistMeetingNote(document, staleText = "ancien texte aplati")
        startMeetingService()
        openSavedMeeting(note)
        val emptied = editFirstMeetingTurn("")

        dispatchDocumentAction(MeetingPanelDocumentAction.EXPORT)

        awaitMainCondition { field<Any?>(requireNotNull(service), "meetingDocumentActionOperation") == null }
        val saved = requireNotNull(notesStorage.all().firstOrNull { it.id == document.sessionId })
        assertEquals(emptied, saved.meeting)
        assertEquals("the intentional deletion remains in the structured record", "", saved.meeting?.turns?.first()?.editedText)
        assertEquals("the empty projection updates the existing note instead of resurrecting stale text", "", saved.text)
        assertNull("an empty note is not handed to the export UI", field<OverlayExportPanel?>(requireNotNull(service), "exportPanel"))
        assertEquals("Réunion vide enregistrée.", ShadowToast.getTextOfLatestToast()?.toString())
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `copy does not run when the controller leaves a stable phase before its flush barrier`() {
        val scheduler = ManualDraftScheduler()
        startMeetingService(scheduler)
        openNewMeetingPanel()
        val controller = requireNotNull(ownedController)
        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "ne pas remplacer"))
        scheduler.holdNextWrite()

        dispatchDocumentAction(MeetingPanelDocumentAction.COPY)
        awaitMainCondition { field<Any?>(requireNotNull(service), "meetingDocumentActionOperation") != null }
        onMain { controller.start("fr") }
        awaitMainCondition { onMain { controller.state.phase == MeetingRecordingPhase.PREPARING } }
        scheduler.runHeldWrite()
        awaitMainCondition { field<Any?>(requireNotNull(service), "meetingDocumentActionOperation") == null }

        assertEquals("a stale flush callback cannot perform the copy after preparation begins",
            "ne pas remplacer", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals("preparation requested only a lease; no native session started", 1, nativeCalls.reservations.get())
        assertEquals(0, nativeCalls.sessions.get())
        assertEquals(0, nativeCalls.microphones.get())
        assertTrue(sessions.sessions.isEmpty())
        assertTrue(microphones.microphones.isEmpty())
        assertFalse(OverlayService.micArmed)
        assertNull(field<Any?>(requireNotNull(service), "activeRun"))
        assertNull(field<Any?>(requireNotNull(service), "audioRecord"))
    }

    @Test
    fun `document actions are refused while the real controller is preparing or pausing`() {
        startMeetingService()
        openNewMeetingPanel()
        val controller = requireNotNull(ownedController)
        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "ne pas modifier"))
        val notesBefore = notesPreferences.all.toMap()
        val draftBefore = draftPreferences.all.toMap()

        var start: CompletableFuture<Unit>? = null
        onMain { start = controller.start("fr") }
        awaitMainCondition { onMain { controller.state.phase == MeetingRecordingPhase.PREPARING } }
        val preparingDocument = onMain { controller.state.document }
        dispatchAllDocumentActions()
        assertEquals("preparing actions do not edit the document", preparingDocument, onMain { controller.state.document })
        assertEquals("preparing actions preserve clipboard", "ne pas modifier", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertNull("preparing actions do not reserve camera or screenshot media", imageStore.pending())
        assertNull(field<OverlayExportPanel?>(requireNotNull(service), "exportPanel"))
        assertEquals(notesBefore, notesPreferences.all.toMap())
        assertEquals(draftBefore, draftPreferences.all.toMap())

        val lease = FakeRuntimeLease()
        reservation.requests.single().grant(lease)
        awaitMainCondition {
            onMain {
                controller.state.phase == MeetingRecordingPhase.LISTENING &&
                    sessions.sessions.size == 1 && microphones.microphones.size == 1
            }
        }
        requireNotNull(start).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        var pause: CompletableFuture<Unit>? = null
        onMain { pause = controller.pause() }
        awaitMainCondition { onMain { controller.state.phase == MeetingRecordingPhase.PAUSING } }
        val pausingDocument = onMain { controller.state.document }
        dispatchAllDocumentActions()
        assertEquals("pausing actions do not edit the document", pausingDocument, onMain { controller.state.document })
        assertEquals("pausing actions preserve clipboard", "ne pas modifier", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertNull("pausing actions do not reserve camera or screenshot media", imageStore.pending())
        assertNull(field<OverlayExportPanel?>(requireNotNull(service), "exportPanel"))
        assertEquals(notesBefore, notesPreferences.all.toMap())
        assertEquals("the native session and recorder remain the ones used to reach PAUSING", 1, sessions.sessions.size)
        assertEquals(1, microphones.microphones.size)
        assertEquals(1, nativeCalls.reservations.get())
        assertEquals(1, nativeCalls.sessions.get())
        assertEquals(1, nativeCalls.microphones.get())

        microphones.microphones.single().stopCompletion.complete(Unit)
        awaitMainCondition { onMain { controller.state.phase == MeetingRecordingPhase.PAUSED } }
        requireNotNull(pause).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `photo on an empty saved meeting flushes a documentary turn and launches the real camera activity`() {
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            finished = true,
        )
        val note = persistMeetingNote(document, staleText = "")
        startMeetingService()
        openSavedMeeting(note)

        dispatchDocumentAction(MeetingPanelDocumentAction.PHOTO)

        awaitMainCondition {
            val pendingNow = imageStore.pending()?.takeIf {
                it.noteId == document.sessionId && it.meetingAnchor != null
            }
            val durableDocument = (MeetingDraftStore(draftFile).load() as? MeetingDocumentRead.Ready)?.document
            val durableTurn = durableDocument?.turns?.firstOrNull {
                it.id == "${document.sessionId}:document:turn"
            }
            val cameraHandoffStarted = onMain {
                field<Boolean>(requireNotNull(service), "captureWindowsHidden")
            }
            pendingNow != null && durableTurn?.utteranceId == 0L && cameraHandoffStarted
        }
        val pending = requireNotNull(imageStore.pending())
        ownedPendingCaptureId = pending.id
        assertEquals(NoteImageKind.CAMERA, pending.kind)
        assertTrue(pending.batch)
        assertEquals(document.sessionId, pending.meetingAnchor?.sessionId)
        assertEquals("${document.sessionId}:document:turn", pending.meetingAnchor?.turnId)
        assertEquals(0, pending.meetingAnchor?.offsetUtf16)

        val savedDraft = MeetingDraftStore(draftFile).load() as? MeetingDocumentRead.Ready
        assertNotNull("the controller flushes the documentary turn before handing off", savedDraft)
        val documentTurn = requireNotNull(savedDraft).document.turns.single()
        assertEquals(pending.meetingAnchor?.turnId, documentTurn.id)
        assertEquals(0L, documentTurn.utteranceId)
        assertEquals("", documentTurn.recognizedText)
        assertEquals("", documentTurn.editedText ?: "")
        val savedNote = notesStorage.all().firstOrNull { it.id == document.sessionId }
        assertNotNull("the structured note is published before opening the camera", savedNote)
        assertEquals(savedDraft.document, savedNote?.meeting)

        val appShadow = Shadows.shadowOf(context.applicationContext as Application)
        val cameraIntent = appShadow.nextStartedActivity
        assertNotNull("the production NoteCameraActivity is launched after the flush", cameraIntent)
        assertEquals(NoteCameraActivity::class.java.name, cameraIntent?.component?.className)
        assertEquals(pending.id, cameraIntent?.getStringExtra("captureId"))
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `accepted photo batch is delivered into the structured meeting document`() {
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            finished = true,
        )
        val note = persistMeetingNote(document, staleText = "")
        startMeetingService()
        openSavedMeeting(note)

        dispatchDocumentAction(MeetingPanelDocumentAction.PHOTO)
        awaitMainCondition {
            imageStore.pending()?.let { it.noteId == document.sessionId && it.meetingAnchor != null } == true
        }
        val reservation = requireNotNull(imageStore.pending())
        ownedPendingCaptureId = reservation.id
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(35, 145, 80))
        }
        try {
            imageStore.store(reservation.id, bitmap, NoteImageKind.CAMERA)
        } finally {
            bitmap.recycle()
        }
        val captured = requireNotNull(imageStore.pending()).allImages.single()
        ownedImageIds += captured.id
        imageStore.acceptBatch(reservation.id)
        onMain { invokeNoArgs(requireNotNull(service), "finishPendingImage") }

        awaitMainCondition {
            val saved = notesStorage.all().firstOrNull { it.id == document.sessionId }
            imageStore.pending() == null && saved?.images?.contains(captured) == true &&
                saved.meeting?.turns?.any { (it.editedText ?: it.recognizedText).contains(captured.marker) } == true
        }
        val saved = requireNotNull(notesStorage.all().firstOrNull { it.id == document.sessionId })
        val liveDocument = onMain { requireNotNull(ownedController).state.document }
        assertEquals(listOf(captured), saved.images)
        assertEquals(saved.meeting, liveDocument)
        assertTrue(imageStore.file(captured.id).isFile)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `accepted batch after service recreation recovers the structured draft without opening a session`() {
        val base = editedMeetingDocument(withImage = false)
        val draftTurnId = base.turns.first().id
        val draftDocument = base.copy(turns = base.turns.map { turn ->
            if (turn.id == draftTurnId) turn.copy(editedText = "correction du brouillon") else turn
        })
        val oldNoteDocument = base.copy(turns = base.turns.map { turn ->
            if (turn.id == draftTurnId) turn.copy(editedText = "ancienne note sauvegardée") else turn
        })
        persistMeetingNote(oldNoteDocument, staleText = "CACHE PLAT OBSOLÈTE")
        MeetingDraftStore(draftFile).save(draftDocument)

        val image = createFixtureImage(number = 1)
        val anchor = MeetingImageAnchor.capture(draftDocument, draftTurnId, 0)
        val pending = persistAcceptedMeetingBatch(anchor, listOf(image))
        startMeetingService()

        awaitMainCondition {
            val saved = notesStorage.all().firstOrNull { it.id == draftDocument.sessionId }
            imageStore.pending() == null && saved?.images == listOf(image) &&
                saved.meeting?.turns?.firstOrNull { it.id == draftTurnId }?.editedText
                    ?.contains("correction du brouillon") == true
        }

        val saved = requireNotNull(notesStorage.all().firstOrNull { it.id == draftDocument.sessionId })
        assertEquals(pending.id, ownedPendingCaptureId)
        assertTrue("the recovered DRAFT takes precedence over the older NOTE",
            saved.meeting!!.turns.first { it.id == draftTurnId }.editedText!!.contains("correction du brouillon"))
        assertFalse(saved.meeting!!.turns.first { it.id == draftTurnId }.editedText!!.contains("ancienne note sauvegardée"))
        assertTrue(saved.meeting!!.turns.any { (it.editedText ?: it.recognizedText).contains(image.marker) })
        assertEquals("Meeting stays selected during document-only recovery", TranscriptionMode.MEETING,
            coordinator.snapshot().mode)
        assertNull(field<MeetingRecordingController?>(requireNotNull(service), "meetingRecordingController"))
        assertNull(field<MeetingPanelController?>(requireNotNull(service), "meetingPanelController"))
        assertNoNativeSessionOrMicrophone()

        val ownership = requireNotNull(fixtureDraftOwnership)
        val nextClaim = ownership.claim(draftFile).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        nextClaim.relinquishLatest().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `accepted batch after service recreation falls back to the matching structured note`() {
        val document = editedMeetingDocument(withImage = false)
        persistMeetingNote(document, staleText = "CACHE PLAT OBSOLÈTE")
        val image = createFixtureImage(number = 1)
        val anchor = MeetingImageAnchor.capture(document, document.turns.first().id, 0)
        persistAcceptedMeetingBatch(anchor, listOf(image))
        assertEquals(MeetingDocumentRead.Absent, MeetingDraftStore(draftFile).load())

        startMeetingService()

        awaitMainCondition {
            val saved = notesStorage.all().firstOrNull { it.id == document.sessionId }
            imageStore.pending() == null && saved?.images == listOf(image) &&
                saved.meeting?.turns?.any { (it.editedText ?: it.recognizedText).contains(image.marker) } == true
        }

        val saved = requireNotNull(notesStorage.all().firstOrNull { it.id == document.sessionId })
        assertEquals(document.sessionId, saved.meeting!!.sessionId)
        assertTrue(saved.meeting!!.turns.any { (it.editedText ?: it.recognizedText).contains(image.marker) })
        assertEquals("Meeting remains selected", TranscriptionMode.MEETING, coordinator.snapshot().mode)
        assertNull(field<MeetingRecordingController?>(requireNotNull(service), "meetingRecordingController"))
        assertNull(field<MeetingPanelController?>(requireNotNull(service), "meetingPanelController"))
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `accepted invalid meeting reservation can be cancelled without images and preserves accepted files`() {
        persistInvalidAcceptedMeetingBatch(images = emptyList())
        startMeetingService()

        awaitMainCondition {
            onMain {
                field<ScreenshotBatchBar?>(requireNotNull(service), "screenshotBatchBar")
                    ?.let { it.cancelButton.visibility == View.VISIBLE && it.cancelButton.isEnabled } == true
            }
        }
        val firstBar = requireNotNull(onMain {
            field<ScreenshotBatchBar?>(requireNotNull(service), "screenshotBatchBar")
        })
        assertFalse(firstBar.captureButton.isEnabled)
        assertFalse(firstBar.acceptButton.isEnabled)
        assertTrue(firstBar.cancelButton.contentDescription.toString().contains("conservées"))
        onMain { assertTrue(firstBar.cancelButton.performClick()) }
        awaitMainCondition { imageStore.pending() == null }

        val image = createFixtureImage(number = 1)
        persistInvalidAcceptedMeetingBatch(images = listOf(image))
        onMain { invokeNoArgs(requireNotNull(service), "recoverPendingImage") }
        awaitMainCondition {
            onMain {
                field<ScreenshotBatchBar?>(requireNotNull(service), "screenshotBatchBar")
                    ?.let { it.cancelButton.visibility == View.VISIBLE && it.cancelButton.isEnabled } == true
            }
        }
        val reusedBar = requireNotNull(onMain {
            field<ScreenshotBatchBar?>(requireNotNull(service), "screenshotBatchBar")
        })
        assertTrue(reusedBar.cancelButton.contentDescription.toString().contains("conservées"))
        onMain { assertTrue(reusedBar.cancelButton.performClick()) }
        awaitMainCondition { imageStore.pending() == null }
        assertTrue("cancelling a malformed reservation keeps accepted image bytes", imageStore.file(image.id).isFile)
        assertTrue("cancelling a malformed reservation keeps accepted thumbnails", imageStore.thumbnail(image.id).isFile)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `accepted batch for another session cannot replace its draft or note`() {
        val target = editedMeetingDocument(withImage = false)
        val other = editedMeetingDocument(withImage = false)
        val targetNote = persistMeetingNote(target, staleText = "NOTE CIBLE")
        MeetingDraftStore(draftFile).save(other)
        val image = createFixtureImage(number = 1)
        val anchor = MeetingImageAnchor.capture(target, target.turns.first().id, 0)
        val pending = persistAcceptedMeetingBatch(anchor, listOf(image))

        startMeetingService()

        awaitMainCondition {
            !field<Boolean>(requireNotNull(service), "imageDeliveryBusy") &&
                field<PendingNoteCapture?>(requireNotNull(service), "acceptedBatchRetry")?.id == pending.id
        }

        assertEquals("the unrelated draft remains the exact recovered document", other,
            (MeetingDraftStore(draftFile).load() as MeetingDocumentRead.Ready).document)
        assertEquals(targetNote.meeting,
            notesStorage.all().firstOrNull { it.id == target.sessionId }?.meeting)
        assertTrue("the accepted batch remains retryable", imageStore.pending()?.let { it.id == pending.id && it.accepted } == true)
        assertTrue("the accepted image file remains available", imageStore.file(image.id).isFile)
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `screenshot action reserves a meeting anchored batch without starting native work`() {
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            finished = true,
        )
        val note = persistMeetingNote(document, staleText = "")
        startMeetingService()
        openSavedMeeting(note)

        assertNull("the fixture will not replace an existing accessibility service", previousAccessibilityService)
        setConnectedAccessibilityServiceForFixture(null)
        dispatchDocumentAction(MeetingPanelDocumentAction.SCREENSHOT)
        awaitMainCondition {
            ShadowToast.getTextOfLatestToast()?.toString() ==
                "Activez le service d’accessibilité DictAI pour capturer l’écran."
        }
        assertNull("screenshot capture is refused when accessibility is disconnected", imageStore.pending())
        assertEquals("Activez le service d’accessibilité DictAI pour capturer l’écran.",
            ShadowToast.getTextOfLatestToast()?.toString())

        connectAccessibilityFixture()

        dispatchDocumentAction(MeetingPanelDocumentAction.SCREENSHOT)

        awaitMainCondition {
            imageStore.pending()?.let { pending ->
                pending.kind == NoteImageKind.SCREENSHOT && pending.batch &&
                    pending.noteId == document.sessionId && pending.meetingAnchor != null &&
                    field<String?>(requireNotNull(service), "screenshotBatchId") == pending.id
            } == true
        }
        val pending = requireNotNull(imageStore.pending())
        ownedPendingCaptureId = pending.id
        assertEquals(document.sessionId, pending.meetingAnchor?.sessionId)
        assertEquals("${document.sessionId}:document:turn", pending.meetingAnchor?.turnId)
        assertEquals(0, pending.meetingAnchor?.offsetUtf16)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `screenshot freezes the selected body before pausing a live meeting`() {
        startMeetingService()
        openNewMeetingPanel()
        val controller = requireNotNull(ownedController)
        val start = onMain { controller.start("fr") }
        reservation.requests.single().grant(FakeRuntimeLease())
        awaitMainCondition { onMain { controller.state.phase == MeetingRecordingPhase.LISTENING } }
        start.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        connectAccessibilityFixture()

        val session = sessions.sessions.single()
        session.emitHypothesis(revision = 1, transcript = "Bonjour")
        awaitMainCondition {
            onMain { controller.state.document.turns.any { it.recognizedText == "Bonjour" } }
        }
        val turn = onMain { controller.state.document.turns.single { it.recognizedText == "Bonjour" } }

        dispatchDocumentAction(
            MeetingPanelDocumentAction.SCREENSHOT,
            com.kafkasl.phonewhisper.meeting.MeetingPanelAnchor(turn.id, "Bonjour".length),
        )
        awaitMainCondition { onMain { controller.state.phase == MeetingRecordingPhase.PAUSING } }
        session.emitHypothesis(revision = 2, transcript = "Bonsoir")
        awaitMainCondition {
            onMain {
                controller.state.document.turns.firstOrNull { it.id == turn.id }?.recognizedText == "Bonsoir"
            }
        }
        assertEquals("the body is explicitly protected before recorder shutdown completes",
            "Bonjour", onMain { controller.state.document.turns.single { it.id == turn.id }.editedText })

        microphones.microphones.single().stopCompletion.complete(Unit)
        awaitMainCondition {
            val pending = imageStore.pending()
            onMain { controller.state.phase == MeetingRecordingPhase.PAUSED } &&
                pending?.kind == NoteImageKind.SCREENSHOT && pending.meetingAnchor?.turnId == turn.id
        }
        val pending = requireNotNull(imageStore.pending())
        ownedPendingCaptureId = pending.id
        assertEquals("Bonjour".length, pending.meetingAnchor?.offsetUtf16)
        val durable = (MeetingDraftStore(draftFile).load() as? MeetingDocumentRead.Ready)?.document
        assertEquals("the serialized anchor still points into the protected body",
            "Bonjour", durable?.turns?.single { it.id == turn.id }?.editedText)
        val saved = requireNotNull(notesStorage.all().firstOrNull {
            it.id == onMain { controller.state.document.sessionId }
        })
        assertEquals(durable, saved.meeting)
        assertEquals(1, nativeCalls.reservations.get())
        assertEquals(1, nativeCalls.sessions.get())
        assertEquals(1, nativeCalls.microphones.get())
    }

    @Test
    fun `moving an image after a selected passage updates only structured bodies and saves`() {
        val image = createFixtureImage(number = 1)
        val document = meetingWithMovableImage(image.marker)
        val note = persistMeetingNote(document, staleText = "CACHE PLAT OBSOLÈTE", images = listOf(image))
        startMeetingService()
        openSavedMeeting(note)
        val controller = requireNotNull(ownedController)
        val sourceTurnId = document.turns[0].id

        onMain {
            val actions = invoke(requireNotNull(service), "meetingPanelActions") as
                com.kafkasl.phonewhisper.meeting.MeetingPanelActions
            actions.imageAction(
                com.kafkasl.phonewhisper.meeting.MeetingPanelAnchor(sourceTurnId, 0),
                image,
            )
        }
        clickLatestMeetingChoice("Déplacer après un passage")
        clickLatestMeetingChoice("Karim")

        awaitMainCondition {
            val saved = notesStorage.all().firstOrNull { it.id == document.sessionId }
            saved?.meeting?.turns?.getOrNull(0)?.editedText == "Bonjour  mardi" &&
                saved.meeting.turns.getOrNull(1)?.editedText == "Phrase cible\n\n${image.marker}"
        }
        val saved = requireNotNull(notesStorage.all().firstOrNull { it.id == document.sessionId })
        val live = onMain { controller.state.document }
        assertEquals("recognized ASR text remains immutable", document.turns.map { it.recognizedText },
            live.turns.map { it.recognizedText })
        assertEquals("the source turn stores only its marker removal", "Bonjour  mardi", live.turns[0].editedText)
        assertEquals("the selected destination receives the marker at its end",
            "Phrase cible\n\n${image.marker}", live.turns[1].editedText)
        assertEquals(live, saved.meeting)
        assertEquals(listOf(image), saved.images)
        assertTrue("moving keeps the image bytes", imageStore.file(image.id).isFile)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `retry persists an image move already applied before the first draft write failed`() {
        val image = createFixtureImage(number = 1)
        val document = meetingWithMovableImage(image.marker)
        val note = persistMeetingNote(document, staleText = "CACHE PLAT OBSOLÈTE", images = listOf(image))
        val scheduler = ManualDraftScheduler()
        startMeetingService(scheduler)
        openSavedMeeting(note)
        val controller = requireNotNull(ownedController)
        val sourceTurnId = document.turns[0].id

        onMain {
            val actions = invoke(requireNotNull(service), "meetingPanelActions") as MeetingPanelActions
            actions.imageAction(com.kafkasl.phonewhisper.meeting.MeetingPanelAnchor(sourceTurnId, 0), image)
        }
        clickLatestMeetingChoice("Déplacer après un passage")
        scheduler.holdNextWrite()
        clickLatestMeetingChoice("Karim")
        awaitMainCondition { scheduler.hasHeldWrite() }
        awaitMainCondition {
            onMain { controller.state.document.turns.getOrNull(1)?.editedText == "Phrase cible\n\n${image.marker}" }
        }

        val parkedDraft = File(context.cacheDir, "${draftDirectory.name}-write-failure")
        check(draftDirectory.renameTo(parkedDraft)) { "Could not isolate the failing writer path" }
        check(draftDirectory.createNewFile()) { "Could not block the failing writer path" }
        try {
            scheduler.runHeldWrite()
        } finally {
            check(draftDirectory.delete()) { "Could not remove the fixture writer-path blocker" }
            check(parkedDraft.renameTo(draftDirectory)) { "Could not restore the isolated writer path" }
        }
        awaitMainCondition { ShadowToast.getTextOfLatestToast()?.toString() == "Échec de sauvegarde" }
        val oldNote = requireNotNull(notesStorage.all().firstOrNull { it.id == document.sessionId })
        assertEquals("the failed barrier leaves the durable note unchanged", document, oldNote.meeting)

        onMain {
            val actions = invoke(requireNotNull(service), "meetingPanelActions") as MeetingPanelActions
            actions.retrySave()
        }

        awaitMainCondition {
            notesStorage.all().firstOrNull { it.id == document.sessionId }?.meeting?.turns?.getOrNull(1)?.editedText ==
                "Phrase cible\n\n${image.marker}"
        }
        assertEquals("retry publishes the in-memory structured move", controller.state.document,
            notesStorage.all().first { it.id == document.sessionId }.meeting)
        assertEquals(listOf(image), notesStorage.all().first { it.id == document.sessionId }.images)
        assertTrue(imageStore.file(image.id).isFile)
        assertNoNativeSessionOrMicrophone()
    }

    @Test
    fun `late image removal callback after mode switch keeps the transferred attachment`() {
        val image = createFixtureImage(number = 1)
        val document = meetingWithMovableImage(image.marker)
        val note = persistMeetingNote(document, staleText = "CACHE PLAT OBSOLÈTE", images = listOf(image))
        val scheduler = ManualDraftScheduler()
        startMeetingService(scheduler)
        openSavedMeeting(note)
        val sourceTurnId = document.turns[0].id

        onMain {
            val actions = invoke(requireNotNull(service), "meetingPanelActions") as MeetingPanelActions
            actions.imageAction(com.kafkasl.phonewhisper.meeting.MeetingPanelAnchor(sourceTurnId, 0), image)
        }
        scheduler.holdNextWrite()
        clickLatestMeetingChoice("Retirer cette image")
        awaitMainCondition { scheduler.hasHeldWrite() }

        assertTrue("the real process coordinator accepts a change to Dictation",
            coordinator.changeMode(TranscriptionMode.DICTATION))
        scheduler.releaseHeldWrites()
        awaitMainCondition {
            coordinator.snapshot().mode == TranscriptionMode.DICTATION &&
                notesStorage.all().firstOrNull { it.id == document.sessionId }?.meeting?.sessionId == document.sessionId
        }

        val transferred = requireNotNull(notesStorage.all().firstOrNull { it.id == document.sessionId })
        assertEquals("the attachment remains in the transferred structured note", listOf(image), transferred.images)
        assertTrue("an obsolete remove callback cannot delete the image bytes", imageStore.file(image.id).isFile)
    }

    @Test
    fun `removing an image saves structured marker changes before deleting its files`() {
        val image = createFixtureImage(number = 1)
        val document = meetingWithMovableImage(image.marker)
        val note = persistMeetingNote(document, staleText = "CACHE PLAT OBSOLÈTE", images = listOf(image))
        assertTrue(imageStore.file(image.id).isFile)
        assertTrue(imageStore.thumbnail(image.id).isFile)
        val scheduler = ManualDraftScheduler()
        startMeetingService(scheduler)
        openSavedMeeting(note)
        val controller = requireNotNull(ownedController)
        val sourceTurnId = document.turns[0].id

        onMain {
            val actions = invoke(requireNotNull(service), "meetingPanelActions") as
                com.kafkasl.phonewhisper.meeting.MeetingPanelActions
            actions.imageAction(
                com.kafkasl.phonewhisper.meeting.MeetingPanelAnchor(sourceTurnId, 0),
                image,
            )
        }
        scheduler.holdNextWrite()
        clickLatestMeetingChoice("Retirer cette image")

        awaitMainCondition { scheduler.hasHeldWrite() }
        assertTrue("image bytes remain available while the structured save barrier is pending",
            imageStore.file(image.id).isFile && imageStore.thumbnail(image.id).isFile)
        val oldNoteDuringBarrier = requireNotNull(notesStorage.all().firstOrNull { it.id == document.sessionId })
        assertEquals("the old note is preserved while the draft write is blocked", listOf(image), oldNoteDuringBarrier.images)
        assertTrue("the durable body still references the image during the barrier",
            oldNoteDuringBarrier.meeting?.turns?.any { (it.editedText ?: it.recognizedText).contains(image.marker) } == true)

        scheduler.runHeldWrite()

        awaitMainCondition {
            notesStorage.all().firstOrNull { it.id == document.sessionId }?.images?.none { it.id == image.id } == true &&
                !imageStore.file(image.id).exists()
        }
        val saved = requireNotNull(notesStorage.all().firstOrNull { it.id == document.sessionId })
        val live = onMain { controller.state.document }
        assertEquals("the ASR source remains available after an image-marker edit",
            document.turns[0].recognizedText, live.turns[0].recognizedText)
        assertEquals("only the edited body loses the marker", "Bonjour  mardi", live.turns[0].editedText)
        assertEquals(live, saved.meeting)
        assertTrue(saved.images.none { it.id == image.id })
        assertFalse("private image bytes are removed only after durable note publication", imageStore.file(image.id).exists())
        assertFalse(imageStore.thumbnail(image.id).exists())
        assertNoNativeSessionOrMicrophone()
    }

    private fun clickLatestMeetingChoice(labelFragment: String) {
        val dialog = requireNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        val list = requireNotNull(dialog.listView)
        val index = (0 until list.count).firstOrNull { position ->
            list.getItemAtPosition(position).toString().contains(labelFragment, ignoreCase = true)
        } ?: error("No meeting-dialog choice contains '$labelFragment'")
        onMain { list.performItemClick(list, index, list.getItemIdAtPosition(index)) }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun connectAccessibilityFixture() {
        check(WhisperAccessibilityService.connected == null) {
            "The accessibility fixture must start from a disconnected state"
        }
        val controller = Robolectric.buildService(WhisperAccessibilityService::class.java).create()
        accessibilityFixtureController = controller
        val fixture = controller.get()
        fixture.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(fixture)
        assertSame("the fixture connects through the real service lifecycle", fixture, WhisperAccessibilityService.connected)
    }

    private fun setConnectedAccessibilityServiceForFixture(value: WhisperAccessibilityService?) {
        WhisperAccessibilityService::class.java.getDeclaredField("connected").apply {
            isAccessible = true
            set(null, value)
        }
    }

    private fun meetingWithMovableImage(marker: String): MeetingDocument {
        val sessionId = UUID.randomUUID().toString()
        return MeetingDocument(
            sessionId = sessionId,
            runId = UUID.randomUUID().toString(),
            participants = listOf(
                MeetingParticipant(id = "speaker-sophie", ordinal = 1, channel = 1, name = "Sophie"),
                MeetingParticipant(id = "speaker-karim", ordinal = 2, channel = 2, name = "Karim"),
            ),
            turns = listOf(
                MeetingTurn(
                    id = "$sessionId:utterance:1:turn:1",
                    utteranceId = 1L,
                    startMs = 0,
                    endMs = 800,
                    recognizedText = "Bonjour mardi",
                    editedText = "Bonjour $marker mardi",
                    automaticParticipantId = "speaker-sophie",
                    attributionStable = true,
                ),
                MeetingTurn(
                    id = "$sessionId:utterance:2:turn:1",
                    utteranceId = 2L,
                    startMs = 900,
                    endMs = 1_400,
                    recognizedText = "Phrase cible",
                    automaticParticipantId = "speaker-karim",
                    attributionStable = true,
                ),
            ),
            finished = true,
        )
    }

    private fun editedMeetingDocument(withImage: Boolean): MeetingDocument {
        val sessionId = UUID.randomUUID().toString()
        val recognizedBody = if (withImage) "Bonjour [[Image 1]] mardi" else "mardi"
        return MeetingDocument(
            sessionId = sessionId,
            runId = UUID.randomUUID().toString(),
            participants = listOf(
                MeetingParticipant(id = "speaker-sophie", ordinal = 1, channel = 1, name = "Sophie"),
                MeetingParticipant(id = "speaker-ignored", ordinal = 2, channel = 2, name = "Secret", ignored = true),
            ),
            turns = listOf(
                MeetingTurn(
                    id = "${sessionId}:utterance:1:turn:1",
                    utteranceId = 1L,
                    startMs = 0,
                    endMs = 800,
                    recognizedText = recognizedBody,
                    automaticParticipantId = "speaker-sophie",
                    attributionStable = true,
                ),
                MeetingTurn(
                    id = "${sessionId}:utterance:2:turn:1",
                    utteranceId = 2L,
                    startMs = 900,
                    endMs = 1_400,
                    recognizedText = "secret à ne pas publier",
                    automaticParticipantId = "speaker-ignored",
                    attributionStable = true,
                ),
            ),
            finished = true,
        )
    }

    private fun editFirstMeetingTurn(text: String): MeetingDocument {
        val controller = requireNotNull(ownedController)
        val turnId = onMain { controller.state.document.turns.first { it.utteranceId > 0L }.id }
        onMain { controller.editTurn(turnId, text) }
        awaitMainCondition {
            onMain {
                controller.state.document.turns.firstOrNull { it.id == turnId }?.editedText == text
            }
        }
        return onMain { controller.state.document }
    }

    private fun persistMeetingNote(
        document: MeetingDocument,
        staleText: String,
        images: List<NoteImage> = emptyList(),
    ): TranscriptNote {
        val note = TranscriptNote(
            id = document.sessionId,
            title = "Réunion média",
            text = staleText,
            updatedAt = System.currentTimeMillis(),
            images = images,
            meeting = document,
        )
        ownedNotes.putIfAbsent(note.id, notesPreferences.getString(note.id, null))
        notesStorage.put(note)
        return note
    }

    private fun createFixtureImage(number: Int): NoteImage {
        val id = UUID.randomUUID().toString()
        val image = NoteImage(id, number, NoteImageKind.CAMERA, System.currentTimeMillis(), 24, 16)
        ownedImageIds += id
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(35, 145, 80))
        }
        try {
            check(imageStore.file(id).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }) { "Could not create the fixture image bytes" }
            check(imageStore.thumbnail(id).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)
            }) { "Could not create the fixture thumbnail bytes" }
        } finally {
            bitmap.recycle()
        }
        return image
    }

    private fun persistAcceptedMeetingBatch(
        anchor: MeetingImageAnchor,
        images: List<NoteImage>,
    ): PendingNoteCapture {
        val id = UUID.randomUUID().toString()
        val raw = JSONObject()
            .put("id", id)
            .put("noteId", anchor.sessionId)
            .put("number", 1)
            .put("kind", NoteImageKind.SCREENSHOT.name)
            .put("time", System.currentTimeMillis())
            .put("resume", false)
            .put("clipboardOnly", true)
            .put("batch", true)
            .put("accepted", true)
            .put("maxImages", NoteImage.MAX_IMAGES)
            .put("meetingCapture", true)
            .put("meetingAnchor", JSONObject()
                .put("sessionId", anchor.sessionId)
                .put("turnId", anchor.turnId)
                .put("offsetUtf16", anchor.offsetUtf16))
            .put("meetingImageNumbersResolved", false)
            .put("images", NoteImageJson.writeList(images))
        check(context.getSharedPreferences("note_capture", Context.MODE_PRIVATE).edit()
            .putString("pending", raw.toString()).commit()) { "Could not persist the accepted meeting batch fixture" }
        ownedPendingCaptureId = id
        return requireNotNull(imageStore.pending())
    }

    private fun persistInvalidAcceptedMeetingBatch(images: List<NoteImage>): PendingNoteCapture {
        val id = UUID.randomUUID().toString()
        val noteSessionId = UUID.randomUUID().toString()
        val mismatchedSessionId = UUID.randomUUID().toString()
        val raw = JSONObject()
            .put("id", id)
            .put("noteId", noteSessionId)
            .put("number", 1)
            .put("kind", NoteImageKind.SCREENSHOT.name)
            .put("time", System.currentTimeMillis())
            .put("resume", false)
            .put("clipboardOnly", true)
            .put("batch", true)
            .put("accepted", true)
            .put("maxImages", NoteImage.MAX_IMAGES)
            .put("meetingCapture", true)
            .put("meetingAnchor", JSONObject()
                .put("sessionId", mismatchedSessionId)
                .put("turnId", "missing-turn")
                .put("offsetUtf16", 0))
            .put("meetingImageNumbersResolved", false)
            .put("images", NoteImageJson.writeList(images))
        check(context.getSharedPreferences("note_capture", Context.MODE_PRIVATE).edit()
            .putString("pending", raw.toString()).commit()) { "Could not persist the invalid accepted meeting batch fixture" }
        ownedPendingCaptureId = id
        return requireNotNull(imageStore.pending()).also {
            check(it.meetingAnchorInvalid && it.meetingAnchor == null)
        }
    }

    private fun startMeetingService(
        scheduler: ManualDraftScheduler? = null,
        selectMeeting: Boolean = true,
    ) {
        if (selectMeeting) {
            assertTrue("the fixture selects Meeting before service creation", coordinator.changeMode(TranscriptionMode.MEETING))
        }
        manualDraftScheduler = scheduler
        val ownership = if (scheduler == null) {
            MeetingDraftOwnership(executor = MeetingDraftOwnershipExecutor { task -> task() })
        } else {
            MeetingDraftOwnership(
                executor = MeetingDraftOwnershipExecutor { task -> task() },
                writerFactory = MeetingDraftOwnershipWriterFactory { path, relay ->
                    MeetingDraftWriter(
                        MeetingDraftPersistence(MeetingDraftStore(path)::save),
                        scheduler,
                        onErrorChanged = relay::publish,
                    )
                },
            )
        }
        fixtureDraftOwnership = ownership
        val overrides = OverlayService.MeetingTestOverrides(
            draftOwnership = ownership,
            draftFile = draftFile,
            modelStore = MeetingModelStore.shared(context),
            modelAvailability = MeetingModelAvailabilityPort { MeetingModelAvailability.READY },
            reservation = reservation,
            sessionFactory = sessions,
            microphoneFactory = microphones,
        )
        assertTrue("the one-shot fixture factory is available before onCreate", OverlayService.setMeetingTestOverridesFactoryForTest { overrides })
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

    private fun openNewMeetingPanel() {
        onMain { invokeNoArgs(requireNotNull(service), "openMeetingPanel") }
        awaitMainCondition {
            val current = requireNotNull(service)
            field<MeetingRecordingController?>(current, "meetingRecordingController")?.also { ownedController = it } != null &&
                field<MeetingPanelController?>(current, "meetingPanelController") != null
        }
    }

    private fun dispatchDocumentAction(
        action: MeetingPanelDocumentAction,
        anchor: com.kafkasl.phonewhisper.meeting.MeetingPanelAnchor? = null,
    ) {
        onMain {
            val actions = invoke(requireNotNull(service), "meetingPanelActions") as MeetingPanelActions
            actions.documentAction(action, anchor)
            Unit
        }
    }

    private fun dispatchAllDocumentActions() {
        MeetingPanelDocumentAction.entries.forEach(::dispatchDocumentAction)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertNoNativeSessionOrMicrophone() {
        assertEquals(0, nativeCalls.reservations.get())
        assertEquals(0, nativeCalls.sessions.get())
        assertEquals(0, nativeCalls.microphones.get())
        assertTrue(sessions.sessions.isEmpty())
        assertTrue(microphones.microphones.isEmpty())
        assertNull(coordinator.snapshot().activeRunMode)
        assertFalse("document actions cannot arm the service microphone", OverlayService.micArmed)
        assertNull(field<Any?>(requireNotNull(service), "activeRun"))
        assertNull(field<Any?>(requireNotNull(service), "audioRecord"))
    }

    private fun awaitMainCondition(timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            looper.idle()
            if (condition()) return
            Thread.sleep(5L)
        }
        looper.idle()
        assertTrue("condition should complete within ${timeoutMs} ms", condition())
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

    private class NativeCallCounts {
        val reservations = AtomicInteger()
        val sessions = AtomicInteger()
        val microphones = AtomicInteger()
    }

    private class ControlledReservationPort(private val calls: NativeCallCounts) : MeetingNativeReservationPort {
        val requests = CopyOnWriteArrayList<ControlledReservationRequest>()

        override fun request(runId: String): MeetingNativeReservationRequest {
            calls.reservations.incrementAndGet()
            return ControlledReservationRequest().also(requests::add)
        }
    }

    private class ControlledReservationRequest : MeetingNativeReservationRequest {
        override val lease = CompletableFuture<MeetingNativeRuntimeLease>()

        override fun cancel(): Unit {
            lease.completeExceptionally(CancellationException("fixture cancellation"))
        }

        fun grant(lease: MeetingNativeRuntimeLease): Unit {
            check(this.lease.complete(lease)) { "fixture reservation was already resolved" }
        }
    }

    private class FakeRuntimeLease : MeetingNativeRuntimeLease {
        val releases = AtomicInteger()
        val poisons = AtomicInteger()
        override fun isCurrentAndUsable(): Boolean = true
        override fun release(): Unit { releases.incrementAndGet() }
        override fun poison(): Unit { poisons.incrementAndGet() }
    }

    private class ControlledSessionFactory(private val calls: NativeCallCounts) : MeetingSessionFactoryPort {
        val sessions = CopyOnWriteArrayList<ControlledSession>()

        override fun start(
            runId: String,
            language: String,
            onReady: () -> Unit,
            onUpdate: (com.kafkasl.phonewhisper.meeting.MeetingHypothesis) -> Unit,
            onFailure: (String) -> Unit,
        ): MeetingSession {
            calls.sessions.incrementAndGet()
            return ControlledSession(runId, onUpdate).also { session ->
                sessions += session
                onReady()
            }
        }
    }

    private class ControlledSession(
        val runId: String,
        private val onUpdate: (MeetingHypothesis) -> Unit,
    ) : MeetingSession {
        override val closed = CompletableFuture<Unit>()
        override val queuedAudioMs: Long = 0L
        override fun acceptPcm16(buffer: ByteArray, length: Int): Boolean = true
        override fun checkpoint(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        override fun finish(): Unit { closed.complete(Unit) }
        override fun cancel(): Unit { closed.complete(Unit) }
        override fun close(): Unit = cancel()

        fun emitHypothesis(revision: Long, transcript: String) {
            onUpdate(
                MeetingHypothesis(
                    runId = runId,
                    utteranceId = 1L,
                    revision = revision,
                    words = listOf(MeetingWord(transcript, 0L, 500L, channel = 1)),
                    transcript = transcript,
                    isFinal = false,
                    stableSpeakerThroughMs = 0L,
                    audioProcessedMs = 500L,
                ),
            )
        }
    }

    private class ControlledMicrophoneFactory(private val calls: NativeCallCounts) : MeetingMicrophoneFactoryPort {
        val microphones = CopyOnWriteArrayList<ControlledMicrophone>()

        override fun create(): MeetingMicrophonePort {
            calls.microphones.incrementAndGet()
            return ControlledMicrophone().also(microphones::add)
        }
    }

    private class ControlledMicrophone : MeetingMicrophonePort {
        val stopCompletion = CompletableFuture<Unit>()
        val starts = AtomicInteger()
        val stops = AtomicInteger()

        override fun start(onPcm16: (ByteArray, Int) -> Boolean, onFailure: (String) -> Unit): Boolean {
            starts.incrementAndGet()
            return true
        }

        override fun stopAndJoin(): CompletableFuture<Unit> {
            stops.incrementAndGet()
            return stopCompletion
        }
    }

    private class ManualDraftScheduler : MeetingDraftScheduler {
        private val heldWrites = ConcurrentLinkedQueue<() -> Unit>()
        @Volatile private var holdNext = false

        override fun schedule(delayMs: Long, task: () -> Unit): MeetingDraftScheduledTask =
            MeetingDraftScheduledTask { }

        override fun execute(task: () -> Unit) {
            if (holdNext) {
                holdNext = false
                heldWrites += task
            } else {
                task()
            }
        }

        override fun shutdown() = Unit

        fun holdNextWrite() { holdNext = true }

        fun hasHeldWrite(): Boolean = heldWrites.isNotEmpty()

        fun releaseHeldWrites() {
            holdNext = false
            while (true) {
                val next = heldWrites.poll() ?: return
                next()
            }
        }

        fun runHeldWrite() {
            requireNotNull(heldWrites.poll()) { "Expected a queued draft write" }.invoke()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val TIMEOUT_MS = TIMEOUT_SECONDS * 1_000L
    }
}
