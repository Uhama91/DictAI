package com.kafkasl.phonewhisper

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.math.roundToInt

class ModelDownloaderTest {

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

    @Test fun `rejects a corrupted bz2 archive`() {
        withTempDir { tmp ->
            val archive = File(tmp, "corrupt.tar.bz2")
            archive.writeText("not a bzip2 archive")

            assertThrows(java.io.IOException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `catalog has expected structure`() {
        assertEquals(5, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.all { it.archive.startsWith("sherpa-onnx-") })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
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
}
