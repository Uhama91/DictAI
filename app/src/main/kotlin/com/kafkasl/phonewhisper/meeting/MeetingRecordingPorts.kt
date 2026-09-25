package com.kafkasl.phonewhisper.meeting

import java.util.concurrent.CompletableFuture

internal enum class MeetingModelAvailability {
    READY,
    MISSING,
    DOWNLOADING,
}

internal fun interface MeetingModelAvailabilityPort {
    fun currentAvailability(): MeetingModelAvailability
}

/** An explicit, cancellable request. A late lease must still be released after cancel(). */
internal interface MeetingNativeReservationRequest {
    val lease: CompletableFuture<MeetingNativeRuntimeLease>
    fun cancel(): Unit
}

internal fun interface MeetingNativeReservationPort {
    fun request(runId: String): MeetingNativeReservationRequest
}

/** Poisoning keeps the process-wide runtime unavailable until process death. */
internal interface MeetingNativeRuntimeLease {
    fun isCurrentAndUsable(): Boolean
    fun release(): Unit
    fun poison(): Unit
}

internal interface MeetingSessionFactoryPort {
    fun start(
        runId: String,
        language: String,
        onReady: () -> Unit,
        onUpdate: (MeetingHypothesis) -> Unit,
        onFailure: (String) -> Unit,
    ): MeetingSession
}

internal fun interface MeetingMicrophoneFactoryPort {
    fun create(): MeetingMicrophonePort
}

/** stopAndJoin asynchronously stops and releases the current recorder. */
internal interface MeetingMicrophonePort {
    fun start(
        onPcm16: (ByteArray, Int) -> Boolean,
        onFailure: (String) -> Unit,
    ): Boolean

    fun stopAndJoin(): CompletableFuture<Unit>
}

internal fun interface MeetingNotePublisherPort {
    fun save(document: MeetingDocument): CompletableFuture<Unit>

    fun hasAttachments(sessionId: String): Boolean = false

    fun hasSavedNote(sessionId: String): Boolean = false
}

/** Implementations must enqueue FIFO, even when called from the main thread. */
internal fun interface MeetingMainDispatcher {
    fun post(task: () -> Unit): Unit
}

internal fun interface MeetingFocusedEditPort {
    fun flushFocusedEdit(): Boolean
}

internal data class MeetingRecordingPorts(
    val modelAvailability: MeetingModelAvailabilityPort,
    val reservation: MeetingNativeReservationPort,
    val sessionFactory: MeetingSessionFactoryPort,
    val microphoneFactory: MeetingMicrophoneFactoryPort,
    val notePublisher: MeetingNotePublisherPort,
    val mainDispatcher: MeetingMainDispatcher,
    val focusedEdit: MeetingFocusedEditPort,
)

internal enum class MeetingRecordingPhase {
    DOCUMENT,
    PREPARING,
    LISTENING,
    PAUSING,
    PAUSED,
    FINALIZING,
    CLOSING,
    FINISHED,
    MODEL_UNAVAILABLE,
    ERROR,
}

internal data class MeetingRecordingState(
    val document: MeetingDocument,
    val phase: MeetingRecordingPhase,
    val captureActive: Boolean = false,
    val recordingError: String? = null,
    val saveError: String? = null,
)
