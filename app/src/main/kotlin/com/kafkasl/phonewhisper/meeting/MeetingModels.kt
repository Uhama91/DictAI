package com.kafkasl.phonewhisper.meeting

data class MeetingWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val channel: Int,
)

data class MeetingHypothesis(
    val runId: String,
    val utteranceId: Long,
    val revision: Long,
    val words: List<MeetingWord>,
    val transcript: String,
    val isFinal: Boolean,
    val stableSpeakerThroughMs: Long,
    val audioProcessedMs: Long,
)

data class MeetingParticipant(
    val id: String,
    val ordinal: Int,
    val channel: Int,
    val name: String? = null,
    val ignored: Boolean = false,
)

data class MeetingTurn(
    val id: String,
    val utteranceId: Long,
    val startMs: Long,
    val endMs: Long,
    val recognizedText: String,
    val automaticParticipantId: String?,
    val manualParticipantId: String? = null,
    val hasManualAttribution: Boolean = false,
    val editedText: String? = null,
    val attributionStable: Boolean = false,
)

data class MeetingDocument(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val runId: String,
    val participants: List<MeetingParticipant> = emptyList(),
    val turns: List<MeetingTurn> = emptyList(),
    val finished: Boolean = false,
)
