package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class PillGestureModeTest {
    @Test fun `stationary hold arms movement and cannot become a shortcut`() {
        val mode = PillGestureMode(8f)
        assertTrue(mode.hold())
        mode.move(0f, -100f)
        assertEquals(PillGestureMode.Mode.DRAG, mode.mode)
        assertFalse(mode.hold())
    }
    @Test fun `direct swipe cannot become drag even when held at destination`() {
        val mode = PillGestureMode(8f)
        mode.move(0f, 20f)
        assertFalse(mode.hold())
        mode.move(0f, 100f)
        assertEquals(PillGestureMode.Mode.SHORTCUT, mode.mode)
    }
    @Test fun `finger jitter still allows tap or stationary hold`() {
        val mode = PillGestureMode(8f)
        mode.move(2f, 2f)
        assertEquals(PillGestureMode.Mode.WAITING, mode.mode)
        assertTrue(mode.hold())
    }
    @Test fun `cancel blocks delayed hold and next touch starts fresh`() {
        val mode = PillGestureMode(8f)
        mode.cancel()
        assertFalse(mode.hold())
        mode.begin()
        assertEquals(PillGestureMode.Mode.WAITING, mode.mode)
        assertTrue(mode.hold())
    }
    @Test fun `returning a swipe to origin never turns it into a tap`() {
        val mode = PillGestureMode(8f)
        mode.move(20f, 0f)
        mode.move(0f, 0f)
        assertEquals(PillGestureMode.Mode.SHORTCUT, mode.mode)
    }
}
