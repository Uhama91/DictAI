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
    private data class SweepCollision(val time: Double)

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

    /** Move without changing the panel dimensions, stopping at the first collision. */
    fun move(rect: Rect, dx: Float, dy: Float, screen: Rect, pill: Rect): Rect {
        val current = fitInside(rect, screen)
        if (intersects(current, inflatedPill(pill))) return keepAwayFromPill(current, screen, pill)
        val target = fitInside(
            current.copy(
                x = (current.x + dx).roundToInt(),
                y = (current.y + dy).roundToInt(),
            ),
            screen,
        )
        val obstacle = inflatedPill(pill)
        val hit = firstCollision(current, target, obstacle) ?: return target
        val safeTime = (hit.time - 0.000001).coerceAtLeast(0.0)
        // The epsilon and four-edge interpolation keep the panel on the original trajectory while
        // rounding to the last safe integer position. Do not infer a collision normal here: an axis
        // can already overlap at t=0 while the other one is merely touching.
        return current.copy(
            x = (current.x + (target.x - current.x) * safeTime).roundToInt(),
            y = (current.y + (target.y - current.y) * safeTime).roundToInt(),
        )
    }

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
        var width = (if (fromLeft) rect.width - dx else rect.width + dx)
            .roundToInt().coerceIn(limits.minWidth, limits.maxWidth)
        var height = (if (fromTop) rect.height - dy else rect.height + dy)
            .roundToInt().coerceIn(limits.minHeight, limits.maxHeight)
        val fixedLeft = rect.x
        val fixedRight = rect.right
        val fixedTop = rect.y
        val fixedBottom = rect.bottom
        if (fromLeft) {
            width = width.coerceAtMost((fixedRight - screen.x).coerceAtLeast(1))
        } else {
            width = width.coerceAtMost((screen.right - fixedLeft).coerceAtLeast(1))
        }
        if (fromTop) {
            height = height.coerceAtMost((fixedBottom - screen.y).coerceAtLeast(1))
        } else {
            height = height.coerceAtMost((screen.bottom - fixedTop).coerceAtLeast(1))
        }
        val candidate = Rect(
            x = if (fromLeft) fixedRight - width else fixedLeft,
            y = if (fromTop) fixedBottom - height else fixedTop,
            width = width,
            height = height,
        )
        return constrainResizeCollision(candidate, rect, handle, screen, pill)
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

    /**
     * Keep a restored rectangle away from the pill while preserving its size whenever one of the
     * four sides can contain it. Only when no full-size side fits do we choose the largest readable
     * side, so a nearby short strip cannot win merely because it is closest.
     */
    private fun keepAwayFromPill(rect: Rect, screen: Rect, pill: Rect): Rect {
        val obstacle = inflatedPill(pill)
        if (!intersects(rect, obstacle)) return rect

        data class Candidate(val rect: Rect, val area: Long, val distance: Int)
        fun inside(candidate: Rect): Boolean =
            candidate.x >= screen.x && candidate.y >= screen.y &&
                candidate.right <= screen.right && candidate.bottom <= screen.bottom &&
                !intersects(candidate, obstacle)
        // Keep the original size first. This is the common path after a rotation or a persisted
        // panel collision and avoids changing readable content just to choose a nearby side.
        val originalX = rect.x
        val originalY = rect.y
        val sameSize = listOf(
            rect.copy(x = obstacle.x - rect.width),
            rect.copy(x = obstacle.right),
            rect.copy(y = obstacle.y - rect.height),
            rect.copy(y = obstacle.bottom),
        ).filter(::inside)
        if (sameSize.isNotEmpty()) {
            return sameSize.minWithOrNull(
                compareBy<Rect> { abs(it.x - originalX) + abs(it.y - originalY) },
            ) ?: rect
        }

        val leftRoom = (obstacle.x - screen.x).coerceAtLeast(0)
        val rightRoom = (screen.right - obstacle.right).coerceAtLeast(0)
        val topRoom = (obstacle.y - screen.y).coerceAtLeast(0)
        val bottomRoom = (screen.bottom - obstacle.bottom).coerceAtLeast(0)
        // No full-size side fits. Preserve the largest surface, keeping the other dimension intact
        // where possible so an IME-height panel does not become a title-only horizontal strip.
        val resized = listOfNotNull(
            leftRoom.takeIf { it > 0 }?.let { room ->
                rect.copy(x = obstacle.x - rect.width.coerceAtMost(room), width = rect.width.coerceAtMost(room))
            },
            rightRoom.takeIf { it > 0 }?.let { room ->
                rect.copy(x = obstacle.right, width = rect.width.coerceAtMost(room))
            },
            topRoom.takeIf { it > 0 }?.let { room ->
                rect.copy(y = obstacle.y - rect.height.coerceAtMost(room), height = rect.height.coerceAtMost(room))
            },
            bottomRoom.takeIf { it > 0 }?.let { room ->
                rect.copy(y = obstacle.bottom, height = rect.height.coerceAtMost(room))
            },
        ).map { candidate ->
            candidate.copy(
                x = candidate.x.coerceIn(screen.x, (screen.right - candidate.width).coerceAtLeast(screen.x)),
                y = candidate.y.coerceIn(screen.y, (screen.bottom - candidate.height).coerceAtLeast(screen.y)),
            )
        }.filter(::inside).map { candidate ->
            Candidate(
                rect = candidate,
                area = candidate.width.toLong() * candidate.height.toLong(),
                distance = abs(candidate.x - originalX) + abs(candidate.y - originalY),
            )
        }
        val readable = resized.filter { candidate ->
            candidate.rect.width >= limits(screen).minWidth && candidate.rect.height >= limits(screen).minHeight
        }
        return (readable.ifEmpty { resized })
            .sortedWith(compareByDescending<Candidate> { it.area }.thenBy { it.distance })
            .firstOrNull()?.rect ?: rect
    }

    private fun inflatedPill(pill: Rect): Rect = Rect(
        pill.x - gap,
        pill.y - gap,
        pill.width + 2 * gap,
        pill.height + 2 * gap,
    )

    private fun fitInside(rect: Rect, screen: Rect): Rect = rect.copy(
        x = rect.x.coerceIn(screen.x, (screen.right - rect.width).coerceAtLeast(screen.x)),
        y = rect.y.coerceIn(screen.y, (screen.bottom - rect.height).coerceAtLeast(screen.y)),
    )

    private fun constrainResizeCollision(
        candidate: Rect,
        original: Rect,
        handle: PanelResizeHandle,
        screen: Rect,
        pill: Rect,
    ): Rect {
        val obstacle = inflatedPill(pill)
        if (!intersects(original, obstacle)) {
            val hit = firstCollision(original, candidate, obstacle)
            if (hit == null) return fitInside(candidate, screen)
            val safeTime = (hit.time - 0.000001).coerceAtLeast(0.0)
            val atHit = interpolate(original, candidate, safeTime)
            return fitInside(atHit, screen)
        }
        return keepAwayFromPill(candidate, screen, pill)
    }

    /** Earliest t in [0, 1] for which the swept rectangles overlap. */
    private fun firstCollision(start: Rect, end: Rect, obstacle: Rect): SweepCollision? {
        fun axisInterval(
            startMin: Int,
            startMax: Int,
            endMin: Int,
            endMax: Int,
            obstacleMin: Int,
            obstacleMax: Int,
        ): Pair<Double, Double>? {
            var lower = 0.0
            var upper = 1.0

            fun applyLess(startValue: Int, endValue: Int, threshold: Int): Boolean {
                val delta = (endValue - startValue).toDouble()
                if (delta == 0.0) return startValue < threshold
                val crossing = (threshold - startValue) / delta
                if (delta > 0.0) upper = minOf(upper, crossing) else lower = maxOf(lower, crossing)
                return lower < upper
            }

            fun applyGreater(startValue: Int, endValue: Int, threshold: Int): Boolean {
                val delta = (endValue - startValue).toDouble()
                if (delta == 0.0) return startValue > threshold
                val crossing = (threshold - startValue) / delta
                if (delta > 0.0) lower = maxOf(lower, crossing) else upper = minOf(upper, crossing)
                return lower < upper
            }

            // min < obstacleMax and max > obstacleMin, with strict bounds so touching is safe.
            if (!applyLess(startMin, endMin, obstacleMax)) return null
            if (!applyGreater(startMax, endMax, obstacleMin)) return null
            return (lower to upper).takeIf { lower < upper && upper > 0.0 && lower < 1.0 }
        }

        val x = axisInterval(start.x, start.right, end.x, end.right, obstacle.x, obstacle.right)
            ?: return null
        val y = axisInterval(start.y, start.bottom, end.y, end.bottom, obstacle.y, obstacle.bottom)
            ?: return null
        val entry = maxOf(x.first, y.first)
        val exit = minOf(x.second, y.second)
        return if (entry < exit && exit > 0.0 && entry < 1.0) {
            SweepCollision(entry)
        } else null
    }

    private fun interpolate(start: Rect, end: Rect, fraction: Double): Rect {
        val left = (start.x + (end.x - start.x) * fraction).roundToInt()
        val top = (start.y + (end.y - start.y) * fraction).roundToInt()
        val right = (start.right + (end.right - start.right) * fraction).roundToInt()
        val bottom = (start.bottom + (end.bottom - start.bottom) * fraction).roundToInt()
        return Rect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
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
