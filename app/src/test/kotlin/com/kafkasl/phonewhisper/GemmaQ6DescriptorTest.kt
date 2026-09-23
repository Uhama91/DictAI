package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaQ6DescriptorTest {
    @Test
    fun pilotDescriptorMatchesFrozenQ6PartsManifest() {
        val artifact = GemmaModelStore.artifactForPilot(pilot = true)

        assertEquals("gemma4-e2b-v6-1956-q6k-qof16.gguf", artifact.fileName)
        assertEquals(3_931_578_880L, artifact.sizeBytes)
        assertEquals("4d8a18db6843337832cebf3f230183241fc3f83ddf5ffbe9f0c798923a6e3cf4", artifact.sha256)
        assertEquals(
            "gemma4-v6-1956-q6-evaluation-20260920",
            GemmaModelStore.PILOT_RELEASE_TAG,
        )
        assertEquals(
            listOf(1_800_000_000L, 1_800_000_000L, 331_578_880L),
            artifact.parts.map { it.sizeBytes },
        )
        assertEquals(
            listOf(
                "2f9dbab55e950b08c8c8f305ae95e09dcd6fc48b58258d5ab135ace20986b6b2",
                "ba9a0e208d46827c306700692f1614fe6ec4d8cd8428ccfa861656096f368f97",
                "9dcd5cfd8360d724044c3f3ff2671aabf3ffa43c34684fd5110b1cb38993cd91",
            ),
            artifact.parts.map { it.sha256 },
        )
        assertEquals(
            listOf("model.part-000", "model.part-001", "model.part-002"),
            artifact.parts.map { it.url.substringAfterLast('/') },
        )
        assertEquals(artifact.sizeBytes, artifact.parts.sumOf { it.sizeBytes })
    }

    @Test
    fun normalDescriptorRemainsTheHistoricalLiteRtArtifact() {
        val normal = GemmaModelStore.artifactForPilot(pilot = false)

        assertEquals(GemmaModelStore.ARTIFACT, normal)
        assertEquals("gemma-4-E2B-it.litertlm", normal.fileName)
        assertTrue(normal.parts.isEmpty())
        assertEquals("Gemma 4 E2B (FR/EN)", GemmaModelStore.modelTitle(pilot = false))
        assertEquals("gemma-4-E2B-it.litertlm", GemmaModelStore.modelFile(pilot = false))
    }

    @Test
    fun buildVariantSelectsItsDefaultDescriptorAndMetadata() {
        val expected = GemmaModelStore.artifactForBuild(
            gemma4Pilot = BuildConfig.GEMMA4_FINE_TUNED_PILOT,
            gemma3RepairPilot = BuildConfig.GEMMA3_REPAIR_PILOT,
        )

        assertEquals(expected, GemmaModelStore.defaultArtifact())
        assertEquals(expected.fileName, GemmaModelStore.MODEL_FILE)
        assertEquals(expected.sizeBytes, GemmaModelStore.EXPECTED_SIZE_BYTES)
        assertEquals(expected.sha256, GemmaModelStore.MODEL_SHA256)
        assertEquals(
            GemmaModelStore.modelTitleForBuild(BuildConfig.GEMMA4_FINE_TUNED_PILOT, BuildConfig.GEMMA3_REPAIR_PILOT),
            GemmaModelStore.MODEL_TITLE,
        )
    }

    @Test
    fun pilotMetadataUsesTheQ6TitleAndFileWithoutChangingNormalMetadata() {
        assertEquals("Gemma 4 E2B V6 expérimental", GemmaModelStore.modelTitle(pilot = true))
        assertEquals("gemma4-e2b-v6-1956-q6k-qof16.gguf", GemmaModelStore.modelFile(pilot = true))
        assertEquals(3_931_578_880L, GemmaModelStore.expectedSizeBytes(pilot = true))
        assertEquals(
            "4d8a18db6843337832cebf3f230183241fc3f83ddf5ffbe9f0c798923a6e3cf4",
            GemmaModelStore.modelSha256(pilot = true),
        )
        assertNotEquals(GemmaModelStore.modelFile(pilot = true), GemmaModelStore.modelFile(pilot = false))
        assertNotEquals(GemmaModelStore.modelSha256(pilot = true), GemmaModelStore.modelSha256(pilot = false))
    }

    @Test
    fun facadeRouteSelectionIsExplicitAndMutuallyExclusive() {
        assertEquals(LocalFormatEngineRouteKind.CPU_PILOT, localFormatEngineRouteKind(pilot = true))
        assertEquals(LocalFormatEngineRouteKind.GPU_LITERT, localFormatEngineRouteKind(pilot = false))
    }

    @Test
    fun diagnosticNamesTheSelectedPilotArtifactAndKeepsHistoricalGpuName() {
        val pilot = LocalFormatRuntimeLabels.model(pilot = true)
        val normal = LocalFormatRuntimeLabels.model(pilot = false)

        assertTrue(pilot.contains("Gemma 4 E2B V6 expérimental"))
        assertTrue(pilot.contains("gemma4-e2b-v6-1956-q6k-qof16.gguf"))
        assertFalse(pilot.contains(".litertlm"))
        assertEquals(GemmaModelStore.MODEL_FILE, LocalFormatEngine.MODEL_FILE)
        assertEquals(GpuLocalFormatEngine.MODEL_FILE, normal)
    }
}
