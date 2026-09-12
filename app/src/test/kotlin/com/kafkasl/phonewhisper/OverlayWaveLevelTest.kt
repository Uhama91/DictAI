package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWaveLevelTest {
    @Test
    fun visualLevelIsMonotoneAndKeepsHeadroomAboveOrdinarySpeech() {
        val quiet = visualWaveLevelFromRms(0.008)
        val weak = visualWaveLevelFromRms(0.02)
        val ordinary = visualWaveLevelFromRms(0.06)
        val loud = visualWaveLevelFromRms(0.15)

        assertEquals(0f, quiet, 0.0001f)
        assertTrue("Noise immediately above the floor should fade in smoothly", visualWaveLevelFromRms(0.0081) < 0.05f)
        assertTrue("Visual response must remain monotone", weak > quiet && ordinary > weak && loud > ordinary)
        assertTrue("A weak syllable should remain visibly sensitive", weak > 0.55f)
        assertTrue("Ordinary speech should retain headroom for stronger syllables", ordinary < 0.93f)
        assertTrue("Speech around -24 dBFS must not saturate", visualWaveLevelFromRms(0.0625) < 1f)
        assertTrue("Loud speech should still leave a measurable path to full scale", visualWaveLevelFromRms(0.22) < 1f)
        assertEquals(1f, visualWaveLevelFromRms(0.24), 0.0001f)
        assertEquals(1f, visualWaveLevelFromRms(2.0), 0.0001f)
    }
}
