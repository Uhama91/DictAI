package com.kafkasl.phonewhisper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** Publish only DictAI's own JPEG. No permission to read the user's gallery is needed on API 30+. */
internal object CapturedImageGallery {
    const val ALBUM = "DictAI"
    fun save(context: Context, image: NoteImage): Uri {
        val resolver = context.contentResolver
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.ROOT)
            .format(java.util.Date(image.capturedAt))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "DictAI_${stamp}_${image.id.take(8)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.DATE_TAKEN, image.capturedAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                checkNotNull(output)
                NoteImageStore(context).file(image.id).inputStream().use { input -> input.copyTo(output) }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            return uri
        } catch (error: Exception) {
            // Never leave an incomplete item in Photos, and never delete unrelated gallery entries.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
