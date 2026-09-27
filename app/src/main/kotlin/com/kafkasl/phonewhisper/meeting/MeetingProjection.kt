package com.kafkasl.phonewhisper.meeting

object MeetingProjection {
    private const val CONFIRMATION_LABEL = "Intervenant à confirmer"
    private val imageMarker = Regex("\\[\\[Image ([1-9][0-9]{0,5})]]")

    data class Row(
        val turnId: String,
        val label: String?,
        val body: String,
        val participantId: String?,
        val attributionStable: Boolean,
        val editableSpeech: Boolean,
        val showSpeakerHeading: Boolean = true,
        val audioStartMs: Long? = null,
        val audioEndMs: Long? = null,
    )

    fun rows(
        document: MeetingDocument,
        imageNumbers: Set<Int> = emptySet(),
        keepEmptyTurns: Boolean = false,
    ): List<Row> {
        val participantsById = document.participants.associateBy { it.id }
        return buildList {
            var previousGroupableTurn: MeetingTurn? = null
            var previousPendingTurn: MeetingTurn? = null
            for (turn in turnsInAudioOrder(document.turns)) {
                val body = turn.editedText ?: turn.recognizedText
                val isManual = turn.hasManualAttribution
                val isUnassignedDocumentTurn = turn.utteranceId == 0L && !isManual
                val isStable = !isUnassignedDocumentTurn && (turn.attributionStable || isManual)
                val isPendingWithoutChannel = !isUnassignedDocumentTurn && !isManual && !isStable &&
                    turn.automaticParticipantId == null
                val participantId = when {
                    isUnassignedDocumentTurn -> null
                    isManual -> turn.manualParticipantId
                    turn.attributionStable -> turn.automaticParticipantId
                    else -> null
                }
                val participant = participantId?.let(participantsById::get)

                if (isStable && participant?.ignored == true) {
                    val attachedMarkers = imageMarker.findAll(body)
                        .filter { match -> match.groupValues[1].toInt() in imageNumbers }
                        .map { it.value }
                        .distinct()
                        .toList()
                    if (attachedMarkers.isNotEmpty()) {
                        add(
                            Row(
                                turnId = turn.id,
                                label = null,
                                body = attachedMarkers.joinToString("\n\n"),
                                participantId = participant.id,
                                attributionStable = true,
                                editableSpeech = false,
                                showSpeakerHeading = false,
                                audioStartMs = turn.audioStartMsOrNull(),
                                audioEndMs = turn.audioEndMsOrNull(),
                            ),
                        )
                    } else if (keepEmptyTurns && !body.hasVisibleContent()) {
                        add(
                            Row(
                                turnId = turn.id,
                                label = null,
                                body = "",
                                participantId = participant.id,
                                attributionStable = true,
                                editableSpeech = false,
                                showSpeakerHeading = false,
                                audioStartMs = turn.audioStartMsOrNull(),
                                audioEndMs = turn.audioEndMsOrNull(),
                            ),
                        )
                    }
                    previousGroupableTurn = null
                    previousPendingTurn = null
                    continue
                }

                if (!body.hasVisibleContent() && !keepEmptyTurns) {
                    previousGroupableTurn = null
                    continue
                }
                val label = when {
                    isUnassignedDocumentTurn -> null
                    isStable && participant != null -> participant.name?.takeIf(String::isNotBlank)
                        ?: "Personne ${participant.ordinal}"
                    else -> CONFIRMATION_LABEL
                }
                val showSpeakerHeading = label != null &&
                    !(body.hasVisibleContent() && canContinueSpeakerHeading(
                        previous = previousGroupableTurn,
                        current = turn,
                        currentParticipantId = participantId,
                        currentIsStable = isStable,
                    )) &&
                    !(isPendingWithoutChannel && canContinuePendingHeading(previousPendingTurn, turn))
                add(
                    Row(
                        turnId = turn.id,
                        label = label,
                        body = body,
                        participantId = participant?.id,
                        attributionStable = isStable,
                        editableSpeech = true,
                        showSpeakerHeading = showSpeakerHeading,
                        audioStartMs = turn.audioStartMsOrNull(),
                        audioEndMs = turn.audioEndMsOrNull(),
                    ),
                )
                previousGroupableTurn = turn.takeIf {
                    body.hasVisibleContent() && it.hasKnownAudioInterval() && isStable && participantId != null
                }
                previousPendingTurn = turn.takeIf { isPendingWithoutChannel && body.hasVisibleContent() }
            }
        }
    }

    fun text(document: MeetingDocument, imageNumbers: Set<Int> = emptySet()): String {
        return rows(document, imageNumbers).joinToString(separator = "\n\n") { row ->
            if (row.label == null) row.body else "${row.label}\n${row.body}"
        }
    }

    private fun String.hasVisibleContent(): Boolean {
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun canContinueSpeakerHeading(
        previous: MeetingTurn?,
        current: MeetingTurn,
        currentParticipantId: String?,
        currentIsStable: Boolean,
    ): Boolean {
        if (previous == null || !currentIsStable || currentParticipantId == null) return false
        if (!previous.hasKnownAudioInterval() || !current.hasKnownAudioInterval()) return false
        val previousParticipantId = if (previous.hasManualAttribution) {
            previous.manualParticipantId
        } else {
            previous.automaticParticipantId
        }
        if (previousParticipantId != currentParticipantId) return false
        return current.startMs - previous.endMs <= CONTINUATION_GAP_MS
    }

    private fun canContinuePendingHeading(previous: MeetingTurn?, current: MeetingTurn): Boolean =
        previous != null &&
            previous.utteranceId == current.utteranceId &&
            previous.automaticParticipantId == null &&
            current.automaticParticipantId == null &&
            !previous.hasManualAttribution &&
            !current.hasManualAttribution &&
            !previous.attributionStable &&
            !current.attributionStable

    private fun turnsInAudioOrder(turns: List<MeetingTurn>): List<MeetingTurn> {
        val ordered = ArrayList<MeetingTurn>(turns.size)
        val alignedRun = mutableListOf<MeetingTurn>()
        fun flushAlignedRun() {
            // Kotlin's sortedBy is stable: revised end bounds or generated IDs must not
            // reorder turns whose known audio start is identical.
            ordered += alignedRun.sortedBy(MeetingTurn::startMs)
            alignedRun.clear()
        }
        turns.forEach { turn ->
            if (turn.hasKnownAudioInterval()) {
                alignedRun += turn
            } else {
                flushAlignedRun()
                ordered += turn
            }
        }
        flushAlignedRun()
        return ordered
    }

    private fun MeetingTurn.hasKnownAudioInterval(): Boolean =
        timingKnown && startMs >= 0L && endMs > startMs

    private fun MeetingTurn.audioStartMsOrNull(): Long? = startMs.takeIf { hasKnownAudioInterval() }

    private fun MeetingTurn.audioEndMsOrNull(): Long? = endMs.takeIf { hasKnownAudioInterval() }

    private const val CONTINUATION_GAP_MS = 1_500L
}
