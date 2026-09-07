package com.kafkasl.phonewhisper

import kotlin.math.abs

/** Select a deliberate vertical shortcut only on release; a held or redirected drag stays a drag. */
internal class VerticalSwipeGesture(
    private val touchSlop: Float,
    private val minDistance: Float,
    private val maxDurationMs: Long = 220,
    private val direction: Direction = Direction.UP,
) {
    enum class Direction { UP, DOWN }
    private var startedAt = 0L
    private var eligible = false
    private fun distance(dy: Float): Float = if (direction == Direction.UP) -dy else dy

    fun begin(eventTime: Long, enabled: Boolean) {
        startedAt = eventTime
        eligible = enabled
    }

    fun cancel() { eligible = false }

    fun move(dx: Float, dy: Float, eventTime: Long) {
        if (eventTime - startedAt !in 0..maxDurationMs) eligible = false
        // Never steal a drag which first went sideways or in the opposite direction.
        if (abs(dx) + abs(dy) > touchSlop && distance(dy) < 2 * abs(dx)) eligible = false
    }

    fun release(dx: Float, dy: Float, eventTime: Long): Boolean {
        move(dx, dy, eventTime)
        val select = eligible && distance(dy) >= minDistance && distance(dy) >= 2 * abs(dx)
        cancel()
        return select
    }
}
