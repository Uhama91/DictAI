package com.kafkasl.phonewhisper

import android.content.Context

/** Private local text snapshot; Android serializes apply writes in their submission order. */
internal class DictationDraftStore(context: Context) {
    private val imageStore = NoteImageStore(context)
    private val prefs = context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE)
    var noteId: String?
        get() = prefs.getString("note_id", null)
        set(value) { prefs.edit().putString("note_id", value).apply() }
    fun load(): String? = prefs.getString("text", null)
    fun captures(): List<DraftImageCapture> = runCatching {
        val array = org.json.JSONArray(prefs.getString("captures", "[]"))
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            val id = item.getString("id"); require(NoteImage.validId(id))
            DraftImageCapture(id, item.getInt("offset").coerceAtLeast(0), item.optJSONObject("image")?.let(NoteImageJson::read))
        }.getOrNull() }.distinctBy { it.id }.take(NoteImage.MAX_IMAGES)
    }.getOrDefault(emptyList())
    private fun encode(captures: List<DraftImageCapture>) = org.json.JSONArray().apply {
        captures.forEach { capture -> put(org.json.JSONObject().put("id", capture.id).put("offset", capture.offset).apply {
            capture.image?.let { put("image", NoteImageJson.write(it)) }
        }) }
    }.toString()
    fun reserveCapture(id: String, existingImages: Int): Boolean {
        val current = captures()
        if (current.size + existingImages >= NoteImage.MAX_IMAGES) return false
        prefs.edit().putString("captures", encode(current + DraftImageCapture(id, load().orEmpty().length))).apply()
        return true
    }
    fun completeCapture(image: NoteImage) {
        prefs.edit().putString("captures", encode(captures().map { if (it.id == image.id) it.copy(image = image) else it })).apply()
    }
    /** Detach after committing a note: the private JPEG now belongs to that note. */
    fun detachCaptures(ids: Set<String>) { prefs.edit().putString("captures", encode(captures().filter { it.id !in ids })).apply() }
    fun removeCapture(id: String) { detachCaptures(setOf(id)); imageStore.delete(id) }
    fun save(text: String) {
        val previous = load().orEmpty()
        if (previous != text || load() == null) prefs.edit().putString("text", text)
            .putString("captures", encode(DraftImageContext.move(captures(), previous, text))).apply()
    }
    fun clear() {
        captures().forEach { imageStore.delete(it.id) }
        prefs.edit().clear().apply()
    }
}
