package com.kafkasl.phonewhisper

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTranscriptNoteStorageTest {
    private lateinit var context: Context

    @Before fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("note_capture", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `legacy note json remains readable and round trips folder metadata`() {
        context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).edit()
            .putString("legacy", "{\"id\":\"legacy\",\"title\":\"Ancienne\",\"text\":\"Texte\",\"updated\":7}")
            .commit()
        val storage = AndroidTranscriptNoteStorage(context)
        val legacy = storage.all().single()
        assertNull(legacy.folderId)
        assertEquals("Texte", legacy.text)

        val notes = TranscriptNotes(storage, now = { 8L }, newId = { "folder-id" })
        val folder = notes.createFolder("Projet")!!
        val filed = notes.chooseFolder("legacy", folder.id)!!
        val reopened = TranscriptNotes(AndroidTranscriptNoteStorage(context))

        assertEquals(folder, reopened.folders().single())
        assertEquals(folder.id, reopened.get("legacy")!!.folderId)
        assertTrue(filed.folderChoicePrompted)
    }

    @Test fun `a note referencing a missing folder is normalized to sans dossier`() {
        context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).edit()
            .putString("orphan", "{\"id\":\"orphan\",\"title\":\"Visible\",\"text\":\"Conservée\",\"updated\":9,\"folderId\":\"gone\"}")
            .commit()

        val notes = TranscriptNotes(AndroidTranscriptNoteStorage(context))
        val note = notes.get("orphan")!!

        assertNull(note.folderId)
        assertEquals("Conservée", note.text)
        assertEquals(listOf("orphan"), notes.all(null).map { it.id })
        assertNull(JSONObjectFolderProbe(context).folderIdFromPrefs())
    }

    @Test fun `deleting one version preserves images referenced by a second saved version`() {
        val image = image()
        val files = NoteImageStore(context)
        files.file(image.id).writeText("original")
        files.thumbnail(image.id).writeText("thumbnail")
        val storage = AndroidTranscriptNoteStorage(context)
        storage.put(TranscriptNote("first", "Original", image.marker, 1, images = listOf(image)))
        storage.put(TranscriptNote("second", "Version conservée", image.marker, 1, images = listOf(image)))

        storage.remove("first")
        assertTrue(files.file(image.id).isFile)
        assertTrue(files.thumbnail(image.id).isFile)
        storage.remove("second")
        assertFalse(files.file(image.id).exists())
    }

    @Test fun `image deletion respects persisted draft anchors and pending capture receipts`() {
        val image = image()
        val files = NoteImageStore(context)
        files.file(image.id).writeText("original")
        context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE).edit().putString("captures",
            "[{\"id\":\"${image.id}\",\"offset\":0,\"image\":${NoteImageJson.write(image)}}]").commit()
        assertFalse(files.deleteIfUnreferenced(image.id))
        assertTrue(files.file(image.id).isFile)

        context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("note_capture", Context.MODE_PRIVATE).edit().putString("pending",
            "{\"id\":\"${image.id}\"}").commit()
        assertFalse(files.deleteIfUnreferenced(image.id))
        assertTrue(files.file(image.id).isFile)

        context.getSharedPreferences("note_capture", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(files.deleteIfUnreferenced(image.id))
        assertFalse(files.file(image.id).exists())
    }

    @Test fun `corrupt reference metadata retains originals instead of assuming they are unused`() {
        val image = image()
        val files = NoteImageStore(context)
        files.file(image.id).writeText("original")
        context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).edit().putString("broken", "{invalid").commit()
        assertFalse(files.deleteIfUnreferenced(image.id))
        assertTrue(files.file(image.id).isFile)
    }

    @Test fun `clearing a draft preserves an original already owned by a saved note`() {
        val image = image()
        val files = NoteImageStore(context)
        files.file(image.id).writeText("original")
        AndroidTranscriptNoteStorage(context).put(TranscriptNote("saved", "Note", image.marker, 1, images = listOf(image)))
        context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE).edit().putString("captures",
            "[{\"id\":\"${image.id}\",\"offset\":0,\"image\":${NoteImageJson.write(image)}}]").putString("text", "Brouillon").commit()

        DictationDraftStore(context).clear()

        assertTrue(files.file(image.id).isFile)
        assertTrue(DictationDraftStore(context).captures().isEmpty())
        assertNull(DictationDraftStore(context).load())
    }

    private fun image() = NoteImage(java.util.UUID.randomUUID().toString(), 1, NoteImageKind.CAMERA, 1, 100, 100)

    /** Keeps the assertion above independent of JSONObject implementation details. */
    private class JSONObjectFolderProbe(private val context: Context) {
        fun folderIdFromPrefs(): String? =
            context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
                .getString("orphan", null)?.let { org.json.JSONObject(it).optString("folderId").takeIf(String::isNotBlank) }
    }
}
