package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Visual compact-wave candidates used by the native comparison renders. */
internal enum class CompactMotionPreset {
    ORGANIC,
    VIVID,
    BREATHING,
}

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
        // Keep the compact silhouette installed in 631dbeb: a 16-unit resting stroke centered
        // in the 40-unit logical viewport. Voice opens it around the same writing axis instead
        // of flattening the resting loops into a short rail.
        private const val COMPACT_CENTER_Y = 20f
        private const val COMPACT_REST_VALLEY_Y = 28f
        private const val COMPACT_REST_TOP_Y = 12f
        private const val COMPACT_LOOP_WIDTH = 28f
        private const val COMPACT_LOOPS = 6
        private const val BRAND_W = 200f
        private const val BRAND_H = 80f
        private const val BRAND_CENTER_Y = 40f
        private const val BRAND_LOOP_WIDTH = 58f
        private const val BRAND_MIN_AMP = 25f
        private const val BRAND_MAX_AMP = 38f
        // Logical units per second. These preserve the former ~0.7 unit/frame and ~0.1 rad/frame
        // at 60 Hz, while making movement independent of the device refresh rate.
        private const val SCROLL_SPEED_PER_SECOND = 42f
        private const val WAVE_SPEED_RAD_PER_SECOND = 6f
        private const val MAX_FRAME_DT_SECONDS = 0.12f
        private const val TWO_PI = 6.2831855f
    }

    private data class LoopVar(val widthMod: Float, val ampMod: Float)

    private data class CompactMotionStyle(
        val maxPeakLift: Float,
        val maxValleyDrop: Float,
        val phaseStep: Float,
        val pulseDepth: Float,
        val crestSkew: Float,
        val crestX: Float,
        val entryControlX: Float,
        val highControlX: Float,
        val returnControlX: Float,
        val lowControlX: Float,
        val attackTau: Float,
        val releaseTau: Float,
    )

    /** Geometry exposed to JVM tests so baseline and periodicity are checked on the real path. */
    internal data class CompactLoopGeometry(
        val startX: Float,
        val endX: Float,
        val topY: Float,
        /** The valley at the loop's left junction; kept for backwards-readable test output. */
        val baselineY: Float,
        /** The same loop's right junction, shared with the next loop. */
        val endBaselineY: Float,
    )

    // Fixed variations make a spatial wrap exact: every cycle has the same handwriting and
    // cannot jump to a different random loop when the first loop re-enters from the edge.
    private val loopVars = arrayOf(
        LoopVar(0.92f, 0.94f),
        LoopVar(1.06f, 1.04f),
        LoopVar(0.86f, 1.08f),
        LoopVar(1.12f, 0.91f),
        LoopVar(0.98f, 1.06f),
        LoopVar(1.04f, 0.97f),
    )
    private val totalLoopsWidth =
        loopVars.fold(0f) { acc, v -> acc + COMPACT_LOOP_WIDTH * v.widthMod }

    // These immutable styles are reused for every frame and every loop. Keeping them cached
    // avoids allocating a data class from the Canvas hot path while retaining test-only presets.
    private val organicStyle = CompactMotionStyle(
        maxPeakLift = 10f,
        maxValleyDrop = 9f,
        phaseStep = 0.86f,
        pulseDepth = 0.18f,
        crestSkew = 0.025f,
        crestX = 0.76f,
        entryControlX = 0.50f,
        highControlX = 1.05f,
        returnControlX = 0.18f,
        lowControlX = 0.76f,
        attackTau = 0.046f,
        releaseTau = 0.115f,
    )
    private val vividStyle = CompactMotionStyle(
        // The installed stroke is top=12/bottom=28 at rest. B opens to approximately 3/37
        // under a full voice event while preserving the same crossed-loop silhouette.
        maxPeakLift = 9f,
        maxValleyDrop = 9f,
        phaseStep = 1.08f,
        pulseDepth = 0.30f,
        crestSkew = 0f,
        crestX = 0.80f,
        entryControlX = 0.50f,
        highControlX = 1.10f,
        returnControlX = 0.20f,
        lowControlX = 0.80f,
        attackTau = 0.032f,
        releaseTau = 0.092f,
    )
    private val breathingStyle = CompactMotionStyle(
        maxPeakLift = 11f,
        maxValleyDrop = 12.5f,
        phaseStep = 0.72f,
        pulseDepth = 0.14f,
        crestSkew = 0.045f,
        crestX = 0.70f,
        entryControlX = 0.50f,
        highControlX = 0.98f,
        returnControlX = 0.27f,
        lowControlX = 0.70f,
        attackTau = 0.054f,
        releaseTau = 0.145f,
    )

    @Volatile private var level = 0f
    private var offset = 0f
    private var time = 0f
    private var smoothAmp = 0f
    /** Normalized spring velocity, retained across audio target changes for smooth attack/release. */
    private var voiceVelocity = 0f
    // Direction B was selected by the user for production after the first comparison. The
    // other two presets remain available to the native comparison test only.
    private var compactMotionPreset = CompactMotionPreset.VIVID
    private var running = false
    private var lastFrameNanos = 0L
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

    /** Select a compact-wave candidate for the native motion comparison. Brand mode is unchanged. */
    internal fun setCompactMotionPreset(preset: CompactMotionPreset) {
        if (compactMotionPreset == preset) return
        compactMotionPreset = preset
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = SystemClock.elapsedRealtimeNanos()
        postOnAnimation(tick)
    }

    fun stop() {
        running = false
        lastFrameNanos = 0L
        removeCallbacks(tick)
    }

    /** Repos : onde calme (amplitude minimale) dessinée une seule fois, sans animer. */
    fun settle() { level = 0f; smoothAmp = 0f; voiceVelocity = 0f; invalidate() }

    /** Advance the same time-based state machine used by Android frames, for deterministic tests. */
    internal fun advanceForTest(dtSeconds: Float) {
        advanceAnimation(dtSeconds)
        invalidate()
    }

    internal fun animationSnapshotForTest(): Triple<Float, Float, Float> =
        Triple(offset, time, smoothAmp)

    internal fun compactLoopPeriodForTest(): Float = totalLoopsWidth

    internal fun compactGeometryForTest(
        scrollOffset: Float = offset,
        waveAmplitude: Float = smoothAmp,
        phase: Float = time,
    ): List<CompactLoopGeometry> {
        val result = ArrayList<CompactLoopGeometry>()
        visitCompactLoops(scrollOffset, waveAmplitude, phase) { start, end, top, startValley, endValley ->
            result += CompactLoopGeometry(start, end, top, startValley, endValley)
        }
        return result
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtimeNanos()
            val previous = lastFrameNanos
            val dt = if (previous <= 0L) 1f / 60f
            else ((now - previous).coerceAtLeast(0L) / 1_000_000_000f)
                .coerceAtMost(MAX_FRAME_DT_SECONDS)
            lastFrameNanos = now
            advanceAnimation(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun advanceAnimation(dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, MAX_FRAME_DT_SECONDS)
        if (dt <= 0f) return
        // Positive offset means the handwritten stroke travels toward the right. Modulo by the
        // fixed period keeps the re-entry identical at every wrap and bounds float drift.
        offset = (offset + SCROLL_SPEED_PER_SECOND * dt) % totalLoopsWidth
        time = (time + WAVE_SPEED_RAD_PER_SECOND * dt) % TWO_PI

        val currentVoice = (smoothAmp / BRAND_MAX_AMP).coerceIn(0f, 1f)
        val targetVoice = level.coerceIn(0f, 1f)
        if (brandMode) {
            // Keep the already shipped brand/header response unchanged. The spring below is
            // intentionally limited to the compact pill so the home identity does not acquire
            // a different feel as part of this motion correction.
            smoothAmp += (targetVoice * BRAND_MAX_AMP - smoothAmp) * 0.12f
            voiceVelocity = 0f
            return
        }
        val style = compactMotionStyle()
        val tau = if (targetVoice >= currentVoice) style.attackTau else style.releaseTau
        // Critically damped, closed-form spring. Unlike a one-pole interpolation, it keeps the
        // velocity when a 20 ms audio block changes the target, so syllable edges stay lively
        // without a derivative jump. The 3/tau factor reaches the new target quickly while the
        // analytic step remains stable for both 60 and 120 Hz frames.
        val omega = 3f / tau
        val displacement = currentVoice - targetVoice
        val decay = exp(-omega * dt)
        val translated = voiceVelocity + omega * displacement
        val nextDisplacement = (displacement + translated * dt) * decay
        val nextVelocity = (voiceVelocity - omega * translated * dt) * decay
        val unclampedVoice = targetVoice + nextDisplacement
        val nextVoice = unclampedVoice.coerceIn(0f, 1f)
        voiceVelocity = if (nextVoice == unclampedVoice) nextVelocity else 0f
        smoothAmp = nextVoice * BRAND_MAX_AMP
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
        var started = false
        val style = compactMotionStyle()
        visitCompactLoops(xOffset, waveAmplitude, t) { currentX, nextX, top, startValley, endValley ->
            val loopW = nextX - currentX
            if (!started) { path.moveTo(currentX, startValley); started = true }
            // The two cubic segments share a horizontal tangent at the crest. Their final and
            // initial controls also share a horizontal tangent at every valley junction, so the
            // small lower movement never tears the continuously written stroke apart.
            val skew = style.crestSkew
            // Keep the return control before the crest and the high control after it. This is
            // the small crossover that makes a handwritten loop, rather than a plain arch.
            val crestX = loopW * (style.crestX + skew * 0.4f)
            path.cubicTo(
                currentX + loopW * style.entryControlX, startValley,
                currentX + loopW * (style.highControlX + skew), top,
                currentX + crestX, top,
            )
            path.cubicTo(
                currentX + loopW * style.returnControlX, top,
                currentX + loopW * style.lowControlX, endValley,
                nextX, endValley,
            )
        }
    }

    /**
     * Visits all loops needed to cover both fade margins. Each junction valley is evaluated by
     * one deterministic function and passed to both neighboring loops, which makes the whole
     * path one connected stroke even while the voice level changes its lower contour.
     */
    private inline fun visitCompactLoops(
        xOffset: Float,
        waveAmplitude: Float,
        t: Float,
        crossinline visitor: (
            startX: Float,
            endX: Float,
            topY: Float,
            startValleyY: Float,
            endValleyY: Float,
        ) -> Unit,
    ) {
        val safeOffset = ((xOffset % totalLoopsWidth) + totalLoopsWidth) % totalLoopsWidth
        var currentX = safeOffset - totalLoopsWidth - COMPACT_LOOP_WIDTH * 2
        var i = 0
        val voice = (waveAmplitude / BRAND_MAX_AMP).coerceIn(0f, 1f)
        val style = compactMotionStyle()
        // The widest possible visible span is covered by several complete deterministic cycles.
        // This limit is deliberately generous and independent of the current offset.
        while (currentX < COMPACT_W + COMPACT_LOOP_WIDTH * 2 && i < loopVars.size * 8) {
            val v = loopVars[i % loopVars.size]
            val loopW = COMPACT_LOOP_WIDTH * v.widthMod
            val nextX = currentX + loopW
            val midX = currentX + loopW * 0.5f
            val distFromCenter = abs(midX - COMPACT_W / 2f) / (COMPACT_W / 2f)
            val envelope = (1f - distFromCenter.pow(1.35f)).coerceIn(0f, 1f)
            val slot = i % loopVars.size
            val pulse = (1f - style.pulseDepth * 0.5f +
                style.pulseDepth * 0.5f * sin(t + slot * style.phaseStep - distFromCenter * 1.8f))
                .coerceIn(0.65f, 1.15f)
            val startValley = compactValleyY(i, voice, t, style)
            val endValley = compactValleyY(i + 1, voice, t, style)
            // A voice event raises the crest decisively while the same local event lowers the
            // connected valleys. Both excursions are measured from the center axis; the B style
            // deliberately keeps their travel comparable instead of making the bottom a tremor.
            val meanValleyActivity = (compactValleyActivity(i, t, style) +
                compactValleyActivity(i + 1, t, style)) * 0.5f
            // The same local envelope drives both directions. A small pulse term keeps the
            // handwriting alive, but it cannot make a crest move independently of its U-shaped
            // lower junctions during a held syllable.
            val eventActivity = (meanValleyActivity * 0.85f + pulse * 0.15f).coerceIn(0f, 1f)
            val peakActivity = (voice * v.ampMod *
                (0.70f + 0.30f * envelope) * eventActivity).coerceIn(0f, 1f)
            val top = (COMPACT_REST_TOP_Y - style.maxPeakLift * peakActivity)
                .coerceIn(2f, COMPACT_CENTER_Y - 3f)
            visitor(currentX, nextX, top, startValley, endValley)
            currentX = nextX
            i++
        }
    }

    private fun compactMotionStyle(): CompactMotionStyle = when (compactMotionPreset) {
        CompactMotionPreset.ORGANIC -> organicStyle
        CompactMotionPreset.VIVID -> vividStyle
        CompactMotionPreset.BREATHING -> breathingStyle
    }

    private fun compactValleyY(index: Int, voice: Float, t: Float, style: CompactMotionStyle): Float {
        return (COMPACT_REST_VALLEY_Y + style.maxValleyDrop * voice * compactValleyActivity(index, t, style))
            .coerceIn(COMPACT_REST_VALLEY_Y, COMPACT_REST_VALLEY_Y + style.maxValleyDrop)
    }

    private fun compactValleyActivity(index: Int, t: Float, style: CompactMotionStyle): Float {
        val slot = ((index % loopVars.size) + loopVars.size) % loopVars.size
        val phase = t + slot * style.phaseStep + 0.42f
        val normalized = (0.5f + 0.5f * sin(phase)).coerceIn(0f, 1f)
        return (0.42f + 0.58f * normalized).coerceIn(0f, 1f)
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
