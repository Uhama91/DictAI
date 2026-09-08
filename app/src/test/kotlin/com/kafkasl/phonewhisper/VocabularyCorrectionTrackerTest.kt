package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class VocabularyCorrectionTrackerTest {
    private val tracker = VocabularyCorrectionTracker(settleDelayMillis = 1_000L)
    private fun edit(before: String, after: String, start: Int, removed: Int, inserted: Int,
        selectionStart: Int = start, selectionEnd: Int = start, at: Long = 0L) {
        tracker.beforeChange(before, start, removed, inserted, selectionStart, selectionEnd)
        tracker.afterChange(after, at)
    }
    private fun suggestion(text: String, at: Long = 1_000L, composing: Boolean = false) =
        tracker.suggestion(text, text.length, text.length, composing, at)
    @Test fun selectedReplacementKeepsSourceAndTargetInOrder() {
        edit("Bonjour mari", "Bonjour Marie", 8, 4, 5, 8, 12)
        assertEquals(VocabularyCorrectionTracker.Suggestion("mari", "Marie"), suggestion("Bonjour Marie"))
    }
    @Test fun deletionAloneNeverSuggests() {
        edit("Bonjour mari", "Bonjour ", 8, 4, 0, 8, 12)
        assertNull(suggestion("Bonjour ", 20_000))
    }
    @Test fun deletionThenTypingLearnsFullSettledWord() {
        edit("Bonjour mari", "Bonjour ", 8, 4, 0, 8, 12)
        edit("Bonjour ", "Bonjour M", 8, 0, 1, at = 100)
        edit("Bonjour M", "Bonjour Ma", 9, 0, 1, at = 200)
        assertNull(suggestion("Bonjour Ma", 1_000))
        edit("Bonjour Ma", "Bonjour Marie", 10, 0, 3, at = 300)
        assertEquals(VocabularyCorrectionTracker.Suggestion("mari", "Marie"), suggestion("Bonjour Marie", 1_300))
    }
    @Test fun compositionAndDebounceSuppressPartialReplacement() {
        edit("mari", "Mar", 0, 4, 3, 0, 4)
        assertNull(suggestion("Mar", 999))
        assertNull(suggestion("Mar", 5_000, composing = true))
        edit("Mar", "Marie", 0, 3, 5, at = 5_100)
        assertEquals(VocabularyCorrectionTracker.Suggestion("mari", "Marie"), suggestion("Marie", 6_100))
    }
    @Test fun leavingDeletionPositionCancelsLearning() {
        edit("mari demain", " demain", 0, 4, 0, 0, 4)
        tracker.onSelectionChanged(" demain", 7, 7)
        tracker.onSelectionChanged(" demain", 0, 0)
        edit(" demain", "Marie demain", 0, 0, 5, at = 100)
        assertNull(suggestion("Marie demain", 1_100))
    }
    @Test fun abandonedDeletionNotReusedLater() {
        edit("mari", "", 0, 4, 0, 0, 4)
        edit("", "Marie", 0, 0, 5, at = 20_000)
        assertNull(suggestion("Marie", 21_000))
    }
    @Test fun ordinaryTypingBackspaceAndFragmentsAreIgnored() {
        edit("Bonjour", "Bonjour Marie", 7, 0, 6)
        assertNull(suggestion("Bonjour Marie"))
        edit("Bonjour Marie", "Bonjour Mari", 12, 1, 0, 13, 13)
        assertNull(suggestion("Bonjour Mari"))
        edit("didier", "Dydier", 0, 3, 3, 0, 3)
        assertNull(suggestion("Dydier"))
    }
    @Test fun reversedSelectionAndPhrasesSupported() {
        edit("new york", "New York City", 0, 8, 13, 8, 0)
        assertEquals(VocabularyCorrectionTracker.Suggestion("new york", "New York City"), suggestion("New York City"))
    }
    @Test fun streamingAppendRetainsButRewriteCancelsCorrection() {
        edit("mari", "Marie", 0, 4, 5, 0, 4)
        tracker.onProgrammaticTextChanged("Marie arrive")
        assertEquals(VocabularyCorrectionTracker.Suggestion("mari", "Marie"), suggestion("Marie arrive"))
        tracker.onProgrammaticTextChanged("Marion arrive")
        assertNull(suggestion("Marion arrive"))
    }
    @Test fun resetPreventsRecognitionFromBecomingCorrection() {
        edit("mari", "Marie", 0, 4, 5, 0, 4)
        tracker.reset(); assertNull(suggestion("Marie"))
    }
    @Test fun casingSupportedButEmptyAndRuleSyntaxRejected() {
        edit("mari", "MARI", 0, 4, 4, 0, 4)
        assertEquals(VocabularyCorrectionTracker.Suggestion("mari", "MARI"), suggestion("MARI"))
        tracker.reset(); edit("mari", " ", 0, 4, 1, 0, 4)
        assertNull(suggestion(" "))
        tracker.reset(); edit("mari", "x => y", 0, 4, 6, 0, 4)
        assertNull(suggestion("x => y"))
    }
    @Test fun staleSuggestionCannotConsumeNewCorrection() {
        edit("mari", "Marie", 0, 4, 5, 0, 4)
        val first = suggestion("Marie")!!
        edit("Marie", "Marion", 0, 5, 6, 0, 5, at = 1_100)
        assertFalse(tracker.consume(first, "Marion"))
        val current = suggestion("Marion", 2_100)!!
        assertEquals("mari", current.from)
        assertTrue(tracker.consume(current, "Marion"))
        assertNull(suggestion("Marion", 3_000))
    }
    @Test fun activeSelectionAndCaretInsideHideSuggestion() {
        edit("mari", "Marie", 0, 4, 5, 0, 4)
        assertNull(tracker.suggestion("Marie", 0, 5, false, 1_000))
        assertNull(tracker.suggestion("Marie", 3, 3, false, 1_000))
        assertNull(suggestion("Marie demain"))
    }
}
