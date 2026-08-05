package com.kafkasl.phonewhisper

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.util.concurrent.TimeUnit

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
)

val MODEL_CATALOG = listOf(
    // Parakeet 0.6B v3 = SEUL modèle multilingue/français du catalogue → recommandé par défaut.
    Model("Parakeet 0.6B (FR)", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Français — recommandé", recommended = true),
    Model("Nemotron 3.5 Live (FR)", "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
        453, "★★★★ Français live — expérimental"),
    Model("Parakeet 110M (EN)", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Anglais uniquement"),
    Model("Whisper Base (EN)", "sherpa-onnx-whisper-base.en",
        199, "★★★ Anglais uniquement"),
    Model("Moonshine Tiny (EN)", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Anglais, rapide"),
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model) =
        modelDir(ctx, model).exists()

    /** Download and extract model. Callbacks fire on background thread. */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val app = ctx.applicationContext // ne pas retenir une Activity pendant un long download
        val url = "$BASE_URL/${model.archive}.tar.bz2"
        val tmpFile = File(app.cacheDir, "${model.archive}.tar.bz2")
        val modelsDir = File(app.filesDir, "models")
        // Staging HORS de models/ → le dossier final n'apparaît qu'une fois complet (validation fiable).
        val staging = File(app.filesDir, "staging_${model.archive}")
        val finalDir = File(modelsDir, model.archive)

        Thread {
            try {
                downloadFile(url, tmpFile, onState)
                onState(DownloadState.Extracting)
                staging.deleteRecursively(); staging.mkdirs()
                extractTarBz2(tmpFile, staging)
                val extracted = staging.listFiles()?.firstOrNull { it.isDirectory } ?: staging
                modelsDir.mkdirs()
                finalDir.deleteRecursively()
                if (!extracted.renameTo(finalDir)) {     // renommage atomique (même FS)
                    extracted.copyRecursively(finalDir, overwrite = true) // repli rare
                }
                onState(DownloadState.Done)
            } catch (e: Exception) {
                finalDir.deleteRecursively() // ne jamais laisser un modèle partiel "validable"
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                tmpFile.delete()
                staging.deleteRecursively()
            }
        }.start()
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    private fun downloadFile(
        url: String, dest: File, onState: (DownloadState) -> Unit
    ) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty response")
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { src ->
            FileOutputStream(dest).use { dst ->
                val buf = ByteArray(16384)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    dst.write(buf, 0, n)
                    downloaded += n
                    if (total > 0)
                        onState(DownloadState.Downloading(downloaded.toFloat() / total))
                }
            }
        }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val dest = File(outDir, entry.name)
                require(dest.canonicalPath.startsWith(outDir.canonicalPath)) {
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
