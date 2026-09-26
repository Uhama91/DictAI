package com.kafkasl.phonewhisper

import kotlin.math.abs

/** A rightward, horizontally dominant release. The right edge limits the required runway. */
internal class RightSwipeBackGesture(
    private val touchSlop: Float,
    private val minimumDistance: Float,
    private val maximumDistance: Float,
) {
    private var armed = false
    private var horizontalIntent = false
    private var threshold = Float.POSITIVE_INFINITY

    val isRightwardEligible: Boolean
        get() = armed && !horizontalIntent

    init {
        require(touchSlop >= 0f)
        require(minimumDistance >= touchSlop)
        require(maximumDistance >= minimumDistance)
    }

    fun begin(downX: Float, rightBoundary: Float, enabled: Boolean = true) {
        val availableRight = rightBoundary - downX
        armed = enabled && availableRight >= minimumDistance
        horizontalIntent = false
        threshold = if (armed) {
            minOf(maximumDistance, maxOf(minimumDistance, availableRight * 0.6f))
        } else Float.POSITIVE_INFINITY
    }

    /** Captures either horizontal direction once slop is crossed so a failed swipe cannot click a row. */
    fun shouldCaptureHorizontal(dx: Float, dy: Float): Boolean =
        abs(dx) > touchSlop && abs(dx) >= 2f * abs(dy)

    fun progress(dx: Float, dy: Float): Float {
        if (!armed) return 0f
        if (abs(dx) + abs(dy) > touchSlop && (dx <= 0f || dx < 2f * abs(dy))) {
            horizontalIntent = true
        }
        return if (horizontalIntent) 0f else (dx / threshold).coerceIn(0f, 1f)
    }

    fun release(dx: Float, dy: Float): Boolean {
        val progress = progress(dx, dy)
        val releasedRight = armed && !horizontalIntent && dx >= threshold && dx >= 2f * abs(dy) && progress >= 1f
        cancel()
        return releasedRight
    }

    fun cancel() {
        armed = false
        horizontalIntent = true
        threshold = Float.POSITIVE_INFINITY
    }
}
