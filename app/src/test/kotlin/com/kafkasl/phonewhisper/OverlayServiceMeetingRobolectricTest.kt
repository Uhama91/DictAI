package com.kafkasl.phonewhisper

import android.content.Context
import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.io.IOException
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.Shadows
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipExecutor
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailability
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailabilityPort
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationPort
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingSession
import com.kafkasl.phonewhisper.meeting.MeetingSessionFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingHypothesis
import com.kafkasl.phonewhisper.meeting.MeetingMicrophoneFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingMicrophonePort
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingParticipant
import com.kafkasl.phonewhisper.meeting.MeetingTurn
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPhase
import com.kafkasl.phonewhisper.meeting.MeetingRecordingState
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import com.kafkasl.phonewhisper.meeting.MeetingDraftStore
import com.kafkasl.phonewhisper.meeting.MeetingPanelActions
import com.kafkasl.phonewhisper.meeting.MeetingPanelController
import com.kafkasl.phonewhisper.meeting.MeetingPanelSessionCommand
import com.kafkasl.phonewhisper.meeting.MeetingPanelStatus

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceMeetingRobolectricTest {
    private class OneFailureMeetingStorage : TranscriptNoteStorage {
        val values = linkedMapOf<String, TranscriptNote>()
        val failNextMeetingWrite = AtomicBoolean(true)
        var beforeMeetingWrite: ((TranscriptNote) -> Unit)? = null

        override fun all(): List<TranscriptNote> = values.values.toList()
        override fun put(note: TranscriptNote) { values[note.id] = note }
        override fun putMeeting(note: TranscriptNote) {
            if (failNextMeetingWrite.compareAndSet(true, false)) throw IOException("fixture write failure")
            beforeMeetingWrite?.invoke(note)
            values[note.id] = note
        }
        override fun remove(id: String) { values.remove(id) }
    }

    private data class PanelFixture(
        val reservationCalls: AtomicInteger,
        val sessionCalls: AtomicInteger,
        val microphoneCalls: AtomicInteger,
    )

    private lateinit var context: Context
    private lateinit var serviceController: ServiceController<OverlayService>
    private lateinit var service: OverlayService
    private lateinit var coordinator: TranscriptionModeCoordinator
    private var previousMode = TranscriptionMode.DICTATION
    private var previousFormat: String? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = PersistencePrefs(context)
        previousMode = prefs.transcriptionMode
        previousFormat = context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
            .getString("selected", null)
        TranscriptionModeCoordinator.clearProcessForTest()
        prefs.transcriptionMode = TranscriptionMode.DICTATION
        coordinator = TranscriptionModeCoordinator.process(context)
        PostProcessingFormats(context).select(
            PostProcessingFormats(context).all().first { it.id == "corrected" },
        )

        ShadowSettings.setCanDrawOverlays(true)
        serviceController = Robolectric.buildService(OverlayService::class.java)
        service = serviceController.create().get()
        invoke(service, "showButton")
    }

    @After
    fun tearDown() {
        runCatching { serviceController.destroy() }
        val prefs = PersistencePrefs(context)
        prefs.transcriptionMode = previousMode
        val formatPrefs = context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
        if (previousFormat == null) formatPrefs.edit().remove("selected").commit()
        else formatPrefs.edit().putString("selected", previousFormat).commit()
        TranscriptionModeCoordinator.clearProcessForTest()
        ShadowSettings.reset()
    }

    @Test
    fun `upward menu requires explicit meeting choice and keeps the dictation format`() {
        val formats = PostProcessingFormats(context)
        assertEquals("corrected", formats.selected().id)

        val pill = field<FrameLayout>(service, "pill")
        send(pill, MotionEvent.ACTION_DOWN, 20f, 20f)
        send(pill, MotionEvent.ACTION_MOVE, 20f, -60f)
        send(pill, MotionEvent.ACTION_UP, 20f, -60f)

        val menu = field<View?>(service, "floatingMenu")
        assertNotNull("the revealed menu exposes a distinct Réunion action", findText(menu, "Réunion"))
        assertEquals("the reveal gesture alone keeps Dictée selected", TranscriptionMode.DICTATION, coordinator.snapshot().mode)

        requireNotNull(findText(menu, "Réunion")).performClick()

        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        assertEquals("Meeting selection must not change the remembered Dictation format", "corrected", formats.selected().id)
    }

    @Test
    fun `service resident engine refuses dictation model loads in meeting mode`() {
        assertEquals(true, coordinator.changeMode(TranscriptionMode.MEETING))
        val resident = field<Lazy<ResidentEngine<Closeable>>>(service, "residentAsrEngine\$delegate").value
        var openCalls = 0

        val failure = runCatching {
            resident.replace("fixture-dictation") {
                openCalls += 1
                Closeable { }
            }
        }.exceptionOrNull()

        assertNotNull("the service resident must enforce the process mode lease", failure)
        assertEquals("a Meeting-mode resident must not open Dictation", 0, openCalls)
        assertEquals(0, coordinator.snapshot().residentDictationEngines)
    }

    @Test
    fun `meeting menu hides dictation formats and exposes an explicit panel action`() {
        assertEquals(true, coordinator.changeMode(TranscriptionMode.MEETING))
        service.javaClass.getDeclaredMethod(
            "showFormatPicker", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(service, false, true)

        val menu = field<View?>(service, "floatingMenu")
        assertNotNull("Meeting mode must keep an explicit panel entry", findText(menu, "Ouvrir la réunion"))
        assertNull("Dictation formats do not belong in Meeting mode", findText(menu, "Format corrigé"))
    }

    @Test
    fun `opening the explicit meeting entry installs the meeting editor without starting audio`() {
        val fixture = openMeetingPanel()

        val panelBody = field<View?>(service, "livePanelBody")
        assertNotNull(
            "opening Meeting must install the participant-aware editor in the existing panel",
            findText(panelBody, "Intervenants (0)"),
        )
        assertNull("opening the panel alone must not create a microphone", field(service, "audioRecord"))
        assertNotNull("the panel waits for and owns its private draft", field(service, "meetingDraftClaim"))
        assertNotNull("the real recording controller backs the rendered panel", field<MeetingRecordingController?>(service, "meetingRecordingController"))
        assertEquals(0, fixture.reservationCalls.get())
        assertEquals(0, fixture.sessionCalls.get())
        assertEquals(0, fixture.microphoneCalls.get())
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
    }

    @Test
    fun `meeting panel keeps full transcript surface and hides dictation rows after resize`() {
        openMeetingPanel()

        val panelController = field<com.kafkasl.phonewhisper.meeting.MeetingPanelController?>(service, "meetingPanelController")
        val params = requireNotNull(panelController).view.layoutParams as FrameLayout.LayoutParams
        assertEquals((48 * context.resources.displayMetrics.density).toInt(), params.topMargin)
        assertEquals("meeting rows use the complete panel width", 0, params.leftMargin)
        assertEquals(0, params.rightMargin)
        assertEquals("meeting rows retain the full panel height after the host toolbar", 0, params.bottomMargin)

        val media = field<View?>(service, "mediaToolbar")
        val actions = field<View?>(service, "editorActionsRow")
        val vocabulary = field<View?>(service, "vocabularyBanner")
        assertEquals(View.GONE, media?.visibility)
        assertEquals(View.GONE, actions?.visibility)
        assertEquals(View.GONE, vocabulary?.visibility)

        service.javaClass.getDeclaredMethod(
            "layoutTranscriptRows", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(service, 500, 1f)

        val currentAnchor = requireNotNull(field<Any?>(service, "currentAnchor"))
        service.javaClass.getDeclaredMethod("positionLivePanel", currentAnchor.javaClass)
            .apply { isAccessible = true }.invoke(service, currentAnchor)

        assertEquals("resize must not restore Dictation media actions over the meeting panel", View.GONE, media?.visibility)
        assertEquals(View.GONE, actions?.visibility)
        assertEquals(View.GONE, vocabulary?.visibility)
        val title = requireNotNull(field<android.widget.TextView?>(service, "panelTitle"))
        assertEquals("Le panneau de réunion conserve son titre après repositionnement", "Réunion", title.text.toString())
        assertEquals("l’accessibilité conserve la description du mode après repositionnement", "Mode Réunion", title.contentDescription.toString())
        assertEquals(36, requireNotNull(panelController).view.recyclerView.paddingLeft)
        assertEquals(36, panelController.view.recyclerView.paddingRight)
    }

    @Test
    fun `meeting errors remain visible while listening`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        val live = controller.state.copy(
            phase = MeetingRecordingPhase.LISTENING,
            captureActive = true,
            saveError = "Sauvegarde impossible",
        )
        val modelState = field<MeetingModelStore>(service, "meetingModelStore").currentState
        service.javaClass.getDeclaredMethod(
            "renderMeetingState", MeetingRecordingState::class.java, MeetingModelStoreState::class.java,
        ).apply { isAccessible = true }.invoke(service, live, modelState)

        val panel = requireNotNull(field<MeetingPanelController?>(service, "meetingPanelController"))
        val status = field<MeetingPanelStatus>(panel, "status")
        assertEquals("model availability must not replace the active recording phase", MeetingPanelStatus.Phase.LISTENING, status.phase)
        assertEquals("the write error remains attached to the active phase", "Sauvegarde impossible", status.saveError)
    }

    @Test
    fun `meeting notification reports phase without exposing transcript text`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setMeetingState(controller, MeetingRecordingPhase.LISTENING)

        val notification = invoke(service, "buildNotification") as Notification
        assertEquals("microphone setup remains the higher priority while unarmed",
            "Ouvre l'app pour activer le micro",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        setField(service, "micArmed", true)
        setServiceState("IDLE")
        val armedNotification = invoke(service, "buildNotification") as Notification
        assertEquals("Réunion en cours", armedNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        val changedTranscript = controller.state.copy(
            document = controller.state.document.copy(
                turns = listOf(MeetingTurn("private", 1L, 0L, 20L, "phrase privée Sophie", null)),
            ),
        )
        setField(service, "meetingControllerState", changedTranscript)
        val afterHypothesis = invoke(service, "buildNotification") as Notification
        assertEquals("a hypothesis never appears in the notification", "Réunion en cours",
            afterHypothesis.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `meeting swipe down while paused opens the finish confirmation`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setMeetingState(controller, MeetingRecordingPhase.PAUSED)

        val pill = field<FrameLayout>(service, "pill")
        send(pill, MotionEvent.ACTION_DOWN, 20f, 20f)
        send(pill, MotionEvent.ACTION_MOVE, 20f, 100f)
        send(pill, MotionEvent.ACTION_UP, 20f, 100f)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("a downward gesture in a paused meeting asks before finishing", dialog)
        assertEquals("Enregistrer la transcription ?", Shadows.shadowOf(requireNotNull(dialog)).getTitle().toString())
        assertEquals(MeetingRecordingPhase.PAUSED, controller.state.phase)
        assertNull("gesture routing must not start a Dictation recorder", field(service, "audioRecord"))
    }

    @Test
    fun `meeting downward preview follows listening and paused phases`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        val hint = field<TextView>(service, "gestureHint")
        val pill = field<FrameLayout>(service, "pill")

        setMeetingState(controller, MeetingRecordingPhase.LISTENING)
        send(pill, MotionEvent.ACTION_DOWN, 20f, 20f)
        send(pill, MotionEvent.ACTION_MOVE, 20f, 120f)
        assertEquals("Mettre en pause pour enregistrer", hint.text.toString())
        send(pill, MotionEvent.ACTION_CANCEL, 20f, 120f)

        setMeetingState(controller, MeetingRecordingPhase.PAUSED)
        send(pill, MotionEvent.ACTION_DOWN, 20f, 20f)
        send(pill, MotionEvent.ACTION_MOVE, 20f, 120f)
        assertEquals("↓ Enregistrer", hint.text.toString())
        send(pill, MotionEvent.ACTION_CANCEL, 20f, 120f)
    }

    @Test
    fun `failed note publication remains visible and retry publishes without restarting audio`() {
        val draftFile = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(MeetingTurn("speech", 1L, 0L, 500L, "Texte à sauver", null)),
            finished = true,
        )
        MeetingDraftStore(draftFile).save(document)
        val storage = OneFailureMeetingStorage()
        val notes = installNoteStorage(storage)
        val fixture = openMeetingPanel(draftFile = draftFile)
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setControllerPhase(controller, MeetingRecordingPhase.FINISHED)
        service.javaClass.getDeclaredMethod("saveOpenMeetingNote", MeetingRecordingController::class.java)
            .apply { isAccessible = true }.invoke(service, controller)

        val panel = requireNotNull(field<MeetingPanelController?>(service, "meetingPanelController"))
        awaitMainCondition { field<MeetingPanelStatus>(panel, "status").saveError != null }
        assertEquals(MeetingRecordingPhase.FINISHED, controller.state.phase)
        assertNull(notes.get(document.sessionId))

        val actions = service.javaClass.getDeclaredMethod("meetingPanelActions")
            .apply { isAccessible = true }.invoke(service) as MeetingPanelActions
        actions.retrySave()
        awaitMainCondition {
            notes.get(document.sessionId) != null && field<MeetingPanelStatus>(panel, "status").saveError == null
        }

        assertEquals("retry publishes the meeting document", "Texte à sauver",
            notes.get(document.sessionId)?.meeting?.turns?.single()?.recognizedText)
        assertEquals("retry does not restart the audio session", 0, fixture.sessionCalls.get())
        assertEquals("retry does not create a microphone", 0, fixture.microphoneCalls.get())
    }

    @Test
    fun `restoring the dictation panel abandons an in flight meeting note publication`() {
        val draftFile = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(MeetingTurn("speech", 1L, 0L, 500L, "Texte à sauver", null)),
        )
        MeetingDraftStore(draftFile).save(document)
        installNoteStorage(OneFailureMeetingStorage().apply { failNextMeetingWrite.set(false) })
        openMeetingPanel(draftFile = draftFile)
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setMeetingState(controller, MeetingRecordingPhase.DOCUMENT)

        service.javaClass.getDeclaredMethod("saveOpenMeetingNote", MeetingRecordingController::class.java)
            .apply { isAccessible = true }.invoke(service, controller)
        assertNotNull("the publication is pending before leaving the Meeting surface",
            field<Any?>(service, "meetingNotePublicationOperation"))

        invoke(service, "restoreDictationPanelAfterMeeting")

        assertNull("leaving the Meeting surface invalidates its operation token",
            field<Any?>(service, "meetingNotePublicationOperation"))
        controller.flushDraft().get(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull("the stale completion cannot resurrect a publication operation",
            field<Any?>(service, "meetingNotePublicationOperation"))
    }

    @Test
    fun `closing phase abandons a pending documentary note publication`() {
        val draftFile = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(MeetingTurn("speech", 1L, 0L, 500L, "Texte à sauver", null)),
        )
        MeetingDraftStore(draftFile).save(document)
        installNoteStorage(OneFailureMeetingStorage().apply { failNextMeetingWrite.set(false) })
        openMeetingPanel(draftFile = draftFile)
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setMeetingState(controller, MeetingRecordingPhase.DOCUMENT)
        service.javaClass.getDeclaredMethod("saveOpenMeetingNote", MeetingRecordingController::class.java)
            .apply { isAccessible = true }.invoke(service, controller)
        assertNotNull(field<Any?>(service, "meetingNotePublicationOperation"))

        setMeetingState(controller, MeetingRecordingPhase.CLOSING)
        controller.flushDraft().get(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull("a publication rejected by a closing phase releases its token",
            field<Any?>(service, "meetingNotePublicationOperation"))
    }

    @Test
    fun `restored documentary note reports host publication failure in notification`() {
        val draftFile = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(MeetingTurn("speech", 1L, 0L, 500L, "Texte à sauver", null)),
        )
        MeetingDraftStore(draftFile).save(document)
        installNoteStorage(OneFailureMeetingStorage())
        openMeetingPanel(draftFile = draftFile)
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setMeetingState(controller, MeetingRecordingPhase.DOCUMENT)
        service.javaClass.getDeclaredMethod("saveOpenMeetingNote", MeetingRecordingController::class.java)
            .apply { isAccessible = true }.invoke(service, controller)

        val panel = requireNotNull(field<MeetingPanelController?>(service, "meetingPanelController"))
        awaitMainCondition { field<MeetingPanelStatus>(panel, "status").saveError != null }
        val notification = invoke(service, "buildNotification") as Notification

        assertEquals("a restored document with a host-side save failure remains actionable",
            "Transcription à enregistrer — ouvrez la réunion",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `save open note reflushes edits that race its initial draft barrier`() {
        val draftFile = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val document = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(MeetingTurn("speech", 1L, 0L, 500L, "Texte reconnu", null)),
            finished = true,
        )
        MeetingDraftStore(draftFile).save(document)
        val storage = OneFailureMeetingStorage().apply { failNextMeetingWrite.set(false) }
        var publicationMatchedDurableDraft = true
        storage.beforeMeetingWrite = { note ->
            val durable = (MeetingDraftStore(draftFile).load() as? com.kafkasl.phonewhisper.meeting.MeetingDocumentRead.Ready)
                ?.document
            publicationMatchedDurableDraft = publicationMatchedDurableDraft && durable == note.meeting
        }
        val notes = installNoteStorage(storage)
        openMeetingPanel(draftFile = draftFile)
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        controller.editTurn("speech", "Avant la sauvegarde")
        setControllerPhase(controller, MeetingRecordingPhase.FINISHED)

        service.javaClass.getDeclaredMethod("saveOpenMeetingNote", MeetingRecordingController::class.java)
            .apply { isAccessible = true }.invoke(service, controller)
        controller.flushDraft().get(5, TimeUnit.SECONDS)
        assertEquals("the first save barrier reaches disk before a later edit is introduced",
            "Avant la sauvegarde",
            (MeetingDraftStore(draftFile).load() as com.kafkasl.phonewhisper.meeting.MeetingDocumentRead.Ready)
                .document.turns.single().editedText)
        controller.editTurn("speech", "Retouche pendant la sauvegarde")
        awaitMainCondition {
            val saved = notes.get(document.sessionId)?.meeting?.turns?.singleOrNull()?.editedText
            saved == "Retouche pendant la sauvegarde"
        }

        val persisted = (MeetingDraftStore(draftFile).load() as com.kafkasl.phonewhisper.meeting.MeetingDocumentRead.Ready).document
        assertEquals("the published note includes only a version whose newer edit crossed a draft barrier",
            "Retouche pendant la sauvegarde", notes.get(document.sessionId)?.meeting?.turns?.single()?.editedText)
        assertEquals("the durable draft and the note describe the same immutable snapshot",
            notes.get(document.sessionId)?.meeting, persisted)
        assertTrue("the note is never published ahead of the version confirmed by the draft barrier",
            publicationMatchedDurableDraft)
    }

    @Test
    fun `stale start command cannot replace an active meeting run`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setControllerPhase(controller, MeetingRecordingPhase.LISTENING)

        service.javaClass.getDeclaredMethod(
            "handleMeetingPanelCommand", MeetingPanelSessionCommand::class.java,
        ).apply { isAccessible = true }.invoke(service, MeetingPanelSessionCommand.START)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue("START from a stale menu must not tear down the current run", field<MeetingRecordingController?>(service, "meetingRecordingController") === controller)
        assertNull("a stale START must not publish or replace the active meeting note", field<Lazy<TranscriptNotes>>(service, "notes\$delegate").value.get(controller.state.document.sessionId))
    }

    @Test
    fun `starting a replacement meeting does not publish an empty document`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setControllerPhase(controller, MeetingRecordingPhase.FINISHED)
        val noteStore = field<Lazy<TranscriptNotes>>(service, "notes\$delegate").value
        val sessionId = controller.state.document.sessionId

        invokeStartNewMeetingAfterSaving(service, controller)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull("a blank draft without attachments must not create a notes entry", noteStore.get(sessionId))
    }

    @Test
    fun `replacement publishes text entered in the documentary turn zero`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        val documentTurn = controller.ensureDocumentTurn()
        controller.editTurn(documentTurn.id, "Note saisie")
        setControllerPhase(controller, MeetingRecordingPhase.FINISHED)
        val document = controller.state.document
        val noteStore = field<Lazy<TranscriptNotes>>(service, "notes\$delegate").value

        invokeStartNewMeetingAfterSaving(service, controller)
        awaitMainCondition { noteStore.get(document.sessionId)?.text == "Note saisie" }

        assertEquals("turn zero is document content and must be saved", "Note saisie", noteStore.get(document.sessionId)?.text)
    }

    @Test
    fun `replacement updates a previously saved note after all speech is deleted`() {
        val file = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val original = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(MeetingTurn("speech", 1L, 0L, 500L, "Ancien texte", null)),
        )
        MeetingDraftStore(file).save(original)
        openMeetingPanel(draftFile = file)
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        val noteStore = field<Lazy<TranscriptNotes>>(service, "notes\$delegate").value
        noteStore.saveMeeting(original.sessionId, original)
        controller.editTurn("speech", "")
        assertEquals("the deletion remains distinct from an untouched hypothesis", "Ancien texte",
            controller.state.document.turns.single().recognizedText)
        assertEquals("", controller.state.document.turns.single().editedText)
        setControllerPhase(controller, MeetingRecordingPhase.FINISHED)
        val emptiedDocument = controller.state.document

        invokeStartNewMeetingAfterSaving(service, controller)
        awaitMainCondition { field<MeetingRecordingController?>(service, "meetingRecordingController") !== controller }

        val saved = noteStore.get(emptiedDocument.sessionId)
        assertEquals("an existing note is updated instead of restoring its old words", "", saved?.text)
        assertEquals("the durable structure keeps the empty edit", "", saved?.meeting?.turns?.single()?.editedText)
        assertEquals("the recognized hypothesis is preserved in the durable structure", "Ancien texte",
            saved?.meeting?.turns?.single()?.recognizedText)
    }

    @Test
    fun `duplicate new meeting requests perform one replacement`() {
        openMeetingPanel()
        val previous = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setControllerPhase(previous, MeetingRecordingPhase.FINISHED)
        val generationBefore = field<Long>(service, "meetingOpenGeneration")
        invokeStartNewMeetingAfterSaving(service, previous)
        invokeStartNewMeetingAfterSaving(service, previous)
        awaitMainCondition { field<MeetingRecordingController?>(service, "meetingRecordingController") !== previous }

        assertEquals("duplicate commands share one claim/replacement operation", generationBefore + 1, field<Long>(service, "meetingOpenGeneration"))
    }

    @Test
    fun `passage review includes unstable manual speech and excludes the document anchor`() {
        val file = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val original = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            participants = listOf(MeetingParticipant("speaker-1", ordinal = 1, channel = 1, ignored = true)),
            turns = listOf(
                MeetingTurn("document-anchor", 0L, 0L, 0L, "Document text", null, attributionStable = true),
                MeetingTurn(
                    id = "unstable-manual",
                    utteranceId = 1L,
                    startMs = 10L,
                    endMs = 900L,
                    recognizedText = "Texte reconnu brut",
                    automaticParticipantId = null,
                    manualParticipantId = "speaker-1",
                    hasManualAttribution = true,
                    editedText = "Texte conservé",
                    attributionStable = false,
                ),
            ),
        )
        MeetingDraftStore(file).save(original)
        openMeetingPanel(draftFile = file)

        invoke(service, "reviewMeetingPassages")

        val dialog = requireNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        val message = Shadows.shadowOf(dialog).getMessage().toString()
        assertTrue("review keeps the original recognized words", message.contains("Texte reconnu brut"))
        assertTrue("review keeps the version preserved by the editor", message.contains("Texte conservé"))
        assertTrue(message.contains("Texte reconnu"))
        assertFalse("the synthetic documentary turn is not a passage", message.contains("Document text"))
    }

    @Test
    fun `passage review preserves deliberate deletion and reports an empty recognized hypothesis`() {
        val file = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val original = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(
                MeetingTurn("deleted", 1L, 0L, 100L, "Reconnu puis supprimé", null, editedText = "", attributionStable = false),
                MeetingTurn("corrected", 2L, 100L, 200L, "", null, editedText = "Correction conservée", attributionStable = false),
            ),
        )
        MeetingDraftStore(file).save(original)
        openMeetingPanel(draftFile = file)

        invoke(service, "reviewMeetingPassages")

        val dialog = requireNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        val message = Shadows.shadowOf(dialog).getMessage().toString()
        assertTrue("blank recognized text remains explicitly identified", message.contains("Texte reconnu : (Aucun texte reconnu)"))
        assertTrue("the deleted version is not replaced by its former recognized text", message.contains("Texte conservé : (Texte supprimé)"))
        assertTrue("manually corrected speech remains visible", message.contains("Texte conservé : Correction conservée"))
    }

    @Test
    fun `meeting state callback renders synchronously when already on main`() {
        val file = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val original = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            participants = listOf(MeetingParticipant("speaker-1", ordinal = 1, channel = 1)),
        )
        MeetingDraftStore(file).save(original)
        openMeetingPanel(draftFile = file)
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        var stateObservedInSameMainTurn: MeetingRecordingState? = null
        Handler(Looper.getMainLooper()).post {
            controller.renameParticipant("speaker-1", "Sophie")
            stateObservedInSameMainTurn = field(service, "meetingControllerState")
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Sophie", stateObservedInSameMainTurn?.document?.participants?.single()?.name)
    }

    @Test
    fun `finish confirmation uses overlay window and ignores a stale phase`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setMeetingState(controller, MeetingRecordingPhase.PAUSED)
        service.javaClass.getDeclaredMethod("confirmMeetingFinish", MeetingRecordingController::class.java)
            .apply { isAccessible = true }.invoke(service, controller)
        awaitMainCondition {
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.let {
                Shadows.shadowOf(it).getTitle().toString() == "Enregistrer la transcription ?"
            } == true
        }

        val dialog = requireNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("Enregistrer la transcription ?", Shadows.shadowOf(dialog).getTitle().toString())
        assertEquals("Enregistrer et terminer", dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Continuer la réunion", dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, dialog.window?.attributes?.type)

        setControllerPhase(controller, MeetingRecordingPhase.PAUSING)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("a response is ignored after the meeting phase changes", MeetingRecordingPhase.PAUSING, controller.state.phase)
    }

    @Test
    fun `finish from the actual meeting actions dialog opens confirmation after dismiss`() {
        openMeetingPanel()
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setMeetingState(controller, MeetingRecordingPhase.PAUSED)
        service.javaClass.getDeclaredMethod(
            "renderMeetingState", MeetingRecordingState::class.java, MeetingModelStoreState::class.java,
        ).apply { isAccessible = true }.invoke(service, controller.state, MeetingModelStoreState.Missing)
        val panel = requireNotNull(field<MeetingPanelController?>(service, "meetingPanelController"))
        val actionsButton = panel.view.javaClass.getDeclaredField("actionsButton").apply { isAccessible = true }
            .get(panel.view) as android.widget.Button

        actionsButton.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val actionsDialog = requireNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("Actions de la réunion", Shadows.shadowOf(actionsDialog).getTitle().toString())
        val list = requireNotNull(actionsDialog.listView)
        val finishIndex = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == "Terminer la réunion" }
        list.performItemClick(list.getChildAt(finishIndex), finishIndex, list.adapter.getItemId(finishIndex))
        awaitMainCondition {
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.let {
                Shadows.shadowOf(it).getTitle().toString() == "Enregistrer la transcription ?"
            } == true
        }

        val confirmation = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("the selected menu command is delivered only after its dialog leaves the tracking set", confirmation)
        assertEquals("Enregistrer la transcription ?", Shadows.shadowOf(requireNotNull(confirmation)).getTitle().toString())
        assertEquals(1, field<Set<android.app.AlertDialog>>(service, "meetingDialogs").size)
        assertTrue(field<Boolean>(service, "meetingDialogOpen"))
    }

    @Test
    fun `meeting dialog security failure clears overlay tracking`() {
        val dialog = object : android.app.AlertDialog(context) {
            override fun show() {
                throw SecurityException("fixture permission loss")
            }
        }
        val shown = service.javaClass.getDeclaredMethod("showMeetingOverlayDialog", android.app.AlertDialog::class.java)
            .apply { isAccessible = true }.invoke(service, dialog) as Boolean

        assertFalse(shown)
        assertFalse(field<Boolean>(service, "meetingDialogOpen"))
        assertTrue(field<Set<android.app.AlertDialog>>(service, "meetingDialogs").isEmpty())
    }

    @Test
    fun `new meeting publishes edits made after finish before replacing the document`() {
        val file = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json")
        val original = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            participants = listOf(MeetingParticipant("speaker-1", ordinal = 1, channel = 1)),
            turns = listOf(
                MeetingTurn(
                    id = "turn-1",
                    utteranceId = 1L,
                    startMs = 0L,
                    endMs = 1_000L,
                    recognizedText = "Texte reconnu",
                    automaticParticipantId = "speaker-1",
                    attributionStable = true,
                ),
            ),
            finished = true,
        )
        MeetingDraftStore(file).save(original)
        openMeetingPanel(draftFile = file)
        val controller = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setControllerPhase(controller, MeetingRecordingPhase.FINISHED)
        val panel = requireNotNull(field<MeetingPanelController?>(service, "meetingPanelController"))
        panel.edit("turn-1", "Retouche après la fin")
        assertEquals("the final focused edit is collected before locking the rows", "Retouche après la fin",
            controller.state.document.turns.single().editedText)

        invokeStartNewMeetingAfterSaving(service, controller)
        assertEquals("the old editable rows disappear while its draft transfers", View.GONE, panel.view.recyclerView.visibility)
        assertEquals("the visible panel explains the transfer", MeetingPanelStatus.Phase.CLOSING,
            field<MeetingPanelStatus>(panel, "status").phase)
        panel.edit("turn-1", "Modification interdite pendant le transfert")
        assertEquals("stale editor callbacks cannot change a document being transferred", "Retouche après la fin",
            controller.state.document.turns.single().editedText)
        val actions = service.javaClass.getDeclaredMethod("meetingPanelActions")
            .apply { isAccessible = true }.invoke(service) as MeetingPanelActions
        actions.assign("turn-1", null)
        actions.rename("speaker-1", "Karim")
        actions.setIgnored("speaker-1", true)
        assertEquals("assignment, rename, and filtering actions are blocked during transfer", null,
            controller.state.document.participants.single().name)
        assertFalse(controller.state.document.participants.single().ignored)

        val noteStore = field<Lazy<TranscriptNotes>>(service, "notes\$delegate").value
        awaitMainCondition {
            noteStore.get(original.sessionId)?.meeting?.turns?.singleOrNull()?.editedText == "Retouche après la fin"
        }
        assertEquals("the durable note includes post-finish edits", "Retouche après la fin",
            noteStore.get(original.sessionId)?.meeting?.turns?.singleOrNull()?.editedText)
        assertTrue("a fresh controller is installed only after publishing the finished note",
            field<MeetingRecordingController?>(service, "meetingRecordingController") !== controller)
    }

    @Test
    fun `returning to a known note after an opaque presentation restores the editor`() {
        val fixture = openMeetingPanel()
        val previous = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        val previousAnchor = previous.ensureDocumentTurn()
        previous.editTurn(previousAnchor.id, "Réunion précédente")
        setControllerPhase(previous, MeetingRecordingPhase.FINISHED)
        val notes = field<Lazy<TranscriptNotes>>(service, "notes\$delegate").value

        val opaqueNote = TranscriptNote(
            id = UUID.randomUUID().toString(),
            title = "Réunion à version future",
            text = "Texte opaque de repli.",
            updatedAt = 1_790_000_000_010L,
            meetingRaw = """{"schemaVersion":7,"future":true}""",
        )
        service.javaClass.getDeclaredMethod("openNote", TranscriptNote::class.java)
            .apply { isAccessible = true }.invoke(service, opaqueNote)

        awaitMainCondition {
            field<MeetingRecordingController?>(service, "meetingRecordingController") == null &&
                field<TextView?>(service, "meetingOpaqueMessage")?.text?.contains("Texte opaque de repli.") == true
        }
        assertEquals("Réunion précédente", notes.get(previous.state.document.sessionId)?.text)

        val nextDocument = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(MeetingTurn("next-turn", 1L, 0L, 400L, "Texte structuré retrouvé", null)),
            finished = true,
        )
        val knownNote = TranscriptNote(
            id = nextDocument.sessionId,
            title = "Réunion connue",
            text = "Ancien texte plat.",
            updatedAt = 1_790_000_000_011L,
            meeting = nextDocument,
        )
        service.javaClass.getDeclaredMethod("openNote", TranscriptNote::class.java)
            .apply { isAccessible = true }.invoke(service, knownNote)

        awaitMainCondition {
            val current = field<MeetingRecordingController?>(service, "meetingRecordingController")
            current?.state?.document?.sessionId == nextDocument.sessionId &&
                field<TextView?>(service, "meetingOpaqueMessage") == null &&
                field<MeetingPanelController?>(service, "meetingPanelController")?.view?.adapter?.itemCount == 1
        }
        val panel = requireNotNull(field<MeetingPanelController?>(service, "meetingPanelController"))
        assertEquals(View.VISIBLE, panel.view.recyclerView.visibility)
        assertEquals("Texte structuré retrouvé", panel.view.adapter.rowAt(0)?.row?.body)
        assertEquals(0, fixture.reservationCalls.get())
        assertEquals(0, fixture.sessionCalls.get())
        assertEquals(0, fixture.microphoneCalls.get())
    }

    @Test
    fun `replacement note with a mismatched session identity stays opaque`() {
        val fixture = openMeetingPanel()
        val previous = requireNotNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        setControllerPhase(previous, MeetingRecordingPhase.FINISHED)
        val mismatchDocument = MeetingDocument(
            sessionId = UUID.randomUUID().toString(),
            runId = UUID.randomUUID().toString(),
            turns = listOf(MeetingTurn("mismatch-turn", 1L, 0L, 400L, "Ne pas ouvrir en édition", null)),
            finished = true,
        )
        val mismatchedNote = TranscriptNote(
            id = UUID.randomUUID().toString(),
            title = "Identité incohérente",
            text = "Texte de repli.",
            updatedAt = 1_790_000_000_012L,
            meeting = mismatchDocument,
        )

        service.javaClass.getDeclaredMethod("openNote", TranscriptNote::class.java)
            .apply { isAccessible = true }.invoke(service, mismatchedNote)

        awaitMainCondition {
            field<MeetingRecordingController?>(service, "meetingRecordingController") !== previous &&
                field<TextView?>(service, "meetingOpaqueMessage") != null
        }
        assertNull(field<MeetingRecordingController?>(service, "meetingRecordingController"))
        val message = requireNotNull(field<TextView?>(service, "meetingOpaqueMessage"))
        assertTrue(message.text.contains("ne correspond pas à sa session"))
        val notes = field<Lazy<TranscriptNotes>>(service, "notes\$delegate").value
        assertNull("an invalid target identity is not published as a note", notes.get(mismatchedNote.id))
        assertNull("a blank previous document is not published either", notes.get(previous.state.document.sessionId))
        assertEquals(0, fixture.reservationCalls.get())
        assertEquals(0, fixture.sessionCalls.get())
        assertEquals(0, fixture.microphoneCalls.get())
    }

    private fun openMeetingPanel(
        draftFile: File = File(context.cacheDir, "meeting-panel-${UUID.randomUUID()}.json"),
        draftOwnership: MeetingDraftOwnership? = null,
    ): PanelFixture {
        assertEquals(true, coordinator.changeMode(TranscriptionMode.MEETING))
        val owner = draftOwnership ?: MeetingDraftOwnership(executor = MeetingDraftOwnershipExecutor { task -> task() })
        val modelStore = MeetingModelStore.shared(context)
        val reservationCalls = AtomicInteger()
        val sessionCalls = AtomicInteger()
        val microphoneCalls = AtomicInteger()
        var installed = false
        Handler(Looper.getMainLooper()).post {
            installed = service.installMeetingTestOverrides(
                OverlayService.MeetingTestOverrides(
                    draftOwnership = owner,
                    draftFile = draftFile,
                    modelStore = modelStore,
                    modelAvailability = MeetingModelAvailabilityPort { MeetingModelAvailability.MISSING },
                    reservation = MeetingNativeReservationPort {
                        reservationCalls.incrementAndGet()
                        throw AssertionError("opening the panel must not reserve native recording")
                    },
                    sessionFactory = object : MeetingSessionFactoryPort {
                        override fun start(
                            runId: String,
                            language: String,
                            onReady: () -> Unit,
                            onUpdate: (MeetingHypothesis) -> Unit,
                            onFailure: (String) -> Unit,
                        ): MeetingSession {
                            sessionCalls.incrementAndGet()
                            throw AssertionError("opening the panel must not load the native engine")
                        }
                    },
                    microphoneFactory = MeetingMicrophoneFactoryPort {
                        microphoneCalls.incrementAndGet()
                        throw AssertionError("opening the panel must not create AudioRecord")
                    },
                ),
            )
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the test native ports are installed before the panel is opened", installed)
        service.javaClass.getDeclaredMethod(
            "showFormatPicker", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(service, false, true)

        val menu = field<View?>(service, "floatingMenu")
        val openMeeting = findText(menu, "Ouvrir la réunion")
        assertNotNull(openMeeting)
        requireNotNull(openMeeting).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return PanelFixture(reservationCalls, sessionCalls, microphoneCalls)
    }

    private fun setControllerPhase(controller: MeetingRecordingController, phase: MeetingRecordingPhase) {
        val field = controller.javaClass.getDeclaredField("currentState").apply { isAccessible = true }
        val current = field.get(controller) as MeetingRecordingState
        field.set(controller, current.copy(phase = phase))
    }

    private fun setMeetingState(controller: MeetingRecordingController, phase: MeetingRecordingPhase) {
        setControllerPhase(controller, phase)
        setField(service, "meetingControllerState", controller.state)
    }

    private fun setServiceState(name: String) {
        val stateField = service.javaClass.getDeclaredField("state").apply { isAccessible = true }
        val state = stateField.type.enumConstants.single { (it as Enum<*>).name == name }
        stateField.set(service, state)
    }

    private fun invokeStartNewMeetingAfterSaving(
        service: OverlayService,
        controller: MeetingRecordingController,
    ) {
        service.javaClass.getDeclaredMethod(
            "startNewMeetingAfterSaving",
            MeetingRecordingController::class.java,
            TranscriptNote::class.java,
            java.lang.Long::class.java,
        ).apply { isAccessible = true }.invoke(service, controller, null, null)
    }

    private fun awaitMainCondition(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadlineNanos) {
            looper.idle()
            Thread.sleep(10L)
        }
        looper.idle()
        assertTrue("condition should complete within ${timeoutMs} ms", condition())
    }

    private fun installNoteStorage(storage: TranscriptNoteStorage): TranscriptNotes {
        val notes = TranscriptNotes(storage)
        setField(service, "notes\$delegate", lazyOf(notes))
        return notes
    }

    private fun send(view: View, action: Int, x: Float, y: Float) {
        val time = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(time, time, action, x, y, 0))
    }

    private inline fun <reified T> field(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.get(target) as T
    }

    private fun findText(root: View?, expected: String): TextView? {
        if (root == null) return null
        if (root is TextView && root.text.toString() == expected) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                findText(root.getChildAt(index), expected)?.let { return it }
            }
        }
        return null
    }

    private fun findTextContaining(root: View?, expected: String): TextView? {
        if (root == null) return null
        if (root is TextView && expected in root.text.toString()) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextContaining(root.getChildAt(index), expected)?.let { return it }
            }
        }
        return null
    }

    private fun invoke(target: Any, name: String): Any? =
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
