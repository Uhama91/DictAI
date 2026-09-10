package com.kafkasl.phonewhisper

/** A correction owns the caret; the viewport can resume following once input settles. */
internal class TranscriptFollowState {
    private var lastInteractionMs = Long.MIN_VALUE
    var pending = false
        private set
    var following = false
        private set

    fun reset() { lastInteractionMs = Long.MIN_VALUE; pending = false; following = false }
    fun userInteraction(nowMs: Long) { lastInteractionMs = nowMs; following = false }
    fun transcriptChanged() { pending = true }
    @Suppress("UNUSED_PARAMETER")
    fun ready(nowMs: Long, touching: Boolean, selected: Boolean, composing: Boolean, editing: Boolean = false): Boolean =
        // A real touch or selection still owns the viewport.  Composition is
        // deliberately time based: some IMEs keep a stale composing span after
        // the user has stopped typing, so it cannot be an indefinite lock.
        pending && !touching && !selected &&
            (lastInteractionMs == Long.MIN_VALUE || nowMs - lastInteractionMs >= 900L)
    fun followed() { pending = false; following = true }
}
