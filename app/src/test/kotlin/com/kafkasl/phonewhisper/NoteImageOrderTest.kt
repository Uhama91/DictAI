package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteImageOrderTest {
    private fun image(number: Int, id: String = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}") =
        NoteImage(id, number, NoteImageKind.SCREENSHOT, number.toLong(), 100, 100)

    @Test fun `moving an image up crosses the preceding text block and preserves identity`() {
        val first = image(1)
        val note = TranscriptNote("note", "Titre", "Avant\n\n[[Image 1]]\n\nAprès", 1L,
            images = listOf(first))

        val moved = NoteImageMarkers.move(note, 1, NoteImageMove.UP)

        assertEquals("[[Image 1]]\n\nAvant\n\nAprès", moved.text)
        assertEquals(listOf(first), moved.images)
        assertSame(first, moved.images[0])
        assertEquals(listOf(1), NoteImageMarkers.numbers(moved.text))
    }

    @Test fun `moving down keeps unanchored images accessible`() {
        val one = image(1)
        val two = image(2)
        val three = image(3)
        val note = TranscriptNote("note", "Titre", "Avant [[Image 1]] après", 1L,
            images = listOf(one, two, three))

        val moved = NoteImageMarkers.move(note, 1, NoteImageMove.DOWN)

        assertEquals(listOf(1, 2, 3), NoteImageMarkers.readingOrder(moved))
        assertEquals(listOf(1, 2, 3), NoteImageMarkers.parts(moved).filterIsInstance<NoteImageMarkers.Part.Image>().map { it.image.number })
        assertEquals("Avant  après[[Image 1]]", moved.text.substringBefore("\n\n[[Image 2]]"))
    }

    @Test fun `moving an unanchored image up materializes its marker without losing any image`() {
        val one = image(1)
        val two = image(2)
        val note = TranscriptNote("note", "Titre", "Avant [[Image 1]] après", 1L,
            images = listOf(one, two))

        val moved = NoteImageMarkers.move(note, 2, NoteImageMove.UP)

        assertTrue(moved.text.contains("[[Image 2]]"))
        assertEquals(listOf(1, 2), NoteImageMarkers.readingOrder(moved))
        assertEquals(listOf(one, two), moved.images)
    }

    @Test fun `moving across the following text block preserves every text character`() {
        val note = TranscriptNote("note", "Titre", "Avant\n\n[[Image 1]]\n\nAprès", 1L,
            images = listOf(image(1)))

        val moved = NoteImageMarkers.move(note, 1, NoteImageMove.DOWN)

        assertEquals("Avant\n\nAprès\n\n[[Image 1]]\n\n", moved.text)
    }

    @Test fun `moving a marker with only a preceding separator keeps one readable gap`() {
        val note = TranscriptNote("note", "Titre", "Avant\n\n[[Image 1]]Après", 1L,
            images = listOf(image(1)))

        val moved = NoteImageMarkers.move(note, 1, NoteImageMove.UP)

        assertEquals("[[Image 1]]\n\nAvant\n\nAprès", moved.text)
    }

    @Test fun `reordering uses the current edited text rather than a stale persisted copy`() {
        val first = image(1)
        val persisted = TranscriptNote("note", "Titre", "Ancien\n\n[[Image 1]]\n\nAprès", 1L,
            images = listOf(first))
        val live = persisted.copy(text = "Édition récente\n\n[[Image 1]]\n\nAprès")

        val moved = NoteImageMarkers.move(live, first, NoteImageMove.DOWN)

        assertEquals("Édition récente\n\nAprès\n\n[[Image 1]]\n\n", moved.text)
    }

    @Test fun `moving at a boundary is a no op`() {
        val note = TranscriptNote("note", "Titre", "[[Image 1]] puis [[Image 2]]", 1L,
            images = listOf(image(1), image(2)))

        assertEquals(note, NoteImageMarkers.move(note, 1, NoteImageMove.UP))
        assertEquals(note, NoteImageMarkers.move(note, 2, NoteImageMove.DOWN))
    }

    @Test fun `share export follows the moved marker order`() {
        val note = TranscriptNote("note", "Titre", "[[Image 1]]\n\n[[Image 2]]", 1L,
            images = listOf(image(1), image(2)))

        val moved = NoteImageMarkers.move(note, 2, NoteImageMove.UP)
        val shared = NoteShareText.create(moved)

        assertEquals(listOf(2, 1), NoteImageMarkers.readingOrder(moved))
        assertTrue(shared.indexOf("Image 2 :") < shared.indexOf("Image 1 :"))
    }
}
