package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class VerticalSwipeGestureTest {
    private fun gesture(enabled: Boolean = true) = VerticalSwipeGesture(8f, 56f).apply {
        begin(1000, enabled)
    }

    @Test fun `deliberate upward flick selects only once on release`() {
        val gesture = gesture()
        gesture.move(2f, -15f, 1040)
        gesture.move(5f, -65f, 1120)
        assertTrue(gesture.release(5f, -70f, 1150))
        assertFalse(gesture.release(5f, -70f, 1160))
    }
    @Test fun `small upward adjustment does not select formats`() {
        assertFalse(gesture().release(0f, -20f, 1100))
        assertFalse(gesture().release(0f, -55f, 1100))
    }
    @Test fun `slow upward repositioning remains a drag`() {
        val gesture = gesture()
        gesture.move(0f, -20f, 1100)
        gesture.move(0f, -90f, 1300)
        assertFalse(gesture.release(0f, -120f, 1500))
    }
    @Test fun `fast move then rest at destination stays a drag`() {
        val gesture = gesture()
        gesture.move(0f, -80f, 1100)
        assertFalse(gesture.release(0f, -80f, 1350))
    }
    @Test fun `duration boundary excludes slower movement`() {
        assertTrue(gesture().release(0f, -56f, 1220))
        assertFalse(gesture().release(0f, -100f, 1221))
    }
    @Test fun `diagonal horizontal and downward drags do not select formats`() {
        for ((dx, dy) in listOf(60f to -70f, -60f to -70f, 80f to 0f, 0f to 80f)) {
            assertFalse(gesture().release(dx, dy, 1150))
        }
    }
    @Test fun `redirecting sideways or downward cannot steal a drag`() {
        for ((dx, dy) in listOf(20f to 0f, 0f to 20f)) {
            val gesture = gesture()
            gesture.move(dx, dy, 1040)
            assertFalse(gesture.release(0f, -100f, 1160))
        }
    }
    @Test fun `initial finger jitter does not prevent a flick`() {
        val gesture = gesture()
        gesture.move(2f, 2f, 1020)
        assertTrue(gesture.release(3f, -70f, 1160))
    }
    @Test fun `hold cancellation and additional pointers disable shortcut`() {
        val gesture = gesture()
        gesture.cancel()
        assertFalse(gesture.release(0f, -90f, 1150))
    }
    @Test fun `disabled surfaces cannot trigger shortcut`() {
        assertFalse(gesture(false).release(0f, -90f, 1150))
    }
    @Test fun `new touch resets previous rejection`() {
        val gesture = gesture()
        gesture.move(50f, 0f, 1050)
        assertFalse(gesture.release(0f, -90f, 1150))
        gesture.begin(2000, true)
        assertTrue(gesture.release(0f, -90f, 2150))
    }
    @Test fun `final coordinates suffice with sparse move events`() {
        assertTrue(gesture().release(0f, -80f, 1120))
    }
    @Test fun `distance scales with density`() {
        for (density in listOf(1f, 2f, 3f)) {
            val gesture = VerticalSwipeGesture(8f * density, 56f * density)
            gesture.begin(1000, true)
            assertFalse(gesture.release(0f, -20f * density, 1150))
            gesture.begin(2000, true)
            assertTrue(gesture.release(4f * density, -70f * density, 2150))
        }
    }
    @Test fun `downward pause accepts brief descent but rejects slow drag and ascent`() {
        val gesture = VerticalSwipeGesture(8f, 56f, 350, VerticalSwipeGesture.Direction.DOWN)
        gesture.begin(1000, true)
        assertTrue(gesture.release(5f, 80f, 1300))
        gesture.begin(2000, true)
        assertFalse(gesture.release(0f, 80f, 2500))
        gesture.begin(3000, true)
        assertFalse(gesture.release(0f, -80f, 3200))
    }
}
