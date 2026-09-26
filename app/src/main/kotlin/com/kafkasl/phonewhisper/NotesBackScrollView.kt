package com.kafkasl.phonewhisper

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView

/** A notes-list scroll container that captures horizontal attempts without stealing vertical scroll. */
internal class NotesBackScrollView(
    context: Context,
    rightBoundary: Float,
    minimumDistance: Float,
    maximumDistance: Float,
    private val onSwipeBack: () -> Unit,
) : ScrollView(context) {
    private val backGesture = RightSwipeBackGesture(
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        minimumDistance = minimumDistance,
        maximumDistance = maximumDistance,
    )
    private var downX = 0f
    private var downY = 0f
    private var horizontalCaptured = false
    private var interrupted = false
    private var navigationGestureAbandoned = false
    private var upResolvedByIntercept = false
    private val swipeRightBoundary = rightBoundary

    init {
        // Empty and disabled lists still need a touch target for a fast DOWN/UP back swipe.
        isClickable = true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                horizontalCaptured = false
                interrupted = false
                navigationGestureAbandoned = false
                upResolvedByIntercept = false
                backGesture.begin(downX, swipeRightBoundary)
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                backGesture.cancel()
                interrupted = true
                horizontalCaptured = true
                return true
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (interrupted) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val newlyCaptured = !horizontalCaptured && shouldCaptureHorizontal(dx, dy)
                if (horizontalCaptured || newlyCaptured) {
                    horizontalCaptured = true
                    if (newlyCaptured && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (backGesture.release(dx, dy)) onSwipeBack()
                        upResolvedByIntercept = true
                    }
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) backGesture.cancel()
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                backGesture.cancel()
                interrupted = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!horizontalCaptured && shouldCaptureHorizontal(event.rawX - downX, event.rawY - downY)) {
                    horizontalCaptured = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (upResolvedByIntercept) {
                    resetGesture()
                    return true
                }
                if (!horizontalCaptured && shouldCaptureHorizontal(event.rawX - downX, event.rawY - downY)) {
                    horizontalCaptured = true
                }
                if (horizontalCaptured && !interrupted &&
                    backGesture.release(event.rawX - downX, event.rawY - downY)
                ) {
                    onSwipeBack()
                }
                val wasHorizontal = horizontalCaptured || interrupted
                resetGesture()
                if (!wasHorizontal) return super.onTouchEvent(event)
                return true
            }
        }
        if (!horizontalCaptured && !interrupted) return super.onTouchEvent(event)
        return true
    }

    private fun resetGesture() {
        backGesture.cancel()
        horizontalCaptured = false
        interrupted = false
        navigationGestureAbandoned = false
        upResolvedByIntercept = false
    }

    private fun shouldCaptureHorizontal(dx: Float, dy: Float): Boolean {
        val eligibleBeforeProgress = backGesture.isRightwardEligible
        backGesture.progress(dx, dy)
        val directionDisarmedNow = eligibleBeforeProgress && !backGesture.isRightwardEligible
        if (directionDisarmedNow) navigationGestureAbandoned = true
        val horizontalAttempt = backGesture.shouldCaptureHorizontal(dx, dy)
        val initialLeftAttempt = directionDisarmedNow && dx < 0f && horizontalAttempt
        return horizontalAttempt && (!navigationGestureAbandoned || initialLeftAttempt)
    }
}
