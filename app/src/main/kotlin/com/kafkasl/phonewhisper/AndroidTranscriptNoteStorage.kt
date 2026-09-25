package com.kafkasl.phonewhisper

import android.content.Context
import com.kafkasl.phonewhisper.meeting.MeetingDocumentJson
import com.kafkasl.phonewhisper.meeting.MeetingDocumentRead
import com.kafkasl.phonewhisper.meeting.MeetingProjection
import org.json.JSONObject
import java.io.IOException

internal class AndroidTranscriptNoteStorage(context: Context) : TranscriptNoteStorage {
    private val imageStore = NoteImageStore(context)
    private val prefs = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
    private val folderPrefs = context.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE)
    override fun all(): List<TranscriptNote> = prefs.all.values.mapNotNull { value ->
        runCatching {
            val item = JSONObject(value as String)
            val images = NoteImageJson.readList(item.optJSONArray("images"))
            val rawMeeting = item.meetingPayload()
            val meetingRead = MeetingDocumentJson.decode(rawMeeting)
            val meeting = (meetingRead as? MeetingDocumentRead.Ready)?.document
            val opaqueMeeting = when (meetingRead) {
                is MeetingDocumentRead.Invalid, is MeetingDocumentRead.Unsupported -> rawMeeting
                else -> null
            }
            TranscriptNote(
                id = item.getString("id"),
                title = item.getString("title"),
                text = meeting?.let { document ->
                    MeetingProjection.text(document, images.mapTo(mutableSetOf()) { it.number })
                } ?: item.getString("text"),
                updatedAt = item.getLong("updated"),
                renamed = item.optBoolean("renamed"),
                images = images,
                folderId = item.optString("folderId").takeIf { it.isNotBlank() },
                folderChoicePrompted = item.optBoolean("folderChoicePrompted", true),
                meeting = meeting,
                meetingRaw = opaqueMeeting,
            )
        }.getOrNull()
    }
    override fun put(note: TranscriptNote) {
        val item = JSONObject().put("id", note.id).put("title", note.title).put("text", note.text)
            .put("updated", note.updatedAt).put("renamed", note.renamed).put("images", NoteImageJson.writeList(note.images))
            .apply {
                note.folderId?.let { put("folderId", it) }
                put("folderChoicePrompted", note.folderChoicePrompted)
                when {
                    note.meeting != null -> put("meeting", MeetingDocumentJson.encode(note.meeting))
                    note.meetingRaw != null -> put("meeting", note.meetingRaw)
                }
            }
        val serialized = item.toString()
        val editor = prefs.edit().putString(note.id, serialized)
        if (note.meeting != null || note.meetingRaw != null) {
            val previous = prefs.all[note.id] as? String
            if (!editor.commit()) {
                val rollback = prefs.edit()
                if (previous == null) rollback.remove(note.id) else rollback.putString(note.id, previous)
                val failure = IOException("Échec de publication de la note Réunion")
                if (!rollback.commit()) failure.addSuppressed(IOException("Restauration de l’ancienne note impossible"))
                throw failure
            }
        } else {
            editor.apply()
        }
    }

    override fun allFolders(): List<NoteFolder> = folderPrefs.all.values.mapNotNull { value ->
        runCatching {
            val item = JSONObject(value as String)
            val created = item.getLong("created")
            NoteFolder(item.getString("id"), item.getString("name"), created, item.optLong("updated", created))
        }.getOrNull()
    }

    override fun putFolder(folder: NoteFolder) {
        val item = JSONObject().put("id", folder.id).put("name", folder.name)
            .put("created", folder.createdAt).put("updated", folder.updatedAt)
        folderPrefs.edit().putString(folder.id, item.toString()).apply()
    }

    override fun removeFolder(id: String) {
        folderPrefs.edit().remove(id).apply()
    }

    override fun remove(id: String) {
        val old = all().firstOrNull { it.id == id }
        prefs.edit().remove(id).apply()
        old?.images?.forEach { imageStore.delete(it.id) }
    }

    private fun JSONObject.meetingPayload(): String? {
        if (!has("meeting")) return null
        return when (val value = opt("meeting")) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> value.toString()
        }
    }
}
