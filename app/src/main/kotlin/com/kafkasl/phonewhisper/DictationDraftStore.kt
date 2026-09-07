package com.kafkasl.phonewhisper

import android.content.Context

/** Private local text snapshot; Android serializes apply writes in their submission order. */
internal class DictationDraftStore(context: Context) {
    private val prefs = context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE)
    fun load(): String? = prefs.getString("text", null)
    fun save(text: String) {
        if (load() != text) prefs.edit().putString("text", text).apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}
