package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View

/**
 * Bordure lumineuse colorée qui tourne autour de la pastille pendant le chargement
 * (transcription / post-traitement LLM). Une "comète" multicolore + halo flou parcourt
 * le contour arrondi pour signaler que ça travaille.
 */
class LoadingBorderView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val stroke = 3f * dp
    private val path = Path()
    private val rect = RectF()
    private val matrix = Matrix()
    @Volatile private var angle = 0f
    private var running = false
    private var shader: SweepGradient? = null

    // Trait net + halo flou (effet lumineux) ; le blur exige une couche software.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke * 1.8f; strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL)
    }

    // Cachée au repos → pas de rendu software/blur tant qu'aucun traitement n'est en cours.
    init { setLayerType(LAYER_TYPE_SOFTWARE, null); visibility = GONE }

    fun start() { if (!running) { running = true; visibility = VISIBLE; postOnAnimation(tick) } }
    fun stop() { running = false; removeCallbacks(tick); visibility = GONE }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            angle = (angle + 5f) % 360f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cx = w / 2f; val cy = h / 2f
        // Comète verte lumineuse (thème « carnet » DictAI) : transparent → vert profond
        // → vert menthe → halo vert pâle → transparent (sur ~40 % du tour).
        shader = SweepGradient(
            cx, cy,
            intArrayOf(0x00000000, 0xFF2D7A4F.toInt(), 0xFF5BCB95.toInt(), 0xFFAEF2D2.toInt(), 0x00000000),
            floatArrayOf(0f, 0.12f, 0.22f, 0.32f, 0.42f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val s = shader ?: return
        val inset = stroke / 2f + 1f
        rect.set(inset, inset, width - inset, height - inset)
        val r = rect.height() / 2f
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        matrix.setRotate(angle, width / 2f, height / 2f)
        s.setLocalMatrix(matrix)
        glow.shader = s; paint.shader = s
        canvas.drawPath(path, glow)
        canvas.drawPath(path, paint)
    }
}
