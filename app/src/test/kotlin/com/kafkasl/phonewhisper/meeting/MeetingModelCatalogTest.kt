package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingModelCatalogTest {
    @Test
    fun productionCatalogPinsBothVersionedHuggingFaceArtifacts() {
        val catalog = MeetingModelCatalog.production

        assertEquals(
            "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/" +
                "1c8deaecc64b91f034d73e08dd8b64625eb3395d/" +
                "nemotron-3.5-asr-streaming-0.6b.q8_0.gguf",
            catalog.asr.url,
        )
        assertEquals(741_548_352L, catalog.asr.sizeBytes)
        assertEquals(
            "a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae",
            catalog.asr.sha256,
        )
        assertEquals(
            "https://huggingface.co/nvidia/Nemotron-3-Diarization/resolve/" +
                "f667ed73aee57d40cc39428eb768b4fd87a0a29e/" +
                "Nemotron-3-Diarization.q8_0.gguf",
            catalog.diarization.url,
        )
        assertEquals(107_012_128L, catalog.diarization.sizeBytes)
        assertEquals(
            "08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1",
            catalog.diarization.sha256,
        )
        assertEquals(848_560_480L, catalog.totalBytes)
        assertTrue(catalog.packageName.contains("meeting"))
        assertFalse(catalog.packageName.contains("handy"))
        assertEquals(2, catalog.artifacts.size)
    }

    @Test
    fun artifactPathsCannotEscapeTheirPackageDirectory() {
        listOf("../outside.gguf", "/absolute.gguf", "folder/../../outside.gguf", "folder\\outside.gguf")
            .forEach { unsafePath ->
                val error = runCatching {
                    MeetingModelArtifact(
                        id = "test",
                        relativePath = unsafePath,
                        url = "https://models.example/test.gguf",
                        sizeBytes = 1,
                        sha256 = "0".repeat(64),
                    )
                }.exceptionOrNull()
                assertTrue("Expected unsafe path to be rejected: $unsafePath", error is IllegalArgumentException)
            }
    }
}
