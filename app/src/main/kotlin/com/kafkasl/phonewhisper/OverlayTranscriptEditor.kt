package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlin.math.abs

/** The first touch acquires the overlay, then opens the IME after the caret gesture is complete. */
internal open class OverlayTranscriptEditor(context: Context) : EditText(context) {
    var acquireWindow: () -> Unit = {}
    var finishEditing: () -> Unit = {}
    var canEdit: () -> Boolean = { true }
    var isEditing = false
        private set
    private val keyboard = EditorKeyboardRequest()
    private var initialOffset: Int? = null
    private var initialText: String? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var keyboardRetries = 0
    private val keyboardRetry = Runnable { showKeyboardWhenReady() }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        // Native focus must not move the window under the user's finger before ACTION_UP.
        showSoftInputOnFocus = false
        isFocusableInTouchMode = true
    }

    fun beginEditing() {
        if (!canEdit()) return
        isEditing = true
        keyboardRetries = 0
        acquireWindow()
        requestFocus()
        keyboard.request()
        post(::showKeyboardWhenReady)
    }

    fun endEditing() {
        isEditing = false
        keyboard.cancel()
        removeCallbacks(keyboardRetry)
        clearFocus()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canEdit()) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            keyboard.beginTouch()
            downX = event.x; downY = event.y; moved = false
            // Capture the visible word before the overlay acquires focus or the
            // IME moves the panel.  This also matters for later taps while the
            // editor is already focused: the tail follower may have moved the
            // viewport since the previous correction.
            initialOffset = getOffsetForPosition(event.x, event.y)
            initialText = text.toString()
            beginEditing()
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            abs(event.x - downX) + abs(event.y - downY) > slop) moved = true
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                keyboard.endTouch()
                // Preserve the position chosen BEFORE the window/keyboard acquisition changed layout.
                if (!moved && event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() &&
                    text.toString() == initialText) initialOffset?.let { setSelection(it.coerceIn(0, length())) }
                initialOffset = null; initialText = null
                if (moved) keyboard.cancel() else post(::showKeyboardWhenReady)
            }
            MotionEvent.ACTION_CANCEL -> { keyboard.cancel(); initialOffset = null; initialText = null }
        }
        return handled
    }

    private fun showKeyboardWhenReady() {
        if (!isEditing || !canEdit()) { keyboard.cancel(); return }
        if (keyboard.consumeIfReady(isFocused, hasWindowFocus(), isShown)) {
            val shown = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            if (!shown) {
                keyboard.request()
                // Some IMEs create their input connection one frame after window focus.
                if (keyboardRetries++ < 2) postDelayed(keyboardRetry, 50L)
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) post(::showKeyboardWhenReady)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused) post(::showKeyboardWhenReady)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) finishEditing()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        isEditing = false
        keyboard.cancel()
        removeCallbacks(keyboardRetry)
        super.onDetachedFromWindow()
    }
}
