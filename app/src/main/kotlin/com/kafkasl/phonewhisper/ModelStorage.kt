package com.kafkasl.phonewhisper

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

enum class RuntimeModelType { TRANSDUCER, WHISPER, MOONSHINE, CTC, GGUF }

data class ValidatedModelLayout(
    val type: RuntimeModelType,
    val tokens: File,
    val encoder: File? = null,
    val decoder: File? = null,
    val joiner: File? = null,
    val preprocess: File? = null,
    val uncachedDecoder: File? = null,
    val cachedDecoder: File? = null,
    val model: File? = null,
)

/** Lightweight filesystem validation shared by installation, selection, and runtime loading. */
object ModelStorage {
    private const val DIRECT_INTEGRITY_MARKER = ".gguf-integrity"
    private const val DIRECT_INTEGRITY_MAGIC = "dictai-gguf-integrity-v1"

    fun isValidModelDirectory(dir: File, expectedType: RuntimeModelType? = null): Boolean {
        val layout = inspectModelDirectory(dir) ?: return false
        return expectedType == null || layout.type == expectedType
    }

    fun isValidModelDirectory(dir: File, expectedModel: Model): Boolean {
        val layout = inspectModelDirectory(dir) ?: return false
        if (layout.type != expectedModel.runtimeType) return false
        val artifact = expectedModel.directArtifact ?: return true
        val gguf = layout.model ?: return false
        return gguf.name == artifact.fileName && hasValidDirectIntegrityMarker(dir, gguf, artifact)
    }

    /** Full byte-level verification. Use only before publishing a freshly downloaded artifact. */
    fun verifyDirectArtifact(file: File, artifact: DirectModelArtifact): Boolean {
        if (!file.isFile || file.length() != artifact.expectedSizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) } == artifact.sha256
    }

    /** Write the metadata proof into unpublished staging using an atomic same-directory rename. */
    fun writeDirectIntegrityMarker(staging: File, gguf: File, artifact: DirectModelArtifact): Boolean {
        if (!staging.isDirectory || gguf.parentFile?.canonicalFile != staging.canonicalFile) return false
        if (gguf.name != artifact.fileName || gguf.length() != artifact.expectedSizeBytes) return false
        val marker = File(staging, DIRECT_INTEGRITY_MARKER)
        val markerPart = File(staging, "$DIRECT_INTEGRITY_MARKER.part")
        if (markerPart.exists() && !markerPart.delete()) return false

        return try {
            FileOutputStream(markerPart).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                output.writeUTF(DIRECT_INTEGRITY_MAGIC)
                output.writeUTF(artifact.sha256)
                output.writeLong(artifact.expectedSizeBytes)
                output.writeLong(gguf.lastModified())
                output.flush()
                fileOutput.fd.sync()
            }
            if (marker.exists() && !marker.delete()) return false
            markerPart.renameTo(marker)
        } catch (_: IOException) {
            markerPart.delete()
            false
        }
    }

    private fun hasValidDirectIntegrityMarker(
        dir: File,
        gguf: File,
        artifact: DirectModelArtifact,
    ): Boolean {
        if (gguf.length() != artifact.expectedSizeBytes) return false
        val marker = File(dir, DIRECT_INTEGRITY_MARKER)
        if (!marker.isFile) return false
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(marker))).use { input ->
                input.readUTF() == DIRECT_INTEGRITY_MAGIC &&
                    input.readUTF() == artifact.sha256 &&
                    input.readLong() == artifact.expectedSizeBytes &&
                    input.readLong() == gguf.lastModified() &&
                    input.read() == -1
            }
        } catch (_: IOException) {
            false
        }
    }

    fun inspectModelDirectory(dir: File): ValidatedModelLayout? {
        if (!dir.isDirectory) return null
        val ggufFiles = dir.listFiles()?.filter { file ->
            file.isFile && file.length() > 0L && file.name.endsWith(".gguf", ignoreCase = true)
        }.orEmpty()
        if (ggufFiles.size == 1) {
            val gguf = ggufFiles.single()
            // GGUF bundles its tokenizer, so the legacy tokens field is the GGUF file itself.
            return ValidatedModelLayout(RuntimeModelType.GGUF, tokens = gguf, model = gguf)
        }
        val tokens = nonEmptyFile(dir, "tokens.txt") ?: return null

        // Moonshine is identified by preprocess, matching LocalTranscriber's runtime branch.
        if (hasRuntimeCandidate(dir, "preprocess")) {
            return ValidatedModelLayout(
                type = RuntimeModelType.MOONSHINE,
                tokens = tokens,
                preprocess = findRuntimeFile(dir, "preprocess") ?: return null,
                encoder = findRuntimeFile(dir, "encode") ?: return null,
                uncachedDecoder = findRuntimeFile(dir, "uncached_decode") ?: return null,
                cachedDecoder = findRuntimeFile(dir, "cached_decode") ?: return null,
            )
        }

        val encoder = findRuntimeFile(dir, "encoder")
        val decoder = findRuntimeFile(dir, "decoder")
        val joiner = findRuntimeFile(dir, "joiner")
        if (encoder != null && decoder != null && joiner != null) {
            return ValidatedModelLayout(RuntimeModelType.TRANSDUCER, tokens, encoder, decoder, joiner)
        }
        // A present but invalid joiner must not make a transducer look like Whisper.
        if (encoder != null && decoder != null && !hasRuntimeCandidate(dir, "joiner")) {
            return ValidatedModelLayout(RuntimeModelType.WHISPER, tokens, encoder, decoder)
        }

        val model = findRuntimeFile(dir, "model")
        if (model != null) return ValidatedModelLayout(RuntimeModelType.CTC, tokens, model = model)

        return null
    }

    /** Select the archive root explicitly; root staging is allowed only for a direct valid layout. */
    fun extractedModelRoot(staging: File, model: Model): File? {
        val expectedRoot = File(staging, model.archive)
        if (expectedRoot.isDirectory) return expectedRoot
        return staging.takeIf { isValidModelDirectory(it, model) }
    }

    /** Publish only an already valid staging directory. Both paths must be on the same filesystem. */
    fun publishValidatedModel(
        staging: File,
        finalDir: File,
        expectedType: RuntimeModelType? = null,
    ): Boolean = publishValidatedModel(staging, finalDir) { dir ->
        isValidModelDirectory(dir, expectedType)
    }

    fun publishValidatedModel(staging: File, finalDir: File, expectedModel: Model): Boolean =
        publishValidatedModel(staging, finalDir) { dir ->
            isValidModelDirectory(dir, expectedModel)
        }

    private fun publishValidatedModel(
        staging: File,
        finalDir: File,
        isValid: (File) -> Boolean,
    ): Boolean {
        if (!isValid(staging)) return false
        if (finalDir.exists()) {
            if (isValid(finalDir)) return true
            if (!finalDir.deleteRecursively()) return false
        }
        val parent = finalDir.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        if (!staging.renameTo(finalDir)) return false
        if (isValid(finalDir)) return true
        finalDir.deleteRecursively()
        return false
    }

    /** A broken final must never become an installed model after a failed attempt. */
    fun removeFinalIfInvalid(finalDir: File, expectedType: RuntimeModelType? = null) {
        if (finalDir.exists() && !isValidModelDirectory(finalDir, expectedType)) finalDir.deleteRecursively()
    }

    fun removeFinalIfInvalid(finalDir: File, expectedModel: Model) {
        if (finalDir.exists() && !isValidModelDirectory(finalDir, expectedModel)) finalDir.deleteRecursively()
    }

    private fun nonEmptyFile(dir: File, name: String): File? =
        File(dir, name).takeIf { it.isFile && it.length() > 0L }

    private fun hasRuntimeCandidate(dir: File, prefix: String): Boolean =
        dir.listFiles()?.any { candidate ->
            candidate.isFile && candidate.name.startsWith(prefix) && candidate.isRuntimeModelFile()
        } == true

    /** Keep the int8 preference consistent with the sherpa runtime file selection. */
    fun findRuntimeFile(dir: File, prefix: String): File? {
        val candidates = dir.listFiles()?.filter { candidate ->
            candidate.isFile && candidate.length() > 0L && candidate.name.startsWith(prefix) &&
                candidate.isRuntimeModelFile()
        } ?: return null
        return candidates.firstOrNull { it.name.contains("int8", ignoreCase = true) } ?: candidates.firstOrNull()
    }

    private fun File.isRuntimeModelFile(): Boolean =
        name.endsWith(".onnx", ignoreCase = true) || name.endsWith(".ort", ignoreCase = true)
}
