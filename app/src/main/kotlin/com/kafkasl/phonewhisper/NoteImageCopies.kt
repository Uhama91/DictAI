package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/** Number only the outgoing copy, so the relation to [[Image N]] survives file renaming. */
internal object NoteImageCopies {
    fun write(context: Context, image: NoteImage, file: File) {
        val original = NoteImageStore.decode(NoteImageStore(context).file(image.id), 2048)
        var labeled: Bitmap? = null
        try {
            val bitmap = Bitmap.createBitmap(original.width, original.height + 72, Bitmap.Config.ARGB_8888)
            labeled = bitmap
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = minOf(32f, original.width / 16f) }
            canvas.drawText("Image ${image.number} · ${image.kind.label}", 16f, 46f, paint)
            canvas.drawBitmap(original, 0f, 72f, null)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        } finally { original.recycle(); labeled?.recycle() }
    }
}
