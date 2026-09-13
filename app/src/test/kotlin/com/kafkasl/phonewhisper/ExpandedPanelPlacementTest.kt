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

    @Test fun `bubble envelope preserves a gap from the real pill at every side`() {
        val pointerLength = 10
        val margin = pointerLength + 2
        for (screen in listOf(Rect(0, 24, 400, 760), Rect(0, 24, 760, 376), Rect(0, 24, 1600, 2400))) {
            for (edge in Edge.values()) for (offset in listOf(0f, .5f, 1f)) {
                val point = OverlayPlacement.pillPosition(Anchor(edge, offset), Rect(0, 0, 74, 44), screen)
                val pill = Rect(point.x, point.y, 74, 44)
                val selected = OverlayPlacement.viablePanelEdge(edge, pill, screen, 312, 280, margin)
                val body = OverlayPlacement.panelBounds(selected, pill, screen, 312, 280, margin)
                val envelope = OverlayPlacement.bubbleEnvelope(body, selected, pointerLength)
                assertTrue(envelope.window.x >= screen.x)
                assertTrue(envelope.window.y >= screen.y)
                assertTrue(envelope.window.right <= screen.right)
                assertTrue(envelope.window.bottom <= screen.bottom)
                assertFalse("Bubble body and pointer must not cover the pill", intersects(envelope.window, pill))
                when (selected) {
                    Edge.LEFT -> assertEquals(pointerLength, envelope.bodyOffsetX)
                    Edge.RIGHT -> assertEquals(0, envelope.bodyOffsetX)
                    Edge.TOP -> assertEquals(pointerLength, envelope.bodyOffsetY)
                    Edge.BOTTOM -> assertEquals(0, envelope.bodyOffsetY)
                }
            }
        }
    }

    @Test fun `viable panel edge falls back when the preferred side has no room`() {
        val screen = Rect(0, 0, 400, 760)
        val pill = Rect(0, 300, 74, 44)
        val selected = OverlayPlacement.viablePanelEdge(Edge.RIGHT, pill, screen, 312, 280, 12)

        assertTrue(selected != Edge.RIGHT)
        val body = OverlayPlacement.panelBounds(selected, pill, screen, 312, 280, 12)
        assertFalse(intersects(body, pill))
    }

    @Test fun `drag grid keeps compact and expanded bubble inside viewport without pill overlap`() {
        val fullScreens = listOf(
            Rect(0, 24, 400, 760),
            Rect(0, 24, 760, 376),
        )
        val pillSize = Rect(0, 0, 74, 44)
        val margin = 14
        val pointerLength = 12
        val panelSizes: List<Pair<Int, Int?>> = listOf(
            312 to 268, // compact editor: lineHeight * 4 + 188 at mdpi
            600 to null, // expanded editor: height is the safe viewport minus 12dp
        )

        for (full in fullScreens) {
            val imeTop = full.y + (full.height * .68f).toInt()
            val viewports = listOf(full, OverlayPlacement.screenAboveKeyboard(full, imeTop = imeTop))
            for (screen in viewports) {
                for (xFraction in listOf(.12f, .5f, .88f)) {
                    for (yFraction in listOf(.12f, .5f, .88f)) {
                        val raw = Point(
                            screen.x + (screen.width * xFraction).toInt(),
                            screen.y + (screen.height * yFraction).toInt(),
                        )
                        val pillPoint = OverlayPlacement.clampPill(raw, pillSize, screen)
                        val pill = Rect(pillPoint.x, pillPoint.y, pillSize.width, pillSize.height)
                        val preferred = OverlayPlacement.snap(pillPoint, pillSize, screen).edge

                        for ((desiredWidth, requestedHeight) in panelSizes) {
                            val desiredHeight = requestedHeight ?: (screen.height - 12).coerceAtLeast(1)
                            val selected = OverlayPlacement.viablePanelEdge(
                                preferred,
                                pill,
                                screen,
                                desiredWidth,
                                desiredHeight,
                                margin,
                            )
                            val body = OverlayPlacement.panelBounds(
                                selected,
                                pill,
                                screen,
                                desiredWidth,
                                desiredHeight,
                                margin,
                            )
                            val envelope = OverlayPlacement.bubbleEnvelope(body, selected, pointerLength).window

                            assertTrue("body escaped viewport: $full / $screen / $pill", inside(body, screen))
                            assertTrue("bubble escaped viewport: $full / $screen / $pill", inside(envelope, screen))
                            assertFalse("bubble covered dragged pill: $full / $screen / $pill", intersects(envelope, pill))
                            val gap = when (selected) {
                                Edge.LEFT -> envelope.x - pill.right
                                Edge.RIGHT -> pill.x - envelope.right
                                Edge.TOP -> envelope.y - pill.bottom
                                Edge.BOTTOM -> pill.y - envelope.bottom
                            }
                            assertTrue("bubble gap was lost: $full / $screen / $pill", gap >= 2)
                        }
                    }
                }
            }
        }
    }

    private fun intersects(first: Rect, second: Rect): Boolean =
        first.x < second.right && first.right > second.x && first.y < second.bottom && first.bottom > second.y

    private fun inside(inner: Rect, outer: Rect): Boolean =
        inner.x >= outer.x && inner.y >= outer.y && inner.right <= outer.right && inner.bottom <= outer.bottom
}
