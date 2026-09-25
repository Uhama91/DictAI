package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlin.math.abs

/** The first touch acquires the overlay, then opens the IME after the caret gesture is complete. */
internal open class OverlayTranscriptEditor(context: Context) : EditText(context) {
    var acquireWindow: () -> Unit = {}
    var finishEditing: () -> Unit = {}
    var canEdit: () -> Boolean = { true }
    var imageBlockAtEditorOffset: (Int) -> String? = { null }
    var imageBlockRange: (String) -> Pair<Int, Int>? = { null }
    var moveImageBlockToEditorOffset: (String, Int) -> Boolean = { _, _ -> false }
    var onProtectedImageClipboard: () -> Unit = {}
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
    private var pressedImageBlockId: String? = null
    private var draggingImageBlockId: String? = null
    private var selectedImageBlockId: String? = null
    private var lastImageDragOffset = 0
    private val imageLongPress = Runnable {
        val block = pressedImageBlockId ?: return@Runnable
        draggingImageBlockId = block
        parent?.requestDisallowInterceptTouchEvent(true)
        lastImageDragOffset = selectionEnd.coerceAtLeast(0)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

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
            pressedImageBlockId = imageBlockIdAt(initialOffset ?: 0)
            if (pressedImageBlockId != null) selectedImageBlockId = pressedImageBlockId
            draggingImageBlockId = null
            pressedImageBlockId?.let { postDelayed(imageLongPress, ViewConfiguration.getLongPressTimeout().toLong()) }
            beginEditing()
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (abs(event.x - downX) + abs(event.y - downY) > slop) {
                moved = true
                if (draggingImageBlockId == null) removeCallbacks(imageLongPress)
            }
            if (draggingImageBlockId != null) {
                lastImageDragOffset = getOffsetForPosition(event.x, event.y).coerceIn(0, length())
                runCatching { setSelection(lastImageDragOffset) }
                return true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && draggingImageBlockId != null) {
            removeCallbacks(imageLongPress)
            keyboard.endTouch()
            val id = draggingImageBlockId ?: return true
            draggingImageBlockId = null
            pressedImageBlockId = null
            parent?.requestDisallowInterceptTouchEvent(false)
            moveImageBlockToEditorOffset(id, lastImageDragOffset)
            return true
        }
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                removeCallbacks(imageLongPress)
                keyboard.endTouch()
                val tappedBlockId = pressedImageBlockId
                // Preserve the position chosen BEFORE the window/keyboard acquisition changed layout.
                if (!moved && event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() &&
                    text.toString() == initialText) {
                    val range = tappedBlockId?.let(imageBlockRange)
                    if (range != null) runCatching { setSelection(range.first, range.second) }
                    else initialOffset?.let { setSelection(it.coerceIn(0, length())) }
                }
                initialOffset = null; initialText = null
                pressedImageBlockId = null
                if (moved) keyboard.cancel() else post(::showKeyboardWhenReady)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(imageLongPress)
                keyboard.cancel(); initialOffset = null; initialText = null
                pressedImageBlockId = null; draggingImageBlockId = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return handled
    }

    private fun imageBlockIdAt(offset: Int): String? = imageBlockAtEditorOffset(offset)
        ?: imageBlockAtEditorOffset(offset - 1)

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.cut || id == android.R.id.copy) {
            val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val end = maxOf(selectionStart, selectionEnd).coerceAtMost(length())
            if (start < end && text.subSequence(start, end).contains(TranscriptImageBlocks.OBJECT_REPLACEMENT)) {
                if (id == android.R.id.copy) {
                    val plain = TranscriptImageBlocks.rawText(text.subSequence(start, end))
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("", plain))
                } else onProtectedImageClipboard()
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    override fun performLongClick(): Boolean {
        if (pressedImageBlockId != null) return true
        return super.performLongClick()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (selectedImageBlockId != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            ACCESSIBILITY_MOVE_IMAGE_ACTION, "Déplacer le bloc image sélectionné au curseur"))
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == ACCESSIBILITY_MOVE_IMAGE_ACTION) {
            val blockId = selectedImageBlockId ?: return false
            return moveImageBlockToEditorOffset(blockId, selectionEnd.coerceAtLeast(0))
        }
        return super.performAccessibilityAction(action, arguments)
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
        removeCallbacks(imageLongPress)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val ACCESSIBILITY_MOVE_IMAGE_ACTION = 0x01020001
    }
}
