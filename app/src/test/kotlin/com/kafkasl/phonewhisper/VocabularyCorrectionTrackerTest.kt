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
        assertNull(suggestion("Mar", 1_000, composing = true))
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
    @Test fun ordinaryTypingIsIgnoredAndSpellingEditsUseTheWholeWord() {
        edit("Bonjour", "Bonjour Marie", 7, 0, 6)
        assertNull(suggestion("Bonjour Marie"))
        edit("Bonjour Marie", "Bonjour Mari", 12, 1, 0, 13, 13)
        assertEquals(VocabularyCorrectionTracker.Suggestion("Marie", "Mari"), suggestion("Bonjour Mari"))
        edit("didier", "Dydier", 0, 3, 3, 0, 3)
        assertEquals(VocabularyCorrectionTracker.Suggestion("didier", "Dydier"), suggestion("Dydier"))
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
    @Test fun activeSelectionHidesSuggestionButCaretInsideTheCorrectionDoesNot() {
        edit("mari", "Marie", 0, 4, 5, 0, 4)
        assertNull(tracker.suggestion("Marie", 0, 5, false, 1_000))
        assertNotNull(tracker.suggestion("Marie", 3, 3, false, 1_000))
        assertNull(suggestion("Marie demain"))
    }
    @Test fun gboardStableCompositionMayBeConfirmedWithoutTypingASpace() {
        edit("cloud", "Claude", 0, 5, 6, 0, 5)
        assertNull(suggestion("Claude", 1_499, composing = true))
        val offer = suggestion("Claude", 1_500, composing = true)!!
        assertEquals(VocabularyCorrectionTracker.Suggestion("cloud", "Claude"), offer)
        // The keyboard changes its candidate before the tap: the stale offer cannot be saved.
        edit("Claude", "Claudie", 0, 6, 7, at = 1_600)
        assertFalse(tracker.consume(offer, "Claudie"))
    }
    @Test fun imeCollapsedSelectionStillRemembersTheExplicitDeletedPhrase() {
        tracker.onSelectionChanged("Bonjour ma yotte", 8, 16)
        tracker.onSelectionChanged("Bonjour ma yotte", 8, 8)
        edit("Bonjour ma yotte", "Bonjour ", 8, 8, 0, at = 20)
        edit("Bonjour ", "Bonjour Maillot", 8, 0, 7, at = 200)
        assertEquals(VocabularyCorrectionTracker.Suggestion("ma yotte", "Maillot"), suggestion("Bonjour Maillot", 1_200))
    }
    @Test fun tentativeSpeechCanBeRevisedWhileTheOfferIsPending() {
        edit("mari arrive peut etre", "Marie arrive peut etre", 0, 4, 5, 0, 4)
        tracker.onProgrammaticTextChanged("Marie arrive demain matin")
        assertEquals(VocabularyCorrectionTracker.Suggestion("mari", "Marie"), suggestion("Marie arrive demain matin"))
        tracker.onProgrammaticTextChanged("Marion arrive demain matin")
        assertNull(suggestion("Marion arrive demain matin"))
    }
    @Test fun deletionFollowedOnlyBySpeechDoesNotLearnTheContinuation() {
        edit("cloud", "", 0, 5, 0, 0, 5)
        tracker.onProgrammaticTextChanged("la suite de la dictée")
        assertNull(suggestion("la suite de la dictée"))
    }
    @Test fun dismissOrExpiryResetDoesNotReofferAfterSpeech() {
        edit("cloud", "Claude", 0, 5, 6, 0, 5)
        assertNotNull(suggestion("Claude"))
        tracker.reset()
        tracker.onProgrammaticTextChanged("Claude arrive")
        assertNull(suggestion("Claude arrive"))
    }
    @Test fun anUnrelatedEditCannotReuseAnOldSelection() {
        tracker.onSelectionChanged("cloud arrive", 0, 5)
        tracker.onSelectionChanged("cloud arrive", 12, 12)
        edit("cloud arrive", "cloud arriv", 11, 1, 0, 12, 12)
        assertEquals(VocabularyCorrectionTracker.Suggestion("arrive", "arriv"), suggestion("cloud arriv"))
    }

    @Test fun afDeletedLetterByLetterThenReplacedByCafInPause() {
        edit("AF", "A", 1, 1, 0, 2, 2)
        edit("A", "", 0, 1, 0, 1, 1, at = 100)
        assertNull(suggestion("", 2_000))
        edit("", "C", 0, 0, 1, at = 300)
        edit("C", "CA", 1, 0, 1, at = 400)
        edit("CA", "CAF", 2, 0, 1, at = 500)
        assertEquals(VocabularyCorrectionTracker.Suggestion("AF", "CAF"), suggestion("CAF", 1_500))
    }
    @Test fun insertingMissingCAtTheBeginningDoesNotRequireMovingCaretToTheEnd() {
        edit("AF", "CAF", 0, 0, 1, 0, 0)
        assertEquals(VocabularyCorrectionTracker.Suggestion("AF", "CAF"),
            tracker.suggestion("CAF", 1, 1, false, 1_000))
    }
    @Test fun gboardCanReplaceTheWholeComposingRegionForOneMissingLetter() {
        edit("Je suis Haron", "Je suis Haroun", 8, 5, 6, 12, 12)
        assertEquals(VocabularyCorrectionTracker.Suggestion("Haron", "Haroun"), suggestion("Je suis Haroun"))
    }
    @Test fun ordinaryLetterByLetterTypingDoesNotTeachIntermediatePrefixes() {
        edit("Bonjour ", "Bonjour C", 8, 0, 1)
        edit("Bonjour C", "Bonjour CA", 9, 0, 1, at = 200)
        edit("Bonjour CA", "Bonjour CAF", 10, 0, 1, at = 400)
        assertNull(suggestion("Bonjour CAF", 2_000))
    }
    @Test fun deletingAnExistingNameOnlyNeverOffersAnEmptyReplacement() {
        edit("Haron", "Haro", 4, 1, 0, 5, 5)
        edit("Haro", "Har", 3, 1, 0, 4, 4, at = 100)
        edit("Har", "Ha", 2, 1, 0, 3, 3, at = 200)
        edit("Ha", "H", 1, 1, 0, 2, 2, at = 300)
        edit("H", "", 0, 1, 0, 1, 1, at = 400)
        assertNull(suggestion("", 10_000))
    }

    @Test fun aPhraseCanBeErasedOneLetterAtATimeAndReplaced() {
        var text = "Bonjour ma yotte demain"
        var caret = 16
        repeat(8) { index ->
            val next = text.removeRange(caret - 1, caret)
            edit(text, next, caret - 1, 1, 0, caret, caret, at = index * 100L)
            text = next; caret--
            tracker.onSelectionChanged(text, caret, caret)
        }
        assertEquals("Bonjour  demain", text)
        assertNull(suggestion(text, 2_000))
        edit(text, "Bonjour Maillot demain", 8, 0, 7, at = 2_100)
        assertEquals(VocabularyCorrectionTracker.Suggestion("ma yotte", "Maillot"), suggestion("Bonjour Maillot demain", 3_100))
    }

}
