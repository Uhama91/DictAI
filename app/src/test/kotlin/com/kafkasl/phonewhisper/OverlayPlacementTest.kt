package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {
    private val portrait = Rect(0, 0, 1080, 1920)
    private val pill = Rect(0, 0, 74, 44)

    @Test fun `snap selects each closest edge with a normalized axis offset`() {
        assertAnchor(Edge.LEFT, 900f / 1876f, OverlayPlacement.snap(Point(0, 900), pill, portrait))
        assertAnchor(Edge.TOP, 500f / 1006f, OverlayPlacement.snap(Point(500, 0), pill, portrait))
        assertAnchor(Edge.RIGHT, 900f / 1876f, OverlayPlacement.snap(Point(1006, 900), pill, portrait))
        assertAnchor(Edge.BOTTOM, 500f / 1006f, OverlayPlacement.snap(Point(500, 1876), pill, portrait))
    }

    @Test fun `snap resolves an equal left top distance toward left`() {
        assertAnchor(Edge.LEFT, 0f, OverlayPlacement.snap(Point(0, 0), pill, portrait))
    }

    @Test fun `pill position retains every edge and clamps the normalized offset`() {
        assertEquals(Point(0, 938), OverlayPlacement.pillPosition(Anchor(Edge.LEFT, .5f), pill, portrait))
        assertEquals(Point(1006, 938), OverlayPlacement.pillPosition(Anchor(Edge.RIGHT, .5f), pill, portrait))
        assertEquals(Point(503, 0), OverlayPlacement.pillPosition(Anchor(Edge.TOP, .5f), pill, portrait))
        assertEquals(Point(503, 1876), OverlayPlacement.pillPosition(Anchor(Edge.BOTTOM, .5f), pill, portrait))
        assertEquals(Point(1006, 0), OverlayPlacement.pillPosition(Anchor(Edge.TOP, 4f), pill, portrait))
    }

    @Test fun `panel stays inside the screen on the interior side of every edge`() {
        val panel = Rect(0, 0, 312, 80)

        assertEquals(Point(82, 920), OverlayPlacement.panelPosition(Edge.LEFT, Rect(0, 938, 74, 44), panel, portrait, 8))
        assertEquals(Point(686, 920), OverlayPlacement.panelPosition(Edge.RIGHT, Rect(1006, 938, 74, 44), panel, portrait, 8))
        assertEquals(Point(384, 52), OverlayPlacement.panelPosition(Edge.TOP, Rect(503, 0, 74, 44), panel, portrait, 8))
        assertEquals(Point(384, 1788), OverlayPlacement.panelPosition(Edge.BOTTOM, Rect(503, 1876, 74, 44), panel, portrait, 8))
    }

    @Test fun `rotation reuses anchor edge and relative position`() {
        val landscape = Rect(0, 0, 1920, 1080)
        val anchored = Anchor(Edge.RIGHT, .5f)

        assertEquals(Point(1846, 518), OverlayPlacement.pillPosition(anchored, pill, landscape))
    }

    @Test fun `safe area offsets drag pill and panel clamping`() {
        val safeScreen = Rect(24, 48, 1032, 1760)
        val panel = Rect(0, 0, 312, 80)

        assertEquals(Point(24, 1764), OverlayPlacement.clampPill(Point(-500, 2000), pill, safeScreen))
        assertEquals(Point(982, 906), OverlayPlacement.pillPosition(Anchor(Edge.RIGHT, .5f), pill, safeScreen))
        assertEquals(
            Point(736, 100),
            OverlayPlacement.panelPosition(Edge.TOP, Rect(982, 48, 74, 44), panel, safeScreen, 8),
        )
    }

    private fun assertAnchor(edge: Edge, offset: Float, actual: Anchor) {
        assertEquals(edge, actual.edge)
        assertEquals(offset, actual.offset, .0001f)
    }
}
