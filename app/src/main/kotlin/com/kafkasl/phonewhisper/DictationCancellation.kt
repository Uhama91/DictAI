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

/**
 * Keeps tap recognition independent from the overlay touch listener.
 *
 * A recording tap cannot stop immediately: doing so turns the first tap into a
 * TRANSCRIBING state and loses the opportunity to recognize the second tap.
 * Instead, the coordinator returns a timeout token.  Only that exact token may
 * later resolve the deferred stop, so a double-tap cancellation makes its stale
 * timeout harmless.
 */
internal class DictationTapGestureCoordinator(private val windowMs: Long = 280L) {
    init { require(windowMs > 0) }

    enum class SurfaceState { IDLE, RECORDING, PAUSED, TRANSCRIBING, CANCELLING, MIC_UNARMED }

    enum class Action {
        NONE,
        START_RECORDING,
        RESUME_RECORDING,
        STOP_RECORDING,
        CANCEL_RECORDING,
        CANCEL_RECORDING_AND_OPEN_APP,
        ARM_PROCESSING_WINDOW,
        CANCEL_PROCESSING,
        PROMPT_MIC_SETUP_AND_OPEN_APP,
    }

    class Timeout internal constructor(
        val token: Long,
        val deadlineMs: Long,
    )

    data class Decision(
        val action: Action,
        val timeout: Timeout? = null,
    )

    private enum class Origin { IDLE, RECORDING, PAUSED, PROCESSING }

    private data class Sequence(
        val origin: Origin,
        val atMs: Long,
        val timeout: Timeout?,
    )

    private var nextToken = 0L
    private var sequence: Sequence? = null

    fun onTap(state: SurfaceState, atMs: Long): Decision = when (state) {
        SurfaceState.IDLE -> {
            sequence = Sequence(Origin.IDLE, atMs, timeout = null)
            Decision(Action.START_RECORDING)
        }
        SurfaceState.RECORDING -> onRecordingTap(atMs)
        SurfaceState.PAUSED -> onPausedTap(atMs)
        SurfaceState.TRANSCRIBING -> onProcessingTap(atMs)
        SurfaceState.MIC_UNARMED -> {
            reset()
            Decision(Action.PROMPT_MIC_SETUP_AND_OPEN_APP)
        }
        SurfaceState.CANCELLING -> Decision(Action.NONE)
    }

    private fun onPausedTap(atMs: Long): Decision {
        val previous = sequence
        if (previous?.origin == Origin.PAUSED && isInsideWindow(previous.atMs, atMs)) {
            sequence = null
            return Decision(Action.CANCEL_RECORDING)
        }
        if (previous?.origin == Origin.PAUSED && previous.timeout != null && atMs >= previous.timeout.deadlineMs) {
            sequence = null
            return Decision(Action.RESUME_RECORDING)
        }
        val timeout = Timeout(++nextToken, atMs + windowMs)
        sequence = Sequence(Origin.PAUSED, atMs, timeout)
        return Decision(Action.NONE, timeout)
    }

    private fun onRecordingTap(atMs: Long): Decision {
        val previous = sequence
        if (previous != null && isInsideWindow(previous.atMs, atMs)) {
            sequence = null
            return Decision(
                if (previous.origin == Origin.IDLE) {
                    Action.CANCEL_RECORDING_AND_OPEN_APP
                } else {
                    Action.CANCEL_RECORDING
                },
            )
        }

        // If the main looper delivered a later tap before its timeout callback,
        // resolve the old single tap now.  At exactly 280 ms it is deliberately
        // not a double-tap; the strict comparison is the boundary contract.
        if (previous?.origin == Origin.RECORDING && previous.timeout != null &&
            atMs >= previous.timeout.deadlineMs
        ) {
            sequence = null
            return Decision(Action.STOP_RECORDING)
        }

        val timeout = Timeout(++nextToken, atMs + windowMs)
        sequence = Sequence(Origin.RECORDING, atMs, timeout)
        return Decision(Action.NONE, timeout)
    }

    private fun onProcessingTap(atMs: Long): Decision {
        val previous = sequence
        if (previous?.origin == Origin.PROCESSING && isInsideWindow(previous.atMs, atMs)) {
            sequence = null
            return Decision(Action.CANCEL_PROCESSING)
        }
        sequence = Sequence(Origin.PROCESSING, atMs, timeout = null)
        return Decision(Action.ARM_PROCESSING_WINDOW)
    }

    fun onTimeout(timeout: Timeout, atMs: Long): Decision {
        val current = sequence
        if (current == null || current.origin !in listOf(Origin.RECORDING, Origin.PAUSED) || current.timeout != timeout) {
            return Decision(Action.NONE)
        }
        if (atMs < timeout.deadlineMs) return Decision(Action.NONE)
        sequence = null
        return Decision(if (current.origin == Origin.PAUSED) Action.RESUME_RECORDING else Action.STOP_RECORDING)
    }

    fun reset() {
        sequence = null
    }

    private fun isInsideWindow(previousAtMs: Long, atMs: Long): Boolean =
        atMs >= previousAtMs && atMs - previousAtMs < windowMs
}
