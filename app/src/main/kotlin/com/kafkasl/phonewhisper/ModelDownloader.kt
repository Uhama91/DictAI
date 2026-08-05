package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DirectModelArtifact(
    val url: String,
    val fileName: String,
    val expectedSizeBytes: Long,
    val sha256: String,
)

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
    val runtimeType: RuntimeModelType,
    val directArtifact: DirectModelArtifact? = null,
)

val MODEL_CATALOG = listOf(
    // Parakeet 0.6B v3 est le modèle français batch recommandé ; Nemotron fournit le streaming français.
    Model("Parakeet 0.6B (FR)", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Français — recommandé", recommended = true, runtimeType = RuntimeModelType.TRANSDUCER),
    Model("Nemotron 3.5 Live (FR)", "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
        453, "★★★★ Français en direct — expérimental", runtimeType = RuntimeModelType.TRANSDUCER),
    Model("Nemotron 3.5 GGUF (FR)", "nemotron-3.5-asr-streaming-0.6b-Q6_K",
        621, "★★★★ Français — expérimental (GGUF Q6_K)", runtimeType = RuntimeModelType.GGUF,
        directArtifact = DirectModelArtifact(
            url = "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/6d44e540bc31b0de1dbe174a3cea87f53a7f22fb/nemotron-3.5-asr-streaming-0.6b-Q6_K.gguf",
            fileName = "nemotron-3.5-asr-streaming-0.6b-Q6_K.gguf",
            expectedSizeBytes = 621356512L,
            sha256 = "4ff802c6207c4a7df23242003fd2aa849a1ab02bba6bc80c3db02e7e82606c28",
        ),
    ),
    Model("Parakeet 110M (EN)", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Anglais uniquement", runtimeType = RuntimeModelType.CTC),
    Model("Whisper Base (EN)", "sherpa-onnx-whisper-base.en",
        199, "★★★ Anglais uniquement", runtimeType = RuntimeModelType.WHISPER),
    Model("Moonshine Tiny (EN)", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Anglais, rapide", runtimeType = RuntimeModelType.MOONSHINE),
)

sealed class DownloadState {
    data class Downloading(val progress: Float, val isDirect: Boolean = false) : DownloadState()
    data class Extracting(val progress: Float) : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/** Maps download and extraction into one monotone 0..1 installation indicator. */
fun installationProgress(state: DownloadState): Float = when (state) {
    is DownloadState.Downloading ->
        state.progress.coerceIn(0f, 1f) * if (state.isDirect) 1f else 0.5f
    is DownloadState.Extracting -> 0.5f + state.progress.coerceIn(0f, 1f) * 0.5f
    else -> error("No installation progress for $state")
}

class WholePercentProgressPublisher(private val onProgress: (Float) -> Unit) {
    private var lastPercent = -1

    fun publish(progress: Float) {
        val bounded = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
        val percent = (bounded * 100f).toInt().coerceIn(0, 100)
        if (percent <= lastPercent) return

        lastPercent = percent
        onProgress(percent / 100f)
    }

    fun complete() = publish(1f)
}

object ModelDownloader {
    private const val TAG = "ModelDownloader"
    private const val EXTRACTION_BUFFER_SIZE = 64 * 1024
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model) =
        ModelStorage.isValidModelDirectory(modelDir(ctx, model), model)

    /** Repair stale or invalid model_name preferences deterministically from the catalog. */
    fun reconcileSelectedModel(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        val selected = prefs.getString("model_name", "") ?: ""
        val selectedModel = MODEL_CATALOG.firstOrNull { it.archive == selected }
        if (selectedModel != null && isInstalled(ctx, selectedModel)) return selected

        val fallback = MODEL_CATALOG.firstOrNull { isInstalled(ctx, it) }?.archive
        prefs.edit().apply {
            if (fallback == null) remove("model_name") else putString("model_name", fallback)
        }.apply()
        return fallback
    }

    /** Download and extract model. Callbacks fire on background thread. */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val app = ctx.applicationContext // ne pas retenir une Activity pendant un long download
        if (!inFlight.add(model.archive)) {
            onState(DownloadState.Error("Téléchargement déjà en cours."))
            return
        }
        val directArtifact = model.directArtifact
        val url = directArtifact?.url ?: "$BASE_URL/${model.archive}.tar.bz2"
        val modelsDir = File(app.filesDir, "models")
        val workspace = File(app.filesDir, "model-downloads/${model.archive}")
        val partFile = File(workspace, "${directArtifact?.fileName ?: "${model.archive}.tar.bz2"}.part")
        val archiveFile = File(workspace, "${model.archive}.tar.bz2")
        // Staging HORS de models/ → le dossier final n'apparaît qu'une fois complet et validé.
        val staging = File(workspace, "staging")
        val finalDir = File(modelsDir, model.archive)

        Thread {
            try {
                // This workspace is protected per model, so a retry can safely clear only its stale files.
                workspace.deleteRecursively()
                if (!workspace.mkdirs()) throw IOException("Impossible de préparer le stockage")
                downloadFile(url, partFile, onState, directArtifact)
                if (directArtifact != null) {
                    if (!publishDirectArtifact(partFile, staging, finalDir, model)) {
                        throw IOException("Validation ou publication du fichier GGUF impossible")
                    }
                } else {
                    finalizeDownload(partFile, archiveFile)
                    if (!staging.mkdirs()) throw IOException("Impossible de préparer l'extraction")
                    extractTarBz2(archiveFile, staging) { progress ->
                        onState(DownloadState.Extracting(progress))
                    }
                    val extracted = ModelStorage.extractedModelRoot(staging, model)
                        ?: throw IOException("Structure du modèle invalide")
                    if (!ModelStorage.publishValidatedModel(extracted, finalDir, model)) {
                        throw IOException("Validation ou publication du modèle impossible")
                    }
                }
                onState(DownloadState.Done)
            } catch (t: Throwable) {
                Log.e(TAG, "Installation ${model.archive} échouée", t)
                ModelStorage.removeFinalIfInvalid(finalDir, model)
                onState(DownloadState.Error("Installation du modèle impossible."))
            } finally {
                workspace.deleteRecursively()
                inFlight.remove(model.archive)
            }
        }.start()
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    private fun downloadFile(
        url: String,
        partFile: File,
        onState: (DownloadState) -> Unit,
        directArtifact: DirectModelArtifact? = null,
    ) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Réponse vide")
            val total = body.contentLength()
            val expectedSizeBytes = directArtifact?.expectedSizeBytes
            if (expectedSizeBytes != null && total >= 0L && total != expectedSizeBytes) {
                throw IOException("Taille annoncée inattendue")
            }
            val progressTotal = expectedSizeBytes ?: total
            var downloaded = 0L
            val progressPublisher = WholePercentProgressPublisher { progress ->
                onState(downloadingState(progress, directArtifact))
            }
            progressPublisher.publish(0f)

            body.byteStream().use { src ->
                FileOutputStream(partFile).use { dst ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        dst.write(buf, 0, n)
                        downloaded += n
                        if (progressTotal > 0L) {
                            progressPublisher.publish(directDownloadProgress(downloaded, progressTotal))
                        }
                    }
                    dst.fd.sync()
                }
            }
            if (!partFile.isFile || partFile.length() <= 0L) throw IOException("Archive absente ou vide")
            if (progressTotal >= 0L && partFile.length() != progressTotal) {
                throw IOException("Téléchargement incomplet")
            }
            progressPublisher.complete()
        }
    }

    fun directDownloadProgress(downloadedBytes: Long, expectedSizeBytes: Long): Float {
        if (expectedSizeBytes <= 0L) return 0f
        return (downloadedBytes.toDouble() / expectedSizeBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun downloadingState(progress: Float, directArtifact: DirectModelArtifact?): DownloadState.Downloading =
        DownloadState.Downloading(progress, isDirect = directArtifact != null)

    fun publishDirectArtifact(partFile: File, staging: File, finalDir: File, model: Model): Boolean {
        val artifact = model.directArtifact ?: return false
        if (!ModelStorage.verifyDirectArtifact(partFile, artifact)) {
            ModelStorage.removeFinalIfInvalid(finalDir, model)
            return false
        }
        if (!staging.mkdirs()) return false
        val stagedFile = File(staging, artifact.fileName)
        if (stagedFile.exists() && !stagedFile.delete()) return false
        if (!partFile.renameTo(stagedFile)) return false
        if (!ModelStorage.writeDirectIntegrityMarker(staging, stagedFile, artifact)) return false
        if (ModelStorage.publishValidatedModel(staging, finalDir, model)) return true
        ModelStorage.removeFinalIfInvalid(finalDir, model)
        return false
    }

    private fun finalizeDownload(partFile: File, archiveFile: File) {
        if (archiveFile.exists() && !archiveFile.delete()) throw IOException("Archive temporaire bloquée")
        if (!partFile.renameTo(archiveFile)) throw IOException("Finalisation de l'archive impossible")
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File, onProgress: (Float) -> Unit = {}) {
        outDir.mkdirs()
        val totalCompressedBytes = archive.length()
        val progressPublisher = WholePercentProgressPublisher(onProgress)
        progressPublisher.publish(0f)
        CountingInputStream(FileInputStream(archive)) { consumedCompressedBytes ->
            if (totalCompressedBytes > 0) {
                progressPublisher.publish(
                    consumedCompressedBytes.toFloat() / totalCompressedBytes.toFloat()
                )
            }
        }.use { compressedInput ->
            val bzIn = BZip2CompressorInputStream(
                BufferedInputStream(compressedInput, EXTRACTION_BUFFER_SIZE)
            )
            TarArchiveInputStream(bzIn).use { tar ->
                generateSequence { tar.nextEntry }.forEach { entry ->
                    val root = outDir.canonicalFile
                    val dest = File(root, entry.name).canonicalFile
                    require(dest.path == root.path || dest.path.startsWith(root.path + File.separator)) {
                        "Path traversal: ${entry.name}"
                    }
                    if (isNonModelArtifact(entry)) return@forEach
                    if (entry.isDirectory) dest.mkdirs()
                    else {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { tar.copyTo(it, EXTRACTION_BUFFER_SIZE) }
                    }
                }
            }
        }
        progressPublisher.complete()
    }

    private fun isNonModelArtifact(entry: org.apache.commons.compress.archivers.ArchiveEntry): Boolean {
        if (entry.isDirectory) return false
        val name = entry.name.substringAfterLast('/')
        return name.startsWith("README", ignoreCase = true) || name.endsWith(".wav", ignoreCase = true)
    }

    /** Counts bytes read from the compressed archive before any buffering or decompression. */
    private class CountingInputStream(
        input: InputStream,
        private val onBytesConsumed: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var consumedBytes = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) count(read)
            return read
        }

        override fun skip(byteCount: Long): Long {
            val skipped = super.skip(byteCount)
            if (skipped > 0) count(skipped)
            return skipped
        }

        private fun count(amount: Int) = count(amount.toLong())

        private fun count(amount: Long) {
            consumedBytes += amount
            onBytesConsumed(consumedBytes)
        }
    }
}
