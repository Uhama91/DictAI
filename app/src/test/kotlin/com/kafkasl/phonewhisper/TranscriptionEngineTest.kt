package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
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
}
