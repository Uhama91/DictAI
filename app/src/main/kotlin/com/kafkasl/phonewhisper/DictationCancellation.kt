package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes cancellation requests with the final publication of a dictation result. */
internal class DictationCancellationCoordinator {
    private val lock = Any()
    private var cancelled = false
    private val cancelActions = mutableListOf<() -> Unit>()

    val isCancelled: Boolean
        get() = synchronized(lock) { cancelled }

    fun onCancel(action: () -> Unit) {
        val invokeNow = synchronized(lock) {
            if (cancelled) true else {
                cancelActions += action
                false
            }
        }
        if (invokeNow) runCatching(action)
    }

    fun cancel(): Boolean {
        val actions = synchronized(lock) {
            if (cancelled) return false
            cancelled = true
            cancelActions.toList().also { cancelActions.clear() }
        }
        actions.forEach { runCatching(it) }
        return true
    }

    fun publishIfActive(publish: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled) return false
        publish()
        true
    }
}

/** Holds a final result only while the processing double-tap window is still open. */
internal class DictationFinalPublicationGate(private val windowMs: Long = 280L) {
    init { require(windowMs > 0) }

    class Window internal constructor(
        val token: Long,
        val deadlineMs: Long,
    )

    enum class Submission { PUBLISHED, DEFERRED, IGNORED }

    private val lock = Any()
    private var nextToken = 0L
    private var activeWindow: Window? = null
    private var pendingPublication: (() -> Unit)? = null
    private var cancelled = false

    fun armProcessingTap(atMs: Long): Window = synchronized(lock) {
        val window = Window(++nextToken, atMs + windowMs)
        if (!cancelled) activeWindow = window
        window
    }

    fun submit(atMs: Long, publish: () -> Unit): Submission {
        var immediatePublication: (() -> Unit)? = null
        val submission = synchronized(lock) {
            when {
                cancelled -> Submission.IGNORED
                activeWindow?.let { atMs < it.deadlineMs } == true -> {
                    pendingPublication = publish
                    Submission.DEFERRED
                }
                else -> {
                    activeWindow = null
                    pendingPublication = null
                    immediatePublication = publish
                    Submission.PUBLISHED
                }
            }
        }
        immediatePublication?.invoke()
        return submission
    }

    fun release(window: Window, nowMs: Long): Submission {
        var pending: (() -> Unit)? = null
        val submission = synchronized(lock) {
            when {
                cancelled || activeWindow != window -> Submission.IGNORED
                nowMs < window.deadlineMs -> Submission.DEFERRED
                else -> {
                    activeWindow = null
                    pending = pendingPublication
                    pendingPublication = null
                    if (pending == null) Submission.IGNORED else Submission.PUBLISHED
                }
            }
        }
        pending?.invoke()
        return submission
    }

    fun cancel() = synchronized(lock) {
        cancelled = true
        activeWindow = null
        pendingPublication = null
    }
}

/** Tracks the optional stop/cancel worker independently from the AudioRecord reader. */
internal class DictationRunCompletionGate {
    private val workerStarted = AtomicBoolean(false)
    private val workerDone = CountDownLatch(1)

    fun markWorkerStarted() {
        check(workerStarted.compareAndSet(false, true)) { "dictation worker already started" }
    }

    fun markWorkerDone() {
        workerDone.countDown()
    }

    /** Raw RECORDING teardown has no worker and must return without waiting. */
    fun awaitWorkerIfStarted() {
        if (!workerStarted.get()) return
        var interrupted = false
        while (true) {
            try {
                workerDone.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/** Pure publication policy shared by the overlay callback and JVM regression tests. */
internal object DictationPreviewPublicationGate {
    fun publishIfAllowed(
        isCurrentRun: Boolean,
        isRecording: Boolean,
        cancellation: DictationCancellationCoordinator,
        publish: () -> Unit,
    ): Boolean {
        if (!isCurrentRun || !isRecording) return false
        return cancellation.publishIfActive(publish)
    }
}

/** Keeps the existing strict 280 ms double-tap boundary independent from Android touch code. */
internal class DoubleTapDetector(private val windowMs: Long = 280L) {
    init { require(windowMs > 0) }

    private var lastTapAt: Long? = null

    fun registerTap(atMs: Long): Tap {
        val previous = lastTapAt
        if (previous != null && atMs >= previous && atMs - previous < windowMs) {
            lastTapAt = null
            return Tap.DOUBLE
        }
        lastTapAt = atMs
        return Tap.SINGLE
    }

    fun reset() {
        lastTapAt = null
    }

    enum class Tap { SINGLE, DOUBLE }
}
