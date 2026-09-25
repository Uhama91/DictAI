package com.kafkasl.phonewhisper.meeting

/** Single mutation boundary for a live meeting or an offline-edited saved document. */
class MeetingDocumentEditor private constructor(
    private val reducer: MeetingTranscriptReducer?,
    private var restoredDocument: MeetingDocument?,
    private var documentTurn: MeetingTurn?,
    private var finished: Boolean,
) {
    companion object {
        fun create(sessionId: String, runId: String): MeetingDocumentEditor {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(runId.isNotBlank()) { "runId must not be blank" }
            return MeetingDocumentEditor(
                reducer = MeetingTranscriptReducer(sessionId, runId),
                restoredDocument = null,
                documentTurn = null,
                finished = false,
            )
        }

        fun restore(document: MeetingDocument): MeetingDocumentEditor {
            MeetingDocumentJson.encode(document)
            val saved = document.copy(
                participants = document.participants.toList(),
                turns = document.turns.toList(),
            )
            val documentTurns = saved.turns.filter { it.utteranceId == DOCUMENT_UTTERANCE_ID }
            require(documentTurns.size <= 1) { "A meeting document may contain only one documentary turn" }
            return MeetingDocumentEditor(
                reducer = null,
                restoredDocument = saved,
                documentTurn = documentTurns.singleOrNull(),
                finished = saved.finished,
            )
        }

        private const val DOCUMENT_UTTERANCE_ID = 0L
    }

    @Synchronized
    fun snapshot(): MeetingDocument {
        val base = restoredDocument ?: requireNotNull(reducer).snapshot()
        val documentTurnId = documentTurn?.id
        val turns = buildList {
            base.turns.filterTo(this) { it.id != documentTurnId }
            documentTurn?.let(::add)
        }.sortedWith(
            compareBy<MeetingTurn> { if (it.utteranceId == DOCUMENT_UTTERANCE_ID) 0 else 1 }
                .thenBy { it.utteranceId }
                .thenBy { it.startMs }
                .thenBy { it.id },
        )
        return base.copy(
            participants = base.participants.toList(),
            turns = turns,
            finished = finished,
        )
    }

    /** ASR belongs only to the live reducer and is ignored after finish or for reserved IDs. */
    @Synchronized
    fun apply(hypothesis: MeetingHypothesis) {
        if (reducer == null || finished || hypothesis.utteranceId <= DOCUMENT_UTTERANCE_ID) return
        reducer.apply(hypothesis)
    }

    @Synchronized
    fun edit(turnId: String, text: String) {
        val turn = requireTurn(turnId)
        when {
            documentTurn?.id == turnId -> documentTurn = turn.copy(editedText = text)
            restoredDocument != null -> replaceRestoredTurn(turn.copy(editedText = text))
            else -> requireNotNull(reducer).edit(turnId, text)
        }
    }

    @Synchronized
    fun assign(turnId: String, participantId: String?) {
        val turn = requireTurn(turnId)
        val current = snapshot()
        require(participantId == null || current.participants.any { it.id == participantId }) {
            "Manual attribution must reference an existing participant or null"
        }
        when {
            documentTurn?.id == turnId -> documentTurn = turn.copy(
                manualParticipantId = participantId,
                hasManualAttribution = true,
            )
            restoredDocument != null -> replaceRestoredTurn(
                turn.copy(manualParticipantId = participantId, hasManualAttribution = true),
            )
            else -> requireNotNull(reducer).assign(turnId, participantId)
        }
    }

    @Synchronized
    fun rename(participantId: String, name: String) {
        val current = snapshot()
        require(current.participants.any { it.id == participantId }) { "Participant does not exist" }
        if (restoredDocument == null) {
            requireNotNull(reducer).rename(participantId, name)
        } else {
            val normalizedName = normalizeMeetingParticipantName(name)
            replaceRestoredDocument(
                current.copy(
                    participants = current.participants.map { participant ->
                        if (participant.id == participantId) participant.copy(name = normalizedName) else participant
                    },
                ),
            )
        }
    }

    @Synchronized
    fun setIgnored(participantId: String, ignored: Boolean) {
        val current = snapshot()
        require(current.participants.any { it.id == participantId }) { "Participant does not exist" }
        if (restoredDocument == null) {
            requireNotNull(reducer).setIgnored(participantId, ignored)
        } else {
            replaceRestoredDocument(
                current.copy(
                    participants = current.participants.map { participant ->
                        if (participant.id == participantId) participant.copy(ignored = ignored) else participant
                    },
                ),
            )
        }
    }

    /** Stops ASR input while leaving the saved document available for later edits. */
    @Synchronized
    fun finish(): MeetingDocument {
        finished = true
        return snapshot()
    }

    /** Creates or returns the reserved, unvoiced document turn used to anchor images. */
    @Synchronized
    fun ensureDocumentTurn(): MeetingTurn {
        documentTurn?.let { return it }
        val current = snapshot()
        val existing = current.turns.filter { it.utteranceId == DOCUMENT_UTTERANCE_ID }
        require(existing.size <= 1) { "A meeting document may contain only one documentary turn" }
        existing.singleOrNull()?.let {
            documentTurn = it
            return it
        }

        val stableId = "${current.sessionId}:document:turn"
        require(current.turns.none { it.id == stableId }) { "Documentary turn ID collides with an existing turn" }
        return MeetingTurn(
            id = stableId,
            utteranceId = DOCUMENT_UTTERANCE_ID,
            startMs = 0,
            endMs = 0,
            recognizedText = "",
            automaticParticipantId = null,
        ).also { documentTurn = it }
    }

    private fun requireTurn(turnId: String): MeetingTurn =
        snapshot().turns.firstOrNull { it.id == turnId }
            ?: throw IllegalArgumentException("Meeting turn does not exist")

    private fun replaceRestoredTurn(turn: MeetingTurn) {
        val current = requireNotNull(restoredDocument)
        replaceRestoredDocument(current.copy(turns = current.turns.map { if (it.id == turn.id) turn else it }))
    }

    private fun replaceRestoredDocument(document: MeetingDocument) {
        restoredDocument = document.copy(
            participants = document.participants.toList(),
            turns = document.turns.toList(),
        )
    }
}
