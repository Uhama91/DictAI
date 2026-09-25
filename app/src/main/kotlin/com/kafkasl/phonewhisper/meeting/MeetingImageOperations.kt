package com.kafkasl.phonewhisper.meeting

import com.kafkasl.phonewhisper.NoteImage
import com.kafkasl.phonewhisper.NoteImageMarkers
import com.kafkasl.phonewhisper.PendingNoteCapture
import com.kafkasl.phonewhisper.TranscriptNote
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

/** A durable location in the unfiltered, serialized body of one meeting turn. */
internal data class MeetingImageAnchor(
    val sessionId: String,
    val turnId: String,
    val offsetUtf16: Int,
) {
    init {
        require(sessionId.isNotBlank())
        require(turnId.isNotBlank())
        require(offsetUtf16 >= 0)
    }

    companion object {
        fun capture(document: MeetingDocument, turnId: String, offsetUtf16: Int): MeetingImageAnchor {
            require(document.turns.any { it.id == turnId }) { "Meeting turn does not exist" }
            return MeetingImageAnchor(document.sessionId, turnId, offsetUtf16)
        }
    }
}

internal data class MeetingImageMutation(
    val document: MeetingDocument,
    val images: List<NoteImage>,
    val attachedIds: Set<String> = emptySet(),
    val usedDocumentTurn: Boolean = false,
    val removedImageIds: Set<String> = emptySet(),
)

/** Pure body-level image changes. These methods never edit a filtered meeting projection. */
internal object MeetingImageBatch {
    fun prepare(
        document: MeetingDocument,
        anchor: MeetingImageAnchor,
        capturedImages: List<NoteImage>,
        existingImages: List<NoteImage>,
    ): MeetingImageMutation = prepareInternal(document, anchor, capturedImages, existingImages, resolvedNumbers = false)

    /** Reconciles a mapping persisted before document mutation; never silently renumbers it. */
    fun prepareResolved(
        document: MeetingDocument,
        anchor: MeetingImageAnchor,
        capturedImages: List<NoteImage>,
        existingImages: List<NoteImage>,
    ): MeetingImageMutation = prepareInternal(document, anchor, capturedImages, existingImages, resolvedNumbers = true)

    private fun prepareInternal(
        document: MeetingDocument,
        anchor: MeetingImageAnchor,
        capturedImages: List<NoteImage>,
        existingImages: List<NoteImage>,
        resolvedNumbers: Boolean,
    ): MeetingImageMutation {
        require(anchor.sessionId == document.sessionId) { "L’ancre appartient à une autre réunion" }

        val existingById = linkedMapOf<String, NoteImage>()
        existingImages.forEach { image -> existingById.putIfAbsent(image.id, image) }
        val existing = existingById.values.toList()
        require(existing.all { it.number in 1..999_999 }) { "Numéro d’image hors limites" }
        require(existing.map { it.number }.distinct().size == existing.size) {
            "Les numéros des images existantes doivent être uniques"
        }

        val uniqueCaptures = capturedImages.distinctBy { it.id }
        val newCaptures = uniqueCaptures.filterNot { it.id in existingById }
        check(existing.size + newCaptures.size <= NoteImage.MAX_IMAGES) {
            "La réunion contient déjà dix images"
        }

        val markerNumbers = document.turns.flatMap { turn ->
            buildList {
                addAll(NoteImageMarkers.numbers(turn.recognizedText))
                turn.editedText?.let { addAll(NoteImageMarkers.numbers(it)) }
            }
        }.toSet()
        val usedNumbers = (existing.map { it.number } + markerNumbers).toMutableSet()
        val resolvedNumbersUsed = mutableSetOf<Int>()
        var nextNumber = 1
        fun allocateNumber(preferred: Int): Int {
            if (resolvedNumbers) {
                check(preferred in 1..999_999) { "Numéro d’image résolu invalide" }
                check(existing.none { it.number == preferred } && resolvedNumbersUsed.add(preferred)) {
                    "Le numéro d’image résolu appartient à une autre pièce jointe"
                }
                usedNumbers += preferred
                return preferred
            }
            if (preferred in 1..999_999 && preferred !in usedNumbers) {
                usedNumbers += preferred
                return preferred
            }
            while (nextNumber in usedNumbers && nextNumber <= 999_999) nextNumber++
            check(nextNumber <= 999_999) { "Aucun numéro d’image disponible" }
            val allocated = nextNumber++
            usedNumbers += allocated
            return allocated
        }

        val capturesWithStableNumbers = newCaptures.map { image ->
            val number = allocateNumber(image.number)
            if (number == image.number) image else image.copy(number = number)
        }
        val images = existing + capturesWithStableNumbers
        val attachedIds = uniqueCaptures.mapTo(linkedSetOf()) { it.id }
        if (uniqueCaptures.isEmpty()) {
            return MeetingImageMutation(document, images, attachedIds)
        }

        val visibleNumbers = document.turns.flatMap { turn ->
            NoteImageMarkers.numbers(turn.editedText ?: turn.recognizedText)
        }.toSet()
        val missing = uniqueCaptures.map { captured ->
            capturesWithStableNumbers.firstOrNull { it.id == captured.id }
                ?: existingById.getValue(captured.id)
        }.filterNot { it.number in visibleNumbers }
        if (missing.isEmpty()) return MeetingImageMutation(document, images, attachedIds)

        var targetDocument = document
        var targetId = anchor.turnId
        var usedDocumentTurn = false
        if (targetDocument.turns.none { it.id == targetId }) {
            val editor = MeetingDocumentEditor.restore(targetDocument)
            targetId = editor.ensureDocumentTurn().id
            targetDocument = editor.snapshot()
            usedDocumentTurn = true
        }
        val target = targetDocument.turns.firstOrNull { it.id == targetId }
            ?: error("Tour de rattachement introuvable")
        val currentBody = target.editedText ?: target.recognizedText
        val updatedBody = insertMarkers(currentBody, anchor.offsetUtf16, missing.map { it.marker })
        val updatedTurns = targetDocument.turns.map { turn ->
            if (turn.id == targetId) turn.copy(editedText = updatedBody) else turn
        }
        return MeetingImageMutation(
            document = targetDocument.copy(turns = updatedTurns),
            images = images,
            attachedIds = attachedIds,
            usedDocumentTurn = usedDocumentTurn,
        )
    }

    fun move(
        document: MeetingDocument,
        images: List<NoteImage>,
        imageId: String,
        targetTurnId: String,
        offsetUtf16: Int,
    ): MeetingImageMutation {
        val moving = images.firstOrNull { it.id == imageId }
            ?: return MeetingImageMutation(document, images)
        val target = document.turns.firstOrNull { it.id == targetTurnId }
        val oldTargetBody = target?.let { it.editedText ?: it.recognizedText }.orEmpty()
        val offset = offsetUtf16.coerceIn(0, oldTargetBody.length)
        val marker = moving.marker
        val removedBeforeOffset = if (target == null) 0 else markerOffsets(oldTargetBody, marker)
            .sumOf { position -> (offset - position).coerceIn(0, marker.length) }
        val adjustedOffset = (offset - removedBeforeOffset).coerceAtLeast(0)
        val stripped = document.copy(turns = document.turns.map { turn -> removeVisibleMarker(turn, marker) })
        val anchor = MeetingImageAnchor(document.sessionId, targetTurnId, adjustedOffset)
        return prepare(stripped, anchor, listOf(moving), images)
    }

    fun remove(
        document: MeetingDocument,
        images: List<NoteImage>,
        imageId: String,
    ): MeetingImageMutation {
        val removed = images.firstOrNull { it.id == imageId }
            ?: return MeetingImageMutation(document, images)
        val updatedDocument = document.copy(turns = document.turns.map { turn -> removeVisibleMarker(turn, removed.marker) })
        return MeetingImageMutation(
            document = updatedDocument,
            images = images.filterNot { it.id == imageId },
            removedImageIds = setOf(imageId),
        )
    }

    private fun insertMarkers(body: String, rawOffset: Int, markers: List<String>): String {
        val offset = safeBoundary(body, rawOffset)
        val prefix = body.substring(0, offset)
        val suffix = body.substring(offset)
        val before = when {
            prefix.isEmpty() || prefix.endsWith("\n\n") -> ""
            prefix.endsWith('\n') -> "\n"
            else -> "\n\n"
        }
        val after = when {
            suffix.isEmpty() || suffix.startsWith("\n\n") -> ""
            suffix.startsWith('\n') -> "\n"
            else -> "\n\n"
        }
        return prefix + before + markers.joinToString("\n") + after + suffix
    }

    private fun safeBoundary(body: String, rawOffset: Int): Int {
        val offset = rawOffset.coerceIn(0, body.length)
        return if (offset in 1 until body.length &&
            Character.isHighSurrogate(body[offset - 1]) && Character.isLowSurrogate(body[offset])) {
            offset - 1
        } else {
            offset
        }
    }

    private fun markerOffsets(body: String, marker: String): List<Int> = buildList {
        var from = 0
        while (true) {
            val next = body.indexOf(marker, from)
            if (next < 0) break
            add(next)
            from = next + marker.length
        }
    }

    private fun removeVisibleMarker(turn: MeetingTurn, marker: String): MeetingTurn {
        val body = turn.editedText ?: turn.recognizedText
        if (!body.contains(marker)) return turn
        return turn.copy(editedText = body.replace(marker, ""))
    }
}

internal enum class MeetingImageSourceKind { LIVE, DRAFT, NOTE }

internal data class MeetingImageSource(
    val document: MeetingDocument,
    val images: List<NoteImage>,
    val kind: MeetingImageSourceKind,
)

/** Selects only data belonging to the reservation session, preferring its live owner. */
internal object MeetingImageRecovery {
    fun select(
        sessionId: String,
        liveDocument: MeetingDocument?,
        draft: MeetingDocumentRead,
        note: TranscriptNote?,
    ): MeetingImageSource? {
        if (sessionId.isBlank()) return null
        val noteForSession = note?.takeIf {
            it.id == sessionId && it.meetingRaw == null && it.meeting?.sessionId == sessionId
        }
        liveDocument?.takeIf { it.sessionId == sessionId }?.let { document ->
            return MeetingImageSource(document, noteForSession?.images.orEmpty(), MeetingImageSourceKind.LIVE)
        }
        when (draft) {
            is MeetingDocumentRead.Ready -> if (draft.document.sessionId == sessionId) {
                return MeetingImageSource(draft.document, noteForSession?.images.orEmpty(), MeetingImageSourceKind.DRAFT)
            }
            is MeetingDocumentRead.Invalid, is MeetingDocumentRead.Unsupported -> return null
            MeetingDocumentRead.Absent -> Unit
        }
        val saved = noteForSession?.meeting ?: return null
        return MeetingImageSource(saved, noteForSession.images, MeetingImageSourceKind.NOTE)
    }
}

/** Ordered, retryable publication; callers inject main-thread ownership and durable boundaries. */
internal class MeetingImageDelivery(
    private val applyDocument: (sessionId: String, document: MeetingDocument) -> Unit,
    private val flushDraft: (sessionId: String, document: MeetingDocument) -> CompletableFuture<Unit>,
    private val saveMeeting: (sessionId: String, document: MeetingDocument, images: List<NoteImage>) -> Unit,
    private val clearPending: (id: String) -> Unit,
    private val resolveMeetingBatchImages: (id: String, images: List<NoteImage>) -> PendingNoteCapture,
    private val mainExecutor: Executor = Executor { it.run() },
    private val currentDocument: (sessionId: String) -> MeetingDocument? = { null },
    private val currentImages: (sessionId: String) -> List<NoteImage>? = { null },
    private val onAnchorRestored: (sessionId: String) -> Unit = {},
) {
    fun deliver(
        capture: PendingNoteCapture,
        source: MeetingImageSource,
    ): CompletableFuture<MeetingImageMutation> {
        val result = CompletableFuture<MeetingImageMutation>()
        val anchor = capture.meetingAnchor
        if (!capture.batch || !capture.accepted || capture.meetingAnchorInvalid || anchor == null || capture.allImages.isEmpty()) {
            result.completeExceptionally(IllegalStateException("Réservation d’image Réunion invalide"))
            return result
        }
        val sessionId = anchor.sessionId
        if (capture.noteId != sessionId || source.document.sessionId != sessionId) {
            result.completeExceptionally(IllegalStateException("La capture ne correspond pas à la réunion restaurée"))
            return result
        }

        val durableCapture = try {
            if (capture.meetingImageNumbersResolved) {
                capture
            } else {
                val proposed = MeetingImageBatch.prepare(source.document, anchor, capture.allImages, source.images)
                val proposedById = proposed.images.associateBy { it.id }
                val resolvedImages = capture.allImages.map { captured ->
                    proposedById[captured.id] ?: error("L’image capturée n’a pas été préparée")
                }
                val persisted = resolveMeetingBatchImages(capture.id, resolvedImages)
                check(persisted.id == capture.id && persisted.meetingAnchor == anchor &&
                    persisted.meetingImageNumbersResolved && persisted.allImages == resolvedImages) {
                    "La réservation des numéros d’image n’a pas été confirmée"
                }
                persisted
            }
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
            return result
        }
        val initial = try {
            MeetingImageBatch.prepareResolved(
                source.document,
                anchor,
                durableCapture.allImages,
                mergeImages(source.images, durableCapture.allImages),
            )
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
            return result
        }
        try {
            applyDocument(sessionId, initial.document)
            if (initial.usedDocumentTurn) onAnchorRestored(sessionId)
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
            return result
        }

        fun flushAndPublish(snapshot: MeetingImageMutation, flushAttempt: Int = 1) {
            val flushed = try {
                flushDraft(sessionId, snapshot.document)
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
                return
            }
            try {
                flushed.whenComplete { _, flushFailure ->
                    try {
                        mainExecutor.execute {
                            if (flushFailure != null) {
                                result.completeExceptionally(unwrap(flushFailure))
                                return@execute
                            }
                            if (result.isDone) return@execute

                            val live = runCatching { currentDocument(sessionId) }.getOrElse {
                                result.completeExceptionally(it)
                                return@execute
                            }
                            val latestDocument = live?.takeIf { it.sessionId == sessionId } ?: snapshot.document
                            val latestImages = runCatching { currentImages(sessionId) }.getOrElse {
                                result.completeExceptionally(it)
                                return@execute
                            }
                            val stableCapturedImages = durableCapture.allImages
                            val rebased = try {
                                val currentAttachmentBase = latestImages ?: snapshot.images
                                val existingForRebase = mergeImages(currentAttachmentBase, stableCapturedImages)
                                MeetingImageBatch.prepareResolved(
                                    latestDocument,
                                    anchor,
                                    stableCapturedImages,
                                    existingForRebase,
                                )
                            } catch (failure: Throwable) {
                                result.completeExceptionally(failure)
                                return@execute
                            }

                            if (rebased.document != latestDocument) {
                                try {
                                    applyDocument(sessionId, rebased.document)
                                    if (rebased.usedDocumentTurn) onAnchorRestored(sessionId)
                                } catch (failure: Throwable) {
                                    result.completeExceptionally(failure)
                                    return@execute
                                }
                            }
                            if (rebased.document != snapshot.document || rebased.images != snapshot.images) {
                                if (flushAttempt >= MAX_DRAFT_FLUSHES) {
                                    result.completeExceptionally(
                                        IllegalStateException("Le document Réunion continue de changer pendant sa sauvegarde"),
                                    )
                                    return@execute
                                }
                                flushAndPublish(rebased, flushAttempt + 1)
                                return@execute
                            }

                            try {
                                saveMeeting(sessionId, rebased.document, rebased.images)
                                clearPending(capture.id)
                                result.complete(rebased)
                            } catch (failure: Throwable) {
                                result.completeExceptionally(failure)
                            }
                        }
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }

        flushAndPublish(initial)
        return result
    }

    private fun mergeImages(current: List<NoteImage>, resolvedCapture: List<NoteImage>): List<NoteImage> {
        val resolvedById = resolvedCapture.associateBy { it.id }
        val merged = current.map { existing ->
            val resolved = resolvedById[existing.id] ?: return@map existing
            check(resolved == existing) {
                "Une pièce jointe courante contredit la résolution durable de la capture"
            }
            existing
        }.toMutableList()
        val present = merged.mapTo(mutableSetOf()) { it.id }
        resolvedCapture.filterNot { it.id in present }.forEach(merged::add)
        return merged
    }

    private companion object {
        const val MAX_DRAFT_FLUSHES = 3
    }

    private fun unwrap(failure: Throwable): Throwable =
        if (failure is CompletionException && failure.cause != null) failure.cause!! else failure
}
