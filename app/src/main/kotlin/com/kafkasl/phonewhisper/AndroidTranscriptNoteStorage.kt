package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONObject

internal class AndroidTranscriptNoteStorage(context: Context) : TranscriptNoteStorage {
    private val imageStore = NoteImageStore(context)
    private val prefs = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
    private val folderPrefs = context.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE)
    override fun all(): List<TranscriptNote> = prefs.all.values.mapNotNull { value ->
        runCatching {
            val item = JSONObject(value as String)
            TranscriptNote(
                item.getString("id"), item.getString("title"), item.getString("text"), item.getLong("updated"),
                item.optBoolean("renamed"), NoteImageJson.readList(item.optJSONArray("images")),
                item.optString("folderId").takeIf { it.isNotBlank() }, item.optBoolean("folderChoicePrompted", true),
            )
        }.getOrNull()
    }
    override fun put(note: TranscriptNote) {
        val item = JSONObject().put("id", note.id).put("title", note.title).put("text", note.text)
            .put("updated", note.updatedAt).put("renamed", note.renamed).put("images", NoteImageJson.writeList(note.images))
            .apply {
                note.folderId?.let { put("folderId", it) }
                put("folderChoicePrompted", note.folderChoicePrompted)
            }
        prefs.edit().putString(note.id, item.toString()).apply()
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
}
