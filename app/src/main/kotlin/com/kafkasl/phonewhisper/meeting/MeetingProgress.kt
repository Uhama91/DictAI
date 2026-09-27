package com.kafkasl.phonewhisper.meeting

/** Content-free timing and backlog data for one meeting audio session. */
class MeetingProgressSnapshot internal constructor(
    val capturedAudioMs: Long,
    val processedAudioMs: Long,
    val pendingAudioMs: Long,
    val queuedAudioMs: Long,
    val inFlightAudioMs: Long,
    val discardedAudioMs: Long,
    val nativeProcessingMs: Long,
    val inFlightProcessingMs: Long,
    val processingCostRatio: Double?,
    val captureElapsedMs: Long,
) {
    companion object {
        val EMPTY = MeetingProgressSnapshot(
            capturedAudioMs = 0L,
            processedAudioMs = 0L,
            pendingAudioMs = 0L,
            queuedAudioMs = 0L,
            inFlightAudioMs = 0L,
            discardedAudioMs = 0L,
            nativeProcessingMs = 0L,
            inFlightProcessingMs = 0L,
            processingCostRatio = null,
            captureElapsedMs = 0L,
        )
    }
}

/**
 * Tracks accepted PCM duration and the wall time spent inside synchronous `acceptPcm16` calls.
 * Open/model loading and finalization are intentionally excluded from the processing cost ratio.
 * No PCM or transcript content is retained.
 */
internal class MeetingProgressTracker(
    private val monotonicClockNanos: () -> Long,
) {
    private val lock = Any()
    private var capturedBytes = 0L
    private var processedBytes = 0L
    private var discardedBytes = 0L
    private var inFlightBytes = 0L
    private var nativeStartedAtNanos: Long? = null
    private var nativeProcessingNanos = 0L
    private var firstPcmAcceptedAtNanos: Long? = null
    private var acceptingAbandoned = false

    /** Called by the audio queue after committing a block and before waking its consumer. */
    fun accepted(bytes: Int) {
        if (bytes <= 0) return
        synchronized(lock) {
            val acceptedAt = monotonicClockNanos()
            if (firstPcmAcceptedAtNanos == null) firstPcmAcceptedAtNanos = acceptedAt
            capturedBytes += bytes.toLong()
            if (acceptingAbandoned) discardedBytes += bytes.toLong()
        }
    }

    /** Returns false if cancellation already abandoned waiting PCM before JNI started. */
    fun beginNative(bytes: Int): Boolean {
        if (bytes <= 0) return false
        synchronized(lock) {
            if (acceptingAbandoned || nativeStartedAtNanos != null) return false
            val waitingBytes = (capturedBytes - processedBytes - discardedBytes - inFlightBytes).coerceAtLeast(0L)
            if (bytes.toLong() > waitingBytes) return false
            inFlightBytes += bytes.toLong()
            nativeStartedAtNanos = monotonicClockNanos()
            return true
        }
    }

    /** A normally returned accept call has processed its whole PCM block. */
    fun completeNative(bytes: Int, succeeded: Boolean) {
        if (bytes <= 0) return
        synchronized(lock) {
            val startedAt = nativeStartedAtNanos ?: return
            val audioBytes = inFlightBytes.coerceAtMost(bytes.toLong())
            if (audioBytes == 0L) return
            val elapsedNanos = elapsedNanos(monotonicClockNanos(), startedAt)
            inFlightBytes -= audioBytes
            nativeStartedAtNanos = null
            if (succeeded) {
                processedBytes += audioBytes
                nativeProcessingNanos += elapsedNanos
            } else {
                discardedBytes += audioBytes
                abandonWaitingLocked()
            }
        }
    }

    /** Moves queued or not-yet-started PCM to discarded; an active call remains visible. */
    fun abandonWaiting() {
        synchronized(lock) {
            acceptingAbandoned = true
            abandonWaitingLocked()
        }
    }

    fun snapshot(): MeetingProgressSnapshot = synchronized(lock) {
        val now = monotonicClockNanos()
        val pendingBytes = (capturedBytes - processedBytes - discardedBytes).coerceAtLeast(0L)
        val waitingBytes = (pendingBytes - inFlightBytes).coerceAtLeast(0L)
        val nativeStartedAt = nativeStartedAtNanos
        val currentNativeNanos = nativeStartedAt?.let { elapsedNanos(now, it) } ?: 0L
        val audioDurationNanos = processedBytes.toDouble() * NANOS_PER_SECOND / PCM16_BYTES_PER_SECOND
        val firstAcceptedAt = firstPcmAcceptedAtNanos
        MeetingProgressSnapshot(
            capturedAudioMs = bytesToAudioMs(capturedBytes),
            processedAudioMs = bytesToAudioMs(processedBytes),
            pendingAudioMs = bytesToAudioMs(pendingBytes),
            queuedAudioMs = bytesToAudioMs(waitingBytes),
            inFlightAudioMs = bytesToAudioMs(inFlightBytes),
            discardedAudioMs = bytesToAudioMs(discardedBytes),
            nativeProcessingMs = nativeProcessingNanos / NANOS_PER_MILLISECOND,
            inFlightProcessingMs = currentNativeNanos / NANOS_PER_MILLISECOND,
            processingCostRatio = if (processedBytes == 0L || audioDurationNanos <= 0.0) {
                null
            } else {
                nativeProcessingNanos.toDouble() / audioDurationNanos
            },
            captureElapsedMs = firstAcceptedAt?.let { elapsedNanos(now, it) / NANOS_PER_MILLISECOND } ?: 0L,
        )
    }

    private fun abandonWaitingLocked() {
        val waitingBytes = (capturedBytes - processedBytes - discardedBytes - inFlightBytes).coerceAtLeast(0L)
        discardedBytes += waitingBytes
    }

    private fun elapsedNanos(now: Long, start: Long): Long = (now - start).coerceAtLeast(0L)

    private fun bytesToAudioMs(bytes: Long): Long = bytes / MeetingEngine.BYTES_PER_MILLISECOND

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val PCM16_BYTES_PER_SECOND = 32_000.0
    }
}
