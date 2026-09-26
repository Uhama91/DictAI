package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RightSwipeBackGestureTest {
    private fun gesture() = RightSwipeBackGesture(
        touchSlop = 8f,
        minimumDistance = 24f,
        maximumDistance = 56f,
    )

    @Test
    fun availableRunwayAtRightEdgeLowersThresholdButKeepsItsSafetyFloor() {
        val gesture = gesture()
        gesture.begin(downX = 963f, rightBoundary = 1_000f)
        assertFalse(gesture.release(dx = 23f, dy = 0f))
        gesture.begin(downX = 963f, rightBoundary = 1_000f)
        assertTrue(gesture.release(dx = 24f, dy = 0f))

        gesture.begin(downX = 990f, rightBoundary = 1_000f)
        assertFalse("less than the safety floor of rightward runway cannot arm", gesture.release(40f, 0f))
    }

    @Test
    fun onlyLongHorizontallyDominantRightReleaseNavigates() {
        val gesture = gesture()
        gesture.begin(downX = 100f, rightBoundary = 300f)
        assertFalse(gesture.release(dx = 23f, dy = 0f))

        gesture.begin(downX = 100f, rightBoundary = 300f)
        assertFalse(gesture.release(dx = -80f, dy = 0f))

        gesture.begin(downX = 100f, rightBoundary = 300f)
        assertFalse(gesture.release(dx = 80f, dy = 60f))

        gesture.begin(downX = 100f, rightBoundary = 300f)
        assertFalse("returning to the start leaves no qualifying displacement", gesture.release(dx = 5f, dy = 0f))

        gesture.begin(downX = 100f, rightBoundary = 300f)
        assertTrue(gesture.release(dx = 56f, dy = 10f))
    }

    @Test
    fun cancelDisarmsGestureAndHorizontalAttemptsCanBeCapturedWithoutInterceptingVerticalScroll() {
        val gesture = gesture()
        gesture.begin(downX = 100f, rightBoundary = 300f)
        assertTrue(gesture.shouldCaptureHorizontal(dx = -18f, dy = 1f))
        assertFalse(gesture.shouldCaptureHorizontal(dx = 1f, dy = 18f))
        gesture.cancel()
        assertFalse(gesture.release(dx = 80f, dy = 0f))
    }
}
