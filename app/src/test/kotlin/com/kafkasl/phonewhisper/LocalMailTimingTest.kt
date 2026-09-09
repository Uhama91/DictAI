package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class LocalMailTimingTest {
    private fun mail(text: String) = LocalFormatRequest(text, "Mail", "French", layoutKind = LocalLayoutKind.EMAIL,
        validation = LocalFormatValidation.GEMMA_PROJECTION, simpleEmailLayout = true)

    @Test fun longConventionalMailUsesTheModelAndCanFinishJustBeyondFiveSeconds() {
        val request = mail(SimpleEmailLayoutTest.LONG_MAIL)
        assertNull(request.directOutput())
        var calls = 0
        val backend = object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? {
                calls++
                Thread.sleep(5_150L)
                return SimpleEmailLayout.format(request.text)
            }
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit, onNativeStart: () -> Unit): String? {
                onNativeStart()
                return generate(request, onChunk)
            }
            override fun cancel() = Unit
        }
        LocalFormattingSession(backend).use { session ->
            assertNotNull(session.finish(request, request.finalWaitMs()) {})
            assertEquals(1, calls)
            assertEquals("applied", session.lastFinish?.outcome)
            assertEquals(true, session.lastFinish?.nativeStarted)
            assertEquals(8_000L, session.lastFinish?.waitLimitMs)
            assertTrue(session.lastFinish!!.waitMs >= 5_000L)
        }
    }

    @Test fun mailMarginDoesNotIncreaseListWaitOrDelayDirectAcknowledgments() {
        val request = mail("OK, ça marche.")
        assertEquals(8_000L, request.finalWaitMs())
        assertEquals(request.text, request.directOutput())
        assertEquals(5_000L, request.copy(layoutKind = LocalLayoutKind.LIST).finalWaitMs())
        assertFalse(mail("mot ".repeat(59)).isLongEmail())
        assertTrue(mail("mot\u00a0".repeat(60)).isLongEmail())
    }

    @Test fun reportSeparatesWaitGenerationAndValidationAndShowsSmallOvershoot() {
        val report = LocalGenerationTiming(10, 1300, 5136, 4, true).report()
        assertTrue(report.contains("Appel natif → retour moteur : 5126 ms"))
        assertTrue(report.contains("Validation du texte : 4 ms"))
        assertTrue(report.contains("Écart au seuil de 5000 ms : +140 ms"))
        assertTrue(report.contains("Écart au seuil de 8000 ms : -2860 ms"))
    }

    @Test fun interruptedBenchmarkDoesNotInventTheUnobservedCompletionTime() {
        val report = LocalGenerationTiming(20, 1400, 20_003, 0, false).report()
        assertTrue(report.contains("Attente observée : 20003 ms"))
        assertTrue(report.contains("Durée nécessaire pour terminer : inconnue"))
        assertFalse(report.contains("Écart au seuil"))
        assertFalse(report.contains("Retour du moteur depuis"))
    }
}
