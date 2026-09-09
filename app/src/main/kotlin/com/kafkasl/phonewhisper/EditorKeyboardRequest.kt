package com.kafkasl.phonewhisper

/** A keyboard request survives asynchronous window focus, but never reopens a dismissed editor. */
internal class EditorKeyboardRequest {
    private var pending = false
    private var touching = false
    fun beginTouch() { touching = true }
    fun endTouch() { touching = false }
    fun request() { pending = true }
    fun cancel() { pending = false; touching = false }
    fun consumeIfReady(editorFocused: Boolean, windowFocused: Boolean, visible: Boolean): Boolean {
        if (!pending || touching || !editorFocused || !windowFocused || !visible) return false
        pending = false
        return true
    }
}
