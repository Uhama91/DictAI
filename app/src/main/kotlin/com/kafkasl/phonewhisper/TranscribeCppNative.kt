package com.kafkasl.phonewhisper

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

    internal interface Bindings {
        fun open(modelPath: String): Long
        fun begin(handle: Long, language: String)
        fun feed(handle: Long, samples: FloatArray): Array<String>
        fun getText(handle: Long): Array<String>
        fun finish(handle: Long): Array<String>
        fun reset(handle: Long)
        fun free(handle: Long)
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
    }

    companion object {
        private const val TEXT_PART_COUNT = 3

        fun open(modelPath: String): TranscribeCppNative {
            require(modelPath.isNotBlank()) { "modelPath must not be blank" }
            return TranscribeCppNative(JniBindings.open(modelPath), JniBindings)
        }

        internal fun forTesting(handle: Long, bindings: Bindings): TranscribeCppNative {
            require(handle != 0L) { "handle must not be zero" }
            return TranscribeCppNative(handle, bindings)
        }
    }
}

class TranscribeCppNativeException(message: String) : IllegalStateException(message)
