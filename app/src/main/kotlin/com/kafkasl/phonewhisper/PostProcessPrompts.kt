package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Prompts de post-traitement (libellé + template avec ${output}), stockés en prefs. */
object PostProcessPrompts {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)

    data class Prompt(val label: String, val template: String)

    private const val DEFAULT_TEMPLATE =
        "Corrige l'orthographe, la grammaire et la ponctuation du texte suivant sans en changer le sens ni la langue. Réponds uniquement avec le texte corrigé.\n\n\${output}"

    private fun default() = listOf(Prompt("Correction", DEFAULT_TEMPLATE))

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("llm_enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("llm_enabled", v).apply()

    /** Moteur de nettoyage : "off" | "local" | "cloud". Migre depuis l'ancien flag llm_enabled. */
    fun engine(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("cleanup_engine", null)?.let { return it }
        return if (p.getBoolean("llm_enabled", false)) "local" else "off"
    }
    fun setEngine(ctx: Context, e: String) =
        // garde l'ancien flag llm_enabled cohérent (préchargement Qwen3, compat)
        prefs(ctx).edit().putString("cleanup_engine", e).putBoolean("llm_enabled", e == "local").apply()

    fun all(ctx: Context): List<Prompt> = try {
        val raw = prefs(ctx).getString("llm_prompts", null) ?: return default()
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Prompt(o.getString("label"), o.getString("template"))
        }.ifEmpty { default() }
    } catch (t: Throwable) { default() }

    fun save(ctx: Context, prompts: List<Prompt>) {
        val arr = JSONArray()
        prompts.forEach { arr.put(JSONObject().put("label", it.label).put("template", it.template)) }
        prefs(ctx).edit().putString("llm_prompts", arr.toString()).apply()
    }

    fun selectedIndex(ctx: Context) =
        prefs(ctx).getInt("llm_selected", 0).coerceIn(0, (all(ctx).size - 1).coerceAtLeast(0))
    fun setSelectedIndex(ctx: Context, i: Int) = prefs(ctx).edit().putInt("llm_selected", i).apply()

    fun selected(ctx: Context): Prompt = all(ctx).getOrElse(selectedIndex(ctx)) { all(ctx).first() }

    /** Remplit le template avec le transcript (substitue ${output}, sinon l'ajoute en fin). */
    fun fillTemplate(template: String, transcript: String): String =
        if (template.contains("\${output}")) template.replace("\${output}", transcript)
        else "$template\n\n$transcript"

    fun fill(ctx: Context, transcript: String) = fillTemplate(selected(ctx).template, transcript)
}
