package com.kafkasl.phonewhisper

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Service-owned resident model. All extraction/loading/generation happens off the UI thread. */
internal class LocalFormatEngine(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val lock = Any()
    private val prefixes by lazy {
        LocalLayoutKind.entries.associateWith { kind ->
            app.assets.open("local-format/layout-${kind.assetName}.prompt").bufferedReader().use { it.readText() }
        }
    }
    @Volatile private var native: LocalFormatNative? = null
    @Volatile private var closed = false
    @Volatile private var activeOwner: Any? = null

    private val warming = java.util.concurrent.atomic.AtomicBoolean()
    fun runtimeName(): String = native?.runtimeName ?: "not-loaded"
    /** Blocking; benchmark worker only. Includes extraction on the first installation. */
    fun prepareForBenchmark(): Long = synchronized(lock) {
        check(!closed)
        val started = SystemClock.elapsedRealtime()
        check(load() != null)
        SystemClock.elapsedRealtime() - started
    }

    fun warm() {
        if (closed || native != null || !warming.compareAndSet(false, true)) return
        Thread({ try { runCatching { synchronized(lock) { load() } } } finally { warming.set(false) } }, "dictai-format-warm").apply {
            isDaemon = true
            start()
        }
    }

    fun backend(): LocalFormatBackend = object : LocalFormatBackend {
        private val cancellationEpoch = java.util.concurrent.atomic.AtomicLong()
        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? {
          val policy = request.layoutPolicy() ?: return null
          if (closed) return null
          policy.directResult?.let { return it }
          val epoch = cancellationEpoch.get()
          val started = SystemClock.elapsedRealtime()
          return synchronized(lock) {
            if (closed || cancellationEpoch.get() != epoch) return@synchronized null
            activeOwner = this
            try {
                val model = load()
                val remaining = 20_000L - (SystemClock.elapsedRealtime() - started)
                if (remaining <= 0L) null else model?.generate(policy.prompt(prefixes.getValue(policy.kind)), request.outputTokenBudget(), remaining, onChunk, grammar = policy.grammar()) {
                    closed || cancellationEpoch.get() != epoch
                }
            } catch (error: Throwable) {
                Log.w("LocalFormat", "event=format outcome=failed type=${error.javaClass.simpleName}")
                null
            } finally {
                activeOwner = null
                Log.i("LocalFormat", "event=format elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            }
          }
        }

        override fun cancel() {
            cancellationEpoch.incrementAndGet()
            if (activeOwner === this) native?.cancel()
        }
    }

    private fun load(): LocalFormatNative? {
        if (closed) return null
        native?.let { return it }
        val started = SystemClock.elapsedRealtime()
        val model = extractModel()
        if (closed) return null
        return LocalFormatNative.open(model.absolutePath, contextSize = 4096, threads = 2).also {
            native = it
            Log.i("LocalFormat", "event=model_ready elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    private fun extractModel(): File = synchronized(extractionLock) {
        val directory = File(app.filesDir, "local-format").apply { check(isDirectory || mkdirs()) }
        val output = File(directory, "${MODEL_SHA.take(16)}.gguf")
        if (output.isFile && output.length() == MODEL_SIZE) return@synchronized output
        val partial = File(directory, "${MODEL_SHA.take(16)}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        app.assets.open("local-format/$MODEL_FILE").use { input ->
            partial.outputStream().use { target ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    check(!closed) { "Formatter closed" }
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    check(count <= MODEL_SIZE)
                    digest.update(buffer, 0, size)
                    target.write(buffer, 0, size)
                }
            }
        }
        check(count == MODEL_SIZE && digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA)
        check(partial.renameTo(output)) { "Cannot publish local formatter" }
        output
    }

    override fun close() {
        closed = true
        native?.cancel()
        Thread({ synchronized(lock) { native?.close(); native = null } }, "dictai-format-close").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private val extractionLock = Any()
        const val MODEL_FILE = "LFM2.5-350M-Q4_K_M.gguf"
        const val MODEL_SIZE = 229312224L
        const val MODEL_SHA = "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4"
    }
}
