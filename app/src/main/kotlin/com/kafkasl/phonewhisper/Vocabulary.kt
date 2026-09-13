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
    private fun key(text: String) = text.trim().replace(Regex("[\\s\\u00a0]+"), " ").lowercase(java.util.Locale.ROOT)
    fun prepareCorrection(raw: String, from: String, to: String): Addition {
        val source = from.trim(); val target = to.trim()
        if (source.isEmpty() || target.isEmpty() || source == target ||
            listOf(source, target).any { it.contains("=>") || it.contains('\n') || it.contains('\r') }) return Addition(AddResult.INVALID, raw)
        val existing = parseCorrections(raw).filter { key(it.first) == key(source) }
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
    private data class Compiled(val source: List<Pair<String, String>>, val pattern: Regex?, val rules: List<Pair<String, String>>)
    @Volatile private var compiled: Compiled? = null
    fun applyCorrectionsTo(text: String, corrections: List<Pair<String, String>>): String {
        val current = compiled?.takeIf { it.source == corrections } ?: run {
            val rules = corrections.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }
            val alternatives = rules.mapIndexed { index, rule ->
                val phrase = rule.first.trim().split(Regex("[\\s\\u00a0]+"))
                    .joinToString("[ \\t\\u00a0]+") { Regex.escape(it) }
                "(?<r$index>$phrase)"
            }.joinToString("|")
            val word = "[\\p{L}\\p{M}\\p{N}_]"
            Compiled(corrections.toList(), if (rules.isEmpty()) null else Regex("(?iu)(?<!$word)(?:$alternatives)(?!$word)"), rules)
                .also { compiled = it }
        }
        return current.pattern?.replace(text) { match ->
            val index = current.rules.indices.first { match.groups["r$it"] != null }
            current.rules[index].second
        } ?: text
    }
}
