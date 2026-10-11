package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RecentDictation(val id: String, val text: String, val timestamp: Long)

/** Ten completed dictations on this installation; no audio, notes or sync entries. */
class DictationHistory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("dictation_history", Context.MODE_PRIVATE)

    fun all(): List<RecentDictation> = synchronized(lock) { read() }

    fun recordTranscript(recognized: String?, finalText: String?, id: String) {
        if (recognized.isNullOrBlank() || finalText.isNullOrBlank()) return
        val text = NoteImageMarkers.markers(finalText).fold(finalText) { value, marker -> value.replace(marker, "") }
        record(text, id)
    }

    fun record(text: String, id: String = UUID.randomUUID().toString(), timestamp: Long = System.currentTimeMillis()) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val entries = read()
            if (entries.firstOrNull { it.id == id }?.text == clean) return
            val date = entries.firstOrNull { it.id == id }?.timestamp ?: timestamp
            write((listOf(RecentDictation(id, clean, date)) + entries.filterNot { it.id == id }).take(LIMIT))
        }
    }

    fun clear() = synchronized(lock) { write(emptyList()) }

    private fun read(): List<RecentDictation> {
        val raw = preferences.getString("entries", null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until minOf(array.length(), LIMIT)).map { index ->
            val entry = array.getJSONObject(index)
            RecentDictation(entry.getString("id"), entry.getString("text"), entry.getLong("timestamp"))
        }
    }

    private fun write(entries: List<RecentDictation>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("text", entry.text).put("timestamp", entry.timestamp))
        }
        check(preferences.edit().putString("entries", array.toString()).commit()) { "L’historique n’a pas pu être enregistré." }
    }

    companion object {
        const val LIMIT = 10
        private val lock = Any()
    }
}
