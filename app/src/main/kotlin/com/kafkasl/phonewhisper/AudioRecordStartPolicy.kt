package com.kafkasl.phonewhisper

/** Android-independent decisions for AudioRecord’s final startup preconditions. */
internal object AudioRecordStartPolicy {
    enum class Decision { START, INVALID_BUFFER, UNINITIALIZED, NOT_RECORDING }

    fun decide(
        bufferSize: Int,
        isInitialized: Boolean,
        isRecording: Boolean,
    ): Decision = when {
        bufferSize <= 0 -> Decision.INVALID_BUFFER
        !isInitialized -> Decision.UNINITIALIZED
        !isRecording -> Decision.NOT_RECORDING
        else -> Decision.START
    }
}
