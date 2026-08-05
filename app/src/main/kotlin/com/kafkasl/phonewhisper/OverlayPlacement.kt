package com.kafkasl.phonewhisper

import kotlin.math.roundToInt

enum class Edge { LEFT, TOP, RIGHT, BOTTOM }

data class Anchor(val edge: Edge, val offset: Float)

data class Point(val x: Int, val y: Int)

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
}

/** Pure geometry for the movable overlay pill and its non-touchable transcript panel. */
object OverlayPlacement {
    fun snap(position: Point, pill: Rect, screen: Rect): Anchor {
        val point = clampPill(position, pill, screen)
        val distances = listOf(
            Edge.LEFT to point.x - screen.x,
            Edge.TOP to point.y - screen.y,
            Edge.RIGHT to screen.right - (point.x + pill.width),
            Edge.BOTTOM to screen.bottom - (point.y + pill.height),
        )
        // Ordered list makes equal distances deterministic: LEFT, TOP, RIGHT, BOTTOM.
        val edge = distances.minBy { it.second }.first
        val offset = when (edge) {
            Edge.LEFT, Edge.RIGHT -> normalized(point.y - screen.y, screen.height - pill.height)
            Edge.TOP, Edge.BOTTOM -> normalized(point.x - screen.x, screen.width - pill.width)
        }
        return Anchor(edge, offset)
    }

    fun pillPosition(anchor: Anchor, pill: Rect, screen: Rect): Point {
        val offset = anchor.offset.coerceIn(0f, 1f)
        val horizontal = screen.x + (offset * (screen.width - pill.width).coerceAtLeast(0)).roundToInt()
        val vertical = screen.y + (offset * (screen.height - pill.height).coerceAtLeast(0)).roundToInt()
        return when (anchor.edge) {
            Edge.LEFT -> Point(screen.x, vertical)
            Edge.TOP -> Point(horizontal, screen.y)
            Edge.RIGHT -> Point(screen.right - pill.width, vertical)
            Edge.BOTTOM -> Point(horizontal, screen.bottom - pill.height)
        }.let { clampPill(it, pill, screen) }
    }

    fun panelPosition(edge: Edge, pill: Rect, panel: Rect, screen: Rect, margin: Int): Point {
        val safeMargin = margin.coerceAtLeast(0)
        val desired = when (edge) {
            Edge.LEFT -> Point(pill.right + safeMargin, pill.centerY - panel.height / 2)
            Edge.TOP -> Point(pill.centerX - panel.width / 2, pill.bottom + safeMargin)
            Edge.RIGHT -> Point(pill.x - safeMargin - panel.width, pill.centerY - panel.height / 2)
            Edge.BOTTOM -> Point(pill.centerX - panel.width / 2, pill.y - safeMargin - panel.height)
        }
        return Point(
            clampPanelAxis(desired.x, screen.x, screen.right, panel.width, safeMargin),
            clampPanelAxis(desired.y, screen.y, screen.bottom, panel.height, safeMargin),
        )
    }

    fun clampPill(point: Point, pill: Rect, screen: Rect): Point = Point(
        point.x.coerceIn(screen.x, (screen.right - pill.width).coerceAtLeast(screen.x)),
        point.y.coerceIn(screen.y, (screen.bottom - pill.height).coerceAtLeast(screen.y)),
    )

    private fun normalized(value: Int, span: Int): Float =
        if (span <= 0) 0f else (value.toFloat() / span).coerceIn(0f, 1f)

    private fun clampPanelAxis(value: Int, start: Int, end: Int, size: Int, margin: Int): Int {
        val insetMin = start + margin
        val insetMax = end - margin - size
        if (insetMax >= insetMin) return value.coerceIn(insetMin, insetMax)
        return value.coerceIn(start, (end - size).coerceAtLeast(start))
    }
}
