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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class MeetingEngineTest {
    @Test
    fun `voice progress reads a changing bridge cache while accept is blocked and keeps it after close`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val session = engine.start("voice-progress", "fr", ready::countDown, {}) { error("unexpected failure") }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val active = MeetingVoiceProgress(MeetingVoiceState.ACTIVE, pendingAudioMs = 240L)
            native.voiceProgressSnapshot = active
            val readsBeforeGetter = native.voiceProgressReadCount.get()
            assertEquals(active, session.voiceProgress)

            assertEquals(readsBeforeGetter + 1, native.voiceProgressReadCount.get())
            native.firstAcceptRelease!!.countDown()
            session.checkpoint().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.finish()
            awaitClosed(session)
            val readsAfterClose = native.voiceProgressReadCount.get()
            assertEquals(active, session.voiceProgress)
            assertEquals(readsAfterClose, native.voiceProgressReadCount.get())
        } finally {
            native.firstAcceptRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `uncertain cleanup reported while opening poisons the engine lease`() {
        val native = CleanupUncertainOpenNative()
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val failures = AtomicInteger()
        val session = engine.start("uncertain-open", "fr", {}, {}, { failures.incrementAndGet() })

        val closeFailure = assertThrows(ExecutionException::class.java) {
            session.closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertTrue(closeFailure.cause is IllegalStateException)
        assertEquals(1, failures.get())
        assertEquals(0, native.closeCount.get())
        assertThrows(IllegalStateException::class.java) {
            engine.start("must-not-reuse", "fr", {}, {}, {})
        }
    }

    @Test
    fun `hybrid worker setup cleanup failure after Handy acquisition poisons the engine`() {
        val workerFailure = IllegalStateException("diar worker setup failed")
        val handyCloseFailure = IllegalStateException("Handy close failed")
        val closeCount = AtomicInteger()
        val handy = object : HandyAsrPort {
            override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) = Unit
            override fun snapshot(firstTokenIndex: Int, maxTokens: Int) = emptyHandyWindow()
            override fun finish(firstTokenIndex: Int, maxTokens: Int) = emptyHandyWindow()
            override fun close() {
                closeCount.incrementAndGet()
                throw handyCloseFailure
            }
        }
        val native = HandyMeetingNativeBridge(
            handyFactory = { _, _ -> handy },
            diarizationFactory = { throw AssertionError("worker must fail before diarization open") },
            setDiarWorkerBackgroundPriority = {},
            workerFactory = { _, _ -> throw workerFailure },
        )
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val session = engine.start("hybrid-cleanup-poison", "fr", {}, {}, {})

        assertThrows(ExecutionException::class.java) {
            session.closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(1, closeCount.get())
        assertThrows(IllegalStateException::class.java) {
            engine.start("hybrid-cleanup-must-not-reuse", "fr", {}, {}, {})
        }
    }

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
    fun `listener is installed before ready and publishes while accept is blocked`() {
        val native = AsyncListenerNative().apply {
            acceptEntered = CountDownLatch(1)
            acceptRelease = CountDownLatch(1)
        }
        val events = native.events
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val updateReceived = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val session = MeetingEngine("asr-path", "diar-path", native).start(
            "listener-during-accept",
            "fr",
            {
                events += "ready"
                ready.countDown()
            },
            {
                updates += it
                updateReceived.countDown()
            },
            { error("unexpected failure") },
        )

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(events.indexOf("listener:add") < events.indexOf("ready"))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.acceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            native.emit(native.update(1, "texte rapide"))

            assertTrue(updateReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("texte rapide", updates.single().transcript)
            native.acceptRelease!!.countDown()
            session.checkpoint().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.finish()
            awaitClosed(session)
        } finally {
            native.acceptRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `listener publishes final and later diarization revisions while finish is blocked`() {
        val native = AsyncListenerNative().apply {
            finishEntered = CountDownLatch(1)
            finishRelease = CountDownLatch(1)
        }
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val bothUpdatesReceived = CountDownLatch(2)
        val ready = CountDownLatch(1)
        val session = MeetingEngine("asr-path", "diar-path", native).start(
            "listener-during-finish",
            "fr",
            ready::countDown,
            {
                updates += it
                bothUpdatesReceived.countDown()
            },
            { error("unexpected failure") },
        )

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            session.checkpoint().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.finish()
            assertTrue(native.finishEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            native.emit(native.update(revision = 7, transcript = "phrase finale", isFinal = true))
            native.emit(native.update(revision = 8, transcript = "phrase finale", isFinal = true))

            assertTrue(bothUpdatesReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf(7L, 8L), updates.map { it.revision })
            assertTrue(updates.all { it.isFinal && it.transcript == "phrase finale" })
            native.finishRelease!!.countDown()
            awaitClosed(session)
        } finally {
            native.finishRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `cancel signals native outside the worker and suppresses late listener updates`() {
        val native = AsyncListenerNative().apply {
            acceptEntered = CountDownLatch(1)
            acceptRelease = CountDownLatch(1)
            cancelEntered = CountDownLatch(1)
        }
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val ready = CountDownLatch(1)
        val session = MeetingEngine("asr-path", "diar-path", native).start(
            "listener-cancel",
            "fr",
            ready::countDown,
            updates::add,
            { error("unexpected failure") },
        )
        val cancelReturned = CountDownLatch(1)
        val cancelThread = Thread {
            session.cancel()
            cancelReturned.countDown()
        }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.acceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            cancelThread.start()

            assertTrue(cancelReturned.await(CANCEL_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(native.cancelEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, native.cancelCount.get())
            assertFalse(session.closed.isDone)
            native.emit(native.update(9, "late revision"))
            assertTrue(updates.isEmpty())

            native.acceptRelease!!.countDown()
            awaitClosed(session)
        } finally {
            native.acceptRelease!!.countDown()
            if (cancelThread.isAlive) cancelThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `cancel during open signals native as soon as the handle becomes available`() {
        val native = AsyncListenerNative().apply {
            openEntered = CountDownLatch(1)
            openRelease = CountDownLatch(1)
            cancelEntered = CountDownLatch(1)
        }
        val readyCount = AtomicInteger()
        val session = MeetingEngine("asr-path", "diar-path", native).start(
            "listener-cancel-open",
            "fr",
            { readyCount.incrementAndGet() },
            {},
            { error("unexpected failure") },
        )

        try {
            assertTrue(native.openEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            session.cancel()
            assertEquals(0, native.cancelCount.get())
            native.openRelease!!.countDown()
            assertTrue(native.cancelEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            awaitClosed(session)

            assertEquals(1, native.cancelCount.get())
            assertEquals(0, readyCount.get())
            assertTrue(native.events.indexOf("open:return") < native.events.indexOf("cancel"))
        } finally {
            native.openRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `late listener callbacks are rejected during and after native close`() {
        val native = AsyncListenerNative().apply {
            closeEntered = CountDownLatch(1)
            closeRelease = CountDownLatch(1)
        }
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val ready = CountDownLatch(1)
        val session = MeetingEngine("asr-path", "diar-path", native).start(
            "listener-close-barrier",
            "fr",
            ready::countDown,
            updates::add,
            { error("unexpected failure") },
        )
        var savedListener: ((MeetingNativeUpdate) -> Unit)? = null

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            savedListener = native.listener
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            session.checkpoint().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.finish()
            assertTrue(native.closeEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            savedListener!!.invoke(native.update(10, "during close"))
            assertTrue(updates.isEmpty())
            native.closeRelease!!.countDown()
            awaitClosed(session)
            savedListener!!.invoke(native.update(11, "after close"))
            assertTrue(updates.isEmpty())
            assertTrue(native.events.contains("listener:remove"))
        } finally {
            native.closeRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `closing waits for an active listener callback without blocking cancel`() {
        val native = AsyncListenerNative().apply {
            closeEntered = CountDownLatch(1)
            closeRelease = CountDownLatch(1)
        }
        val closingReached = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val sessionRef = AtomicReference<MeetingSession>()
        val session = MeetingEngine(
            asrPath = "asr-path",
            diarPath = "diar-path",
            native = native,
            queueCapacityBytes = 32_000,
            onSessionClosingForTest = closingReached::countDown,
        ).start(
            "listener-close-barrier-cancel",
            "fr",
            ready::countDown,
            {
                callbackEntered.countDown()
                if (closingReached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    sessionRef.get().cancel()
                    cancelReturned.countDown()
                    if (allowCallbackReturn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        callbackFinished.countDown()
                    }
                }
            },
            { error("unexpected failure") },
        )
        sessionRef.set(session)

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            session.checkpoint().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val emitter = Thread { native.emit(native.update(12, "active callback")) }
            emitter.start()
            assertTrue(callbackEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            session.finish()
            assertTrue(closingReached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(cancelReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(native.closeEntered!!.await(CANCEL_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(0, native.cancelCount.get())

            allowCallbackReturn.countDown()
            assertTrue(callbackFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            emitter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertTrue(native.closeEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            native.closeRelease!!.countDown()
            awaitClosed(session)
        } finally {
            allowCallbackReturn.countDown()
            native.closeRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `progress includes an in-flight native block and measures its monotonic wall cost`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
            finishEntered = CountDownLatch(1)
            finishRelease = CountDownLatch(1)
        }
        val monotonicNanos = AtomicLong(0L)
        val engine = MeetingEngine(
            "asr-path",
            "diar-path",
            native,
            queueCapacityBytes = 32_000,
            monotonicClockNanos = monotonicNanos::get,
        )
        val ready = CountDownLatch(1)
        val session = engine.start("progress-inflight", "fr", ready::countDown, {}) { error("unexpected failure") }
        val oneSecondPcm = ByteArray(32_000)

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(oneSecondPcm, oneSecondPcm.size))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            monotonicNanos.set(2_500_000_000L)
            val whileNativeIsBlocked = session.progress
            assertEquals(1_000L, whileNativeIsBlocked.capturedAudioMs)
            assertEquals(0L, whileNativeIsBlocked.processedAudioMs)
            assertEquals(1_000L, whileNativeIsBlocked.pendingAudioMs)
            assertEquals(0L, whileNativeIsBlocked.queuedAudioMs)
            assertEquals(1_000L, whileNativeIsBlocked.inFlightAudioMs)
            assertEquals(0L, whileNativeIsBlocked.discardedAudioMs)
            assertEquals(0L, whileNativeIsBlocked.nativeProcessingMs)
            assertEquals(2_500L, whileNativeIsBlocked.inFlightProcessingMs)
            assertEquals(null, whileNativeIsBlocked.processingCostRatio)
            assertEquals(2_500L, whileNativeIsBlocked.captureElapsedMs)

            monotonicNanos.set(3_000_000_000L)
            native.firstAcceptRelease!!.countDown()
            session.checkpoint().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val afterNativeReturns = session.progress
            assertEquals(1_000L, afterNativeReturns.processedAudioMs)
            assertEquals(0L, afterNativeReturns.pendingAudioMs)
            assertEquals(0L, afterNativeReturns.inFlightAudioMs)
            assertEquals(3_000L, afterNativeReturns.nativeProcessingMs)
            assertEquals(0L, afterNativeReturns.inFlightProcessingMs)
            assertEquals(3.0, afterNativeReturns.processingCostRatio!!, 0.001)
            session.finish()
            assertTrue(native.finishEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            monotonicNanos.set(8_000_000_000L)
            val whileFinalizationIsBlocked = session.progress
            assertEquals(3_000L, whileFinalizationIsBlocked.nativeProcessingMs)
            assertEquals(3.0, whileFinalizationIsBlocked.processingCostRatio!!, 0.001)
            assertEquals(8_000L, whileFinalizationIsBlocked.captureElapsedMs)
            native.finishRelease!!.countDown()
            awaitClosed(session)
            assertEquals(3_000L, session.progress.nativeProcessingMs)
        } finally {
            native.firstAcceptRelease!!.countDown()
            native.finishRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `progress marks queued pcm discarded on cancel while retaining native work in flight`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val monotonicNanos = AtomicLong(0L)
        val engine = MeetingEngine(
            "asr-path",
            "diar-path",
            native,
            queueCapacityBytes = 32_000,
            monotonicClockNanos = monotonicNanos::get,
        )
        val ready = CountDownLatch(1)
        val session = engine.start("progress-cancel", "fr", ready::countDown, {}) { error("unexpected failure") }
        val oneSecondPcm = ByteArray(32_000)

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(oneSecondPcm, oneSecondPcm.size))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(oneSecondPcm, oneSecondPcm.size))
            monotonicNanos.set(2_500_000_000L)

            session.cancel()
            val whileCancelledNativeCallIsBlocked = session.progress
            assertEquals(2_000L, whileCancelledNativeCallIsBlocked.capturedAudioMs)
            assertEquals(0L, whileCancelledNativeCallIsBlocked.processedAudioMs)
            assertEquals(1_000L, whileCancelledNativeCallIsBlocked.pendingAudioMs)
            assertEquals(0L, whileCancelledNativeCallIsBlocked.queuedAudioMs)
            assertEquals(1_000L, whileCancelledNativeCallIsBlocked.inFlightAudioMs)
            assertEquals(1_000L, whileCancelledNativeCallIsBlocked.discardedAudioMs)
            assertEquals(2_500L, whileCancelledNativeCallIsBlocked.inFlightProcessingMs)
            assertFalse(session.closed.isDone)

            native.firstAcceptRelease!!.countDown()
            awaitClosed(session)
            val afterClose = session.progress
            assertEquals(2_000L, afterClose.capturedAudioMs)
            assertEquals(1_000L, afterClose.processedAudioMs)
            assertEquals(0L, afterClose.pendingAudioMs)
            assertEquals(1_000L, afterClose.discardedAudioMs)
            assertEquals(2_500L, afterClose.nativeProcessingMs)
            assertEquals(2.5, afterClose.processingCostRatio!!, 0.001)
        } finally {
            native.firstAcceptRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
        }
    }

    @Test
    fun `progress remains nonnegative when admission races cancellation`() {
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native, queueCapacityBytes = 6_400)
        val ready = CountDownLatch(1)
        val session = engine.start("progress-admission-cancel-race", "fr", ready::countDown, {}) {
            error("unexpected failure")
        }
        val oneMsPcm = ByteArray(32)
        val snapshotsNonnegative = AtomicBoolean(true)
        val startRace = CountDownLatch(1)
        val producer = Thread {
            startRace.await()
            repeat(200) {
                if (!session.acceptPcm16(oneMsPcm, oneMsPcm.size)) return@Thread
                val progress = session.progress
                if (progress.capturedAudioMs < 0L || progress.processedAudioMs < 0L ||
                    progress.pendingAudioMs < 0L || progress.queuedAudioMs < 0L ||
                    progress.inFlightAudioMs < 0L || progress.discardedAudioMs < 0L
                ) {
                    snapshotsNonnegative.set(false)
                }
            }
        }
        val canceller = Thread {
            startRace.await()
            session.cancel()
        }
        val firstBlock = ByteArray(32)

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(firstBlock, firstBlock.size))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            producer.start()
            canceller.start()
            startRace.countDown()
            producer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            canceller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse("producer must leave after cancellation", producer.isAlive)
            assertFalse("cancellation must remain independent of native completion", canceller.isAlive)
            assertTrue(snapshotsNonnegative.get())

            native.firstAcceptRelease!!.countDown()
            awaitClosed(session)
            val afterClose = session.progress
            assertTrue(afterClose.capturedAudioMs >= afterClose.processedAudioMs)
            assertEquals(afterClose.capturedAudioMs - afterClose.processedAudioMs, afterClose.discardedAudioMs)
            assertEquals(0L, afterClose.pendingAudioMs)
            assertEquals(0L, afterClose.queuedAudioMs)
            assertEquals(0L, afterClose.inFlightAudioMs)
        } finally {
            startRace.countDown()
            native.firstAcceptRelease!!.countDown()
            if (!session.closed.isDone) session.cancel()
            if (producer.state != Thread.State.NEW) producer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            if (canceller.state != Thread.State.NEW) canceller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }
    }

    @Test
    fun `cancel returns while an admitted pcm offer is blocked on queue storage`() {
        val spoolRoot = Files.createTempDirectory("meeting-engine-cancel-blocked-offer").toFile()
        val native = RecordingNative()
        val engine = MeetingEngine(
            "asr-path",
            "diar-path",
            native,
            queueCapacityBytes = 2,
            spoolRoot = spoolRoot,
            spoolCapacityBytes = 8,
        )
        val ready = CountDownLatch(1)
        val session = engine.start("run-cancel-blocked-offer", "fr", ready::countDown, {}) { error("unexpected failure") }
        assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val queue = session.javaClass.getDeclaredField("queue").apply { isAccessible = true }.get(session)
        val queueLock = queue.javaClass.getDeclaredField("lock").apply { isAccessible = true }
            .get(queue) as ReentrantLock
        queueLock.lock()
        val offerStarted = CountDownLatch(1)
        val offerReturned = CountDownLatch(1)
        val offerResult = AtomicBoolean(true)
        val producer = Thread {
            offerStarted.countDown()
            offerResult.set(session.acceptPcm16(byteArrayOf(1, 2), 2))
            offerReturned.countDown()
        }.apply { isDaemon = true }
        val cancelReturned = CountDownLatch(1)
        val canceller = Thread {
            session.cancel()
            cancelReturned.countDown()
        }.apply { isDaemon = true }
        var producerStarted = false
        var cancellerStarted = false
        var cancelWasResponsive = false

        try {
            producer.start()
            producerStarted = true
            assertTrue(offerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue("audio offer did not wait behind simulated slow spool I/O", awaitWaiting(producer))
            canceller.start()
            cancellerStarted = true
            cancelWasResponsive = cancelReturned.await(CANCEL_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            assertFalse(session.closed.isDone)
        } finally {
            queueLock.unlock()
            if (producerStarted) producer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            if (cancellerStarted) canceller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }

        awaitClosed(session)
        assertTrue("cancel must not wait for an admitted queue offer", cancelWasResponsive)
        assertTrue("the admitted offer is rejected after cancellation", offerReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(offerResult.get())
        assertEquals(1, native.closeCount.get())
        assertNoSessionSpools(spoolRoot)
        spoolRoot.deleteRecursively()
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
    fun `bounded private spool accepts over ten seconds and finish drains exact pcm fifo`() {
        val spoolRoot = Files.createTempDirectory("meeting-engine-spool").toFile()
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine(
            "asr-path",
            "diar-path",
            native,
            queueCapacityBytes = 1_280,
            spoolRoot = spoolRoot,
            spoolCapacityBytes = MeetingAudioQueue.MAX_SPOOL_BYTES,
        )
        val ready = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val session = engine.start("run-long-backlog", "fr", ready::countDown, {}) { failures += it }
        val blocks = pcmBlocks(blockCount = 550)

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(blocks.first(), blocks.first().size))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            blocks.drop(1).forEach { block -> assertTrue(session.acceptPcm16(block, block.size)) }
            assertTrue("more than ten seconds remain queued behind native work", session.queuedAudioMs > 10_000L)
            session.finish()
        } finally {
            native.firstAcceptRelease!!.countDown()
        }

        awaitClosed(session)
        val acceptedBytes = native.acceptedBlocks.flatMap { it }.toByteArray()
        assertArrayEquals(blocks.flatMap { it.asIterable() }.toByteArray(), acceptedBytes)
        assertEquals(550, native.acceptCount.get())
        assertEquals(1, native.finishCount.get())
        assertTrue(failures.isEmpty())
        assertNoSessionSpools(spoolRoot)
        spoolRoot.deleteRecursively()
    }

    @Test
    fun `spool quota reports a safe limit error and cancellation still closes the native lease`() {
        val spoolRoot = Files.createTempDirectory("meeting-engine-spool-limit").toFile()
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine(
            "asr-path",
            "diar-path",
            native,
            queueCapacityBytes = 2,
            spoolRoot = spoolRoot,
            spoolCapacityBytes = 2,
        )
        val ready = CountDownLatch(1)
        val failureDelivered = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val session = engine.start("run-spool-limit", "fr", ready::countDown, {}) {
            failures += it
            failureDelivered.countDown()
        }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(3, 4), 2))
            assertFalse(session.acceptPcm16(byteArrayOf(5, 6), 2))
            assertTrue(failureDelivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf(MeetingEngine.AUDIO_SPOOL_LIMIT_ERROR), failures)
            session.cancel()
            assertFalse(session.closed.isDone)
        } finally {
            native.firstAcceptRelease!!.countDown()
        }

        awaitClosed(session)
        assertEquals(0, native.finishCount.get())
        assertEquals(1, native.closeCount.get())
        assertNoSessionSpools(spoolRoot)
        spoolRoot.deleteRecursively()
    }

    @Test
    fun `cancel clears spooled backlog after blocked native accept and rejects late updates`() {
        val spoolRoot = Files.createTempDirectory("meeting-engine-spool-cancel").toFile()
        val native = RecordingNative().apply {
            firstAcceptEntered = CountDownLatch(1)
            firstAcceptRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine(
            "asr-path",
            "diar-path",
            native,
            queueCapacityBytes = 640,
            spoolRoot = spoolRoot,
            spoolCapacityBytes = MeetingAudioQueue.MAX_SPOOL_BYTES,
        )
        val ready = CountDownLatch(1)
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val session = engine.start("run-cancel-spool", "fr", ready::countDown, updates::add) { error("unexpected failure") }
        val blocks = pcmBlocks(blockCount = 550)

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(blocks.first(), blocks.first().size))
            assertTrue(native.firstAcceptEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            blocks.drop(1).forEach { block -> assertTrue(session.acceptPcm16(block, block.size)) }
            assertTrue(session.queuedAudioMs > 10_000L)

            session.cancel()
            assertEquals(0L, session.queuedAudioMs)
            assertFalse(session.closed.isDone)
        } finally {
            native.firstAcceptRelease!!.countDown()
        }

        awaitClosed(session)
        assertEquals(1, native.acceptCount.get())
        assertEquals(0, native.finishCount.get())
        assertEquals(1, native.closeCount.get())
        assertTrue(updates.isEmpty())
        assertNoSessionSpools(spoolRoot)
        spoolRoot.deleteRecursively()
    }

    @Test
    fun `cancel during native finish suppresses its late final update`() {
        val native = RecordingNative().apply {
            finishEntered = CountDownLatch(1)
            finishRelease = CountDownLatch(1)
        }
        val engine = MeetingEngine("asr-path", "diar-path", native)
        val ready = CountDownLatch(1)
        val updates = Collections.synchronizedList(mutableListOf<MeetingHypothesis>())
        val session = engine.start("run-cancel-finish", "fr", ready::countDown, updates::add) { error("unexpected failure") }

        try {
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(session.acceptPcm16(byteArrayOf(1, 2), 2))
            session.finish()
            assertTrue(native.finishEntered!!.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val updatesBeforeCancel = updates.toList()
            assertEquals(listOf(false), updatesBeforeCancel.map { it.isFinal })
            session.cancel()
            assertFalse(session.closed.isDone)
            native.finishRelease!!.countDown()
            awaitClosed(session)
            assertEquals("cancel must reject native finish's late final", updatesBeforeCancel, updates.toList())
            assertTrue(updates.none { it.isFinal })
        } finally {
            native.finishRelease!!.countDown()
        }

        assertEquals(1, native.finishCount.get())
        assertEquals(1, native.closeCount.get())
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

    private fun awaitWaiting(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
        return thread.state == Thread.State.WAITING
    }

    private fun pcmBlocks(blockCount: Int): List<ByteArray> = List(blockCount) { blockIndex ->
        ByteArray(640).also { block ->
            repeat(block.size / 2) { sampleIndex ->
                val sample = blockIndex * (block.size / 2) + sampleIndex
                val pcm16 = (sample and 0x7FFF).toShort().toInt()
                block[sampleIndex * 2] = (pcm16 and 0xFF).toByte()
                block[sampleIndex * 2 + 1] = (pcm16 ushr 8).toByte()
            }
        }
    }

    private fun assertNoSessionSpools(root: File) {
        assertTrue(root.listFiles()?.none { it.isDirectory && it.name.startsWith("session-") } != false)
    }

    private class AsyncListenerNative : MeetingNativeBridge {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val cancelCount = AtomicInteger()
        @Volatile var openEntered: CountDownLatch? = null
        @Volatile var openRelease: CountDownLatch? = null
        @Volatile var acceptEntered: CountDownLatch? = null
        @Volatile var acceptRelease: CountDownLatch? = null
        @Volatile var finishEntered: CountDownLatch? = null
        @Volatile var finishRelease: CountDownLatch? = null
        @Volatile var cancelEntered: CountDownLatch? = null
        @Volatile var closeEntered: CountDownLatch? = null
        @Volatile var closeRelease: CountDownLatch? = null
        @Volatile var listener: ((MeetingNativeUpdate) -> Unit)? = null
            private set
        @Volatile var lastListener: ((MeetingNativeUpdate) -> Unit)? = null
            private set

        override fun open(asrPath: String, diarPath: String, language: String): Long {
            events += "open:enter"
            openEntered?.countDown()
            awaitRelease(openRelease)
            events += "open:return"
            return 73L
        }

        override fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate> {
            events += "accept:enter"
            acceptEntered?.countDown()
            awaitRelease(acceptRelease)
            events += "accept:return"
            return emptyList()
        }

        override fun finish(handle: Long): List<MeetingNativeUpdate> {
            events += "finish:enter"
            finishEntered?.countDown()
            awaitRelease(finishRelease)
            events += "finish:return"
            return emptyList()
        }

        override fun setUpdateListener(handle: Long, listener: ((MeetingNativeUpdate) -> Unit)?) {
            events += if (listener == null) "listener:remove" else "listener:add"
            this.listener = listener
            if (listener != null) lastListener = listener
        }

        override fun requestCancel(handle: Long) {
            events += "cancel"
            cancelCount.incrementAndGet()
            cancelEntered?.countDown()
        }

        override fun close(handle: Long) {
            events += "close:enter"
            closeEntered?.countDown()
            awaitRelease(closeRelease)
            events += "close:return"
        }

        fun emit(update: MeetingNativeUpdate) {
            listener?.invoke(update)
        }

        fun update(revision: Long, transcript: String, isFinal: Boolean = false) = MeetingNativeUpdate(
            utteranceId = 1,
            revision = revision,
            words = emptyList(),
            transcript = transcript,
            isFinal = isFinal,
            stableSpeakerThroughMs = 0L,
            audioProcessedMs = 0L,
        )

        private fun awaitRelease(latch: CountDownLatch?) {
            if (latch != null) check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "test latch timed out" }
        }
    }

    private class CleanupUncertainOpenNative : MeetingNativeBridge {
        val closeCount = AtomicInteger()

        override fun open(asrPath: String, diarPath: String, language: String): Long =
            throw MeetingNativeCleanupUncertainException(IllegalStateException("begin failed"))

        override fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate> = emptyList()
        override fun finish(handle: Long): List<MeetingNativeUpdate> = emptyList()
        override fun close(handle: Long) { closeCount.incrementAndGet() }
    }

    private class RecordingNative : MeetingNativeBridge {
        val openCount = AtomicInteger()
        val acceptCount = AtomicInteger()
        val finishCount = AtomicInteger()
        val closeCount = AtomicInteger()
        val voiceProgressReadCount = AtomicInteger()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val acceptedBlocks = Collections.synchronizedList(mutableListOf<List<Byte>>())
        val callThreadIds = Collections.synchronizedList(mutableListOf<Long>())
        val failNextOpen = AtomicBoolean()
        val failNextAccept = AtomicBoolean()
        @Volatile var failClose = false
        @Volatile var acceptedUpdates: List<MeetingNativeUpdate>? = null
        @Volatile var voiceProgressSnapshot: MeetingVoiceProgress = MeetingVoiceProgress.EMPTY
        @Volatile var concurrentNativeCalls = false
        @Volatile var openEntered: CountDownLatch? = null
        @Volatile var openRelease: CountDownLatch? = null
        @Volatile var firstAcceptEntered: CountDownLatch? = null
        @Volatile var firstAcceptRelease: CountDownLatch? = null
        @Volatile var secondAcceptEntered: CountDownLatch? = null
        @Volatile var secondAcceptRelease: CountDownLatch? = null
        @Volatile var finishEntered: CountDownLatch? = null
        @Volatile var finishRelease: CountDownLatch? = null
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
            finishEntered?.countDown()
            awaitRelease(finishRelease)
            listOf(update(99, "final", isFinal = true))
        }

        override fun voiceProgress(handle: Long): MeetingVoiceProgress {
            voiceProgressReadCount.incrementAndGet()
            return voiceProgressSnapshot
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

        fun emptyHandyWindow() = HandyTokenWindow(
            fullTextUtf8 = byteArrayOf(),
            firstTokenIndex = 0,
            totalTokenCount = 0,
            committedTokenCount = 0,
            tokenBytes = byteArrayOf(),
            tokenByteEnds = intArrayOf(),
            tokenStartsMs = longArrayOf(),
            tokenEndsMs = longArrayOf(),
        )
    }
}
