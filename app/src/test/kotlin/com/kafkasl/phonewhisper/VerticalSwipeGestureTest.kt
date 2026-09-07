package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class VerticalSwipeGestureTest {
    private fun gesture(enabled: Boolean = true) = VerticalSwipeGesture(8f, 56f).apply {
        begin(enabled)
    }

    @Test fun `deliberate upward flick selects only once on release`() {
        val gesture = gesture()
        gesture.move(2f, -15f)
        gesture.move(5f, -65f)
        assertTrue(gesture.release(5f, -70f))
        assertFalse(gesture.release(5f, -70f))
    }
    @Test fun `preview follows pull and retract`() {
        val gesture = gesture()
        assertEquals(0.5f, gesture.progress(0f, -28f), 0.001f)
        assertEquals(1f, gesture.progress(0f, -70f), 0.001f)
        assertEquals(0.5f, gesture.progress(0f, -28f), 0.001f)
        assertFalse(gesture.release(0f, -28f))
    }
    @Test fun `small upward adjustment does not select formats`() {
        assertFalse(gesture().release(0f, -20f))
        assertFalse(gesture().release(0f, -55f))
    }
    @Test fun `pull can stay at destination without expiring`() {
        val gesture = gesture()
        repeat(1000) { gesture.move(0f, -80f) }
        assertTrue(gesture.release(0f, -80f))
    }
    @Test fun `diagonal horizontal and downward drags do not select formats`() {
        for ((dx, dy) in listOf(60f to -70f, -60f to -70f, 80f to 0f, 0f to 80f)) {
            assertFalse(gesture().release(dx, dy))
        }
    }
    @Test fun `redirecting sideways or downward cannot steal a drag`() {
        for ((dx, dy) in listOf(20f to 0f, 0f to 20f)) {
            val gesture = gesture()
            gesture.move(dx, dy)
            assertFalse(gesture.release(0f, -100f))
        }
    }
    @Test fun `initial finger jitter does not prevent a flick`() {
        val gesture = gesture()
        gesture.move(2f, 2f)
        assertTrue(gesture.release(3f, -70f))
    }
    @Test fun `hold cancellation and additional pointers disable shortcut`() {
        val gesture = gesture()
        gesture.cancel()
        assertFalse(gesture.release(0f, -90f))
    }
    @Test fun `disabled surfaces cannot trigger shortcut`() {
        assertFalse(gesture(false).release(0f, -90f))
    }
    @Test fun `new touch resets previous rejection`() {
        val gesture = gesture()
        gesture.move(50f, 0f)
        assertFalse(gesture.release(0f, -90f))
        gesture.begin(true)
        assertTrue(gesture.release(0f, -90f))
    }
    @Test fun `final coordinates suffice with sparse move events`() {
        assertTrue(gesture().release(0f, -80f))
    }
    @Test fun `distance scales with density`() {
        for (density in listOf(1f, 2f, 3f)) {
            val gesture = VerticalSwipeGesture(8f * density, 56f * density)
            gesture.begin(true)
            assertFalse(gesture.release(0f, -20f * density))
            gesture.begin(true)
            assertTrue(gesture.release(4f * density, -70f * density))
        }
    }
    @Test fun `downward pause accepts descent and rejects ascent`() {
        val gesture = VerticalSwipeGesture(8f, 56f, VerticalSwipeGesture.Direction.DOWN)
        gesture.begin(true)
        assertTrue(gesture.release(5f, 80f))
        gesture.begin(true)
        assertTrue(gesture.release(0f, 80f))
        gesture.begin(true)
        assertFalse(gesture.release(0f, -80f))
    }
}
