package com.kafkasl.phonewhisper

import android.content.SharedPreferences
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionModeCoordinatorTest {
    @Test
    fun `mode preference defaults safely and survives reopening`() {
        val values = mutableMapOf<String, Any?>()
        val storage = memoryPreferences(values)

        assertEquals(TranscriptionMode.DICTATION, PersistencePrefs(storage).transcriptionMode)
        PersistencePrefs(storage).transcriptionMode = TranscriptionMode.MEETING
        assertEquals(TranscriptionMode.MEETING, PersistencePrefs(storage).transcriptionMode)
        values["transcription_mode"] = "unknown-mode"
        assertEquals(TranscriptionMode.DICTATION, PersistencePrefs(storage).transcriptionMode)
    }

    @Test
    fun `a run keeps the mode locked until its idempotent safe close`() {
        val coordinator = coordinator()
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        val run = coordinator.reserveRun(TranscriptionMode.MEETING)

        assertNotNull(run)
        run!!.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNull(coordinator.reserveRun(TranscriptionMode.MEETING))
        assertFalse(coordinator.changeMode(TranscriptionMode.DICTATION))
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)

        run.close()
        run.close()
        assertTrue(coordinator.changeMode(TranscriptionMode.DICTATION))
        assertNull(coordinator.snapshot().activeRunMode)
    }

    @Test
    fun `current run validation checks active owner identity generation mode and poison`() {
        val coordinator = coordinator()
        val foreignCoordinator = coordinator()
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        assertTrue(foreignCoordinator.changeMode(TranscriptionMode.MEETING))

        val first = coordinator.reserveRun(TranscriptionMode.MEETING)!!
        val foreign = foreignCoordinator.reserveRun(TranscriptionMode.MEETING)!!
        first.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        foreign.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(coordinator.isCurrentRun(first))
        assertFalse(coordinator.isCurrentRun(foreign))

        first.close()
        val replacement = coordinator.reserveRun(TranscriptionMode.MEETING)!!
        replacement.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(first.generation, replacement.generation)
        assertFalse("a closed run cannot become current again", coordinator.isCurrentRun(first))
        assertTrue(coordinator.isCurrentRun(replacement))

        coordinator.reportUncertainClose()
        assertFalse(coordinator.isCurrentRun(replacement))
        replacement.close()
        foreign.close()
    }

    @Test
    fun `meeting readiness waits for old preload and for a resident engine close`() {
        val coordinator = coordinator()
        val preload = coordinator.beginDictationLoad()!!
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        val meeting = coordinator.reserveRun(TranscriptionMode.MEETING)!!

        assertFalse(meeting.ready.isDone)
        preload.completeWithoutPublish()
        assertTrue(meeting.ready.isDone)
        meeting.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        meeting.close()

        assertTrue(coordinator.changeMode(TranscriptionMode.DICTATION))
        val residentTicket = coordinator.beginDictationLoad()!!
        val registration = residentTicket.publish { }!!
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        val nextMeeting = coordinator.reserveRun(TranscriptionMode.MEETING)!!
        assertFalse(nextMeeting.ready.isDone)
        registration.close()
        nextMeeting.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        nextMeeting.close()
    }

    @Test
    fun `stale preload cannot publish after a mode generation change`() {
        val coordinator = coordinator()
        val ticket = coordinator.beginDictationLoad()!!
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        var published = false

        assertNull(ticket.publish { published = true })
        assertFalse(published)
        assertEquals(0, coordinator.snapshot().residentDictationEngines)
        // Refusing publication is not a safe-close acknowledgement; Meeting still waits.
        assertEquals(1, coordinator.snapshot().pendingDictationLoads)
        val meeting = coordinator.reserveRun(TranscriptionMode.MEETING)!!
        assertFalse(meeting.ready.isDone)
        ticket.completeWithoutPublish()
        meeting.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        meeting.close()
    }

    @Test
    fun `meeting barrier remains pending while a rejected preload closes on its worker`() {
        val coordinator = coordinator()
        val ticket = coordinator.beginDictationLoad()!!
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        val meeting = coordinator.reserveRun(TranscriptionMode.MEETING)!!
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                assertNull(ticket.publish { fail("stale preload cannot publish") })
                closeEntered.countDown()
                assertTrue(releaseClose.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                ticket.completeWithoutPublish()
            } catch (t: Throwable) {
                workerFailure.set(t)
            }
        }, "stale-dictation-close")

        worker.start()
        try {
            assertTrue(closeEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(meeting.ready.isDone)
            assertEquals(1, coordinator.snapshot().pendingDictationLoads)
            releaseClose.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse(worker.isAlive)
            assertNull(workerFailure.get())
            meeting.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            releaseClose.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            meeting.close()
        }
    }

    @Test
    fun `a Dictation run can finish its in-flight load but a later run cannot steal it`() {
        val coordinator = coordinator()
        val run = coordinator.reserveRun(TranscriptionMode.DICTATION)!!
        run.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val ticket = coordinator.beginDictationLoad(run)!!
        assertNull(coordinator.reserveRun(TranscriptionMode.DICTATION))
        val registration = ticket.publish { }!!
        assertEquals(1, coordinator.snapshot().residentDictationEngines)
        run.close()
        registration.close()
    }

    @Test
    fun `a run lease from another process coordinator is not accepted even when ids match`() {
        val firstCoordinator = coordinator()
        val secondCoordinator = coordinator()
        val firstRun = firstCoordinator.reserveRun(TranscriptionMode.DICTATION)!!
        val foreignRun = secondCoordinator.reserveRun(TranscriptionMode.DICTATION)!!
        val firstTicket = firstCoordinator.beginDictationLoad(firstRun)

        assertEquals(firstRun.id, foreignRun.id)
        assertTrue(firstCoordinator.canUseDictationEngine(firstRun))
        assertFalse(firstCoordinator.canUseDictationEngine(foreignRun))
        assertNull(firstCoordinator.beginDictationLoad(foreignRun))
        checkNotNull(firstTicket).completeWithoutPublish()
        firstRun.close()
        foreignRun.close()
    }

    @Test
    fun `a ticket arriving after its run was cancelled still blocks Meeting until native close`() {
        val coordinator = coordinator()
        val dictation = coordinator.reserveRun(TranscriptionMode.DICTATION)!!
        val ticket = coordinator.beginDictationLoad(dictation)!!
        dictation.close()
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        val meeting = coordinator.reserveRun(TranscriptionMode.MEETING)!!
        assertNull(ticket.publish { fail("cancelled run ticket cannot publish") })
        assertFalse(meeting.ready.isDone)

        ticket.completeWithoutPublish()
        meeting.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        meeting.close()
    }

    @Test
    fun `poison fails pending barriers and permanently rejects all admissions`() {
        val coordinator = coordinator()
        val ticket = coordinator.beginDictationLoad()!!
        val run = coordinator.reserveRun(TranscriptionMode.MEETING)
        assertNull(run)
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        val meeting = coordinator.reserveRun(TranscriptionMode.MEETING)!!
        assertFalse(meeting.ready.isDone)

        coordinator.reportUncertainClose()
        val error = assertThrows(Exception::class.java) {
            meeting.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertTrue(error.message.orEmpty().contains(TRANSCRIPTION_MODE_UNAVAILABLE_MESSAGE))
        assertTrue(coordinator.snapshot().poisoned)
        assertFalse(coordinator.changeMode(TranscriptionMode.DICTATION))
        assertNull(coordinator.reserveRun(TranscriptionMode.MEETING))
        assertNull(coordinator.beginDictationLoad())
        assertNull(ticket.publish { fail("poisoned ticket must not publish") })
        meeting.close()
        assertTrue(coordinator.snapshot().poisoned)
    }

    @Test
    fun `listeners run outside state lock and one listener failure does not block others`() {
        val coordinator = coordinator()
        val laterListener = CountDownLatch(1)
        val reentryFinished = AtomicReference<Boolean>(false)
        val secondThreadReturnedBeforeCallback = AtomicReference<Boolean>(false)
        coordinator.subscribe {
            val otherThread = Thread {
                coordinator.snapshot()
                reentryFinished.set(true)
            }
            otherThread.start()
            secondThreadReturnedBeforeCallback.set(otherThread.joinBounded())
        }
        coordinator.subscribe {
            throw IllegalStateException("listener-local failure")
        }
        coordinator.subscribe { laterListener.countDown() }

        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        assertTrue(laterListener.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(true, reentryFinished.get())
        assertEquals(true, secondThreadReturnedBeforeCallback.get())
    }

    @Test
    fun `queued listener notifications read the current snapshot even when executor order reverses`() {
        val executor = ManualExecutor()
        val coordinator = TranscriptionModeCoordinator(listenerExecutor = executor)
        val deliveries = mutableListOf<TranscriptionModeSnapshot>()
        coordinator.subscribe(deliveries::add)
        assertEquals(1, executor.size)

        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        assertTrue(coordinator.changeMode(TranscriptionMode.DICTATION))
        assertEquals(3, executor.size)
        executor.runAt(2)
        executor.runAt(1)
        executor.runAt(0)

        assertEquals(3, deliveries.size)
        val current = coordinator.snapshot()
        assertTrue(deliveries.all { it.mode == current.mode && it.generation == current.generation })
    }

    @Test
    fun `native close poison callback runs outside guard lock and reaches shared mode gate`() {
        val coordinator = coordinator()
        val guard = LocalFormatNativeCloseGuard(coordinator::reportUncertainClose)
        val observerReturned = AtomicReference<Boolean>(false)
        val observerWorkCompleted = AtomicReference<Boolean>(false)
        coordinator.subscribe { snapshot ->
            if (snapshot.poisoned) {
                val observer = Thread {
                    guard.closeConversation { fail("uncertain guard must not call native close again") }
                    observerWorkCompleted.set(true)
                }
                observer.start()
                observerReturned.set(observer.joinBounded())
            }
        }

        assertFalse(guard.closeEngine { throw IllegalStateException("private native close error") })

        assertTrue(coordinator.snapshot().poisoned)
        assertEquals(true, observerReturned.get())
        assertEquals(true, observerWorkCompleted.get())
        assertNull(coordinator.reserveRun(TranscriptionMode.DICTATION))
    }

    @Test
    fun `late ResidentEngine load is closed on its loading worker rather than published`() {
        val coordinator = coordinator()
        val resident = ResidentEngine<RecordingEngine>(coordinator)
        val enteredOpen = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val closeThread = AtomicReference<String>()
        val loadedEngine = RecordingEngine {
            closeThread.set(Thread.currentThread().name)
        }
        val result = AtomicReference<RecordingEngine?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                result.set(resident.replace("asr") {
                    enteredOpen.countDown()
                    assertTrue(releaseOpen.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    loadedEngine
                })
            } catch (t: Throwable) {
                failure.set(t)
            }
        }, "dictation-load-worker")

        worker.start()
        try {
            assertTrue(enteredOpen.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
            releaseOpen.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertNull(result.get())
            assertEquals(1, loadedEngine.closeCount)
            assertEquals("dictation-load-worker", closeThread.get())
            assertFalse(resident.isLoaded("asr"))
            val meeting = coordinator.reserveRun(TranscriptionMode.MEETING)!!
            meeting.ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            meeting.close()
        } finally {
            releaseOpen.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            resident.close()
        }
    }

    @Test
    fun `resident close failure during replacement poisons a new owner without retrying native close`() {
        val coordinator = coordinator()
        val closeCount = AtomicInteger()
        val failedEngine = RecordingEngine {
            closeCount.incrementAndGet()
            throw IllegalStateException("private native close details")
        }
        val firstOwner = ResidentEngine<RecordingEngine>(coordinator)
        assertSame(failedEngine, firstOwner.replace("asr") { failedEngine })
        val observerReturned = AtomicReference<Boolean>(false)
        val observerWorkCompleted = AtomicReference<Boolean>(false)
        coordinator.subscribe { snapshot ->
            if (snapshot.poisoned) {
                val observer = Thread {
                    try { firstOwner.close() } catch (_: IllegalStateException) { }
                    observerWorkCompleted.set(true)
                }
                observer.start()
                observerReturned.set(observer.joinBounded())
            }
        }

        var openedReplacement = false
        val closeError = assertThrows(IllegalStateException::class.java) {
            firstOwner.replace("new-asr") { openedReplacement = true; RecordingEngine {} }
        }
        assertEquals(TRANSCRIPTION_ENGINE_UNAVAILABLE_MESSAGE, closeError.message)
        assertNull(closeError.cause)
        assertFalse(openedReplacement)
        assertTrue(coordinator.snapshot().poisoned)
        assertEquals(true, observerReturned.get())
        assertEquals(true, observerWorkCompleted.get())

        val newOwner = ResidentEngine<RecordingEngine>(coordinator)
        var opened = false
        val openError = assertThrows(IllegalStateException::class.java) {
            newOwner.replace("asr") { opened = true; RecordingEngine {} }
        }
        assertEquals(TRANSCRIPTION_ENGINE_UNAVAILABLE_MESSAGE, openError.message)
        assertFalse(opened)
        assertThrows(IllegalStateException::class.java) { firstOwner.close() }
        assertEquals(1, closeCount.get())
        assertNull(coordinator.reserveRun(TranscriptionMode.DICTATION))
    }

    @Test
    fun `mode admission and run reservation serialize against each other`() {
        val coordinator = coordinator()
        val start = CountDownLatch(1)
        val runResult = AtomicReference<TranscriptionRunLease?>()
        val modeResult = AtomicReference<Boolean>()
        val runThread = Thread { start.await(); runResult.set(coordinator.reserveRun(TranscriptionMode.DICTATION)) }
        val modeThread = Thread { start.await(); modeResult.set(coordinator.changeMode(TranscriptionMode.MEETING)) }
        runThread.start()
        modeThread.start()
        start.countDown()
        runThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        modeThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        assertFalse(runThread.isAlive)
        assertFalse(modeThread.isAlive)

        val run = runResult.get()
        if (run != null) {
            assertFalse(modeResult.get())
            run.close()
        } else {
            assertTrue(modeResult.get())
            val meeting = coordinator.reserveRun(TranscriptionMode.MEETING)!!
            meeting.close()
        }
    }

    private fun coordinator() = TranscriptionModeCoordinator(
        initialMode = TranscriptionMode.DICTATION,
        listenerExecutor = Executor { command -> command.run() },
    )

    private fun memoryPreferences(values: MutableMap<String, Any?>): SharedPreferences {
        fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean", "putInt" -> {
                        pending[args[0] as String] = args[1]
                        proxy
                    }
                    "remove" -> { pending[args[0] as String] = null; proxy }
                    "apply", "commit" -> {
                        pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        if (method.name == "commit") true else null
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences.Editor
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] as? String ?: args.getOrNull(1) as? String
                "getBoolean" -> values[args[0] as String] as? Boolean ?: args[1] as Boolean
                "getInt" -> values[args[0] as String] as? Int ?: args[1] as Int
                "contains" -> values.containsKey(args[0] as String)
                "edit" -> editor()
                "getAll" -> HashMap(values)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }

    private class RecordingEngine(private val closeAction: () -> Unit) : Closeable {
        var closeCount = 0
            private set
        override fun close() { closeCount++; closeAction() }
    }

    private class ManualExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        val size: Int get() = tasks.size
        fun runAt(index: Int) { tasks.removeAt(index).run() }
    }

    private fun Thread.joinBounded(): Boolean {
        join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        return !isAlive
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    companion object {
        private const val TIMEOUT_SECONDS = 2L
    }
}
