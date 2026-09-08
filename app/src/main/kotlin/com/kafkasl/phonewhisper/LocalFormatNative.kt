package com.kafkasl.phonewhisper

import java.util.concurrent.atomic.AtomicLong

/** Resident CPU runtime. Generation is serialized; cancellation never waits for inference. */
internal class LocalFormatNative private constructor(
    private val handle: Long,
    private val bindings: LocalFormatNativeApi,
) : AutoCloseable {
    val runtimeName: String get() = bindings.runtimeName
    private val lock = Any()
    private val sequence = AtomicLong()
    private val activeGeneration = AtomicLong()
    @Volatile private var closed = false

    fun generate(prompt: String, maxTokens: Int, timeoutMs: Long, onChunk: (String) -> Unit,
        grammar: String? = null, isCancelled: () -> Boolean = { false }): String? =
        synchronized(lock) {
            if (closed || maxTokens <= 0 || timeoutMs <= 0) return@synchronized null
            val generation = sequence.incrementAndGet()
            activeGeneration.set(generation)
            try {
                if (closed || isCancelled()) return@synchronized null
                bindings.generate(handle, generation, prompt.toByteArray(Charsets.UTF_8),
                    grammar?.toByteArray(Charsets.UTF_8), maxTokens, timeoutMs, object : LocalFormatChunkSink {
                        override fun onBytes(bytes: ByteArray) { onChunk(bytes.toString(Charsets.UTF_8)) }
                    })?.toString(Charsets.UTF_8)
            } finally { activeGeneration.compareAndSet(generation, 0) }
        }

    fun cancel() {
        val generation = activeGeneration.get()
        if (generation != 0L) bindings.cancel(handle, generation)
    }

    override fun close() {
        closed = true
        cancel()
        synchronized(lock) {
            bindings.close(handle)
        }
    }

    companion object {
        private val preferredBindings: LocalFormatNativeApi by lazy {
            if (LocalFormatBindings.supportsArm82()) {
                try { LocalFormatArm82Bindings } catch (_: LinkageError) { LocalFormatBindings }
            } else LocalFormatBindings
        }
        fun open(modelPath: String, contextSize: Int = 4096, threads: Int = 2,
            preferOptimized: Boolean = true): LocalFormatNative {
            require(contextSize in 512..8192 && threads in 1..8)
            val bindings = if (preferOptimized) preferredBindings else LocalFormatBindings
            val handle = bindings.open(modelPath.toByteArray(Charsets.UTF_8), contextSize, threads)
            check(handle != 0L) { "Unable to load local formatting model" }
            return LocalFormatNative(handle, bindings)
        }
    }
}

internal interface LocalFormatChunkSink { fun onBytes(bytes: ByteArray) }

internal interface LocalFormatNativeApi {
    val runtimeName: String
    fun open(path: ByteArray, contextSize: Int, threads: Int): Long
    fun generate(handle: Long, generation: Long, prompt: ByteArray, grammar: ByteArray?, maxTokens: Int,
        timeoutMs: Long, sink: LocalFormatChunkSink): ByteArray?
    fun cancel(handle: Long, generation: Long)
    fun close(handle: Long)
}

internal object LocalFormatBindings : LocalFormatNativeApi {
    init { System.loadLibrary("dictai_llm") }
    override val runtimeName: String = "arm64-baseline"
    external fun supportsArm82(): Boolean
    external override fun open(path: ByteArray, contextSize: Int, threads: Int): Long
    external override fun generate(handle: Long, generation: Long, prompt: ByteArray, grammar: ByteArray?, maxTokens: Int,
        timeoutMs: Long, sink: LocalFormatChunkSink): ByteArray?
    external override fun cancel(handle: Long, generation: Long)
    external override fun close(handle: Long)
}

internal object LocalFormatArm82Bindings : LocalFormatNativeApi {
    init { System.loadLibrary("dictai_llm_arm82") }
    override val runtimeName: String = "arm64-dotprod-fp16"
    external override fun open(path: ByteArray, contextSize: Int, threads: Int): Long
    external override fun generate(handle: Long, generation: Long, prompt: ByteArray, grammar: ByteArray?, maxTokens: Int,
        timeoutMs: Long, sink: LocalFormatChunkSink): ByteArray?
    external override fun cancel(handle: Long, generation: Long)
    external override fun close(handle: Long)
}
