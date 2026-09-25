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
    )

    fun rows(
        document: MeetingDocument,
        imageNumbers: Set<Int> = emptySet(),
        keepEmptyTurns: Boolean = false,
    ): List<Row> {
        val participantsById = document.participants.associateBy { it.id }
        return buildList {
            for (turn in document.turns) {
                val body = turn.editedText ?: turn.recognizedText
                val isManual = turn.hasManualAttribution
                val isUnassignedDocumentTurn = turn.utteranceId == 0L && !isManual
                val isStable = !isUnassignedDocumentTurn && (turn.attributionStable || isManual)
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
                            ),
                        )
                    }
                    continue
                }

                if (!body.hasVisibleContent() && !keepEmptyTurns) continue
                val label = when {
                    isUnassignedDocumentTurn -> null
                    isStable && participant != null -> participant.name?.takeIf(String::isNotBlank)
                        ?: "Personne ${participant.ordinal}"
                    else -> CONFIRMATION_LABEL
                }
                add(
                    Row(
                        turnId = turn.id,
                        label = label,
                        body = body,
                        participantId = participant?.id,
                        attributionStable = isStable,
                        editableSpeech = true,
                    ),
                )
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
}
