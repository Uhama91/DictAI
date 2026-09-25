package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptImageBlocksTest {
    private fun image(number: Int) = NoteImage(
        "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
        number,
        NoteImageKind.SCREENSHOT,
        number.toLong(),
        1080,
        1920,
    )

    @Test fun savedMarkersBecomeOneEditableObjectAndRoundTripWithoutChangingSurroundingText() {
        val first = image(1)
        val source = "Avant.\n\n[[Image 1]]\n\nAprès."

        val projection = TranscriptImageBlocks.fromNote(source, listOf(first))

        assertEquals("Avant.\n\n\uFFFC\n\nAprès.", projection.editorText)
        assertEquals("Avant.\n\n[[Image 1]]\n\nAprès.", projection.serializedNoteText())
        assertEquals(listOf(first), projection.blocks.single().images)
    }

    @Test fun legacyNoteDraftMarkersRecoverAsOneNativeBlockAndNeverRemainRaw() {
        val image = image(1)
        val oldDraftText = "Avant\n\n[[Image 1]]\n\nAprès"

        val migrated = requireNotNull(TranscriptImageBlocks.fromLegacyNoteDraft(oldDraftText, listOf(image)))

        assertEquals("Avant\n\n\n\nAprès", migrated.rawText())
        assertEquals("Avant\n\n\uFFFC\n\nAprès", migrated.editorText)
        assertEquals(oldDraftText, migrated.serializedNoteText())
        assertEquals(1, migrated.blocks.size)
    }

    @Test fun consecutiveSavedImagesShareOneSpanAndKeepTheirOrderOnRoundTrip() {
        val images = listOf(image(2), image(1))
        val source = "Avant\n\n[[Image 2]]\n\n[[Image 1]]\n\nAprès"

        val projection = TranscriptImageBlocks.fromNote(source, images)

        assertEquals("Avant\n\n\uFFFC\n\nAprès", projection.editorText)
        assertEquals(listOf(2, 1), projection.blocks.single().images.map { it.number })
        assertEquals(source, projection.serializedNoteText())
    }

    @Test fun groupedMarkersRetainEachOriginalWhitespaceSeparator() {
        val images = listOf(image(1), image(2), image(3))
        val source = "[[Image 1]]\n[[Image 2]]\n\n[[Image 3]]"

        val projection = TranscriptImageBlocks.fromNote(source, images)

        assertEquals("\uFFFC", projection.editorText)
        assertEquals(source, projection.serializedNoteText())
    }

    @Test fun acceptedBatchRendersAsOneObjectAtTheReservedPlainTextOffset() {
        val first = image(1)
        val second = image(2)
        val captures = listOf(
            DraftImageCapture(first.id, 5, first, groupId = "capture-session"),
            DraftImageCapture(second.id, 5, second, groupId = "capture-session"),
        )

        val projection = TranscriptImageBlocks.fromDraft("AvantAprès", captures)

        assertEquals("Avant\uFFFCAprès", projection.editorText)
        assertEquals(5, projection.blocks.single().rawOffset)
        assertEquals(listOf(first, second), projection.blocks.single().images)
    }

    @Test fun movingAWholeBlockPreservesEveryPlainTextCharacterAndImageIdentity() {
        val first = image(1)
        val source = "Avant [[Image 1]] après"
        val projection = TranscriptImageBlocks.fromNote(source, listOf(first))

        val moved = TranscriptImageBlocks.move(projection, projection.blocks.single().id, rawOffset = 0)

        assertEquals("Avant  après", moved.rawText())
        assertEquals("[[Image 1]]Avant  après", moved.serializedNoteText())
        assertEquals(first.id, moved.blocks.single().images.single().id)
    }

    @Test fun removingOneBatchObjectRemovesAllItsImagesAndKeepsTheText() {
        val first = image(1)
        val second = image(2)
        val projection = TranscriptImageBlocks.fromDraft(
            "Texte gardé",
            listOf(
                DraftImageCapture(first.id, 5, first, groupId = "lot"),
                DraftImageCapture(second.id, 5, second, groupId = "lot"),
            ),
        )

        val removed = TranscriptImageBlocks.remove(projection, projection.blocks.single().id)

        assertEquals("Texte gardé", removed.rawText())
        assertTrue(removed.blocks.isEmpty())
        assertFalse(removed.serializedNoteText().contains("[[Image"))
    }

    @Test fun removingOneImageFromAGroupedBlockKeepsTheOtherImageAndBlockIdentity() {
        val first = image(1)
        val second = image(2)
        val source = "Avant [[Image 1]]\n\n[[Image 2]] après"
        val projection = TranscriptImageBlocks.fromNote(source, listOf(first, second))
        val originalBlockId = projection.blocks.single().id

        val reduced = TranscriptImageBlocks.removeImage(projection, originalBlockId, first.id)

        assertEquals(listOf(originalBlockId), reduced.blocks.map { it.id })
        assertEquals(listOf(second.id), reduced.blocks.single().images.map { it.id })
        assertEquals("Avant  après", reduced.rawText())
        assertEquals("Avant [[Image 2]] après", reduced.serializedNoteText())
    }

    @Test fun noteEditMoveSaveReopenAndPartialDeleteKeepTextAndImageOrder() {
        val first = image(1)
        val second = image(2)
        val storage = object : TranscriptNoteStorage {
            private val saved = linkedMapOf<String, TranscriptNote>()
            override fun all() = saved.values.toList()
            override fun put(note: TranscriptNote) { saved[note.id] = note }
            override fun remove(id: String) { saved.remove(id) }
        }
        val notes = TranscriptNotes(storage, now = { 1L }, newId = { "note-test" })
        val original = notes.save(null, "Avant [[Image 1]]\n\n[[Image 2]] après", listOf(first, second))
        val opened = TranscriptImageBlocks.fromNote(original.text, original.images)
        val sourceBlock = opened.blocks.single()
        val editedRaw = "Début " + opened.rawText() + " fin"
        val anchors = sourceBlock.images.mapIndexed { index, image ->
            DraftImageCapture(image.id, sourceBlock.rawOffset, image, sourceBlock.id,
                orderAtOffset = sourceBlock.orderAtOffset + index)
        }
        val editedAnchors = DraftImageContext.move(anchors, opened.rawText(), editedRaw)
        val edited = TranscriptImageBlocks.fromDraft(editedRaw, editedAnchors)
        val moved = TranscriptImageBlocks.move(edited, sourceBlock.id, edited.rawText().length)
        val saved = notes.save(original.id, moved.serializedNoteText(), moved.blocks.flatMap { it.images })

        val reopened = TranscriptImageBlocks.fromNote(saved.text, saved.images)
        assertEquals(editedRaw, reopened.rawText())
        assertEquals(listOf(first.id, second.id), reopened.blocks.flatMap { it.images }.map { it.id })
        assertEquals(moved.serializedNoteText(), reopened.serializedNoteText())

        val partiallyDeleted = TranscriptImageBlocks.removeImage(reopened, reopened.blocks.single().id, first.id)
        val savedAgain = notes.save(saved.id, partiallyDeleted.serializedNoteText(), listOf(second))
        val reopenedAgain = TranscriptImageBlocks.fromNote(savedAgain.text, savedAgain.images)
        assertEquals(editedRaw, reopenedAgain.rawText())
        assertEquals(listOf(second.id), reopenedAgain.blocks.flatMap { it.images }.map { it.id })
        assertEquals(partiallyDeleted.serializedNoteText(), reopenedAgain.serializedNoteText())
    }

    @Test fun messageDraftKeepsAcceptedSeriesAtCaretAcrossAsrAndInjectsOnlyRawText() {
        val first = image(1)
        val second = image(2)
        val captures = listOf(first, second).map { image ->
            DraftImageCapture(image.id, 5, image, groupId = "series", orderAtOffset = 0)
        }
        val rawBeforeAsr = "AvantAprès"
        val projection = TranscriptImageBlocks.fromDraft(rawBeforeAsr, captures)
        assertEquals("Avant\uFFFCAprès", projection.editorText)
        assertEquals("Avant[[Image 1]]\n\n[[Image 2]]Après", projection.serializedNoteText())

        val rawAfterAsr = "$rawBeforeAsr dictée"
        val shiftedCaptures = DraftImageContext.move(captures, rawBeforeAsr, rawAfterAsr)
        val continued = TranscriptImageBlocks.fromDraft(rawAfterAsr, shiftedCaptures)
        var injectedText: String? = null
        val controller = object : InjectionController {
            override fun inject(text: String): InjectionResult {
                injectedText = text
                return InjectionResult.Inserted
            }
        }

        assertEquals(InjectionResult.Inserted,
            injectOrCopy(controller, continued.rawText(), copyToClipboard = { false }))
        assertEquals(rawAfterAsr, injectedText)
        assertFalse(injectedText.orEmpty().contains("[[Image"))
        assertFalse(injectedText.orEmpty().contains(TranscriptImageBlocks.OBJECT_REPLACEMENT))
        assertEquals(listOf(first.id, second.id), continued.blocks.single().images.map { it.id })
        assertEquals(5, continued.blocks.single().rawOffset)
    }

    @Test fun rawTextDropsBoundAndUnboundObjectReplacementCharacters() {
        assertEquals("AB", TranscriptImageBlocks.rawText("A\uFFFCB"))
    }

    @Test fun editorCaretOffsetsMapAcrossOneObjectReplacementCharacter() {
        val projection = TranscriptImageBlocks.fromNote("AB[[Image 1]]CD", listOf(image(1)))

        assertEquals(2, projection.rawOffsetForEditor(3))
        assertEquals(3, projection.editorOffsetForRaw(2))
    }

    @Test fun distinctBlocksAtOneTextOffsetKeepOrderAndCanMoveBetweenEachOther() {
        val first = image(1)
        val second = image(2)
        val third = image(3)
        val projection = TranscriptImageBlocks.fromDraft(
            "Texte",
            listOf(
                DraftImageCapture(first.id, 3, first, groupId = "lot-a", orderAtOffset = 0),
                DraftImageCapture(second.id, 3, second, groupId = "lot-a", orderAtOffset = 0),
                DraftImageCapture(third.id, 3, third, groupId = "lot-b", orderAtOffset = 1),
            ),
        )
        val caretAfterBothObjects = projection.caretForEditor(7)

        val moved = TranscriptImageBlocks.moveToCaret(projection, "lot-a", caretAfterBothObjects)

        assertEquals("Texte", moved.rawText())
        assertEquals(listOf("lot-b", "lot-a"), moved.blocks.map { it.id })
        assertEquals(listOf(3, 1, 2), moved.blocks.flatMap { it.images }.map { it.number })
    }

    @Test fun movingFirstOfThreeAdjacentBlocksIntoTheSecondThirdGapUsesPostRemovalCaret() {
        val first = image(1)
        val second = image(2)
        val third = image(3)
        val projection = TranscriptImageBlocks.fromDraft(
            "Texte",
            listOf(
                DraftImageCapture(first.id, 3, first, groupId = "A", orderAtOffset = 0),
                DraftImageCapture(second.id, 3, second, groupId = "B", orderAtOffset = 1),
                DraftImageCapture(third.id, 3, third, groupId = "C", orderAtOffset = 2),
            ),
        )
        val gapBetweenBAndC = projection.caretForEditor(5)

        val moved = TranscriptImageBlocks.moveToCaret(projection, "A", gapBetweenBAndC)

        assertEquals(listOf("B", "A", "C"), moved.blocks.map { it.id })
    }

    @Test fun droppingFirstBlockInItsExistingGapIsAStableNoOp() {
        val first = image(1)
        val second = image(2)
        val projection = TranscriptImageBlocks.fromDraft(
            "Texte",
            listOf(
                DraftImageCapture(first.id, 3, first, groupId = "A", orderAtOffset = 0),
                DraftImageCapture(second.id, 3, second, groupId = "B", orderAtOffset = 1),
            ),
        )
        val originalText = projection.editorText
        val gapBetweenAAndB = projection.caretForEditor(4)

        val moved = TranscriptImageBlocks.moveToCaret(projection, "A", gapBetweenAAndB)

        assertEquals(originalText, moved.editorText)
        assertEquals(listOf("A", "B"), moved.blocks.map { it.id })
    }
}
