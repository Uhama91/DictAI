package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptSelectionMappingTest {
    @Test fun caretBeforeLaterRevisionStaysAtTheCorrectedWord() {
        assertEquals(
            TranscriptSelectionMapping.Selection(5, 5),
            TranscriptSelectionMapping.afterReplacement(
                selectionStart = 5, selectionEnd = 5,
                oldStart = 12, oldEnd = 20, newEnd = 26, newLength = 40,
            ),
        )
    }

    @Test fun caretAfterRevisionMovesWithTheNewTailLength() {
        assertEquals(
            TranscriptSelectionMapping.Selection(31, 31),
            TranscriptSelectionMapping.afterReplacement(
                selectionStart = 25, selectionEnd = 25,
                oldStart = 12, oldEnd = 20, newEnd = 26, newLength = 40,
            ),
        )
    }

    @Test fun selectionInsideRevisedRangeCollapsesAtTheReplacementEnd() {
        assertEquals(
            TranscriptSelectionMapping.Selection(26, 26),
            TranscriptSelectionMapping.afterReplacement(
                selectionStart = 14, selectionEnd = 18,
                oldStart = 12, oldEnd = 20, newEnd = 26, newLength = 40,
            ),
        )
    }

    @Test fun missingSelectionIsNotInvented() {
        assertNull(
            TranscriptSelectionMapping.afterReplacement(
                selectionStart = -1, selectionEnd = -1,
                oldStart = 12, oldEnd = 20, newEnd = 26, newLength = 40,
            ),
        )
    }
}
