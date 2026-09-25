package com.kafkasl.phonewhisper.meeting

import com.kafkasl.phonewhisper.LocalFormatReservationCoordinator
import com.kafkasl.phonewhisper.TranscriptionMode
import com.kafkasl.phonewhisper.TranscriptionModeCoordinator
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MeetingNativeAdmissionTest {
    @Test
    fun `reserves the meeting run before asking owners to close`() {
        val fixture = fixture()

        val request = fixture.admission.request("meeting-1")

        assertTrue(fixture.runWasReservedBeforeResidentClose.get())
        assertEquals(listOf("close-resident", "reserve-formatter"), fixture.events)
        assertFalse(request.lease.isDone)
    }

    @Test
    fun `waits for coordinator readiness resident close and formatter close`() {
        val fixture = fixture()
        val request = fixture.admission.request("meeting-1")

        assertTrue(fixture.formatWorker.runNext())
        assertFalse(request.lease.isDone)

        fixture.loadTicket.completeWithoutPublish()
        assertFalse(request.lease.isDone)

        fixture.residentClosed.complete(Unit)
        val lease = request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(lease.isCurrentAndUsable())
        assertNull(fixture.formatter?.admitWork())
        lease.release()
        assertNull(fixture.mode.snapshot().activeRunMode)
        assertNotNull(fixture.formatter?.admitWork())
    }

    @Test
    fun `can finish resident barriers before the formatter barrier`() {
        val fixture = fixture()
        val request = fixture.admission.request("meeting-1")

        fixture.loadTicket.completeWithoutPublish()
        fixture.residentClosed.complete(Unit)
        assertFalse(request.lease.isDone)

        assertTrue(fixture.formatWorker.runNext())
        val lease = request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(lease.isCurrentAndUsable())
        lease.release()
    }

    @Test
    fun `cancellation waits for all close barriers then releases safe reservations`() {
        val fixture = fixture()
        val request = fixture.admission.request("meeting-1")

        request.cancel()
        assertFalse(request.lease.isDone)
        assertNull(fixture.formatter?.admitWork())

        assertTrue(fixture.formatWorker.runNext())
        assertFalse(request.lease.isDone)
        fixture.loadTicket.completeWithoutPublish()
        assertFalse(request.lease.isDone)
        fixture.residentClosed.complete(Unit)

        assertThrows(CancellationException::class.java) {
            request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertNull(fixture.mode.snapshot().activeRunMode)
        assertNotNull(fixture.formatter?.admitWork())
    }

    @Test
    fun `cancellation after formatter grant releases it only after remaining barriers`() {
        val fixture = fixture()
        val request = fixture.admission.request("meeting-1")

        assertTrue(fixture.formatWorker.runNext())
        assertNull(fixture.formatter?.admitWork())
        request.cancel()
        assertFalse(request.lease.isDone)
        assertNull(fixture.formatter?.admitWork())

        fixture.loadTicket.completeWithoutPublish()
        fixture.residentClosed.complete(Unit)
        assertThrows(CancellationException::class.java) {
            request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertNotNull(fixture.formatter?.admitWork())
        assertNull(fixture.mode.snapshot().activeRunMode)
    }

    @Test
    fun `cancellation after grant does not release the lease`() {
        val fixture = fixture()
        val request = fixture.admission.request("meeting-1")
        fixture.loadTicket.completeWithoutPublish()
        fixture.residentClosed.complete(Unit)
        assertTrue(fixture.formatWorker.runNext())
        val lease = request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        request.cancel()

        assertTrue(lease.isCurrentAndUsable())
        assertNull(fixture.formatter?.admitWork())
        lease.release()
        assertFalse(lease.isCurrentAndUsable())
        assertNotNull(fixture.formatter?.admitWork())
    }

    @Test
    fun `uncertain resident close poisons the process and retains both reservations`() {
        val fixture = fixture()
        val request = fixture.admission.request("meeting-1")
        assertTrue(fixture.formatWorker.runNext())
        fixture.loadTicket.completeWithoutPublish()
        fixture.residentClosed.completeExceptionally(IllegalStateException("private native detail"))

        val failure = assertThrows(ExecutionException::class.java) {
            request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals("Le moteur de réunion est indisponible.", failure.cause?.message)
        assertNull(failure.cause?.cause)
        assertTrue(fixture.mode.snapshot().poisoned)
        assertEquals(TranscriptionMode.MEETING, fixture.mode.snapshot().activeRunMode)
        assertNull(fixture.mode.reserveRun(TranscriptionMode.MEETING))
        assertNull(fixture.formatter?.admitWork())
    }

    @Test
    fun `uncertain formatter close poisons the process and keeps the meeting run`() {
        val fixture = fixture(formatterClose = { mode ->
            mode.reportUncertainClose()
            false
        })
        val request = fixture.admission.request("meeting-1")
        fixture.loadTicket.completeWithoutPublish()
        fixture.residentClosed.complete(Unit)
        assertTrue(fixture.formatWorker.runNext())

        val failure = assertThrows(ExecutionException::class.java) {
            request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals("Le moteur de réunion est indisponible.", failure.cause?.message)
        assertNull(failure.cause?.cause)
        assertTrue(fixture.mode.snapshot().poisoned)
        assertEquals(TranscriptionMode.MEETING, fixture.mode.snapshot().activeRunMode)
        assertNull(fixture.mode.reserveRun(TranscriptionMode.MEETING))
        assertNull(fixture.formatter?.admitWork())
    }

    @Test
    fun `formatter close refusal without an existing global poison is treated as uncertain`() {
        val fixture = fixture(formatterClose = { false })
        val request = fixture.admission.request("meeting-1")
        assertTrue(fixture.formatWorker.runNext())
        fixture.loadTicket.completeWithoutPublish()
        fixture.residentClosed.complete(Unit)

        val failure = assertThrows(ExecutionException::class.java) {
            request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals("Le moteur de réunion est indisponible.", failure.cause?.message)
        assertTrue(fixture.mode.snapshot().poisoned)
        assertEquals(TranscriptionMode.MEETING, fixture.mode.snapshot().activeRunMode)
        assertNull(fixture.formatter?.admitWork())
    }

    @Test
    fun `a rejected mode reservation does not close or reserve either engine`() {
        val mode = TranscriptionModeCoordinator()
        val closeCalls = AtomicBoolean(false)
        val worker = ManualExecutor()
        val formatter = LocalFormatReservationCoordinator(
            worker = worker,
            closeEngineOnWorker = { error("formatter must not be closed") },
        )
        val admission = MeetingNativeAdmission(
            coordinator = mode,
            closeResidentDictation = {
                closeCalls.set(true)
                CompletableFuture.completedFuture(Unit)
            },
            reserveFormatter = { formatter.reserveForMeeting() },
        )

        val request = admission.request("meeting-1")
        val failure = assertThrows(ExecutionException::class.java) {
            request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertNotNull(failure.cause)
        assertFalse(closeCalls.get())
        assertEquals(0, worker.size)
        assertNull(mode.snapshot().activeRunMode)
    }

    @Test
    fun `lease completion permits another thread to cancel without waiting on adapter lock`() {
        val fixture = fixture()
        val request = fixture.admission.request("meeting-1")
        val callbackReentered = AtomicBoolean(false)
        request.lease.whenComplete { _, _ ->
            val callbackThread = Thread {
                request.cancel()
                callbackReentered.set(true)
            }
            callbackThread.start()
            callbackThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }

        fixture.loadTicket.completeWithoutPublish()
        fixture.residentClosed.complete(Unit)
        assertTrue(fixture.formatWorker.runNext())
        val lease = request.lease.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(callbackReentered.get())
        assertTrue(lease.isCurrentAndUsable())
        lease.release()
    }

    private fun fixture(
        formatterClose: (TranscriptionModeCoordinator) -> Boolean = { true },
    ): Fixture {
        val mode = TranscriptionModeCoordinator()
        val loadTicket = checkNotNull(mode.beginDictationLoad())
        assertTrue(mode.changeMode(TranscriptionMode.MEETING))
        val residentClosed = CompletableFuture<Unit>()
        val events = mutableListOf<String>()
        val runWasReservedBeforeResidentClose = AtomicBoolean(false)
        val formatWorker = ManualExecutor()
        val formatter = LocalFormatReservationCoordinator(
            worker = formatWorker,
            processPoisoned = { mode.snapshot().poisoned },
            closeEngineOnWorker = { formatterClose(mode) },
        )
        val admission = MeetingNativeAdmission(
            coordinator = mode,
            closeResidentDictation = {
                runWasReservedBeforeResidentClose.set(
                    mode.snapshot().activeRunMode == TranscriptionMode.MEETING,
                )
                events += "close-resident"
                residentClosed
            },
            reserveFormatter = {
                events += "reserve-formatter"
                formatter.reserveForMeeting()
            },
        )
        return Fixture(
            mode = mode,
            loadTicket = loadTicket,
            residentClosed = residentClosed,
            formatWorker = formatWorker,
            formatter = formatter,
            admission = admission,
            events = events,
            runWasReservedBeforeResidentClose = runWasReservedBeforeResidentClose,
        )
    }

    private data class Fixture(
        val mode: TranscriptionModeCoordinator,
        val loadTicket: com.kafkasl.phonewhisper.DictationLoadTicket,
        val residentClosed: CompletableFuture<Unit>,
        val formatWorker: ManualExecutor,
        val formatter: LocalFormatReservationCoordinator?,
        val admission: MeetingNativeAdmission,
        val events: MutableList<String>,
        val runWasReservedBeforeResidentClose: AtomicBoolean,
    )

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext(): Boolean {
            val task = tasks.pollFirst() ?: return false
            task.run()
            return true
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}
