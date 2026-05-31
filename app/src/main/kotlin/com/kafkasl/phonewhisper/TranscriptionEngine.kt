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
            val samples = pcm16ToFloat(pcm)
            val text = local.transcribe(samples, SAMPLE_RATE)
            Result(text)
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
        val modelName = prefs(ctx).getString("model_name", "") ?: ""
        return if (modelName.isBlank()) {
            val models = LocalTranscriber.availableModels(ctx)
            if (models.isNotEmpty()) LocalTranscriber.create(ctx, models.first()) else null
        } else LocalTranscriber.create(ctx, modelName)
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
}
