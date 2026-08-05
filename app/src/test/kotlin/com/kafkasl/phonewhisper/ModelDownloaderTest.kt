package com.kafkasl.phonewhisper

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.roundToInt

class ModelDownloaderTest {

    @Test fun `whole percent publisher suppresses repeated percentages`() {
        val reported = mutableListOf<Float>()
        val publisher = WholePercentProgressPublisher { reported += it }

        publisher.publish(0f)
        publisher.publish(0.001f)
        publisher.publish(0.009f)
        publisher.publish(0.01f)
        publisher.publish(0.019f)

        assertEquals(listOf(0f, 0.01f), reported)
    }

    @Test fun `whole percent publisher publishes large progress jumps without filling gaps`() {
        val reported = mutableListOf<Float>()
        val publisher = WholePercentProgressPublisher { reported += it }

        publisher.publish(0f)
        publisher.publish(0.43f)
        publisher.publish(0.97f)
        publisher.publish(1f)

        assertEquals(listOf(0f, 0.43f, 0.97f, 1f), reported)
    }

    @Test fun `whole percent publisher emits start and final completion once`() {
        val reported = mutableListOf<Float>()
        val publisher = WholePercentProgressPublisher { reported += it }

        publisher.publish(0f)
        publisher.complete()
        publisher.complete()

        assertEquals(listOf(0f, 1f), reported)
    }

    @Test fun `whole percent publisher bounds and never regresses`() {
        val reported = mutableListOf<Float>()
        val publisher = WholePercentProgressPublisher { reported += it }

        publisher.publish(-1f)
        publisher.publish(0.25f)
        publisher.publish(0.20f)
        publisher.publish(5f)

        assertEquals(listOf(0f, 0.25f, 1f), reported)
        assertTrue(reported.all { it in 0f..1f })
        assertTrue(reported.zipWithNext().all { (previous, next) -> next >= previous })
    }

    @Test fun `extracts model assets while skipping readme and test wav files`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            val outDir = File(tmp, "out")

            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "hello\nworld",
                "mymodel/encoder.onnx" to "fake-onnx-data",
                "mymodel/README.md" to "documentation",
                "mymodel/test_wavs/sample.wav" to "test audio",
            ))

            ModelDownloader.extractTarBz2(archive, outDir)

            assertTrue(File(outDir, "mymodel").isDirectory)
            assertEquals("hello\nworld", File(outDir, "mymodel/tokens.txt").readText())
            assertEquals("fake-onnx-data", File(outDir, "mymodel/encoder.onnx").readText())
            assertFalse(File(outDir, "mymodel/README.md").exists())
            assertFalse(File(outDir, "mymodel/test_wavs/sample.wav").exists())
        }
    }

    @Test fun `rejects path traversal`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `rejects traversal into a sibling whose path shares the output prefix`() {
        withTempDir { tmp ->
            val archive = File(tmp, "prefix-sibling.tar.bz2")
            val outDir = File(tmp, "out")
            writeTarBz2(archive, mapOf("../out-sibling/evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, outDir)
            }

            assertFalse(File(tmp, "out-sibling/evil.txt").exists())
        }
    }

    @Test fun `reports monotone extraction progress at whole percent boundaries`() {
        withTempDir { tmp ->
            val archive = File(tmp, "progress.tar.bz2")
            val reported = mutableListOf<Float>()
            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "tokens",
                "mymodel/encoder.onnx" to "x".repeat(128 * 1024),
            ))

            ModelDownloader.extractTarBz2(archive, File(tmp, "out")) { reported += it }

            assertEquals(0f, reported.first())
            assertEquals(1f, reported.last())
            assertTrue(reported.zipWithNext().all { (previous, next) -> next >= previous })
            assertTrue(reported.all { it in 0f..1f && it * 100 == (it * 100).roundToInt().toFloat() })
            assertEquals(reported.size, reported.distinct().size)
        }
    }

    @Test fun `maps installation progress monotonically across download and extraction`() {
        val progress = listOf(
            DownloadState.Downloading(-1f),
            DownloadState.Downloading(0f),
            DownloadState.Downloading(1f),
            DownloadState.Extracting(0f),
            DownloadState.Extracting(1f),
            DownloadState.Extracting(2f),
        ).map(::installationProgress)

        assertEquals(0f, progress.first())
        assertEquals(0.5f, progress[2])
        assertEquals(0.5f, progress[3])
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (previous, next) -> next >= previous })
    }

    @Test fun `direct download maps its complete range to installation progress`() {
        val progress = listOf(-1f, 0f, 0.5f, 1f, 2f).map { value ->
            installationProgress(DownloadState.Downloading(value, isDirect = true))
        }

        assertEquals(listOf(0f, 0f, 0.5f, 1f, 1f), progress)
    }

    @Test fun `downloader marks progress as direct only when an artifact is direct`() {
        val directArtifact = MODEL_CATALOG.single { it.runtimeType == RuntimeModelType.GGUF }.directArtifact

        val directState = ModelDownloader.downloadingState(0.75f, directArtifact)
        val archiveState = ModelDownloader.downloadingState(0.75f, null)

        assertEquals(0.75f, installationProgress(directState))
        assertEquals(0.375f, installationProgress(archiveState))
    }

    @Test fun `rejects a corrupted bz2 archive`() {
        withTempDir { tmp ->
            val archive = File(tmp, "corrupt.tar.bz2")
            archive.writeText("not a bzip2 archive")

            assertThrows(java.io.IOException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `catalog includes the experimental Nemotron GGUF model`() {
        assertEquals(6, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.any {
            it.name == "Nemotron 3.5 GGUF (FR)" && it.runtimeType.name == "GGUF"
        })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
    }

    @Test fun `catalog pins the Nemotron GGUF direct artifact`() {
        val model = MODEL_CATALOG.single { it.name == "Nemotron 3.5 GGUF (FR)" }
        val artifact = model.directArtifact

        assertNotNull("Le modèle GGUF doit utiliser un artefact direct.", artifact)
        assertEquals(
            "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/6d44e540bc31b0de1dbe174a3cea87f53a7f22fb/nemotron-3.5-asr-streaming-0.6b-Q6_K.gguf",
            artifact!!.url,
        )
        assertEquals("nemotron-3.5-asr-streaming-0.6b-Q6_K.gguf", artifact.fileName)
        assertEquals(621356512L, artifact.expectedSizeBytes)
        assertEquals(
            "4ff802c6207c4a7df23242003fd2aa849a1ab02bba6bc80c3db02e7e82606c28",
            artifact.sha256,
        )
    }

    @Test fun `direct publication verifies size and SHA before atomically exposing the GGUF`() {
        withTempDir { tmp ->
            val content = "valid GGUF payload".toByteArray()
            val model = directTestModel(content)
            val finalDir = File(tmp, "models/${model.archive}")
            val staging = File(tmp, "staging")
            val part = File(tmp, "download.part").apply { writeBytes(content) }

            assertFalse(finalDir.exists())
            assertTrue(ModelDownloader.publishDirectArtifact(part, staging, finalDir, model))
            assertTrue(ModelStorage.isValidModelDirectory(finalDir, model))

            val alteredPart = File(tmp, "altered.part").apply { writeText("altered") }
            assertFalse(ModelDownloader.publishDirectArtifact(alteredPart, File(tmp, "altered-staging"), finalDir, model))
            assertTrue(ModelStorage.isValidModelDirectory(finalDir, model))
        }
    }

    @Test fun `direct download progress is monotone and bounded`() {
        val progress = listOf(0L, 1L, 512L, 512L, 1024L, 2048L)
            .map { downloaded -> ModelDownloader.directDownloadProgress(downloaded, 1024L) }

        assertEquals(0f, progress.first())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (previous, next) -> next >= previous })
        assertTrue(progress.all { it in 0f..1f })
    }

    // -- helpers --

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun writeTarBz2(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(file))).use { tar ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun directTestModel(content: ByteArray): Model {
        val catalogModel = MODEL_CATALOG.single { it.runtimeType == RuntimeModelType.GGUF }
        return catalogModel.copy(
            directArtifact = catalogModel.directArtifact!!.copy(
                expectedSizeBytes = content.size.toLong(),
                sha256 = content.sha256(),
            )
        )
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
}
