package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandyMeetingVoiceLeadTest {
    @Test(timeout = 10_000L)
    fun default_diarization_worker_opens_with_normal_java_priority() {
        val handy = BlockingHandy()
        val diarization = RecordingDiarization()
        val diarizationOpened = CountDownLatch(1)
        val observedJavaPriority = AtomicInteger(-1)
        val bridge = HandyMeetingNativeBridge(
            handyFactory = { _, _ -> handy },
            diarizationFactory = {
                observedJavaPriority.set(Thread.currentThread().priority)
                diarizationOpened.countDown()
                diarization
            },
            setDiarWorkerPriority = {},
        )
        val handle = bridge.open("fake-handy", "fake-diarization", "fr-FR")

        try {
            assertTrue(diarizationOpened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(Thread.NORM_PRIORITY, observedJavaPriority.get())
        } finally {
            bridge.close(handle)
        }
    }

    @Test(timeout = 60_000L)
    fun stalled_handy_caps_voice_lead_at_sixty_seconds_then_drains_capture_fifo() {
        val rig = VoiceLeadRig()
        val blocks = pcmBlocks()

        try {
            rig.captureBlock(0, blocks[0])
            rig.startFirstHandyAccept(blocks[0])
            assertTrue(rig.handy.firstAcceptEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            for (index in 1 until blocks.size) rig.captureBlock(index, blocks[index])
            assertTrue(
                "all captured PCM blocks reach the independent backlog",
                rig.allCaptureBlocksTransferred.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertAtSixtySecondLeadLimit(rig)

            rig.handy.releaseFirstAccept.countDown()
            assertTrue(rig.firstHandyAcceptReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertNull(rig.firstHandyAcceptFailure.get())
            for (index in 1 until blocks.size) {
                rig.bridge.acceptPcm16(rig.handle, blocks[index], blocks[index].size)
            }

            rig.bridge.finish(rig.handle)

            val acceptedBlocks = rig.diarization.acceptedBlocksSnapshot()
            assertEquals(blocks.size, acceptedBlocks.size)
            blocks.indices.forEach { index -> assertArrayEquals(blocks[index], acceptedBlocks[index]) }
            assertEquals(blocks.size.toLong() * BLOCK_BYTES, rig.diarization.acceptedAudioBytes.get())
            assertTrue(rig.diarization.finishEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 60_000L)
    fun cancel_wakes_voice_wait_without_waiting_for_the_blocked_handy_call() {
        val rig = VoiceLeadRig()
        val blocks = pcmBlocks()

        try {
            rig.captureBlock(0, blocks[0])
            rig.startFirstHandyAccept(blocks[0])
            assertTrue(rig.handy.firstAcceptEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            for (index in 1 until blocks.size) rig.captureBlock(index, blocks[index])
            assertTrue(
                "all captured PCM blocks reach the independent backlog",
                rig.allCaptureBlocksTransferred.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertAtSixtySecondLeadLimit(rig)

            val cancelReturned = CountDownLatch(1)
            val cancelFailure = AtomicReference<Throwable?>()
            Thread({
                try {
                    rig.bridge.requestCancel(rig.handle)
                } catch (failure: Throwable) {
                    cancelFailure.set(failure)
                } finally {
                    cancelReturned.countDown()
                }
            }, "meeting-test-cancel-at-voice-lead-limit").apply {
                isDaemon = true
                start()
            }

            assertTrue(
                "requestCancel returns while Handy remains blocked",
                cancelReturned.await(1, TimeUnit.SECONDS),
            )
            assertNull(cancelFailure.get())
            assertEquals(MeetingVoiceState.UNAVAILABLE, rig.bridge.voiceProgress(rig.handle).state)
            assertEquals(
                MeetingVoiceUnavailableReason.CANCELLED,
                rig.bridge.voiceProgress(rig.handle).unavailableReason,
            )
            assertTrue(
                "cancellation wakes and closes the voice worker before Handy is released",
                rig.diarization.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertEquals(1L, rig.firstHandyAcceptReturned.count)
            assertEquals(6, rig.diarization.acceptCount.get())
            assertEquals(MAX_VOICE_LEAD_BYTES, rig.diarization.acceptedAudioBytes.get())
        } finally {
            rig.close()
        }
    }

    private fun assertAtSixtySecondLeadLimit(rig: VoiceLeadRig) {
        assertTrue(
            "the voice worker must be waiting at the measured headroom limit",
            awaitCondition(TIMEOUT_SECONDS) {
                rig.diarization.acceptedAudioBytes.get() == MAX_VOICE_LEAD_BYTES &&
                    rig.diarizationWorker.get()?.state == Thread.State.WAITING
            },
        )
        assertEquals("Handy is still held inside its first accept", 1L, rig.firstHandyAcceptReturned.count)
        assertEquals("six ten-second blocks, and no seventh, reached the diarizer", 6, rig.diarization.acceptCount.get())
        assertEquals(MAX_VOICE_LEAD_BYTES, rig.diarization.acceptedAudioBytes.get())
        assertEquals(MeetingVoiceState.ACTIVE, rig.bridge.voiceProgress(rig.handle).state)
    }

    private fun pcmBlocks(): List<ByteArray> = (0 until CAPTURE_BLOCK_COUNT).map { blockIndex ->
        ByteArray(BLOCK_BYTES) { (blockIndex + 1).toByte() }
    }

    private fun awaitCondition(timeoutSeconds: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.yield()
        }
        return condition()
    }

    private inner class VoiceLeadRig : AutoCloseable {
        val handy = BlockingHandy()
        val diarization = RecordingDiarization()
        val diarizationWorker = AtomicReference<Thread?>()
        val transferredBlockCount = AtomicInteger()
        val allCaptureBlocksTransferred = CountDownLatch(CAPTURE_BLOCK_COUNT)
        val firstHandyAcceptReturned = CountDownLatch(1)
        val firstHandyAcceptFailure = AtomicReference<Throwable?>()
        private val spoolRoot = Files.createTempDirectory("meeting-voice-lead").toFile()
        val bridge = HandyMeetingNativeBridge(
            handyFactory = { _, _ -> handy },
            diarizationFactory = { diarization },
            maxDiarizationQueueBytes = 120 * PCM_BYTES_PER_SECOND,
            setDiarWorkerPriority = {},
            workerFactory = { runnable, name ->
                Thread(runnable, name).apply {
                    isDaemon = true
                    diarizationWorker.set(this)
                }
            },
            diarizationSpoolRoot = spoolRoot,
            onDiarizationBlockTransferredForTest = {
                transferredBlockCount.incrementAndGet()
                allCaptureBlocksTransferred.countDown()
            },
        )
        val handle = bridge.open("fake-handy", "fake-diarization", "fr-FR")
        private var firstHandyThread: Thread? = null

        init {
            bridge.awaitCaptureReady(handle)
        }

        fun captureBlock(index: Int, block: ByteArray) {
            bridge.onPcmCaptured(handle, block, block.size)
            assertTrue(
                "capture block $index is transferred before the next block is offered",
                awaitCondition(TIMEOUT_SECONDS) { transferredBlockCount.get() >= index + 1 },
            )
        }

        fun startFirstHandyAccept(block: ByteArray) {
            firstHandyThread = Thread({
                try {
                    bridge.acceptPcm16(handle, block, block.size)
                } catch (failure: Throwable) {
                    firstHandyAcceptFailure.set(failure)
                } finally {
                    firstHandyAcceptReturned.countDown()
                }
            }, "meeting-test-blocked-handy-accept").apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            handy.releaseFirstAccept.countDown()
            firstHandyThread?.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            try {
                bridge.close(handle)
            } finally {
                spoolRoot.deleteRecursively()
            }
        }
    }

    private class BlockingHandy : HandyAsrPort {
        val firstAcceptEntered = CountDownLatch(1)
        val releaseFirstAccept = CountDownLatch(1)
        private val acceptCount = AtomicInteger()
        private val emptyWindow = HandyTokenWindow(
            fullTextUtf8 = ByteArray(0),
            firstTokenIndex = 0,
            totalTokenCount = 0,
            committedTokenCount = 0,
            tokenBytes = ByteArray(0),
            tokenByteEnds = IntArray(0),
            tokenStartsMs = LongArray(0),
            tokenEndsMs = LongArray(0),
        )

        override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) {
            if (acceptCount.incrementAndGet() == 1) {
                firstAcceptEntered.countDown()
                check(releaseFirstAccept.await(TIMEOUT_SECONDS * 3L, TimeUnit.SECONDS)) {
                    "the test did not release the blocked Handy accept"
                }
            }
        }

        override fun snapshot(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow = emptyWindow

        override fun finish(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow = emptyWindow

        override fun close() = Unit
    }

    private class RecordingDiarization : DiarizationSessionPort {
        val acceptedAudioBytes = AtomicLong()
        val acceptCount = AtomicInteger()
        val acceptedBlocks = Collections.synchronizedList(mutableListOf<ByteArray>())
        val finishEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) {
            acceptedBlocks += buffer.copyOf(lengthBytes)
            acceptedAudioBytes.addAndGet(lengthBytes.toLong())
            acceptCount.incrementAndGet()
        }

        override fun snapshot(firstFrameIndex: Long, maxFrames: Int): DiarizationFrameWindow {
            val totalFrames = acceptedAudioBytes.get() / BYTES_PER_TEN_MILLISECONDS
            val windowStart = firstFrameIndex.coerceAtMost(totalFrames)
            val frameCount = minOf(maxFrames.toLong(), totalFrames - windowStart).toInt()
            return DiarizationFrameWindow(
                firstFrameIndex = windowStart,
                secondsPerFrame = 0.01,
                probabilities = FloatArray(frameCount),
                speakerCount = 1,
                stableFrameCount = totalFrames,
                totalFrameCount = totalFrames,
            )
        }

        override fun finish() {
            finishEntered.countDown()
        }

        override fun close() {
            closed.countDown()
        }

        fun acceptedBlocksSnapshot(): List<ByteArray> = synchronized(acceptedBlocks) {
            acceptedBlocks.map { it.copyOf() }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val PCM_BYTES_PER_SECOND = 32_000
        const val BYTES_PER_TEN_MILLISECONDS = 320L
        const val BLOCK_BYTES = 10 * PCM_BYTES_PER_SECOND
        const val CAPTURE_BLOCK_COUNT = 8
        const val MAX_VOICE_LEAD_BYTES = 60L * PCM_BYTES_PER_SECOND
    }
}
