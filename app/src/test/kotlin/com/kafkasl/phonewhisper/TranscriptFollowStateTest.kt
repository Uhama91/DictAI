package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class TranscriptFollowStateTest {
    @Test fun readingAndCorrectingKeepsTheViewportEvenAfterTheTypingTimeout() {
        val state = TranscriptFollowState()
        state.userInteraction(1000)
        state.transcriptChanged()
        assertTrue(state.ready(10000, false, false, false, editing = false))
    }

    @Test fun newRecognitionFollowsWithoutDependingOnEditorFocus() {
        val state = TranscriptFollowState()
        state.transcriptChanged()
        assertTrue(state.ready(0, false, false, false))
        state.followed()
        assertTrue(state.following)
        assertFalse(state.pending)
    }

    @Test fun typingSelectionAndCompositionKeepCaretOwnershipUntilSettledThenFollowViewport() {
        val state = TranscriptFollowState()
        state.userInteraction(1000)
        state.transcriptChanged()
        assertFalse(state.ready(1899, false, false, false))
        assertFalse(state.ready(1900, true, false, false))
        assertFalse(state.ready(1900, false, true, false))
        assertTrue(state.ready(1900, false, false, true))
        assertTrue(state.ready(1900, false, false, false))
        state.followed()
        state.userInteraction(2000)
        assertFalse(state.following)
        assertFalse(state.ready(4000, false, false, false)) // no new text: do not jump from the edit.
    }

    @Test fun editingFlagDoesNotBlockSettledRecognitionFromFollowingTheTail() {
        val state = TranscriptFollowState()
        state.userInteraction(1000)
        state.transcriptChanged()

        assertTrue(state.ready(1900, false, false, false, editing = true))
    }

    @Test fun aNewDictationDoesNotInheritOldEditingOrScrollRequests() {
        val state = TranscriptFollowState()
        state.userInteraction(2000); state.transcriptChanged(); state.reset()
        assertFalse(state.pending)
        state.transcriptChanged()
        assertTrue(state.ready(2001, false, false, false))
    }
}
