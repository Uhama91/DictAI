package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPanelGeometryTest {
    private val screen = Rect(24, 48, 1000, 1800)
    private val pill = Rect(24, 900, 74, 44)

    @Test
    fun `standard reduced rectangle is bounded by screen and avoids pill`() {
        val geometry = OverlayPanelGeometry(
            minWidth = 240,
            minHeight = 160,
            maxWidthFraction = .92f,
            maxHeightFraction = .75f,
            gap = 12,
        )

        val standard = geometry.standard(screen, pill, desiredWidth = 312, desiredHeight = 268)

        assertTrue(inside(standard, screen))
        assertTrue(!intersects(standard, pill))
        assertEquals(312, standard.width)
        assertEquals(268, standard.height)
    }

    @Test
    fun `drag clamps panel inside viewport and keeps it away from pill`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val current = Rect(100, 100, 312, 268)

        val moved = geometry.move(current, dx = 900f, dy = 1000f, screen = screen, pill = pill)

        assertTrue(inside(moved, screen))
        assertTrue(!intersects(moved, pill))
    }

    @Test
    fun `large horizontal move from left stops at pill without shrinking or slipping above it`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 1000, 800)
        val rightPill = Rect(700, 300, 74, 44)
        val current = Rect(100, 280, 312, 268)

        val moved = geometry.move(current, dx = 900f, dy = 0f, screen = viewport, pill = rightPill)

        assertEquals(current.width, moved.width)
        assertEquals(current.height, moved.height)
        assertEquals(current.y, moved.y)
        assertEquals(rightPill.x - 12 - current.width, moved.x)
        assertEquals(rightPill.x - 12, moved.right)
        assertTrue(!intersects(moved, rightPill))
    }

    @Test
    fun `large horizontal move from right stops at pill without shrinking or slipping below it`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 1000, 800)
        val leftPill = Rect(200, 300, 74, 44)
        val current = Rect(500, 280, 312, 268)

        val moved = geometry.move(current, dx = -900f, dy = 0f, screen = viewport, pill = leftPill)

        assertEquals(current.width, moved.width)
        assertEquals(current.height, moved.height)
        assertEquals(current.y, moved.y)
        assertEquals(leftPill.right + 12, moved.x)
        assertEquals(leftPill.right + 12 + current.width, moved.right)
        assertTrue(!intersects(moved, leftPill))
    }

    @Test
    fun `large move whose target is fully beyond pill still stops on the original side`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 2000, 800)
        val pill = Rect(700, 300, 74, 44)
        val current = Rect(100, 280, 312, 268)

        val moved = geometry.move(current, dx = 1_400f, dy = 0f, screen = viewport, pill = pill)

        assertEquals(current.width, moved.width)
        assertEquals(current.height, moved.height)
        assertEquals(current.y, moved.y)
        assertEquals(pill.x - 12 - current.width, moved.x)
        assertEquals(pill.x - 12, moved.right)
        assertTrue(!intersects(moved, pill))
    }

    @Test
    fun `large move from right to fully beyond pill stops on the original side`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 2000, 800)
        val pill = Rect(700, 300, 74, 44)
        val current = Rect(1_000, 280, 312, 268)

        val moved = geometry.move(current, dx = -1_400f, dy = 0f, screen = viewport, pill = pill)

        assertEquals(current.width, moved.width)
        assertEquals(current.height, moved.height)
        assertEquals(current.y, moved.y)
        assertEquals(pill.right + 12, moved.x)
        assertEquals(pill.right + 12 + current.width, moved.right)
        assertTrue(!intersects(moved, pill))
    }

    @Test
    fun `moving back from the horizontal boundary responds immediately`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 2000, 800)
        val pill = Rect(700, 300, 74, 44)
        val atBoundary = Rect(pill.x - 12 - 312, 280, 312, 268)

        val moved = geometry.move(atBoundary, dx = -40f, dy = 0f, screen = viewport, pill = pill)

        assertEquals(atBoundary.x - 40, moved.x)
        assertEquals(atBoundary.y, moved.y)
        assertEquals(atBoundary.width, moved.width)
        assertEquals(atBoundary.height, moved.height)
    }

    @Test
    fun `diagonal move stops at first contact instead of crossing the pill`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 2000, 1500)
        val pill = Rect(700, 600, 74, 44)
        val current = Rect(100, 100, 240, 160)

        val moved = geometry.move(current, dx = 1_100f, dy = 1_000f, screen = viewport, pill = pill)

        assertEquals(current.width, moved.width)
        assertEquals(current.height, moved.height)
        assertEquals(pill.y - 12, moved.bottom)
        assertTrue(!intersects(moved, pill))
    }

    @Test
    fun `diagonal move from a top contact does not teleport across an already aligned axis`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 2000, 1500)
        val pill = Rect(700, 600, 74, 44)
        val current = Rect(700, 428, 240, 160)

        val moved = geometry.move(current, dx = 20f, dy = 20f, screen = viewport, pill = pill)

        assertEquals(current, moved)
    }

    @Test
    fun `diagonal move from a left contact does not teleport across an already aligned axis`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 2000, 1500)
        val pill = Rect(700, 600, 74, 44)
        val current = Rect(448, 600, 240, 160)

        val moved = geometry.move(current, dx = 20f, dy = 20f, screen = viewport, pill = pill)

        assertEquals(current, moved)
    }

    @Test
    fun `horizontal resize toward pill keeps opposite edge and height on the left`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 1000, 800)
        val rightPill = Rect(700, 300, 74, 44)
        val current = Rect(100, 280, 312, 268)

        val resized = geometry.resize(
            current,
            handle = PanelResizeHandle.TOP_RIGHT,
            dx = 500f,
            dy = 0f,
            screen = viewport,
            pill = rightPill,
        )

        assertEquals(current.x, resized.x)
        assertEquals(current.y, resized.y)
        assertEquals(current.height, resized.height)
        assertEquals(rightPill.x - 12, resized.right)
        assertEquals(rightPill.x - 12 - current.x, resized.width)
        assertTrue(!intersects(resized, rightPill))
    }

    @Test
    fun `horizontal resize toward pill keeps opposite edge and height on the right`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 1000, 800)
        val leftPill = Rect(200, 300, 74, 44)
        val current = Rect(500, 280, 312, 268)

        val resized = geometry.resize(
            current,
            handle = PanelResizeHandle.TOP_LEFT,
            dx = -500f,
            dy = 0f,
            screen = viewport,
            pill = leftPill,
        )

        assertEquals(current.right, resized.right)
        assertEquals(current.y, resized.y)
        assertEquals(current.height, resized.height)
        assertEquals(leftPill.right + 12, resized.x)
        assertEquals(current.right - (leftPill.right + 12), resized.width)
        assertTrue(!intersects(resized, leftPill))
    }

    @Test
    fun `right side resize trigger stops at right pill without becoming a top strip`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 400, 850)
        val rightPill = Rect(326, 100, 74, 44)
        val current = Rect(32, 20, 240, 268)

        val resized = geometry.resize(
            current,
            handle = PanelResizeHandle.BOTTOM_RIGHT,
            dx = 200f,
            dy = 0f,
            screen = viewport,
            pill = rightPill,
        )

        assertEquals(32, resized.x)
        assertEquals(20, resized.y)
        assertEquals(282, resized.width)
        assertEquals(268, resized.height)
        assertEquals(rightPill.x - 12, resized.right)
        assertTrue(!intersects(resized, rightPill))
    }

    @Test
    fun `left side resize trigger keeps x and height at the screen edge`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 400, 850)
        val leftPill = Rect(0, 100, 74, 44)
        val current = Rect(100, 20, 240, 268)

        val resized = geometry.resize(
            current,
            handle = PanelResizeHandle.BOTTOM_RIGHT,
            dx = 200f,
            dy = 0f,
            screen = viewport,
            pill = leftPill,
        )

        assertEquals(100, resized.x)
        assertEquals(20, resized.y)
        assertEquals(300, resized.width)
        assertEquals(268, resized.height)
        assertEquals(viewport.right, resized.right)
        assertTrue(!intersects(resized, leftPill))
    }

    @Test
    fun `standard panel keeps readable minimum when a side has room`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val viewport = Rect(0, 0, 1000, 800)
        val centerPill = Rect(450, 300, 74, 44)

        val standard = geometry.standard(
            viewport,
            centerPill,
            desiredWidth = 120,
            desiredHeight = 100,
        )

        assertEquals(240, standard.width)
        assertEquals(160, standard.height)
        assertTrue(inside(standard, viewport))
        assertTrue(!intersects(standard, centerPill))
    }

    @Test
    fun `corner resize enforces readable size and viewport fractions`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val current = Rect(200, 200, 312, 268)

        val resized = geometry.resize(
            current,
            handle = PanelResizeHandle.TOP_LEFT,
            dx = 1_000f,
            dy = 1_000f,
            screen = screen,
            pill = pill,
        )

        assertTrue(inside(resized, screen))
        assertTrue(!intersects(resized, pill))
        assertTrue(resized.width >= 240)
        assertTrue(resized.height >= 160)
        assertTrue(resized.width <= (screen.width * .92f).toInt())
        assertTrue(resized.height <= (screen.height * .75f).toInt())
    }

    @Test
    fun `rectangle survives rotation through normalized persistence and rebounds`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val portrait = Rect(0, 0, 1080, 1920)
        val source = Rect(700, 1200, 312, 268)
        val saved = geometry.normalize(source, portrait)

        val landscape = Rect(0, 0, 1920, 1080)
        val restored = geometry.denormalize(saved, landscape, Rect(1700, 500, 74, 44))

        assertTrue(inside(restored, landscape))
        assertTrue(restored.width >= 240)
        assertTrue(restored.height >= 160)
    }

    @Test
    fun `keyboard viewport is used for height and reset returns standard`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val reduced = Rect(0, 0, 1000, 700)
        val custom = Rect(100, 100, 700, 500)

        val bounded = geometry.bound(custom, reduced, pill)
        val reset = geometry.standard(reduced, pill, desiredWidth = 312, desiredHeight = 268)

        assertTrue(inside(bounded, reduced))
        assertTrue(bounded.height <= (reduced.height * .75f).toInt())
        assertEquals(312, reset.width)
        assertEquals(268, reset.height)
    }

    @Test
    fun `large panel shrinks to a free side when full size would hide the pill`() {
        val tiny = Rect(0, 0, 360, 700)
        val centerPill = Rect(0, 300, 74, 44)
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)

        val bounded = geometry.bound(Rect(0, 0, 331, 525), tiny, centerPill)

        assertTrue(inside(bounded, tiny))
        assertTrue(!intersects(bounded, centerPill))
        assertTrue(bounded.width < 331 || bounded.height < 525)
    }

    @Test
    fun `pointer reservation keeps the envelope inside the viewport`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val body = Rect(0, 200, 300, 160)

        val reserved = geometry.reservePointer(body, Edge.LEFT, Rect(0, 0, 360, 700), pointerLength = 12)

        assertTrue(reserved.x >= 12)
        assertTrue(reserved.right <= 360)
    }

    @Test
    fun `pointer reservation shrinks its strip on a tiny viewport`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val tiny = Rect(0, 0, 10, 10)
        val body = Rect(0, 0, 10, 10)

        val length = geometry.pointerLengthInside(body, Edge.LEFT, tiny, pointerLength = 12)
        val reserved = geometry.reservePointer(body, Edge.LEFT, tiny, pointerLength = 12)
        val envelope = OverlayPlacement.bubbleEnvelope(reserved, Edge.LEFT, length)

        assertTrue(length < 12)
        assertTrue(inside(envelope.window, tiny))
    }

    @Test
    fun `pointer edge always faces the independently moved pill`() {
        val geometry = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12)
        val panel = Rect(300, 300, 240, 160)

        assertEquals(Edge.LEFT, geometry.pointerEdge(panel, Rect(100, 340, 74, 44)))
        assertEquals(Edge.RIGHT, geometry.pointerEdge(panel, Rect(600, 340, 74, 44)))
        assertEquals(Edge.TOP, geometry.pointerEdge(panel, Rect(390, 100, 74, 44)))
        assertEquals(Edge.BOTTOM, geometry.pointerEdge(panel, Rect(390, 600, 74, 44)))
    }

    private fun intersects(first: Rect, second: Rect): Boolean =
        first.x < second.right && first.right > second.x &&
            first.y < second.bottom && first.bottom > second.y

    private fun inside(inner: Rect, outer: Rect): Boolean =
        inner.x >= outer.x && inner.y >= outer.y &&
            inner.right <= outer.right && inner.bottom <= outer.bottom
}
