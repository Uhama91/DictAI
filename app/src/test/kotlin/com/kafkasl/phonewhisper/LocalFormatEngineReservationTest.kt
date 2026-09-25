package com.kafkasl.phonewhisper

import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFormatEngineReservationTest {
    @Test
    fun `reservation closes immediately at admission boundary and watchdog return cannot release it`() {
        val worker = QueuedWorker()
        val closeCount = AtomicInteger()
        val coordinator = LocalFormatReservationCoordinator(worker) {
            closeCount.incrementAndGet()
            true
        }
        val preExistingWork = checkNotNull(coordinator.admitWork())
        assertTrue(coordinator.beginWork(preExistingWork))
        assertTrue(coordinator.beginConversation(preExistingWork))

        val request = coordinator.reserveForMeeting()
        assertFalse(request.completion.isDone)
        assertNull("a new engine owner sees the shared reservation immediately", coordinator.admitWork())

        val runtimeAdmission = GemmaRuntimeAdmission()
        val deadline = GemmaInitializationDeadline(runtimeAdmission::block, runtimeAdmission::resume)
        deadline.timeout()
        deadline.returned()
        assertFalse(runtimeAdmission.isBlocked)
        assertNull("watchdog cleanup cannot lift the independent meeting reservation", coordinator.admitWork())

        assertTrue(worker.runNext())
        val lease = request.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(1, closeCount.get())
        assertNull(coordinator.admitWork())
        request.cancel()
        assertNull("cancelling an already granted request cannot revoke its lease", coordinator.admitWork())
        lease.close()
        lease.close()
        assertNotNull("idempotent release returns the shared formatter slot", coordinator.admitWork())
        assertEquals(1, closeCount.get())
    }

    @Test
    fun `queued work rechecks before loading and an in-flight load rechecks before conversation`() {
        val worker = QueuedWorker()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = LocalFormatReservationCoordinator(worker) {
            events += "engine-close"
            true
        }
        val loadingPermit = checkNotNull(coordinator.admitWork())
        val queuedPermit = checkNotNull(coordinator.admitWork())
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val conversationAllowed = AtomicBoolean(true)

        worker.execute {
            if (coordinator.beginWork(loadingPermit)) {
                events += "load-start"
                loadEntered.countDown()
                check(releaseLoad.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "load latch timed out" }
                events += "load-return"
                conversationAllowed.set(coordinator.beginConversation(loadingPermit))
                events += if (conversationAllowed.get()) "conversation" else "conversation-skipped"
            }
        }
        val activeLoad = worker.startNext()
        try {
            assertTrue(loadEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            worker.execute {
                events += if (coordinator.beginWork(queuedPermit)) "queued-load" else "queued-work-rejected"
            }
            val request = coordinator.reserveForMeeting()
            assertFalse(request.completion.isDone)
            assertNull(coordinator.admitWork())

            releaseLoad.countDown()
            activeLoad.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse(activeLoad.isAlive)
            assertTrue(worker.runNext())
            assertTrue(worker.runNext())
            request.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).close()

            assertFalse("reservation during load prevents conversation creation", conversationAllowed.get())
            assertEquals(
                listOf("load-start", "load-return", "conversation-skipped", "queued-work-rejected", "engine-close"),
                events.toList(),
            )
        } finally {
            releaseLoad.countDown()
            activeLoad.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }
    }

    @Test
    fun `reservation barrier waits for already-started native generation`() {
        val worker = QueuedWorker()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = LocalFormatReservationCoordinator(worker) {
            events += "engine-close"
            true
        }
        val permit = checkNotNull(coordinator.admitWork())
        val generationEntered = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        worker.execute {
            if (coordinator.beginWork(permit) && coordinator.beginConversation(permit)) {
                events += "generation-start"
                generationEntered.countDown()
                check(releaseGeneration.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "generation latch timed out" }
                events += "generation-return"
            }
        }
        val activeGeneration = worker.startNext()

        try {
            assertTrue(generationEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val request = coordinator.reserveForMeeting()
            assertFalse(request.completion.isDone)
            assertEquals(listOf("generation-start"), events.toList())

            releaseGeneration.countDown()
            activeGeneration.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse(activeGeneration.isAlive)
            assertTrue(worker.runNext())
            request.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).close()
            assertEquals(listOf("generation-start", "generation-return", "engine-close"), events.toList())
        } finally {
            releaseGeneration.countDown()
            activeGeneration.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }
    }

    @Test
    fun `cancel waits for safe close before completing exceptionally and reopening admission`() {
        val worker = QueuedWorker()
        val coordinator = LocalFormatReservationCoordinator(worker) { true }
        val request = coordinator.reserveForMeeting()
        request.cancel()
        request.cancel()

        assertFalse(request.completion.isDone)
        assertNull(coordinator.admitWork())
        assertTrue(worker.runNext())
        val error = assertThrows(CancellationException::class.java) {
            request.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals("La réservation du moteur local a été annulée.", error.message)
        assertNotNull(coordinator.admitWork())
    }

    @Test
    fun `close failure wins over cancellation and poisons future reservations`() {
        val worker = QueuedWorker()
        val coordinator = LocalFormatReservationCoordinator(worker) { false }
        val request = coordinator.reserveForMeeting()
        request.cancel()

        assertTrue(worker.runNext())
        val error = assertThrows(ExecutionException::class.java) {
            request.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals("Le moteur local est indisponible pour la réunion.", error.cause?.message)
        assertNull(error.cause?.cause)
        assertTrue(coordinator.isPoisoned)
        assertNull(coordinator.admitWork())

        val laterRequest = coordinator.reserveForMeeting()
        val laterError = assertThrows(ExecutionException::class.java) {
            laterRequest.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals("Le moteur local est indisponible pour la réunion.", laterError.cause?.message)
        assertEquals(0, worker.size)
    }

    @Test
    fun `a close failure before meeting request remains poisoned across later owners`() {
        val worker = QueuedWorker()
        val coordinator = LocalFormatReservationCoordinator(worker) { error("must not run") }
        coordinator.markUncertainClose()

        assertNull(coordinator.admitWork())
        val request = coordinator.reserveForMeeting()
        val error = assertThrows(ExecutionException::class.java) {
            request.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals("Le moteur local est indisponible pour la réunion.", error.cause?.message)
        assertNull(error.cause?.cause)
        assertEquals(0, worker.size)
    }

    @Test
    fun `process poison rejects formatter admission queued work and conversation boundary`() {
        val worker = QueuedWorker()
        val processCoordinator = TranscriptionModeCoordinator()
        val coordinator = LocalFormatReservationCoordinator(
            worker = worker,
            closeEngineOnWorker = { error("poisoned process must not start a close barrier") },
            processPoisoned = { processCoordinator.snapshot().poisoned },
        )
        val inFlightPermit = checkNotNull(coordinator.admitWork())
        assertTrue(coordinator.beginWork(inFlightPermit))
        val queuedPermit = checkNotNull(coordinator.admitWork())
        val nativeLoadStarted = AtomicBoolean()
        worker.execute {
            if (coordinator.beginWork(queuedPermit)) nativeLoadStarted.set(true)
        }

        processCoordinator.reportUncertainClose()

        assertTrue(coordinator.isPoisoned)
        assertNull("warm/prepare/enqueue admission shares this process gate", coordinator.admitWork())
        assertTrue(worker.runNext())
        assertFalse("queued operations recheck before native load", nativeLoadStarted.get())
        assertFalse("an in-flight load rechecks before creating a conversation", coordinator.beginConversation(inFlightPermit))
        assertEquals(0, worker.size)

        val request = coordinator.reserveForMeeting()
        val error = assertThrows(ExecutionException::class.java) {
            request.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals("Le moteur local est indisponible pour la réunion.", error.cause?.message)
        assertEquals(0, worker.size)
    }

    @Test
    fun `global process poison prevents later Gemma destruction calls`() {
        val processCoordinator = TranscriptionModeCoordinator()
        val localReservation = LocalFormatReservationCoordinator(
            worker = QueuedWorker(),
            processPoisoned = { processCoordinator.snapshot().poisoned },
            closeEngineOnWorker = { true },
        )
        val nativeGuard = LocalFormatNativeCloseGuard(
            onUncertainClose = {
                localReservation.markUncertainClose()
                processCoordinator.reportUncertainClose()
            },
            processPoisoned = { processCoordinator.snapshot().poisoned },
        )
        val nativeAttempts = AtomicInteger()
        processCoordinator.reportUncertainClose()

        assertFalse(nativeGuard.closeConversation { nativeAttempts.incrementAndGet() })
        assertFalse(nativeGuard.closeEngine { nativeAttempts.incrementAndGet() })
        assertEquals(0, nativeAttempts.get())
        assertTrue(localReservation.isPoisoned)
        assertTrue(processCoordinator.snapshot().poisoned)
    }

    @Test
    fun `conversation engine and initialized failed init close errors poison the shared admission boundary`() {
        val closeSites: List<(LocalFormatNativeCloseGuard) -> Boolean> = listOf(
            { guard -> guard.closeConversation { throw IllegalStateException("private conversation native error") } },
            { guard -> guard.closeEngine { throw IllegalStateException("private engine native error") } },
            { guard ->
                guard.closeInitializedFailedEngine(isInitialized = true) {
                    throw IllegalStateException("private failed-init native error")
                }
            },
        )

        closeSites.forEach { closeAtActualCleanupBoundary ->
            val worker = QueuedWorker()
            val coordinator = LocalFormatReservationCoordinator(worker) { true }
            val guard = LocalFormatNativeCloseGuard(coordinator::markUncertainClose)

            assertFalse(closeAtActualCleanupBoundary(guard))
            assertTrue(coordinator.isPoisoned)
            assertNull(coordinator.admitWork())
            val laterRequest = coordinator.reserveForMeeting()
            val error = assertThrows(ExecutionException::class.java) {
                laterRequest.completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            assertEquals("Le moteur local est indisponible pour la réunion.", error.cause?.message)
            assertNull(error.cause?.cause)
            assertEquals(0, worker.size)
        }
    }

    @Test
    fun `failed initialization without a native handle does not call close or poison admission`() {
        val worker = QueuedWorker()
        val coordinator = LocalFormatReservationCoordinator(worker) { true }
        val guard = LocalFormatNativeCloseGuard(coordinator::markUncertainClose)
        val closeInvoked = AtomicBoolean()

        assertTrue(guard.closeInitializedFailedEngine(isInitialized = false) { closeInvoked.set(true) })

        assertFalse(closeInvoked.get())
        assertFalse(coordinator.isPoisoned)
        assertNotNull(coordinator.admitWork())
    }

    @Test
    fun `uncertain conversation or engine close permanently suppresses later native destroy attempts`() {
        val firstCloseSites: List<(LocalFormatNativeCloseGuard, MutableList<String>) -> Boolean> = listOf(
            { guard, attempts ->
                guard.closeConversation {
                    attempts += "conversation-close"
                    throw IllegalStateException("private conversation close error")
                }
            },
            { guard, attempts ->
                guard.closeEngine {
                    attempts += "engine-close"
                    throw IllegalStateException("private engine close error")
                }
            },
        )

        firstCloseSites.forEach { firstClose ->
            val coordinator = LocalFormatReservationCoordinator(QueuedWorker()) { true }
            val guard = LocalFormatNativeCloseGuard(coordinator::markUncertainClose)
            val attempts = mutableListOf<String>()

            assertFalse(firstClose(guard, attempts))
            assertFalse(guard.closeEngine { attempts += "later-engine-close" })
            assertFalse(guard.closeConversation { attempts += "later-conversation-close" })
            assertFalse(guard.closeInitializedFailedEngine(isInitialized = true) {
                attempts += "later-failed-init-close"
            })

            assertEquals(1, attempts.size)
            assertTrue(coordinator.isPoisoned)
            assertNull(coordinator.admitWork())
        }
    }

    @Test
    fun `only one of two concurrent meeting reservations reaches the worker`() {
        val worker = QueuedWorker()
        val closeCount = AtomicInteger()
        val coordinator = LocalFormatReservationCoordinator(worker) {
            closeCount.incrementAndGet()
            true
        }
        val start = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<LocalFormatMeetingReservationRequest>())
        val first = Thread {
            start.await()
            requests += coordinator.reserveForMeeting()
        }
        val second = Thread {
            start.await()
            requests += coordinator.reserveForMeeting()
        }
        first.start()
        second.start()
        start.countDown()
        first.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        second.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(2, requests.size)

        val pending = requests.filterNot { it.completion.isDone }
        val rejected = requests.filter { it.completion.isCompletedExceptionally }
        assertEquals(1, pending.size)
        assertEquals(1, rejected.size)
        val rejectedError = assertThrows(ExecutionException::class.java) {
            rejected.single().completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals("Le moteur local est indisponible pour la réunion.", rejectedError.cause?.message)
        assertEquals(1, worker.size)

        assertTrue(worker.runNext())
        pending.single().completion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).close()
        assertEquals(1, closeCount.get())
    }

    private class QueuedWorker : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            synchronized(tasks) { tasks.addLast(command) }
        }

        fun runNext(): Boolean {
            val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() } ?: return false
            next.run()
            return true
        }

        fun startNext(): Thread {
            val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() }
                ?: error("No queued worker task")
            return Thread(next, "test-local-format-worker").apply { isDaemon = true; start() }
        }

        val size: Int
            get() = synchronized(tasks) { tasks.size }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}
