package com.kafkasl.phonewhisper.meeting

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HandyMeetingNativeBridgeTest {
    @Test
    fun asr_is_ready_and_publishes_while_diarization_model_open_is_blocked() {
        val diarOpenEntered = CountDownLatch(1)
        val diarOpenRelease = CountDownLatch(1)
        val handy = RecordingHandy(window("Salut."))
        var handyLanguage: String? = null
        val bridge = bridge(
            handy = handy,
            handyFactory = { _, language -> handyLanguage = language; handy },
            openDiarization = {
                diarOpenEntered.countDown()
                awaitRelease(diarOpenRelease)
                RecordingDiarization()
            },
        )
        val handle = bridge.open("handy", "diar", "fr")
        val updates = Collections.synchronizedList(mutableListOf<MeetingNativeUpdate>())
        val updateArrived = CountDownLatch(1)

        try {
            assertTrue(diarOpenEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            bridge.setUpdateListener(handle) { update -> updates += update; updateArrived.countDown() }
            assertEquals(emptyList<MeetingNativeUpdate>(), bridge.acceptPcm16(handle, ByteArray(32_000), 32_000))

            assertTrue("Handy transcript must not wait for diarization loading", updateArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("fr-FR", handyLanguage)
            assertEquals("Salut.", updates.single().transcript)
            val progress = bridge.voiceProgress(handle)
            assertEquals(MeetingVoiceState.PREPARING, progress.state)
            assertEquals(1_000L, progress.pendingAudioMs)
        } finally {
            diarOpenRelease.countDown()
            bridge.requestCancel(handle)
            bridge.close(handle)
        }
    }

    @Test
    fun finish_publishes_final_text_before_diar_drain_then_publishes_voice_revision() {
        val diarAcceptEntered = CountDownLatch(1)
        val diarAcceptRelease = CountDownLatch(1)
        val diar = RecordingDiarization(
            acceptEntered = diarAcceptEntered,
            acceptRelease = diarAcceptRelease,
        )
        val handy = RecordingHandy(window("Bonjour."))
        val bridge = bridge(handy = handy, diarization = diar)
        val handle = bridge.open("handy", "diar", "fr-FR")
        val updates = Collections.synchronizedList(mutableListOf<MeetingNativeUpdate>())
        val finalTextPublished = CountDownLatch(1)
        bridge.setUpdateListener(handle) { update ->
            updates += update
            if (update.isFinal) finalTextPublished.countDown()
        }
        val pcm = ByteArray(32_000)
        val finishReturned = CountDownLatch(1)

        try {
            bridge.acceptPcm16(handle, pcm, pcm.size)
            assertTrue(diarAcceptEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            Thread({
                bridge.finish(handle)
                finishReturned.countDown()
            }, "meeting-test-finish").start()

            assertTrue("final ASR text is published before waiting for diarization", finalTextPublished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1L, finishReturned.count)
            assertEquals("Bonjour.", updates.first { it.isFinal }.transcript)

            diarAcceptRelease.countDown()
            assertTrue("finish waits until the final diarization revision is delivered", finishReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val finalRevision = updates.last()
            assertTrue(finalRevision.revision > updates.first { it.isFinal }.revision)
            assertEquals("Bonjour.", finalRevision.transcript)
            assertEquals(1, finalRevision.words.single().channel)
            assertEquals(emptyList<MeetingNativeUpdate>(), bridge.finish(handle))
        } finally {
            diarAcceptRelease.countDown()
            bridge.close(handle)
        }
    }

    @Test
    fun cancel_does_not_wait_for_inflight_diar_call_and_suppresses_late_revisions() {
        val diarAcceptEntered = CountDownLatch(1)
        val diarAcceptRelease = CountDownLatch(1)
        val diar = RecordingDiarization(
            acceptEntered = diarAcceptEntered,
            acceptRelease = diarAcceptRelease,
        )
        val handy = RecordingHandy(window("Bonjour."))
        val bridge = bridge(handy = handy, diarization = diar)
        val handle = bridge.open("handy", "diar", "en")
        val updates = Collections.synchronizedList(mutableListOf<MeetingNativeUpdate>())
        bridge.setUpdateListener(handle) { updates += it }
        val pcm = ByteArray(32_000)
        val cancelReturned = CountDownLatch(1)

        try {
            bridge.acceptPcm16(handle, pcm, pcm.size)
            assertTrue(diarAcceptEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val updatesBeforeCancel = updates.size
            Thread({
                bridge.requestCancel(handle)
                cancelReturned.countDown()
            }, "meeting-test-cancel").start()

            assertTrue("cancel must return while native diarization remains in flight", cancelReturned.await(1, TimeUnit.SECONDS))
            val progress = bridge.voiceProgress(handle)
            assertEquals(MeetingVoiceState.UNAVAILABLE, progress.state)
            assertEquals(MeetingVoiceUnavailableReason.CANCELLED, progress.unavailableReason)

            diarAcceptRelease.countDown()
            bridge.close(handle)
            assertEquals(updatesBeforeCancel, updates.size)
            assertTrue(diar.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            diarAcceptRelease.countDown()
            bridge.close(handle)
        }
    }

    @Test
    fun diarization_backlog_overflow_disables_voice_without_stopping_handy() {
        val diarAcceptEntered = CountDownLatch(1)
        val diarAcceptRelease = CountDownLatch(1)
        val diar = RecordingDiarization(
            acceptEntered = diarAcceptEntered,
            acceptRelease = diarAcceptRelease,
        )
        val handy = RecordingHandy(window("Salut."))
        val bridge = bridge(handy = handy, diarization = diar, maxDiarizationQueueBytes = 4)
        val handle = bridge.open("handy", "diar", "en-US")

        try {
            bridge.setUpdateListener(handle) { }
            assertEquals(emptyList<MeetingNativeUpdate>(), bridge.acceptPcm16(handle, ByteArray(4), 4))
            assertTrue(diarAcceptEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(emptyList<MeetingNativeUpdate>(), bridge.acceptPcm16(handle, ByteArray(4), 4))

            assertEquals(2, handy.acceptCount.get())
            val progress = bridge.voiceProgress(handle)
            assertEquals(MeetingVoiceState.UNAVAILABLE, progress.state)
            assertEquals(MeetingVoiceUnavailableReason.BACKLOG_LIMIT, progress.unavailableReason)
        } finally {
            diarAcceptRelease.countDown()
            bridge.requestCancel(handle)
            bridge.close(handle)
        }
    }

    @Test
    fun unsupported_language_is_rejected_before_opening_either_model() {
        val handyOpens = AtomicInteger()
        val diarOpens = AtomicInteger()
        val bridge = HandyMeetingNativeBridge(
            handyFactory = { _, _ -> handyOpens.incrementAndGet(); RecordingHandy(window("hello")) },
            diarizationFactory = { diarOpens.incrementAndGet(); RecordingDiarization() },
            setDiarWorkerBackgroundPriority = {},
        )

        try {
            bridge.open("handy", "diar", "es")
            throw AssertionError("unsupported language was accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, handyOpens.get())
            assertEquals(0, diarOpens.get())
        }
    }

    @Test
    fun a_long_diarization_session_requests_the_latest_bounded_frame_window() {
        val totalFrames = 25_000L
        val requested = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        val diar = object : DiarizationSessionPort {
            val closed = CountDownLatch(1)

            override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) = Unit

            override fun snapshot(firstFrameIndex: Long, maxFrames: Int): DiarizationFrameWindow {
                requested += firstFrameIndex to maxFrames
                return if (maxFrames == 0) {
                    DiarizationFrameWindow(
                        firstFrameIndex = 0L,
                        secondsPerFrame = 0.01,
                        probabilities = FloatArray(0),
                        speakerCount = 1,
                        stableFrameCount = totalFrames,
                        totalFrameCount = totalFrames,
                    )
                } else {
                    DiarizationFrameWindow(
                        firstFrameIndex = firstFrameIndex,
                        secondsPerFrame = 0.01,
                        probabilities = FloatArray(maxFrames) { 1.0f },
                        speakerCount = 1,
                        stableFrameCount = totalFrames,
                        totalFrameCount = totalFrames,
                    )
                }
            }

            override fun finish() = Unit
            override fun close() { closed.countDown() }
        }
        val pcmBytes = 7_680_640 // 240,020 ms at 16 kHz mono PCM16.
        val handy = RecordingHandy(window("Bonjour ", startMs = 240_000L, endMs = 240_020L))
        val bridge = bridge(handy, diarization = diar, maxDiarizationQueueBytes = pcmBytes)
        val handle = bridge.open("handy", "diar", "fr")
        val voiceRevision = CountDownLatch(1)
        bridge.setUpdateListener(handle) { update ->
            if (update.words.any { it.channel == 1 }) voiceRevision.countDown()
        }

        try {
            bridge.acceptPcm16(handle, ByteArray(pcmBytes), pcmBytes)

            assertTrue("recent frames must be attributed after audio exceeds 164 s", voiceRevision.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf(0L to 0, 13_000L to 12_000), requested.toList())
        } finally {
            bridge.requestCancel(handle)
            bridge.close(handle)
        }
    }

    @Test
    fun cancel_prevents_later_accept_and_finish_from_entering_handy() {
        val diarOpened = CountDownLatch(1)
        val handy = RecordingHandy(window("Salut "))
        val diar = RecordingDiarization()
        val bridge = bridge(
            handy = handy,
            openDiarization = {
                diarOpened.countDown()
                diar
            },
        )
        val handle = bridge.open("handy", "diar", "fr")

        try {
            assertTrue(diarOpened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            bridge.requestCancel(handle)
            val accepts = handy.acceptCount.get()
            val snapshots = handy.snapshotCount.get()
            val finishes = handy.finishCount.get()

            assertEquals(emptyList<MeetingNativeUpdate>(), bridge.acceptPcm16(handle, ByteArray(32), 32))
            assertEquals(emptyList<MeetingNativeUpdate>(), bridge.finish(handle))
            assertEquals(accepts, handy.acceptCount.get())
            assertEquals(snapshots, handy.snapshotCount.get())
            assertEquals(finishes, handy.finishCount.get())
        } finally {
            bridge.close(handle)
            bridge.close(handle)
        }

        assertEquals("Handy closes exactly once", 1, handy.closeCount.get())
        assertEquals("diarization closes exactly once", 1, diar.closeCount.get())
    }

    @Test
    fun diarization_load_or_feed_failure_does_not_stop_handy_transcription() {
        val loadHandy = RecordingHandy(window("Salut "))
        val workerFinished = CountDownLatch(1)
        val loadBridge = HandyMeetingNativeBridge(
            handyFactory = { _, _ -> loadHandy },
            diarizationFactory = { throw IllegalStateException("diar model load failed") },
            setDiarWorkerBackgroundPriority = {},
            workerFactory = { runnable, name ->
                Thread({
                    try { runnable.run() } finally { workerFinished.countDown() }
                }, name).apply { isDaemon = true }
            },
        )
        val loadHandle = loadBridge.open("handy", "diar", "fr")
        val loadUpdates = Collections.synchronizedList(mutableListOf<MeetingNativeUpdate>())
        loadBridge.setUpdateListener(loadHandle, loadUpdates::add)
        try {
            loadBridge.acceptPcm16(loadHandle, ByteArray(32_000), 32_000)
            assertTrue(workerFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf("Salut "), loadUpdates.map { it.transcript })
            assertEquals(MeetingVoiceUnavailableReason.MODEL_LOAD_FAILED, loadBridge.voiceProgress(loadHandle).unavailableReason)
        } finally {
            loadBridge.close(loadHandle)
        }

        val feedHandy = RecordingHandy(window("Bonjour "))
        val failingDiar = RecordingDiarization(acceptFailure = IllegalStateException("diar feed failed"))
        val feedBridge = bridge(handy = feedHandy, diarization = failingDiar)
        val feedHandle = feedBridge.open("handy", "diar", "fr")
        val feedUpdates = Collections.synchronizedList(mutableListOf<MeetingNativeUpdate>())
        feedBridge.setUpdateListener(feedHandle, feedUpdates::add)
        try {
            feedBridge.acceptPcm16(feedHandle, ByteArray(32_000), 32_000)
            assertTrue(failingDiar.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf("Bonjour "), feedUpdates.map { it.transcript })
            assertEquals(MeetingVoiceUnavailableReason.PROCESSING_FAILED, feedBridge.voiceProgress(feedHandle).unavailableReason)
        } finally {
            feedBridge.close(feedHandle)
        }
    }

    @Test
    fun close_waits_for_an_active_listener_and_restores_interrupt_status() {
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val acceptReturned = CountDownLatch(1)
        val handy = RecordingHandy(window("Salut "))
        val diar = RecordingDiarization()
        val diarOpened = CountDownLatch(1)
        val bridge = bridge(
            handy = handy,
            openDiarization = {
                diarOpened.countDown()
                diar
            },
        )
        val handle = bridge.open("handy", "diar", "fr")
        assertTrue(diarOpened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        bridge.setUpdateListener(handle) {
            callbackEntered.countDown()
            awaitRelease(callbackRelease)
        }
        Thread({
            bridge.acceptPcm16(handle, ByteArray(32), 32)
            acceptReturned.countDown()
        }, "meeting-test-blocked-callback").start()
        assertTrue(callbackEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val closeReturned = CountDownLatch(1)
        val closeThreadInterrupted = AtomicBoolean()
        val closeThread = Thread({
            Thread.currentThread().interrupt()
            bridge.close(handle)
            closeThreadInterrupted.set(Thread.currentThread().isInterrupted)
            closeReturned.countDown()
        }, "meeting-test-interrupted-close")
        closeThread.start()
        try {
            assertTrue(diar.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse("native Handy close must remain behind the callback barrier", handy.closeEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            callbackRelease.countDown()
        }

        assertTrue(acceptReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(closeReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(closeThreadInterrupted.get())
        assertEquals(1, handy.closeCount.get())
    }

    @Test
    fun failed_worker_creation_closes_handys_lease_and_preserves_uncertain_cleanup() {
        val startFailure = IllegalStateException("diar worker creation failed")
        val closeFailure = IllegalStateException("Handy close failed")
        val handy = RecordingHandy(window("Salut "), closeFailure = closeFailure)
        val bridge = HandyMeetingNativeBridge(
            handyFactory = { _, _ -> handy },
            diarizationFactory = { RecordingDiarization() },
            setDiarWorkerBackgroundPriority = {},
            workerFactory = { _, _ -> throw startFailure },
        )

        val uncertain = assertThrows(MeetingNativeCleanupUncertainException::class.java) {
            bridge.open("handy", "diar", "fr")
        }

        assertSame(startFailure, uncertain.cause)
        assertEquals(1, uncertain.suppressed.size)
        assertTrue(uncertain.suppressed.single() is MeetingNativeCleanupUncertainException)
        assertSame(closeFailure, uncertain.suppressed.single().cause)
        assertEquals(1, handy.closeCount.get())
    }

    @Test
    fun diar_revision_is_not_blocked_by_the_next_synchronous_handy_accept() {
        val secondAcceptEntered = CountDownLatch(1)
        val secondAcceptRelease = CountDownLatch(1)
        val snapshotEntered = CountDownLatch(1)
        val snapshotRelease = CountDownLatch(1)
        val handy = RecordingHandy(
            window("Bonjour "),
            secondAcceptEntered = secondAcceptEntered,
            secondAcceptRelease = secondAcceptRelease,
        )
        val diar = RecordingDiarization(
            snapshotEntered = snapshotEntered,
            snapshotRelease = snapshotRelease,
        )
        val bridge = bridge(handy = handy, diarization = diar)
        val handle = bridge.open("handy", "diar", "fr")
        val voiceRevision = CountDownLatch(1)
        val secondAcceptReturned = CountDownLatch(1)
        var secondAcceptStarted = false
        bridge.setUpdateListener(handle) { update ->
            if (update.words.any { it.channel == 1 }) voiceRevision.countDown()
        }

        try {
            bridge.acceptPcm16(handle, ByteArray(32_000), 32_000)
            assertTrue(snapshotEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            secondAcceptStarted = true
            Thread({
                bridge.acceptPcm16(handle, ByteArray(32), 32)
                secondAcceptReturned.countDown()
            }, "meeting-test-blocked-asr").start()
            assertTrue(secondAcceptEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            snapshotRelease.countDown()

            assertTrue("voice revision should not wait behind Handy JNI", voiceRevision.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            snapshotRelease.countDown()
            secondAcceptRelease.countDown()
            if (secondAcceptStarted) assertTrue(secondAcceptReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            bridge.requestCancel(handle)
            bridge.close(handle)
        }
    }

    private fun bridge(
        handy: RecordingHandy,
        diarization: DiarizationSessionPort = RecordingDiarization(),
        maxDiarizationQueueBytes: Int = 64_000,
        handyFactory: (String, String) -> HandyAsrPort = { _, _ -> handy },
        openDiarization: (() -> DiarizationSessionPort)? = null,
        workerFactory: (Runnable, String) -> Thread = { runnable, name ->
            Thread(runnable, name).apply { isDaemon = true }
        },
    ) = HandyMeetingNativeBridge(
        handyFactory = handyFactory,
        diarizationFactory = { openDiarization?.invoke() ?: diarization },
        maxDiarizationQueueBytes = maxDiarizationQueueBytes,
        setDiarWorkerBackgroundPriority = {},
        workerFactory = workerFactory,
    )

    private class RecordingHandy(
        private val snapshotWindow: HandyTokenWindow,
        private val secondAcceptEntered: CountDownLatch? = null,
        private val secondAcceptRelease: CountDownLatch? = null,
        private val closeFailure: Throwable? = null,
    ) : HandyAsrPort {
        val acceptCount = AtomicInteger()
        val snapshotCount = AtomicInteger()
        val finishCount = AtomicInteger()
        val closeCount = AtomicInteger()
        val closeEntered = CountDownLatch(1)

        override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) {
            if (acceptCount.incrementAndGet() == 2) {
                secondAcceptEntered?.countDown()
                awaitRelease(secondAcceptRelease)
            }
        }

        override fun snapshot(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow {
            snapshotCount.incrementAndGet()
            return snapshotWindow
        }

        override fun finish(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow {
            finishCount.incrementAndGet()
            return snapshotWindow
        }

        override fun close() {
            closeCount.incrementAndGet()
            closeEntered.countDown()
            closeFailure?.let { throw it }
        }
    }

    private class RecordingDiarization(
        private val acceptEntered: CountDownLatch? = null,
        private val acceptRelease: CountDownLatch? = null,
        private val acceptFailure: Throwable? = null,
        private val snapshotEntered: CountDownLatch? = null,
        private val snapshotRelease: CountDownLatch? = null,
        private val snapshotProvider: ((Long, Int) -> DiarizationFrameWindow)? = null,
    ) : DiarizationSessionPort {
        val closed = CountDownLatch(1)
        val finishEntered = CountDownLatch(1)
        val frames = testDiarizationWindow()
        val closeCount = AtomicInteger()
        val snapshotRequests = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())

        override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) {
            acceptEntered?.countDown()
            awaitRelease(acceptRelease)
            acceptFailure?.let { throw it }
        }

        override fun snapshot(firstFrameIndex: Long, maxFrames: Int): DiarizationFrameWindow {
            snapshotRequests += firstFrameIndex to maxFrames
            snapshotEntered?.countDown()
            awaitRelease(snapshotRelease)
            return snapshotProvider?.invoke(firstFrameIndex, maxFrames) ?: frames
        }

        override fun finish() {
            finishEntered.countDown()
        }

        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }

    private fun window(text: String, startMs: Long = 0L, endMs: Long = 20L): HandyTokenWindow {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return HandyTokenWindow(
            fullTextUtf8 = bytes,
            firstTokenIndex = 0,
            totalTokenCount = 1,
            committedTokenCount = 1,
            tokenBytes = bytes,
            tokenByteEnds = intArrayOf(bytes.size),
            tokenStartsMs = longArrayOf(startMs),
            tokenEndsMs = longArrayOf(endMs),
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 3L

        fun awaitRelease(latch: CountDownLatch?) {
            if (latch != null) check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "test latch timed out"
            }
        }
    }
}

private fun testDiarizationWindow() = DiarizationFrameWindow(
    firstFrameIndex = 0L,
    secondsPerFrame = 0.01,
    probabilities = FloatArray(200) { index -> if (index % 2 == 0) 1.0f else 0.0f },
    speakerCount = 2,
    stableFrameCount = 100L,
    totalFrameCount = 100L,
)
