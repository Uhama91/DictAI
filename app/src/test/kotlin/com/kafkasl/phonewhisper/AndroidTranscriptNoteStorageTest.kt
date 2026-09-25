package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingParticipant
import com.kafkasl.phonewhisper.meeting.MeetingTurn
import java.io.IOException
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test fun `unknown meeting payload remains byte exact through rename and folder changes`() {
        val raw = "  { \"schemaVersion\" : 9, \"sessionId\":\"future\", \"extra\": [1, 2] }  "
        context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).edit()
            .putString(
                "future",
                JSONObject()
                    .put("id", "future")
                    .put("title", "Titre de repli")
                    .put("text", "Texte de repli conservé")
                    .put("updated", 11L)
                    .put("meeting", raw)
                    .toString(),
            )
            .commit()

        val storage = AndroidTranscriptNoteStorage(context)
        val notes = TranscriptNotes(storage, now = { 12L }, newId = { "folder" })
        val note = notes.get("future")!!
        assertNull(note.meeting)
        assertEquals(raw, note.meetingRaw)
        assertEquals("Texte de repli conservé", note.text)

        notes.rename("future", "Titre modifié")
        val folder = notes.createFolder("À classer")!!
        notes.move("future", folder.id)
        val encoded = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
            .getString("future", null)!!

        assertEquals(raw, JSONObject(encoded).getString("meeting"))
        val reopened = AndroidTranscriptNoteStorage(context).all().single()
        assertEquals(raw, reopened.meetingRaw)
        assertEquals("Texte de repli conservé", reopened.text)
        assertEquals(folder.id, reopened.folderId)
        assertEquals("Titre modifié", reopened.title)
    }

    @Test fun `stored v1 meeting reopens from structure instead of stale fallback text`() {
        val speaker = MeetingParticipant("speaker-1", ordinal = 1, channel = 1, name = "Sophie")
        val ignored = MeetingParticipant("speaker-2", ordinal = 2, channel = 2, name = "Karim", ignored = true)
        val document = MeetingDocument(
            sessionId = "meeting-session",
            runId = "run-a",
            participants = listOf(speaker, ignored),
            turns = listOf(
                MeetingTurn("hidden", 1, 0, 1, "Ne pas réafficher.", ignored.id, attributionStable = true),
                MeetingTurn("visible", 2, 1, 2, "Le budget est voté.", speaker.id, attributionStable = true),
            ),
        )
        val storage = AndroidTranscriptNoteStorage(context)
        storage.put(
            TranscriptNote(
                id = document.sessionId,
                title = "Réunion",
                text = "Ancien texte avec Ne pas réafficher.",
                updatedAt = 13L,
                meeting = document,
            ),
        )

        val loaded = AndroidTranscriptNoteStorage(context).all().single()

        assertEquals(document, loaded.meeting)
        assertNull(loaded.meetingRaw)
        assertEquals("Sophie\nLe budget est voté.", loaded.text)
    }

    @Test fun `failed meeting commit restores the exact prior serialized note`() {
        val id = "session-1"
        val previous = JSONObject()
            .put("id", id)
            .put("title", "Ancien titre")
            .put("text", "Ancien texte")
            .put("updated", 4L)
            .put("meeting", "ancienne donnée opaque")
            .toString()
        val preferences = ControlledPreferences(mapOf(id to previous)).apply {
            nextCommitResults.addAll(listOf(false, true))
        }

        val failure = assertThrows(IOException::class.java) {
            storageUsing(preferences).put(meetingRawNote(id, "nouvelle donnée opaque"))
        }

        assertEquals("Échec de publication de la note Réunion", failure.message)
        assertEquals(previous, preferences.values[id])
        assertEquals(listOf(false, true), preferences.commitResults)
        assertEquals(0, preferences.applyCalls)
    }

    @Test fun `failed first meeting commit removes the newly written note`() {
        val id = "first-session"
        val preferences = ControlledPreferences().apply {
            nextCommitResults.addAll(listOf(false, true))
        }

        assertThrows(IOException::class.java) {
            storageUsing(preferences).put(meetingRawNote(id, "donnée opaque"))
        }

        assertNull(preferences.values[id])
        assertEquals(listOf(false, true), preferences.commitResults)
        assertEquals(0, preferences.applyCalls)
    }

    @Test fun `failed rollback is attached to the original meeting commit failure`() {
        val id = "rollback-session"
        val previous = JSONObject()
            .put("id", id)
            .put("title", "Ancien")
            .put("text", "Texte")
            .put("updated", 5L)
            .put("meeting", "ancienne donnée opaque")
            .toString()
        val preferences = ControlledPreferences(mapOf(id to previous)).apply {
            nextCommitResults.addAll(listOf(false, false))
        }

        val failure = assertThrows(IOException::class.java) {
            storageUsing(preferences).put(meetingRawNote(id, "nouvelle donnée opaque"))
        }

        assertEquals("Échec de publication de la note Réunion", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertEquals("Restauration de l’ancienne note impossible", failure.suppressed.single().message)
        assertEquals(listOf(false, false), preferences.commitResults)
    }

    @Test fun `flat note writes continue to use apply instead of commit`() {
        val preferences = ControlledPreferences()

        storageUsing(preferences).put(
            TranscriptNote(id = "flat", title = "Dictée", text = "Texte", updatedAt = 6L),
        )

        assertEquals(1, preferences.applyCalls)
        assertTrue(preferences.commitResults.isEmpty())
        assertEquals("flat", JSONObject(preferences.values.getValue("flat")).getString("id"))
    }

    private fun storageUsing(preferences: ControlledPreferences): AndroidTranscriptNoteStorage =
        AndroidTranscriptNoteStorage(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "transcript_notes") preferences.sharedPreferences
                else super.getSharedPreferences(name, mode)
        })

    private fun meetingRawNote(id: String, raw: String) = TranscriptNote(
        id = id,
        title = "Réunion",
        text = "Texte de repli",
        updatedAt = 7L,
        meetingRaw = raw,
    )

    /** Models SharedPreferences updating its in-memory map even when commit reports failure. */
    private class ControlledPreferences(initial: Map<String, String> = emptyMap()) {
        val values = initial.toMutableMap()
        val nextCommitResults = mutableListOf<Boolean>()
        val commitResults = mutableListOf<Boolean>()
        var applyCalls = 0
            private set

        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args!![0] as String)
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, String?>()
            var clear = false
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        changes[args!![0] as String] = args[1] as String?
                        proxy
                    }
                    "remove" -> {
                        changes[args!![0] as String] = null
                        proxy
                    }
                    "clear" -> {
                        clear = true
                        proxy
                    }
                    "commit", "apply" -> {
                        if (clear) values.clear()
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        if (method.name == "apply") {
                            applyCalls += 1
                            null
                        } else {
                            (nextCommitResults.removeFirstOrNull() ?: true).also(commitResults::add)
                        }
                    }
                    else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    /** Keeps the assertion above independent of JSONObject implementation details. */
    private class JSONObjectFolderProbe(private val context: Context) {
        fun folderIdFromPrefs(): String? =
            context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
                .getString("orphan", null)?.let { org.json.JSONObject(it).optString("folderId").takeIf(String::isNotBlank) }
    }
}
