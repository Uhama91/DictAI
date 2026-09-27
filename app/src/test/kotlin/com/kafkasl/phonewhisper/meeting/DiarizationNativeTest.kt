package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DiarizationNativeTest {
    @Test
    fun opens_with_one_thread_by_default_and_forwards_explicit_thread_counts() {
        val defaults = FakeBindings()
        val defaultSession = DiarizationNative.open("diar.gguf", bindings = defaults)
        assertEquals(listOf("diar.gguf"), defaults.openedPaths)
        assertEquals(listOf(1), defaults.openedCpuThreads)
        assertEquals(listOf(0), defaults.openedChunkFrames)
        defaultSession.close()

        val fourThreads50Frames = FakeBindings()
        val fourThreads = DiarizationNative.open(
            "diar-four.gguf",
            cpuThreads = 4,
            chunkFrames = 50,
            bindings = fourThreads50Frames,
        )
        assertEquals(listOf("diar-four.gguf"), fourThreads50Frames.openedPaths)
        assertEquals(listOf(4), fourThreads50Frames.openedCpuThreads)
        assertEquals(listOf(50), fourThreads50Frames.openedChunkFrames)
        fourThreads.close()

        val twoThreads100Frames = FakeBindings()
        val twoThreads = DiarizationNative.open(
            "diar-eight-second-chunk.gguf",
            cpuThreads = 2,
            chunkFrames = 100,
            bindings = twoThreads100Frames,
        )
        assertEquals(listOf(2), twoThreads100Frames.openedCpuThreads)
        assertEquals(listOf(100), twoThreads100Frames.openedChunkFrames)
        twoThreads.close()
    }

    @Test
    fun rejects_cpu_thread_counts_outside_one_through_four_before_native_dispatch() {
        val bindings = FakeBindings()

        for (cpuThreads in listOf(0, 5)) {
            assertThrows(IllegalArgumentException::class.java) {
                DiarizationNative.open("diar.gguf", cpuThreads = cpuThreads, bindings = bindings)
            }
        }

        assertEquals(emptyList<String>(), bindings.openedPaths)
        assertEquals(emptyList<Int>(), bindings.openedCpuThreads)
    }

    @Test
    fun rejects_chunk_frame_overrides_other_than_zero_fifty_or_one_hundred_before_native_dispatch() {
        val bindings = FakeBindings()

        for (chunkFrames in listOf(-1, 1, 49, 51, 99, 101)) {
            assertThrows(IllegalArgumentException::class.java) {
                DiarizationNative.open("diar.gguf", chunkFrames = chunkFrames, bindings = bindings)
            }
        }

        assertEquals(emptyList<String>(), bindings.openedPaths)
        assertEquals(emptyList<Int>(), bindings.openedCpuThreads)
        assertEquals(emptyList<Int>(), bindings.openedChunkFrames)
    }

    @Test
    fun rejects_invalid_cpu_threads_before_initializing_jni_bindings() {
        val invalidArguments = listOf(
            Triple("", 1, 0),
            Triple("diar.gguf", 0, 0),
            Triple("diar.gguf", 5, 0),
            Triple("diar.gguf", 1, -1),
            Triple("diar.gguf", 1, 1),
            Triple("diar.gguf", 1, 49),
            Triple("diar.gguf", 1, 51),
            Triple("diar.gguf", 1, 99),
            Triple("diar.gguf", 1, 101),
        )

        for ((diarPath, cpuThreads, chunkFrames) in invalidArguments) {
            assertThrows(
                "path='$diarPath', cpuThreads=$cpuThreads, chunkFrames=$chunkFrames",
                IllegalArgumentException::class.java,
            ) {
                DiarizationNative.open(
                    diarPath,
                    cpuThreads = cpuThreads,
                    chunkFrames = chunkFrames,
                )
            }
        }
    }

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
        val openedPaths = mutableListOf<String>()
        val openedCpuThreads = mutableListOf<Int>()
        val openedChunkFrames = mutableListOf<Int>()
        val closedHandles = mutableListOf<Long>()

        override fun open(diarPath: String, cpuThreads: Int, chunkFrames: Int): Long {
            openedPaths += diarPath
            openedCpuThreads += cpuThreads
            openedChunkFrames += chunkFrames
            return 93L
        }
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
