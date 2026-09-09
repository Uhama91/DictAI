package com.kafkasl.phonewhisper

/** Editing owns the caret; recognition owns the viewport after the edit settles. */
internal class TranscriptFollowState {
    private var lastInteractionMs = Long.MIN_VALUE
    var pending = false
        private set
    var following = false
        private set

    fun reset() { lastInteractionMs = Long.MIN_VALUE; pending = false; following = false }
    fun userInteraction(nowMs: Long) { lastInteractionMs = nowMs; following = false }
    fun transcriptChanged() { pending = true }
    fun ready(nowMs: Long, touching: Boolean, selected: Boolean, composing: Boolean): Boolean =
        pending && !touching && !selected && !composing &&
            (lastInteractionMs == Long.MIN_VALUE || nowMs - lastInteractionMs >= 900L)
    fun followed() { pending = false; following = true }
}
