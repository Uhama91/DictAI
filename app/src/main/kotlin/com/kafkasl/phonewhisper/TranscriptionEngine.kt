package com.kafkasl.phonewhisper

import android.content.Context
import java.io.Closeable

/** Keeps exactly one native transcription engine resident and closes it before a replacement opens. */
internal class ResidentEngine<T : Closeable> : Closeable {
    private val lock = Any()
    private var modelName: String? = null
    private var engine: T? = null

    fun isLoaded(model: String): Boolean = synchronized(lock) { modelName == model && engine != null }

    fun replace(model: String, open: () -> T?): T? = synchronized(lock) {
        if (modelName == model && engine != null) return engine
        val previous = engine
        engine = null
        modelName = null
        previous?.close()
        return open()?.also {
            engine = it
            modelName = model
        }
    }

    override fun close() = synchronized(lock) {
        val previous = engine
        engine = null
        modelName = null
        previous?.close()
        Unit
    }
}

object TranscriptionEngine {

    private const val SAMPLE_RATE = 16000

    fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        return out
    }

    data class Result(val text: String?, val error: String? = null)

    /** Bloquant — appeler hors thread principal. */
    fun transcribe(ctx: Context, pcm: ByteArray, local: LocalTranscriber?): Result {
        // Modèle non téléchargé : afficher un message explicite, sans repli réseau.
        if (local == null) return Result(null,
            "Modèle local absent — téléchargez Parakeet ou Nemotron dans l’application")
        return try {
            val samples = pcm16ToFloat(pcm)
            Result(local.transcribe(samples, SAMPLE_RATE))
        } catch (t: Throwable) {
            // UnsatisfiedLinkError et autres erreurs natives ne doivent jamais tuer l'overlay.
            android.util.Log.w("WhisperPin", "transcription locale indisponible: ${t.javaClass.simpleName}")
            Result(null, "Transcription locale indisponible.")
        }
    }

    fun loadLocal(ctx: Context, modelName: String = selectedModelName(ctx)): LocalTranscriber? {
        return try {
            if (LiveStreamingTranscriber.supports(modelName)) return null
            if (modelName.isBlank()) null else LocalTranscriber.create(ctx, modelName)
        } catch (t: Throwable) {
            // Une erreur native est une Error, pas nécessairement une Exception :
            // catch(Throwable) évite que l’application plante.
            android.util.Log.w("WhisperPin", "modèle local indisponible: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    fun loadStreamingLocal(ctx: Context, modelName: String = selectedModelName(ctx)): LiveStreamingTranscriber? {
        return try {
            if (modelName.isBlank()) return null
            LiveStreamingTranscriber.create(ctx, modelName)
        } catch (t: Throwable) {
            android.util.Log.w("WhisperPin", "modèle streaming indisponible: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    fun selectedModelName(ctx: Context): String =
        ModelDownloader.reconcileSelectedModel(ctx) ?: ""
}
