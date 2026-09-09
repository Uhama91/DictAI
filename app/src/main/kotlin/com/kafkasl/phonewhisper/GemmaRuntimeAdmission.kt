package com.kafkasl.phonewhisper

import java.util.concurrent.CompletableFuture

/** Reject waiting callers while a native operation has outlived its deadline. */
internal class GemmaRuntimeAdmission {
    private val lock = Any()
    private val waiting = HashSet<CompletableFuture<*>>()
    @Volatile var isBlocked = false
        private set

    fun admit(result: CompletableFuture<*>): Boolean = synchronized(lock) {
        if (result.isDone) return@synchronized false
        if (isBlocked) {
            result.completeExceptionally(unavailable())
            return@synchronized false
        }
        waiting.add(result)
        result.whenComplete { _, _ -> synchronized(lock) { waiting.remove(result) } }
        true
    }

    fun block() {
        val rejected = synchronized(lock) {
            isBlocked = true
            waiting.toList().also { waiting.clear() }
        }
        rejected.forEach { it.completeExceptionally(unavailable()) }
    }

    /** Called only after the blocking native operation returns or is safely cleaned up. */
    fun resume() { synchronized(lock) { isBlocked = false } }

    private fun unavailable() = IllegalStateException("Local GPU runtime is waiting for native completion")
}

/** Coordinates the timeout thread with an initialization call that cannot be interrupted. */
internal class GemmaInitializationDeadline(
    private val onTimeout: () -> Unit,
    private val onReturnedAfterTimeout: () -> Unit,
) {
    private val lock = Any()
    private var running = true
    private var timedOut = false

    fun timeout() {
        synchronized(lock) {
            if (!running || timedOut) return
            timedOut = true
            onTimeout()
        }
    }

    fun returned() {
        synchronized(lock) {
            if (!running) return
            running = false
            if (timedOut) onReturnedAfterTimeout()
        }
    }
}
