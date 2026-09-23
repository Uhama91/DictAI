package com.kafkasl.phonewhisper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFormatRuntimeLabelsTest {
    @Test fun pilotBenchmarkLabelsPrimaryCpuGreedyRuntimeWithoutFallbackLanguage() {
        val configuration = LocalFormatRuntimeLabels.configuration(pilot = true)
        val calculation = LocalFormatRuntimeLabels.calculation(pilot = true, runtime = "arm64-baseline")
        val failure = LocalFormatRuntimeLabels.failure(pilot = true, runtime = "cpu-error", code = "generation_error")

        assertTrue(configuration.contains("CPU · llama.cpp · greedy"))
        assertTrue(configuration.contains("thinking désactivé"))
        assertFalse(configuration.contains("repli"))
        assertTrue(calculation.contains("CPU · llama.cpp · greedy · arm64-baseline"))
        assertTrue(failure.startsWith("État CPU : cpu-error"))
        assertTrue(failure.contains("generation_error"))
        assertFalse(failure.contains("État GPU"))
    }

    @Test fun historicalBenchmarkLabelsRemainGpuMtpAndUseExistingModelName() {
        val configuration = LocalFormatRuntimeLabels.configuration(pilot = false)
        val calculation = LocalFormatRuntimeLabels.calculation(pilot = false, runtime = "litert-lm-gpu-mtp-thinking-off")

        assertTrue(configuration.contains("LiteRT-LM 0.17.0 · GPU · MTP activé"))
        assertTrue(calculation.contains("GPU · LiteRT-LM · MTP"))
        assertTrue(LocalFormatRuntimeLabels.model(pilot = false).endsWith(".litertlm"))
        assertFalse(configuration.contains("CPU · llama.cpp"))
    }

    @Test fun pilotModelLabelNamesTheFrozenQ6Descriptor() {
        val model = LocalFormatRuntimeLabels.model(pilot = true)

        assertTrue(model.contains("Gemma 4 E2B V6 expérimental"))
        assertTrue(model.contains("gemma4-e2b-v6-1956-q6k-qof16.gguf"))
        assertFalse(model.endsWith(".litertlm"))
    }

    @Test fun cacheWordingDistinguishesCpuPilotFromHistoricalGpuBenchmark() {
        assertTrue(LocalFormatRuntimeLabels.cacheNote(pilot = true).contains("système/CPU"))
        assertFalse(LocalFormatRuntimeLabels.cacheNote(pilot = true).contains("GPU"))
        assertTrue(LocalFormatRuntimeLabels.cacheNote(pilot = false).contains("système/GPU"))
        assertTrue(LocalFormatRuntimeLabels.pssNote(pilot = false).contains("mémoire GPU partagée potentiellement exclue"))
        assertFalse(LocalFormatRuntimeLabels.pssNote(pilot = true).contains("mémoire GPU"))
    }

    @Test fun gemma3RuntimeLabelsNameTheFrozenV3WeightsAndTheThreeSecondCpuLimit() {
        val model = LocalFormatRuntimeLabels.model(pilot = false, gemma3RepairPilot = true)
        val configuration = LocalFormatRuntimeLabels.configuration(pilot = false, gemma3RepairPilot = true)
        val calculation = LocalFormatRuntimeLabels.calculation(
            pilot = false,
            gemma3RepairPilot = true,
            runtime = "arm64-baseline",
        )

        assertTrue(model.contains("Gemma 3 270M V3 expérimental"))
        assertTrue(model.contains("gemma3-270m-postclean-v3-q8_0.gguf"))
        assertTrue(configuration.contains("CPU · llama.cpp · greedy"))
        assertTrue(configuration.contains("3000 ms"))
        assertTrue(calculation.contains("CPU · llama.cpp · greedy"))
        assertTrue(calculation.contains("arm64-baseline"))
        assertFalse(model.contains("Gemma 4"))
        assertFalse(configuration.contains("GPU"))
    }
}
