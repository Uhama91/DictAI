package com.kafkasl.phonewhisper

import java.io.File

enum class RuntimeModelType { TRANSDUCER, WHISPER, MOONSHINE, CTC }

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
    fun isValidModelDirectory(dir: File, expectedType: RuntimeModelType? = null): Boolean {
        val layout = inspectModelDirectory(dir) ?: return false
        return expectedType == null || layout.type == expectedType
    }

    fun inspectModelDirectory(dir: File): ValidatedModelLayout? {
        if (!dir.isDirectory) return null
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
        return staging.takeIf { isValidModelDirectory(it, model.runtimeType) }
    }

    /** Publish only an already valid staging directory. Both paths must be on the same filesystem. */
    fun publishValidatedModel(
        staging: File,
        finalDir: File,
        expectedType: RuntimeModelType? = null,
    ): Boolean {
        if (!isValidModelDirectory(staging, expectedType)) return false
        if (finalDir.exists()) {
            if (isValidModelDirectory(finalDir, expectedType)) return true
            if (!finalDir.deleteRecursively()) return false
        }
        val parent = finalDir.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        if (!staging.renameTo(finalDir)) return false
        if (isValidModelDirectory(finalDir, expectedType)) return true
        finalDir.deleteRecursively()
        return false
    }

    /** A broken final must never become an installed model after a failed attempt. */
    fun removeFinalIfInvalid(finalDir: File, expectedType: RuntimeModelType? = null) {
        if (finalDir.exists() && !isValidModelDirectory(finalDir, expectedType)) finalDir.deleteRecursively()
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
