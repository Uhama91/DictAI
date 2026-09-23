package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemma3ApkIntegrationTest {
    @Test
    fun gemma3BuildSelectsOnlyTheFrozenV3Artifact() {
        val artifact = GemmaModelStore.artifactForBuild(
            gemma4Pilot = false,
            gemma3RepairPilot = true,
        )

        assertEquals("https://github.com/Uhama91/DictAI/releases/download/gemma270-v3-model/gemma3-270m-postclean-v3-q8_0.gguf", artifact.url)
        assertEquals("gemma3-270m-postclean-v3-q8_0.gguf", artifact.fileName)
        assertEquals(291_545_280L, artifact.sizeBytes)
        assertEquals("6c4b7b6654c9638287c31e50fd0bf849f33a2ff2dd93bee685bdd9632f20ecf5", artifact.sha256)
        assertTrue(artifact.parts.isEmpty())
        assertEquals("Gemma 3 270M V3 expérimental", GemmaModelStore.modelTitleForBuild(false, true))
        assertNotEquals(GemmaModelStore.Q6_ARTIFACT, artifact)
    }

    @Test
    fun gemmaModelAndEngineRoutesAreExplicitAndMutuallyExclusive() {
        assertEquals(
            GemmaModelStore.Q6_ARTIFACT,
            GemmaModelStore.artifactForBuild(gemma4Pilot = true, gemma3RepairPilot = false),
        )
        assertEquals(
            GemmaModelStore.ARTIFACT,
            GemmaModelStore.artifactForBuild(gemma4Pilot = false, gemma3RepairPilot = false),
        )
        assertEquals(
            LocalFormatEngineRouteKind.CPU_GEMMA3_REPAIR,
            localFormatEngineRouteKind(gemma4Pilot = false, gemma3RepairPilot = true),
        )
        assertEquals(
            LocalFormatEngineRouteKind.CPU_PILOT,
            localFormatEngineRouteKind(gemma4Pilot = true, gemma3RepairPilot = false),
        )
        assertEquals(
            LocalFormatEngineRouteKind.GPU_LITERT,
            localFormatEngineRouteKind(gemma4Pilot = false, gemma3RepairPilot = false),
        )
        assertThrows(IllegalArgumentException::class.java) {
            artifactForBuildForTest(gemma4Pilot = true, gemma3RepairPilot = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            localFormatEngineRouteKind(gemma4Pilot = true, gemma3RepairPilot = true)
        }
    }

    @Test
    fun gemma3EligibilityIsLimitedToTheFrenchCorrectedTextPreset() {
        val corrected = PostProcessingFormats.builtins.single { it.id == "corrected" }
        val list = PostProcessingFormats.builtins.single { it.id == "list" }
        val email = PostProcessingFormats.builtins.single { it.id == "email" }
        val customText = PostProcessingFormat("custom", "Texte perso", "Consigne personnelle")

        assertEquals(Gemma3RepairRefusal.NONE, gemma3RepairRefusal(corrected, DictationLanguage.FRENCH))
        assertEquals(Gemma3RepairRefusal.UNSUPPORTED_FORMAT, gemma3RepairRefusal(list, DictationLanguage.FRENCH))
        assertEquals(Gemma3RepairRefusal.UNSUPPORTED_FORMAT, gemma3RepairRefusal(email, DictationLanguage.FRENCH))
        assertEquals(Gemma3RepairRefusal.UNSUPPORTED_FORMAT, gemma3RepairRefusal(customText, DictationLanguage.FRENCH))
        assertEquals(Gemma3RepairRefusal.UNSUPPORTED_LANGUAGE, gemma3RepairRefusal(corrected, DictationLanguage.ENGLISH))
    }

    @Test
    fun gemma3RequestKeepsTheFrenchFinalContractAndDoesNotEraseContext() {
        val source = "On conserve les chiffres 23 et la négation ne pas."
        val context = "préfixe corrigé par la personne"
        val request = gemma3FinalLocalFormatRequest(
            source = source,
            language = DictationLanguage.FRENCH,
            protectedTerms = listOf("Maëlys", "[[Image 1]]"),
            contextBefore = context,
        )

        assertEquals(source, request.text)
        assertEquals("", request.instructions)
        assertEquals("français", request.language)
        assertEquals(listOf("Maëlys", "[[Image 1]]"), request.protectedTerms)
        assertEquals(LocalLayoutKind.TEXT, request.layoutKind)
        assertEquals(LocalFormatValidation.GEMMA3_CONTEXTUAL, request.validation)
        assertFalse(request.simpleEmailLayout)
        assertEquals(GemmaFineTunedPrompt.Phase.FINAL, request.phase)
        assertEquals(context, request.contextBefore)
    }

    @Test
    fun sixGemma3LatencyRequestsUseTheSameFinalOnlyFrenchContract() {
        val requests = gemma3FinalLatencyBenchmarkRequests()

        assertEquals(6, requests.size)
        requests.forEach { request ->
            assertEquals("français", request.language)
            assertEquals("", request.instructions)
            assertEquals(LocalLayoutKind.TEXT, request.layoutKind)
            assertEquals(LocalFormatValidation.GEMMA3_CONTEXTUAL, request.validation)
            assertEquals(GemmaFineTunedPrompt.Phase.FINAL, request.phase)
            assertFalse(request.simpleEmailLayout)
            assertEquals("", request.contextBefore)
        }
        assertTrue(requests.all { it.text.isNotBlank() })
    }

    private fun artifactForBuildForTest(gemma4Pilot: Boolean, gemma3RepairPilot: Boolean) =
        GemmaModelStore.artifactForBuild(gemma4Pilot, gemma3RepairPilot)
}
