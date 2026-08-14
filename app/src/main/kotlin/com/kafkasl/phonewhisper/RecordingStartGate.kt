package com.kafkasl.phonewhisper

/** Pure guard for the prerequisites that must hold before opening AudioRecord. */
internal object RecordingStartGate {
    enum class Decision { START, LOADING, RELOAD_REQUIRED, UNAVAILABLE }

    fun decide(
        localLoading: Boolean,
        selectedModel: String,
        loadedModel: String?,
        hasAsrEngine: Boolean,
    ): Decision = when {
        localLoading -> Decision.LOADING
        selectedModel != loadedModel -> Decision.RELOAD_REQUIRED
        !hasAsrEngine -> Decision.UNAVAILABLE
        else -> Decision.START
    }
}
