package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatSwipeGestureTest {
    private fun gesture(initial: Int = 0, count: Int = 4) = FormatSwipeGesture(
        touchSlop = 8f,
        openDistance = 56f,
        selectionActivationDistance = 24f,
        selectionStep = 56f,
    ).apply {
        begin(enabled = true, initialIndex = initial, itemCount = count)
    }

    @Test
    fun `upward pull opens menu while keeping the current format selected`() {
        val update = gesture(initial = 2).move(dx = 0f, dy = -56f)

        assertTrue(update.openedNow)
        assertTrue(update.opened)
        assertFalse(update.selecting)
        assertEquals(2, update.selectedIndex)
    }

    @Test
    fun `release immediately after opening leaves menu tappable without committing`() {
        val result = gesture(initial = 1).release(dx = 0f, dy = -60f)

        assertEquals(FormatSwipeGesture.ReleaseAction.OPEN_MENU, result.action)
        assertEquals(1, result.selectedIndex)
    }

    @Test
    fun `overshooting the opening threshold on the first move still needs another move`() {
        val gesture = gesture(initial = 1)

        val opened = gesture.move(dx = 0f, dy = -200f)
        val result = gesture.release(dx = 0f, dy = -200f)

        assertTrue(opened.openedNow)
        assertFalse(opened.selecting)
        assertEquals(1, opened.selectedIndex)
        assertEquals(FormatSwipeGesture.ReleaseAction.OPEN_MENU, result.action)
        assertEquals(1, result.selectedIndex)
    }

    @Test
    fun `release displacement after opening does not count as a selection move`() {
        val gesture = gesture(initial = 1)
        gesture.move(dx = 0f, dy = -56f)

        val result = gesture.release(dx = 0f, dy = -120f)

        assertEquals(FormatSwipeGesture.ReleaseAction.OPEN_MENU, result.action)
        assertEquals(1, result.selectedIndex)
    }

    @Test
    fun `additional movement commits a bounded selection on release`() {
        val gesture = gesture(initial = 1)
        gesture.move(dx = 0f, dy = -56f)

        val update = gesture.move(dx = 0f, dy = -120f)
        val result = gesture.release(dx = 0f, dy = -120f)

        assertTrue(update.selecting)
        assertEquals(2, update.selectedIndex)
        assertEquals(FormatSwipeGesture.ReleaseAction.COMMIT, result.action)
        assertEquals(2, result.selectedIndex)
    }

    @Test
    fun `selection can move down from the current item but never wraps`() {
        val gesture = gesture(initial = 1)
        gesture.move(dx = 0f, dy = -56f)

        assertEquals(0, gesture.move(dx = 0f, dy = 20f).selectedIndex)
        assertEquals(0, gesture.move(dx = 0f, dy = 200f).selectedIndex)
        assertEquals(FormatSwipeGesture.ReleaseAction.COMMIT, gesture.release(0f, 200f).action)
    }

    @Test
    fun `last format clamps when finger keeps moving upward`() {
        val gesture = gesture(initial = 2, count = 3)
        gesture.move(dx = 0f, dy = -56f)

        assertEquals(2, gesture.move(dx = 0f, dy = -400f).selectedIndex)
    }

    @Test
    fun `horizontal redirect and cancellation preserve the current format`() {
        val redirected = gesture(initial = 1)
        redirected.move(dx = 48f, dy = -20f)
        assertEquals(FormatSwipeGesture.ReleaseAction.NONE, redirected.release(0f, -120f).action)

        val cancelled = gesture(initial = 2)
        cancelled.move(dx = 0f, dy = -100f)
        cancelled.cancel()
        assertEquals(FormatSwipeGesture.ReleaseAction.NONE, cancelled.release(0f, -100f).action)
    }

    @Test
    fun `disabled gesture cannot open or commit`() {
        val gesture = FormatSwipeGesture(8f, 56f, 24f, 56f)
        gesture.begin(enabled = false, initialIndex = 0, itemCount = 4)

        assertFalse(gesture.move(0f, -200f).opened)
        assertEquals(FormatSwipeGesture.ReleaseAction.NONE, gesture.release(0f, -200f).action)
    }
}
