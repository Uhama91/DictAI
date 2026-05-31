package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistencePrefsTest {
    @Test fun clamp_keepsInBounds() {
        assertEquals(0, PersistencePrefs.clampX(-50, 100, 1080))
        assertEquals(980, PersistencePrefs.clampX(5000, 100, 1080))
        assertEquals(300, PersistencePrefs.clampX(300, 100, 1080))
    }
}
