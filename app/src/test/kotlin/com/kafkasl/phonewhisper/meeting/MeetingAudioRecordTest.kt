package com.kafkasl.phonewhisper.meeting

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MeetingAudioRecordTest {
    @Test
    fun `permission and service readiness are rechecked before recorder creation`() {
        val ready = AtomicBoolean(true)
        val factoryCalls = AtomicInteger()
        val adapter = MeetingAudioRecord(
            readinessCheck = { ready.get() },
            recorderFactory = MeetingAudioRecorderFactory { _, _ ->
                factoryCalls.incrementAndGet()
                FakeRecorder()
            },
        )
        val microphone = adapter.create()
        ready.set(false)

        assertFalse(microphone.start({ _, _ -> true }, {}))
        assertEquals(0, factoryCalls.get())
        assertTrue(microphone.stopAndJoin().isDone)
    }

    @Test
    fun `invalid recorder is released and start is refused`() {
        val recorder = FakeRecorder(initialized = false)
        val microphone = adapter(recorder).create()

        assertFalse(microphone.start({ _, _ -> true }, {}))

        assertEquals(0, recorder.startCalls.get())
        assertEquals(1, recorder.releaseCalls.get())
        microphone.stopAndJoin().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `a refused PCM block stops the reader and reports a constant failure`() {
        val recorder = FakeRecorder()
        val microphone = adapter(recorder, blockBytes = 64).create()
        val delivered = CompletableFuture<ByteArray>()
        val failure = CompletableFuture<String>()
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertTrue(microphone.start({ block, length ->
            delivered.complete(block.copyOf(length))
            false
        }, { message ->
            failure.complete(message)
            Unit
        }))
        assertTrue(recorder.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        recorder.offer(bytes)

        assertArrayEquals(bytes, delivered.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(MEETING_AUDIO_UNAVAILABLE_MESSAGE, failure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        microphone.stopAndJoin().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(1, recorder.readCalls.get())
        assertTrue(recorder.maxRequestedBytes.get() <= 64)
        assertEquals(1, recorder.releaseCalls.get())
    }

    @Test
    fun `stop is idempotent asynchronous and completes only after read exits and release`() {
        val recorder = FakeRecorder(blockFirstRead = true)
        val microphone = adapter(recorder).create()
        assertTrue(microphone.start({ _, _ -> true }, {}))
        assertTrue(recorder.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val stopped = microphone.stopAndJoin()
        val repeated = microphone.stopAndJoin()

        assertSame(stopped, repeated)
        assertTrue(recorder.stopCalled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(stopped.isDone)
        assertEquals(0, recorder.releaseCalls.get())

        recorder.allowBlockedReadToReturn.countDown()
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(recorder.readReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(recorder.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, recorder.stopCalls.get())
        assertEquals(1, recorder.releaseCalls.get())
    }

    @Test
    fun `failed stop does not release recorder until blocked read has exited`() {
        val recorder = FakeRecorder(blockFirstRead = true, failStop = true)
        val microphone = adapter(recorder).create()
        assertTrue(microphone.start({ _, _ -> true }, {}))
        assertTrue(recorder.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val stopped = microphone.stopAndJoin()
        assertTrue(recorder.stopCalled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(stopped.isDone)
        assertEquals(0, recorder.releaseCalls.get())

        recorder.allowBlockedReadToReturn.countDown()
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(recorder.readReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, recorder.releaseCalls.get())
    }

    @Test
    fun `release uncertainty fails stop future with no native detail`() {
        val recorder = FakeRecorder(blockFirstRead = true, failRelease = true)
        val microphone = adapter(recorder).create()
        assertTrue(microphone.start({ _, _ -> true }, {}))
        assertTrue(recorder.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val stopped = microphone.stopAndJoin()
        assertTrue(recorder.stopCalled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        recorder.allowBlockedReadToReturn.countDown()

        val error = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        assertEquals(MEETING_AUDIO_UNAVAILABLE_MESSAGE, error.cause?.message)
        assertTrue(error.cause?.cause == null)
    }

    @Test
    fun `stop before start permanently refuses without creating a recorder`() {
        val factoryCalls = AtomicInteger()
        val adapter = MeetingAudioRecord(
            readinessCheck = { true },
            recorderFactory = MeetingAudioRecorderFactory { _, _ ->
                factoryCalls.incrementAndGet()
                FakeRecorder()
            },
        )
        val microphone = adapter.create()
        val stopped = microphone.stopAndJoin()

        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertFalse(microphone.start({ _, _ -> true }, {}))
        assertSame(stopped, microphone.stopAndJoin())
        assertEquals(0, factoryCalls.get())
    }

    @Test
    fun `stop during recorder startup waits for rollback and starts no reader`() {
        val recorder = FakeRecorder(blockStart = true)
        val microphone = adapter(recorder).create()
        val startResult = AtomicReference<Boolean>()
        val startThread = Thread {
            startResult.set(microphone.start({ _, _ -> true }, {}))
        }
        startThread.start()
        assertTrue(recorder.startEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val stopped = microphone.stopAndJoin()
        assertFalse(stopped.isDone)
        assertEquals(0, recorder.readCalls.get())

        recorder.allowStartToReturn.countDown()
        startThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        assertFalse(startThread.isAlive)
        assertFalse(startResult.get())
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertEquals(0, recorder.readCalls.get())
        assertEquals(1, recorder.releaseCalls.get())
    }

    private fun adapter(
        recorder: FakeRecorder,
        blockBytes: Int = 128,
    ) = MeetingAudioRecord(
        readinessCheck = { true },
        recorderFactory = MeetingAudioRecorderFactory { _, _ -> recorder },
        blockBytes = blockBytes,
    )

    private class FakeRecorder(
        override val initialized: Boolean = true,
        private val blockFirstRead: Boolean = false,
        private val failRelease: Boolean = false,
        private val blockStart: Boolean = false,
        private val failStop: Boolean = false,
    ) : MeetingAudioRecorder {
        private val recordingState = AtomicBoolean(false)
        override val recording: Boolean get() = recordingState.get()
        val startCalls = AtomicInteger()
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        val readCalls = AtomicInteger()
        val maxRequestedBytes = AtomicInteger()
        val startEntered = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val readReturned = CountDownLatch(1)
        val stopCalled = CountDownLatch(1)
        val released = CountDownLatch(1)
        val allowStartToReturn = CountDownLatch(1)
        val allowBlockedReadToReturn = CountDownLatch(1)
        private val nextRead = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

        override fun startRecording() {
            startCalls.incrementAndGet()
            startEntered.countDown()
            if (blockStart) allowStartToReturn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            recordingState.set(true)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls.incrementAndGet()
            maxRequestedBytes.updateAndGet { old -> maxOf(old, length) }
            readEntered.countDown()
            if (blockFirstRead && readCalls.get() == 1) {
                allowBlockedReadToReturn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                readReturned.countDown()
                return 0
            }
            val bytes = nextRead.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (bytes == null) {
                readReturned.countDown()
                return 0
            }
            bytes.copyInto(buffer, offset, 0, minOf(bytes.size, length))
            readReturned.countDown()
            return minOf(bytes.size, length)
        }

        fun offer(bytes: ByteArray) {
            nextRead.offer(bytes)
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            stopCalled.countDown()
            if (failStop) throw IllegalStateException("private recorder stop failure")
            recordingState.set(false)
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            recordingState.set(false)
            released.countDown()
            if (failRelease) throw IllegalStateException("private recorder failure")
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}
