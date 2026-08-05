package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistencePrefsTest {
    @Test fun clamp_keepsInBounds() {
        assertEquals(0, PersistencePrefs.clampX(-50, 100, 1080))
        assertEquals(980, PersistencePrefs.clampX(5000, 100, 1080))
        assertEquals(300, PersistencePrefs.clampX(300, 100, 1080))
    }

    @Test fun `legacy coordinates migrate to a persisted edge anchor`() {
        val anchor = PersistencePrefs.migrateLegacyAnchor(1006, 900, 74, 44, 1080, 1920)

        assertEquals(Edge.RIGHT, anchor.edge)
        assertEquals(900f / 1876f, anchor.offset, .0001f)
    }

    @Test fun `invalid saved edge is rejected so legacy migration can run`() {
        assertEquals(null, PersistencePrefs.edgeFromPreference("diagonal"))
        assertEquals(Edge.BOTTOM, PersistencePrefs.edgeFromPreference("BOTTOM"))
    }
}
