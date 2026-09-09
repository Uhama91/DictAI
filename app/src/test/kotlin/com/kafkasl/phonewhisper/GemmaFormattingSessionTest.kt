package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class GemmaFormattingSessionTest {
    private val request = LocalFormatRequest(
        "Bonjour Iris ne modifiez pas les 12 dossiers cordialement Malik",
        "Mail", "French", listOf("Iris", "Malik"), LocalLayoutKind.EMAIL,
        LocalFormatValidation.GEMMA_PROJECTION,
    )

    private fun backend(output: String) = object : LocalFormatBackend {
        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String = output
        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit, onNativeStart: () -> Unit): String {
            onNativeStart()
            onChunk(output)
            return output
        }
        override fun cancel() = Unit
    }

    @Test fun finalizationAppliesGemmaProjectionAndKeepsSourceNames() {
        val candidate = "Bonjour IRIS,\n\nNe modifiez pas les 12 dossiers.\n\nCordialement,\nMALIK"
        LocalFormattingSession(backend(candidate)).use { session ->
            val result = session.finish(request, 2_000L) {}
            assertEquals("Bonjour Iris,\n\nne modifiez pas les 12 dossiers.\n\ncordialement,\nMalik", result)
            assertEquals("applied", session.lastFinish?.outcome)
            assertEquals(true, session.lastFinish?.nativeStarted)
        }
    }

    @Test fun finalizationRejectsCompleteButUnfaithfulGemmaOutput() {
        val candidate = "Bonjour Iris,\n\nModifiez les 12 dossiers.\n\nCordialement,\nMalik"
        LocalFormattingSession(backend(candidate)).use { session ->
            assertNull(session.finish(request, 2_000L) {})
            assertEquals("fidelity_rejected", session.lastFinish?.outcome)
            assertEquals(true, session.lastFinish?.nativeStarted)
        }
    }
}
