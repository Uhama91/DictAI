package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class DraftImageContextTest {
    private fun image(number: Int) = NoteImage("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}", number,
        NoteImageKind.SCREENSHOT, number.toLong(), 100, 100)
    @Test fun laterSpeechDoesNotMoveCapturePastItsOriginalContext() {
        val first = image(1)
        val anchors = listOf(DraftImageCapture(first.id, 7, first))
        val next = DraftImageContext.move(anchors, "Avant. ", "Avant. Après.")
        assertEquals(7, next.single().offset)
        val note = DraftImageContext.materialize("Avant. Après.", emptyList(), next)
        assertTrue(note.text.indexOf("Avant.") < note.text.indexOf("[[Image 1]]"))
        assertTrue(note.text.indexOf("[[Image 1]]") < note.text.indexOf("Après."))
        assertEquals(listOf(first), note.images)
    }
    @Test fun typingBeforeTheAnchorMovesItAndDeletingAcrossItClampsIt() {
        val captures = listOf(DraftImageCapture(image(1).id, 5, image(1)))
        assertEquals(9, DraftImageContext.move(captures, "Haron vient", "M. Haroun vient").single().offset)
        assertEquals(0, DraftImageContext.move(captures, "Haron vient", "vient").single().offset)
    }
    @Test fun repeatedCapturesKeepOrderAndExistingNotesKeepTheirImages() {
        val first = image(1); val second = image(2); val third = image(3)
        val captures = listOf(DraftImageCapture(second.id, 3, second), DraftImageCapture(third.id, 3, third))
        val result = DraftImageContext.materialize("ABC suite", listOf(first), captures)
        assertEquals(listOf(first, second, third), result.images)
        assertTrue(result.text.indexOf("[[Image 2]]") < result.text.indexOf("[[Image 3]]"))
        assertEquals(setOf(second.id, third.id), result.attachedIds)
    }
    @Test fun failedAndExcessCapturesDoNotIntroduceDanglingReferences() {
        val first = image(1)
        val failed = DraftImageCapture(first.id, 0)
        assertEquals("Texte intact", DraftImageContext.materialize("Texte intact", emptyList(), listOf(failed)).text)
        val existing = (1..10).map(::image)
        val eleventh = image(11)
        val result = DraftImageContext.materialize("Texte intact", existing, listOf(DraftImageCapture(eleventh.id, 4, eleventh)))
        assertEquals("Texte intact", result.text)
        assertEquals(existing, result.images)
        assertTrue(result.attachedIds.isEmpty())
    }
}
