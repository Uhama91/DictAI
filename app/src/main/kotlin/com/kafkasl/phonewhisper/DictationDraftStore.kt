package com.kafkasl.phonewhisper

import android.content.Context

/** Private local text snapshot; Android serializes apply writes in their submission order. */
internal class DictationDraftStore(context: Context) {
    private val imageStore = NoteImageStore(context)
    private val prefs = context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE)
    var purpose: DictationPurpose
        get() = DictationPurpose.restore(prefs.getString("purpose", null), noteId)
        set(value) { prefs.edit().putString("purpose", value.name).apply() }
    var noteId: String?
        get() = prefs.getString("note_id", null)
        set(value) { prefs.edit().putString("note_id", value).apply() }
    fun load(): String? = prefs.getString("text", null)
    fun captures(): List<DraftImageCapture> = runCatching {
        val array = org.json.JSONArray(prefs.getString("captures", "[]"))
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            val id = item.getString("id"); require(NoteImage.validId(id))
            DraftImageCapture(
                id = id,
                offset = item.getInt("offset").coerceAtLeast(0),
                image = item.optJSONObject("image")?.let(NoteImageJson::read),
                groupId = item.optString("groupId").takeIf { it.isNotEmpty() },
                visible = item.optBoolean("visible", true),
                orderAtOffset = item.optInt("orderAtOffset", 0).coerceAtLeast(0),
            )
        }.getOrNull() }.filter { it.visible }.distinctBy { it.id }.take(NoteImage.MAX_IMAGES)
    }.getOrDefault(emptyList())
    private fun encode(captures: List<DraftImageCapture>) = org.json.JSONArray().apply {
        captures.forEach { capture -> put(org.json.JSONObject().put("id", capture.id).put("offset", capture.offset)
            .put("visible", capture.visible).put("orderAtOffset", capture.orderAtOffset).apply {
            capture.groupId?.let { put("groupId", it) }
            capture.image?.let { put("image", NoteImageJson.write(it)) }
        }) }
    }.toString()
    fun reserveCapture(id: String, existingImages: Int, offset: Int? = null, orderAtOffset: Int = 0): Boolean {
        val current = captures()
        val usedSlots = current.count { it.image != null } + current.count { it.image == null }
        if (usedSlots + existingImages >= NoteImage.MAX_IMAGES) return false
        return prefs.edit().putString("captures", encode(current + DraftImageCapture(
            id = id,
            offset = (offset ?: load().orEmpty().length).coerceAtLeast(0),
            groupId = id,
            orderAtOffset = orderAtOffset.coerceAtLeast(0),
        ))).commit()
    }
    fun completeCapture(image: NoteImage) {
        prefs.edit().putString("captures", encode(captures().map { if (it.id == image.id) it.copy(image = image) else it })).apply()
    }
    /** Replace a session reservation with every accepted JPEG while preserving one block anchor. */
    fun completeBatch(sessionId: String, images: List<NoteImage>, existingImages: Int = 0): Boolean {
        val current = captures()
        val reservation = current.firstOrNull { it.id == sessionId }
        if (reservation == null) {
            val uniqueImages = images.distinctBy { it.id }
            val alreadyDelivered = uniqueImages.isNotEmpty() && uniqueImages.size == images.size &&
                uniqueImages.all { image -> current.any { it.id == image.id && it.image?.id == image.id } }
            // SharedPreferences may update its in-memory map even when commit() reports a
            // disk failure. Re-commit this idempotent state before acknowledging the capture.
            return alreadyDelivered && prefs.edit().putString("captures", encode(current)).commit()
        }
        val others = current.filterNot { it.id == sessionId }
        val uniqueImages = images.distinctBy { it.id }
        val room = NoteImage.MAX_IMAGES - existingImages.coerceAtLeast(0) -
            others.count { it.image != null } - others.count { it.image == null }
        if (uniqueImages.isEmpty() || uniqueImages.size > room) return false
        val accepted = uniqueImages
        val records = accepted.map { image ->
            DraftImageCapture(image.id, reservation.offset, image, sessionId, reservation.visible, reservation.orderAtOffset)
        }
        // The capture coordinator clears its pending batch only after this durable replacement.
        // A failed commit may still update the in-memory preferences, so retry by UUID instead
        // of assuming the reservation remains present.
        return prefs.edit().putString("captures", encode(others + records)).commit()
    }

    /** Keep removed draft images out of rendering/serialization without deleting sources used by undo. */
    fun syncVisibleCaptures(visibleIds: Set<String>) {
        val retained = captures().filter { capture -> capture.image == null || capture.id in visibleIds }
        prefs.edit().putString("captures", encode(retained)).commit()
    }

    /** Commit the exact editor anchors before focus can return to another application. */
    fun syncProjection(blocks: List<TranscriptImageBlock>) {
        val synchronized = DraftImageContext.synchronize(captures(), blocks)
        prefs.edit().putString("captures", encode(synchronized)).apply()
    }

    /** Drop draft metadata after its note has been saved; image files remain available to undo/history. */
    fun forgetCaptures(ids: Set<String>) {
        if (ids.isEmpty()) return
        prefs.edit().putString("captures", encode(captures().filterNot { it.id in ids })).apply()
    }
    /** Detach after committing a note: the private JPEG now belongs to that note. */
    fun detachCaptures(ids: Set<String>) { prefs.edit().putString("captures", encode(captures().filter { it.id !in ids })).apply() }
    /** Removing an editor object drops its metadata; keeping the JPEG allows a later undo path. */
    fun removeCapture(id: String) { detachCaptures(setOf(id)) }
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
