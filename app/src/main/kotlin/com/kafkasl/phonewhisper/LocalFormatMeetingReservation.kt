package com.kafkasl.phonewhisper

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal const val LOCAL_FORMAT_MEETING_UNAVAILABLE_MESSAGE = "Le moteur local est indisponible pour la réunion."
private const val LOCAL_FORMAT_MEETING_CANCELLED_MESSAGE = "La réservation du moteur local a été annulée."

internal class LocalFormatMeetingReservationRequest internal constructor(
    val completion: CompletableFuture<LocalFormatMeetingReservation>,
    private val cancelRequest: () -> Unit,
) {
    fun cancel() = cancelRequest()
}

internal class LocalFormatMeetingReservation internal constructor(
    private val releaseReservation: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean()

    override fun close() {
        if (released.compareAndSet(false, true)) releaseReservation()
    }
}

internal class LocalFormatWorkPermit internal constructor(val generation: Long)

/** Shared admission boundary used by the local formatter's worker tasks and meeting handoff. */
internal class LocalFormatReservationCoordinator(
    private val worker: Executor,
    private val processPoisoned: () -> Boolean = { false },
    private val closeEngineOnWorker: () -> Boolean,
) {
    private val lock = Any()
    private var generation = 0L
    private var nextReservationId = 0L
    private var pendingRequest: RequestState? = null
    private var activeLeaseId: Long? = null

    private var locallyPoisoned = false

    val isPoisoned: Boolean
        get() = synchronized(lock) { isPoisonedLocked() }

    fun admitWork(): LocalFormatWorkPermit? = synchronized(lock) {
        if (isPoisonedLocked() || pendingRequest != null || activeLeaseId != null) {
            null
        } else {
            LocalFormatWorkPermit(generation)
        }
    }

    /** Recheck on the worker immediately before load or other queued formatter work. */
    fun beginWork(permit: LocalFormatWorkPermit): Boolean = synchronized(lock) {
        isPermitCurrent(permit)
    }

    /** Recheck after model loading, atomically claiming the conversation-start boundary. */
    fun beginConversation(permit: LocalFormatWorkPermit): Boolean = synchronized(lock) {
        isPermitCurrent(permit)
    }

    fun reserveForMeeting(): LocalFormatMeetingReservationRequest {
        val completion = CompletableFuture<LocalFormatMeetingReservation>()
        val request = synchronized(lock) {
            if (isPoisonedLocked() || pendingRequest != null || activeLeaseId != null) {
                null
            } else {
                RequestState(++nextReservationId, completion).also {
                    pendingRequest = it
                    generation += 1
                }
            }
        }
        if (request == null) {
            completion.completeExceptionally(unavailable())
            return LocalFormatMeetingReservationRequest(completion) { }
        }

        try {
            worker.execute { finishReservationBarrier(request) }
        } catch (_: Throwable) {
            synchronized(lock) {
                if (pendingRequest === request) {
                    pendingRequest = null
                    generation += 1
                }
                locallyPoisoned = true
            }
            completion.completeExceptionally(unavailable())
        }
        return LocalFormatMeetingReservationRequest(completion) { cancel(request) }
    }

    /** Permanent for the shared core: a later owner must not re-open after uncertain cleanup. */
    fun markUncertainClose() {
        synchronized(lock) {
            locallyPoisoned = true
            generation += 1
        }
    }

    private fun isPermitCurrent(permit: LocalFormatWorkPermit): Boolean =
        !isPoisonedLocked() && pendingRequest == null && activeLeaseId == null && permit.generation == generation

    /** Fail closed if the process-wide mode coordinator cannot be queried. */
    private fun isPoisonedLocked(): Boolean = locallyPoisoned || try {
        processPoisoned()
    } catch (_: Throwable) {
        true
    }

    private fun cancel(request: RequestState) {
        synchronized(lock) {
            // Once the lease is granted, only the meeting owner may release it.
            if (pendingRequest === request) request.cancelled = true
        }
    }

    private fun finishReservationBarrier(request: RequestState) {
        val nativeClosed = try {
            closeEngineOnWorker()
        } catch (_: Throwable) {
            false
        }
        val outcome = synchronized(lock) {
            if (!nativeClosed) locallyPoisoned = true
            if (pendingRequest === request) pendingRequest = null

            when {
                isPoisonedLocked() || !nativeClosed -> {
                    generation += 1
                    BarrierOutcome.FAILED
                }
                request.cancelled -> {
                    generation += 1
                    BarrierOutcome.CANCELLED
                }
                else -> {
                    activeLeaseId = request.id
                    BarrierOutcome.GRANTED
                }
            }
        }
        // CompletableFuture may run dependent callbacks inline, so never complete under state lock.
        when (outcome) {
            BarrierOutcome.FAILED -> request.completion.completeExceptionally(unavailable())
            BarrierOutcome.CANCELLED -> request.completion.completeExceptionally(
                CancellationException(LOCAL_FORMAT_MEETING_CANCELLED_MESSAGE),
            )
            BarrierOutcome.GRANTED -> request.completion.complete(
                LocalFormatMeetingReservation { release(request.id) },
            )
        }
    }

    private fun release(reservationId: Long) {
        synchronized(lock) {
            if (activeLeaseId == reservationId) {
                activeLeaseId = null
                generation += 1
            }
        }
    }

    private fun unavailable() = IllegalStateException(LOCAL_FORMAT_MEETING_UNAVAILABLE_MESSAGE)

    private class RequestState(
        val id: Long,
        val completion: CompletableFuture<LocalFormatMeetingReservation>,
        var cancelled: Boolean = false,
    )

    private enum class BarrierOutcome {
        FAILED,
        CANCELLED,
        GRANTED,
    }
}

/** Converts uncertain native cleanup into a permanent shared-core poison, without exposing causes. */
internal class LocalFormatNativeCloseGuard(
    private val onUncertainClose: () -> Unit,
    private val processPoisoned: () -> Boolean = { false },
) {
    private val lock = Any()
    private var uncertain = false

    fun closeConversation(close: () -> Unit): Boolean = closeSafely(close)

    fun closeEngine(close: () -> Unit): Boolean = closeSafely(close)

    /** Failed initialization only needs cleanup if LiteRT-LM actually created a native handle. */
    fun closeInitializedFailedEngine(isInitialized: Boolean, close: () -> Unit): Boolean =
        if (isInitialized) closeSafely(close) else true

    private fun closeSafely(close: () -> Unit): Boolean {
        val (succeeded, notifyPoison) = synchronized(lock) {
            val globallyPoisoned = try { processPoisoned() } catch (_: Throwable) { true }
            if (uncertain || globallyPoisoned) return@synchronized false to false
            try {
                close()
                true to false
            } catch (_: Throwable) {
                uncertain = true
                false to true
            }
        }
        if (notifyPoison) {
            try { onUncertainClose() } catch (_: Throwable) {
                // Native cleanup already failed; observers cannot change that result or trigger retry.
            }
        }
        return succeeded
    }
}
