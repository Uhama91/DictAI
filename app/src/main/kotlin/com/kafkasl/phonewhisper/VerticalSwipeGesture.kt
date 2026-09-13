package com.kafkasl.phonewhisper

import kotlin.math.abs

/** A vertical pull selects on release, without a speed requirement. */
internal class VerticalSwipeGesture(
    private val touchSlop: Float,
    private val minDistance: Float,
    private val direction: Direction = Direction.UP,
) {
    enum class Direction { UP, DOWN }
    private var eligible = false
    private fun distance(dy: Float): Float = if (direction == Direction.UP) -dy else dy

    fun begin(enabled: Boolean) {
        eligible = enabled
    }

    fun cancel() { eligible = false }

    fun move(dx: Float, dy: Float) {
        // Never steal a drag which first went sideways or in the opposite direction.
        if (abs(dx) + abs(dy) > touchSlop && distance(dy) < 2 * abs(dx)) eligible = false
    }

    fun progress(dx: Float, dy: Float): Float {
        move(dx, dy)
        return if (eligible) (distance(dy) / minDistance).coerceIn(0f, 1f) else 0f
    }

    fun release(dx: Float, dy: Float): Boolean {
        move(dx, dy)
        val select = eligible && distance(dy) >= minDistance && distance(dy) >= 2 * abs(dx)
        cancel()
        return select
    }
}
