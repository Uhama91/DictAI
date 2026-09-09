package com.kafkasl.phonewhisper

import android.os.SystemClock
import android.view.ViewTreeObserver
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.ScrollView

/** Scroll after text layout, including while the keyboard keeps the editor focused. */
internal class TranscriptTailFollower(
    private val editor: EditText,
    private val scroll: ScrollView,
    private val enabled: () -> Boolean,
) {
    private val state = TranscriptFollowState()
    private var touching = false
    private var listening = false
    private val retry = Runnable { schedule() }
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        stopListening()
        if (ready()) {
            // A WRAP_CONTENT EditText inside a ScrollView must not retain its own caret scroll.
            editor.scrollTo(0, 0)
            scroll.scrollTo(0, (editor.bottom + scroll.paddingBottom - scroll.height).coerceAtLeast(0))
            state.followed()
        } else if (enabled() && state.pending) editor.postDelayed(retry, 100L)
        true
    }

    val followsTail: Boolean get() = enabled() && state.following

    fun userInteraction() { state.userInteraction(SystemClock.uptimeMillis()) }
    fun touch(active: Boolean) { touching = active; userInteraction() }
    fun changed() { state.transcriptChanged(); schedule() }
    fun resized() { if (state.following || state.pending) changed() }
    fun reset() { editor.removeCallbacks(retry); stopListening(); touching = false; state.reset() }

    private fun ready() = enabled() && state.ready(SystemClock.uptimeMillis(), touching,
        editor.selectionStart != editor.selectionEnd, BaseInputConnection.getComposingSpanStart(editor.text) >= 0)

    private fun schedule() {
        editor.removeCallbacks(retry)
        if (!enabled() || !state.pending) return
        if (!ready()) { editor.postDelayed(retry, 100L); return }
        if (!listening) { listening = true; scroll.viewTreeObserver.addOnPreDrawListener(beforeDraw) }
        scroll.invalidate()
    }

    private fun stopListening() {
        if (listening && scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnPreDrawListener(beforeDraw)
        listening = false
    }
}
