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

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
    val runtimeType: RuntimeModelType,
)

val MODEL_CATALOG = listOf(
    // Parakeet 0.6B v3 est le modèle français batch recommandé ; Nemotron fournit le streaming français.
    Model("Parakeet 0.6B (FR)", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Français — recommandé", recommended = true, runtimeType = RuntimeModelType.TRANSDUCER),
    Model("Nemotron 3.5 Live (FR)", "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
        453, "★★★★ Français en direct — expérimental", runtimeType = RuntimeModelType.TRANSDUCER),
    Model("Parakeet 110M (EN)", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Anglais uniquement", runtimeType = RuntimeModelType.CTC),
    Model("Whisper Base (EN)", "sherpa-onnx-whisper-base.en",
        199, "★★★ Anglais uniquement", runtimeType = RuntimeModelType.WHISPER),
    Model("Moonshine Tiny (EN)", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Anglais, rapide", runtimeType = RuntimeModelType.MOONSHINE),
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private const val TAG = "ModelDownloader"
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model) =
        ModelStorage.isValidModelDirectory(modelDir(ctx, model), model.runtimeType)

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
        val url = "$BASE_URL/${model.archive}.tar.bz2"
        val modelsDir = File(app.filesDir, "models")
        val workspace = File(app.filesDir, "model-downloads/${model.archive}")
        val partFile = File(workspace, "${model.archive}.tar.bz2.part")
        val archiveFile = File(workspace, "${model.archive}.tar.bz2")
        // Staging HORS de models/ → le dossier final n'apparaît qu'une fois complet et validé.
        val staging = File(workspace, "staging")
        val finalDir = File(modelsDir, model.archive)

        Thread {
            try {
                // This workspace is protected per model, so a retry can safely clear only its stale files.
                workspace.deleteRecursively()
                if (!workspace.mkdirs()) throw IOException("Impossible de préparer le stockage")
                downloadFile(url, partFile, archiveFile, onState)
                onState(DownloadState.Extracting)
                if (!staging.mkdirs()) throw IOException("Impossible de préparer l'extraction")
                extractTarBz2(archiveFile, staging)
                val extracted = ModelStorage.extractedModelRoot(staging, model)
                    ?: throw IOException("Structure du modèle invalide")
                if (!ModelStorage.publishValidatedModel(extracted, finalDir, model.runtimeType)) {
                    throw IOException("Validation ou publication du modèle impossible")
                }
                onState(DownloadState.Done)
            } catch (t: Throwable) {
                Log.e(TAG, "Installation ${model.archive} échouée", t)
                ModelStorage.removeFinalIfInvalid(finalDir, model.runtimeType)
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
        url: String, partFile: File, archiveFile: File, onState: (DownloadState) -> Unit
    ) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Réponse vide")
            val total = body.contentLength()
            var downloaded = 0L

            body.byteStream().use { src ->
                FileOutputStream(partFile).use { dst ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        dst.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            onState(DownloadState.Downloading(downloaded.toFloat() / total))
                        }
                    }
                    dst.fd.sync()
                }
            }
            if (!partFile.isFile || partFile.length() <= 0L) throw IOException("Archive absente ou vide")
            if (total >= 0L && partFile.length() != total) throw IOException("Archive incomplète")
            if (archiveFile.exists() && !archiveFile.delete()) throw IOException("Archive temporaire bloquée")
            if (!partFile.renameTo(archiveFile)) throw IOException("Finalisation de l'archive impossible")
        }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val root = outDir.canonicalFile
                val dest = File(root, entry.name).canonicalFile
                require(dest.path == root.path || dest.path.startsWith(root.path + File.separator)) {
                    "Path traversal: ${entry.name}"
                }
                if (entry.isDirectory) dest.mkdirs()
                else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { tar.copyTo(it) }
                }
            }
        }
    }
}
