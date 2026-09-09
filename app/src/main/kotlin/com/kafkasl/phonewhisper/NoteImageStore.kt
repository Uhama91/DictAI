package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object NoteImageJson {
    fun write(image: NoteImage) = JSONObject().put("id", image.id).put("number", image.number)
        .put("kind", image.kind.name).put("capturedAt", image.capturedAt)
        .put("width", image.width).put("height", image.height)
    fun read(json: JSONObject) = NoteImage(json.getString("id"), json.getInt("number"),
        NoteImageKind.valueOf(json.getString("kind")), json.getLong("capturedAt"), json.getInt("width"), json.getInt("height"))
    fun writeList(images: List<NoteImage>) = JSONArray().apply { images.forEach { put(write(it)) } }
    fun readList(array: JSONArray?): List<NoteImage> = (0 until (array?.length() ?: 0)).mapNotNull {
        runCatching { read(array!!.getJSONObject(it)) }.getOrNull()
    }.distinctBy { it.id }.distinctBy { it.number }.take(NoteImage.MAX_IMAGES)
}

internal data class PendingNoteCapture(val id: String, val noteId: String, val number: Int,
    val kind: NoteImageKind, val capturedAt: Long, val resumeListening: Boolean,
    val image: NoteImage? = null, val error: String? = null, val clipboardOnly: Boolean = false) {
    val complete get() = image != null || error != null
}

/** Images live in private durable storage; only a single camera output is temporarily writable. */
internal class NoteImageStore(context: Context) {
    private val root = File(context.filesDir, "note_images").apply { mkdirs() }
    private val cameraRoot = File(context.cacheDir, "note_camera").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("note_capture", Context.MODE_PRIVATE)
    fun file(id: String): File { require(NoteImage.validId(id)); return File(root, "$id.jpg") }
    fun thumbnail(id: String): File { require(NoteImage.validId(id)); return File(root, "$id.thumb.jpg") }
    fun cameraFile(id: String): File { require(NoteImage.validId(id)); return File(cameraRoot, "$id.jpg") }
    fun pending(): PendingNoteCapture? = runCatching {
        val json = JSONObject(prefs.getString("pending", null) ?: return null)
        PendingNoteCapture(json.getString("id"), json.getString("noteId"), json.getInt("number"),
            NoteImageKind.valueOf(json.getString("kind")), json.getLong("time"), json.optBoolean("resume"),
            json.optJSONObject("image")?.let(NoteImageJson::read), json.optString("error").takeIf { it.isNotEmpty() }, json.optBoolean("clipboardOnly"))
            .also { require(NoteImage.validId(it.id)) }
    }.getOrNull()

    fun begin(note: TranscriptNote, kind: NoteImageKind, resume: Boolean): PendingNoteCapture {
        check(pending() == null && note.images.size < NoteImage.MAX_IMAGES)
        return PendingNoteCapture(UUID.randomUUID().toString(), note.id, NoteImage.nextNumber(note.images, note.text),
            kind, System.currentTimeMillis(), resume).also(::writePending)
    }
    /** Independent of notes: each gesture prepares one clipboard image, without a context marker. */
    fun beginClipboard(kind: NoteImageKind, resume: Boolean): PendingNoteCapture {
        check(pending() == null)
        return PendingNoteCapture(UUID.randomUUID().toString(), "", 1, kind,
            System.currentTimeMillis(), resume, clipboardOnly = true).also(::writePending)
    }
    private fun writePending(pending: PendingNoteCapture) {
        val json = JSONObject().put("id", pending.id).put("noteId", pending.noteId).put("number", pending.number)
            .put("kind", pending.kind.name).put("time", pending.capturedAt).put("resume", pending.resumeListening).put("clipboardOnly", pending.clipboardOnly)
        pending.image?.let { json.put("image", NoteImageJson.write(it)) }
        pending.error?.let { json.put("error", it) }
        check(prefs.edit().putString("pending", json.toString()).commit()) { "Capture non enregistrée" }
    }
    fun fail(id: String, message: String) = synchronized(completionLock) {
        pending()?.takeIf { it.id == id && !it.complete }?.let {
            delete(id)
            runCatching { writePending(it.copy(error = message)) }
                .onFailure { android.util.Log.w("DictAI", "event=note_capture persistence=failed") }
        }
    }
    fun clearPending(id: String) = synchronized(completionLock) {
        if (pending()?.id == id) {
            prefs.edit().remove("pending").apply()
            cameraFile(id).delete()
        }
    }
    fun delete(id: String) { file(id).delete(); thumbnail(id).delete(); cameraFile(id).delete() }

    /** Called on a worker, never the audio or UI thread. ImageDecoder also applies EXIF orientation. */
    fun importCamera(id: String) {
        val input = cameraFile(id)
        require(input.length() in 1..40L * 1024 * 1024) { "Photo vide ou trop volumineuse" }
        val bitmap = decode(input, 2048)
        try { store(id, bitmap) } finally { bitmap.recycle(); input.delete() }
    }
    fun store(id: String, bitmap: Bitmap) {
        val capture = pending()?.takeIf { it.id == id && !it.complete } ?: return
        val ratio = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (ratio < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        val temporary = File(root, "$id.tmp")
        try {
            temporary.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            check(temporary.length() in 1..8L * 1024 * 1024)
            check(temporary.renameTo(file(id)))
            if (!capture.clipboardOnly) {
                val smallRatio = minOf(1f, 160f / maxOf(scaled.width, scaled.height))
                val thumb = Bitmap.createScaledBitmap(scaled, (scaled.width * smallRatio).toInt().coerceAtLeast(1),
                    (scaled.height * smallRatio).toInt().coerceAtLeast(1), true)
                try { thumbnail(id).outputStream().use { check(thumb.compress(Bitmap.CompressFormat.JPEG, 82, it)) } }
                finally { if (thumb !== scaled) thumb.recycle() }
            }
            synchronized(completionLock) {
                if (pending()?.let { it.id == id && !it.complete } == true)
                    writePending(capture.copy(image = NoteImage(id, capture.number, capture.kind, capture.capturedAt, scaled.width, scaled.height)))
                else delete(id)
            }
        } finally {
            temporary.delete()
            if (scaled !== bitmap) scaled.recycle()
        }
    }
    companion object {
        private val completionLock = Any()
        fun decode(file: File, maxEdge: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            require(info.size.width > 0 && info.size.height > 0 && info.size.width.toLong() * info.size.height <= 100_000_000)
            val ratio = minOf(1f, maxEdge.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}
