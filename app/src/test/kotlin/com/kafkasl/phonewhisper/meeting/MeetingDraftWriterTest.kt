package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class MeetingDraftWriterTest {
    @Test
    fun `continuous mutations keep first deadline and save latest snapshot`() {
        val scheduler = ManualScheduler()
        val storage = RecordingStorage()
        val writer = MeetingDraftWriter(storage, scheduler)

        writer.updateSnapshot(document("A"))
        scheduler.advanceBy(400)
        writer.updateSnapshot(document("B"))
        scheduler.advanceBy(99)
        assertTrue(storage.saved.isEmpty())

        scheduler.advanceBy(1)

        assertEquals(listOf("B"), storage.saved.map(::body))
        await(writer.flush())
    }

    @Test
    fun `flush waits for latest generation after an active write and coalesces pending snapshots`() {
        val scheduler = ManualScheduler()
        val storage = RecordingStorage(blockFirstWrite = true)
        val writer = MeetingDraftWriter(storage, scheduler)
        writer.updateSnapshot(document("A"))
        val firstFlush = writer.flush()
        val activeWrite = Thread { scheduler.runNextImmediate() }
        activeWrite.start()
        assertTrue("first write did not start", storage.firstWriteStarted.await(2, TimeUnit.SECONDS))

        writer.updateSnapshot(document("B"))
        writer.updateSnapshot(document("C"))
        val latestFlush = writer.flush()
        assertFalse(latestFlush.isDone)
        storage.releaseFirstWrite.countDown()
        activeWrite.join(2_000)
        assertFalse("storage task did not finish", activeWrite.isAlive)
        scheduler.runImmediateTasks()

        await(firstFlush)
        await(latestFlush)
        assertEquals(listOf("A", "C"), storage.saved.map(::body))
    }

    @Test
    fun `persistent failure does not retry automatically and explicit flush retries retained snapshot`() {
        val scheduler = ManualScheduler()
        val storage = RecordingStorage(failWrites = true)
        val errors = CopyOnWriteArrayList<String?>()
        val writer = MeetingDraftWriter(storage, scheduler, onErrorChanged = { message ->
            errors += message
            if (message != null) throw IllegalStateException("callback failure is isolated")
        })
        writer.updateSnapshot(document("retained"))
        scheduler.advanceBy(500)
        assertEquals(1, storage.attempts)
        assertEquals(1, errors.size)
        assertTrue(requireNotNull(errors.last()).isNotBlank())
        assertFalse(requireNotNull(errors.last()).contains("synthetic storage failure"))

        scheduler.advanceBy(5_000)

        assertEquals("failure must not create a retry loop", 1, storage.attempts)
        val explicitFailure = writer.flush()
        scheduler.runImmediateTasks()
        expectExceptional(explicitFailure)
        assertEquals(2, storage.attempts)

        storage.failWrites = false
        val retry = writer.flush()
        scheduler.runImmediateTasks()
        await(retry)
        assertEquals("retained", body(storage.saved.single()))
        assertEquals(null, errors.last())
    }

    @Test
    fun `failed close rejects mutations retains snapshot and retries only on explicit close`() {
        val scheduler = ManualScheduler()
        val storage = RecordingStorage(failWrites = true)
        val writer = MeetingDraftWriter(storage, scheduler)
        writer.updateSnapshot(document("last edit"))

        val firstClose = writer.close()
        scheduler.runImmediateTasks()
        expectExceptional(firstClose)
        assertEquals(1, storage.attempts)
        assertFalse(scheduler.wasShutdown)
        assertIllegalState { writer.updateSnapshot(document("must be rejected")) }

        storage.failWrites = false
        val retryClose = writer.close()
        scheduler.runImmediateTasks()

        await(retryClose)
        assertEquals(listOf("last edit"), storage.saved.map(::body))
        assertTrue(scheduler.wasShutdown)
        assertEquals(2, storage.attempts)
    }

    @Test
    fun `retirement after failed close shuts worker and permanently rejects old owner`() {
        val scheduler = ManualScheduler()
        val storage = RecordingStorage(failWrites = true)
        val writer = MeetingDraftWriter(storage, scheduler)
        writer.updateSnapshot(document("snapshot retained by ownership"))

        val close = writer.close()
        scheduler.runImmediateTasks()
        expectExceptional(close)
        assertFalse(scheduler.wasShutdown)

        writer.retireAfterCloseFailure()

        assertTrue(scheduler.wasShutdown)
        assertIllegalState { writer.updateSnapshot(document("stale owner must not write")) }
        expectExceptional(writer.flush())
        expectExceptional(writer.close())
        scheduler.runImmediateTasks()
        assertEquals(1, storage.attempts)
        assertTrue(storage.saved.isEmpty())
    }

    @Test
    fun `retirement is rejected before failed close or while final write is active`() {
        val scheduler = ManualScheduler()
        val storage = RecordingStorage(failWrites = true, blockFirstWrite = true)
        val writer = MeetingDraftWriter(storage, scheduler)

        assertIllegalState { writer.retireAfterCloseFailure() }
        writer.updateSnapshot(document("snapshot"))
        val closing = writer.close()
        val writeThread = Thread { scheduler.runNextImmediate() }
        writeThread.start()
        assertTrue("final write did not start", storage.firstWriteStarted.await(2, TimeUnit.SECONDS))
        assertIllegalState { writer.retireAfterCloseFailure() }

        storage.releaseFirstWrite.countDown()
        writeThread.join(2_000)
        assertFalse("final write did not finish", writeThread.isAlive)
        scheduler.runImmediateTasks()
        expectExceptional(closing)
        writer.retireAfterCloseFailure()
        assertTrue(scheduler.wasShutdown)
        assertEquals(1, storage.attempts)
    }

    @Test
    fun `close during a blocked write flushes captured state before releasing worker`() {
        val scheduler = ManualScheduler()
        val storage = RecordingStorage(blockFirstWrite = true)
        val writer = MeetingDraftWriter(storage, scheduler)
        writer.updateSnapshot(document("final"))
        val closing = writer.close()
        val activeWrite = Thread { scheduler.runNextImmediate() }
        activeWrite.start()
        assertTrue(storage.firstWriteStarted.await(2, TimeUnit.SECONDS))
        assertFalse(closing.isDone)
        assertIllegalState { writer.updateSnapshot(document("after close request")) }

        storage.releaseFirstWrite.countDown()
        activeWrite.join(2_000)
        scheduler.runImmediateTasks()

        await(closing)
        assertEquals(listOf("final"), storage.saved.map(::body))
        assertTrue(scheduler.wasShutdown)
    }

    private fun document(text: String) = MeetingDocument(
        sessionId = "writer-session",
        runId = "writer-run",
        turns = listOf(MeetingTurn("turn", 1, 0, text.length.toLong(), text, null)),
    )

    private fun body(document: MeetingDocument) = document.turns.single().recognizedText

    private fun await(future: CompletableFuture<Unit>) {
        future.get(2, TimeUnit.SECONDS)
    }

    private fun expectExceptional(future: CompletableFuture<Unit>) {
        try {
            future.get(2, TimeUnit.SECONDS)
            throw AssertionError("expected save failure")
        } catch (_: ExecutionException) {
            // Expected.
        }
    }

    private fun assertIllegalState(action: () -> Unit) {
        try {
            action()
            throw AssertionError("expected a closing writer to reject mutations")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    private class RecordingStorage(
        @Volatile var failWrites: Boolean = false,
        blockFirstWrite: Boolean = false,
    ) : MeetingDraftPersistence {
        val saved = CopyOnWriteArrayList<MeetingDocument>()
        val firstWriteStarted = CountDownLatch(if (blockFirstWrite) 1 else 0)
        val releaseFirstWrite = CountDownLatch(if (blockFirstWrite) 1 else 0)
        @Volatile var attempts = 0
            private set

        override fun save(document: MeetingDocument) {
            val attempt = synchronized(this) { ++attempts }
            if (attempt == 1 && releaseFirstWrite.count > 0) {
                firstWriteStarted.countDown()
                check(releaseFirstWrite.await(2, TimeUnit.SECONDS)) { "test did not release blocked write" }
            }
            if (failWrites) throw IllegalStateException("synthetic storage failure")
            saved += document
        }
    }

    private class ManualScheduler : MeetingDraftScheduler {
        private data class TimedTask(
            val deadlineMs: Long,
            val block: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val immediate = ArrayDeque<() -> Unit>()
        private val timed = mutableListOf<TimedTask>()
        private var nowMs = 0L
        var wasShutdown = false
            private set

        override fun execute(task: () -> Unit) {
            synchronized(this) { immediate.addLast(task) }
        }

        override fun schedule(delayMs: Long, task: () -> Unit): MeetingDraftScheduledTask {
            val entry = synchronized(this) {
                TimedTask(nowMs + delayMs, task).also(timed::add)
            }
            return MeetingDraftScheduledTask { synchronized(this) { entry.cancelled = true } }
        }

        override fun shutdown() {
            wasShutdown = true
        }

        fun advanceBy(milliseconds: Long) {
            val due = synchronized(this) {
                nowMs += milliseconds
                val ready = timed.filter { !it.cancelled && it.deadlineMs <= nowMs }
                timed.removeAll(ready.toSet())
                ready
            }
            due.forEach { it.block() }
            runImmediateTasks()
        }

        fun runNextImmediate() {
            val task = synchronized(this) { immediate.removeFirstOrNull() }
                ?: error("no immediate task is queued")
            task()
        }

        fun runImmediateTasks() {
            while (true) {
                val task = synchronized(this) { immediate.removeFirstOrNull() } ?: return
                task()
            }
        }
    }
}
