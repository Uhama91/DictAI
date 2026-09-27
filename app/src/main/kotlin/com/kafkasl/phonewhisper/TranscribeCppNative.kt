package com.kafkasl.phonewhisper

import com.kafkasl.phonewhisper.meeting.HandyTokenWindow
import java.io.Closeable

/** Small, synchronized Kotlin facade over one native transcribe.cpp session. */
class TranscribeCppNative private constructor(
    private var handle: Long,
    private val bindings: Bindings,
) : Closeable {
    data class Text(
        val full: String,
        val committed: String,
        val tentative: String,
    )

    fun begin(language: String = DictationLanguage.FRENCH.transcribeCppLanguage) =
        withOpenHandle { bindings.begin(it, language) }

    fun feed(samples: FloatArray): Text {
        require(samples.isNotEmpty()) { "samples must not be empty" }
        return withOpenHandle { asText(bindings.feed(it, samples)) }
    }

    fun getText(): Text = withOpenHandle { asText(bindings.getText(it)) }

    /** Flushes only audio already fed to the stream; it never adds synthetic silence. */
    fun finish(): Text = withOpenHandle { asText(bindings.finish(it)) }

    /** Feeds little-endian mono PCM16 without a Kotlin-side FloatArray conversion. */
    internal fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) = withOpenHandle { openHandle ->
        requirePcm16Length(buffer, lengthBytes)
        bindings.acceptPcm16(openHandle, buffer, lengthBytes)
    }

    internal fun tokenSnapshot(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow =
        withOpenHandle { openHandle ->
            validateTokenWindow(firstTokenIndex, maxTokens)
            bindings.tokenSnapshot(openHandle, firstTokenIndex, maxTokens)
        }

    /** Finalizes only audio already fed, then copies the requested token window. */
    internal fun finishTokenSnapshot(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow =
        withOpenHandle { openHandle ->
            validateTokenWindow(firstTokenIndex, maxTokens)
            bindings.finishTokenSnapshot(openHandle, firstTokenIndex, maxTokens)
        }

    fun reset() = withOpenHandle { bindings.reset(it) }

    override fun close() {
        synchronized(this) {
            if (handle == 0L) return
            val openHandle = handle
            handle = 0L
            bindings.free(openHandle)
        }
    }

    private fun <T> withOpenHandle(block: (Long) -> T): T = synchronized(this) {
        check(handle != 0L) { "TranscribeCppNative session is closed" }
        block(handle)
    }

    private fun asText(parts: Array<String>): Text {
        check(parts.size == TEXT_PART_COUNT) { "Native text snapshot must contain $TEXT_PART_COUNT parts" }
        return Text(full = parts[0], committed = parts[1], tentative = parts[2])
    }

    private fun validateTokenWindow(firstTokenIndex: Int, maxTokens: Int) {
        require(firstTokenIndex >= 0) { "firstTokenIndex must not be negative" }
        require(maxTokens in 0..MAX_TOKEN_WINDOW) { "maxTokens must be between 0 and $MAX_TOKEN_WINDOW" }
    }

    private fun requirePcm16Length(buffer: ByteArray, lengthBytes: Int) {
        require(lengthBytes in PCM16_SAMPLE_BYTES..buffer.size && lengthBytes % PCM16_SAMPLE_BYTES == 0) {
            "lengthBytes must select complete PCM16 samples from the buffer"
        }
    }

    internal interface Bindings {
        fun open(modelPath: String): Long
        fun begin(handle: Long, language: String)
        fun feed(handle: Long, samples: FloatArray): Array<String>
        fun getText(handle: Long): Array<String>
        fun finish(handle: Long): Array<String>
        fun reset(handle: Long)
        fun free(handle: Long)

        // Existing dictation fakes need not implement the additive meeting-only API.
        fun acceptPcm16(handle: Long, buffer: ByteArray, lengthBytes: Int) {
            throw UnsupportedOperationException("PCM16 feed is not implemented by these bindings")
        }

        fun tokenSnapshot(handle: Long, firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow {
            throw UnsupportedOperationException("Token snapshots are not implemented by these bindings")
        }

        fun finishTokenSnapshot(
            handle: Long,
            firstTokenIndex: Int,
            maxTokens: Int,
        ): HandyTokenWindow {
            throw UnsupportedOperationException("Token snapshots are not implemented by these bindings")
        }
    }

    private object JniBindings : Bindings {
        init {
            System.loadLibrary("transcribe_jni")
        }

        external override fun open(modelPath: String): Long
        external override fun begin(handle: Long, language: String)
        external override fun feed(handle: Long, samples: FloatArray): Array<String>
        external override fun getText(handle: Long): Array<String>
        external override fun finish(handle: Long): Array<String>
        external override fun reset(handle: Long)
        external override fun free(handle: Long)
        external override fun acceptPcm16(handle: Long, buffer: ByteArray, lengthBytes: Int)
        external override fun tokenSnapshot(
            handle: Long,
            firstTokenIndex: Int,
            maxTokens: Int,
        ): HandyTokenWindow
        external override fun finishTokenSnapshot(
            handle: Long,
            firstTokenIndex: Int,
            maxTokens: Int,
        ): HandyTokenWindow
    }

    companion object {
        private const val TEXT_PART_COUNT = 3
        private const val PCM16_SAMPLE_BYTES = 2
        private const val MAX_TOKEN_WINDOW = 8_192

        fun open(modelPath: String): TranscribeCppNative {
            require(modelPath.isNotBlank()) { "modelPath must not be blank" }
            return openUsingBindings(modelPath, JniBindings)
        }

        internal fun openUsingBindings(modelPath: String, bindings: Bindings): TranscribeCppNative {
            require(modelPath.isNotBlank()) { "modelPath must not be blank" }
            val handle = bindings.open(modelPath)
            require(handle != 0L) { "Native open returned an invalid handle" }
            return TranscribeCppNative(handle, bindings)
        }

        internal fun forTesting(handle: Long, bindings: Bindings): TranscribeCppNative {
            require(handle != 0L) { "handle must not be zero" }
            return TranscribeCppNative(handle, bindings)
        }
    }
}

class TranscribeCppNativeException(message: String) : IllegalStateException(message)
