package com.kafkasl.phonewhisper.meeting

import java.io.Closeable

/** A bounded, frame-major window of standalone diarizer probabilities. */
internal data class DiarizationFrameWindow(
    val firstFrameIndex: Long,
    val secondsPerFrame: Double,
    val probabilities: FloatArray,
    val speakerCount: Int,
    val stableFrameCount: Long,
    val totalFrameCount: Long,
) {
    init {
        require(firstFrameIndex >= 0L) { "firstFrameIndex must not be negative" }
        require(secondsPerFrame.isFinite() && secondsPerFrame > 0.0) {
            "secondsPerFrame must be finite and positive"
        }
        require(speakerCount > 0) { "speakerCount must be positive" }
        require(stableFrameCount in 0L..totalFrameCount) {
            "stableFrameCount must be within totalFrameCount"
        }
        require(firstFrameIndex <= totalFrameCount) { "firstFrameIndex exceeds totalFrameCount" }
        require(probabilities.size % speakerCount == 0) {
            "Probability count must contain complete speaker rows"
        }
        require(firstFrameIndex + probabilities.size / speakerCount <= totalFrameCount) {
            "Frame window extends past totalFrameCount"
        }
        require(probabilities.size / speakerCount <= MAX_FRAME_WINDOW) {
            "Frame window exceeds $MAX_FRAME_WINDOW frames"
        }
    }

    private companion object {
        const val MAX_FRAME_WINDOW = 16_384
    }
}

/** Dedicated native Sortformer worker; it does not load or call any ASR model. */
internal class DiarizationNative internal constructor(
    handle: Long,
    private val bindings: Bindings,
) : Closeable {
    internal interface Bindings {
        fun open(diarPath: String): Long
        fun acceptPcm16(handle: Long, buffer: ByteArray, lengthBytes: Int)
        fun snapshot(handle: Long, firstFrameIndex: Long, maxFrames: Int): DiarizationFrameWindow
        fun finish(handle: Long)
        fun close(handle: Long)
    }

    private var handle: Long = handle.also { require(it > 0L) { "handle must be positive" } }
    private var finished = false

    @Synchronized
    fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) {
        ensureOpen()
        check(!finished) { "Diarization session is already finished" }
        require(lengthBytes in PCM16_SAMPLE_BYTES..buffer.size && lengthBytes % PCM16_SAMPLE_BYTES == 0) {
            "lengthBytes must select complete PCM16 samples from the buffer"
        }
        bindings.acceptPcm16(handle, buffer, lengthBytes)
    }

    @Synchronized
    fun snapshot(firstFrameIndex: Long, maxFrames: Int = MAX_FRAME_WINDOW): DiarizationFrameWindow {
        ensureOpen()
        require(firstFrameIndex >= 0L) { "firstFrameIndex must not be negative" }
        require(maxFrames in 0..MAX_FRAME_WINDOW) {
            "maxFrames must be between 0 and $MAX_FRAME_WINDOW"
        }
        return bindings.snapshot(handle, firstFrameIndex, maxFrames)
    }

    @Synchronized
    fun finish() {
        ensureOpen()
        if (finished) return
        bindings.finish(handle)
        finished = true
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        val closingHandle = handle
        handle = 0L
        bindings.close(closingHandle)
    }

    private fun ensureOpen() {
        check(handle != 0L) { "Diarization session is closed" }
    }

    internal companion object {
        const val MAX_FRAME_WINDOW = 16_384
        private const val PCM16_SAMPLE_BYTES = 2

        fun open(diarPath: String): DiarizationNative {
            require(diarPath.isNotBlank()) { "diarPath must not be blank" }
            return DiarizationNative(JniBindings.open(diarPath), JniBindings)
        }
    }

    private object JniBindings : Bindings {
        init {
            System.loadLibrary("dictai_meeting")
        }

        external override fun open(diarPath: String): Long
        external override fun acceptPcm16(handle: Long, buffer: ByteArray, lengthBytes: Int)
        external override fun snapshot(
            handle: Long,
            firstFrameIndex: Long,
            maxFrames: Int,
        ): DiarizationFrameWindow
        external override fun finish(handle: Long)
        external override fun close(handle: Long)
    }
}
