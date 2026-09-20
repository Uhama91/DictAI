package com.kafkasl.phonewhisper

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Small dispatcher seam used by the coordinator and by deterministic tests. */
internal fun interface ProgressiveTaskDispatcher {
    fun dispatch(task: () -> Unit)
}

internal data class ProgressivePreviewSnapshot(
    val committed: String,
    val tentative: String,
)

/** Defers UI replacement while Android's IME owns a composing span. */
internal class ProgressiveCompositionGate {
    private var deferred: ProgressivePreviewSnapshot? = null

    @Synchronized fun offer(
        snapshot: ProgressivePreviewSnapshot,
        composing: Boolean,
        valid: Boolean,
    ): ProgressivePreviewSnapshot? {
        if (!valid) {
            deferred = null
            return null
        }
        if (composing) {
            deferred = snapshot
            return null
        }
        deferred = null
        return snapshot
    }

    @Synchronized fun replay(composing: Boolean, valid: Boolean): ProgressivePreviewSnapshot? {
        if (!valid) {
            deferred = null
            return null
        }
        if (composing) return null
        val ready = deferred
        deferred = null
        return ready
    }

    @Synchronized fun clear() {
        deferred = null
    }
}

/**
 * Owns the one-at-a-time model work for a progressive dictation.
 *
 * The buffer remains the source of truth for offsets and epochs. This class only schedules a
 * request, validates the complete response, and asks the caller to render the latest state.
 * Streaming chunks are deliberately discarded at this boundary.
 */
internal class ProgressiveFormattingCoordinator(
    private val mode: LocalLayoutKind,
    private val backend: LocalFormatBackend,
    private val normalizer: (String) -> String = { it },
    workDispatcher: ProgressiveTaskDispatcher? = null,
    mainDispatcher: ProgressiveTaskDispatcher? = null,
    private val requestFactory: (ProgressiveFormatRequest, String) -> LocalFormatRequest,
    private val onSnapshot: (ProgressiveBufferState) -> Unit = {},
    private val finalWaitMs: Long = FINAL_WAIT_MS,
) : AutoCloseable {
    private val lock = Any()
    private val buffer = ProgressiveFormattingBuffer(normalizer = normalizer)
    private val ownedWorker: ExecutorService? = if (workDispatcher == null) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dictai-progressive-format").apply { isDaemon = true }
        }
    } else null
    private val work = workDispatcher ?: ProgressiveTaskDispatcher { task -> ownedWorker!!.execute(task) }
    private val main = mainDispatcher ?: ProgressiveTaskDispatcher { task -> task() }

    private var closed = false
    private var finalizing = false
    private var activeRequest: ProgressiveFormatRequest? = null
    private var finalRequest: ProgressiveFormatRequest? = null
    private var finalResult: CompletableFuture<ProgressiveBufferState>? = null

    /** Applies the exact ASR source and may queue a stable partial request. */
    fun update(
        rawText: String,
        stableWordCount: Int,
        totalDictationWordCount: Int = wordCount(rawText),
    ): ProgressiveBufferState {
        lateinit var state: ProgressiveBufferState
        var cancel = false
        var next: ProgressiveFormatRequest? = null
        synchronized(lock) {
            if (closed) return buffer.state()
            state = buffer.update(rawText, stableWordCount, totalDictationWordCount)
            val current = state.inFlight
            val previous = activeRequest
            if (previous != null && current?.id != previous.id) {
                activeRequest = null
                cancel = true
            }
            if (!finalizing) {
                if (current != null && activeRequest == null) activeRequest = current
                if (current == null || activeRequest == null) {
                    next = buffer.request(mode, GemmaFineTunedPrompt.Phase.PARTIAL)
                    if (next != null) activeRequest = next
                }
            }
        }
        if (cancel) backend.cancel()
        next?.let { dispatch(it, isFinal = false) }
        return state
    }

    /** Invalidates all pending work while keeping the visible prefix owned by the user. */
    fun resetForHumanEdit(visiblePrefix: String): ProgressiveBufferState {
        lateinit var state: ProgressiveBufferState
        var cancel = false
        var waiter: CompletableFuture<ProgressiveBufferState>? = null
        synchronized(lock) {
            if (closed) return buffer.state()
            state = buffer.resetForHumanEdit(visiblePrefix)
            if (closed) return state
            cancel = activeRequest != null
            activeRequest = null
            finalRequest = null
            finalizing = false
            waiter = finalResult
            finalResult = null
        }
        if (cancel) backend.cancel()
        waiter?.complete(state)
        publish()
        return state
    }

    /** Returns the latest pure buffer state without scheduling work. */
    fun state(): ProgressiveBufferState = synchronized(lock) { buffer.state() }

    /**
     * Runs one bounded FINAL request over only the current unaccepted continuation.
     * A blank final ASR result leaves the accepted buffer intact.
     */
    fun finish(
        rawText: String,
        stableWordCount: Int,
        totalDictationWordCount: Int = wordCount(rawText),
    ): ProgressiveBufferState {
        var cancel = false
        synchronized(lock) {
            if (closed) return buffer.state()
            if (rawText.isNotBlank()) buffer.update(rawText, stableWordCount, totalDictationWordCount)
            cancel = activeRequest != null
            activeRequest = null
            finalizing = true
            finalRequest = null
            finalResult?.complete(buffer.state())
            finalResult = null
        }
        if (cancel) backend.cancel()

        val result = CompletableFuture<ProgressiveBufferState>()
        val request: ProgressiveFormatRequest
        synchronized(lock) {
            if (closed) return buffer.state()
            request = buffer.request(mode, GemmaFineTunedPrompt.Phase.FINAL)
                ?: return finishWithoutRequest()
            finalRequest = request
            activeRequest = request
            finalResult = result
        }
        dispatch(request, isFinal = true)
        return try {
            result.get(finalWaitMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            backend.cancel()
            settleTimeout(request, result)
        } catch (_: InterruptedException) {
            backend.cancel()
            Thread.currentThread().interrupt()
            settleTimeout(request, result)
        }
    }

    override fun close() {
        var cancel = false
        var waiter: CompletableFuture<ProgressiveBufferState>? = null
        lateinit var state: ProgressiveBufferState
        synchronized(lock) {
            if (closed) return
            closed = true
            cancel = activeRequest != null
            activeRequest = null
            finalRequest = null
            finalizing = false
            waiter = finalResult
            finalResult = null
            state = buffer.state()
        }
        if (cancel) backend.cancel()
        waiter?.complete(state)
        ownedWorker?.shutdownNow()
    }

    private fun finishWithoutRequest(): ProgressiveBufferState {
        lateinit var state: ProgressiveBufferState
        synchronized(lock) {
            state = buffer.state()
            finalizing = false
            finalRequest = null
            finalResult = null
        }
        publish()
        return state
    }

    private fun settleTimeout(
        request: ProgressiveFormatRequest,
        result: CompletableFuture<ProgressiveBufferState>,
    ): ProgressiveBufferState {
        lateinit var state: ProgressiveBufferState
        synchronized(lock) {
            if (activeRequest?.id == request.id && !closed) {
                if (buffer.state().inFlight?.id == request.id) buffer.acceptValidated(request, null)
                if (activeRequest?.id == request.id) activeRequest = null
                if (finalRequest?.id == request.id) finalRequest = null
                finalizing = false
                if (finalResult === result) finalResult = null
            }
            state = buffer.state()
        }
        result.complete(state)
        publish()
        return state
    }

    private fun dispatch(request: ProgressiveFormatRequest, isFinal: Boolean) {
        try {
            work.dispatch { runRequest(request, isFinal) }
        } catch (_: Throwable) {
            val current = acceptIfCurrent(request, null)
            completeRequest(request, isFinal, publish = current)
        }
    }

    private fun runRequest(request: ProgressiveFormatRequest, isFinal: Boolean) {
        // A cancelled task can remain in a caller-provided queue. Do not let it enter the
        // backend after a human edit, ASR revision, note switch, or close.
        if (!isCurrentRequest(request)) return
        val localRequest = runCatching {
            val normalized = normalizer(request.source).ifBlank { request.source }
            requestFactory(request, normalized)
        }.getOrNull()
        val generated = localRequest?.let {
            if (!isCurrentRequest(request)) return@let null
            runCatching {
                backend.generate(
                    it,
                    onChunk = {},
                    onNativeStart = {
                        // The native backend gets one last identity check immediately before
                        // entering JNI. Its own call object also observes cancellation.
                        if (!isCurrentRequest(request)) backend.cancel()
                    },
                )
            }.getOrNull()
        }
        val validated = localRequest?.acceptOutput(generated)
        val current = acceptIfCurrent(request, validated)
        completeRequest(request, isFinal, publish = current)
    }

    private fun isCurrentRequest(request: ProgressiveFormatRequest): Boolean {
        synchronized(lock) {
            return !closed && activeRequest?.id == request.id && buffer.state().inFlight?.id == request.id
        }
    }

    /** Checks lifecycle and accepts under one coordinator->buffer lock order. */
    private fun acceptIfCurrent(
        request: ProgressiveFormatRequest,
        validated: String?,
    ): Boolean = synchronized(lock) {
        if (closed || activeRequest?.id != request.id) return@synchronized false
        if (buffer.state().inFlight?.id != request.id) return@synchronized false
        // A null/blank model output still settles the raw source and must advance to the next
        // segment. The Boolean returned by the buffer means “accepted model text”, whereas this
        // coordinator result means “the request was current and was settled”.
        buffer.acceptValidated(request, validated)
        true
    }

    private fun completeRequest(
        request: ProgressiveFormatRequest,
        isFinal: Boolean,
        publish: Boolean,
    ) {
        var next: ProgressiveFormatRequest? = null
        var result: CompletableFuture<ProgressiveBufferState>? = null
        lateinit var state: ProgressiveBufferState
        synchronized(lock) {
            if (activeRequest?.id == request.id) activeRequest = null
            if (isFinal && finalRequest?.id == request.id) {
                finalRequest = null
                finalizing = false
                result = finalResult
                finalResult = null
            }
            if (!isFinal && publish && !closed && !finalizing) {
                next = buffer.request(mode, GemmaFineTunedPrompt.Phase.PARTIAL)
                if (next != null) activeRequest = next
            }
            state = buffer.state()
        }
        result?.complete(state)
        if (publish) publish()
        next?.let { dispatch(it, isFinal = false) }
    }

    private fun publish() {
        try {
            main.dispatch {
                val state = synchronized(lock) {
                    if (closed) return@dispatch
                    buffer.state()
                }
                onSnapshot(state)
            }
        } catch (_: Throwable) {
            // A lifecycle dispatcher can close while a native callback is unwinding.
        }
    }

    private fun wordCount(value: String): Int = WORD.findAll(value).count()

    private companion object {
        const val FINAL_WAIT_MS = 20_000L
        val WORD = Regex("\\S+")
    }
}
