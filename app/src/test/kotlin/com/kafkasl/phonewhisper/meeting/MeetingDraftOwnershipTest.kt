package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MeetingDraftOwnershipTest {
    @Test
    fun claimReadsAndCreatesWriterOnInjectedIoExecutor() {
        val executor = SingleThreadOwnershipExecutor()
        try {
            val readerThread = AtomicReference<String>()
            val factoryThread = AtomicReference<String>()
            val factory = RecordingWriterFactory(factoryThread = factoryThread)
            val ownership = MeetingDraftOwnership(
                executor = executor,
                writerFactory = factory,
                reader = MeetingDraftOwnershipReader {
                    readerThread.set(Thread.currentThread().name)
                    MeetingDocumentRead.Absent
                },
            )
            val callerThread = Thread.currentThread().name

            val claim = ownership.claim(File("/tmp/meeting-owner-io.json"))
                .future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertNotEquals(callerThread, readerThread.get())
            assertEquals(readerThread.get(), factoryThread.get())
            await(claim.relinquish(document("initial")))
        } finally {
            executor.close()
        }
    }

    @Test
    fun claimFutureContinuationCanReenterRegistryWithoutHoldingItsLock() {
        val executor = SingleThreadOwnershipExecutor()
        try {
            val ownership = ownership(executor, RecordingWriterFactory())
            val request = ownership.claim(File("/tmp/meeting-owner-reentry.json"))
            val callbackCompleted = CountDownLatch(1)
            val callbackStage = request.future.thenAccept {
                val reentrantThread = Thread {
                    ownership.claim(File("/tmp/meeting-owner-reentry-other.json")).cancel()
                    callbackCompleted.countDown()
                }
                reentrantThread.start()
                check(callbackCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "claim completion ran while the registry lock was held"
                }
            }
            val claim = request.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            callbackStage.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            await(claim.relinquish(document("completed")))
        } finally {
            executor.close()
        }
    }

    @Test
    fun clearIsFifoWithClaimsAndWaitsForThePreviousWriter() {
        val executor = SingleThreadOwnershipExecutor()
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val factory = BlockingFirstWriterFactory(writeStarted, releaseWrite)
        val clearedPaths = CopyOnWriteArrayList<String>()
        try {
            val original = document("saved before clear", sessionId = "session-clear")
            val path = File("/tmp/meeting-owner-ordered-clear.json")
            val ownership = MeetingDraftOwnership(
                executor = executor,
                writerFactory = factory,
                reader = MeetingDraftOwnershipReader { MeetingDocumentRead.Absent },
                clearer = MeetingDraftOwnershipClearer { clearedPaths += it.absolutePath },
            )
            val first = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            first.writer.updateSnapshot(original)

            val flushResult = AtomicReference<CompletableFuture<Unit>>()
            val writeThread = Thread { flushResult.set(first.writer.flush()) }
            writeThread.start()
            assertTrue("final draft write did not block", writeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val earlierClaimRequest = ownership.claim(path)
            val clearing = ownership.clear(path, original.sessionId)
            val laterClaimRequest = ownership.claim(path)
            executor.awaitIdle()
            assertFalse(earlierClaimRequest.future.isDone)
            assertFalse(clearing.isDone)
            assertFalse(laterClaimRequest.future.isDone)
            assertTrue(clearedPaths.isEmpty())

            val relinquishing = first.relinquish(original)
            assertFalse(relinquishing.isDone)
            releaseWrite.countDown()
            writeThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse("final draft write did not finish", writeThread.isAlive)
            await(requireNotNull(flushResult.get()))
            await(relinquishing)

            val earlierClaim = earlierClaimRequest.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(original, earlierClaim.snapshot)
            assertFalse(clearing.isDone)
            await(earlierClaim.relinquishLatest())

            await(clearing)
            assertEquals(listOf(path.absolutePath), clearedPaths.toList())
            val laterClaim = laterClaimRequest.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(MeetingDocumentRead.Absent, laterClaim.recoveredDocument)
            assertEquals(null, laterClaim.snapshot)
            await(laterClaim.relinquishLatest())
        } finally {
            releaseWrite.countDown()
            factory.closeAll()
            executor.close()
        }
    }

    @Test
    fun clearRefusesDifferentSessionsAndUnknownDocumentsWithoutDeleting() {
        val cases = listOf(
            MeetingDocumentRead.Ready(document("other session", sessionId = "not-the-requested-session")),
            MeetingDocumentRead.Unsupported(version = 99, raw = "private unsupported payload"),
            MeetingDocumentRead.Invalid(raw = "private invalid payload"),
        )
        cases.forEachIndexed { index, recovered ->
            val executor = SingleThreadOwnershipExecutor()
            val clearCalls = AtomicInteger()
            try {
                val path = File("/tmp/meeting-owner-clear-refusal-$index.json")
                val ownership = MeetingDraftOwnership(
                    executor = executor,
                    writerFactory = RecordingWriterFactory(),
                    reader = MeetingDraftOwnershipReader { recovered },
                    clearer = MeetingDraftOwnershipClearer { clearCalls.incrementAndGet() },
                )

                expectExceptional(ownership.clear(path, "requested-session"))
                assertEquals(0, clearCalls.get())

                val claim = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertEquals(recovered, claim.recoveredDocument)
                if (recovered is MeetingDocumentRead.Ready) {
                    assertEquals(recovered.document, claim.snapshot)
                } else {
                    assertEquals(null, claim.snapshot)
                }
                await(claim.relinquishLatest())
            } finally {
                executor.close()
            }
        }
    }

    @Test
    fun clearOfAnAbsentDraftSucceedsWithoutTouchingStorage() {
        val executor = SingleThreadOwnershipExecutor()
        val clearCalls = AtomicInteger()
        try {
            val path = File("/tmp/meeting-owner-clear-absent.json")
            val ownership = MeetingDraftOwnership(
                executor = executor,
                writerFactory = RecordingWriterFactory(),
                reader = MeetingDraftOwnershipReader { MeetingDocumentRead.Absent },
                clearer = MeetingDraftOwnershipClearer { clearCalls.incrementAndGet() },
            )

            await(ownership.clear(path, "session-with-no-file"))

            assertEquals(0, clearCalls.get())
            val claim = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(MeetingDocumentRead.Absent, claim.recoveredDocument)
            assertEquals(null, claim.snapshot)
            await(claim.relinquishLatest())
        } finally {
            executor.close()
        }
    }

    @Test
    fun failedClearRetainsTheSnapshotAndAllowsAnExplicitRetry() {
        val executor = SingleThreadOwnershipExecutor()
        val clearCalls = AtomicInteger()
        try {
            val saved = document("recoverable text", sessionId = "retry-clear-session")
            val path = File("/tmp/meeting-owner-clear-retry.json")
            val ownership = MeetingDraftOwnership(
                executor = executor,
                writerFactory = RecordingWriterFactory(),
                reader = MeetingDraftOwnershipReader { MeetingDocumentRead.Ready(saved) },
                clearer = MeetingDraftOwnershipClearer {
                    if (clearCalls.incrementAndGet() == 1) throw IllegalStateException("private file detail")
                },
            )

            expectExceptional(ownership.clear(path, saved.sessionId))
            val retained = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(saved, retained.snapshot)
            assertEquals(MeetingDocumentRead.Ready(saved), retained.recoveredDocument)
            assertTrue("failed clear should remain visible", !retained.saveError.isNullOrBlank())
            await(retained.relinquishLatest())

            await(ownership.clear(path, saved.sessionId))
            assertEquals(2, clearCalls.get())
            val afterClear = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(MeetingDocumentRead.Absent, afterClear.recoveredDocument)
            assertEquals(null, afterClear.snapshot)
            assertEquals(null, afterClear.saveError)
            await(afterClear.relinquishLatest())
        } finally {
            executor.close()
        }
    }

    @Test
    fun clearFutureCompletesOutsideTheRegistryLock() {
        val executor = SingleThreadOwnershipExecutor()
        try {
            val ownership = ownership(executor, RecordingWriterFactory())
            val callbackThreadCompleted = CountDownLatch(1)
            val callbackCouldReenter = AtomicReference(false)
            val clearing = ownership.clear(File("/tmp/meeting-owner-clear-callback.json"), "absent-session")
            val callback = clearing.thenRun {
                val otherThread = Thread {
                    ownership.claim(File("/tmp/meeting-owner-clear-callback-other.json")).cancel()
                    callbackThreadCompleted.countDown()
                }
                otherThread.start()
                callbackCouldReenter.set(callbackThreadCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                otherThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            }

            await(clearing)
            callback.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(callbackCouldReenter.get())
        } finally {
            executor.close()
        }
    }

    @Test
    fun secondClaimWaitsUntilFirstWriterHasClosedThenReceivesLatestSnapshot() {
        val executor = SingleThreadOwnershipExecutor()
        try {
            val factory = RecordingWriterFactory()
            val ownership = ownership(executor, factory)
            val path = File("/tmp/meeting-owner-transfer.json")
            val firstRequest = ownership.claim(path)
            val first = firstRequest.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val snapshot = document("latest snapshot")
            first.writer.updateSnapshot(snapshot)
            first.rememberSnapshot(snapshot)

            val waitingRequest = ownership.claim(path)
            assertFalse(waitingRequest.future.isDone)
            assertEquals(1, factory.created.size)

            await(first.relinquish(snapshot))
            val next = waitingRequest.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(snapshot, next.snapshot)
            assertEquals(2, factory.created.size)
            assertFalse(first.writer === next.writer)
            await(next.relinquish(snapshot))
        } finally {
            executor.close()
        }
    }

    @Test
    fun secondClaimCannotReadOrCreateWriterWhileFirstFinalWriteIsBlocked() {
        val executor = SingleThreadOwnershipExecutor()
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val factory = BlockingFirstWriterFactory(writeStarted, releaseWrite)
        try {
            val reads = AtomicReference(0)
            val ownership = MeetingDraftOwnership(
                executor = executor,
                writerFactory = factory,
                reader = MeetingDraftOwnershipReader {
                    reads.updateAndGet { it + 1 }
                    MeetingDocumentRead.Absent
                },
            )
            val path = File("/tmp/meeting-owner-blocked-close.json")
            val first = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val latest = document("blocked final state")
            first.writer.updateSnapshot(latest)

            val flushResult = AtomicReference<CompletableFuture<Unit>>()
            val writeThread = Thread { flushResult.set(first.writer.flush()) }
            writeThread.start()
            assertTrue("first write did not block", writeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val waiting = ownership.claim(path)
            executor.awaitIdle()
            assertFalse(waiting.future.isDone)
            assertEquals(1, reads.get())
            assertEquals(1, factory.created.size)

            val relinquishing = first.relinquish(latest)
            assertFalse(relinquishing.isDone)
            releaseWrite.countDown()
            writeThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse("blocked writer did not finish", writeThread.isAlive)
            await(requireNotNull(flushResult.get()))
            await(relinquishing)

            val next = waiting.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(latest, next.snapshot)
            assertEquals(1, reads.get())
            assertEquals(2, factory.created.size)
            await(next.relinquish(latest))
        } finally {
            releaseWrite.countDown()
            factory.closeAll()
            executor.close()
        }
    }

    @Test
    fun cancelledWaitingRequestCannotBecomeOrphanOwnerAheadOfNextClaim() {
        val executor = SingleThreadOwnershipExecutor()
        try {
            val factory = RecordingWriterFactory()
            val ownership = ownership(executor, factory)
            val path = File("/tmp/meeting-owner-cancel-wait.json")
            val first = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val abandoned = ownership.claim(path)
            abandoned.cancel()
            val nextRequest = ownership.claim(path)

            await(first.relinquish(document("released")))
            val next = nextRequest.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertTrue(abandoned.future.isCompletedExceptionally)
            assertEquals(2, factory.created.size)
            await(next.relinquish(document("next")))
        } finally {
            executor.close()
        }
    }

    @Test
    fun cancellingDeliveredRequestExplicitlyReleasesItsClaim() {
        val executor = SingleThreadOwnershipExecutor()
        try {
            val factory = RecordingWriterFactory()
            val ownership = ownership(executor, factory)
            val path = File("/tmp/meeting-owner-cancel-delivered.json")
            val deliveredRequest = ownership.claim(path)
            val deliveredClaim = deliveredRequest.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val waitingRequest = ownership.claim(path)

            deliveredRequest.cancel()

            val next = waitingRequest.future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(next)
            assertEquals(2, factory.created.size)
            await(next.relinquish(document("after explicit cancel")))
        } finally {
            executor.close()
        }
    }

    @Test
    fun failedTransferRetiresOldWriterAndPassesSnapshotAndErrorToEditableNewClaim() {
        val executor = SingleThreadOwnershipExecutor()
        try {
            val factory = RecordingWriterFactory(failFirstSave = true)
            val ownership = ownership(executor, factory)
            val path = File("/tmp/meeting-owner-retry.json")
            val first = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val original = document("pending final snapshot")
            val oldWriter = first.writer
            val callbackErrors = CopyOnWriteArrayList<String?>()
            first.errorRelay.attach(callbackErrors::add)
            first.writer.updateSnapshot(original)
            first.rememberSnapshot(original)

            expectExceptional(first.relinquish(original))
            val next = ownership.claim(path).future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(original, next.snapshot)
            assertNotNull(next.saveError)
            assertFalse(oldWriter === next.writer)
            assertNotNull(first.errorRelay.lastError)
            assertTrue("former owner was detached before its final close", callbackErrors.isEmpty())
            val writesBefore = factory.created.first().saved.size
            assertFailure { oldWriter.updateSnapshot(document("stale owner write")) }
            expectExceptional(oldWriter.flush())
            assertEquals(writesBefore, factory.created.first().saved.size)

            val newOwnerErrors = CopyOnWriteArrayList<String?>()
            next.errorRelay.attach(newOwnerErrors::add)
            assertTrue(newOwnerErrors.any { it != null })
            val edited = document("edited and retried")
            next.writer.updateSnapshot(edited)
            await(next.writer.flush())
            next.clearSaveError()
            assertEquals(listOf("edited and retried"), factory.created.last().saved.map(::body))
            assertEquals(null, newOwnerErrors.last())
            assertTrue(callbackErrors.isEmpty())
            await(next.relinquish(edited))
        } finally {
            executor.close()
        }
    }

    @Test
    fun oldErrorRelayIsDetachedAfterTransfer() {
        val executor = SingleThreadOwnershipExecutor()
        try {
            val factory = RecordingWriterFactory()
            val ownership = ownership(executor, factory)
            val first = ownership.claim(File("/tmp/meeting-owner-relay.json"))
                .future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val callbackErrors = CopyOnWriteArrayList<String?>()
            first.errorRelay.attach(callbackErrors::add)
            await(first.relinquish(document("saved")))
            first.errorRelay.publish("late private error")

            assertTrue(callbackErrors.none { it == "late private error" })
            val next = ownership.claim(File("/tmp/meeting-owner-relay.json"))
                .future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            await(next.relinquish(document("saved again")))
        } finally {
            executor.close()
        }
    }

    private fun ownership(
        executor: MeetingDraftOwnershipExecutor,
        factory: RecordingWriterFactory,
    ): MeetingDraftOwnership = MeetingDraftOwnership(
        executor = executor,
        writerFactory = factory,
        reader = MeetingDraftOwnershipReader { MeetingDocumentRead.Absent },
    )

    private fun document(text: String, sessionId: String = "ownership-session") = MeetingDocument(
        sessionId = sessionId,
        runId = "ownership-run",
        turns = listOf(MeetingTurn("turn", 1, 0, text.length.toLong(), text, null)),
    )

    private fun body(document: MeetingDocument): String = document.turns.single().recognizedText

    private fun await(future: CompletableFuture<Unit>): Unit {
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun expectExceptional(future: CompletableFuture<Unit>): Unit {
        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            throw AssertionError("expected final draft save failure")
        } catch (_: ExecutionException) {
            // Expected.
        }
    }

    private fun assertFailure(action: () -> Unit): Unit {
        try {
            action()
            throw AssertionError("expected retired writer to reject mutation")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    private class SingleThreadOwnershipExecutor : MeetingDraftOwnershipExecutor, AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "meeting-owner-test").apply { isDaemon = true }
        }

        override fun execute(task: () -> Unit): Unit {
            executor.execute { task() }
        }

        override fun close(): Unit {
            executor.shutdownNow()
        }

        fun awaitIdle(): Unit {
            val idle = CompletableFuture<Unit>()
            execute { idle.complete(Unit) }
            idle.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private class RecordingWriterFactory(
        private val factoryThread: AtomicReference<String>? = null,
        failFirstSave: Boolean = false,
    ) : MeetingDraftOwnershipWriterFactory {
        private var shouldFailFirstSave = failFirstSave
        val created = CopyOnWriteArrayList<WriterRecord>()

        override fun create(path: File, errorRelay: MeetingDraftErrorRelay): MeetingDraftWriter {
            factoryThread?.set(Thread.currentThread().name)
            val failThisWriter = synchronized(this) {
                shouldFailFirstSave.also { shouldFailFirstSave = false }
            }
            val saved = CopyOnWriteArrayList<MeetingDocument>()
            val scheduler = InlineOwnershipScheduler()
            val writer = MeetingDraftWriter(
                persistence = MeetingDraftPersistence { document ->
                    if (failThisWriter && saved.isEmpty()) throw IllegalStateException("private disk detail")
                    saved += document
                },
                scheduler = scheduler,
                onErrorChanged = errorRelay::publish,
            )
            created += WriterRecord(writer, scheduler, saved)
            return writer
        }
    }

    private class WriterRecord(
        val writer: MeetingDraftWriter,
        val scheduler: MeetingDraftScheduler,
        val saved: CopyOnWriteArrayList<MeetingDocument>,
    )

    private class BlockingFirstWriterFactory(
        private val writeStarted: CountDownLatch,
        private val releaseWrite: CountDownLatch,
    ) : MeetingDraftOwnershipWriterFactory {
        val created = CopyOnWriteArrayList<WriterRecord>()
        private val schedulers = CopyOnWriteArrayList<AsyncOwnershipScheduler>()

        override fun create(path: File, errorRelay: MeetingDraftErrorRelay): MeetingDraftWriter {
            val saved = CopyOnWriteArrayList<MeetingDocument>()
            val scheduler = AsyncOwnershipScheduler()
            schedulers += scheduler
            val writer = MeetingDraftWriter(
                persistence = MeetingDraftPersistence { document ->
                    writeStarted.countDown()
                    check(releaseWrite.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "blocked write was not released by its test"
                    }
                    saved += document
                },
                scheduler = scheduler,
                onErrorChanged = errorRelay::publish,
            )
            created += WriterRecord(writer, scheduler, saved)
            return writer
        }

        fun closeAll(): Unit = schedulers.forEach(AsyncOwnershipScheduler::close)
    }

    private class AsyncOwnershipScheduler : MeetingDraftScheduler, AutoCloseable {
        private val executor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "meeting-owner-writer-test").apply { isDaemon = true }
        }

        override fun schedule(delayMs: Long, task: () -> Unit): MeetingDraftScheduledTask {
            val scheduled = executor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
            return MeetingDraftScheduledTask { scheduled.cancel(false) }
        }

        override fun execute(task: () -> Unit): Unit {
            executor.execute { task() }
        }

        override fun shutdown(): Unit {
            executor.shutdown()
        }

        override fun close(): Unit {
            executor.shutdownNow()
        }
    }

    private class InlineOwnershipScheduler : MeetingDraftScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): MeetingDraftScheduledTask = MeetingDraftScheduledTask {}
        override fun execute(task: () -> Unit): Unit = task()
        override fun shutdown(): Unit = Unit
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}
