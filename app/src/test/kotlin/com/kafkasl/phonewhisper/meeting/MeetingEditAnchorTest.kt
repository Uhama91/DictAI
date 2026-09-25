package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingEditAnchorTest {
    @Test
    fun wordAlignmentReturnsOnlyWordsAfterTheEditedAudioAnchor() {
        val anchorWords = listOf(
            word("Le", 100, 150),
            word("mardi", 150, 200),
        )
        val anchor = MeetingEditAnchor.capture(anchorWords, "Le jeudi")

        val result = anchor.continuationWords(
            anchorWords + word("matin", 200, 250),
        )

        assertTrue(result.isAligned)
        assertEquals(listOf(word("matin", 200, 250)), result.value)
    }

    @Test
    fun textAlignmentPreservesAnEditedWordAndReturnsOnlyTheRecognizedContinuation() {
        val anchor = MeetingEditAnchor.capture(listOf(word("mardi", 100, 200)), "jeudi")

        val result = anchor.continuationText("mardi matin")

        assertTrue(result.isAligned)
        assertEquals("matin", result.value)
    }

    @Test
    fun textAlignmentReportsWhenTheEditedAnchorCannotBeFound() {
        val anchor = MeetingEditAnchor.capture(listOf(word("mardi", 100, 200)), "jeudi")

        val result = anchor.continuationText("vendredi réunion")

        assertFalse(result.isAligned)
        assertEquals("", result.value)
    }

    @Test
    fun wordAlignmentFindsTheAnchorAfterAnEarlierWordWasRemoved() {
        val anchor = MeetingEditAnchor.capture(
            listOf(word("euh", 100, 150), word("mardi", 150, 200)),
            "jeudi",
        )

        val result = anchor.continuationWords(
            listOf(word("mardi", 150, 200), word("matin", 200, 250)),
        )

        assertTrue(result.isAligned)
        assertEquals(listOf(word("matin", 200, 250)), result.value)
    }

    @Test
    fun textAlignmentRejectsRepeatedAnchorOccurrencesAsAmbiguous() {
        val anchor = MeetingEditAnchor.capture(listOf(word("oui", 100, 200)), "non")

        val result = anchor.continuationText("oui puis oui encore")

        assertFalse(result.isAligned)
        assertEquals("", result.value)
    }

    private fun word(text: String, startMs: Long, endMs: Long) =
        MeetingWord(text, startMs, endMs, channel = 1)
}
