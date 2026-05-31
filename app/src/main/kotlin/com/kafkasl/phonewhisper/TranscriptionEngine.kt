package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

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
        val prefs = prefs(ctx)
        val useLocal = prefs.getBoolean("use_local", true)
        return if (useLocal && local != null) {
            try {
                val samples = pcm16ToFloat(pcm)
                Result(local.transcribe(samples, SAMPLE_RATE))
            } catch (t: Throwable) {
                // Lib native sherpa absente / échec runtime → ne pas crasher
                android.util.Log.w("WhisperPin", "transcription locale indisponible: ${t.javaClass.simpleName}")
                Result(null, "Transcription locale indisponible (lib native absente)")
            }
        } else {
            val apiKey = prefs.getString("api_key", "") ?: ""
            if (apiKey.isBlank()) return Result(null, "Set API key")
            val wav = WavWriter.encode(pcm)
            var result: Result = Result(null, "timeout")
            val latch = java.util.concurrent.CountDownLatch(1)
            TranscriberClient.transcribe(wav, apiKey) { r ->
                result = Result(r.text, r.error); latch.countDown()
            }
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            result
        }
    }

    fun loadLocal(ctx: Context): LocalTranscriber? {
        return try {
            val modelName = prefs(ctx).getString("model_name", "") ?: ""
            if (modelName.isBlank()) {
                val models = LocalTranscriber.availableModels(ctx)
                if (models.isNotEmpty()) LocalTranscriber.create(ctx, models.first()) else null
            } else LocalTranscriber.create(ctx, modelName)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (libsherpa-onnx-jni.so absente du build) est une Error,
            // pas une Exception → catch(Throwable) obligatoire pour ne pas crasher l'app.
            android.util.Log.w("WhisperPin", "modèle local indisponible: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
}
