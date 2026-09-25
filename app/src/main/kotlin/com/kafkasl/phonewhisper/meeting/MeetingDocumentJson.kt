package com.kafkasl.phonewhisper.meeting

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal

sealed interface MeetingDocumentRead {
    object Absent : MeetingDocumentRead
    data class Ready(val document: MeetingDocument) : MeetingDocumentRead
    data class Unsupported(val version: Int, val raw: String) : MeetingDocumentRead
    data class Invalid(val raw: String) : MeetingDocumentRead
}

object MeetingDocumentJson {
    private const val CURRENT_VERSION = 1

    fun encode(document: MeetingDocument): String {
        validate(document)

        val participants = JSONArray()
        document.participants.forEach { participant ->
            participants.put(
                JSONObject()
                    .put("id", participant.id)
                    .put("ordinal", participant.ordinal)
                    .put("channel", participant.channel)
                    .put("name", participant.name.jsonValue())
                    .put("ignored", participant.ignored),
            )
        }

        val turns = JSONArray()
        document.turns.forEach { turn ->
            turns.put(
                JSONObject()
                    .put("id", turn.id)
                    .put("utteranceId", turn.utteranceId)
                    .put("startMs", turn.startMs)
                    .put("endMs", turn.endMs)
                    .put("recognizedText", turn.recognizedText)
                    .put("automaticParticipantId", turn.automaticParticipantId.jsonValue())
                    .put("manualParticipantId", turn.manualParticipantId.jsonValue())
                    .put("hasManualAttribution", turn.hasManualAttribution)
                    .put("editedText", turn.editedText.jsonValue())
                    .put("attributionStable", turn.attributionStable),
            )
        }

        return JSONObject()
            .put("schemaVersion", document.schemaVersion)
            .put("sessionId", document.sessionId)
            .put("runId", document.runId)
            .put("participants", participants)
            .put("turns", turns)
            .put("finished", document.finished)
            .toString()
    }

    fun decode(raw: String?): MeetingDocumentRead {
        if (raw == null) return MeetingDocumentRead.Absent

        return try {
            val tokener = JSONTokener(raw)
            val root = JSONObject(tokener)
            require(tokener.nextClean() == '\u0000') { "Unexpected trailing JSON content" }
            val version = root.requiredInt("schemaVersion")
            if (version > CURRENT_VERSION) {
                return MeetingDocumentRead.Unsupported(version = version, raw = raw)
            }
            if (version != CURRENT_VERSION) return MeetingDocumentRead.Invalid(raw)

            val document = MeetingDocument(
                schemaVersion = version,
                sessionId = root.requiredString("sessionId"),
                runId = root.requiredString("runId"),
                participants = root.requiredArray("participants").toParticipants(),
                turns = root.requiredArray("turns").toTurns(),
                finished = root.requiredBoolean("finished"),
            )
            validate(document)
            MeetingDocumentRead.Ready(document)
        } catch (_: Exception) {
            MeetingDocumentRead.Invalid(raw)
        }
    }

    private fun validate(document: MeetingDocument) {
        require(document.schemaVersion == CURRENT_VERSION) { "Unsupported meeting document version" }
        require(document.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(document.runId.isNotBlank()) { "runId must not be blank" }
        require(document.participants.size <= 8) { "At most eight participants are allowed" }

        val participantIds = HashSet<String>()
        val ordinals = HashSet<Int>()
        val channels = HashSet<Int>()
        document.participants.forEach { participant ->
            require(participantIds.add(participant.id)) { "Participant IDs must be unique" }
            require(participant.ordinal in 1..8) { "Participant ordinals must be in 1..8" }
            require(ordinals.add(participant.ordinal)) { "Participant ordinals must be unique" }
            require(participant.channel in 1..8) { "Participant channels must be in 1..8" }
            require(channels.add(participant.channel)) { "Participant channels must be unique" }
        }

        val turnIds = HashSet<String>()
        document.turns.forEach { turn ->
            require(turnIds.add(turn.id)) { "Turn IDs must be unique" }
            require(turn.startMs >= 0) { "Turn start time must not be negative" }
            require(turn.endMs >= 0) { "Turn end time must not be negative" }
            require(turn.endMs >= turn.startMs) { "Turn end time must not precede start time" }
            require(turn.automaticParticipantId == null || turn.automaticParticipantId in participantIds) {
                "Automatic participant must exist"
            }
            require(turn.manualParticipantId == null || turn.manualParticipantId in participantIds) {
                "Manual participant must exist"
            }
        }
    }

    private fun JSONArray.toParticipants(): List<MeetingParticipant> = buildList(length()) {
        for (index in 0 until length()) {
            val value = getJSONObject(index)
            add(
                MeetingParticipant(
                    id = value.requiredString("id"),
                    ordinal = value.requiredInt("ordinal"),
                    channel = value.requiredInt("channel"),
                    name = value.requiredNullableString("name"),
                    ignored = value.requiredBoolean("ignored"),
                ),
            )
        }
    }

    private fun JSONArray.toTurns(): List<MeetingTurn> = buildList(length()) {
        for (index in 0 until length()) {
            val value = getJSONObject(index)
            add(
                MeetingTurn(
                    id = value.requiredString("id"),
                    utteranceId = value.requiredLong("utteranceId"),
                    startMs = value.requiredLong("startMs"),
                    endMs = value.requiredLong("endMs"),
                    recognizedText = value.requiredString("recognizedText"),
                    automaticParticipantId = value.requiredNullableString("automaticParticipantId"),
                    manualParticipantId = value.requiredNullableString("manualParticipantId"),
                    hasManualAttribution = value.requiredBoolean("hasManualAttribution"),
                    editedText = value.requiredNullableString("editedText"),
                    attributionStable = value.requiredBoolean("attributionStable"),
                ),
            )
        }
    }

    private fun JSONObject.requiredValue(key: String): Any {
        require(has(key)) { "Missing field: $key" }
        return get(key)
    }

    private fun JSONObject.requiredString(key: String): String =
        requiredValue(key) as? String ?: throw IllegalArgumentException("$key must be a string")

    private fun JSONObject.requiredNullableString(key: String): String? = when (val value = requiredValue(key)) {
        JSONObject.NULL -> null
        is String -> value
        else -> throw IllegalArgumentException("$key must be a string or null")
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean =
        requiredValue(key) as? Boolean ?: throw IllegalArgumentException("$key must be a boolean")

    private fun JSONObject.requiredInt(key: String): Int = requiredInteger(key).intValueExact()

    private fun JSONObject.requiredLong(key: String): Long = requiredInteger(key).longValueExact()

    private fun JSONObject.requiredInteger(key: String): BigDecimal {
        val value = requiredValue(key)
        require(value is Number) { "$key must be a number" }
        return BigDecimal(value.toString()).stripTrailingZeros()
    }

    private fun JSONObject.requiredArray(key: String): JSONArray =
        requiredValue(key) as? JSONArray ?: throw IllegalArgumentException("$key must be an array")

    private fun String?.jsonValue(): Any = this ?: JSONObject.NULL
}
