package com.kafkasl.phonewhisper

import android.content.Context

/** Vocabulaire personnel : corrections explicites au format "entendu => voulu". */
object Vocabulary {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)

    fun getRaw(ctx: Context): String = prefs(ctx).getString("vocab_raw", "") ?: ""

    fun setRaw(ctx: Context, raw: String) {
        prefs(ctx).edit().putString("vocab_raw", raw).apply()
    }

    /** Lignes "a => b" -> paires de correction. */
    fun corrections(ctx: Context): List<Pair<String, String>> =
        getRaw(ctx).lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || !t.contains("=>")) return@mapNotNull null
            val parts = t.split("=>", limit = 2)
            val from = parts[0].trim(); val to = parts[1].trim()
            if (from.isEmpty()) null else from to to
        }

    fun applyCorrections(ctx: Context, text: String): String =
        applyCorrectionsTo(text, corrections(ctx))

    fun applyCorrectionsTo(text: String, corrections: List<Pair<String, String>>): String {
        var out = text
        for ((from, to) in corrections) {
            val rx = Regex("(?iu)(?<![\\p{L}])" + Regex.escape(from) + "(?![\\p{L}])")
            out = rx.replace(out, Regex.escapeReplacement(to))
        }
        return out
    }
}
