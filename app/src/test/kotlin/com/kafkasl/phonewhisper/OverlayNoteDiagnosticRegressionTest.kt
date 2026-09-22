package com.kafkasl.phonewhisper

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

/**
 * Exercises the real stop -> publication path with a synthetic ASR session.
 *
 * The dictated text is deliberately private-looking and must only appear in the saved note;
 * the persisted diagnostic is metadata-only.  These tests call the service's actual private
 * [OverlayService.processStoppedRecording] entry point through reflection because the audio and
 * model layers are outside this regression's scope.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayNoteDiagnosticRegressionTest {
    private lateinit var context: Context
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService

    private var oldLatestReport: String? = null
    private var oldFormatReport: String? = null
    private var oldNotes: Map<String, String> = emptyMap()
    private var oldFolders: Map<String, String> = emptyMap()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        snapshotAndClearPersistence()
        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        ShadowLooper.idleMainLooper(500L)
        restorePersistence()
        ShadowSettings.reset()
    }

    @Test
    fun `archive as note saves text and refreshes metadata without leaking dictated text`() {
        val oldReport = seedOldReport()
        val privateText = "Texte privé synthétique pour la note"
        val session = FakeSession(privateText)
        val run = newActiveRun(session, DictationPurpose.MESSAGE)
        setField(run, "archiveAsNote", true)
        installRun(run, DictationPurpose.MESSAGE)

        invoke(service, "processStoppedRecording", newCapture(run, session))
        drainMain()

        assertEquals(1, session.finishCalls)
        val report = latestReport()
        assertNotNull(report)
        assertTrue(report!!.contains("Application : ${BuildConfig.VERSION_NAME}"))
        assertTrue(report.contains("Date : "))
        assertTrue(report.contains("Publication : note enregistrée"))
        assertFalse("diagnostics must never persist dictated text", report.contains(privateText))
        assertFalse("the old 0.9.5 report must be replaced", report == oldReport)
        assertNotNull(savedNoteContaining(privateText))
    }

    @Test
    fun `note destination saves the note through the non archive path`() {
        val oldReport = seedOldReport()
        val privateText = "Texte privé synthétique dans une note existante"
        val session = FakeSession(privateText)
        val run = newActiveRun(session, DictationPurpose.NOTE)
        installRun(run, DictationPurpose.NOTE)

        invoke(service, "processStoppedRecording", newCapture(run, session))
        drainMain()

        assertEquals(1, session.finishCalls)
        val report = requireNotNull(latestReport())
        assertTrue(report.contains("Application : ${BuildConfig.VERSION_NAME}"))
        assertTrue(report.contains("Date : "))
        assertNotEquals("the NOTE path must replace the old diagnostic", oldReport, report)
        assertTrue(report.contains("Publication : note enregistrée"))
        assertFalse(report.contains(privateText))
        assertNotNull(savedNoteContaining(privateText))
    }

    @Test
    fun `message destination remains message publication and does not create a note`() {
        seedOldReport()
        val privateText = "Texte privé synthétique pour un message"
        val session = FakeSession(privateText)
        val run = newActiveRun(session, DictationPurpose.MESSAGE)
        installRun(run, DictationPurpose.MESSAGE)
        val notesBefore = currentNotes().map { it.id }.toSet()

        invoke(service, "processStoppedRecording", newCapture(run, session))
        drainMain()

        assertEquals(1, session.finishCalls)
        val report = requireNotNull(latestReport())
        assertFalse(report.contains("Publication : note enregistrée"))
        assertFalse(report.contains(privateText))
        assertTrue(
            "MESSAGE must retain an insertion/copy publication result",
            listOf("Publication : inséré", "Publication : copié", "Publication : échec")
                .any(report::contains),
        )
        assertEquals(notesBefore, currentNotes().map { it.id }.toSet())
    }

    @Test
    fun `cancellation before publication keeps the old report and does not save a note`() {
        val oldReport = seedOldReport()
        val privateText = "Texte privé synthétique annulé"
        val session = FakeSession(privateText)
        val run = newActiveRun(session, DictationPurpose.MESSAGE)
        setField(run, "archiveAsNote", true)
        installRun(run, DictationPurpose.MESSAGE)
        val cancellation = field<DictationCancellationCoordinator>(run, "cancellation")

        invoke(service, "processStoppedRecording", newCapture(run, session))
        // processStoppedRecording has finished ASR and queued publication, but the paused main
        // looper has not crossed that publication boundary yet.
        assertEquals(1, session.finishCalls)
        cancellation.cancel()
        drainMain()

        assertEquals("late cancellation must suppress queued publication", 1, session.finishCalls)
        assertEquals(oldReport, latestReport())
        assertTrue(currentNotes().none { it.text.contains(privateText) })
    }

    private fun snapshotAndClearPersistence() {
        val reportPrefs = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        oldLatestReport = reportPrefs.getString("last_postprocessing_diagnostic", null)
        oldFormatReport = reportPrefs.getString("last_format_postprocessing_diagnostic", null)
        reportPrefs.edit()
            .remove("last_postprocessing_diagnostic")
            .remove("last_format_postprocessing_diagnostic")
            .commit()

        oldNotes = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
            .all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()
        oldFolders = context.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE)
            .all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()
        context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun restorePersistence() {
        val reportPrefs = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        val reportEdit = reportPrefs.edit()
            .remove("last_postprocessing_diagnostic")
            .remove("last_format_postprocessing_diagnostic")
        oldLatestReport?.let { reportEdit.putString("last_postprocessing_diagnostic", it) }
        oldFormatReport?.let { reportEdit.putString("last_format_postprocessing_diagnostic", it) }
        reportEdit.commit()

        val notes = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
        notes.edit().clear().apply { oldNotes.forEach { (key, value) -> putString(key, value) } }.commit()
        val folders = context.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE)
        folders.edit().clear().apply { oldFolders.forEach { (key, value) -> putString(key, value) } }.commit()
    }

    private fun seedOldReport(): String {
        val oldReport = "Application : 0.9.5\nDate : 2026-09-21 10:00:00 +0000\nFormat : Texte\nPublication : note enregistrée"
        context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).edit()
            .putString("last_postprocessing_diagnostic", oldReport)
            .putString("last_format_postprocessing_diagnostic", oldReport)
            .commit()
        return oldReport
    }

    private fun latestReport(): String? =
        PersistencePrefs(context).lastPostprocessingDiagnostic

    private fun currentNotes(): List<TranscriptNote> =
        TranscriptNotes(AndroidTranscriptNoteStorage(context)).all()

    private fun savedNoteContaining(text: String): TranscriptNote? =
        currentNotes().firstOrNull { it.text.contains(text) }

    private fun installRun(run: Any, purpose: DictationPurpose) {
        setField(service, "purpose", purpose)
        setField(service, "activeRun", run)
    }

    private fun newActiveRun(session: DictationAsrSession, purpose: DictationPurpose): Any {
        val optionsClass = Class.forName("com.kafkasl.phonewhisper.OverlayService\$RecordingOptions")
        val optionsConstructor = optionsClass.declaredConstructors.first { it.parameterTypes.size == 9 }
        optionsConstructor.isAccessible = true
        val options = optionsConstructor.newInstance(
            DictationLanguage.FRENCH,
            DictationAsrMode.BATCH,
            false,
            false,
            CloudModelCatalog.default,
            PostProcessingFormats.builtins.first(),
            false,
            NumberStyle.DIGITS,
            true,
        )

        val runClass = Class.forName("com.kafkasl.phonewhisper.OverlayService\$ActiveDictationRun")
        val constructor = runClass.declaredConstructors.first { it.parameterTypes.size == 4 }
        constructor.isAccessible = true
        return constructor.newInstance(
            session,
            options,
            purpose,
            DictationCancellationCoordinator(),
        )
    }

    private fun newCapture(run: Any, session: DictationAsrSession): Any {
        val captureClass = Class.forName("com.kafkasl.phonewhisper.OverlayService\$RecordingCapture")
        val constructor = captureClass.declaredConstructors.first { it.parameterTypes.size == 4 }
        constructor.isAccessible = true
        return constructor.newInstance(
            run,
            byteArrayOf(1, 2),
            session,
            field<Any>(run, "formatOptions"),
        )
    }

    private fun drainMain() {
        ShadowLooper.idleMainLooper(500L)
        ShadowLooper.idleMainLooper(500L)
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }?.let { method ->
                method.isAccessible = true
                method.invoke(target, *args)
                return
            }
            type = type.superclass
        }
        error("No method $name on ${target.javaClass.name}")
    }

    private inline fun <reified T> field(target: Any, name: String): T {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { declared ->
                declared.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return declared.get(target) as T
            }
            type = type.superclass
        }
        error("No field $name on ${target.javaClass.name}")
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { declared ->
                declared.isAccessible = true
                declared.set(target, value)
                return
            }
            type = type.superclass
        }
        error("No field $name on ${target.javaClass.name}")
    }

    private class FakeSession(private val finalText: String) : DictationAsrSession {
        var finishCalls = 0

        override fun acceptPcm16(buffer: ByteArray, length: Int) = Unit

        override fun finish(fullPcm: ByteArray): TranscriptionEngine.Result {
            finishCalls++
            return TranscriptionEngine.Result(finalText)
        }

        override fun cancel() = Unit

        override fun cancelAndAwait(): Boolean = true
    }
}
