package com.kafkasl.phonewhisper.meeting

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal fun interface MeetingDraftPersistence {
    fun save(document: MeetingDocument)
}

internal fun interface MeetingDraftScheduledTask {
    fun cancel()
}

internal interface MeetingDraftScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): MeetingDraftScheduledTask
    fun execute(task: () -> Unit)
    fun shutdown()
}

/** Debounces immutable meeting snapshots and serializes persistence on one I/O worker. */
class MeetingDraftWriter internal constructor(
    private val persistence: MeetingDraftPersistence,
    private val scheduler: MeetingDraftScheduler,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onErrorChanged: (String?) -> Unit = {},
) {
    constructor(
        store: MeetingDraftStore,
        onErrorChanged: (String?) -> Unit = {},
    ) : this(
        persistence = MeetingDraftPersistence(store::save),
        scheduler = DefaultMeetingDraftScheduler(),
        onErrorChanged = onErrorChanged,
    )

    private data class FlushWaiter(
        val generation: Long,
        val future: CompletableFuture<Unit>,
    )

    private val lock = Any()
    private val waiters = mutableListOf<FlushWaiter>()
    private var generation = 0L
    private var savedGeneration = 0L
    private var pendingGeneration = 0L
    private var pendingSnapshot: MeetingDocument? = null
    private var writing = false
    private var closing = false
    private var closed = false
    private var retired = false
    private var hasSaveError = false
    private var debounceTask: MeetingDraftScheduledTask? = null
    private var debounceElapsed = false
    private var timerSequence = 0L
    private var activeTimer = 0L

    init {
        require(debounceMs >= 0) { "debounceMs must not be negative" }
    }

    /** Replaces the one pending snapshot. Repeated updates keep the first dirty deadline. */
    fun updateSnapshot(document: MeetingDocument): Unit {
        synchronized(lock) {
            check(!retired && !closing && !closed) { "Meeting draft writer is no longer available" }
            generation += 1
            pendingGeneration = generation
            pendingSnapshot = document
            if (debounceTask == null) {
                debounceElapsed = false
                scheduleDebounceLocked()
            }
        }
    }

    /** Completes when the generation current at invocation has been persisted. */
    fun flush(): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        var alreadyComplete = false
        var retiredWriter = false
        synchronized(lock) {
            if (retired) {
                retiredWriter = true
            } else if (closed || (savedGeneration >= generation && !writing && pendingSnapshot == null)) {
                alreadyComplete = true
            } else {
                waiters += FlushWaiter(generation, future)
                cancelDebounceLocked()
                if (!writing) startWriteLocked()
            }
        }
        if (retiredWriter) {
            future.completeExceptionally(IllegalStateException(SAVE_ERROR_MESSAGE))
        } else if (alreadyComplete) {
            future.complete(Unit)
        }
        return future
    }

    /** Refuses future mutations and saves the last snapshot before releasing the worker. */
    fun close(): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        var releaseWorker = false
        var alreadyClosed = false
        var retiredWriter = false
        synchronized(lock) {
            if (retired) {
                retiredWriter = true
            } else if (closed) {
                alreadyClosed = true
            } else {
                closing = true
                if (!writing && pendingSnapshot == null && savedGeneration >= generation) {
                    closed = true
                    releaseWorker = true
                } else {
                    waiters += FlushWaiter(generation, future)
                    cancelDebounceLocked()
                    if (!writing) startWriteLocked()
                }
            }
        }
        if (retiredWriter) {
            future.completeExceptionally(IllegalStateException(SAVE_ERROR_MESSAGE))
        } else if (alreadyClosed) {
            future.complete(Unit)
        } else if (releaseWorker) {
            try {
                scheduler.shutdown()
                future.complete(Unit)
            } catch (_: Throwable) {
                future.completeExceptionally(IllegalStateException(SAVE_ERROR_MESSAGE))
            }
        }
        return future
    }

    /** Permanently retires a writer after its final close failed during ownership transfer. */
    internal fun retireAfterCloseFailure(): Unit {
        synchronized(lock) {
            check(!retired) { "Meeting draft writer is already retired" }
            check(closing && !closed && !writing && hasSaveError) {
                "Meeting draft writer can be retired only after a failed close with no active write"
            }
            cancelDebounceLocked()
            retired = true
        }
        scheduler.shutdown()
    }

    private fun scheduleDebounceLocked() {
        val timer = ++timerSequence
        activeTimer = timer
        debounceTask = scheduler.schedule(debounceMs) { onDebounce(timer) }
    }

    private fun onDebounce(timer: Long) {
        synchronized(lock) {
            if (timer != activeTimer || closed || retired) return
            activeTimer = 0L
            debounceTask = null
            debounceElapsed = true
            if (!writing) startWriteLocked()
        }
    }

    private fun cancelDebounceLocked() {
        activeTimer = 0L
        debounceTask?.cancel()
        debounceTask = null
        debounceElapsed = false
    }

    /** Called with [lock] held. */
    private fun startWriteLocked() {
        if (writing || retired) return
        val snapshot = pendingSnapshot ?: return
        val targetGeneration = pendingGeneration
        pendingSnapshot = null
        writing = true
        debounceElapsed = false
        scheduler.execute { writeSnapshot(snapshot, targetGeneration) }
    }

    private fun writeSnapshot(snapshot: MeetingDocument, targetGeneration: Long) {
        val failure = try {
            persistence.save(snapshot)
            null
        } catch (_: Throwable) {
            IllegalStateException(SAVE_ERROR_MESSAGE)
        }

        val completedSuccessfully = mutableListOf<CompletableFuture<Unit>>()
        val completedWithFailure = mutableListOf<CompletableFuture<Unit>>()
        var notifyError = false
        var errorValue: String? = null
        var releaseWorker = false

        synchronized(lock) {
            writing = false
            if (failure == null) {
                savedGeneration = maxOf(savedGeneration, targetGeneration)
                if (hasSaveError) {
                    hasSaveError = false
                    notifyError = true
                    errorValue = null
                }
                val covered = waiters.filter { it.generation <= savedGeneration }
                covered.forEach { completedSuccessfully += it.future }
                waiters.removeAll(covered.toSet())

                if (closing && pendingSnapshot == null && savedGeneration >= generation) {
                    closed = true
                    releaseWorker = true
                } else if (pendingSnapshot != null && shouldDrainPendingAfterWriteLocked()) {
                    cancelDebounceLocked()
                    startWriteLocked()
                }
            } else {
                if (pendingSnapshot == null || pendingGeneration < targetGeneration) {
                    pendingGeneration = targetGeneration
                    pendingSnapshot = snapshot
                }
                if (!hasSaveError) {
                    hasSaveError = true
                    notifyError = true
                    errorValue = SAVE_ERROR_MESSAGE
                }
                val failed = waiters.filter { it.generation <= targetGeneration }
                failed.forEach { completedWithFailure += it.future }
                waiters.removeAll(failed.toSet())

                if (pendingSnapshot != null && shouldDrainPendingAfterFailureLocked(targetGeneration)) {
                    cancelDebounceLocked()
                    startWriteLocked()
                }
            }
        }

        if (notifyError) notifyErrorChanged(errorValue)
        completedSuccessfully.forEach { it.complete(Unit) }
        completedWithFailure.forEach { it.completeExceptionally(failure ?: IllegalStateException(SAVE_ERROR_MESSAGE)) }
        if (releaseWorker) {
            try {
                scheduler.shutdown()
            } catch (_: Throwable) {
                // The final snapshot is already durable; worker shutdown failures do not undo it.
            }
        }
    }

    private fun shouldDrainPendingAfterWriteLocked(): Boolean =
        closing || debounceElapsed || waiters.any { it.generation > savedGeneration }

    private fun shouldDrainPendingAfterFailureLocked(failedGeneration: Long): Boolean =
        debounceElapsed || waiters.any { it.generation > failedGeneration }

    private fun notifyErrorChanged(value: String?) {
        try {
            onErrorChanged(value)
        } catch (_: Throwable) {
            // UI notification failures must not alter the persistence state machine.
        }
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
        const val SAVE_ERROR_MESSAGE = "Sauvegarde impossible"
    }
}

private class DefaultMeetingDraftScheduler : MeetingDraftScheduler {
    private val threadIds = AtomicLong()
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "meeting-draft-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }

    override fun schedule(delayMs: Long, task: () -> Unit): MeetingDraftScheduledTask {
        val scheduled = executor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
        return MeetingDraftScheduledTask { scheduled.cancel(false) }
    }

    override fun execute(task: () -> Unit) {
        executor.execute { task() }
    }

    override fun shutdown() {
        executor.shutdown()
    }
}
