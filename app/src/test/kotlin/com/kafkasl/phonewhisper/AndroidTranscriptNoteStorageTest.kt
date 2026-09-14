package com.kafkasl.phonewhisper

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Keeps the assertion above independent of JSONObject implementation details. */
    private class JSONObjectFolderProbe(private val context: Context) {
        fun folderIdFromPrefs(): String? =
            context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
                .getString("orphan", null)?.let { org.json.JSONObject(it).optString("folderId").takeIf(String::isNotBlank) }
    }
}
