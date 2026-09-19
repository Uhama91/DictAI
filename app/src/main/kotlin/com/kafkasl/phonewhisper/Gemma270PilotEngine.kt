package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** CPU Gemma runtime used only by the opt-in pilot build. */
internal class Gemma270PilotCore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dictai-gemma270-cpu").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private val owners = AtomicInteger()
    private val nativeCancellation = Gemma270PilotCancellationGate {
        native?.cancel()
    }
    private var modelStore: Gemma270PilotModelStore? = null
    @Volatile private var native: LocalFormatNative? = null
    @Volatile var runtimeName: String = "not-loaded"
        private set
    @Volatile var failureCode: String? = null
        private set
    @Volatile var isLoaded: Boolean = false
        private set
    @Volatile var lastLoadMs: Long? = null
        private set

    val isClosed: Boolean get() = closed.get()

    fun retain() {
        check(!closed.get()) { "Formatter closed" }
        owners.incrementAndGet()
    }

    fun release() {
        check(owners.decrementAndGet() >= 0)
        if (owners.get() == 0) close()
    }

    fun backend(onClosed: () -> Unit = {}): PilotBackend = PilotBackend(onClosed)

    fun warm() {
        if (closed.get()) return
        worker.execute { runCatching { load() } }
    }

    fun prepareForBenchmarkInfo(): LocalFormatPreparation {
        requireWorkerThread()
        check(!closed.get()) { "Formatter closed" }
        val started = SystemClock.elapsedRealtime()
        val alreadyLoaded = isLoaded
        val result = CompletableFuture<Unit>()
        worker.execute {
            runCatching { load() }.onSuccess { result.complete(Unit) }
                .onFailure { result.completeExceptionally(it) }
        }
        result.get(Gemma270PilotSupport.DEADLINE_MS, TimeUnit.MILLISECONDS)
        return LocalFormatPreparation(
            loadMs = lastLoadMs ?: 0L,
            wasAlreadyLoaded = alreadyLoaded,
            waitMs = SystemClock.elapsedRealtime() - started,
        )
    }

    fun prepareForBenchmark(): Long = prepareForBenchmarkInfo().waitMs

    fun generate(
        request: LocalFormatRequest,
        onChunk: (String) -> Unit,
        onNativeStart: () -> Unit,
        job: Gemma270PilotJob,
    ): String? {
        requireWorkerThread()
        if (closed.get() || !request.isGemma270Pilot ||
            !Gemma270PilotSupport.accepts(request.language, request.layoutKind?.formatId ?: "")) {
            failureCode = "unsupported_request"
            return null
        }
        val result = CompletableFuture<String?>()
        worker.execute {
            if (closed.get() || job.cancelled.get()) {
                result.complete(null)
                return@execute
            }
            try {
                load()
                if (!nativeCancellation.activate(job)) {
                    result.complete(null)
                    return@execute
                }
                failureCode = null
                // The native API can emit chunks for diagnostics, but the pilot
                // deliberately buffers them: only an EOS-complete result returns.
                val output = checkNotNull(native).generate(
                    prompt = request.prompt(),
                    maxTokens = request.outputTokenBudget(),
                    timeoutMs = Gemma270PilotSupport.DEADLINE_MS,
                    onChunk = {},
                    onNativeStart = onNativeStart,
                    isCancelled = { closed.get() || job.cancelled.get() },
                    greedy = true,
                )
                if (closed.get() || job.cancelled.get()) result.complete(null)
                else result.complete(output)
            } catch (_: TimeoutException) {
                failureCode = "generation_timeout"
                result.complete(null)
            } catch (_: Throwable) {
                failureCode = failureCode ?: "generation_error"
                result.complete(null)
            } finally {
                nativeCancellation.clear(job)
            }
        }
        return try {
            result.get(Gemma270PilotSupport.DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            failureCode = "generation_timeout"
            job.cancel()
            null
        } catch (_: InterruptedException) {
            job.cancel()
            Thread.currentThread().interrupt()
            null
        } catch (_: Throwable) {
            failureCode = failureCode ?: "generation_error"
            null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        nativeCancellation.close()
        worker.execute {
            native?.close()
            native = null
            isLoaded = false
        }
        worker.shutdown()
    }

    private fun load() {
        if (native != null) return
        check(!closed.get()) { "Formatter closed" }
        val started = SystemClock.elapsedRealtime()
        val store = modelStore ?: runCatching { Gemma270PilotModelStore(appContext) }
            .onFailure {
                failureCode = "model_manifest_missing"
                runtimeName = "model_manifest_missing"
            }
            .getOrNull()
            ?: throw IllegalStateException("Gemma 270M pilot manifest unavailable")
        modelStore = store
        val model = store.installFromAsset()
            ?: run {
                failureCode = "model_integrity_failed"
                runtimeName = "model_integrity_failed"
                throw IllegalStateException("Gemma 270M pilot asset unavailable")
            }
        runtimeName = "loading"
        val opened = runCatching {
            LocalFormatNative.open(
                modelPath = model.absolutePath,
                contextSize = Gemma270PilotSupport.CONTEXT_SIZE,
                threads = 2,
                preferOptimized = true,
            )
        }.getOrElse {
            failureCode = "native_open_failed"
            runtimeName = "native_open_failed"
            throw it
        }
        native = opened
        isLoaded = true
        failureCode = null
        runtimeName = Gemma270PilotSupport.RUNTIME_NAME
        lastLoadMs = SystemClock.elapsedRealtime() - started
    }

    internal inner class PilotBackend(private val onClosed: () -> Unit) : LocalFormatBackend, LocalFormatBackendOwner {
        private val closed = AtomicBoolean()
        private val activeLock = Any()
        private var activeJob: Gemma270PilotJob? = null

        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? =
            generate(request, onChunk, {})

        override fun generate(
            request: LocalFormatRequest,
            onChunk: (String) -> Unit,
            onNativeStart: () -> Unit,
        ): String? {
            if (closed.get()) return null
            val job = Gemma270PilotJob()
            synchronized(activeLock) {
                if (closed.get()) return null
                activeJob = job
            }
            return try {
                this@Gemma270PilotCore.generate(request, onChunk, onNativeStart, job)
            } finally {
                synchronized(activeLock) {
                    if (activeJob === job) activeJob = null
                }
            }
        }

        override fun cancel() {
            val job = synchronized(activeLock) { activeJob }
            job?.cancel()
        }

        override fun closeOwner() = closeOwner(notify = true)

        fun closeOwner(notify: Boolean) {
            val job = synchronized(activeLock) {
                if (!closed.compareAndSet(false, true)) return
                activeJob
            }
            job?.cancel()
            if (notify) onClosed()
        }
    }

    private fun requireWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Local formatter requires a worker thread" }
    }
}

/** Cancellation belongs to one backend/job, so an old session cannot cancel a newer one. */
internal class Gemma270PilotJob {
    @Volatile var cancelNative: (() -> Unit)? = null
    val cancelled = AtomicBoolean()

    fun cancel() {
        cancelled.set(true)
        cancelNative?.invoke()
    }
}

/**
 * Binds native cancellation to the job that owns the current serialized call.
 * The lock covers only activation, deactivation, and the short native cancel
 * dispatch; generation itself never runs under this lock.
 */
internal class Gemma270PilotCancellationGate(private val cancelCurrent: () -> Unit) {
    private val lock = Any()
    private var active: Gemma270PilotJob? = null
    private var closed = false

    fun activate(job: Gemma270PilotJob): Boolean = synchronized(lock) {
        if (closed || job.cancelled.get()) return@synchronized false
        active = job
        job.cancelNative = { cancel(job) }
        true
    }

    fun clear(job: Gemma270PilotJob) = synchronized(lock) {
        if (active === job) active = null
        if (job.cancelNative != null) job.cancelNative = null
    }

    private fun cancel(job: Gemma270PilotJob) = synchronized(lock) {
        if (active === job) cancelCurrent()
    }

    fun close() = synchronized(lock) {
        closed = true
        active?.let { job ->
            if (!job.cancelled.get()) job.cancelled.set(true)
            cancelCurrent()
        }
    }
}

private val LocalLayoutKind.formatId: String
    get() = when (this) {
        LocalLayoutKind.TEXT -> "corrected"
        LocalLayoutKind.LIST -> "list"
        LocalLayoutKind.EMAIL -> "email"
    }
