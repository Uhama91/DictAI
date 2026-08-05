package com.kafkasl.phonewhisper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelStorageTest {
    @Test fun `accepts every offline layout currently supported by runtime`() = withTempDir { tmp ->
        assertTrue(ModelStorage.isValidModelDirectory(transducer(tmp, ".onnx")))
        assertTrue(ModelStorage.isValidModelDirectory(transducer(File(tmp, "ort"), ".ort")))
        assertTrue(ModelStorage.isValidModelDirectory(whisper(File(tmp, "whisper"))))
        assertTrue(ModelStorage.isValidModelDirectory(moonshine(File(tmp, "moonshine"))))
        assertTrue(ModelStorage.isValidModelDirectory(ctc(File(tmp, "ctc"))))
    }

    @Test fun `rejects folders that cannot be loaded by the runtime`() = withTempDir { tmp ->
        val emptyDir = File(tmp, "empty").apply { mkdirs() }
        val emptyTokens = transducer(File(tmp, "empty-tokens"), ".onnx")
        File(emptyTokens, "tokens.txt").writeText("")
        val missingJoiner = transducer(File(tmp, "missing-joiner"), ".onnx")
        File(missingJoiner, "joiner.int8.onnx").delete()
        val emptyEncoder = transducer(File(tmp, "empty-encoder"), ".onnx")
        File(emptyEncoder, "encoder.int8.onnx").writeText("")

        assertFalse(ModelStorage.isValidModelDirectory(emptyDir))
        assertFalse(ModelStorage.isValidModelDirectory(emptyTokens))
        assertFalse(ModelStorage.isValidModelDirectory(missingJoiner, RuntimeModelType.TRANSDUCER))
        assertFalse(ModelStorage.isValidModelDirectory(emptyEncoder))
    }

    @Test fun `accepts exactly one non-empty GGUF without tokens`() = withTempDir { tmp ->
        val artifact = MODEL_CATALOG.single { it.runtimeType == RuntimeModelType.GGUF }.directArtifact!!
        val valid = gguf(File(tmp, "valid"), artifact.fileName)
        val multiple = gguf(File(tmp, "multiple"), artifact.fileName).also {
            File(it, "another.gguf").writeText("another model")
        }

        assertTrue(ModelStorage.isValidModelDirectory(valid, RuntimeModelType.GGUF))
        assertEquals(RuntimeModelType.GGUF, ModelStorage.inspectModelDirectory(valid)?.type)
        assertFalse(ModelStorage.isValidModelDirectory(multiple, RuntimeModelType.GGUF))
    }

    @Test fun `GGUF requires the catalogued artifact name`() = withTempDir { tmp ->
        val model = MODEL_CATALOG.single { it.runtimeType == RuntimeModelType.GGUF }
        val alternate = gguf(File(tmp, "alternate"), "nemotron-altered.gguf")

        assertFalse(ModelStorage.isValidModelDirectory(alternate, model))
    }

    @Test fun `GGUF without an integrity marker is not installed`() = withTempDir { tmp ->
        val model = directTestModel("GGUF")
        val dir = gguf(File(tmp, "gguf"), model.directArtifact!!.fileName)

        assertFalse(ModelStorage.isValidModelDirectory(dir, model))
    }

    @Test fun `ordinary GGUF validation uses the marker without hashing the payload`() = withTempDir { tmp ->
        val contents = "GGUF"
        val model = directTestModel(contents)
        val finalDir = publishDirectModel(tmp, model, contents)
        val artifact = model.directArtifact!!
        val gguf = File(finalDir, artifact.fileName)
        val publishedMtime = gguf.lastModified()
        val markerFiles = finalDir.listFiles().orEmpty().filterNot { it.name == artifact.fileName }

        assertEquals(1, markerFiles.size)
        assertFalse(markerFiles.single().name.endsWith(".part"))
        gguf.writeText("BORK")
        assertTrue(gguf.setLastModified(publishedMtime))

        assertTrue(ModelStorage.isValidModelDirectory(finalDir, model))
        assertFalse(ModelStorage.verifyDirectArtifact(gguf, artifact))
    }

    @Test fun `same-size GGUF alteration with normal mtime change is invalid`() = withTempDir { tmp ->
        val contents = "GGUF"
        val model = directTestModel(contents)
        val finalDir = publishDirectModel(tmp, model, contents)
        val gguf = File(finalDir, model.directArtifact!!.fileName)
        val publishedMtime = gguf.lastModified()

        gguf.writeText("BORK")

        assertEquals(contents.toByteArray().size.toLong(), gguf.length())
        assertNotEquals(publishedMtime, gguf.lastModified())
        assertFalse(ModelStorage.isValidModelDirectory(finalDir, model))
    }

    @Test fun `publication refuses invalid staging without exposing a final directory`() = withTempDir { tmp ->
        val staging = File(tmp, "staging").apply { mkdirs() }
        val final = File(tmp, "models/model")

        assertFalse(ModelStorage.publishValidatedModel(staging, final))
        assertFalse(final.exists())
    }

    private fun transducer(dir: File, extension: String): File = dir.apply {
        mkdirs()
        write("tokens.txt", "<blk>\nbonjour")
        write("encoder.int8$extension", "encoder")
        write("decoder.int8$extension", "decoder")
        write("joiner.int8$extension", "joiner")
    }

    private fun whisper(dir: File): File = dir.apply {
        mkdirs()
        write("tokens.txt", "<blk>\nhello")
        write("encoder.int8.onnx", "encoder")
        write("decoder.int8.onnx", "decoder")
    }

    private fun moonshine(dir: File): File = dir.apply {
        mkdirs()
        write("tokens.txt", "<blk>\nhello")
        write("preprocess.int8.onnx", "preprocess")
        write("encode.int8.onnx", "encode")
        write("uncached_decode.int8.onnx", "uncached")
        write("cached_decode.int8.onnx", "cached")
    }

    private fun ctc(dir: File): File = dir.apply {
        mkdirs()
        write("tokens.txt", "<blk>\nhello")
        write("model.int8.onnx", "model")
    }

    private fun gguf(dir: File, name: String): File = dir.apply {
        mkdirs()
        write(name, "GGUF")
    }

    private fun File.write(name: String, contents: String) {
        File(this, name).writeText(contents)
    }

    private fun directTestModel(contents: String): Model {
        val catalogModel = MODEL_CATALOG.single { it.runtimeType == RuntimeModelType.GGUF }
        return catalogModel.copy(
            directArtifact = catalogModel.directArtifact!!.copy(
                expectedSizeBytes = contents.toByteArray().size.toLong(),
                sha256 = contents.toByteArray().sha256(),
            )
        )
    }

    private fun publishDirectModel(tmp: File, model: Model, contents: String): File {
        val part = File(tmp, "model.part").apply {
            writeText(contents)
            setLastModified(System.currentTimeMillis() - 60_000L)
        }
        val staging = File(tmp, "staging")
        val finalDir = File(tmp, "models/${model.archive}")
        assertTrue(ModelDownloader.publishDirectArtifact(part, staging, finalDir, model))
        return finalDir
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-storage-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }
}
