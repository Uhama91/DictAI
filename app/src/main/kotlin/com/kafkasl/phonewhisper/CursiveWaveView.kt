package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Port Android du `CursiveLoops` de DictAI : des boucles d'écriture cursive qui défilent
 * horizontalement et ondulent au rythme de la voix (niveau micro via [setLevel]).
 * Algorithme repris de src/overlay/RecordingOverlay.tsx (espace logique 200x40, mis à l'échelle).
 */
class CursiveWaveView(context: Context) : View(context) {

    companion object {
        // The compact pill and the home brand have different aspect ratios. They share the
        // same animated voice signal, but each draws in a logical box that keeps the stroke
        // legible instead of squeezing a wide SVG into a short view.
        private const val COMPACT_W = 100f
        private const val COMPACT_H = 40f
        private const val COMPACT_CENTER_Y = 20f
        private const val COMPACT_LOOP_WIDTH = 28f
        private const val COMPACT_LOOPS = 4
        private const val COMPACT_MIN_AMP = 16f
        private const val COMPACT_MAX_AMP = 34f
        private const val BRAND_W = 200f
        private const val BRAND_H = 80f
        private const val BRAND_CENTER_Y = 40f
        private const val BRAND_LOOP_WIDTH = 58f
        private const val BRAND_MIN_AMP = 25f
        private const val BRAND_MAX_AMP = 38f
        // Pendant l'enregistrement les boucles DÉFILENT (écriture qui avance) + ondulent avec la voix.
        // Au repos le tick est arrêté → figé. Vitesse de défilement horizontal :
        private const val SCROLL_SPEED = 0.7f
        private const val WAVE_SPEED = 0.1f
        private const val TWO_PI = 6.2831855f
    }

    private data class LoopVar(val widthMod: Float, val ampMod: Float)

    private val loopVars = Array(COMPACT_LOOPS + 2) {
        LoopVar(0.8f + Math.random().toFloat() * 0.4f, 0.8f + Math.random().toFloat() * 0.4f)
    }
    private val totalLoopsWidth =
        loopVars.fold(0f) { acc, v -> acc + COMPACT_LOOP_WIDTH * v.widthMod }

    @Volatile private var level = 0f
    private var offset = 0f
    private var time = 0f
    private var smoothAmp = 0f
    private var running = false
    private var brandMode = false
    private var edgeShader: LinearGradient? = null
    private var edgeShaderColor = 0
    private var edgeShaderWidth = 0f

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ThemeTokens.palette(context).ink
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var strokeColorOverride: Int? = null

    /** Niveau de voix [0..1] qui pilote l'amplitude de l'onde. */
    fun setLevel(l: Float) { level = l.coerceIn(0f, 1f) }

    /** Optional accent for the calm home identity loop; runtime overlays use ink by default. */
    fun setStrokeColor(color: Int) { strokeColorOverride = color; invalidate() }

    /** Use the taller, spacious identity mark used beside the DictAI wordmark. */
    fun setBrandMode(enabled: Boolean) {
        if (brandMode == enabled) return
        brandMode = enabled
        edgeShader = null
        invalidate()
    }

    fun start() { if (!running) { running = true; postOnAnimation(tick) } }
    fun stop() { running = false; removeCallbacks(tick) }

    /** Repos : onde calme (amplitude minimale) dessinée une seule fois, sans animer. */
    fun settle() { level = 0f; smoothAmp = 0f; invalidate() }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // décroissant → translation des boucles vers la droite ; borné pour éviter la dérive float
            offset = (offset - SCROLL_SPEED) % totalLoopsWidth
            time = (time - WAVE_SPEED) % TWO_PI
            val targetAmp = max(0f, level * 38f)
            smoothAmp += (targetAmp - smoothAmp) * 0.12f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val strokeColor = strokeColorOverride ?: ThemeTokens.palette(context).ink
        paint.color = strokeColor
        val logicalW = if (brandMode) BRAND_W else COMPACT_W
        val logicalH = if (brandMode) BRAND_H else COMPACT_H
        // Keep a roughly 2.3dp stroke after the logical canvas is scaled at any density.
        val scale = min(w / logicalW, h / logicalH)
        if (scale <= 0f) return
        paint.strokeWidth = 2.3f * resources.displayMetrics.density / scale
        // Preserve the hand-drawn proportions when the view is wider than its useful height.
        // The logical path fades at the two edges so a moving loop never ends in a hard cut.
        val drawW = logicalW * scale
        val drawH = logicalH * scale
        canvas.save()
        canvas.translate((w - drawW) / 2f, (h - drawH) / 2f)
        canvas.scale(scale, scale)
        paint.shader = edgeShader(strokeColor, logicalW)
        if (brandMode) buildBrandPath(smoothAmp, time) else buildCompactPath(offset, smoothAmp, time)
        canvas.drawPath(path, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun edgeShader(color: Int, width: Float): LinearGradient {
        if (edgeShader == null || edgeShaderColor != color || edgeShaderWidth != width) {
            edgeShader = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(withAlpha(color, 0), color, color, withAlpha(color, 0)),
                floatArrayOf(0f, 0.14f, 0.86f, 1f),
                Shader.TileMode.CLAMP,
            )
            edgeShaderColor = color
            edgeShaderWidth = width
        }
        return edgeShader!!
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255) and 0xFF) shl 24)

    private fun buildCompactPath(xOffset: Float, waveAmplitude: Float, t: Float) {
        path.reset()
        // modulo positif (offset peut être négatif quand on défile vers la droite)
        val safeOffset = ((xOffset % totalLoopsWidth) + totalLoopsWidth) % totalLoopsWidth
        var currentX = -safeOffset - COMPACT_LOOP_WIDTH * 2
        var started = false
        var i = 0
        // The starting offset can place the first loop well left of the viewport. Draw
        // enough cycles to cover the right fade zone for every randomized width/offset,
        // rather than stopping after a fixed number that can leave an internal hard edge.
        while (currentX < COMPACT_W + COMPACT_LOOP_WIDTH * 2 && i < loopVars.size * 4) {
            val v = loopVars[i % loopVars.size]
            val loopW = COMPACT_LOOP_WIDTH * v.widthMod
            val nextX = currentX + loopW
            val midX = currentX + loopW * 0.5f
            val distFromCenter = abs(midX - COMPACT_W / 2f) / (COMPACT_W / 2f)
            val envelope = max(0f, 1f - distFromCenter.pow(1.5f))
            val travelingWave = sin(-distFromCenter * 6f + t) * 0.4f + 0.6f
            var actualAmp = COMPACT_MIN_AMP + waveAmplitude * v.ampMod * envelope * travelingWave
            actualAmp = min(COMPACT_MAX_AMP, max(COMPACT_MIN_AMP, actualAmp))
            val top = max(2f, COMPACT_CENTER_Y - actualAmp / 2f)
            val bottom = min(COMPACT_H - 2f, COMPACT_CENTER_Y + actualAmp / 2f)
            if (!started) { path.moveTo(currentX, bottom); started = true }
            path.cubicTo(currentX + loopW * 0.5f, bottom, nextX + loopW * 0.1f, top, nextX - loopW * 0.2f, top)
            path.cubicTo(currentX + loopW * 0.2f, top, nextX - loopW * 0.2f, bottom, nextX, bottom)
            currentX = nextX
            if (currentX > COMPACT_W + COMPACT_LOOP_WIDTH * 2) break
            i++
        }
    }

    /** One broad, gently asymmetric identity stroke for the home header. */
    private fun buildBrandPath(waveAmplitude: Float, t: Float) {
        path.reset()
        // The identity mark is a single handwritten gesture: a soft entry, one narrow
        // high crest, a deep fall, then two quieter rebounds. Keeping one continuous path
        // avoids the repeated arches and clipped returns of the compact animation.
        val voiceLift = min(8f, waveAmplitude * 0.18f)
        val drift = sin(t * 0.7f) * 0.8f
        fun y(base: Float, weight: Float = 1f): Float =
            BRAND_CENTER_Y + (base - BRAND_CENTER_Y) * (1f + voiceLift / BRAND_MAX_AMP * weight) + drift

        path.moveTo(0f, y(47f, 0.35f))
        path.cubicTo(10f, y(47f, 0.35f), 16f, y(34f, 0.55f), 26f, y(36f, 0.55f))
        path.cubicTo(36f, y(38f, 0.45f), 43f, y(17f, 0.95f), 51f, y(18f, 0.95f))
        path.cubicTo(56f, y(18f, 0.95f), 57f, y(10f, 1f), 64f, y(9f, 1f))
        path.cubicTo(70f, y(8f, 1f), 70f, y(45f, 0.9f), 78f, y(61f, 0.9f))
        path.cubicTo(85f, y(72f, 0.9f), 92f, y(66f, 0.8f), 99f, y(53f, 0.8f))
        path.cubicTo(106f, y(40f, 0.7f), 111f, y(29f, 0.65f), 118f, y(33f, 0.65f))
        path.cubicTo(125f, y(37f, 0.6f), 129f, y(54f, 0.55f), 136f, y(51f, 0.55f))
        path.cubicTo(143f, y(48f, 0.5f), 148f, y(29f, 0.6f), 153f, y(30f, 0.6f))
        path.cubicTo(160f, y(31f, 0.6f), 166f, y(47f, 0.45f), 174f, y(45f, 0.45f))
        path.cubicTo(182f, y(43f, 0.45f), 191f, y(37f, 0.35f), 200f, y(38f, 0.35f))
    }
}
