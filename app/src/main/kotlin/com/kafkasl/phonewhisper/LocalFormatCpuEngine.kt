package com.kafkasl.phonewhisper

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val CPU_DEFAULT_SHARED_KEY = "gemma4-cpu-default"

/** Supplies the installed GGUF path without coupling the CPU runtime to model storage. */
internal fun interface LocalFormatCpuModelProvider {
    fun installedModel(): File?
}

/** Native construction is injected so lifecycle and cancellation can be tested without JNI. */
internal fun interface LocalFormatCpuNativeFactory {
    fun open(modelPath: String, contextSize: Int, threads: Int): LocalFormatNative
}

internal fun interface LocalFormatCpuClock {
    fun nowMs(): Long
}

internal fun interface LocalFormatCpuCancellationDispatcher {
    fun dispatch(task: () -> Unit)
}

/**
 * CPU Gemma runtime with a process-shared native handle. It intentionally has no Android/UI
 * dependency: callers own only a backend, while the core owns the worker and native lifetime.
 */
internal class LocalFormatCpuEngine(
    modelProvider: LocalFormatCpuModelProvider,
    nativeFactory: LocalFormatCpuNativeFactory = LocalFormatCpuNativeFactory { path, context, threads ->
        LocalFormatNative.open(path, context, threads, preferOptimized = true)
    },
    clock: LocalFormatCpuClock = LocalFormatCpuClock { System.nanoTime() / 1_000_000L },
    cancelDispatcher: LocalFormatCpuCancellationDispatcher? = null,
    sharedKey: String = CPU_DEFAULT_SHARED_KEY,
    profile: LocalFormatCpuProfile = LocalFormatCpuProfile.Gemma4,
) : AutoCloseable {
    private val owner = CpuCore.acquire(sharedKey, profile, modelProvider, nativeFactory, clock, cancelDispatcher)
    private val closed = AtomicBoolean(false)

    fun runtimeName(): String = owner.runtimeName()
    fun failureCode(): String? = owner.failureCode()
    fun isLoaded(): Boolean = owner.isLoaded()
    fun lastLoadMs(): Long? = owner.lastLoadMs()

    /** Loads on the shared worker; this method is for worker/benchmark callers, never UI code. */
    fun prepareForBenchmarkInfo(): LocalFormatPreparation {
        check(!closed.get()) { "Formatter closed" }
        return owner.prepare()
    }

    fun prepareForBenchmark(): Long = prepareForBenchmarkInfo().waitMs

    fun warm() {
        if (!closed.get()) owner.warm()
    }

    fun backend(): LocalFormatBackend = CpuBackend(owner)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        owner.close()
    }

    private class CpuBackend(private val owner: CpuOwner) : LocalFormatBackend {
        private val epoch = AtomicLong()
        private val token = Any()

        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? =
            generate(request, onChunk, {})

        override fun generate(
            request: LocalFormatRequest,
            onChunk: (String) -> Unit,
            onNativeStart: () -> Unit,
        ): String? {
            if (owner.isClosed()) return null
            val profile = owner.profile
            if (!profile.accepts(request)) return null
            if (LocalFormatCpuEngine.containsReservedTokenizerData(request, profile)) return null
            if (profile == LocalFormatCpuProfile.Gemma4) request.directOutput()?.let { return it }
            val prompt = profile.buildPrompt(request) ?: return null
            if (profile == LocalFormatCpuProfile.Gemma3Final) request.directOutput()?.let { return it }
            val started = owner.nowMs()
            val callEpoch = epoch.get()
            val call = CpuCall(
                owner = owner,
                backendToken = token,
                stale = { owner.isClosed() || epoch.get() != callEpoch },
                prompt = prompt,
                maxTokens = profile.maxNewTokens ?: request.outputTokenBudget(),
                deadlineMs = started + profile.deadlineMs,
                onChunk = onChunk,
                onNativeStart = onNativeStart,
            )
            owner.submit(call)
            return try {
                val remaining = (call.deadlineMs - owner.nowMs()).coerceAtLeast(1L)
                call.result.get(remaining, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                owner.cancel(call, "generation_timeout")
                null
            } catch (_: InterruptedException) {
                owner.cancel(call)
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                owner.cancel(call)
                null
            } finally {
                owner.forget(call)
            }
        }

        override fun cancel() {
            epoch.incrementAndGet()
            owner.cancelAll(token)
        }

    }

    private class CpuCall(
        val owner: CpuOwner,
        val backendToken: Any,
        private val stale: () -> Boolean,
        val prompt: String,
        val maxTokens: Int,
        val deadlineMs: Long,
        private val onChunk: (String) -> Unit,
        private val onNativeStart: () -> Unit,
    ) {
        data class NativeCancellation(val native: LocalFormatNative, val generation: Long)

        val result = CompletableFuture<String?>()
        private val stateLock = Any()
        private val cancelled = AtomicBoolean(false)
        @Volatile private var native: LocalFormatNative? = null
        private var nativeGeneration = 0L
        @Volatile var enteredNative = false

        fun shouldAbort(nowMs: Long): Boolean = cancelled.get() || stale() || nowMs >= deadlineMs

        fun expired(nowMs: Long): Boolean = nowMs >= deadlineMs
        fun isStale(): Boolean = stale()

        fun cancel(): NativeCancellation? {
            if (!cancelled.compareAndSet(false, true)) return null
            val target = synchronized(stateLock) {
                native?.let { resident ->
                    nativeGeneration.takeIf { enteredNative && it > 0L }
                        ?.let { generation -> NativeCancellation(resident, generation) }
                }
            }
            result.complete(null)
            return target
        }

        fun markNativeStart(nowMs: Long): Boolean {
            val allowed = synchronized(stateLock) {
                if (cancelled.get() || stale() || nowMs >= deadlineMs) {
                    false
                } else {
                    enteredNative = true
                    true
                }
            }
            if (allowed) onNativeStart()
            return allowed
        }

        fun markNativeGeneration(resident: LocalFormatNative, generation: Long): Boolean =
            synchronized(stateLock) {
                if (cancelled.get() || stale()) return@synchronized false
                native = resident
                nativeGeneration = generation
                true
            }

        fun emit(chunk: String, nowMs: Long) {
            val allowed = synchronized(stateLock) {
                !cancelled.get() && !stale() && nowMs < deadlineMs
            }
            if (allowed) onChunk(chunk)
        }

        fun complete(value: String?) {
            val accepted = !cancelled.get() && !stale() && owner.nowMs() < deadlineMs
            result.complete(if (accepted) value else null)
        }
    }

    private class CpuOwner(private val core: CpuCore, val token: Any) {
        val profile: LocalFormatCpuProfile get() = core.profile
        private val closed = AtomicBoolean(false)

        fun isClosed(): Boolean = closed.get()
        fun nowMs(): Long = core.nowMs()
        fun runtimeName(): String = core.runtimeName()
        fun failureCode(): String? = core.failureCode()
        fun isLoaded(): Boolean = core.isLoaded()
        fun lastLoadMs(): Long? = core.lastLoadMs()
        fun submit(call: CpuCall) = core.submit(call)
        fun forget(call: CpuCall) = core.forget(call)
        fun cancel(call: CpuCall, failureCode: String? = null) = core.cancel(call, failureCode)
        fun cancelAll(backendToken: Any) = core.cancelOwner(token, backendToken)
        fun prepare(): LocalFormatPreparation = core.prepare()
        fun warm() = core.warm()

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            core.cancelOwner(token, null)
            core.release()
        }
    }

    private class CpuCore(
        private val key: Pair<String, LocalFormatCpuProfile>,
        val profile: LocalFormatCpuProfile,
        private val modelProvider: LocalFormatCpuModelProvider,
        private val nativeFactory: LocalFormatCpuNativeFactory,
        private val clock: LocalFormatCpuClock,
        private val cancelDispatcher: LocalFormatCpuCancellationDispatcher?,
    ) {
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "dictai-gemma-cpu").apply { isDaemon = true }
        }
        private val canceller = Executors.newSingleThreadExecutor { task ->
            Thread(task, "dictai-gemma-cpu-cancel").apply { isDaemon = true }
        }
        private val stateLock = Any()
        private val jobs = ConcurrentHashMap.newKeySet<CpuCall>()
        private var owners = 0
        private var native: LocalFormatNative? = null
        private var closeQueued = false
        private var retired = false
        @Volatile private var runtime = "not-loaded"
        @Volatile private var failure: String? = null
        @Volatile private var loaded = false
        @Volatile private var loadedMs: Long? = null

        fun tryRetain(token: Any): Boolean {
            synchronized(stateLock) {
                if (retired) return false
                owners++
                closeQueued = false
                return true
            }
        }

        fun release() {
            val shouldQueue = synchronized(stateLock) {
                owners--
                check(owners >= 0)
                if (owners == 0 && !closeQueued) {
                    closeQueued = true
                    true
                } else false
            }
            if (shouldQueue) worker.execute(::closeWhenIdle)
        }

        fun nowMs(): Long = clock.nowMs()
        fun runtimeName(): String = runtime
        fun failureCode(): String? = failure
        fun isLoaded(): Boolean = loaded
        fun lastLoadMs(): Long? = loadedMs

        fun submit(call: CpuCall) {
            jobs.add(call)
            try {
                worker.execute { run(call) }
            } catch (_: RuntimeException) {
                jobs.remove(call)
                cancel(call)
            }
        }

        fun forget(call: CpuCall) { jobs.remove(call) }

        fun cancel(call: CpuCall, failureCode: String? = null) {
            if (failureCode != null && !call.isStale()) setFailure(failureCode, "cpu-timeout")
            call.cancel()?.let(::requestNativeCancel)
        }

        fun cancelOwner(token: Any, backendToken: Any?) {
            val ownerJobs = jobs.filter { it.owner.token === token }
            ownerJobs.filter { backendToken == null || it.backendToken === backendToken }
                .forEach { call -> cancel(call) }
        }

        fun warm() {
            synchronized(stateLock) { if (owners == 0 || native != null) return }
            worker.execute { ensureLoaded() }
        }

        fun prepare(): LocalFormatPreparation {
            val started = nowMs()
            val already = loaded
            val result = CompletableFuture<Unit>()
            worker.execute {
                if (result.isDone) return@execute
                if (ensureLoaded() != null) result.complete(Unit)
                else result.completeExceptionally(IllegalStateException(failure ?: "cpu_initialization_failed"))
            }
            try {
                result.get(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (error: TimeoutException) {
                setFailure("initialization_timeout", "loading-timeout")
                throw error
            }
            return LocalFormatPreparation(
                loadMs = loadedMs ?: (nowMs() - started).coerceAtLeast(0L),
                wasAlreadyLoaded = already,
                waitMs = (nowMs() - started).coerceAtLeast(0L),
            )
        }

        private fun run(call: CpuCall) {
            try {
                if (call.shouldAbort(nowMs())) {
                    call.cancel()
                    return
                }
                val resident = ensureLoaded() ?: run {
                    call.cancel()
                    return
                }
                if (call.shouldAbort(nowMs())) {
                    call.cancel()
                    return
                }
                val remaining = (call.deadlineMs - nowMs()).coerceAtLeast(1L)
                val output = try {
                    resident.generate(
                        prompt = call.prompt,
                        maxTokens = call.maxTokens,
                        timeoutMs = remaining,
                        onChunk = { chunk -> call.emit(chunk, nowMs()) },
                        grammar = null,
                        profile = LocalFormatDecodingProfile.GemmaFineTunedGreedy,
                        onNativeStart = { call.markNativeStart(nowMs()) },
                        onNativeGeneration = { generation -> call.markNativeGeneration(resident, generation) },
                        isCancelled = { call.shouldAbort(nowMs()) },
                    )
                } catch (_: Throwable) {
                    setFailure("generation_error", "cpu-error")
                    null
                }
                val now = nowMs()
                if (output == null || call.shouldAbort(now)) {
                    if (call.expired(now) && !call.isStale()) setFailure("generation_timeout", "cpu-timeout")
                    cancel(call)
                }
                else {
                    failure = null
                    runtime = resident.runtimeName
                    call.complete(output)
                }
            } finally {
                jobs.remove(call)
            }
        }

        private fun ensureLoaded(): LocalFormatNative? {
            synchronized(stateLock) {
                native?.let { return it }
                if (owners == 0) return null
                runtime = "loading"
            }
            val model = try { modelProvider.installedModel() } catch (_: Throwable) { null }
            if (model == null || !model.isFile) {
                setFailure("model_missing", "model-missing")
                return null
            }
            val started = nowMs()
            val opening = try {
                nativeFactory.open(
                model.absolutePath,
                    profile.contextSize,
                    profile.threads,
                )
            } catch (_: Throwable) {
                setFailure("cpu_initialization_failed", "cpu-error")
                return null
            }
            synchronized(stateLock) {
                if (owners == 0) {
                    // No caller remains, but close stays on this worker and no second handle is made.
                    runCatching { opening.close() }
                    runtime = "not-loaded"
                    return null
                }
                native = opening
                loaded = true
                loadedMs = (nowMs() - started).coerceAtLeast(0L)
                failure = null
                runtime = opening.runtimeName
                return opening
            }
        }

        private fun setFailure(code: String, name: String) {
            failure = code
            val resident = native
            if (resident == null) {
                runtime = name
                loaded = false
            } else {
                runtime = resident.runtimeName
                loaded = true
            }
        }

        private fun requestNativeCancel(target: CpuCall.NativeCancellation) {
            val cancel = { target.native.cancel(target.generation) }
            cancelDispatcher?.dispatch(cancel) ?: canceller.execute { cancel() }
        }

        private fun closeWhenIdle() {
            var closing: LocalFormatNative? = null
            val mayClose = synchronized(stateLock) {
                if (owners != 0) {
                    closeQueued = false
                    false
                } else {
                    closing = native
                    native = null
                    true
                }
            }
            if (!mayClose) return
            closing?.let { value -> runCatching { value.close() } }
            synchronized(stateLock) {
                if (owners != 0) {
                    // A new owner arrived after extraction; the closed handle is gone, so the
                    // shared worker will reload it before that owner's next request.
                    loaded = false
                    loadedMs = null
                    runtime = "not-loaded"
                    failure = null
                    closeQueued = false
                    return@synchronized
                }
                loaded = false
                loadedMs = null
                runtime = "not-loaded"
                failure = null
                closeQueued = false
                if (owners == 0) {
                    retired = true
                    REGISTRY.remove(key, this)
                    worker.shutdown()
                    canceller.shutdown()
                }
            }
        }

        companion object {
            private val REGISTRY = ConcurrentHashMap<Pair<String, LocalFormatCpuProfile>, CpuCore>()
            private val LOCK = Any()

            fun acquire(
                sharedKey: String,
                profile: LocalFormatCpuProfile,
                modelProvider: LocalFormatCpuModelProvider,
                nativeFactory: LocalFormatCpuNativeFactory,
                clock: LocalFormatCpuClock,
                cancelDispatcher: LocalFormatCpuCancellationDispatcher?,
            ): CpuOwner = synchronized(LOCK) {
                val token = Any()
                val key = sharedKey to profile
                val existing = REGISTRY[key]
                val core = if (existing != null && existing.tryRetain(token)) {
                    existing
                } else {
                    CpuCore(key, profile, modelProvider, nativeFactory, clock, cancelDispatcher).also {
                        check(it.tryRetain(token))
                        REGISTRY[key] = it
                    }
                }
                CpuOwner(core, token)
            }
        }
    }

    companion object {
        const val CPU_CONTEXT_SIZE = 4096
        const val CPU_THREADS = 2
        const val GENERATION_DEADLINE_MS = 20_000L
        const val LOAD_WAIT_MS = 45_000L

        fun containsReservedTokenizerData(
            request: LocalFormatRequest,
            profile: LocalFormatCpuProfile = LocalFormatCpuProfile.Gemma4,
        ): Boolean {
            return sequenceOf(
                request.text,
                request.contextBefore,
                *request.protectedTerms.toTypedArray(),
            ).any { value ->
                containsReservedTokenizerDelimiter(value) || profile.hasAdditionalReservedToken(value)
            }
        }

        fun containsReservedTokenizerDelimiter(value: String): Boolean =
            "<|" in value || "|>" in value ||
                listOf("<pad>", "<eos>", "<bos>", "<unk>", "<mask>")
                    .any { token -> value.contains(token) }
    }
}
