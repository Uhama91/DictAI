package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class FloatingMenuPlacementTest {
    @Test fun `menus stay visible at corners and centered anchors`() {
        val screen = Rect(0, 24, 400, 760)
        for (edge in Edge.values()) for (offset in listOf(0f, .5f, 1f)) for (above in listOf(false, true)) {
            val point = OverlayPlacement.pillPosition(Anchor(edge, offset), Rect(0, 0, 74, 44), screen)
            val position = FloatingMenuPlacement.position(Rect(point.x, point.y, 74, 44), screen, 300, 300, 6, above)
            assertTrue(position.x >= screen.x && position.y >= screen.y)
            assertTrue(position.x + 300 <= screen.right && position.y + 300 <= screen.bottom)
        }
    }
    @Test fun `large menus shrink instead of covering the pill`() {
        val screen = Rect(0, 24, 400, 760)
        for (edge in Edge.values()) for (offset in listOf(0f, .5f, 1f)) for (above in listOf(false, true)) {
            val p = OverlayPlacement.pillPosition(Anchor(edge, offset), Rect(0, 0, 74, 44), screen)
            val pill = Rect(p.x, p.y, 74, 44)
            val menu = FloatingMenuPlacement.bounds(pill, screen, 320, 500, 6, above)
            assertFalse(menu.x < pill.right && menu.right > pill.x && menu.y < pill.bottom && menu.bottom > pill.y)
        }
    }
    @Test fun `format menu is above pill when space is available`() {
        assertEquals(394, FloatingMenuPlacement.position(Rect(170, 600, 74, 44), Rect(0, 0, 400, 800), 300, 200, 6, true).y)
    }
    @Test fun `left swipe is recognized using rotated coordinates without becoming vertical shortcut`() {
        val left = VerticalSwipeGesture(8f, 24f)
        left.begin(true)
        assertTrue(left.release(2f, -30f))
        left.begin(true)
        left.move(20f, 0f)
        assertFalse(left.release(0f, -50f))
    }
}
