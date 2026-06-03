package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Nettoyage cloud via OpenRouter — gateway compatible OpenAI donnant accès à tous les
 * fournisseurs (OpenAI, Google…) avec UNE seule clé et UN seul endpoint. Le modèle est choisi
 * par son slug OpenRouter (vérifiés sur la doc / le catalogue OpenRouter).
 */
object CloudCleanup {
    private const val TAG = "WhisperPin"
    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val SYSTEM =
        "Tu es un correcteur de transcription vocale en français. Corrige l'orthographe, la " +
        "grammaire, la ponctuation et les majuscules sans changer le sens ni la langue. " +
        "Réponds UNIQUEMENT avec le texte corrigé, sans aucun commentaire."

    data class CloudModel(val id: String, val label: String, val slug: String, val price: String)

    /** Du moins cher au plus cher. Slugs OpenRouter vérifiés. */
    val CLOUD_MODELS = listOf(
        CloudModel("gpt5nano",   "GPT-5 nano",            "openai/gpt-5-nano",                 "≈ $0.05/$0.40 — le moins cher"),
        CloudModel("gemini20fl", "Gemini 2.0 Flash-Lite", "google/gemini-2.0-flash-lite-001",  "≈ $0.075/$0.30"),
        CloudModel("gemini25fl", "Gemini 2.5 Flash-Lite", "google/gemini-2.5-flash-lite",      "≈ $0.10/$0.40"),
        CloudModel("gpt4omini",  "GPT-4o mini",           "openai/gpt-4o-mini",                "≈ $0.15/$0.60 — fiable"),
        CloudModel("gpt54nano",  "GPT-5.4 nano",          "openai/gpt-5.4-nano",               "≈ $0.20/$1.25 — plus récent"),
    )

    private fun cfg(ctx: Context) = ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
    private fun keys(ctx: Context) = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)

    fun selectedModel(ctx: Context): CloudModel =
        CLOUD_MODELS.firstOrNull { it.id == cfg(ctx).getString("cloud_model", "") } ?: CLOUD_MODELS.first()
    fun setSelectedModel(ctx: Context, id: String) = cfg(ctx).edit().putString("cloud_model", id).apply()

    fun key(ctx: Context): String = keys(ctx).getString("openrouter_key", "") ?: ""
    fun setKey(ctx: Context, k: String) = keys(ctx).edit().putString("openrouter_key", k.trim()).apply()
    fun hasKey(ctx: Context) = key(ctx).isNotBlank()

    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    /** Synchrone (hors thread principal). Renvoie le texte nettoyé, ou null si clé absente/échec. */
    fun rewrite(ctx: Context, userPrompt: String): String? {
        val key = key(ctx)
        if (key.isBlank()) { Log.w(TAG, "OpenRouter: clé absente"); return null }
        val model = selectedModel(ctx)
        val payload = JSONObject().apply {
            put("model", model.slug)
            put("temperature", 0.0)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }
        val req = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("X-Title", "DictAI")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                // Ne PAS logger le corps : il peut contenir le texte dicté (vie privée).
                if (!resp.isSuccessful) { Log.w(TAG, "OpenRouter HTTP ${resp.code}"); return null }
                val choices = JSONObject(s).optJSONArray("choices") ?: return null
                choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()?.ifBlank { null }
            }
        } catch (e: Exception) { Log.w(TAG, "OpenRouter échec: ${e.javaClass.simpleName} ${e.message}"); null }
    }
}
