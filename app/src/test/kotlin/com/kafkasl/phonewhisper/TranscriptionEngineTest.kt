package com.kafkasl.phonewhisper

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TranscriptionEngineTest {
    @Test
    fun pcm16ToFloat_convertsKnownSamples() {
        val pcm = byteArrayOf(
            0x00, 0x00,
            0xFF.toByte(), 0x7F,
            0x00, 0x80.toByte()
        )
        val f = TranscriptionEngine.pcm16ToFloat(pcm)
        assertEquals(3, f.size)
        assertEquals(0f, f[0], 1e-6f)
        assertEquals(32767f / 32768f, f[1], 1e-4f)
        assertEquals(-1f, f[2], 1e-6f)
    }

    @Test
    fun resident_engine_closes_the_previous_engine_before_opening_the_next_one_and_reuses_it() {
        val events = mutableListOf<String>()
        val resident = ResidentEngine<RecordingEngine>()
        val first = resident.replace("onnx") { RecordingEngine("close_onnx", events) }!!

        val second = resident.replace("gguf") {
            events += "open_gguf"
            RecordingEngine("close_gguf", events)
        }!!
        val reused = resident.replace("gguf") { error("must reuse the loaded engine") }
        resident.close()

        assertEquals(listOf("close_onnx", "open_gguf", "close_gguf"), events)
        assertSame(second, reused)
        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    private class RecordingEngine(
        private val closeEvent: String,
        private val events: MutableList<String>,
    ) : Closeable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            events += closeEvent
        }
    }
}
