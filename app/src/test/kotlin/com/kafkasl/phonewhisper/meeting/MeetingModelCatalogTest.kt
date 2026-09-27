package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MeetingModelCatalogTest {
    @Test
    fun productionCatalogPinsHandyAsrAndTheMatchingVersionedDiarizationArtifact() {
        val catalog = MeetingModelCatalog.production

        assertEquals("v2-asr-6d44e540-diar-f667ed73", catalog.version)
        assertEquals(
            "nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf",
            catalog.asr.relativePath,
        )
        assertEquals(
            "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/" +
                "6d44e540bc31b0de1dbe174a3cea87f53a7f22fb/" +
                "nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf",
            catalog.asr.url,
        )
        assertEquals(751_094_240L, catalog.asr.sizeBytes)
        assertEquals(
            "b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c",
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
        assertEquals(858_106_368L, catalog.totalBytes)
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

    @Test
    fun productionReuseCandidatePathsUseHandyAsrAndOnlyTheV1DiarizationPackage() {
        val privateFilesDirectory = File.createTempFile("meeting-models", "test").apply {
            delete()
            mkdirs()
        }
        try {
            val meetingDirectory = File(privateFilesDirectory, "meeting-models").apply { mkdirs() }
            val handyAsr = File(
                privateFilesDirectory,
                "models/nemotron-3.5-asr-streaming-0.6b-Q8_0/${MeetingModelCatalog.production.asr.relativePath}",
            ).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
            val previousCatalog = MeetingModelCatalog.previousForReuse
            val previousDiarization = File(
                meetingDirectory,
                "${previousCatalog.packageDirectoryName("a1b2c3")}/${previousCatalog.diarization.relativePath}",
            ).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2)) }

            val candidates = MeetingModelReuseCandidateProvider.production.candidates(
                meetingDirectory,
                MeetingModelCatalog.production,
            )

            assertEquals(1, candidates.size)
            assertEquals(handyAsr.canonicalFile, requireNotNull(candidates.single().asrFile).canonicalFile)
            assertEquals(
                previousDiarization.canonicalFile,
                requireNotNull(candidates.single().diarizationFile).canonicalFile,
            )
        } finally {
            privateFilesDirectory.deleteRecursively()
        }
    }

    @Test
    fun productionReuseCandidatesDiscoverEachArtifactIndependently() {
        val handyOnlyRoot = File.createTempFile("meeting-handy-only", "test").apply {
            delete()
            mkdirs()
        }
        val diarizationOnlyRoot = File.createTempFile("meeting-diar-only", "test").apply {
            delete()
            mkdirs()
        }
        try {
            val handyMeetingDirectory = File(handyOnlyRoot, "meeting-models").apply { mkdirs() }
            val handyAsr = File(
                handyOnlyRoot,
                "models/nemotron-3.5-asr-streaming-0.6b-Q8_0/${MeetingModelCatalog.production.asr.relativePath}",
            ).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
            val handyCandidates = MeetingModelReuseCandidateProvider.production.candidates(
                handyMeetingDirectory,
                MeetingModelCatalog.production,
            )
            assertEquals(1, handyCandidates.size)
            assertEquals(handyAsr.canonicalFile, requireNotNull(handyCandidates.single().asrFile).canonicalFile)
            assertEquals(null, handyCandidates.single().diarizationFile)

            val diarMeetingDirectory = File(diarizationOnlyRoot, "meeting-models").apply { mkdirs() }
            val previousCatalog = MeetingModelCatalog.previousForReuse
            val previousDiarization = File(
                diarMeetingDirectory,
                "${previousCatalog.packageDirectoryName("a1b2c3")}/${previousCatalog.diarization.relativePath}",
            ).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2)) }
            val diarCandidates = MeetingModelReuseCandidateProvider.production.candidates(
                diarMeetingDirectory,
                MeetingModelCatalog.production,
            )
            assertEquals(1, diarCandidates.size)
            assertEquals(null, diarCandidates.single().asrFile)
            assertEquals(
                previousDiarization.canonicalFile,
                requireNotNull(diarCandidates.single().diarizationFile).canonicalFile,
            )
        } finally {
            handyOnlyRoot.deleteRecursively()
            diarizationOnlyRoot.deleteRecursively()
        }
    }
}
