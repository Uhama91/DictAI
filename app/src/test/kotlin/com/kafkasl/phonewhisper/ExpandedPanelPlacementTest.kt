package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class ExpandedPanelPlacementTest {
    @Test fun `absolute ime top is used without subtracting the safe navigation inset twice`() {
        val full = Rect(0, 24, 400, 760)

        val visible = OverlayPlacement.screenAboveKeyboard(full, imeTop = 484)

        assertEquals(484, visible.bottom)
        assertEquals(full.x, visible.x)
        assertEquals(full.width, visible.width)
    }

    @Test fun `visible display frame is used when an overlay receives no ime inset`() {
        val full = Rect(0, 24, 400, 760)

        val visible = OverlayPlacement.screenAboveKeyboard(full, visibleFrameBottom = 460)

        assertEquals(460, visible.bottom)
    }

    @Test fun `stale frame outside the display cannot move the panel outside safe bounds`() {
        val full = Rect(0, 24, 400, 760)

        val visible = OverlayPlacement.screenAboveKeyboard(full, visibleFrameBottom = 1)

        assertEquals(full.bottom, visible.bottom)
    }

    @Test fun `expanded panel stays above the keyboard in a short portrait viewport`() {
        val full = Rect(0, 24, 400, 760)
        val visible = OverlayPlacement.screenAboveKeyboard(full, imeTop = 484)
        val pill = Rect(0, 300, 74, 44)

        val panel = OverlayPlacement.panelBounds(
            Edge.BOTTOM, pill, visible, desiredWidth = 380, desiredHeight = visible.height, margin = 6,
        )

        assertTrue(panel.bottom <= visible.bottom)
        assertTrue(panel.y >= visible.y)
    }

    @Test fun `compact header hides lower priority rows before shrinking transcript viewport`() {
        val layout = OverlayPlacement.transcriptPanelLayout(
            panelHeight = 180,
            toolbarHeight = 48,
            formatHeight = 24,
            mediaHeight = 48,
            actionsHeight = 48,
            minimumTextHeight = 96,
        )

        assertTrue(layout.compact)
        assertFalse(layout.showMedia)
        assertFalse(layout.showActions)
        assertTrue(layout.showFormat)
        assertEquals(72, layout.transcriptTop)
    }

    @Test fun `expanded header keeps every row only when text still has room`() {
        val layout = OverlayPlacement.transcriptPanelLayout(
            panelHeight = 264,
            toolbarHeight = 48,
            formatHeight = 24,
            mediaHeight = 48,
            actionsHeight = 48,
            minimumTextHeight = 96,
        )

        assertFalse(layout.compact)
        assertTrue(layout.showMedia)
        assertTrue(layout.showActions)
        assertTrue(layout.showFormat)
        assertEquals(168, layout.transcriptTop)
    }

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
