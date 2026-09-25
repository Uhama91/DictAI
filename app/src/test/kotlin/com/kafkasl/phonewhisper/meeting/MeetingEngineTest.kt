package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MeetingEngineTest {
    @Test
    fun `input before ready and finish during open do not publish or invent a final`() {
        val native = RecordingNative().apply {
            openEntered = CountDownLatch(1)
            openRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val readyCount = AtomicInteger()
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val session = engine.start("run-1", "fr", { readyCount.incrementAndGet() }, updates::add) { error("unexpected failure") }

        try {
            assertTrue(native.openEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(session.acceptPcm16(byteArrayOf(1, 2), 2))
            session.finish()
            assertFalse(session.closed.isDone)
        } finally {
            native.openRelease!!.countDown()
        }

        awaitClosed(session)
        assertEquals(0, readyCount.get())
        assertTrue(updates.isEmpty())
        assertEquals(0, native.acceptCount.get())
        assertEquals(0, native.finishCount.get())
        assertEquals(1, native.closeCount.get())
    }

    @Test
    fun `cancel during open defers close and suppresses all later callbacks`() {
        val native = RecordingNative().apply {
            openEntered = CountDownLatch(1)
            openRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val readyCount = AtomicInteger()
        val failureCount = AtomicInteger()
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val session = engine.start(
            "run-2",
            "fr",
            { readyCount.incrementAndGet() },
            updates::add,
            { failureCount.incrementAndGet() },
        )

        try {
            assertTrue(native.openEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            session.cancel()
            assertFalse(session.closed.isDone)
            assertEquals(0, native.closeCount.get())
            assertThrows(IllegalStateException::class.java) {
                engine.start("run-overlap", "fr", {}, {}, {})
            }
        } finally {
            native.openRelease!!.countDown()
        }

        awaitClosed(session)
        assertEquals(0, readyCount.get())
        assertEquals(0, failureCount.get())
        assertTrue(updates.isEmpty())
        assertEquals(1, native.closeCount.get())
        assertFalse(native.concurrentNativeCalls)
        assertEquals(1, native.callThreadIds.toSet().size)
    }

    @Test
    fun `cancel during native accept waits for worker close and drops returned updates`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val session = engine.start("run-3", "fr", ready::countDown, updates::add) { error("unexpected failure") }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            session.cancel()
            assertFalse(session.closed.isDone)
            assertEquals(0, native.closeCount.get())
        } finally {
            native.firstAcceptRelease!!.countDown()
        }

        awaitClosed(session)
        assertTrue(updates.isEmpty())
        assertEquals(0, native.finishCount.get())
        assertEquals(1, native.closeCount.get())
        assertFalse(native.concurrentNativeCalls)
        assertEquals(1, native.callThreadIds.toSet().size)
    }

    @Test
    fun `cancel returns while an admitted update callback is blocked and suppresses the next update`() {
        val native = RecordingNative().apply {
            acceptedUpdates = listOf(
                update(1, "first"),
                update(2, "second"),
            )
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val updateCount = AtomicInteger()
        val session = engine.start("run-callback-cancel", "fr", ready::countDown, {
            updateCount.incrementAndGet()
            callbackEntered.countDown()
            check(releaseCallback.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "callback latch timed out" }
        }) { error("unexpected failure") }
        val canceller = Thread {
            cancelStarted.countDown()
            session.cancel()
            cancelReturned.countDown()
        }.apply { isDaemon = true }
        var cancelWasResponsive = false

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(callbackEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            canceller.start()
            assertTrue(cancelStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            cancelWasResponsive = cancelReturned.await(CANCEL_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            assertEquals(0, native.closeCount.get())
            assertFalse(session.closed.isDone)
        } finally {
            releaseCallback.countDown()
            if (canceller.state != Thread.State.NEW) canceller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            if (!session.closed.isDone) session.cancel()
        }

        awaitClosed(session)
        assertTrue("cancel must not wait for an admitted callback", cancelWasResponsive)
        assertEquals(1, updateCount.get())
        assertEquals(1, native.closeCount.get())
    }

    @Test
    fun `finish drains accepted pcm in order and emits final once with the session run id`() {
        val native = RecordingNative()
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val session = engine.start("immutable-run", "fr", ready::countDown, updates::add) { error("unexpected failure") }

        assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
        assertTrue(session.acceptPcm16(byteArrayOf(3, 4), 2))
        val checkpoint = session.checkpoint()
        session.finish()
        session.finish()
        assertFalse(session.acceptPcm16(byteArrayOf(5, 6), 2))

        checkpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        awaitClosed(session)
        assertEquals(listOf("open", "accept:1,2", "accept:3,4", "finish", "close"), native.events.toList())
        assertEquals(1, native.finishCount.get())
        assertEquals(listOf("immutable-run", "immutable-run", "immutable-run"), updates.map { it.runId })
        assertEquals(listOf(false, false, true), updates.map { it.isFinal })
        assertEquals(1, native.closeCount.get())
    }

    @Test
    fun `checkpoint waits for in flight native accept when queue is already empty`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val session = engine.start("run-checkpoint-native", "fr", ready::countDown, {}) { error("unexpected failure") }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0L, session.queuedAudioMs)

            val checkpoint = session.checkpoint()
            assertFalse("an empty queue does not mean native processing is complete", checkpoint.isDone)

            native.firstAcceptRelease!!.countDown()
            checkpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.finish()
        } finally {
            native.firstAcceptRelease?.countDown()
            if (!session.closed.isDone) session.cancel()
        }

        awaitClosed(session)
        assertEquals(1, native.acceptCount.get())
        assertEquals(1, native.closeCount.get())
    }

    @Test
    fun `checkpoint waits for publication callback after native accept returns`() {
        val native = RecordingNative().apply { acceptedUpdates = listOf(update(1, "published")) }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val session = engine.start("run-checkpoint-callback", "fr", ready::countDown, {
            callbackEntered.countDown()
            check(releaseCallback.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "callback latch timed out" }
        }) { error("unexpected failure") }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(callbackEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0L, session.queuedAudioMs)

            val checkpoint = session.checkpoint()
            assertFalse("the callback for the captured block is still running", checkpoint.isDone)

            releaseCallback.countDown()
            checkpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.finish()
        } finally {
            releaseCallback.countDown()
            if (!session.closed.isDone) session.cancel()
        }

        awaitClosed(session)
        assertEquals(1, native.acceptCount.get())
    }

    @Test
    fun `checkpoint snapshots accepted blocks and later audio belongs to the next barrier`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
            secondAcceptEntered = CountDownLatch(1)
            secondAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val session = engine.start("run-checkpoint-snapshots", "fr", ready::countDown, {}) { error("unexpected failure") }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val firstCheckpoint = session.checkpoint()
            val sameTargetCheckpoint = session.checkpoint()
            assertFalse(firstCheckpoint.isDone)
            assertFalse(sameTargetCheckpoint.isDone)

            assertTrue(session.acceptPcm16(byteArrayOf(3, 4), 2))
            val secondCheckpoint = session.checkpoint()
            assertFalse(secondCheckpoint.isDone)

            native.firstAcceptRelease!!.countDown()
            assertTrue(native.secondAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            firstCheckpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sameTargetCheckpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertFalse("later accepted audio must not delay the earlier barrier", secondCheckpoint.isDone)

            native.secondAcceptRelease!!.countDown()
            secondCheckpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.finish()
        } finally {
            native.firstAcceptRelease?.countDown()
            native.secondAcceptRelease?.countDown()
            if (!session.closed.isDone) session.cancel()
        }

        awaitClosed(session)
        assertEquals(2, native.acceptCount.get())
    }

    @Test
    fun `empty checkpoint resolves without producing a final and remains deterministic after finish`() {
        val native = RecordingNative()
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val session = engine.start("run-checkpoint-empty", "fr", ready::countDown, {}) { error("unexpected failure") }

        assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        session.checkpoint().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        session.finish()
        awaitClosed(session)
        session.checkpoint().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertEquals(0, native.acceptCount.get())
        assertEquals(0, native.finishCount.get())
        assertEquals(1, native.closeCount.get())
    }

    @Test
    fun `cancel fails a pending checkpoint promptly with a constant safe error`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("secret-asr-path", "secret-diar-path", native)
        val ready = CountDownLatch(1)
        val session = engine.start("run-checkpoint-cancel", "fr", ready::countDown, {}) { error("unexpected failure") }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val checkpoint = session.checkpoint()
            session.cancel()

            val failure = assertThrows(ExecutionException::class.java) {
                checkpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            assertEquals("La session de réunion n’a pas pu être traitée.", failure.cause?.message)
            assertNull(failure.cause?.cause)
        } finally {
            native.firstAcceptRelease?.countDown()
        }

        awaitClosed(session)
        assertEquals(1, native.closeCount.get())
    }

    @Test
    fun `native failure fails a pending checkpoint with a constant safe error`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("secret-asr-path", "secret-diar-path", native)
        val ready = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val session = engine.start("run-checkpoint-failure", "fr", ready::countDown, {}, failures::add)

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val checkpoint = session.checkpoint()
            native.failNextAccept.set(true)
            native.firstAcceptRelease!!.countDown()

            val failure = assertThrows(ExecutionException::class.java) {
                checkpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            assertEquals("La session de réunion n’a pas pu être traitée.", failure.cause?.message)
            assertNull(failure.cause?.cause)
        } finally {
            native.firstAcceptRelease?.countDown()
        }

        awaitClosed(session)
        assertEquals(listOf("La session de réunion n’a pas pu être traitée."), failures.toList())
        assertEquals(1, native.closeCount.get())
    }

    @Test
    fun `checkpoint completion does not hold the state lock`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val session = engine.start("run-checkpoint-unlocked", "fr", ready::countDown, {}) { error("unexpected failure") }
        val completionFinished = CountDownLatch(1)
        val cancellationReturned = AtomicBoolean(false)

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val checkpoint = session.checkpoint()
            checkpoint.thenRun {
                val cancelReturned = CountDownLatch(1)
                val canceller = Thread {
                    session.cancel()
                    cancelReturned.countDown()
                }.apply { isDaemon = true }
                canceller.start()
                cancellationReturned.set(cancelReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                canceller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                completionFinished.countDown()
            }

            native.firstAcceptRelease!!.countDown()
            assertTrue(completionFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue("checkpoint continuations must run without holding stateLock", cancellationReturned.get())
        } finally {
            native.firstAcceptRelease?.countDown()
            if (!session.closed.isDone) session.cancel()
        }

        awaitClosed(session)
    }

    @Test
    fun `saturated queue rejects new pcm without dropping accepted blocks`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native, queueCapacityBytes = 32)
        val ready = CountDownLatch(1)
        val session = engine.start("run-4", "fr", ready::countDown, {}) { error("unexpected failure") }
        val first = ByteArray(32) { 1 }
        val second = ByteArray(32) { 2 }
        val rejected = ByteArray(32) { 3 }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(first, first.size))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(second, second.size))
            assertEquals(1L, session.queuedAudioMs)
            assertFalse(session.acceptPcm16(rejected, rejected.size))
            assertEquals(1L, session.queuedAudioMs)
            session.finish()
        } finally {
            native.firstAcceptRelease!!.countDown()
        }

        awaitClosed(session)
        assertEquals(listOf(1, 2), native.acceptedBlocks.map { it.first().toInt() })
        assertEquals(1, native.finishCount.get())
    }

    @Test
    fun `open and warmup failures report safe errors and release the engine for retry`() {
        val native = RecordingNative().apply { failNextOpen.set(true) }
        val engine = MeetingEngine("secret-asr-path", "secret-diar-path", native)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val first = engine.start("run-open-fails", "fr", {}, {}, { message ->
            failures += message
            throw IllegalStateException("client callback failure")
        })

        awaitClosed(first)
        assertEquals(1, failures.size)
        assertFalse(failures.single().contains("secret"))
        assertEquals(0, native.closeCount.get())

        val ready = CountDownLatch(1)
        val second = engine.start("run-accept-fails", "fr", ready::countDown, {}, failures::add)
        assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        native.failNextAccept.set(true)
        assertTrue(second.acceptPcm16(byteArrayOf(7, 8), 2))
        awaitClosed(second)
        assertEquals(2, failures.size)
        assertFalse(failures.last().contains("secret"))
        assertEquals(1, native.closeCount.get())

        val readyAgain = CountDownLatch(1)
        val third = engine.start("run-retry", "fr", readyAgain::countDown, {}, failures::add)
        assertTrue(readyAgain.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        third.finish()
        awaitClosed(third)
        assertEquals(2, failures.size)
    }

    @Test
    fun `close failure completes exceptionally and permanently prevents another session`() {
        val native = RecordingNative().apply { failClose = true }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val session = engine.start("run-close-fails", "fr", ready::countDown, {}, failures::add)

        assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        session.finish()
        val closeError = assertThrows(ExecutionException::class.java) {
            session.closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(1, native.closeCount.get())
        assertEquals(1, failures.size)
        assertFalse(failures.single().contains("asr-path"))
        assertEquals("La session de réunion n’a pas pu être libérée.", closeError.cause?.message)
        assertNull(closeError.cause?.cause)
        assertThrows(IllegalStateException::class.java) {
            engine.start("run-after-close-failure", "fr", {}, {}, {})
        }
    }

    @Test
    fun `pcm input validation rejects malformed blocks and accepts only bounded even samples`() {
        val native = RecordingNative()
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val session = engine.start("run-pcm-validation", "fr", ready::countDown, {}) { error("unexpected failure") }

        assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(session.acceptPcm16(byteArrayOf(), 0))
        assertFalse(session.acceptPcm16(byteArrayOf(1), 1))
        assertFalse(session.acceptPcm16(byteArrayOf(1, 2), 4))
        assertFalse(session.acceptPcm16(ByteArray(320_002), 320_002))
        assertTrue(session.acceptPcm16(byteArrayOf(3, 4), 2))
        session.finish()

        awaitClosed(session)
        assertEquals(1, native.acceptCount.get())
        assertEquals(1, native.finishCount.get())
    }

    private fun awaitClosed(session: MeetingSession) {
        session.closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private class RecordingNative : MeetingNativeBridge {
        val openCount = AtomicInteger()
        val acceptCount = AtomicInteger()
        val finishCount = AtomicInteger()
        val closeCount = AtomicInteger()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val acceptedBlocks = Collections.synchronizedList(mutableListOf<List<Byte>>())
        val callThreadIds = Collections.synchronizedList(mutableListOf<Long>())
        val failNextOpen = AtomicBoolean()
        val failNextAccept = AtomicBoolean()
        @Volatile var failClose = false
        @Volatile var acceptedUpdates: List<MeetingNativeUpdate>? = null
        @Volatile var concurrentNativeCalls = false
        @Volatile var openEntered: CountDownLatch? = null
        @Volatile var openRelease: CountDownLatch? = null
        @Volatile var firstAcceptEntered: CountDownLatch? = null
        @Volatile var firstAcceptRelease: CountDownLatch? = null
        @Volatile var secondAcceptEntered: CountDownLatch? = null
        @Volatile var secondAcceptRelease: CountDownLatch? = null
        private val activeCalls = AtomicInteger()

        override fun open(asrPath: String, diarPath: String, language: String): Long = call("open") {
            openCount.incrementAndGet()
            openEntered?.countDown()
            awaitRelease(openRelease)
            if (failNextOpen.compareAndSet(true, false)) throw IllegalStateException("native path and text")
            41L
        }

        override fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate> {
            val number = acceptCount.incrementAndGet()
            val bytes = buffer.copyOf(length)
            return call("accept:${bytes.joinToString(",")}") {
                acceptedBlocks += bytes.toList()
                if (number == 1) {
                    firstAcceptEntered?.countDown()
                    awaitRelease(firstAcceptRelease)
                } else if (number == 2) {
                    secondAcceptEntered?.countDown()
                    awaitRelease(secondAcceptRelease)
                }
                if (failNextAccept.compareAndSet(true, false)) throw IllegalStateException("native transcript")
                acceptedUpdates ?: listOf(update(number.toLong(), "partial-$number", isFinal = false))
            }
        }

        override fun finish(handle: Long): List<MeetingNativeUpdate> = call("finish") {
            finishCount.incrementAndGet()
            listOf(update(99, "final", isFinal = true))
        }

        override fun close(handle: Long) {
            call("close") {
                closeCount.incrementAndGet()
                if (failClose) throw IllegalStateException("native release")
            }
        }

        private inline fun <T> call(event: String, operation: () -> T): T {
            if (activeCalls.incrementAndGet() > 1) concurrentNativeCalls = true
            callThreadIds += Thread.currentThread().id
            events += event
            return try {
                operation()
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        private fun awaitRelease(latch: CountDownLatch?) {
            if (latch != null) check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "test latch timed out" }
        }

        fun update(revision: Long, transcript: String, isFinal: Boolean = false) = MeetingNativeUpdate(
            utteranceId = 1,
            revision = revision,
            words = emptyList(),
            transcript = transcript,
            isFinal = isFinal,
            stableSpeakerThroughMs = 0,
            audioProcessedMs = 0,
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
        const val CANCEL_CALLBACK_TIMEOUT_MILLIS = 250L
    }
}
