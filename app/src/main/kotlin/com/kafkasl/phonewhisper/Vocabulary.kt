package com.kafkasl.phonewhisper

import android.content.Context

/** Vocabulaire personnel : corrections explicites au format "entendu => voulu". */
object Vocabulary {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
    fun getRaw(ctx: Context): String = prefs(ctx).getString("vocab_raw", "") ?: ""
    fun setRaw(ctx: Context, raw: String) { prefs(ctx).edit().putString("vocab_raw", raw).apply() }
    fun corrections(ctx: Context): List<Pair<String, String>> = parseCorrections(getRaw(ctx))
    private fun parseCorrections(raw: String): List<Pair<String, String>> = raw.lines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || !t.contains("=>")) return@mapNotNull null
        val parts = t.split("=>", limit = 2)
        val from = parts[0].trim(); val to = parts[1].trim()
        if (from.isEmpty()) null else from to to
    }
    enum class AddResult { ADDED, ALREADY_PRESENT, CONFLICT, INVALID }
    data class Addition(val result: AddResult, val raw: String)
    fun prepareCorrection(raw: String, from: String, to: String): Addition {
        val source = from.trim(); val target = to.trim()
        if (source.isEmpty() || target.isEmpty() || source == target ||
            listOf(source, target).any { it.contains("=>") || it.contains('\n') || it.contains('\r') }) return Addition(AddResult.INVALID, raw)
        val existing = parseCorrections(raw).filter { it.first.equals(source, ignoreCase = true) }
        if (existing.isNotEmpty()) return Addition(
            if (existing.all { it.second == target }) AddResult.ALREADY_PRESENT else AddResult.CONFLICT, raw,
        )
        val separator = if (raw.isEmpty() || raw.endsWith('\n')) "" else "\n"
        return Addition(AddResult.ADDED, raw + separator + source + " => " + target)
    }
    @Synchronized fun addCorrection(ctx: Context, from: String, to: String): AddResult {
        val addition = prepareCorrection(getRaw(ctx), from, to)
        if (addition.result == AddResult.ADDED) setRaw(ctx, addition.raw)
        return addition.result
    }
    fun applyCorrections(ctx: Context, text: String): String = applyCorrectionsTo(text, corrections(ctx))
    fun applyCorrectionsTo(text: String, corrections: List<Pair<String, String>>): String {
        val rules = corrections.filter { it.first.isNotEmpty() }.sortedByDescending { it.first.length }
        if (rules.isEmpty()) return text
        val alternatives = rules.joinToString("|") { Regex.escape(it.first) }
        val rx = Regex("(?iu)(?<![\\p{L}])(?:$alternatives)(?![\\p{L}])")
        return rx.replace(text) { match -> rules.firstOrNull { it.first.equals(match.value, ignoreCase = true) }?.second ?: match.value }
    }
}
