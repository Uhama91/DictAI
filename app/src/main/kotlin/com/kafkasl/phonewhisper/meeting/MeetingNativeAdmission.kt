package com.kafkasl.phonewhisper.meeting

import com.kafkasl.phonewhisper.LocalFormatMeetingReservation
import com.kafkasl.phonewhisper.LocalFormatMeetingReservationRequest
import com.kafkasl.phonewhisper.TranscriptionMode
import com.kafkasl.phonewhisper.TranscriptionModeCoordinator
import com.kafkasl.phonewhisper.TranscriptionRunLease
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

private const val MEETING_NATIVE_UNAVAILABLE_MESSAGE = "Le moteur de réunion est indisponible."
private const val MEETING_NATIVE_CANCELLED_MESSAGE = "La réservation du moteur de réunion a été annulée."

/**
 * Reserves the process-wide meeting run before closing either resident native engine.
 * Future continuations and cleanup run on the completing worker and never under [Request.lock].
 */
internal class MeetingNativeAdmission(
    private val coordinator: TranscriptionModeCoordinator,
    private val closeResidentDictation: () -> CompletableFuture<Unit>,
    private val reserveFormatter: (() -> LocalFormatMeetingReservationRequest)? = null,
) : MeetingNativeReservationPort {
    override fun request(runId: String): MeetingNativeReservationRequest {
        val run = coordinator.reserveRun(TranscriptionMode.MEETING)
            ?: return FailedRequest(unavailable())
        return Request(run).also { it.begin() }
    }

    private inner class Request(
        private val run: TranscriptionRunLease,
    ) : MeetingNativeReservationRequest {
        override val lease = CompletableFuture<MeetingNativeRuntimeLease>()

        val lock = Any()
        private var ready = Barrier.PENDING
        private var residentClose = Barrier.PENDING
        private var formatterClose = if (reserveFormatter == null) Barrier.SUCCESS else Barrier.PENDING
        private var formatterRequest: LocalFormatMeetingReservationRequest? = null
        private var formatterReservation: LocalFormatMeetingReservation? = null
        private var cancelled = false
        private var unsafe = false
        private var safeFailure = false
        private var finalizing = false
        private var finished = false
        private var granted = false

        fun begin() {
            run.ready.whenComplete { _, error -> onReady(error) }

            val residentBarrier = try {
                closeResidentDictation()
            } catch (_: Throwable) {
                onResidentClosed(IllegalStateException(MEETING_NATIVE_UNAVAILABLE_MESSAGE))
                null
            }
            residentBarrier?.whenComplete { _, error -> onResidentClosed(error) }

            var formatterSetupFailed = false
            val request = try {
                reserveFormatter?.invoke()
            } catch (_: Throwable) {
                formatterSetupFailed = true
                onFormatterFailed(IllegalStateException(MEETING_NATIVE_UNAVAILABLE_MESSAGE))
                null
            }
            if (reserveFormatter != null && request == null && !formatterSetupFailed) {
                // The public API cannot distinguish a safe refusal from an uncertain close;
                // onFormatterFailed therefore poisons conservatively.
                onFormatterFailed(IllegalStateException(MEETING_NATIVE_UNAVAILABLE_MESSAGE))
            } else if (request != null) {
                synchronized(lock) { formatterRequest = request }
                request.completion.whenComplete { reservation, error ->
                    if (error == null && reservation != null) onFormatterReserved(reservation)
                    else onFormatterFailed(error ?: IllegalStateException(MEETING_NATIVE_UNAVAILABLE_MESSAGE))
                }
            }
        }

        override fun cancel() {
            val pendingFormatter = synchronized(lock) {
                if (finished || granted || finalizing) return
                cancelled = true
                formatterRequest
            }
            try {
                pendingFormatter?.cancel()
            } catch (_: Throwable) {
                // The original barriers still determine whether releasing the run is safe.
            }
            finishIfReady()
        }

        private fun onReady(error: Throwable?) {
            val poisoned = error != null && coordinator.snapshot().poisoned
            synchronized(lock) {
                if (ready != Barrier.PENDING) return
                ready = if (error == null) Barrier.SUCCESS else Barrier.FAILURE
                if (error != null) safeFailure = true
                if (poisoned) unsafe = true
            }
            finishIfReady()
        }

        private fun onResidentClosed(error: Throwable?) {
            synchronized(lock) {
                if (residentClose != Barrier.PENDING) return
                residentClose = if (error == null) Barrier.SUCCESS else Barrier.FAILURE
                if (error != null) unsafe = true
            }
            if (error != null) {
                // A close failure leaves the owner uncertain; preserve both reservations forever.
                coordinator.reportUncertainClose()
            }
            finishIfReady()
        }

        private fun onFormatterReserved(reservation: LocalFormatMeetingReservation) {
            val closeLateReservation = synchronized(lock) {
                if (formatterClose != Barrier.PENDING) {
                    true
                } else {
                    formatterReservation = reservation
                    formatterClose = Barrier.SUCCESS
                    false
                }
            }
            if (closeLateReservation) {
                // A duplicate or late completion cannot take ownership away from the request.
                if (!safeClose(reservation)) coordinator.reportUncertainClose()
                return
            }
            finishIfReady()
        }

        private fun onFormatterFailed(error: Throwable) {
            val cancelledByOwner = error is CancellationException && synchronized(lock) { cancelled }
            synchronized(lock) {
                if (formatterClose != Barrier.PENDING) return
                formatterClose = Barrier.FAILURE
                if (!cancelledByOwner) unsafe = true
            }
            // The formatter request's public future cannot distinguish a harmless rejection
            // from a locally poisoned/uncertain close. Fail closed unless this is our own cancel.
            if (!cancelledByOwner) coordinator.reportUncertainClose()
            finishIfReady()
        }

        private fun finishIfReady() {
            val canEvaluate = synchronized(lock) {
                if (finished || finalizing || ready == Barrier.PENDING || residentClose == Barrier.PENDING ||
                    formatterClose == Barrier.PENDING
                ) {
                    false
                } else {
                    finalizing = true
                    true
                }
            }
            if (!canEvaluate) return

            // Query the coordinator outside the adapter lock. If poison races the grant, the
            // returned lease still refuses use through isCurrentRun() before native work begins.
            val processPoisoned = coordinator.snapshot().poisoned
            val runCurrent = coordinator.isCurrentRun(run)
            val outcome = synchronized(lock) {
                if (finished) return
                when {
                    unsafe || processPoisoned -> {
                        finished = true
                        Outcome.POISONED
                    }
                    safeFailure || !runCurrent -> {
                        finished = true
                        Outcome.SAFE_FAILURE
                    }
                    cancelled -> {
                        finished = true
                        Outcome.CANCELLED
                    }
                    else -> {
                        finished = true
                        granted = true
                        Outcome.GRANT
                    }
                }
            }

            when (outcome) {
                Outcome.POISONED -> lease.completeExceptionally(unavailable())
                Outcome.SAFE_FAILURE -> cleanupAndFail(cancelled = synchronized(lock) { cancelled })
                Outcome.CANCELLED -> cleanupAndFail(cancelled = true)
                Outcome.GRANT -> {
                    val reservation = synchronized(lock) { formatterReservation }
                    lease.complete(GrantedLease(coordinator, run, reservation))
                }
            }
        }

        private fun cleanupAndFail(cancelled: Boolean) {
            if (coordinator.snapshot().poisoned) {
                lease.completeExceptionally(unavailable())
                return
            }

            val reservation = synchronized(lock) { formatterReservation }
            if (reservation != null && !safeClose(reservation)) {
                coordinator.reportUncertainClose()
                lease.completeExceptionally(unavailable())
                return
            }

            if (coordinator.snapshot().poisoned) {
                lease.completeExceptionally(unavailable())
                return
            }
            try {
                run.close()
            } catch (_: Throwable) {
                coordinator.reportUncertainClose()
                lease.completeExceptionally(unavailable())
                return
            }

            if (cancelled) lease.completeExceptionally(CancellationException(MEETING_NATIVE_CANCELLED_MESSAGE))
            else lease.completeExceptionally(unavailable())
        }

        private fun safeClose(reservation: LocalFormatMeetingReservation): Boolean = try {
            reservation.close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private class GrantedLease(
        private val coordinator: TranscriptionModeCoordinator,
        private val run: TranscriptionRunLease,
        private val formatter: LocalFormatMeetingReservation?,
    ) : MeetingNativeRuntimeLease {
        private val lock = Any()
        private var released = false
        private var poisoned = false

        override fun isCurrentAndUsable(): Boolean {
            val usable = synchronized(lock) { !released && !poisoned }
            return usable && coordinator.isCurrentRun(run)
        }

        override fun release() {
            val mayRelease = synchronized(lock) {
                if (released || poisoned) false else {
                    released = true
                    true
                }
            }
            if (!mayRelease) return

            if (coordinator.snapshot().poisoned || !coordinator.isCurrentRun(run)) {
                poisonProcess()
                return
            }
            if (formatter != null && !tryClose(formatter)) {
                poisonProcess()
                return
            }
            if (coordinator.snapshot().poisoned) return
            try {
                run.close()
            } catch (_: Throwable) {
                poisonProcess()
            }
        }

        override fun poison() = poisonProcess()

        private fun poisonProcess() {
            val notify = synchronized(lock) {
                if (poisoned) false else {
                    poisoned = true
                    true
                }
            }
            if (notify) coordinator.reportUncertainClose()
        }

        private fun tryClose(reservation: LocalFormatMeetingReservation): Boolean = try {
            reservation.close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private class FailedRequest(error: Throwable) : MeetingNativeReservationRequest {
        override val lease = CompletableFuture<MeetingNativeRuntimeLease>().also {
            it.completeExceptionally(error)
        }

        override fun cancel(): Unit = Unit
    }

    private enum class Barrier {
        PENDING,
        SUCCESS,
        FAILURE,
    }

    private enum class Outcome {
        POISONED,
        SAFE_FAILURE,
        CANCELLED,
        GRANT,
    }

    private companion object {
        fun unavailable() = IllegalStateException(MEETING_NATIVE_UNAVAILABLE_MESSAGE)
    }
}
