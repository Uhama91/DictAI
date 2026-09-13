package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONObject

internal class AndroidTranscriptNoteStorage(context: Context) : TranscriptNoteStorage {
    private val imageStore = NoteImageStore(context)
    private val prefs = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
    override fun all(): List<TranscriptNote> = prefs.all.values.mapNotNull { value ->
        runCatching {
            val item = JSONObject(value as String)
            TranscriptNote(item.getString("id"), item.getString("title"), item.getString("text"), item.getLong("updated"), item.optBoolean("renamed"), NoteImageJson.readList(item.optJSONArray("images")))
        }.getOrNull()
    }
    override fun put(note: TranscriptNote) {
        val item = JSONObject().put("id", note.id).put("title", note.title).put("text", note.text)
            .put("updated", note.updatedAt).put("renamed", note.renamed).put("images", NoteImageJson.writeList(note.images))
        prefs.edit().putString(note.id, item.toString()).apply()
    }
    override fun remove(id: String) {
        val old = all().firstOrNull { it.id == id }
        prefs.edit().remove(id).apply()
        old?.images?.forEach { imageStore.delete(it.id) }
    }
}
