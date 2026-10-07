package com.kafkasl.phonewhisper

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration

/** Observes a window's children until a deliberate right pull owns the gesture. */
internal class RightSwipeBackGesture(context: Context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val distance = maxOf(64 * context.resources.displayMetrics.density, 4 * slop)
    // Rotate the existing direction-locking gesture: right becomes up.
    private val gesture = VerticalSwipeGesture(slop, distance)
    private var downX = 0f
    private var downY = 0f
    private var ownsTouch = false

    fun dispatch(
        event: MotionEvent,
        canBegin: () -> Boolean,
        dispatchToChildren: (MotionEvent) -> Boolean,
        goBack: () -> Unit,
    ): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.rawX
            downY = event.rawY
            ownsTouch = false
            gesture.begin(canBegin())
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) gesture.cancel()
        if (!ownsTouch && event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) {
            gesture.cancel() // Long presses belong to text/image selection or row actions.
        }
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            if (!ownsTouch && gesture.progress(dy, -dx) * distance >= 2 * slop) {
                ownsTouch = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { dispatchToChildren(cancel) } finally { cancel.recycle() }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP && ownsTouch) {
                val navigate = gesture.release(dy, -dx)
                ownsTouch = false
                if (navigate) goBack()
                return true
            }
        }
        if (ownsTouch) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) ownsTouch = false
            return true
        }
        return dispatchToChildren(event)
    }
}
