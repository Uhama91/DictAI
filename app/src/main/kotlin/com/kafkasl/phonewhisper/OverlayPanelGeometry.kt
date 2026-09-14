package com.kafkasl.phonewhisper

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/** A display-independent rectangle used to remember a reduced transcript panel. */
internal data class NormalizedPanelRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** The four corners exposed by the reduced panel's resize affordances. */
internal enum class PanelResizeHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * Pure geometry for the reduced transcript window.
 *
 * The panel has a readable minimum, a conservative maximum, and never covers the pill when the
 * viewport has room for a separate rectangle. Coordinates are absolute display coordinates so the
 * same helper can be used for a full safe area and the shorter viewport above the IME.
 */
internal class OverlayPanelGeometry(
    private val minWidth: Int,
    private val minHeight: Int,
    private val maxWidthFraction: Float,
    private val maxHeightFraction: Float,
    private val gap: Int,
) {
    private data class Limits(val minWidth: Int, val maxWidth: Int, val minHeight: Int, val maxHeight: Int)

    fun standard(screen: Rect, pill: Rect, desiredWidth: Int, desiredHeight: Int): Rect {
        val limits = limits(screen)
        val width = desiredWidth.coerceIn(limits.minWidth, limits.maxWidth)
        val height = desiredHeight.coerceIn(limits.minHeight, limits.maxHeight)
        val edge = nearestEdge(pill, screen)
        val desired = OverlayPlacement.panelPosition(
            edge,
            pill,
            Rect(0, 0, width, height),
            screen,
            gap,
        )
        return bound(Rect(desired.x, desired.y, width, height), screen, pill)
    }

    fun move(rect: Rect, dx: Float, dy: Float, screen: Rect, pill: Rect): Rect = bound(
        rect.copy(x = (rect.x + dx).roundToInt(), y = (rect.y + dy).roundToInt()),
        screen,
        pill,
    )

    fun resize(
        rect: Rect,
        handle: PanelResizeHandle,
        dx: Float,
        dy: Float,
        screen: Rect,
        pill: Rect,
    ): Rect {
        val limits = limits(screen)
        val fromLeft = handle == PanelResizeHandle.TOP_LEFT || handle == PanelResizeHandle.BOTTOM_LEFT
        val fromTop = handle == PanelResizeHandle.TOP_LEFT || handle == PanelResizeHandle.TOP_RIGHT
        val width = (if (fromLeft) rect.width - dx else rect.width + dx)
            .roundToInt().coerceIn(limits.minWidth, limits.maxWidth)
        val height = (if (fromTop) rect.height - dy else rect.height + dy)
            .roundToInt().coerceIn(limits.minHeight, limits.maxHeight)
        val x = if (fromLeft) rect.right - width else rect.x
        val y = if (fromTop) rect.bottom - height else rect.y
        return bound(Rect(x, y, width, height), screen, pill)
    }

    fun bound(rect: Rect, screen: Rect, pill: Rect): Rect {
        val limits = limits(screen)
        val width = rect.width.coerceIn(limits.minWidth, limits.maxWidth)
        val height = rect.height.coerceIn(limits.minHeight, limits.maxHeight)
        val clamped = Rect(
            rect.x.coerceIn(screen.x, (screen.right - width).coerceAtLeast(screen.x)),
            rect.y.coerceIn(screen.y, (screen.bottom - height).coerceAtLeast(screen.y)),
            width,
            height,
        )
        return keepAwayFromPill(clamped, screen, pill)
    }

    /** Reserve the pointer strip on the side facing the pill, shrinking only when necessary. */
    fun reservePointer(rect: Rect, edge: Edge, screen: Rect, pointerLength: Int): Rect {
        val length = pointerLengthInside(rect, edge, screen, pointerLength)
        if (length == 0) return rect
        val x = when (edge) {
            Edge.LEFT -> clampAxis(
                rect.x,
                screen.x + length,
                screen.right - rect.width.coerceAtMost((screen.width - length).coerceAtLeast(1)),
            )
            Edge.RIGHT -> clampAxis(
                rect.x,
                screen.x,
                screen.right - length - rect.width.coerceAtMost((screen.width - length).coerceAtLeast(1)),
            )
            Edge.TOP, Edge.BOTTOM -> rect.x
        }
        val y = when (edge) {
            Edge.TOP -> clampAxis(
                rect.y,
                screen.y + length,
                screen.bottom - rect.height.coerceAtMost((screen.height - length).coerceAtLeast(1)),
            )
            Edge.BOTTOM -> clampAxis(
                rect.y,
                screen.y,
                screen.bottom - length - rect.height.coerceAtMost((screen.height - length).coerceAtLeast(1)),
            )
            Edge.LEFT, Edge.RIGHT -> rect.y
        }
        val width = if (edge == Edge.LEFT || edge == Edge.RIGHT) {
            rect.width.coerceAtMost((screen.width - length).coerceAtLeast(1))
        } else rect.width
        val height = if (edge == Edge.TOP || edge == Edge.BOTTOM) {
            rect.height.coerceAtMost((screen.height - length).coerceAtLeast(1))
        } else rect.height
        return rect.copy(x = x, y = y, width = width, height = height)
    }

    /** Maximum pointer strip that can fit beside [rect] without leaving [screen]. */
    fun pointerLengthInside(rect: Rect, edge: Edge, screen: Rect, pointerLength: Int): Int {
        val requested = pointerLength.coerceAtLeast(0)
        val available = when (edge) {
            Edge.LEFT, Edge.RIGHT -> screen.width - rect.width
            Edge.TOP, Edge.BOTTOM -> screen.height - rect.height
        }
        return requested.coerceAtMost(available.coerceAtLeast(0))
    }

    fun normalize(rect: Rect, screen: Rect): NormalizedPanelRect {
        val width = screen.width.coerceAtLeast(1).toFloat()
        val height = screen.height.coerceAtLeast(1).toFloat()
        return NormalizedPanelRect(
            x = ((rect.x - screen.x) / width).coerceIn(0f, 1f),
            y = ((rect.y - screen.y) / height).coerceIn(0f, 1f),
            width = (rect.width / width).coerceIn(0f, 1f),
            height = (rect.height / height).coerceIn(0f, 1f),
        )
    }

    fun denormalize(saved: NormalizedPanelRect, screen: Rect, pill: Rect): Rect {
        val raw = Rect(
            x = screen.x + (saved.x.coerceIn(0f, 1f) * screen.width).roundToInt(),
            y = screen.y + (saved.y.coerceIn(0f, 1f) * screen.height).roundToInt(),
            width = (saved.width.coerceIn(0f, 1f) * screen.width).roundToInt(),
            height = (saved.height.coerceIn(0f, 1f) * screen.height).roundToInt(),
        )
        return bound(raw, screen, pill)
    }

    /** Edge of the panel that faces the pill, used to orient the decorative pointer. */
    fun pointerEdge(panel: Rect, pill: Rect): Edge {
        if (pill.right <= panel.x) return Edge.LEFT
        if (pill.x >= panel.right) return Edge.RIGHT
        if (pill.bottom <= panel.y) return Edge.TOP
        if (pill.y >= panel.bottom) return Edge.BOTTOM
        val distances = listOf(
            Edge.LEFT to abs(pill.centerX - panel.x),
            Edge.RIGHT to abs(pill.centerX - panel.right),
            Edge.TOP to abs(pill.centerY - panel.y),
            Edge.BOTTOM to abs(pill.centerY - panel.bottom),
        )
        return distances.minBy { it.second }.first
    }

    private fun limits(screen: Rect): Limits {
        val maxWidth = (screen.width * maxWidthFraction).roundToInt().coerceIn(1, screen.width.coerceAtLeast(1))
        val maxHeight = (screen.height * maxHeightFraction).roundToInt().coerceIn(1, screen.height.coerceAtLeast(1))
        // On a very narrow or short viewport the readable minimum is necessarily reduced to the
        // largest rectangle that still satisfies the percentage cap.
        return Limits(
            minWidth = minWidth.coerceIn(1, maxWidth),
            maxWidth = maxWidth,
            minHeight = minHeight.coerceIn(1, maxHeight),
            maxHeight = maxHeight,
        )
    }

    private fun nearestEdge(pill: Rect, screen: Rect): Edge = listOf(
        Edge.LEFT to pill.x - screen.x,
        Edge.TOP to pill.y - screen.y,
        Edge.RIGHT to screen.right - pill.right,
        Edge.BOTTOM to screen.bottom - pill.bottom,
    ).minBy { it.second }.first

    private fun keepAwayFromPill(rect: Rect, screen: Rect, pill: Rect): Rect {
        if (!intersects(rect, pill)) return rect
        val leftRoom = (pill.x - gap - screen.x).coerceAtLeast(0)
        val rightRoom = (screen.right - pill.right - gap).coerceAtLeast(0)
        val topRoom = (pill.y - gap - screen.y).coerceAtLeast(0)
        val bottomRoom = (screen.bottom - pill.bottom - gap).coerceAtLeast(0)
        // If a very large panel overlaps the pill, reduce only the dimension that blocks each
        // candidate side. This preserves the pill's touch target even when no full-size side is
        // wide/tall enough (for example on a short IME viewport).
        val candidates = listOfNotNull(
            leftRoom.takeIf { it > 0 }?.let { room ->
                val width = rect.width.coerceAtMost(room)
                rect.copy(x = pill.x - gap - width, width = width)
            },
            rightRoom.takeIf { it > 0 }?.let { room ->
                val width = rect.width.coerceAtMost(room)
                rect.copy(x = pill.right + gap, width = width)
            },
            topRoom.takeIf { it > 0 }?.let { room ->
                val height = rect.height.coerceAtMost(room)
                rect.copy(y = pill.y - gap - height, height = height)
            },
            bottomRoom.takeIf { it > 0 }?.let { room ->
                val height = rect.height.coerceAtMost(room)
                rect.copy(y = pill.bottom + gap, height = height)
            },
        ).map { candidate ->
            candidate.copy(
                x = candidate.x.coerceIn(screen.x, (screen.right - candidate.width).coerceAtLeast(screen.x)),
                y = candidate.y.coerceIn(screen.y, (screen.bottom - candidate.height).coerceAtLeast(screen.y)),
            )
        }
        return candidates
            .filter { !intersects(it, pill) }
            .minByOrNull { abs(it.x - rect.x) + abs(it.y - rect.y) }
            ?: rect.copy(
                width = rect.width.coerceAtMost((screen.width - 1).coerceAtLeast(1)),
                height = rect.height.coerceAtMost((screen.height - 1).coerceAtLeast(1)),
            )
    }

    private fun intersects(first: Rect, second: Rect): Boolean =
        first.x < second.right && first.right > second.x &&
            first.y < second.bottom && first.bottom > second.y

    private fun clampAxis(value: Int, min: Int, max: Int): Int =
        if (max >= min) value.coerceIn(min, max) else max
}

/** Persistence kept separate from the existing pill preferences to avoid changing their schema. */
internal class OverlayPanelPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("dictai_overlay_panel", Context.MODE_PRIVATE)

    fun load(): NormalizedPanelRect? {
        if (!prefs.contains(KEY_X) || !prefs.contains(KEY_Y) ||
            !prefs.contains(KEY_WIDTH) || !prefs.contains(KEY_HEIGHT)
        ) return null
        val value = NormalizedPanelRect(
            prefs.getFloat(KEY_X, Float.NaN),
            prefs.getFloat(KEY_Y, Float.NaN),
            prefs.getFloat(KEY_WIDTH, Float.NaN),
            prefs.getFloat(KEY_HEIGHT, Float.NaN),
        )
        return value.takeIf {
            it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite() &&
                it.x in 0f..1f && it.y in 0f..1f && it.width in 0f..1f && it.height in 0f..1f
        }
    }

    fun save(value: NormalizedPanelRect) {
        prefs.edit()
            .putFloat(KEY_X, value.x.coerceIn(0f, 1f))
            .putFloat(KEY_Y, value.y.coerceIn(0f, 1f))
            .putFloat(KEY_WIDTH, value.width.coerceIn(0f, 1f))
            .putFloat(KEY_HEIGHT, value.height.coerceIn(0f, 1f))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_X).remove(KEY_Y).remove(KEY_WIDTH).remove(KEY_HEIGHT).apply()
    }

    companion object {
        private const val KEY_X = "panel_x"
        private const val KEY_Y = "panel_y"
        private const val KEY_WIDTH = "panel_width"
        private const val KEY_HEIGHT = "panel_height"
    }
}
