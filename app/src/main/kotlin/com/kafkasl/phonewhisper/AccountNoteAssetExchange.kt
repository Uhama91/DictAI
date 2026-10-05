package com.kafkasl.phonewhisper

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Network and image hashing run on the Worker; notes are imported only after verified downloads. */
internal class AccountNoteAssetExchange(context: Context, private val transport: AccountAttachmentTransport,
    accountScope: String, private val preview: (List<SyncDocument>) -> SyncDocument) : ReplicaAssetExchange {
    private val app = context.applicationContext
    private val content = AccountNotesSyncStore(app)
    private val uploaded = app.getSharedPreferences("dictai_uploaded_assets_" + digest(accountScope.toByteArray()), Context.MODE_PRIVATE)

    override fun receive(session: AccountSession, documents: List<SyncDocument>, stillCurrent: () -> Boolean) {
        // Validate every document before downloading or modifying any original.
        documents.forEach(content::validate)
        restorePending(session, preview(documents), stillCurrent)
    }

    fun restorePending(session: AccountSession, document: SyncDocument, stillCurrent: () -> Boolean) {
        content.validate(document)
        val assets = AccountNotesSyncStore.assetDescriptors(document).distinct()
        require(assets.groupBy { it.id }.values.all { group -> group.map { it.sha256 to it.size }.distinct().size == 1 }) {
            "Conflicting attachment identities"
        }
        for (asset in assets) {
            if (!stillCurrent()) return
            val file = AccountNotesSyncStore.imageFile(app, asset.id)
            if (file.exists()) checkOriginal(file, asset)
            else {
                transport.download(session.accessToken, session.userId, asset.sha256, file)
                checkOriginal(file, asset)
            }
            ensurePreview(asset.id, file)
        }
    }

    override fun send(session: AccountSession, document: SyncDocument, stillCurrent: () -> Boolean) {
        content.validate(document)
        for (asset in AccountNotesSyncStore.assetDescriptors(document)) {
            if (!stillCurrent()) return
            if (uploaded.getBoolean(asset.sha256, false)) continue
            val file = AccountNotesSyncStore.imageFile(app, asset.id)
            checkOriginal(file, asset)
            transport.upload(session.accessToken, session.userId, asset.sha256, file)
            if (!stillCurrent()) return
            check(uploaded.edit().putBoolean(asset.sha256, true).commit()) { "Attachment receipt was not saved" }
        }
    }

    private fun checkOriginal(file: File, asset: SyncedNoteAsset) {
        if (!file.isFile || file.length() != asset.size || file.length() !in 1..SupabaseAccountAttachmentTransport.MAX_IMAGE_BYTES.toLong()) {
            throw IOException("Attachment original missing or invalid")
        }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        if (hex(hash.digest()) != asset.sha256) throw IOException("Attachment original conflicts with synchronized bytes")
    }

    private fun ensurePreview(imageId: String, original: File) {
        val destination = NoteImageStore(app).thumbnail(imageId)
        if (destination.isFile && destination.length() > 0) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) {
            "Invalid attachment dimensions"
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 320) sample *= 2
        val decoded = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Attachment preview unavailable")
        val ratio = minOf(1f, 160f / maxOf(decoded.width, decoded.height))
        val preview = if (ratio < 1) Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1), true) else decoded
        val temporary = File.createTempFile("preview-", ".tmp", destination.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                check(preview.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "Attachment preview was not saved" }
                output.fd.sync()
            }
            check(temporary.length() in 1..512L * 1024) { "Attachment preview too large" }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
            if (preview !== decoded) preview.recycle()
            decoded.recycle()
        }
    }

    companion object {
        private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
