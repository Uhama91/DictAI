package com.kafkasl.phonewhisper

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date

/** Selectable native text with image pages in reading order, using Android's bundled PDF engine. */
internal object NotePdfExport {
    fun write(note: TranscriptNote, output: OutputStream, store: NoteImageStore) {
        val pdf = PdfDocument()
        try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 35, 41); textSize = 12f }
            var page: PdfDocument.Page? = null
            var number = 0
            var y = 42f
            fun nextPage() {
                page?.let { pdf.finishPage(it); page = null }
                check(number < 1000) { "Note trop longue pour cet export PDF" }
                page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
                y = 42f
                val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 9f }
                page!!.canvas.drawText("DictAI · $number", 42f, 817f, footer)
            }
            fun prose(text: String, size: Float = 12f, bold: Boolean = false) {
                if (text.isBlank()) return
                paint.textSize = size
                paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // Small chunks bound layout memory even for a very long manually edited note.
                NoteTextChunks.split(text.trim()).forEach { chunk ->
                    val layout = StaticLayout.Builder.obtain(chunk, 0, chunk.length, paint, 511)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1f).build()
                    var line = 0
                    while (line < layout.lineCount) {
                        if (page == null || y + layout.getLineBottom(line) - layout.getLineTop(line) > 796) nextPage()
                        val start = layout.getLineTop(line)
                        var end = line + 1
                        while (end < layout.lineCount && y + layout.getLineBottom(end) - start <= 796) end++
                        val height = layout.getLineBottom(end - 1) - start
                        page!!.canvas.save()
                        page!!.canvas.clipRect(42f, y, 553f, y + height)
                        page!!.canvas.translate(42f, y - start)
                        layout.draw(page!!.canvas)
                        page!!.canvas.restore()
                        y += height + 8
                        line = end
                    }
                }
            }
            try {
                nextPage()
                prose(note.title, 21f, true)
                prose(DateFormat.getDateTimeInstance().format(Date(note.updatedAt)), 10f)
                NoteImageMarkers.parts(note).forEach { part ->
                    when (part) {
                        is NoteImageMarkers.Part.Text -> prose(part.text)
                        is NoteImageMarkers.Part.Image -> {
                            val image = part.image
                            val bitmap = NoteImageStore.decode(store.file(image.id), 1800)
                            try {
                                val scale = minOf(511f / bitmap.width, 650f / bitmap.height)
                                val height = bitmap.height * scale
                                if (y + height + 52 > 796) nextPage()
                                prose("Image ${image.number} · ${image.kind.label}" + if (part.missingMarker) " · repère retiré du texte" else "", 10f)
                                val left = 42 + (511 - bitmap.width * scale) / 2
                                page!!.canvas.drawBitmap(bitmap, null, RectF(left, y, left + bitmap.width * scale, y + height),
                                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                                y += height + 8
                                prose(DateFormat.getDateTimeInstance().format(Date(image.capturedAt)), 9f)
                            } finally { bitmap.recycle() }
                        }
                    }
                }
                page?.let { pdf.finishPage(it); page = null }
                pdf.writeTo(output)
            } finally { page?.let { pdf.finishPage(it) } }
        } finally { pdf.close() }
    }
}
