package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStartGateTest {
    @Test
    fun `matching loaded engine starts recording`() {
        assertEquals(
            RecordingStartGate.Decision.START,
            RecordingStartGate.decide(
                localLoading = false,
                selectedModel = "model",
                loadedModel = "model",
                hasAsrEngine = true,
            ),
        )
    }

    @Test
    fun `loading engine blocks recording`() {
        assertEquals(
            RecordingStartGate.Decision.LOADING,
            RecordingStartGate.decide(
                localLoading = true,
                selectedModel = "model",
                loadedModel = "model",
                hasAsrEngine = true,
            ),
        )
    }

    @Test
    fun `changed selected model requires a reload`() {
        assertEquals(
            RecordingStartGate.Decision.RELOAD_REQUIRED,
            RecordingStartGate.decide(
                localLoading = false,
                selectedModel = "new-model",
                loadedModel = "old-model",
                hasAsrEngine = true,
            ),
        )
    }

    @Test
    fun `missing unified engine is unavailable`() {
        assertEquals(
            RecordingStartGate.Decision.UNAVAILABLE,
            RecordingStartGate.decide(
                localLoading = false,
                selectedModel = "model",
                loadedModel = "model",
                hasAsrEngine = false,
            ),
        )
    }
}
