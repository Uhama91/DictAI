package com.kafkasl.phonewhisper.meeting

/** Content-free status for the optional live voice-attribution path. */
data class MeetingVoiceProgress(
    val state: MeetingVoiceState,
    val pendingAudioMs: Long,
    val unavailableReason: MeetingVoiceUnavailableReason? = null,
) {
    init {
        require(pendingAudioMs >= 0L) { "pendingAudioMs must not be negative" }
        require((state == MeetingVoiceState.UNAVAILABLE) == (unavailableReason != null)) {
            "Only an unavailable voice path has an unavailable reason"
        }
    }

    companion object {
        /** Legacy bridges expose no voice path; UI should hide its optional progress header. */
        val EMPTY = MeetingVoiceProgress(
            state = MeetingVoiceState.UNAVAILABLE,
            pendingAudioMs = 0L,
            unavailableReason = MeetingVoiceUnavailableReason.UNSUPPORTED_BRIDGE,
        )
    }
}

enum class MeetingVoiceState {
    PREPARING,
    ACTIVE,
    UNAVAILABLE,
}

enum class MeetingVoiceUnavailableReason {
    UNSUPPORTED_BRIDGE,
    MODEL_LOAD_FAILED,
    BACKLOG_LIMIT,
    STORAGE_ERROR,
    PROCESSING_FAILED,
    CANCELLED,
}
