package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Loading time is separate from queue time; a benchmark can share an already resident engine. */
internal data class LocalFormatPreparation(val loadMs: Long, val wasAlreadyLoaded: Boolean, val waitMs: Long)

/**
 * A lightweight owner of the process-wide GPU engine. The overlay and benchmark share one model;
 * each request receives an independent conversation. No initialization or cancellation joins the UI.
 */
internal class LocalFormatEngine(context: Context) : AutoCloseable {
    private val core = synchronized(sharedLock) {
        (shared ?: GemmaEngineCore(context.applicationContext).also { shared = it }).also { it.retain() }
    }
    private val closed = AtomicBoolean()
    private val ownedJobs = ConcurrentHashMap.newKeySet<GemmaCall>()

    fun runtimeName(): String = core.runtimeName
    fun failureCode(): String? = core.failureCode
    fun isLoaded(): Boolean = core.isLoaded
    fun lastLoadMs(): Long? = core.lastLoadMs

    fun prepareForBenchmarkInfo(): LocalFormatPreparation {
        requireWorkerThread()
        check(!closed.get()) { "Formatter closed" }
        return core.prepare { closed.get() }
    }

    /** Kept for older callers. Use prepareForBenchmarkInfo to distinguish an already loaded model. */
    fun prepareForBenchmark(): Long = prepareForBenchmarkInfo().waitMs

    fun warm() {
        if (!closed.get()) core.warm()
    }

    fun backend(): LocalFormatBackend = GemmaBackend()

    private inner class GemmaBackend : LocalFormatBackend {
        private val cancellationEpoch = AtomicLong()
        private val jobs = ConcurrentHashMap.newKeySet<GemmaCall>()

        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? =
            generate(request, onChunk, {})

        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit, onNativeStart: () -> Unit): String? {
            requireWorkerThread()
            if (closed.get()) return null
            val epoch = cancellationEpoch.get()
            val call = GemmaCall(request, onChunk, onNativeStart) {
                closed.get() || cancellationEpoch.get() != epoch
            }
            // Reject unsupported/oversized layouts before queueing any load or native work.
            val policy = call.policy ?: return null
            // No engine load or GPU work for an acknowledgment.
            request.directOutput()?.let { return it }
            jobs.add(call)
            ownedJobs.add(call)
            try {
                // Cancellation may race with registration; the epoch still makes this call stale.
                if (call.isCancelled()) return null
                core.enqueue(call)
                return call.result.get(GENERATION_DEADLINE_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                core.cancel(call, "generation_timeout")
                throw TimeoutException("Local format deadline exceeded")
            } catch (_: InterruptedException) {
                core.cancel(call)
                Thread.currentThread().interrupt()
                return null
            } catch (error: ExecutionException) {
                // Do not pass native errors (which can contain model input) to UI/logging.
                throw IllegalStateException("Local GPU formatter failed: ${core.failureCode ?: "generation_error"}")
            } finally {
                jobs.remove(call)
                ownedJobs.remove(call)
            }
        }

        override fun cancel() {
            cancellationEpoch.incrementAndGet()
            jobs.forEach { core.cancel(it) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownedJobs.forEach { core.cancel(it) }
        ownedJobs.clear()
        core.release()
    }

    companion object {
        private val sharedLock = Any()
        private var shared: GemmaEngineCore? = null
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val GENERATION_DEADLINE_MS = 20_000L

        private fun requireWorkerThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Local formatter requires a worker thread" }
        }
    }
}

/** No prompt, result or native exception message leaves this object through a diagnostic. */
private class GemmaEngineCore(context: Context) {
    private val store = GemmaModelStore(context)
    private val cache = File(context.cacheDir, "gemma-litert-lm-0.17.0")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dictai-gemma-gpu").apply { isDaemon = true }
    }
    private val canceller = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dictai-gemma-cancel").apply { isDaemon = true }
    }
    private val loadWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dictai-gemma-load-deadline").apply { isDaemon = true }
    }
    private val owners = AtomicInteger()
    private val warming = AtomicBoolean()
    private val admission = GemmaRuntimeAdmission()
    // Engine creation, conversation lifetime and engine destruction belong to worker only.
    private var engine: Engine? = null
    @Volatile var runtimeName = "not-loaded"
        private set
    @Volatile var failureCode: String? = null
        private set
    @Volatile var isLoaded = false
        private set
    @Volatile var lastLoadMs: Long? = null
        private set

    fun retain() { owners.incrementAndGet() }

    fun release() {
        check(owners.decrementAndGet() >= 0)
        worker.execute {
            // An owner may have arrived while native cancellation was still finishing.
            if (owners.get() == 0) closeEngine()
        }
    }

    fun warm() {
        // Resident state is checked on worker: a final owner may currently be closing it.
        if (admission.isBlocked || !warming.compareAndSet(false, true)) return
        worker.execute {
            try {
                if (!admission.isBlocked && owners.get() > 0) runCatching { load() }
            } finally {
                warming.set(false)
            }
        }
    }

    fun prepare(isOwnerClosed: () -> Boolean): LocalFormatPreparation {
        val started = SystemClock.elapsedRealtime()
        val result = CompletableFuture<LocalFormatPreparation>()
        if (!admission.admit(result)) throw IllegalStateException("Local GPU runtime is waiting for native completion")
        worker.execute {
            if (result.isDone) return@execute
            try {
                check(!isOwnerClosed()) { "Formatter closed" }
                val alreadyLoaded = engine != null
                val loadMs = load()
                check(!isOwnerClosed()) { "Formatter closed" }
                result.complete(LocalFormatPreparation(loadMs, alreadyLoaded, SystemClock.elapsedRealtime() - started))
            } catch (_: Throwable) {
                result.completeExceptionally(IllegalStateException("Local GPU preparation failed: ${failureCode ?: "closed"}"))
            }
        }
        return result.get(45L, TimeUnit.SECONDS)
    }

    fun enqueue(call: GemmaCall) {
        if (call.policy == null) { call.result.complete(null); return }
        if (!admission.admit(call.result)) return
        worker.execute { if (!call.result.isDone) run(call) }
    }

    /** Never invoke JNI or wait on a conversation lock from a lifecycle/UI caller. */
    fun cancel(call: GemmaCall, code: String? = null) {
        if (code != null && !admission.isBlocked) failureCode = code
        call.cancel()
        if (call.cancelScheduled.compareAndSet(false, true)) {
            canceller.execute { call.cancelNative() }
        }
    }

    private fun run(call: GemmaCall) {
        if (call.isCancelled() || call.expired()) {
            call.cancel()
            return
        }
        val started = SystemClock.elapsedRealtime()
        var invalidateEngine = false
        try {
            load()
            if (call.isCancelled() || call.expired()) {
                cancel(call)
                return
            }
            failureCode = null
            runtimeName = "litert-lm-gpu-mtp-thinking-off"
            val config = ConversationConfig(
                systemInstruction = Contents.of(GemmaFormattingPrompt.system(call.request)),
                samplerConfig = SamplerConfig(topK = 50, topP = 0.95, temperature = 0.1, seed = 1234),
                automaticToolCalling = false,
                maxOutputToken = call.request.outputTokenBudget(),
                // Configure both controls explicitly: there is no reasoning budget to hide latency.
                thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                prefillPrefaceOnInit = false,
            )
            val conversation = checkNotNull(engine).createConversation(config)
            call.bind(conversation)
            try {
                val startedGeneration = call.start {
                    call.onNativeStart()
                    conversation.sendMessageAsync(
                        GemmaFormattingPrompt.user(call.request),
                        call.callback,
                        maxOutputToken = call.request.outputTokenBudget(),
                        thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    )
                }
                if (startedGeneration) {
                    var cancelledAt: Long? = null
                    while (!call.terminal.await(50L, TimeUnit.MILLISECONDS)) {
                        if (call.nativeFailure != null && !call.isCancelled()) {
                            failureCode = if (call.rejectedThinking) "thinking_not_disabled" else "generation_error"
                            call.result.completeExceptionally(IllegalStateException(failureCode))
                            cancel(call)
                        }
                        if (call.isCancelled() || call.expired()) {
                            val now = SystemClock.elapsedRealtime()
                            if (cancelledAt == null) cancelledAt = now
                            cancel(call, if (call.expired()) "generation_timeout" else null)
                            if (now - cancelledAt > 5_000L) {
                                // Do not free memory under an unresponsive native callback. Results
                                // are already discarded; the single worker prevents a second engine.
                                if (!admission.isBlocked) {
                                    runtimeName = "cancellation-pending"
                                    failureCode = "cancellation_pending"
                                    // Finish both existing queue waiters and new requests promptly.
                                    // Their abandoned worker tasks skip loading when eventually drained.
                                    admission.block()
                                }
                                invalidateEngine = true
                            }
                        }
                    }
                }
                val nativeFailure = call.nativeFailure
                if (nativeFailure != null && (!call.isCancelled() || nativeFailure !is CancellationException)) {
                    failureCode = if (call.rejectedThinking) "thinking_not_disabled" else "generation_error"
                    invalidateEngine = true
                    if (!call.isCancelled()) call.result.completeExceptionally(IllegalStateException(failureCode))
                } else if (!call.isCancelled() && !call.expired()) {
                    call.result.complete(call.output())
                } else call.cancel()
            } finally {
                // Native terminal callback has arrived, or sendMessageAsync did not begin.
                call.closeConversation()
            }
        } catch (_: Throwable) {
            if (!call.isCancelled()) {
                if (failureCode == null) failureCode = "generation_error"
                runtimeName = if (failureCode == "model_missing") "model-missing" else "gpu-error"
                call.result.completeExceptionally(IllegalStateException(failureCode))
            }
            invalidateEngine = true
        } finally {
            if (invalidateEngine) closeEngine(preserveFailure = true)
            Log.i("LocalFormat", "event=format runtime=litert-lm-gpu mtp=on thinking=off elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    /** Returns actual load time, zero when already resident. No CPU fallback is attempted. */
    @OptIn(ExperimentalApi::class)
    private fun load(): Long {
        engine?.let { return 0L }
        val model = store.installedModel()
        if (model == null) {
            runtimeName = "model-missing"
            failureCode = "model_missing"
            throw IllegalStateException("Gemma model is not installed")
        }
        runtimeName = "loading"
        failureCode = null
        lastLoadMs = null
        val started = SystemClock.elapsedRealtime()
        var opening: Engine? = null
        val deadline = GemmaInitializationDeadline(
            onTimeout = {
                runtimeName = "loading-timeout"
                failureCode = "initialization_timeout"
                // initialize() has no abort API. Reject callers now, keep its memory alive,
                // and resume admission only when the native call eventually returns.
                admission.block()
            },
            onReturnedAfterTimeout = { admission.resume() },
        )
        val alarm = loadWatchdog.schedule({ deadline.timeout() }, 45L, TimeUnit.SECONDS)
        try {
            check(cache.isDirectory || cache.mkdirs())
            // Native error text can contain prompts; expose only our fixed diagnostic codes.
            Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
            // The pinned E2B package includes the MTP head; GPU speculative decoding reduces
            // full-output time without enabling reasoning or switching to a CPU model.
            ExperimentalFlags.enableSpeculativeDecoding = true
            opening = Engine(EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.GPU(),
                maxNumTokens = 4096,
                cacheDir = cache.absolutePath,
                visionBackend = null,
                audioBackend = null,
            ))
            opening.initialize()
            deadline.returned()
            failureCode = null
            engine = opening
            isLoaded = true
            runtimeName = "litert-lm-gpu-mtp-thinking-off"
            val elapsed = SystemClock.elapsedRealtime() - started
            lastLoadMs = elapsed
            Log.i("LocalFormat", "event=model_ready runtime=litert-lm-gpu mtp=on thinking=off elapsed_ms=$elapsed")
            return elapsed
        } catch (_: Throwable) {
            deadline.returned()
            if (opening?.isInitialized() == true) runCatching { opening.close() }
            runtimeName = "gpu-error"
            failureCode = "gpu_initialization_failed"
            isLoaded = false
            throw IllegalStateException("Gemma GPU initialization failed")
        } finally {
            deadline.returned()
            alarm.cancel(false)
        }
    }

    private fun closeEngine(preserveFailure: Boolean = false) {
        val closing = engine
        engine = null
        isLoaded = false
        if (closing != null) runCatching { closing.close() }
        if (!preserveFailure) {
            runtimeName = "not-loaded"
            failureCode = null
        } else if (failureCode != "model_missing") runtimeName = "gpu-error"
        admission.resume()
    }
}

/** One inference, with a native resource lock independent from the caller/session's UI lock. */
internal class GemmaCall(
    val request: LocalFormatRequest,
    private val onChunk: (String) -> Unit,
    val onNativeStart: () -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val stale: () -> Boolean,
) {
    val policy = request.layoutPolicy()
    val result = CompletableFuture<String?>()
    val terminal = CountDownLatch(1)
    val cancelScheduled = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val createdAt = now()
    private val nativeLock = Any()
    private var conversation: Conversation? = null
    private val textLock = Any()
    private val text = StringBuilder()
    @Volatile var nativeFailure: Throwable? = null
        private set
    @Volatile var rejectedThinking = false
        private set

    fun isCancelled(): Boolean = cancelled.get() || stale()
    fun expired(): Boolean = now() - createdAt >= LocalFormatEngine.GENERATION_DEADLINE_MS
    fun cancel() { cancelled.set(true); result.complete(null) }
    fun bind(value: Conversation) { synchronized(nativeLock) { conversation = value } }
    fun start(send: () -> Unit): Boolean = synchronized(nativeLock) {
        if (policy == null || isCancelled() || expired()) false else { send(); true }
    }
    fun cancelNative() {
        synchronized(nativeLock) { conversation?.let { runCatching { it.cancelProcess() } } }
    }
    fun closeConversation() {
        synchronized(nativeLock) {
            conversation?.let { runCatching { it.close() } }
            conversation = null
        }
    }
    fun output(): String = synchronized(textLock) { text.toString() }

    val callback = object : MessageCallback {
        override fun onMessage(message: Message) {
            if (isCancelled() || expired() || nativeFailure != null) return
            if (message.channels["thought"]?.isNotBlank() == true || message.toolCalls.isNotEmpty()) {
                rejectedThinking = message.channels["thought"]?.isNotBlank() == true
                nativeFailure = IllegalStateException("Unexpected generation channel")
                return
            }
            val chunk = message.toString()
            if (chunk.isEmpty()) return
            val prefix = synchronized(textLock) { text.append(chunk).toString() }
            // Never call a consumer while holding the native lock: session cancellation can run
            // with its own lock held. The session independently suppresses stale preview callbacks.
            if (!isCancelled() && !expired()) {
                try { onChunk(prefix) } catch (_: Throwable) {
                    nativeFailure = IllegalStateException("Format consumer failed")
                }
            }
        }
        override fun onDone() { terminal.countDown() }
        override fun onError(throwable: Throwable) { nativeFailure = throwable; terminal.countDown() }
    }
}
