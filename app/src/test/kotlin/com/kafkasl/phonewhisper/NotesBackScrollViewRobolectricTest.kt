package com.kafkasl.phonewhisper

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
class NotesBackScrollViewRobolectricTest {
    private var currentDownTime = 0L

    private data class Fixture(
        val scroll: NotesBackScrollView,
        val backCalls: IntArray,
        val clicks: IntArray,
    )

    private fun fixture(withClickableRow: Boolean = true): Fixture {
        val context = RuntimeEnvironment.getApplication() as Context
        val backCalls = intArrayOf(0)
        val clicks = intArrayOf(0)
        val scroll = NotesBackScrollView(
            context = context,
            rightBoundary = 300f,
            minimumDistance = 24f,
            maximumDistance = 56f,
            onSwipeBack = { backCalls[0]++ },
        )
        if (withClickableRow) {
            val row = TextView(context).apply {
                text = "Une note enregistrée"
                isClickable = true
                minimumHeight = 400
                setOnClickListener { clicks[0]++ }
            }
            scroll.addView(row, android.widget.FrameLayout.LayoutParams(-1, 400))
        }
        val width = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        scroll.measure(width, height)
        scroll.layout(0, 0, 200, 100)
        return Fixture(scroll, backCalls, clicks)
    }

    @Test
    fun rightSwipeOnNotesRowReturnsWithoutActivatingTheNote() {
        val fixture = fixture()
        send(fixture.scroll, MotionEvent.ACTION_DOWN, 100f, 50f, time = 1_000L)
        send(fixture.scroll, MotionEvent.ACTION_MOVE, 180f, 51f, time = 1_030L)
        send(fixture.scroll, MotionEvent.ACTION_UP, 180f, 51f, time = 1_050L)

        assertEquals(1, fixture.backCalls[0])
        assertEquals("horizontal navigation cancels the row click", 0, fixture.clicks[0])
    }

    @Test
    fun fastRightReleaseWorksWithoutMoveEvenWhenTheNotesListIsEmpty() {
        val withRow = fixture()
        send(withRow.scroll, MotionEvent.ACTION_DOWN, 100f, 50f, time = 1_500L)
        send(withRow.scroll, MotionEvent.ACTION_UP, 180f, 50f, time = 1_520L)
        assertEquals(1, withRow.backCalls[0])
        assertEquals(0, withRow.clicks[0])

        val empty = fixture(withClickableRow = false)
        send(empty.scroll, MotionEvent.ACTION_DOWN, 100f, 50f, time = 1_700L)
        send(empty.scroll, MotionEvent.ACTION_UP, 180f, 50f, time = 1_720L)
        assertEquals("the empty list remains a valid navigation surface", 1, empty.backCalls[0])
    }

    @Test
    fun shortOrLeftSwipeDoesNotNavigateOrActivateTheRow() {
        val short = fixture()
        send(short.scroll, MotionEvent.ACTION_DOWN, 100f, 50f, time = 2_000L)
        send(short.scroll, MotionEvent.ACTION_MOVE, 120f, 50f, time = 2_020L)
        send(short.scroll, MotionEvent.ACTION_UP, 120f, 50f, time = 2_040L)
        assertEquals(0, short.backCalls[0])
        assertEquals(0, short.clicks[0])

        val left = fixture()
        send(left.scroll, MotionEvent.ACTION_DOWN, 100f, 50f, time = 3_000L)
        send(left.scroll, MotionEvent.ACTION_MOVE, 50f, 50f, time = 3_020L)
        send(left.scroll, MotionEvent.ACTION_UP, 50f, 50f, time = 3_040L)
        assertEquals(0, left.backCalls[0])
        assertEquals(0, left.clicks[0])
    }

    @Test
    fun verticalMovementKeepsNativeScrollBehaviorAndCancelDoesNotNavigate() {
        val vertical = fixture()
        send(vertical.scroll, MotionEvent.ACTION_DOWN, 100f, 80f, time = 4_000L)
        send(vertical.scroll, MotionEvent.ACTION_MOVE, 100f, 50f, time = 4_020L)
        send(vertical.scroll, MotionEvent.ACTION_MOVE, 100f, 20f, time = 4_040L)
        send(vertical.scroll, MotionEvent.ACTION_UP, 100f, 20f, time = 4_060L)
        assertTrue(
            "vertical movement continues to scroll the list " +
                "(scrollY=${vertical.scroll.scrollY}, height=${vertical.scroll.height}, " +
                "childHeight=${vertical.scroll.getChildAt(0)?.height})",
            vertical.scroll.scrollY > 0,
        )
        assertEquals(0, vertical.backCalls[0])

        val cancelled = fixture()
        send(cancelled.scroll, MotionEvent.ACTION_DOWN, 100f, 50f, time = 5_000L)
        send(cancelled.scroll, MotionEvent.ACTION_MOVE, 180f, 50f, time = 5_030L)
        send(cancelled.scroll, MotionEvent.ACTION_CANCEL, 180f, 50f, time = 5_040L)
        assertEquals(0, cancelled.backCalls[0])
        assertEquals(0, cancelled.clicks[0])
    }

    @Test
    fun verticalOrLeftIntentBeforeMovingRightCannotTurnIntoBackNavigation() {
        val verticalThenRight = fixture()
        send(verticalThenRight.scroll, MotionEvent.ACTION_DOWN, 100f, 80f, time = 6_000L)
        send(verticalThenRight.scroll, MotionEvent.ACTION_MOVE, 100f, 50f, time = 6_020L)
        send(verticalThenRight.scroll, MotionEvent.ACTION_MOVE, 100f, 20f, time = 6_040L)
        assertTrue("the native vertical scroll has begun before horizontal movement", verticalThenRight.scroll.scrollY > 0)
        send(verticalThenRight.scroll, MotionEvent.ACTION_MOVE, 280f, 20f, time = 6_050L)
        send(verticalThenRight.scroll, MotionEvent.ACTION_UP, 280f, 20f, time = 6_060L)
        assertEquals("a vertical scroll stays a scroll", 0, verticalThenRight.backCalls[0])
        assertTrue(
            "the list remains scrollable after vertical intent " +
                "(scrollY=${verticalThenRight.scroll.scrollY}, height=${verticalThenRight.scroll.height}, " +
                "childHeight=${verticalThenRight.scroll.getChildAt(0)?.height})",
            verticalThenRight.scroll.scrollY > 0,
        )

        val leftThenRight = fixture()
        send(leftThenRight.scroll, MotionEvent.ACTION_DOWN, 100f, 50f, time = 7_000L)
        send(leftThenRight.scroll, MotionEvent.ACTION_MOVE, 50f, 50f, time = 7_020L)
        send(leftThenRight.scroll, MotionEvent.ACTION_MOVE, 180f, 50f, time = 7_040L)
        send(leftThenRight.scroll, MotionEvent.ACTION_UP, 180f, 50f, time = 7_060L)
        assertEquals("a gesture that began to the left cannot become back navigation", 0, leftThenRight.backCalls[0])
        assertEquals("the attempted horizontal gesture never activates the row", 0, leftThenRight.clicks[0])
    }

    private fun send(view: View, action: Int, x: Float, y: Float, time: Long) {
        if (action == MotionEvent.ACTION_DOWN) currentDownTime = time
        val event = MotionEvent.obtain(currentDownTime, time, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
