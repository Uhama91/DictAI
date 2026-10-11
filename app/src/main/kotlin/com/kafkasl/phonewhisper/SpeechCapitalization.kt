package com.kafkasl.phonewhisper

import java.util.Locale

/** Repair common French ASR continuations without inferring a sentence from silence. */
internal object SpeechCapitalization {
    private val words = Regex("(?<![\\p{L}\\p{M}\\p{N}_])\\p{Lu}[\\p{L}\\p{M}]*(?:['’][\\p{L}\\p{M}]+)?(?![\\p{L}\\p{M}\\p{N}_'’])")
    private val ordinary = setOf("je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles",
        "ce", "ça", "cela", "ceci", "voilà", "voici", "incohérent", "incohérente", "complètement", "ensuite",
        "de", "du", "des", "et", "mais", "donc", "car", "parce", "puisque", "lorsque", "quand", "si",
        "que", "qui", "pour", "avec", "sans", "dans", "sur", "sous", "alors", "le", "la", "les", "un", "une",
        "vois", "suis", "est", "sommes", "êtes", "sont", "ai", "as", "avons", "avez", "ont", "fais", "fait", "faut",
        "en", "au", "aux", "aussi", "encore", "toujours", "même", "juste", "enfin", "après", "avant", "bien", "oui", "non")
    private val elisions = setOf("j", "t", "m", "s", "n", "c", "d", "l", "qu")
    private val namePrefixes = setOf("de", "du", "des", "le", "la", "les", "un", "une")
    private val technical = Regex("\\S*(?:https?://|www\\.|@|/|\\\\|\\.[A-Za-z]{2,8}|_)\\S*")
    private data class Terms(val values: List<String>, val pattern: Regex?)
    @Volatile private var terms = Terms(emptyList(), null)

    fun normalize(text: String, after: String = "", language: DictationLanguage = DictationLanguage.FRENCH,
        protectedTerms: List<String> = emptyList()): String {
        if (language != DictationLanguage.FRENCH || text.isEmpty()) return text
        val prefix = after + if (after.isNotEmpty() && !after.last().isWhitespace()) " " else ""
        val context = prefix + text
        val candidates = words.findAll(context, prefix.length).filter { it.range.first > 0 }.toList()
        if (candidates.isEmpty()) return text
        val locked = protectedRanges(context, protectedTerms)
        val result = StringBuilder(text)
        for (match in candidates.asReversed()) {
            val word = match.value
            if (word.drop(1).none(Char::isLowerCase) || word.drop(1).any(Char::isUpperCase) ||
                locked.any { it.first <= match.range.last && it.last >= match.range.first }) continue
            val previous = context.subSequence(0, match.range.first).lastOrNull { !it.isWhitespace() || it in "\r\n" }
            if (previous == null || previous in ".!?…\n\r:»\"”«“([{`") continue
            val lowered = word.lowercase(Locale.ROOT)
            val apostrophe = word.indexOfFirst { it == '\'' || it == '’' }
            val elided = apostrophe > 0 && lowered.take(apostrophe) in elisions
            if (lowered !in ordinary && !elided) continue
            val next = context.subSequence(match.range.last + 1, context.length).firstOrNull { !it.isWhitespace() }
            if (lowered in namePrefixes && next?.isUpperCase() == true) continue
            val offset = match.range.first - prefix.length
            result.replace(offset, offset + 1, word.take(1).lowercase(Locale.ROOT))
        }
        return result.toString()
    }

    private fun protectedRanges(text: String, protectedTerms: List<String>): List<IntRange> = buildList {
        var opening = -1
        var closing = ' '
        for (i in text.indices) {
            if (opening >= 0) {
                if (text[i] == closing) { add(opening..i); opening = -1 }
            } else if (text[i] in "\"«“`" || text[i] == '\'' && (i == 0 || text[i - 1].isWhitespace())) {
                opening = i
                closing = when (text[i]) { '«' -> '»'; '“' -> '”'; else -> text[i] }
            }
        }
        if (opening >= 0) add(opening..text.lastIndex)
        technical.findAll(text).forEach { add(it.range) }
        val compiled = terms.takeIf { it.values == protectedTerms } ?: run {
            val phrases = protectedTerms.filter(String::isNotBlank).map { term ->
                term.trim().split(Regex("[\\s\\u00a0]+"))
                    .joinToString("[ \\t\\u00a0]+", transform = Regex::escape)
            }
            Terms(protectedTerms.toList(), if (phrases.isEmpty()) null else
                Regex("(?iu)(?<![\\p{L}\\p{M}\\p{N}_])(?:${phrases.joinToString("|")})(?![\\p{L}\\p{M}\\p{N}_])"))
                .also { terms = it }
        }
        compiled.pattern?.findAll(text)?.forEach { add(it.range) }
    }
}
