package com.kafkasl.phonewhisper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayExportControllerTest {
    @Test fun `a newer export invalidates an older generation`() {
        val gate = ExportGenerationGate()
        val first = gate.next()
        val second = gate.next()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun `closing export invalidates the current generation`() {
        val gate = ExportGenerationGate()
        val token = gate.next()

        gate.invalidate()

        assertFalse(gate.isCurrent(token))
    }

    @Test fun `bridge restore waits for resume and window focus in either order`() {
        val gate = OverlayBridgeReturnGate()

        gate.onWindowFocusChanged(true)
        gate.request()
        assertFalse(gate.consumeIfReady())

        gate.onResume()
        assertTrue(gate.consumeIfReady())
        assertFalse(gate.consumeIfReady())

        gate.onPause()
        gate.request()
        assertFalse(gate.consumeIfReady())
        gate.onResume()
        assertTrue(gate.consumeIfReady())
    }

    @Test fun `bridge request made while foreground is consumed once`() {
        val gate = OverlayBridgeReturnGate()
        gate.onResume()
        gate.onWindowFocusChanged(true)

        gate.request()

        assertTrue(gate.isPending())
        assertTrue(gate.consumeIfReady())
        assertFalse(gate.isPending())
        assertFalse(gate.consumeIfReady())
    }

    @Test fun `bridge remains pending across background until both signals return`() {
        val gate = OverlayBridgeReturnGate()
        gate.onResume()
        gate.onWindowFocusChanged(true)
        gate.onPause()
        gate.onWindowFocusChanged(false)
        gate.request()

        gate.onWindowFocusChanged(true)
        assertFalse(gate.consumeIfReady())
        gate.onResume()
        assertTrue(gate.consumeIfReady())
    }
}
