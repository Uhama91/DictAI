package com.kafkasl.phonewhisper.meeting

import com.kafkasl.phonewhisper.NoteImage
import com.kafkasl.phonewhisper.NoteImageKind
import com.kafkasl.phonewhisper.NoteImageMarkers
import com.kafkasl.phonewhisper.NoteImageMove
import com.kafkasl.phonewhisper.PendingNoteCapture
import com.kafkasl.phonewhisper.TranscriptNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingImageOperationsTest {
    private fun image(number: Int, idNumber: Int = number) = NoteImage(
        id = "00000000-0000-0000-0000-${idNumber.toString().padStart(12, '0')}",
        number = number,
        kind = NoteImageKind.SCREENSHOT,
        capturedAt = idNumber.toLong(),
        width = 120,
        height = 80,
    )

    private fun turn(id: String, utteranceId: Long, text: String, edited: String? = null) = MeetingTurn(
        id = id,
        utteranceId = utteranceId,
        startMs = utteranceId * 100,
        endMs = utteranceId * 100 + 500,
        recognizedText = text,
        automaticParticipantId = null,
        editedText = edited,
    )

    private fun document(sessionId: String = "session-a", turns: List<MeetingTurn>) = MeetingDocument(
        sessionId = sessionId,
        runId = "run-$sessionId",
        turns = turns,
    )

    private fun anchor(doc: MeetingDocument, turnId: String, offset: Int) =
        MeetingImageAnchor.capture(doc, turnId, offset)

    @Test
    fun captureAnchorStoresSessionTurnAndUtf16Offset() {
        val doc = document(turns = listOf(turn("turn-1", 1, "😀 bonjour")))

        val captured = anchor(doc, "turn-1", 2)

        assertEquals(doc.sessionId, captured.sessionId)
        assertEquals("turn-1", captured.turnId)
        assertEquals(2, captured.offsetUtf16)
        assertThrows(IllegalArgumentException::class.java) { anchor(doc, "missing", 0) }
        assertThrows(IllegalArgumentException::class.java) { anchor(doc, "turn-1", -1) }
    }

    @Test
    fun prepareCaptureInsertsAtUtf16OffsetAndPreservesExistingImageOrder() {
        val existing = image(6)
        val fresh = image(7)
        val doc = document(
            turns = listOf(
                turn("turn-1", 1, "😀 matin après"),
                turn("turn-2", 2, "déjà [[Image 6]]"),
            ),
        )

        val result = MeetingImageBatch.prepare(
            document = doc,
            anchor = anchor(doc, "turn-1", 2),
            capturedImages = listOf(fresh),
            existingImages = listOf(existing),
        )

        val body = requireNotNull(result.document.turns.first { it.id == "turn-1" }.editedText)
        val markerAt = body.indexOf("[[Image 7]]")
        assertTrue(markerAt > body.indexOf("😀"))
        assertTrue(markerAt < body.indexOf("matin"))
        assertEquals("déjà [[Image 6]]", result.document.turns.last().recognizedText)
        assertEquals(listOf(existing, fresh), result.images)
        assertEquals(setOf(fresh.id), result.attachedIds)
        assertFalse(result.usedDocumentTurn)
    }

    @Test
    fun retryingSameBatchIsIdempotentAndDoesNotDuplicateMarkerOrAttachment() {
        val fresh = image(1)
        val doc = document(turns = listOf(turn("turn-1", 1, "avant après")))
        val anchor = anchor(doc, "turn-1", 6)
        val first = MeetingImageBatch.prepare(doc, anchor, listOf(fresh), emptyList())

        val retry = MeetingImageBatch.prepare(first.document, anchor, listOf(fresh), first.images)

        assertEquals(first.document, retry.document)
        assertEquals(listOf(fresh), retry.images)
        assertEquals(1, retry.document.turns.single().editedText!!.windowed("[[Image 1]]".length).count { it == "[[Image 1]]" })
    }

    @Test
    fun missingTurnFallsBackToVisibleDocumentTurnAndReportsRestoration() {
        val fresh = image(1)
        val doc = document(turns = listOf(turn("turn-1", 1, "parole")))

        val result = MeetingImageBatch.prepare(
            doc,
            MeetingImageAnchor(doc.sessionId, "deleted-turn", 4),
            listOf(fresh),
            emptyList(),
        )

        assertTrue(result.usedDocumentTurn)
        val documentTurn = result.document.turns.single { it.utteranceId == 0L }
        assertTrue(documentTurn.id.endsWith(":document:turn"))
        assertEquals("[[Image 1]]", documentTurn.editedText)
    }

    @Test
    fun ignoredOrRetouchedSpeechIsEditedInItsUnderlyingBody() {
        val participant = MeetingParticipant("p1", 1, 1, "Camille", ignored = true)
        val speech = turn("turn-1", 1, "texte reconnu", edited = "texte retouché")
            .copy(automaticParticipantId = participant.id, attributionStable = true)
        val doc = document(turns = listOf(speech)).copy(participants = listOf(participant))

        val result = MeetingImageBatch.prepare(
            doc,
            anchor(doc, speech.id, 5),
            listOf(image(1)),
            emptyList(),
        )

        val edited = result.document.turns.single()
        assertEquals("texte reconnu", edited.recognizedText)
        assertTrue(edited.editedText!!.contains("[[Image 1]]"))
        assertTrue(MeetingProjection.text(result.document, setOf(1)).contains("[[Image 1]]"))
        assertFalse(MeetingProjection.text(result.document, setOf(1)).contains("texte retouché"))
    }

    @Test
    fun duplicateIdsAreIgnoredAndCollidingImageNumbersAreReassigned() {
        val existing = image(1)
        val captured = image(1, idNumber = 2)
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))

        val result = MeetingImageBatch.prepare(
            doc,
            anchor(doc, "turn-1", 5),
            listOf(captured, captured),
            listOf(existing),
        )

        assertEquals(listOf(1, 2), result.images.map { it.number })
        assertEquals(1, result.attachedIds.size)
        assertEquals(1, result.document.turns.single().editedText!!.windowed(11).count { it == "[[Image 2]]" })
    }

    @Test
    fun globalTenImageLimitDoesNotCreateAnUnattachedMarker() {
        val existing = (1..10).map(::image)
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))

        assertThrows(IllegalStateException::class.java) {
            MeetingImageBatch.prepare(
                doc,
                anchor(doc, "turn-1", 5),
                listOf(image(11)),
                existing,
            )
        }

        assertEquals("texte", doc.turns.single().recognizedText)
        assertNull(doc.turns.single().editedText)
    }

    @Test
    fun moveAndRemoveMutateOnlyMeetingBodiesAndKeepAttachmentOwnershipExplicit() {
        val attachment = image(1)
        val doc = document(
            turns = listOf(
                turn("turn-1", 1, "avant [[Image 1]] après"),
                turn("turn-2", 2, "destination"),
                turn("turn-3", 3, "inchangé"),
            ),
        )

        val moved = MeetingImageBatch.move(doc, listOf(attachment), attachment.id, "turn-2", 11)
        assertFalse(moved.document.turns.first().editedText.orEmpty().contains("[[Image 1]]"))
        assertEquals("avant [[Image 1]] après", moved.document.turns.first().recognizedText)
        assertEquals("avant  après", moved.document.turns.first().editedText)
        assertTrue(moved.document.turns[1].editedText!!.contains("[[Image 1]]"))
        assertNull(moved.document.turns[2].editedText)
        assertEquals(listOf(attachment), moved.images)

        val removed = MeetingImageBatch.remove(moved.document, moved.images, attachment.id)
        assertTrue(removed.document.turns.none { it.editedText.orEmpty().contains("[[Image 1]]") })
        assertEquals("destination", removed.document.turns[1].recognizedText)
        assertEquals("destination\n\n", removed.document.turns[1].editedText)
        assertEquals("inchangé", removed.document.turns[2].recognizedText)
        assertNull(removed.document.turns[2].editedText)
        assertTrue(removed.images.isEmpty())
        assertEquals(setOf(attachment.id), removed.removedImageIds)
    }

    @Test
    fun offsetInsideSurrogatePairIsMovedToSafeUtf16BoundaryWithoutDamagingText() {
        val doc = document(turns = listOf(turn("turn-1", 1, "😀bonjour")))

        val result = MeetingImageBatch.prepare(
            doc,
            anchor(doc, "turn-1", 1),
            listOf(image(1)),
            emptyList(),
        )

        val body = result.document.turns.single().editedText!!
        assertTrue(body.contains("😀"))
        val markerAt = body.indexOf("[[Image 1]]")
        val emojiAt = body.indexOf("😀")
        assertTrue(markerAt < emojiAt || markerAt >= emojiAt + 2)
        assertEquals(1, body.windowed("[[Image 1]]".length).count { it == "[[Image 1]]" })
    }

    @Test
    fun recoveryPrefersMatchingLiveThenMatchingDraftThenMatchingStructuredNote() {
        val wanted = document("session-a", listOf(turn("wanted", 1, "wanted")))
        val other = document("session-b", listOf(turn("other", 1, "other")))
        val note = TranscriptNote("session-a", "Réunion", "", 1L, meeting = wanted)

        assertEquals(
            MeetingImageSourceKind.LIVE,
            MeetingImageRecovery.select("session-a", wanted, MeetingDocumentRead.Ready(other), note)?.kind,
        )
        assertEquals(
            MeetingImageSourceKind.DRAFT,
            MeetingImageRecovery.select("session-a", null, MeetingDocumentRead.Ready(wanted), note)?.kind,
        )
        assertEquals(
            MeetingImageSourceKind.NOTE,
            MeetingImageRecovery.select("session-a", other, MeetingDocumentRead.Absent, note)?.kind,
        )
        assertNull(MeetingImageRecovery.select("session-a", other, MeetingDocumentRead.Absent, null))
    }

    @Test
    fun recoveryNeverTreatsAnOtherSessionLiveDocumentAsTheCaptureTarget() {
        val wanted = document("session-a", listOf(turn("wanted", 1, "ancien")))
        val current = document("session-b", listOf(turn("current", 1, "nouveau")))
        val note = TranscriptNote("session-a", "Réunion", "", 1L, meeting = wanted)

        val source = MeetingImageRecovery.select("session-a", current, MeetingDocumentRead.Absent, note)

        assertEquals(MeetingImageSourceKind.NOTE, source?.kind)
        assertEquals("session-a", source?.document?.sessionId)
    }

    @Test
    fun deliveryOrdersMutationFlushSaveAndClearAndDoesNotClearOnFailure() {
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))
        val capture = capture(doc.sessionId, anchor(doc, "turn-1", 5), listOf(image(1)))
        val flush = CompletableFuture<Unit>()
        val events = mutableListOf<String>()
        val delivery = MeetingImageDelivery(
            applyDocument = { session, _ -> events += "apply:$session" },
            flushDraft = { session, _ -> events += "flush:$session"; flush },
            saveMeeting = { session, _, images -> events += "save:$session:${images.size}" },
            clearPending = { id -> events += "clear:$id" },
            resolveMeetingBatchImages = { id, images ->
                events += "resolve:$id:${images.size}"
                capture.copy(images = images, meetingImageNumbersResolved = true)
            },
        )

        val result = delivery.deliver(capture, MeetingImageSource(doc, emptyList(), MeetingImageSourceKind.LIVE))

        assertEquals(listOf("resolve:${capture.id}:1", "apply:session-a", "flush:session-a"), events)
        assertFalse(result.isDone)
        flush.complete(Unit)

        assertTrue(result.isDone && !result.isCompletedExceptionally)
        assertEquals(
            listOf("resolve:${capture.id}:1", "apply:session-a", "flush:session-a", "save:session-a:1", "clear:${capture.id}"),
            events,
        )
    }

    @Test
    fun draftFailureRetainsTheReservationForExplicitRetry() {
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))
        val capture = capture(doc.sessionId, anchor(doc, "turn-1", 5), listOf(image(1)))
        val events = mutableListOf<String>()
        val delivery = MeetingImageDelivery(
            applyDocument = { _, _ -> events += "apply" },
            flushDraft = { _, _ -> CompletableFuture<Unit>().also { it.completeExceptionally(IllegalStateException("disk detail")) } },
            saveMeeting = { _, _, _ -> events += "save" },
            clearPending = { events += "clear" },
            resolveMeetingBatchImages = { _, images -> capture.copy(images = images, meetingImageNumbersResolved = true) },
        )

        val result = delivery.deliver(capture, MeetingImageSource(doc, emptyList(), MeetingImageSourceKind.DRAFT))

        assertTrue(result.isCompletedExceptionally)
        assertEquals(listOf("apply"), events)
        assertTrue(capture.allImages.single().id == image(1).id)
    }

    @Test
    fun limitFailureDoesNotApplyOrClearTheAcceptedReservation() {
        val existing = (1..10).map(::image)
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))
        val capture = capture(doc.sessionId, anchor(doc, "turn-1", 5), listOf(image(11)))
        var applyCount = 0
        var flushCount = 0
        var saveCount = 0
        var clearCount = 0
        val delivery = MeetingImageDelivery(
            applyDocument = { _, _ -> applyCount++ },
            flushDraft = { _, _ -> flushCount++; CompletableFuture.completedFuture(Unit) },
            saveMeeting = { _, _, _ -> saveCount++ },
            clearPending = { clearCount++ },
            resolveMeetingBatchImages = { _, images -> capture.copy(images = images, meetingImageNumbersResolved = true) },
        )

        val result = delivery.deliver(
            capture,
            MeetingImageSource(doc, existing, MeetingImageSourceKind.LIVE),
        )

        assertTrue(result.isCompletedExceptionally)
        assertEquals(0, applyCount)
        assertEquals(0, flushCount)
        assertEquals(0, saveCount)
        assertEquals(0, clearCount)
        assertEquals(1, capture.images.size)
    }

    @Test
    fun deliveryRebasesOnEditsMadeWhileDraftFlushWasInFlight() {
        val doc = document(turns = listOf(turn("turn-1", 1, "parole")))
        val anchor = anchor(doc, "turn-1", 6)
        val capture = capture(doc.sessionId, anchor, listOf(image(1)))
        val firstFlush = CompletableFuture<Unit>()
        val secondFlush = CompletableFuture<Unit>()
        var current = doc
        var calls = 0
        var saved: MeetingDocument? = null
        val applied = mutableListOf<MeetingDocument>()
        val delivery = MeetingImageDelivery(
            applyDocument = { _, next -> current = next; applied += next },
            flushDraft = { _, _ -> if (calls++ == 0) firstFlush else secondFlush },
            saveMeeting = { _, next, _ -> saved = next },
            clearPending = {},
            currentDocument = { current },
            resolveMeetingBatchImages = { _, images -> capture.copy(images = images, meetingImageNumbersResolved = true) },
        )

        val result = delivery.deliver(capture, MeetingImageSource(doc, emptyList(), MeetingImageSourceKind.LIVE))
        current = current.copy(turns = current.turns.map { turn ->
            turn.copy(editedText = turn.editedText!!.replaceFirst("parole", "parole corrigée"))
        })
        firstFlush.complete(Unit)

        assertEquals(2, calls)
        val currentBody = current.turns.single().editedText!!
        assertEquals("parole corrigée", currentBody.substringBefore("[[Image").trim())
        assertEquals(1, currentBody.windowed("[[Image 1]]".length).count { it == "[[Image 1]]" })
        assertFalse(result.isDone)
        secondFlush.complete(Unit)

        assertTrue(result.isDone && !result.isCompletedExceptionally)
        val savedBody = saved!!.turns.single().editedText!!
        assertEquals("parole corrigée", savedBody.substringBefore("[[Image").trim())
        assertEquals(1, savedBody.windowed("[[Image 1]]".length).count { it == "[[Image 1]]" })
        assertEquals(1, applied.size)
    }

    @Test
    fun rebaseReinsertsMissingMarkersInOriginalCaptureOrderAfterRetouch() {
        val existing = image(1, idNumber = 10)
        val fresh = image(7, idNumber = 11)
        val doc = document(turns = listOf(turn("turn-1", 1, "parole")))
        val capture = capture(doc.sessionId, anchor(doc, "turn-1", 6), listOf(fresh, existing))
        val firstFlush = CompletableFuture<Unit>()
        var current = doc
        var flushCount = 0
        var saved: MeetingDocument? = null
        val delivery = MeetingImageDelivery(
            applyDocument = { _, next -> current = next },
            flushDraft = { _, _ ->
                if (flushCount++ == 0) firstFlush else CompletableFuture.completedFuture(Unit)
            },
            saveMeeting = { _, next, _ -> saved = next },
            clearPending = {},
            currentDocument = { current },
            currentImages = { listOf(existing) },
            resolveMeetingBatchImages = { _, images -> capture.copy(images = images, meetingImageNumbersResolved = true) },
        )

        val result = delivery.deliver(capture, MeetingImageSource(doc, listOf(existing), MeetingImageSourceKind.LIVE))
        assertEquals(listOf(7, 1), NoteImageMarkers.numbers(current.turns.single().editedText!!))
        current = current.copy(turns = current.turns.map { it.copy(editedText = "parole corrigée") })
        firstFlush.complete(Unit)

        assertTrue(result.isDone && !result.isCompletedExceptionally)
        assertEquals(2, flushCount)
        val savedBody = saved!!.turns.single().editedText!!
        assertEquals(
            "parole corrigée",
            Regex("\\[\\[Image [1-9][0-9]{0,5}]]").replace(savedBody, " ")
                .replace(Regex("\\s+"), " ").trim(),
        )
        assertEquals(listOf(7, 1), NoteImageMarkers.numbers(savedBody))
    }

    @Test
    fun mainThreadCompletionIsDispatchedBeforeSavingOrClearing() {
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))
        val capture = capture(doc.sessionId, anchor(doc, "turn-1", 5), listOf(image(1)))
        val flush = CompletableFuture<Unit>()
        val queued = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        val delivery = MeetingImageDelivery(
            applyDocument = { _, _ -> events += "apply" },
            flushDraft = { _, _ -> flush },
            saveMeeting = { _, _, _ -> events += "save" },
            clearPending = { events += "clear" },
            resolveMeetingBatchImages = { _, images ->
                events += "resolve"
                capture.copy(images = images, meetingImageNumbersResolved = true)
            },
            mainExecutor = Executor { queued += it },
        )

        delivery.deliver(capture, MeetingImageSource(doc, emptyList(), MeetingImageSourceKind.LIVE))
        flush.complete(Unit)

        assertEquals(listOf("resolve", "apply"), events)
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(listOf("resolve", "apply", "save", "clear"), events)
    }

    @Test
    fun resolvedNumbersSurviveDraftFlushThenRetryWithStaleNoteAttachments() {
        val existing = image(1, idNumber = 10)
        val captured = image(1, idNumber = 11)
        val original = document(turns = listOf(turn("turn-1", 1, "texte")))
        val originalAnchor = anchor(original, "turn-1", 5)
        val pending = capture(original.sessionId, originalAnchor, listOf(captured))
        var persisted = pending
        var draft = original
        var resolutionCalls = 0
        var saveCalls = 0
        var clearCalls = 0
        val failedSave = MeetingImageDelivery(
            applyDocument = { _, next -> draft = next },
            flushDraft = { _, next -> draft = next; CompletableFuture.completedFuture(Unit) },
            saveMeeting = { _, _, _ -> saveCalls++; error("simulated note write failure") },
            clearPending = { clearCalls++ },
            resolveMeetingBatchImages = { id, images ->
                resolutionCalls++
                persisted = persisted.copy(images = images, meetingImageNumbersResolved = true)
                persisted.also { assertEquals(id, it.id) }
            },
            currentImages = { listOf(existing) },
        )

        assertTrue(failedSave.deliver(pending, MeetingImageSource(original, listOf(existing), MeetingImageSourceKind.LIVE))
            .isCompletedExceptionally)
        assertTrue(persisted.meetingImageNumbersResolved)
        assertEquals(listOf(2), persisted.images.map { it.number })
        assertEquals(1, resolutionCalls)
        assertEquals(0, clearCalls)

        val recoveredDraft = draft
        val retry = MeetingImageDelivery(
            applyDocument = { _, next -> draft = next },
            flushDraft = { _, _ -> CompletableFuture.completedFuture(Unit) },
            saveMeeting = { _, next, images ->
                saveCalls++
                assertEquals(recoveredDraft, next)
                assertEquals(listOf(1, 2), images.map { it.number })
            },
            clearPending = { clearCalls++ },
            resolveMeetingBatchImages = { _, _ -> error("resolved mapping must not be rewritten") },
            currentImages = { listOf(existing) },
        )

        val completed = retry.deliver(
            persisted,
            MeetingImageSource(recoveredDraft, listOf(existing), MeetingImageSourceKind.DRAFT),
        )

        assertTrue(completed.isDone && !completed.isCompletedExceptionally)
        assertEquals(1, NoteImageMarkers.numbers(draft.turns.single().editedText!!).count { it == 2 })
        assertEquals(2, completed.join().images.size)
        assertEquals(2, saveCalls)
        assertEquals(1, clearCalls)
        assertEquals(1, resolutionCalls)
    }

    @Test
    fun unresolvedNumberPersistenceFailurePrecedesDocumentMutationAndFlush() {
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))
        val capture = capture(doc.sessionId, anchor(doc, "turn-1", 5), listOf(image(1)))
        var applyCalls = 0
        var flushCalls = 0
        val delivery = MeetingImageDelivery(
            applyDocument = { _, _ -> applyCalls++ },
            flushDraft = { _, _ -> flushCalls++; CompletableFuture.completedFuture(Unit) },
            saveMeeting = { _, _, _ -> error("save must not run") },
            clearPending = { error("clear must not run") },
            resolveMeetingBatchImages = { _, _ -> error("reservation disk failure") },
        )

        val result = delivery.deliver(capture, MeetingImageSource(doc, emptyList(), MeetingImageSourceKind.LIVE))

        assertTrue(result.isCompletedExceptionally)
        assertEquals(0, applyCalls)
        assertEquals(0, flushCalls)
    }

    @Test
    fun persistsResolvedImagesInCaptureOrderWhenBatchContainsExistingIds() {
        val existing = image(1, idNumber = 10)
        val fresh = image(7, idNumber = 11)
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))
        val capture = capture(doc.sessionId, anchor(doc, "turn-1", 5), listOf(fresh, existing))
        var persistedIds: List<String>? = null
        var saved: MeetingImageMutation? = null
        val delivery = MeetingImageDelivery(
            applyDocument = { _, _ -> },
            flushDraft = { _, _ -> CompletableFuture.completedFuture(Unit) },
            saveMeeting = { _, document, images -> saved = MeetingImageMutation(document, images) },
            clearPending = {},
            resolveMeetingBatchImages = { _, images ->
                persistedIds = images.map { it.id }
                capture.copy(images = images, meetingImageNumbersResolved = true)
            },
        )

        val result = delivery.deliver(
            capture,
            MeetingImageSource(doc, listOf(existing), MeetingImageSourceKind.LIVE),
        )

        assertTrue(result.isDone && !result.isCompletedExceptionally)
        assertEquals(listOf(fresh.id, existing.id), persistedIds)
        assertEquals(listOf(existing.id, fresh.id), saved!!.images.map { it.id })
        assertEquals(listOf(7, 1), NoteImageMarkers.numbers(saved!!.document.turns.single().editedText!!))
    }

    @Test
    fun rebaseRefusesCurrentAttachmentWithDifferentDurableNumber() {
        val doc = document(turns = listOf(turn("turn-1", 1, "avant [[Image 1]] après")))
        val image = image(1)
        val resolvedCapture = capture(doc.sessionId, anchor(doc, "turn-1", 0), listOf(image))
            .copy(meetingImageNumbersResolved = true)
        var saves = 0
        var clears = 0
        val delivery = MeetingImageDelivery(
            applyDocument = { _, _ -> },
            flushDraft = { _, _ -> CompletableFuture.completedFuture(Unit) },
            saveMeeting = { _, _, _ -> saves++ },
            clearPending = { clears++ },
            resolveMeetingBatchImages = { _, _ -> error("resolved mapping must not be rewritten") },
            currentImages = { listOf(image.copy(number = 2)) },
        )

        val result = delivery.deliver(
            resolvedCapture,
            MeetingImageSource(doc, emptyList(), MeetingImageSourceKind.DRAFT),
        )

        assertTrue(result.isCompletedExceptionally)
        assertEquals(0, saves)
        assertEquals(0, clears)
    }

    @Test
    fun concurrentEditsCannotCauseAnUnboundedDraftFlushLoop() {
        val doc = document(turns = listOf(turn("turn-1", 1, "texte")))
        val capture = capture(doc.sessionId, anchor(doc, "turn-1", 5), listOf(image(1)))
        var current = doc
        val flushes = mutableListOf<CompletableFuture<Unit>>()
        var saves = 0
        var clears = 0
        val delivery = MeetingImageDelivery(
            applyDocument = { _, next -> current = next },
            flushDraft = { _, _ -> CompletableFuture<Unit>().also(flushes::add) },
            saveMeeting = { _, _, _ -> saves++ },
            clearPending = { clears++ },
            currentDocument = { current },
            resolveMeetingBatchImages = { _, images -> capture.copy(images = images, meetingImageNumbersResolved = true) },
        )
        val result = delivery.deliver(capture, MeetingImageSource(doc, emptyList(), MeetingImageSourceKind.LIVE))
        repeat(3) { index ->
            current = current.copy(turns = current.turns.map { turn ->
                turn.copy(editedText = "texte edit$index\n\n[[Image 1]]")
            })
            flushes[index].complete(Unit)
        }

        assertEquals(3, flushes.size)
        assertTrue(result.isCompletedExceptionally)
        assertEquals(0, saves)
        assertEquals(0, clears)
    }

    private fun capture(sessionId: String, anchor: MeetingImageAnchor, images: List<NoteImage>) = PendingNoteCapture(
        id = "00000000-0000-0000-0000-000000000999",
        noteId = sessionId,
        number = images.first().number,
        kind = NoteImageKind.SCREENSHOT,
        capturedAt = 1L,
        resumeListening = false,
        clipboardOnly = true,
        batch = true,
        images = images,
        accepted = true,
        meetingAnchor = anchor,
    )
}
