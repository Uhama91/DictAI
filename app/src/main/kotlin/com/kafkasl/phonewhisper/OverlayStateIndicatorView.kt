package com.kafkasl.phonewhisper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Small accessible state mark that sits beside the selected format in the overlay header. */
class OverlayStateIndicatorView(context: Context) : View(context) {
    enum class VisualState { IDLE, RECORDING, PAUSED, PROCESSING }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var visualState = VisualState.IDLE
    private var pulse = .5f
    private var animator: ValueAnimator? = null

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "État de la dictée"
    }

    fun setVisualState(next: VisualState) {
        if (visualState == next) {
            if (next == VisualState.RECORDING) startPulseIfAllowed()
            invalidate()
            return
        }
        visualState = next
        if (next == VisualState.RECORDING) startPulseIfAllowed() else stopPulse()
        contentDescription = when (next) {
            VisualState.IDLE -> "Dictée inactive"
            VisualState.RECORDING -> "Enregistrement en cours"
            VisualState.PAUSED -> "Dictée en pause"
            VisualState.PROCESSING -> "Traitement en cours"
        }
        invalidate()
    }

    private fun animationsAllowed(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }.getOrDefault(true)

    private fun startPulseIfAllowed() {
        if (!isAttachedToWindow || visibility != VISIBLE || windowVisibility != VISIBLE || !isShown || !animationsAllowed()) {
            stopPulse()
            pulse = .5f
            return
        }
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        animator?.cancel()
        animator = null
        pulse = .5f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visualState == VisualState.RECORDING) startPulseIfAllowed()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visualState == VisualState.RECORDING) {
            if (visibility == VISIBLE && isShown) startPulseIfAllowed() else stopPulse()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visualState == VisualState.RECORDING) {
            if (visibility == VISIBLE && isShown) startPulseIfAllowed() else stopPulse()
        }
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colors = ThemeTokens.palette(context)
        val cx = width / 2f
        val cy = height / 2f
        when (visualState) {
            VisualState.RECORDING -> {
                val dot = 5f * density
                val halo = (10f + 5f * pulse) * density
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(colors.recordingRed, (40 + (55 * pulse)).toInt())
                canvas.drawCircle(cx, cy, halo, paint)
                paint.color = colors.recordingRed
                canvas.drawCircle(cx, cy, dot, paint)
            }
            VisualState.PAUSED -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(colors.green, 0x66)
                val marker = 16f * density
                canvas.drawRoundRect(cx - marker, cy - marker, cx + marker, cy + marker, marker, marker, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.5f * density
                paint.color = colors.pauseInk
                val halfHeight = 9f * density
                val gap = 4f * density
                canvas.save()
                canvas.rotate(-6f, cx, cy)
                canvas.drawLine(cx - gap, cy - halfHeight, cx - gap, cy + halfHeight, paint)
                canvas.drawLine(cx + gap, cy - halfHeight, cx + gap, cy + halfHeight, paint)
                canvas.restore()
            }
            VisualState.PROCESSING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f * density
                paint.color = colors.blue
                canvas.drawCircle(cx, cy, 8f * density, paint)
            }
            VisualState.IDLE -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(colors.inkMuted, 150)
                canvas.drawCircle(cx, cy, 4f * density, paint)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255) and 0xFF) shl 24)
}
