package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressiveCompositionGateTest {
    @Test fun `composing preview is deferred and only latest snapshot is replayed once`() {
        val gate = ProgressiveCompositionGate()
        val first = ProgressivePreviewSnapshot("stable 1", "en cours 1")
        val latest = ProgressivePreviewSnapshot("stable 2", "en cours 2")

        assertNull(gate.offer(first, composing = true, valid = true))
        assertNull(gate.offer(latest, composing = true, valid = true))
        assertNull(gate.replay(composing = true, valid = true))
        assertEquals(latest, gate.replay(composing = false, valid = true))
        assertNull(gate.replay(composing = false, valid = true))
    }

    @Test fun `close or stale request clears deferred composition`() {
        val gate = ProgressiveCompositionGate()
        val snapshot = ProgressivePreviewSnapshot("stable", "draft")

        gate.offer(snapshot, composing = true, valid = true)
        assertNull(gate.replay(composing = false, valid = false))
        gate.offer(snapshot, composing = true, valid = true)
        gate.clear()
        assertNull(gate.replay(composing = false, valid = true))
    }
}
