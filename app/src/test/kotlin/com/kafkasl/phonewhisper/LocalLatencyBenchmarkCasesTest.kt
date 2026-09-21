package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLatencyBenchmarkCasesTest {
    @Test
    fun corpusHasSixSyntheticFrenchTextCasesWithTwoCasesPerLengthBucket() {
        assertEquals(6, LocalLatencyBenchmarkCases.all.size)
        assertEquals(
            mapOf(
                LocalLatencyBucket.SHORT to 2,
                LocalLatencyBucket.MEDIUM to 2,
                LocalLatencyBucket.LONG to 2,
            ),
            LocalLatencyBenchmarkCases.all.groupingBy { it.bucket }.eachCount(),
        )
        assertEquals(6, LocalLatencyBenchmarkCases.all.map { it.id }.toSet().size)
        assertTrue(LocalLatencyBenchmarkCases.all.all { it.source.isNotBlank() })
        assertTrue(LocalLatencyBenchmarkCases.all.all { it.language == "French" })
        assertTrue(LocalLatencyBenchmarkCases.all.all { it.request.layoutKind == LocalLayoutKind.TEXT })
    }

    @Test
    fun wordCountsStayInsideDeclaredShortMediumAndLongRanges() {
        LocalLatencyBenchmarkCases.all.forEach { case ->
            val words = case.source.trim().split(Regex("\\s+")).size
            val range = when (case.bucket) {
                LocalLatencyBucket.SHORT -> 20..40
                LocalLatencyBucket.MEDIUM -> 70..110
                LocalLatencyBucket.LONG -> 150..210
            }
            assertTrue("${case.id} has $words words, expected $range", words in range)
        }
    }

    @Test
    fun everyRequestUsesTheActualFinalGemmaEditingPathAndCannotBypassTheModel() {
        LocalLatencyBenchmarkCases.all.forEach { case ->
            val request = case.request
            assertEquals(LocalFormatValidation.GEMMA_EDITING, request.validation)
            assertFalse(request.simpleEmailLayout)
            assertEquals(GemmaFineTunedPrompt.Phase.FINAL, request.phase)
            assertEquals("French", request.language)
            assertEquals(PostProcessingFormats.builtins.first { it.id == "corrected" }.instructions, request.instructions)
            assertNull("${case.id} must reach the model", request.directOutput())
            assertNull(request.acceptOutput(null))
        }
    }

    @Test
    fun threePassPlanIsPartOfTheCaseContract() {
        assertEquals(3, LocalLatencyBenchmarkCases.PASS_COUNT)
        assertEquals(setOf("latency-1", "latency-2", "latency-3", "latency-4", "latency-5", "latency-6"),
            LocalLatencyBenchmarkCases.all.map { it.id }.toSet())
    }

    @Test
    fun latencySummarySeparatesAcceptedFallbackMissingAndDirectOutcomes() {
        val source = "Le texte de référence."
        val unchanged = classifyLocalLatencyOutcome(source, source, source, null, direct = false)
        val modified = classifyLocalLatencyOutcome(source, source, "Le texte de référence !", null, direct = false)
        val rejected = classifyLocalLatencyOutcome(source, source, null, null, direct = false)
        val absent = classifyLocalLatencyOutcome(source, null, null, null, direct = false)
        val direct = classifyLocalLatencyOutcome(source, source, source, null, direct = true)

        assertEquals(LocalLatencyDisposition.ACCEPTED_UNCHANGED, unchanged.disposition)
        assertEquals(LocalLatencyDisposition.ACCEPTED_MODIFIED, modified.disposition)
        assertEquals(LocalLatencyDisposition.FALLBACK_SOURCE, rejected.disposition)
        assertEquals("sortie_absente", absent.failureCode)
        assertTrue(unchanged.complete)
        assertTrue(direct.direct)
        assertFalse(absent.complete)
    }

    @Test
    fun latencyAggregateExcludesDirectRejectedAndAbsentFromAcceptedDurations() {
        val observations = listOf(
            LocalLatencyObservation(40, 12, true, LocalLatencyDisposition.ACCEPTED_MODIFIED, false),
            LocalLatencyObservation(70, 18, true, LocalLatencyDisposition.FALLBACK_SOURCE, false),
            LocalLatencyObservation(0, null, false, LocalLatencyDisposition.FALLBACK_SOURCE, false),
            LocalLatencyObservation(5, 2, true, LocalLatencyDisposition.ACCEPTED_UNCHANGED, true),
        )

        val summary = summarizeLocalLatencyObservations(observations)

        assertEquals(listOf(40L, 70L), summary.completeDurationsMs)
        assertEquals(listOf(40L), summary.acceptedDurationsMs)
        assertEquals(listOf(12L, 18L), summary.completeFirstFragmentsMs)
        assertEquals(listOf(12L), summary.acceptedFirstFragmentsMs)
        assertEquals(1, summary.directCount)
        assertEquals(1, summary.incompleteCount)
    }

    @Test
    fun medianLatencyUsesTheMeanOfTheTwoCentralValues() {
        assertEquals(15L, medianLatencyMs(listOf(10L, 20L)))
        assertEquals(20L, medianLatencyMs(listOf(10L, 20L, 30L)))
    }
}
