package com.kafkasl.phonewhisper.meeting

private const val MAX_MEETING_PARTICIPANT_NAME_CODE_POINTS = 80

internal fun normalizeMeetingParticipantName(input: String): String? {
    val singleLine = buildString {
        var index = 0
        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            index += Character.charCount(codePoint)
            appendCodePoint(if (isMeetingNameLineBreak(codePoint)) ' '.code else codePoint)
        }
    }.trim()

    if (singleLine.isEmpty()) return null
    val codePointCount = singleLine.codePointCount(0, singleLine.length)
    val endIndex = singleLine.offsetByCodePoints(0, minOf(codePointCount, MAX_MEETING_PARTICIPANT_NAME_CODE_POINTS))
    return singleLine.substring(0, endIndex).trimEnd().ifEmpty { null }
}

private fun isMeetingNameLineBreak(codePoint: Int): Boolean {
    val type = Character.getType(codePoint)
    return codePoint == '\r'.code ||
        codePoint == '\n'.code ||
        codePoint == 0x0B ||
        codePoint == 0x0C ||
        codePoint == 0x85 ||
        type == Character.LINE_SEPARATOR.toInt() ||
        type == Character.PARAGRAPH_SEPARATOR.toInt()
}

class MeetingParticipants(private val sessionId: String) {
    private val participantsByChannel = linkedMapOf<Int, MeetingParticipant>()

    fun observe(channel: Int): MeetingParticipant? {
        if (channel !in MIN_CHANNEL..MAX_CHANNEL) return null
        participantsByChannel[channel]?.let { return it }
        if (participantsByChannel.size >= MAX_PARTICIPANTS) return null

        val participant = MeetingParticipant(
            id = "$sessionId:participant:$channel",
            ordinal = participantsByChannel.size + 1,
            channel = channel,
        )
        participantsByChannel[channel] = participant
        return participant
    }

    fun rename(id: String, name: String) {
        val channel = participantsByChannel.entries.firstOrNull { it.value.id == id }?.key ?: return
        val participant = requireNotNull(participantsByChannel[channel])
        participantsByChannel[channel] = participant.copy(name = normalizeMeetingParticipantName(name))
    }

    fun setIgnored(id: String, ignored: Boolean) {
        val channel = participantsByChannel.entries.firstOrNull { it.value.id == id }?.key ?: return
        val participant = requireNotNull(participantsByChannel[channel])
        participantsByChannel[channel] = participant.copy(ignored = ignored)
    }

    fun all(): List<MeetingParticipant> = participantsByChannel.values.toList()

    private companion object {
        const val MIN_CHANNEL = 1
        const val MAX_CHANNEL = 8
        const val MAX_PARTICIPANTS = 8
    }
}
