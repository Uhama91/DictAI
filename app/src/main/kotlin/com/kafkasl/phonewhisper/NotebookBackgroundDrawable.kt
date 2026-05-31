package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Fond "page de carnet" : lignes réglées horizontales + filet de marge vert. */
class NotebookBackgroundDrawable(ctx: Context) : Drawable() {
    private val d = ctx.resources.displayMetrics.density
    private val spacing = 34f * d
    private val marginX = 30f * d
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeTokens.LINE; strokeWidth = 1f * d
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (0x55_000000.toInt() and 0 or ThemeTokens.GREEN); alpha = 90; strokeWidth = 1.5f * d
    }
    private val bgPaint = Paint().apply { color = ThemeTokens.BG }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), bgPaint)
        var y = b.top + spacing
        while (y < b.bottom) {
            canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, linePaint)
            y += spacing
        }
        canvas.drawLine(b.left + marginX, b.top.toFloat(), b.left + marginX, b.bottom.toFloat(), marginPaint)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("deprecated in API") override fun getOpacity() = PixelFormat.OPAQUE
}
