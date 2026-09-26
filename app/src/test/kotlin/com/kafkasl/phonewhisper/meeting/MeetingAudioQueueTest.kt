package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class MeetingAudioQueueTest {
    @Test
    fun `offer copies only requested bytes and preserves fifo at exact capacity`() {
        val queue = MeetingAudioQueue(capacityBytes = 6)
        val first = byteArrayOf(1, 2, 90)
        val second = byteArrayOf(3, 4, 5, 6, 91)
        val rejected = byteArrayOf(7, 8, 92)

        assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(first, 2))
        first.fill(40)
        assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(second, 4))
        assertEquals(6, queue.queuedBytes)

        assertEquals(MeetingAudioQueue.OfferResult.FULL, queue.offer(rejected, 2))
        assertArrayEquals(byteArrayOf(7, 8, 92), rejected)
        assertEquals(6, queue.queuedBytes)

        assertArrayEquals(byteArrayOf(1, 2), queue.take())
        assertEquals(4, queue.queuedBytes)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), queue.take())
        assertEquals(0, queue.queuedBytes)
    }

    @Test
    fun `default capacity holds exactly ten seconds of pcm16 mono at sixteen kilohertz`() {
        val queue = MeetingAudioQueue()
        val tenSeconds = ByteArray(320_000)

        assertEquals(128L * 1024L * 1024L, MeetingAudioQueue.MAX_SPOOL_BYTES)
        assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(tenSeconds, tenSeconds.size))
        assertEquals(320_000, queue.queuedBytes)
        assertEquals(MeetingAudioQueue.OfferResult.FULL, queue.offer(byteArrayOf(1, 2), 2))
        assertEquals(320_000, queue.queuedBytes)
    }

    @Test
    fun `zero length offer does not allocate a queued block and closed input rejects it`() {
        val queue = MeetingAudioQueue(capacityBytes = 4)
        val empty = byteArrayOf()

        assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(empty, 0))
        assertEquals(0, queue.queuedBytes)
        queue.finishInput()
        assertEquals(MeetingAudioQueue.OfferResult.CLOSED, queue.offer(empty, 0))
        assertNull(queue.take())
    }

    @Test
    fun `finish input is idempotent and drains accepted blocks before returning null`() {
        val queue = MeetingAudioQueue(capacityBytes = 8)
        assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(byteArrayOf(1, 2), 2))
        assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(byteArrayOf(3, 4), 2))

        queue.finishInput()
        queue.finishInput()
        assertFalse(queue.isCancelled)
        assertEquals(MeetingAudioQueue.OfferResult.CLOSED, queue.offer(byteArrayOf(5, 6), 2))
        assertArrayEquals(byteArrayOf(1, 2), queue.take())
        assertArrayEquals(byteArrayOf(3, 4), queue.take())
        assertEquals(0, queue.queuedBytes)
        assertNull(queue.take())
        assertNull(queue.take())
    }

    @Test
    fun `finish input returns without waiting for queue storage work`() {
        val queue = MeetingAudioQueue(capacityBytes = 4)
        val queueLock = MeetingAudioQueue::class.java.getDeclaredField("lock").apply { isAccessible = true }
            .get(queue) as ReentrantLock
        queueLock.lock()
        val finishReturned = CountDownLatch(1)
        val finisher = Thread {
            queue.finishInput()
            finishReturned.countDown()
        }.apply { isDaemon = true }
        var finishedPromptly = false

        try {
            finisher.start()
            finishedPromptly = finishReturned.await(250, TimeUnit.MILLISECONDS)
        } finally {
            queueLock.unlock()
            finisher.join(TimeUnit.SECONDS.toMillis(1))
        }

        assertTrue("finish input must not wait on disk-backed queue work", finishedPromptly)
        assertEquals(MeetingAudioQueue.OfferResult.CLOSED, queue.offer(byteArrayOf(1, 2), 2))
        assertNull(queue.take())
        queue.close()
    }

    @Test
    fun `cancel wakes a blocked consumer and discards queued blocks even after finish`() {
        val waitingQueue = MeetingAudioQueue(capacityBytes = 8)
        val enteredTake = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Throwable?>()
        val consumer = Thread {
            enteredTake.countDown()
            try {
                result.set(waitingQueue.take())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        consumer.isDaemon = true
        consumer.start()

        try {
            assertTrue("consumer did not start", enteredTake.await(1, TimeUnit.SECONDS))
            assertTrue("consumer did not block in take", awaitWaiting(consumer, 1, TimeUnit.SECONDS))
            waitingQueue.cancel()
            waitingQueue.cancel()
            consumer.join(TimeUnit.SECONDS.toMillis(1))
            assertFalse("consumer remained blocked after cancel", consumer.isAlive)
            assertNull(failure.get())
            assertNull(result.get())
        } finally {
            waitingQueue.cancel()
            consumer.interrupt()
            consumer.join(TimeUnit.SECONDS.toMillis(1))
        }

        val finishedQueue = MeetingAudioQueue(capacityBytes = 4)
        assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, finishedQueue.offer(byteArrayOf(9, 8), 2))
        finishedQueue.finishInput()
        finishedQueue.cancel()
        assertTrue(finishedQueue.isCancelled)
        assertEquals(0, finishedQueue.queuedBytes)
        assertNull(finishedQueue.take())
        finishedQueue.cancel()
        assertEquals(0, finishedQueue.queuedBytes)
    }

    @Test
    fun `concurrent offer and cancel leave no queued bytes`() {
        val queue = MeetingAudioQueue(capacityBytes = 2_000)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val block = byteArrayOf(1, 2)

        val producer = Thread {
            try {
                ready.countDown()
                check(start.await(1, TimeUnit.SECONDS))
                repeat(1_000) {
                    if (queue.offer(block, block.size) == MeetingAudioQueue.OfferResult.CLOSED) return@Thread
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val canceller = Thread {
            try {
                ready.countDown()
                check(start.await(1, TimeUnit.SECONDS))
                queue.cancel()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        producer.isDaemon = true
        canceller.isDaemon = true
        var producerStarted = false
        var cancellerStarted = false
        try {
            producer.start()
            producerStarted = true
            canceller.start()
            cancellerStarted = true

            assertTrue("workers did not reach start barrier", ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            producer.join(TimeUnit.SECONDS.toMillis(2))
            canceller.join(TimeUnit.SECONDS.toMillis(2))

            assertFalse("producer did not finish", producer.isAlive)
            assertFalse("canceller did not finish", canceller.isAlive)
            assertNull(failure.get())
            assertTrue(queue.isCancelled)
            assertEquals(0, queue.queuedBytes)
            assertNull(queue.take())
        } finally {
            start.countDown()
            queue.cancel()
            if (producerStarted) {
                producer.interrupt()
                producer.join(TimeUnit.SECONDS.toMillis(1))
            }
            if (cancellerStarted) {
                canceller.interrupt()
                canceller.join(TimeUnit.SECONDS.toMillis(1))
            }
        }
    }

    @Test
    fun `offer rejects negative oversized and odd pcm byte lengths`() {
        val queue = MeetingAudioQueue(capacityBytes = 8)
        assertIllegalArgument { queue.offer(byteArrayOf(1, 2), -1) }
        assertIllegalArgument { queue.offer(byteArrayOf(1, 2), 3) }
        assertIllegalArgument { queue.offer(byteArrayOf(1, 2), 1) }
        assertEquals(0, queue.queuedBytes)
        assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(byteArrayOf(1, 2), 2))
    }

    @Test
    fun `disk spool preserves block fifo and drains before finished input`() {
        val root = Files.createTempDirectory("meeting-audio-spool").toFile()
        val queue = MeetingAudioQueue(
            capacityBytes = 4,
            spoolRoot = root,
            spoolCapacityBytes = 64,
            segmentTargetBytes = 12,
        )
        val blocks = listOf(
            byteArrayOf(1, 2),
            byteArrayOf(3, 4),
            byteArrayOf(5, 6),
            byteArrayOf(7, 8),
            byteArrayOf(9, 10),
        )

        try {
            blocks.forEach { assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(it, it.size)) }
            assertTrue(queue.spooledBytesQueued > 0)
            queue.finishInput()

            blocks.forEach { assertArrayEquals(it, queue.take()) }
            assertNull(queue.take())
            assertEquals(0, queue.queuedBytes)
            assertEquals(0L, queue.spooledBytesQueued)
            assertNoSessionSpools(root)
        } finally {
            queue.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `disk capacity rejects new pcm and frees backlog budget as blocks are consumed`() {
        val root = Files.createTempDirectory("meeting-audio-spool-limit").toFile()
        val queue = MeetingAudioQueue(
            capacityBytes = 2,
            spoolRoot = root,
            spoolCapacityBytes = 4,
            segmentTargetBytes = 12,
        )
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3, 4)
        val third = byteArrayOf(5, 6)

        try {
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(first, first.size))
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(second, second.size))
            assertEquals(4L, queue.spooledBytesQueued)
            assertEquals(MeetingAudioQueue.OfferResult.LIMIT_REACHED, queue.offer(third, third.size))

            assertArrayEquals(first, queue.take())
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(third, third.size))
            queue.finishInput()
            assertArrayEquals(second, queue.take())
            assertArrayEquals(third, queue.take())
            assertNull(queue.take())
            assertNoSessionSpools(root)
        } finally {
            queue.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `spool io failure is explicit and cancel close purge the session directory`() {
        val parent = Files.createTempDirectory("meeting-audio-spool-io").toFile()
        val notDirectory = File(parent, "not-a-directory").apply { writeText("block") }
        val queue = MeetingAudioQueue(
            capacityBytes = 2,
            spoolRoot = notDirectory,
            spoolCapacityBytes = 8,
        )
        try {
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(byteArrayOf(1, 2), 2))
            assertEquals(MeetingAudioQueue.OfferResult.IO_ERROR, queue.offer(byteArrayOf(3, 4), 2))
            queue.cancel()
            assertEquals(0, queue.queuedBytes)
            queue.close()
        } finally {
            queue.close()
            parent.deleteRecursively()
        }
    }

    @Test
    fun `failure to reclaim a consumed segment is reported instead of losing quota accounting`() {
        val root = Files.createTempDirectory("meeting-audio-spool-delete").toFile()
        val queue = MeetingAudioQueue(
            capacityBytes = 2,
            spoolRoot = root,
            spoolCapacityBytes = 16,
            segmentTargetBytes = 6,
        )
        val block = byteArrayOf(1, 2)
        var sessionDirectory: File? = null

        try {
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(block, block.size))
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, queue.offer(block, block.size))
            sessionDirectory = sessionDirectories(root).single()
            assertTrue(sessionDirectory!!.setWritable(false, true))

            assertThrows(MeetingAudioSpoolException::class.java) { queue.take() }
            assertEquals(MeetingAudioQueue.OfferResult.IO_ERROR, queue.offer(block, block.size))
        } finally {
            sessionDirectory?.setWritable(true, true)
            queue.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `orphan sweep skips an active locked spool and reclaims it after close`() {
        val root = Files.createTempDirectory("meeting-audio-spool-lock").toFile()
        val active = MeetingAudioQueue(capacityBytes = 2, spoolRoot = root, spoolCapacityBytes = 32)
        var second: MeetingAudioQueue? = null

        try {
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, active.offer(byteArrayOf(1, 2), 2))
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, active.offer(byteArrayOf(3, 4), 2))
            val activeDirectory = sessionDirectories(root).single()

            val other = MeetingAudioQueue(capacityBytes = 2, spoolRoot = root, spoolCapacityBytes = 32)
            second = other
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, other.offer(byteArrayOf(5, 6), 2))
            assertEquals(MeetingAudioQueue.OfferResult.ACCEPTED, other.offer(byteArrayOf(7, 8), 2))
            assertTrue(activeDirectory.exists())
            assertArrayEquals(byteArrayOf(1, 2), active.take())

            active.close()
            assertFalse(activeDirectory.exists())
        } finally {
            active.close()
            second?.close()
            root.deleteRecursively()
        }
    }

    private fun assertNoSessionSpools(root: File) {
        assertTrue(sessionDirectories(root).isEmpty())
    }

    private fun sessionDirectories(root: File): List<File> =
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("session-") }.orEmpty()

    private fun awaitWaiting(thread: Thread, timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.yield()
        }
        return thread.state == Thread.State.WAITING
    }

    private fun assertIllegalArgument(action: () -> Unit) {
        var thrown = false
        try {
            action()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("expected IllegalArgumentException", thrown)
    }
}
