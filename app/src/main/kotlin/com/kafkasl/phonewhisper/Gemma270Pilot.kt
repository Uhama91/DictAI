package com.kafkasl.phonewhisper

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Contract shared by the training export, the pilot APK, and its diagnostics. */
internal object Gemma270PilotSupport {
    const val MODEL_FILE_NAME = "gemma3-270m-postclean-v3-q8_0.gguf"
    const val MODEL_ASSET_PATH = "local-format/$MODEL_FILE_NAME"
    const val MANIFEST_ASSET_PATH = "local-format/gemma270-model.json"
    const val MODEL_ID = "google/gemma-3-270m-it"
    const val PROMPT_VERSION = "v3"
    const val PROMPT_SHA256 = "a95ce1093ba1308bddd5d2006eff73b6a936623261fbbfe6fe54562dfe3cd6f6"
    const val RUNTIME_NAME = "gemma270-v3-q8-cpu"
    const val CONTEXT_SIZE = 8_192
    const val MAX_NEW_TOKENS = 4_096
    const val FIXTURE_MAX_NEW_TOKENS = 768
    const val DEADLINE_MS = 60_000L

    fun accepts(language: String, formatId: String): Boolean =
        formatId == "corrected" && language.trim().lowercase() in setOf("fr", "français", "french")
}

internal object Gemma270PilotPrompt {
    const val FIXTURE_MAX_NEW_TOKENS = Gemma270PilotSupport.FIXTURE_MAX_NEW_TOKENS
    const val INSTRUCTION = "Nettoie cette transcription sans la résumer ni la reformuler. " +
        "Rétablis ponctuation, majuscules et paragraphes. " +
        "Supprime les hésitations et répétitions accidentelles, mais conserve les insistances. " +
        "Lors d'une autocorrection explicite, remplace le passage erroné par la formulation finale. " +
        "Corrige un mot mal transcrit seulement si le contexte rend la correction claire ; " +
        "pour un nom rare, appuie-toi sur une forme correcte présente dans le passage. " +
        "Sinon, conserve le texte ambigu. " +
        "Préserve toutes les autres informations, les nombres, les négations, les incertitudes " +
        "et les erreurs citées. " +
        "Renvoie seulement le texte nettoyé."

    /** The user content is byte-for-byte the content used by the Python train/eval pipeline. */
    fun userContent(request: LocalFormatRequest): String {
        require(request.text.isNotBlank()) { "Pilot transcript must not be empty" }
        val protected = request.protectedTerms.takeIf { it.isNotEmpty() }?.let { terms ->
            require(terms.all { it.isNotBlank() && '\n' !in it && '\r' !in it && '<' !in it && '>' !in it }) {
                "Pilot protected terms must be nonempty single-line literals"
            }
            "<protected_terms>\n${terms.joinToString("\n")}\n</protected_terms>\n\n"
        }.orEmpty()
        return "$INSTRUCTION\n\n$protected<transcription>\n${request.text.trim()}\n</transcription>"
    }

    /** Gemma 3's native user-only template, including the generation turn. */
    fun render(request: LocalFormatRequest): String =
        "<bos><start_of_turn>user\n${userContent(request)}<end_of_turn>\n<start_of_turn>model\n"

    fun outputTokenBudget(text: String): Int =
        text.codePointCount(0, text.length).plus(128)
            .coerceIn(192, Gemma270PilotSupport.MAX_NEW_TOKENS)

    fun acceptOutput(text: String?): String? = LocalFormatOutput.accept(text)
        ?.takeUnless { output -> ROLE_MARKERS.any(output::contains) }

    private val ROLE_MARKERS = listOf("<bos>", "<eos>", "<start_of_turn>", "<end_of_turn>")
}

internal data class Gemma270ModelManifest(
    val modelId: String,
    val modelRevision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val promptVersion: String,
    val promptSha256: String,
    val language: String,
    val format: String,
    val runtime: String,
    val contextSize: Int,
    val maxNewTokens: Int,
    val label: String = "",
    val adapterDirectorySha256: String = "",
    val adapterWeightsSha256: String = "",
    val releaseUrl: String = "",
) {
    fun isCompatible(): Boolean =
        modelId == Gemma270PilotSupport.MODEL_ID &&
            modelRevision.matches(Regex("[0-9a-fA-F]{40}")) &&
            fileName == Gemma270PilotSupport.MODEL_FILE_NAME &&
            sizeBytes in 1L..999_999_999L &&
            sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            promptVersion == Gemma270PilotSupport.PROMPT_VERSION &&
            promptSha256.equals(Gemma270PilotSupport.PROMPT_SHA256, ignoreCase = true) &&
            language.lowercase() in setOf("fr", "français", "french") &&
            format.lowercase() == "corrected" &&
            runtime == Gemma270PilotSupport.RUNTIME_NAME &&
            contextSize == Gemma270PilotSupport.CONTEXT_SIZE &&
            maxNewTokens == Gemma270PilotSupport.MAX_NEW_TOKENS

    companion object {
        fun fromJson(json: String): Gemma270ModelManifest {
            val value = JSONObject(json)
            fun requiredString(name: String): String = value.getString(name).also {
                require(it.isNotBlank()) { "$name must not be blank" }
            }
            fun requiredLong(name: String): Long = value.getLong(name)
            fun requiredInt(name: String): Int = value.getInt(name)
            return Gemma270ModelManifest(
                modelId = requiredString("base_model"),
                modelRevision = requiredString("base_revision"),
                fileName = requiredString("filename"),
                sizeBytes = requiredLong("size_bytes"),
                sha256 = requiredString("sha256"),
                promptVersion = requiredString("prompt_version"),
                promptSha256 = requiredString("instruction_sha256"),
                language = requiredString("language"),
                format = requiredString("format"),
                runtime = requiredString("runtime"),
                contextSize = requiredInt("context_size"),
                maxNewTokens = requiredInt("max_new_tokens"),
                label = requiredString("label"),
                adapterDirectorySha256 = requiredString("adapter_directory_sha256"),
                adapterWeightsSha256 = requiredString("adapter_weights_sha256"),
                releaseUrl = requiredString("release_url"),
            )
        }
    }
}

/**
 * Installs the APK asset on a worker. A versioned directory means a bad new
 * copy can never remove a previously verified model.
 */
internal class Gemma270PilotModelStore internal constructor(
    private val root: File,
    private val manifest: Gemma270ModelManifest,
    private val assetSource: () -> InputStream,
) {
    constructor(context: Context) : this(
        root = File(context.applicationContext.noBackupFilesDir, "gemma270-pilot"),
        manifest = loadManifest(context),
        assetSource = { context.applicationContext.assets.open(Gemma270PilotSupport.MODEL_ASSET_PATH) },
    )

    private val finalDir: File get() = File(root, "installed-${manifest.sha256.take(16).lowercase()}")
    private val finalFile: File get() = File(finalDir, manifest.fileName)

    fun installedModel(): File? = finalFile.takeIf { it.isFile && it.length() == manifest.sizeBytes && sha256(it) == manifest.sha256.lowercase() }

    /** Returns null for an unavailable or invalid asset and never exposes a partial file. */
    fun installFromAsset(): File? {
        if (!manifest.isCompatible()) return null
        installedModel()?.let { return it }
        if (!root.exists() && !root.mkdirs()) return null
        val stagingDir = File(root, ".staging-${manifest.sha256.take(16)}-${UUID.randomUUID()}")
        val staging = File(stagingDir, manifest.fileName)
        try {
            if (!stagingDir.mkdirs()) return null
            assetSource().use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            if (staging.length() != manifest.sizeBytes || sha256(staging) != manifest.sha256.lowercase()) return null
            val backup = File(root, ".previous-${manifest.sha256.take(16)}-${UUID.randomUUID()}")
            if (finalDir.exists() && !finalDir.renameTo(backup)) return null
            if (!stagingDir.renameTo(finalDir)) {
                if (backup.exists()) backup.renameTo(finalDir)
                return null
            }
            if (backup.exists()) backup.deleteRecursively()
            return installedModel()
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private fun loadManifest(context: Context): Gemma270ModelManifest =
            context.applicationContext.assets.open(Gemma270PilotSupport.MANIFEST_ASSET_PATH).use {
                Gemma270ModelManifest.fromJson(it.bufferedReader(Charsets.UTF_8).readText())
            }
    }
}
