package com.kafkasl.phonewhisper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/**
 * Decorative tail for the existing transcript window. It has no touch target and is hosted in
 * the same WindowManager window as the rounded body; its reserved strip never covers the pill.
 */
internal class OverlayBubblePointerView(context: android.content.Context) : View(context) {
    private val path = Path()
    private val outlinePath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var edge = Edge.LEFT
    private var bodyOffsetX = 0
    private var bodyOffsetY = 0
    private var bodyWidth = 0
    private var bodyHeight = 0
    private var pointerLength = 0
    private var targetX = 0f
    private var targetY = 0f
    private var surfaceColor = 0
    private var strokeColor = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    fun setGeometry(
        edge: Edge,
        bodyOffsetX: Int,
        bodyOffsetY: Int,
        bodyWidth: Int,
        bodyHeight: Int,
        pointerLength: Int,
        targetX: Float,
        targetY: Float,
    ) {
        this.edge = edge
        this.bodyOffsetX = bodyOffsetX
        this.bodyOffsetY = bodyOffsetY
        this.bodyWidth = bodyWidth
        this.bodyHeight = bodyHeight
        this.pointerLength = pointerLength.coerceAtLeast(0)
        this.targetX = targetX
        this.targetY = targetY
        invalidate()
    }

    fun setColors(surface: Int, stroke: Int) {
        surfaceColor = surface
        strokeColor = stroke
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (pointerLength <= 0 || bodyWidth <= 0 || bodyHeight <= 0) return
        val density = resources.displayMetrics.density
        val halfWidth = min(8f * density, when (edge) {
            Edge.LEFT, Edge.RIGHT -> bodyHeight * 0.18f
            Edge.TOP, Edge.BOTTOM -> bodyWidth * 0.18f
        })
        if (halfWidth <= 0f) return
        val p = pointerLength.toFloat()
        // Draw the tail a couple of pixels into the rounded body. The pointer is layered above
        // that body in the shared envelope, so the surface fill hides the body's edge stroke at
        // the join while the open outline keeps the tail free of a straight seam.
        val bodyOverlap = min(2f * density, when (edge) {
            Edge.LEFT, Edge.RIGHT -> bodyWidth * 0.08f
            Edge.TOP, Edge.BOTTOM -> bodyHeight * 0.08f
        })
        path.reset()
        outlinePath.reset()
        when (edge) {
            Edge.LEFT -> {
                val x = bodyOffsetX + bodyOverlap
                val radiusClearance = min(24f * density, bodyHeight * 0.32f) + halfWidth
                val minBaseY = bodyOffsetY + radiusClearance
                val maxBaseY = bodyOffsetY + bodyHeight - radiusClearance
                val baseY = targetY.coerceIn(minBaseY, maxBaseY.coerceAtLeast(minBaseY))
                val tipY = targetY.coerceIn(halfWidth, (height - halfWidth).coerceAtLeast(halfWidth))
                path.moveTo(x, baseY - halfWidth)
                path.cubicTo(x - p * 0.18f, baseY - halfWidth, x - p * 0.72f, tipY - halfWidth * 0.82f, 0.5f, tipY)
                path.cubicTo(x - p * 0.72f, tipY + halfWidth * 0.82f, x - p * 0.18f, baseY + halfWidth, x, baseY + halfWidth)
                outlinePath.moveTo(x, baseY - halfWidth)
                outlinePath.cubicTo(x - p * 0.18f, baseY - halfWidth, x - p * 0.72f, tipY - halfWidth * 0.82f, 0.5f, tipY)
                outlinePath.cubicTo(x - p * 0.72f, tipY + halfWidth * 0.82f, x - p * 0.18f, baseY + halfWidth, x, baseY + halfWidth)
            }
            Edge.RIGHT -> {
                val x = bodyOffsetX + bodyWidth.toFloat() - bodyOverlap
                val radiusClearance = min(24f * density, bodyHeight * 0.32f) + halfWidth
                val minBaseY = bodyOffsetY + radiusClearance
                val maxBaseY = bodyOffsetY + bodyHeight - radiusClearance
                val baseY = targetY.coerceIn(minBaseY, maxBaseY.coerceAtLeast(minBaseY))
                val tipY = targetY.coerceIn(halfWidth, (height - halfWidth).coerceAtLeast(halfWidth))
                path.moveTo(x, baseY - halfWidth)
                path.cubicTo(x + p * 0.18f, baseY - halfWidth, x + p * 0.72f, tipY - halfWidth * 0.82f, width - 0.5f, tipY)
                path.cubicTo(x + p * 0.72f, tipY + halfWidth * 0.82f, x + p * 0.18f, baseY + halfWidth, x, baseY + halfWidth)
                outlinePath.moveTo(x, baseY - halfWidth)
                outlinePath.cubicTo(x + p * 0.18f, baseY - halfWidth, x + p * 0.72f, tipY - halfWidth * 0.82f, width - 0.5f, tipY)
                outlinePath.cubicTo(x + p * 0.72f, tipY + halfWidth * 0.82f, x + p * 0.18f, baseY + halfWidth, x, baseY + halfWidth)
            }
            Edge.TOP -> {
                val radiusClearance = min(24f * density, bodyWidth * 0.32f) + halfWidth
                val minBaseX = bodyOffsetX + radiusClearance
                val maxBaseX = bodyOffsetX + bodyWidth - radiusClearance
                val baseX = targetX.coerceIn(minBaseX, maxBaseX.coerceAtLeast(minBaseX))
                val tipX = targetX.coerceIn(halfWidth, (width - halfWidth).coerceAtLeast(halfWidth))
                val y = bodyOffsetY + bodyOverlap
                path.moveTo(baseX - halfWidth, y)
                path.cubicTo(baseX - halfWidth, y - p * 0.18f, tipX - halfWidth * 0.82f, y - p * 0.72f, tipX, 0.5f)
                path.cubicTo(tipX + halfWidth * 0.82f, y - p * 0.72f, baseX + halfWidth, y - p * 0.18f, baseX + halfWidth, y)
                outlinePath.moveTo(baseX - halfWidth, y)
                outlinePath.cubicTo(baseX - halfWidth, y - p * 0.18f, tipX - halfWidth * 0.82f, y - p * 0.72f, tipX, 0.5f)
                outlinePath.cubicTo(tipX + halfWidth * 0.82f, y - p * 0.72f, baseX + halfWidth, y - p * 0.18f, baseX + halfWidth, y)
            }
            Edge.BOTTOM -> {
                val radiusClearance = min(24f * density, bodyWidth * 0.32f) + halfWidth
                val minBaseX = bodyOffsetX + radiusClearance
                val maxBaseX = bodyOffsetX + bodyWidth - radiusClearance
                val baseX = targetX.coerceIn(minBaseX, maxBaseX.coerceAtLeast(minBaseX))
                val tipX = targetX.coerceIn(halfWidth, (width - halfWidth).coerceAtLeast(halfWidth))
                val y = bodyOffsetY + bodyHeight.toFloat() - bodyOverlap
                path.moveTo(baseX - halfWidth, y)
                path.cubicTo(baseX - halfWidth, y + p * 0.18f, tipX - halfWidth * 0.82f, y + p * 0.72f, tipX, height - 0.5f)
                path.cubicTo(tipX + halfWidth * 0.82f, y + p * 0.72f, baseX + halfWidth, y + p * 0.18f, baseX + halfWidth, y)
                outlinePath.moveTo(baseX - halfWidth, y)
                outlinePath.cubicTo(baseX - halfWidth, y + p * 0.18f, tipX - halfWidth * 0.82f, y + p * 0.72f, tipX, height - 0.5f)
                outlinePath.cubicTo(tipX + halfWidth * 0.82f, y + p * 0.72f, baseX + halfWidth, y + p * 0.18f, baseX + halfWidth, y)
            }
        }
        path.close()
        fillPaint.color = surfaceColor
        canvas.drawPath(path, fillPaint)
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = density.coerceAtLeast(1f)
        // The fill closes across the body edge for a continuous surface, but the outline remains
        // open there: drawing that closing segment would leave a straight seam/"ear" at the
        // junction and make the tail look pasted onto the rounded body.
        canvas.drawPath(outlinePath, strokePaint)
    }
}
