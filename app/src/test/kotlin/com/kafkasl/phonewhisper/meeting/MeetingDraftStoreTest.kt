package com.kafkasl.phonewhisper.meeting

import android.util.AtomicFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingDraftStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `load reports absent only when no draft exists`() {
        val store = MeetingDraftStore(draftFile())

        assertEquals(MeetingDocumentRead.Absent, store.load())
    }

    @Test
    fun `save and load round trip a complete meeting document`() {
        val store = MeetingDraftStore(draftFile())
        val document = MeetingDocument(
            sessionId = "session-α",
            runId = "run-β",
            participants = listOf(
                MeetingParticipant("p1", ordinal = 1, channel = 1, name = "Zoë 🌸", ignored = true),
            ),
            turns = listOf(
                MeetingTurn(
                    id = "turn-1",
                    utteranceId = 12,
                    startMs = 100,
                    endMs = 260,
                    recognizedText = "ligne \"citée\"\nSeconde ligne 😀",
                    automaticParticipantId = "p1",
                    manualParticipantId = null,
                    hasManualAttribution = true,
                    editedText = "",
                    attributionStable = true,
                ),
            ),
            finished = false,
        )

        store.save(document)

        assertEquals(MeetingDocumentRead.Ready(document), store.load())
    }

    @Test
    fun `invalid document is rejected before changing an existing draft`() {
        val file = draftFile()
        val store = MeetingDraftStore(file)
        val existing = MeetingDocument(sessionId = "kept-session", runId = "kept-run")
        store.save(existing)
        val originalBytes = file.readBytes()

        assertIllegalArgument { store.save(existing.copy(schemaVersion = 2)) }

        assertArrayEquals(originalBytes, file.readBytes())
        assertFalse(File(file.path + ".bak").exists())
        assertEquals(MeetingDocumentRead.Ready(existing), store.load())
    }

    @Test
    fun `silent atomic finish failure is reported and preserves previous draft`() {
        val file = draftFile()
        val original = MeetingDocument(sessionId = "original", runId = "run")
        MeetingDraftStore(file).save(original)
        val originalBytes = file.readBytes()
        val storeWithSilentFinish = MeetingDraftStore(file, SilentFinishAtomicFile(file))

        assertIOException {
            storeWithSilentFinish.save(MeetingDocument(sessionId = "replacement", runId = "run"))
        }

        assertArrayEquals(originalBytes, file.readBytes())
        assertFalse(File(file.path + ".new").exists())
        assertFalse(File(file.path + ".bak").exists())
        assertEquals(MeetingDocumentRead.Ready(original), MeetingDraftStore(file).load())
    }

    @Test
    fun `unsupported and corrupt payloads cannot be overwritten by save`() {
        val file = draftFile()
        val store = MeetingDraftStore(file)
        val replacement = MeetingDocument(sessionId = "replacement", runId = "run")
        val protectedPayloads = listOf(
            """ { "schemaVersion": 7, "keep": "future" } """,
            """{"schemaVersion":1,"truncated": """,
        )

        protectedPayloads.forEach { raw ->
            file.writeText(raw, Charsets.UTF_8)
            val originalBytes = file.readBytes()

            assertIllegalState { store.save(replacement) }

            assertArrayEquals(originalBytes, file.readBytes())
        }
    }

    @Test
    fun `atomic file backup restores previous draft after interrupted write`() {
        val file = draftFile()
        val oldDocument = MeetingDocument(sessionId = "old-session", runId = "old-run")
        MeetingDraftStore(file).save(oldDocument)

        val interruptedOutput = AtomicFile(file).startWrite()
        interruptedOutput.write("partial new document".toByteArray(Charsets.UTF_8))
        interruptedOutput.close()

        assertEquals(MeetingDocumentRead.Ready(oldDocument), MeetingDraftStore(file).load())
        assertEquals(MeetingDocumentJson.encode(oldDocument), file.readText(Charsets.UTF_8))
    }

    @Test
    fun `orphaned first write temp is absent and does not block a later save`() {
        val file = draftFile()
        val newFile = File(file.path + ".new")
        newFile.writeText("partial first write", Charsets.UTF_8)
        val store = MeetingDraftStore(file)

        assertEquals(MeetingDocumentRead.Absent, store.load())
        assertFalse(newFile.exists())

        val fresh = MeetingDocument(sessionId = "fresh", runId = "run")
        store.save(fresh)
        assertEquals(MeetingDocumentRead.Ready(fresh), store.load())
    }

    @Test
    fun `clear explicitly removes protected payload and allows a new draft`() {
        val file = draftFile()
        val store = MeetingDraftStore(file)
        val raw = """{"schemaVersion":9,"future":"preserve until clear"}"""
        file.writeText(raw, Charsets.UTF_8)
        assertIllegalState { store.save(MeetingDocument(sessionId = "new", runId = "run")) }

        store.clear()

        assertFalse(file.exists())
        assertFalse(File(file.path + ".bak").exists())
        val fresh = MeetingDocument(sessionId = "new", runId = "run")
        store.save(fresh)
        assertEquals(MeetingDocumentRead.Ready(fresh), store.load())
    }

    @Test
    fun `read io failures propagate instead of becoming absent`() {
        val directoryAtFilePath = File(temporaryFolder.root, "draft-is-a-directory")
        assertTrue(directoryAtFilePath.mkdir())

        var failure: IOException? = null
        try {
            MeetingDraftStore(directoryAtFilePath).load()
        } catch (error: IOException) {
            failure = error
        }

        assertNotNull("expected AtomicFile read failure to propagate", failure)
    }

    private fun draftFile(): File = File(temporaryFolder.root, "meeting-draft.json")

    private fun assertIllegalArgument(action: () -> Unit) {
        var thrown = false
        try {
            action()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("expected IllegalArgumentException", thrown)
    }

    private fun assertIllegalState(action: () -> Unit) {
        var thrown = false
        try {
            action()
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue("expected explicit refusal to overwrite", thrown)
    }

    private fun assertIOException(action: () -> Unit) {
        var thrown = false
        try {
            action()
        } catch (_: IOException) {
            thrown = true
        }
        assertTrue("expected IOException", thrown)
    }

    private class SilentFinishAtomicFile(file: File) : AtomicFile(file) {
        override fun finishWrite(stream: FileOutputStream) = Unit
    }
}
