package com.kafkasl.phonewhisper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    private fun File.write(name: String, contents: String) {
        File(this, name).writeText(contents)
    }

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-storage-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }
}
