package com.kafkasl.phonewhisper

import com.kafkasl.phonewhisper.meeting.MeetingImageAnchor
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
    }.distinctBy { it.id }.take(NoteImage.MAX_IMAGES)
}

internal data class PendingNoteCapture(val id: String, val noteId: String, val number: Int,
    val kind: NoteImageKind, val capturedAt: Long, val resumeListening: Boolean,
    val image: NoteImage? = null, val error: String? = null, val clipboardOnly: Boolean = false,
    val batch: Boolean = false, val images: List<NoteImage> = emptyList(), val accepted: Boolean = false,
    val maxImages: Int = NoteImage.MAX_IMAGES, val message: String? = null,
    val meetingAnchor: MeetingImageAnchor? = null, val meetingAnchorInvalid: Boolean = false,
    val meetingImageNumbersResolved: Boolean = false) {
    val complete get() = error != null || if (batch) accepted else image != null
    val allImages get() = if (batch) images else listOfNotNull(image)
    val meetingCapture get() = meetingAnchor != null || meetingAnchorInvalid
}

internal interface PendingCapturePersistence {
    fun read(): String?
    fun commit(raw: String?): Boolean
}

private class SharedPreferencesPendingCapturePersistence(
    private val preferences: SharedPreferences,
) : PendingCapturePersistence {
    override fun read(): String? = preferences.getString("pending", null)

    override fun commit(raw: String?): Boolean {
        val edit = preferences.edit()
        if (raw == null) edit.remove("pending") else edit.putString("pending", raw)
        return edit.commit()
    }
}

/** Images live in private durable storage; only a single camera output is temporarily writable. */
internal class NoteImageStore(
    context: Context,
    private val pendingPersistence: PendingCapturePersistence = SharedPreferencesPendingCapturePersistence(
        context.getSharedPreferences("note_capture", Context.MODE_PRIVATE),
    ),
) {
    private val appContext = context.applicationContext
    private val root = File(context.filesDir, "note_images").apply { mkdirs() }
    private val cameraRoot = File(context.cacheDir, "note_camera").apply { mkdirs() }
    private var hasPendingFallback = false
    private var pendingFallback: String? = null
    fun file(id: String): File { require(NoteImage.validId(id)); return File(root, "$id.jpg") }
    fun thumbnail(id: String): File { require(NoteImage.validId(id)); return File(root, "$id.thumb.jpg") }
    fun cameraFile(id: String): File { require(NoteImage.validId(id)); return File(cameraRoot, "$id.jpg") }
    fun pending(): PendingNoteCapture? = runCatching {
        val json = JSONObject(readPendingRaw() ?: return null)
        val noteId = json.getString("noteId")
        val hasMeetingMetadata = json.has("meetingCapture") || json.has("meetingAnchor") ||
            json.has("meetingImageNumbersResolved")
        val parsedAnchor = json.optJSONObject("meetingAnchor")?.let(::readMeetingAnchor)
        val parsedNumbersResolved = when {
            !json.has("meetingImageNumbersResolved") -> false
            json.opt("meetingImageNumbersResolved") is Boolean -> json.optBoolean("meetingImageNumbersResolved")
            else -> null
        }
        val invalidMeetingAnchor = hasMeetingMetadata &&
            (parsedAnchor == null || parsedAnchor.sessionId != noteId || parsedNumbersResolved == null)
        PendingNoteCapture(json.getString("id"), noteId, json.getInt("number"),
            NoteImageKind.valueOf(json.getString("kind")), json.getLong("time"), json.optBoolean("resume"),
            json.optJSONObject("image")?.let(NoteImageJson::read), json.optString("error").takeIf { it.isNotEmpty() }, json.optBoolean("clipboardOnly"),
            json.optBoolean("batch"), readPendingImages(json.optJSONArray("images")), json.optBoolean("accepted"),
            json.optInt("maxImages", NoteImage.MAX_IMAGES).coerceIn(1, NoteImage.MAX_IMAGES),
            json.optString("message").takeIf { it.isNotEmpty() },
            meetingAnchor = parsedAnchor.takeUnless { invalidMeetingAnchor },
            meetingAnchorInvalid = invalidMeetingAnchor,
            meetingImageNumbersResolved = parsedNumbersResolved == true)
            .also {
                require(NoteImage.validId(it.id))
                require(it.allImages.map { image -> image.id }.distinct().size == it.allImages.size)
                require(it.allImages.map { image -> image.number }.distinct().size == it.allImages.size)
            }
    }.getOrNull()

    private fun readPendingImages(array: JSONArray?): List<NoteImage> = (0 until (array?.length() ?: 0))
        .mapNotNull { index -> runCatching { NoteImageJson.read(array!!.getJSONObject(index)) }.getOrNull() }
        .distinctBy { it.id }.take(NoteImage.MAX_IMAGES)

    private fun readMeetingAnchor(json: JSONObject): MeetingImageAnchor? = runCatching {
        val sessionId = json.opt("sessionId") as? String ?: error("Invalid meeting image session")
        val turnId = json.opt("turnId") as? String ?: error("Invalid meeting image turn")
        val rawOffset = json.get("offsetUtf16") as? Number ?: error("Invalid meeting image offset")
        val offset = rawOffset.toLong()
        require(offset in 0..Int.MAX_VALUE.toLong() && rawOffset.toDouble() == offset.toDouble())
        MeetingImageAnchor(sessionId, turnId, offset.toInt())
    }.getOrNull()

    fun begin(note: TranscriptNote, kind: NoteImageKind, resume: Boolean): PendingNoteCapture {
        check(pending() == null && note.images.size < NoteImage.MAX_IMAGES)
        return PendingNoteCapture(UUID.randomUUID().toString(), note.id, NoteImage.nextNumber(note.images, note.text),
            kind, System.currentTimeMillis(), resume).also(::writePending)
    }

    /** Start a private staging batch. Its session id remains separate from each image id. */
    fun beginBatch(kind: NoteImageKind, resume: Boolean, number: Int, maxImages: Int): PendingNoteCapture = synchronized(completionLock) {
        check(pending() == null)
        require(number > 0)
        require(maxImages in 1..NoteImage.MAX_IMAGES)
        PendingNoteCapture(UUID.randomUUID().toString(), "", number, kind,
            System.currentTimeMillis(), resume, clipboardOnly = true, batch = true, maxImages = maxImages)
            .also(::writePending)
    }

    /** Start a meeting batch whose durable route is the captured session, never the current app mode. */
    fun beginMeetingBatch(
        anchor: MeetingImageAnchor,
        kind: NoteImageKind,
        resume: Boolean,
        number: Int = 1,
        maxImages: Int = NoteImage.MAX_IMAGES,
    ): PendingNoteCapture = synchronized(completionLock) {
        check(pending() == null)
        require(number > 0)
        require(maxImages in 1..NoteImage.MAX_IMAGES)
        PendingNoteCapture(
            id = UUID.randomUUID().toString(),
            noteId = anchor.sessionId,
            number = number,
            kind = kind,
            capturedAt = System.currentTimeMillis(),
            resumeListening = resume,
            clipboardOnly = true,
            batch = true,
            maxImages = maxImages,
            meetingAnchor = anchor,
        ).also(::writePending)
    }

    fun acceptBatch(id: String): PendingNoteCapture = synchronized(completionLock) {
        val capture = pending()?.takeIf { it.id == id && it.batch && !it.accepted && !it.meetingAnchorInvalid }
            ?: error("Série de captures indisponible")
        require(capture.images.isNotEmpty()) { "Ajoutez au moins une image avant de valider" }
        capture.copy(accepted = true, message = null).also(::writePending)
    }

    /** Durably records stable marker numbers before a meeting document is mutated. */
    fun resolveMeetingBatchImages(id: String, images: List<NoteImage>): PendingNoteCapture =
        synchronized(completionLock) {
            val capture = pending()?.takeIf {
                it.id == id && it.batch && it.accepted && !it.meetingAnchorInvalid && it.meetingAnchor != null
            } ?: error("Série de captures Réunion indisponible")
            require(images.map { it.id } == capture.images.map { it.id }) {
                "La résolution doit conserver les images et leur ordre"
            }
            require(images.map { it.number }.distinct().size == images.size) {
                "Les numéros résolus doivent être uniques"
            }
            require(images.all { it.number in 1..999_999 } && images.size <= capture.maxImages) {
                "La résolution dépasse les limites de la série"
            }
            require(images.zip(capture.images).all { (resolved, original) ->
                resolved.kind == original.kind && resolved.capturedAt == original.capturedAt &&
                    resolved.width == original.width && resolved.height == original.height
            }) { "La résolution ne peut modifier que les numéros d’image" }
            if (capture.meetingImageNumbersResolved) return@synchronized capture
            capture.copy(
                images = images.toList(),
                meetingImageNumbersResolved = true,
                message = null,
            ).also(::writePending)
        }

    fun cancelBatch(id: String) = synchronized(completionLock) {
        val capture = pending()?.takeIf { it.id == id && it.batch } ?: return@synchronized
        if (capture.meetingAnchorInvalid) {
            clearPending(id)
            return@synchronized
        }
        check(!capture.accepted) { "Une série validée appartient désormais au brouillon" }
        writePending(capture.copy(images = emptyList(), accepted = false, error = "Capture annulée", message = null))
        capture.images.forEach { delete(it.id) }
        cameraFile(id).delete()
    }

    /** Clear a recoverable per-image error without changing the batch id or its saved images. */
    fun retryBatch(id: String): PendingNoteCapture? = synchronized(completionLock) {
        val capture = pending()?.takeIf { it.id == id && it.batch && !it.accepted && !it.meetingAnchorInvalid } ?: return@synchronized null
        capture.copy(error = null, message = null).also(::writePending)
    }

    fun removeBatchImage(id: String, imageId: String) = synchronized(completionLock) {
        val capture = pending()?.takeIf { it.id == id && it.batch } ?: return@synchronized
        check(!capture.meetingAnchorInvalid) { "Ancre Réunion invalide; annulez la réservation pour la supprimer" }
        check(!capture.accepted) { "Une série validée ne peut plus être modifiée" }
        if (capture.images.none { it.id == imageId }) return@synchronized
        writePending(capture.copy(images = capture.images.filterNot { it.id == imageId }))
        delete(imageId)
    }
    /** Independent of notes: each gesture prepares one clipboard image, without a context marker. */
    fun beginClipboard(kind: NoteImageKind, resume: Boolean, number: Int = 1): PendingNoteCapture {
        check(pending() == null)
        return PendingNoteCapture(UUID.randomUUID().toString(), "", number, kind,
            System.currentTimeMillis(), resume, clipboardOnly = true).also(::writePending)
    }
    private fun writePending(pending: PendingNoteCapture) {
        val json = JSONObject().put("id", pending.id).put("noteId", pending.noteId).put("number", pending.number)
            .put("kind", pending.kind.name).put("time", pending.capturedAt).put("resume", pending.resumeListening).put("clipboardOnly", pending.clipboardOnly)
            .put("batch", pending.batch).put("accepted", pending.accepted).put("maxImages", pending.maxImages)
        check(!pending.meetingAnchorInvalid) { "Ancre Réunion invalide; la réservation ne peut pas être réécrite" }
        require(!pending.meetingImageNumbersResolved || pending.meetingAnchor != null) {
            "La résolution des numéros nécessite une ancre Réunion valide"
        }
        pending.meetingAnchor?.let { anchor ->
            require(pending.noteId == anchor.sessionId)
            json.put("meetingCapture", true)
                .put("meetingAnchor", JSONObject()
                    .put("sessionId", anchor.sessionId)
                    .put("turnId", anchor.turnId)
                    .put("offsetUtf16", anchor.offsetUtf16))
            json.put("meetingImageNumbersResolved", pending.meetingImageNumbersResolved)
        }
        if (pending.batch) json.put("images", NoteImageJson.writeList(pending.images))
        pending.image?.let { json.put("image", NoteImageJson.write(it)) }
        pending.error?.let { json.put("error", it) }
        pending.message?.let { json.put("message", it) }
        commitPending(json.toString(), "Capture non enregistrée")
    }

    private fun readPendingRaw(): String? =
        if (hasPendingFallback) pendingFallback else pendingPersistence.read()

    /** SharedPreferences.commit may mutate its in-memory map before reporting a disk failure. */
    private fun commitPending(value: String?, failureMessage: String) {
        val previous = readPendingRaw()
        val committed = runCatching { pendingPersistence.commit(value) }.getOrDefault(false)
        if (committed) {
            hasPendingFallback = false
            pendingFallback = null
            return
        }

        val restored = runCatching { pendingPersistence.commit(previous) }.getOrDefault(false)
        if (restored) {
            hasPendingFallback = false
            pendingFallback = null
        } else {
            // Keep this process from admitting a new batch over a reservation whose durable
            // value could not be restored to the SharedPreferences in-memory view.
            hasPendingFallback = true
            pendingFallback = previous
        }
        val failure = IllegalStateException(failureMessage)
        if (!restored) failure.addSuppressed(IllegalStateException("Restauration de la réservation impossible"))
        throw failure
    }
    fun fail(id: String, message: String) = synchronized(completionLock) {
        pending()?.takeIf { it.id == id && !it.complete && !it.meetingAnchorInvalid }?.let {
            if (it.batch && it.images.isNotEmpty()) {
                runCatching { writePending(it.copy(message = message)) }
            } else {
                delete(id)
                runCatching { writePending(it.copy(error = message)) }
            }
                .onFailure { android.util.Log.w("DictAI", "event=note_capture persistence=failed") }
        }
    }
    fun clearPending(id: String) = synchronized(completionLock) {
        pending()?.takeIf { it.id == id }?.let { capture ->
            commitPending(null, "Capture non effacée")
            if (capture.batch && !capture.accepted) capture.images.forEach { delete(it.id) }
            cameraFile(id).delete()
        }
    }
    fun delete(id: String) { file(id).delete(); thumbnail(id).delete(); cameraFile(id).delete() }

    /** Called on a worker, never the audio or UI thread. ImageDecoder also applies EXIF orientation. */
    fun importCamera(id: String, kind: NoteImageKind? = null) {
        val input = cameraFile(id)
        try {
            require(input.length() in 1..40L * 1024 * 1024) { "Photo vide ou trop volumineuse" }
            val bitmap = decode(input, 2048)
            try { store(id, bitmap, kind) } finally { bitmap.recycle() }
        } finally { input.delete() }
    }
    fun importUri(id: String, uri: Uri, kind: NoteImageKind) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri)) { decoder, info, _ ->
            require(info.size.width > 0 && info.size.height > 0 && info.size.width.toLong() * info.size.height <= 100_000_000)
            val ratio = minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try { store(id, bitmap, kind) } finally { bitmap.recycle() }
    }

    fun store(id: String, bitmap: Bitmap, kind: NoteImageKind? = null) {
        val reservation = pending()?.takeIf { it.id == id } ?: return
        check(!reservation.meetingAnchorInvalid) { "Ancre Réunion invalide; la capture ne peut pas être ajoutée" }
        val initial = reservation.takeUnless { it.complete } ?: return
        check(!initial.meetingAnchorInvalid) { "Ancre Réunion invalide; la capture ne peut pas être ajoutée" }
        if (initial.batch && initial.images.size >= initial.maxImages) return
        val imageId = if (initial.batch) UUID.randomUUID().toString() else id
        val ratio = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (ratio < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        val temporary = File(root, "$imageId.tmp")
        val temporaryThumbnail = File(root, "$imageId.thumb.tmp")
        var published = false
        try {
            temporary.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            check(temporary.length() in 1..8L * 1024 * 1024)
            val destination = file(imageId)
            check(temporary.renameTo(destination))
            run {
                val smallRatio = minOf(1f, 160f / maxOf(scaled.width, scaled.height))
                val thumb = Bitmap.createScaledBitmap(scaled, (scaled.width * smallRatio).toInt().coerceAtLeast(1),
                    (scaled.height * smallRatio).toInt().coerceAtLeast(1), true)
                try {
                    temporaryThumbnail.outputStream().use { check(thumb.compress(Bitmap.CompressFormat.JPEG, 82, it)) }
                    check(temporaryThumbnail.length() in 1..512L * 1024)
                    check(temporaryThumbnail.renameTo(thumbnail(imageId)))
                }
                finally { if (thumb !== scaled) thumb.recycle() }
            }
            published = synchronized(completionLock) {
                val capture = pending()?.takeIf { it.id == id && !it.complete }
                if (capture == null || (capture.batch && capture.images.size >= capture.maxImages)) false
                else {
                    val number = if (capture.batch) capture.images.maxOfOrNull { it.number }?.plus(1) ?: capture.number else capture.number
                    val storedImage = NoteImage(imageId, number, kind ?: capture.kind, System.currentTimeMillis(), scaled.width, scaled.height)
                    if (capture.batch) writePending(capture.copy(images = capture.images + storedImage, message = null))
                    else writePending(capture.copy(image = storedImage))
                    true
                }
            }
        } finally {
            temporary.delete()
            temporaryThumbnail.delete()
            if (!published) {
                file(imageId).delete()
                thumbnail(imageId).delete()
            }
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
