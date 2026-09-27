package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DiarizationNativeTest {
    @Test
    fun preserves_absolute_frame_origin_and_probability_rows_from_bounded_snapshot() {
        val bindings = FakeBindings()
        val native = DiarizationNative(handle = 91L, bindings = bindings)

        val window = native.snapshot(firstFrameIndex = 96L, maxFrames = 4)

        assertEquals(96L, bindings.requestedFirstFrame)
        assertEquals(4, bindings.requestedMaxFrames)
        assertEquals(100L, window.firstFrameIndex)
        assertEquals(0.01, window.secondsPerFrame, 0.0)
        assertEquals(2, window.speakerCount)
        assertEquals(130L, window.stableFrameCount)
        assertEquals(200L, window.totalFrameCount)
        assertArrayEquals(floatArrayOf(0.1f, 0.9f, 0.3f, 0.7f), window.probabilities, 0.0f)
    }

    @Test
    fun rejects_malformed_pcm_and_unbounded_frame_requests_before_native_dispatch() {
        val bindings = FakeBindings()
        val native = DiarizationNative(handle = 92L, bindings = bindings)

        assertThrows(IllegalArgumentException::class.java) {
            native.acceptPcm16(byteArrayOf(1, 2, 3), lengthBytes = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            native.acceptPcm16(byteArrayOf(1, 2), lengthBytes = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            native.snapshot(firstFrameIndex = -1L, maxFrames = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            native.snapshot(firstFrameIndex = 0L, maxFrames = 16_385)
        }

        assertEquals(0, bindings.acceptCalls)
        assertEquals(0, bindings.snapshotCalls)
    }

    @Test
    fun finish_and_close_release_the_standalone_worker_idempotently() {
        val bindings = FakeBindings()
        val native = DiarizationNative(handle = 93L, bindings = bindings)

        native.finish()
        native.close()
        native.close()

        assertEquals(1, bindings.finishCalls)
        assertEquals(listOf(93L), bindings.closedHandles)
        assertThrows(IllegalStateException::class.java) {
            native.acceptPcm16(byteArrayOf(0, 0), lengthBytes = 2)
        }
    }

    private class FakeBindings : DiarizationNative.Bindings {
        private val expectedWindow = DiarizationFrameWindow(
            firstFrameIndex = 100L,
            secondsPerFrame = 0.01,
            probabilities = floatArrayOf(0.1f, 0.9f, 0.3f, 0.7f),
            speakerCount = 2,
            stableFrameCount = 130L,
            totalFrameCount = 200L,
        )
        var requestedFirstFrame = -1L
        var requestedMaxFrames = -1
        var requestedWindow: DiarizationFrameWindow? = null
        var acceptCalls = 0
        var snapshotCalls = 0
        var finishCalls = 0
        val closedHandles = mutableListOf<Long>()

        override fun open(diarPath: String): Long = 93L
        override fun acceptPcm16(handle: Long, buffer: ByteArray, lengthBytes: Int) {
            acceptCalls++
        }

        override fun snapshot(
            handle: Long,
            firstFrameIndex: Long,
            maxFrames: Int,
        ): DiarizationFrameWindow {
            requestedFirstFrame = firstFrameIndex
            requestedMaxFrames = maxFrames
            snapshotCalls++
            return expectedWindow.also { requestedWindow = it }
        }

        override fun finish(handle: Long) { finishCalls++ }
        override fun close(handle: Long) { closedHandles += handle }
    }
}
