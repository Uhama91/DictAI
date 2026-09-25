package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.max
import kotlin.math.min

/** Small graphical attachment chip; its covered editor range is exactly one U+FFFC character. */
internal class OverlayImageBlockSpan(
    context: Context,
    val block: TranscriptImageBlock,
) : ReplacementSpan() {
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private val textSize = 12f * scaledDensity
    private val height = max(38f * density, textSize + 14f * density)
    private val pad = 6f * density
    private val radius = 9f * density
    private val thumbnailSize = 28f * density
    private val background = 0xFF23342B.toInt()
    private val stroke = 0xFF69C995.toInt()
    private val foreground = 0xFFF2F5F2.toInt()
    private val label = when (block.images.size) {
        1 -> block.images.single().kind.label
        else -> "${block.images.size} images"
    }
    private val thumbnail: Bitmap? = block.images.firstOrNull()?.let { image ->
        runCatching { BitmapFactory.decodeFile(NoteImageStore(context).thumbnail(image.id).path) }.getOrNull()
    }

    init {
        contentDescription = when (block.images.size) {
            1 -> block.images.single().kind.label
            else -> "Série de ${block.images.size} images"
        }
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fontMetrics: Paint.FontMetricsInt?): Int {
        fontMetrics?.let {
            it.top = min(it.top, -height.toInt() + (3 * density).toInt())
            it.ascent = min(it.ascent, -height.toInt() + (3 * density).toInt())
            it.descent = max(it.descent, (3 * density).toInt())
            it.bottom = max(it.bottom, (3 * density).toInt())
        }
        val labelPaint = Paint(paint).apply {
            textSize = this@OverlayImageBlockSpan.textSize
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val textWidth = labelPaint.measureText(label).toInt()
        return min((240f * density).toInt(), (thumbnailSize + pad * 3 + textWidth).toInt())
            .coerceAtLeast((84f * density).toInt())
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val width = getSize(paint, text, start, end, null).toFloat()
        val topF = y - height + (3 * density)
        val rect = RectF(x, topF, x + width, topF + height)
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background; style = Paint.Style.FILL }
        canvas.drawRoundRect(rect, radius, radius, chipPaint)
        chipPaint.color = stroke
        chipPaint.style = Paint.Style.STROKE
        chipPaint.strokeWidth = density
        canvas.drawRoundRect(rect, radius, radius, chipPaint)

        val thumbRect = RectF(x + pad, topF + (height - thumbnailSize) / 2, x + pad + thumbnailSize,
            topF + (height + thumbnailSize) / 2)
        val thumb = thumbnail
        if (thumb != null && !thumb.isRecycled) {
            val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(thumb, null, thumbRect, thumbPaint)
        } else {
            chipPaint.style = Paint.Style.FILL
            chipPaint.color = stroke
            canvas.drawRoundRect(thumbRect, 4f * density, 4f * density, chipPaint)
            chipPaint.color = background
            canvas.drawRect(thumbRect.left + 5 * density, thumbRect.top + 6 * density,
                thumbRect.right - 5 * density, thumbRect.bottom - 6 * density, chipPaint)
        }
        val labelPaint = Paint(paint).apply {
            color = foreground
            textSize = this@OverlayImageBlockSpan.textSize
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val labelX = thumbRect.right + pad
        canvas.save()
        canvas.clipRect(labelX, rect.top, rect.right - pad, rect.bottom)
        canvas.drawText(label, labelX, y - (height / 2) + (labelPaint.textSize * .36f), labelPaint)
        canvas.restore()
    }
}
