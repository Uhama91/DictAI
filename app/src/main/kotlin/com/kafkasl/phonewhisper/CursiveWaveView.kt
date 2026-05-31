package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
        // Boucles plus larges et moins nombreuses → ondulations cursives bien visibles
        // (au lieu d'un gribouillis dense). Espace logique 200x40.
        private const val LOOP_WIDTH = 22f
        private const val NUM_LOOPS = 12
        private const val SVG_W = 200f
        private const val SVG_H = 40f
        private const val CENTER_Y = 20f
        private const val MIN_AMP = 9f
        private const val MAX_AMP = 36f
        // Pendant l'enregistrement les boucles DÉFILENT (écriture qui avance) + ondulent avec la voix.
        // Au repos le tick est arrêté → figé. Vitesse de défilement horizontal :
        private const val SCROLL_SPEED = 0.7f
        private const val WAVE_SPEED = 0.1f
        private const val TWO_PI = 6.2831855f
    }

    private data class LoopVar(val widthMod: Float, val ampMod: Float)

    private val loopVars = Array(NUM_LOOPS + 2) {
        LoopVar(0.8f + Math.random().toFloat() * 0.4f, 0.8f + Math.random().toFloat() * 0.4f)
    }
    private val totalLoopsWidth =
        loopVars.fold(0f) { acc, v -> acc + LOOP_WIDTH * v.widthMod }

    @Volatile private var level = 0f
    private var offset = 0f
    private var time = 0f
    private var smoothAmp = 0f
    private var running = false

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF1A1A2E.toInt() // encre sombre DictAI
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Niveau de voix [0..1] qui pilote l'amplitude de l'onde. */
    fun setLevel(l: Float) { level = l.coerceIn(0f, 1f) }

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
        paint.strokeWidth = 2.2f * (h / SVG_H)
        canvas.save()
        // Confiner l'encre à la pastille : les boucles ne débordent plus sur les côtés.
        canvas.clipRect(0f, 0f, w, h)
        canvas.scale(w / SVG_W, h / SVG_H)
        buildPath(offset, smoothAmp, time)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun buildPath(xOffset: Float, waveAmplitude: Float, t: Float) {
        path.reset()
        // modulo positif (offset peut être négatif quand on défile vers la droite)
        val safeOffset = ((xOffset % totalLoopsWidth) + totalLoopsWidth) % totalLoopsWidth
        var currentX = -safeOffset - LOOP_WIDTH * 2
        var started = false
        var i = 0
        while (i < loopVars.size * 2) {
            val v = loopVars[i % loopVars.size]
            val loopW = LOOP_WIDTH * v.widthMod
            val nextX = currentX + loopW
            val midX = currentX + loopW * 0.5f
            val distFromCenter = abs(midX - SVG_W / 2f) / (SVG_W / 2f)
            val envelope = max(0f, 1f - distFromCenter.pow(1.5f))
            val travelingWave = sin(-distFromCenter * 6f + t) * 0.4f + 0.6f
            var actualAmp = MIN_AMP + waveAmplitude * v.ampMod * envelope * travelingWave
            actualAmp = min(MAX_AMP, max(MIN_AMP, actualAmp))
            val top = max(2f, CENTER_Y - actualAmp / 2f)
            val bottom = min(SVG_H - 2f, CENTER_Y + actualAmp / 2f)
            if (!started) { path.moveTo(currentX, bottom); started = true }
            path.cubicTo(currentX + loopW * 0.5f, bottom, nextX + loopW * 0.1f, top, nextX - loopW * 0.2f, top)
            path.cubicTo(currentX + loopW * 0.2f, top, nextX - loopW * 0.2f, bottom, nextX, bottom)
            currentX = nextX
            if (currentX > SVG_W + LOOP_WIDTH * 2) break
            i++
        }
    }
}
