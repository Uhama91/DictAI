package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingDocumentJson
import com.kafkasl.phonewhisper.meeting.MeetingDocumentRead
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailability
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailabilityPort
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationPort
import com.kafkasl.phonewhisper.meeting.MeetingParticipant
import com.kafkasl.phonewhisper.meeting.MeetingPanelController
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingSession
import com.kafkasl.phonewhisper.meeting.MeetingSessionFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingMicrophoneFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingHypothesis
import com.kafkasl.phonewhisper.meeting.MeetingTurn
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowSettings

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceMeetingDocumentRobolectricTest {
    private class NativeCallCounts {
        val reservations = AtomicInteger()
        val sessions = AtomicInteger()
        val microphones = AtomicInteger()
    }

    private lateinit var context: Context
    private lateinit var coordinator: TranscriptionModeCoordinator
    private lateinit var notesStorage: AndroidTranscriptNoteStorage
    private lateinit var notesPreferences: SharedPreferences
    private lateinit var draftPreferences: SharedPreferences
    private lateinit var draftPreferencesBefore: Map<String, Any?>
    private lateinit var nativeCalls: NativeCallCounts
    private lateinit var draftDirectory: File
    private lateinit var draftFile: File
    private var previousMode = TranscriptionMode.DICTATION
    private var ownedNoteId: String? = null
    private var previousNoteValue: String? = null
    private var serviceController: ServiceController<OverlayService>? = null
    private var service: OverlayService? = null
    private var noteWriteListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val persistence = PersistencePrefs(context)
        previousMode = persistence.transcriptionMode
        TranscriptionModeCoordinator.clearProcessForTest()
        persistence.transcriptionMode = TranscriptionMode.DICTATION
        coordinator = TranscriptionModeCoordinator.process(context)

        notesStorage = AndroidTranscriptNoteStorage(context)
        notesPreferences = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
        draftPreferences = context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE)
        draftPreferencesBefore = draftPreferences.all.toMap()
        draftPreferences.edit().clear().commit()

        val fixtureId = UUID.randomUUID().toString()
        draftDirectory = File(context.cacheDir, "meeting-document-reopen-$fixtureId").apply {
            check(mkdirs())
        }
        draftFile = File(draftDirectory, "draft.json")
        nativeCalls = NativeCallCounts()
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

        cleanup {
            noteWriteListener?.let(notesPreferences::unregisterOnSharedPreferenceChangeListener)
            noteWriteListener = null
        }

        val runningService = service
        var controller: MeetingRecordingController? = null
        if (runningService != null) {
            cleanup { controller = field(runningService, "meetingRecordingController") }
        }
        var closeFuture: java.util.concurrent.CompletableFuture<Unit>? = null
        cleanup {
            onMain {
                closeFuture = controller?.destroy()
                serviceController?.destroy()
            }
        }
        closeFuture?.let { future ->
            cleanup {
                check(awaitMainUntil(2_000L) { future.isDone }) {
                    "Meeting recording controller did not finish closing during fixture cleanup"
                }
                future.get(2, TimeUnit.SECONDS)
            }
        }

        cleanup { ownedNoteId?.let { id ->
            val editor = notesPreferences.edit()
            if (previousNoteValue == null) editor.remove(id) else editor.putString(id, previousNoteValue)
            check(editor.commit()) { "Could not restore fixture note $id" }
        } }
        cleanup { restorePreferences("dictation_draft", draftPreferencesBefore) }
        cleanup { PersistencePrefs(context).transcriptionMode = previousMode }
        cleanup { TranscriptionModeCoordinator.clearProcessForTest() }

        cleanup {
            listOf(draftFile, File(draftFile.path + ".bak"), File(draftFile.path + ".new"))
                .forEach { file -> check(!file.exists() || file.delete()) { "Could not remove fixture file ${file.name}" } }
            check(!draftDirectory.exists() || draftDirectory.delete()) { "Could not remove fixture directory" }
        }
        cleanup { ShadowSettings.reset() }
        cleanupFailure?.let { throw AssertionError("Meeting document fixture cleanup failed", it) }
    }

    @Test
    fun `opening a finished structured note renders the edited participant without starting audio`() {
        val sessionId = UUID.randomUUID().toString()
        val document = MeetingDocument(
            sessionId = sessionId,
            runId = UUID.randomUUID().toString(),
            participants = listOf(MeetingParticipant("speaker-1", ordinal = 1, channel = 1, name = "Sophie")),
            turns = listOf(
                MeetingTurn(
                    id = "turn-1",
                    utteranceId = 1,
                    startMs = 100,
                    endMs = 900,
                    recognizedText = "La réunion est mardi.",
                    automaticParticipantId = "speaker-1",
                    editedText = "La réunion est jeudi.",
                    attributionStable = true,
                ),
            ),
            finished = true,
        )
        val staleFlatText = "Texte plat obsolète : cette réunion est mardi."
        val note = TranscriptNote(
            id = sessionId,
            title = "Réunion du matin",
            text = staleFlatText,
            updatedAt = 1_790_000_000_000L,
            meeting = document,
        )
        persistFixtureNote(note)
        val storedRecord = requireNotNull(notesPreferences.getString(note.id, null))
        assertEquals(staleFlatText, JSONObject(storedRecord).getString("text"))

        startServiceWithNativeFakes()
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        assertTrue("the structured note opens without a Dictation draft", draftPreferences.all.isEmpty())
        assertNull("the Dictation mode must be idle before opening the note", coordinator.snapshot().activeRunMode)

        invokeOpenNote(note)

        assertTrue("opening the note should finish rendering its restored participant panel", awaitMainUntil {
            val panel = runCatching { field<MeetingPanelController?>(requireNotNull(service), "meetingPanelController") }
                .getOrNull()
            panel?.view?.adapter?.itemCount == 1
        })
        val panel = requireNotNull(field<MeetingPanelController?>(requireNotNull(service), "meetingPanelController"))
        val rendered = requireNotNull(panel.view.adapter.rowAt(0)?.row)
        assertEquals("Sophie", rendered.label)
        assertEquals("La réunion est jeudi.", rendered.body)
        assertFalse("the stale plain-text column must not replace the structured edit", rendered.body.contains(staleFlatText))
        assertEquals(document, requireNotNull(field<MeetingRecordingController?>(requireNotNull(service), "meetingRecordingController")).state.document)
        assertNotNull("the participant count belongs to the real panel view", findText(panel.view, "Intervenants (1)"))

        assertNoMeetingAudioOrRun()
    }

    @Test
    fun `opening an unsupported meeting note keeps its fallback read only and does not rewrite it`() {
        val futurePayload = """{"schemaVersion":7,"futureField":{"state":"keep-opaque"}}"""
        assertTrue(MeetingDocumentJson.decode(futurePayload) is MeetingDocumentRead.Unsupported)
        val fallbackText = "Compte rendu original conservé sans migration."
        val note = TranscriptNote(
            id = UUID.randomUUID().toString(),
            title = "Réunion version future",
            text = fallbackText,
            updatedAt = 1_790_000_000_001L,
            meetingRaw = futurePayload,
        )
        persistFixtureNote(note)
        val storedNote = requireNotNull(notesStorage.all().firstOrNull { it.id == note.id })
        assertEquals(fallbackText, storedNote.text)
        assertEquals(futurePayload, storedNote.meetingRaw)
        val storedRecordBeforeOpen = requireNotNull(notesPreferences.getString(note.id, null))
        val noteWrites = AtomicInteger()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == note.id) noteWrites.incrementAndGet()
        }
        noteWriteListener = listener
        notesPreferences.registerOnSharedPreferenceChangeListener(listener)

        startServiceWithNativeFakes()
        assertNull("the unsupported note opens without an active run", coordinator.snapshot().activeRunMode)
        invokeOpenNote(storedNote)

        assertTrue("the future-version payload should open a protected read-only surface", awaitMainUntil {
            field<TextView?>(requireNotNull(service), "meetingOpaqueMessage") != null
        })
        val message = requireNotNull(field<TextView?>(requireNotNull(service), "meetingOpaqueMessage"))
        assertTrue(message.text.contains("lecture seule"))
        assertTrue(message.text.contains(fallbackText))
        assertFalse("the opaque JSON should not be flattened into the visible note", message.text.contains(futurePayload))
        assertFalse("an unknown version is displayed as non-editable text", message.isFocusable)
        val openedService = requireNotNull(service)
        assertNull(
            "unknown documents do not create a recording controller",
            field<MeetingRecordingController?>(openedService, "meetingRecordingController"),
        )
        val presentationPanel = field<MeetingPanelController?>(openedService, "meetingPanelController")
        if (presentationPanel != null) {
            assertTrue(
                "an opaque presentation panel does not expose editable speech fields",
                descendants(presentationPanel.view).none { it is OverlayTranscriptEditor },
            )
        }

        assertEquals("opening a protected note must not write or migrate its stored record", 0, noteWrites.get())
        assertEquals(storedRecordBeforeOpen, notesPreferences.getString(note.id, null))
        val afterOpen = requireNotNull(notesStorage.all().firstOrNull { it.id == note.id })
        assertNull(afterOpen.meeting)
        assertEquals(futurePayload, afterOpen.meetingRaw)
        assertEquals(fallbackText, afterOpen.text)

        assertNoMeetingAudioOrRun()
    }

    @Test
    fun `opening another note cannot replace a meeting document while its coordinator lease is held`() {
        val meetingBody = "Texte du document Réunion à conserver."
        val incomingNote = TranscriptNote(
            id = UUID.randomUUID().toString(),
            title = "Note plate entrante",
            text = "Texte plat qui ne doit pas remplacer la Réunion.",
            updatedAt = 1_790_000_000_002L,
        )
        persistFixtureNote(incomingNote)
        val incomingRecordBefore = requireNotNull(notesPreferences.getString(incomingNote.id, null))

        assertTrue("the fixture selects Meeting before creating the service", coordinator.changeMode(TranscriptionMode.MEETING))
        startServiceWithNativeFakes()
        val runningService = requireNotNull(service)
        onMain { invoke(runningService, "openMeetingPanel") }

        var controllerBeforeOpen: MeetingRecordingController? = null
        assertTrue("the real Meeting panel creates its controller", awaitMainUntil {
            controllerBeforeOpen = field<MeetingRecordingController?>(runningService, "meetingRecordingController")
            controllerBeforeOpen != null
        })
        val controller = requireNotNull(controllerBeforeOpen)
        var sourceTurnId: String? = null
        onMain {
            sourceTurnId = controller.ensureDocumentTurn().id
            controller.editTurn(requireNotNull(sourceTurnId), meetingBody)
        }
        val sourceTurn = requireNotNull(sourceTurnId)
        assertTrue("the edited body is rendered in the real panel", awaitMainUntil {
            field<MeetingPanelController?>(runningService, "meetingPanelController")
                ?.view?.adapter?.rowAt(0)?.row?.body == meetingBody
        })

        var documentBefore: MeetingDocument? = null
        var activeNoteIdBefore: String? = null
        var purposeBefore: DictationPurpose? = null
        var panelBefore: MeetingPanelController? = null
        onMain {
            documentBefore = controller.state.document
            activeNoteIdBefore = field(runningService, "activeNoteId")
            purposeBefore = field(runningService, "purpose")
            panelBefore = field(runningService, "meetingPanelController")
        }
        val sourceDocument = requireNotNull(documentBefore)
        assertEquals(meetingBody, sourceDocument.turns.single { it.id == sourceTurn }.editedText)
        val flatDraftBefore = draftPreferences.all.toMap()

        val incomingNoteWrites = AtomicInteger()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == incomingNote.id) incomingNoteWrites.incrementAndGet()
        }
        noteWriteListener = listener
        notesPreferences.registerOnSharedPreferenceChangeListener(listener)

        val heldLease = requireNotNull(coordinator.reserveRun(TranscriptionMode.MEETING))
        try {
            assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
            assertFalse("a Meeting lease blocks switching modes even without capture", coordinator.changeMode(TranscriptionMode.DICTATION))

            invokeOpenNote(incomingNote)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertSame(controller, field<MeetingRecordingController?>(runningService, "meetingRecordingController"))
            assertSame(panelBefore, field<MeetingPanelController?>(runningService, "meetingPanelController"))
            var documentAfter: MeetingDocument? = null
            var activeNoteIdAfter: String? = null
            var purposeAfter: DictationPurpose? = null
            onMain {
                documentAfter = controller.state.document
                activeNoteIdAfter = field(runningService, "activeNoteId")
                purposeAfter = field(runningService, "purpose")
            }
            assertEquals("the existing Meeting session and document remain active", sourceDocument, documentAfter)
            assertEquals(sourceDocument.sessionId, requireNotNull(documentAfter).sessionId)
            assertEquals(meetingBody, requireNotNull(documentAfter).turns.single { it.id == sourceTurn }.editedText)
            assertEquals(activeNoteIdBefore, activeNoteIdAfter)
            assertEquals(purposeBefore, purposeAfter)
            assertEquals("the Meeting panel still renders its original body", meetingBody,
                field<MeetingPanelController?>(runningService, "meetingPanelController")
                    ?.view?.adapter?.rowAt(0)?.row?.body)
            assertEquals("opening another note must not write the flat Dictation draft", flatDraftBefore, draftPreferences.all.toMap())
            assertEquals("the incoming note remains untouched", incomingRecordBefore, notesPreferences.getString(incomingNote.id, null))
            assertEquals("the incoming note received no write", 0, incomingNoteWrites.get())
            assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
            assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
            assertNull(field<Any?>(runningService, "activeRun"))
            assertNull(field<Any?>(runningService, "audioRecord"))
            assertEquals(0, nativeCalls.reservations.get())
            assertEquals(0, nativeCalls.sessions.get())
            assertEquals(0, nativeCalls.microphones.get())
            assertFalse(OverlayService.micArmed)
        } finally {
            heldLease.close()
        }
        assertNull("only the fixture lease is released", coordinator.snapshot().activeRunMode)
    }

    private fun persistFixtureNote(note: TranscriptNote) {
        ownedNoteId = note.id
        previousNoteValue = notesPreferences.getString(note.id, null)
        notesStorage.put(note)
    }

    private fun startServiceWithNativeFakes() {
        val created = Robolectric.buildService(OverlayService::class.java)
        val started = created.create().get()
        serviceController = created
        service = started
        onMain { invoke(started, "showButton") }

        val modelStore = MeetingModelStore.shared(context)
        val overrides = OverlayService.MeetingTestOverrides(
            draftOwnership = MeetingDraftOwnership(),
            draftFile = draftFile,
            modelStore = modelStore,
            modelAvailability = MeetingModelAvailabilityPort {
                when (modelStore.currentState) {
                    is MeetingModelStoreState.Ready -> MeetingModelAvailability.READY
                    MeetingModelStoreState.Checking,
                    is MeetingModelStoreState.Downloading -> MeetingModelAvailability.DOWNLOADING
                    MeetingModelStoreState.Missing,
                    is MeetingModelStoreState.Error -> MeetingModelAvailability.MISSING
                }
            },
            reservation = MeetingNativeReservationPort {
                nativeCalls.reservations.incrementAndGet()
                throw AssertionError("opening a saved note must not reserve the native meeting runtime")
            },
            sessionFactory = object : MeetingSessionFactoryPort {
                override fun start(
                    runId: String,
                    language: String,
                    onReady: () -> Unit,
                    onUpdate: (MeetingHypothesis) -> Unit,
                    onFailure: (String) -> Unit,
                ): MeetingSession {
                    nativeCalls.sessions.incrementAndGet()
                    throw AssertionError("opening a saved note must not create a native meeting session")
                }
            },
            microphoneFactory = MeetingMicrophoneFactoryPort {
                nativeCalls.microphones.incrementAndGet()
                throw AssertionError("opening a saved note must not create a microphone")
            },
        )
        var installed = false
        onMain { installed = started.installMeetingTestOverrides(overrides) }
        assertTrue("instance-scoped native fakes are installed before opening a note", installed)
    }

    private fun invokeOpenNote(note: TranscriptNote) {
        onMain { invoke(requireNotNull(service), "openNote", note) }
    }

    private fun assertNoMeetingAudioOrRun() {
        assertEquals(0, nativeCalls.reservations.get())
        assertEquals(0, nativeCalls.sessions.get())
        assertEquals(0, nativeCalls.microphones.get())
        assertNull(field<Any?>(requireNotNull(service), "activeRun"))
        assertNull(field<Any?>(requireNotNull(service), "audioRecord"))
        assertNull(coordinator.snapshot().activeRunMode)
        assertFalse("saved-note viewing must not arm the microphone", OverlayService.micArmed)
    }

    private fun awaitMainUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private fun onMain(block: () -> Unit) {
        val failure = arrayOfNulls<Throwable>(1)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (thrown: Throwable) {
                failure[0] = thrown
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        failure[0]?.let { throw AssertionError("Main-thread fixture operation failed", it) }
    }

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(target) as T
        }

    private fun findText(root: View?, expected: String): TextView? {
        if (root == null) return null
        if (root is TextView && root.text.toString() == expected) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findText(root.getChildAt(index), expected)?.let { return it }
            }
        }
        return null
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
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
        editor.commit()
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first { candidate ->
            candidate.name == name && candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.zip(args).all { (type, argument) ->
                    argument == null || type.isAssignableFrom(argument.javaClass) ||
                        (type.isPrimitive && argument is Boolean && type == Boolean::class.javaPrimitiveType)
                }
        }.apply { isAccessible = true }
        method.invoke(target, *args)
    }
}
