package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class ExpandedPanelPlacementTest {
    @Test fun `expanded panel fits at every edge in portrait landscape and tablet`() {
        for (screen in listOf(Rect(0, 24, 400, 760), Rect(0, 24, 760, 376), Rect(0, 24, 1600, 2400))) {
            for (edge in Edge.values()) for (offset in listOf(0f, .5f, 1f)) {
                val point = OverlayPlacement.pillPosition(Anchor(edge, offset), Rect(0, 0, 74, 44), screen)
                val pill = Rect(point.x, point.y, 74, 44)
                val panel = OverlayPlacement.panelBounds(edge, pill, screen, 520, (screen.height * .65f).toInt(), 6)
                assertTrue(panel.x >= screen.x && panel.y >= screen.y)
                assertTrue(panel.right <= screen.right && panel.bottom <= screen.bottom)
                assertFalse(panel.x < pill.right && panel.right > pill.x && panel.y < pill.bottom && panel.bottom > pill.y)
            }
        }
    }
}
