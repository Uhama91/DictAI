package com.kafkasl.phonewhisper

/** Final plain-text cleanup only. Deliberately does not infer meaning or rewrite sentences. */
internal object LightTextCleanup {
    private val fillers = Regex("(?iu)(?<![\\p{L}\\p{N}_’'-])(?:euh+|heu+|uh+|um+)(?![\\p{L}\\p{N}_’'-])")
    // Exclude reflexives (nous nous, vous vous) and emphatic repetitions (très très, non non).
    private val repeats = Regex("(?iu)\\b(je|tu|il|elle|ils|elles|i|he|she|they)(?:[ \\t]+\\1\\b)+")
    private val mention = Regex("(?iu)\\b(?:mot|word|interjection)\\s*$")
    private val question = Regex("(?iu)^(?:est-ce que\\b|qu’est-ce que\\b|qu'est-ce que\\b|(?:peux|pouvez|pourrais|pourriez|veux|voulez|dois|doit|suis|est|êtes|avez|as|a)[-](?:tu|vous|je|il|elle|t-il|t-elle)\\b|(?:can|could|would|will|do|does|did|are|is|have|has|should) (?:you|we|i|he|she|they|it)\\b)")

    fun apply(text: String, protectedTerms: List<String> = emptyList()): String {
        if (text.length > 16000) return text
        fun protectedRanges(value: String): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var start = -1
            var endQuote = ' '
            for (i in value.indices) {
                if (start >= 0) {
                    if (value[i] == endQuote) { ranges.add(start..i); start = -1 }
                } else if (value[i] in "\"«“`" || (value[i] == '\'' && (i == 0 || value[i - 1].isWhitespace()))) {
                    start = i
                    endQuote = when (value[i]) { '«' -> '»'; '“' -> '”'; else -> value[i] }
                }
            }
            if (start >= 0) ranges.add(start..value.lastIndex)
            Regex("\\S*(?:https?://|www\\.|@|/|\\\\|\\.[A-Za-z]{2,8})\\S*")
                .findAll(value).forEach { ranges.add(it.range) }
            protectedTerms.filter { it.isNotBlank() }.forEach { term ->
                Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(value).forEach { ranges.add(it.range) }
            }
            return ranges
        }
        var result = text
        val protected = protectedRanges(result)
        val removals = fillers.findAll(result).filter { match ->
            protected.none { it.first <= match.range.last && it.last >= match.range.first } &&
                match.value != match.value.uppercase(java.util.Locale.ROOT) &&
                !mention.containsMatchIn(result.substring(0, match.range.first).takeLast(32))
        }.toList()
        for (match in removals.asReversed()) {
            var start = match.range.first
            var end = match.range.last + 1
            while (start > 0 && result[start - 1] in " \t,") start--
            while (end < result.length && result[end] in " \t,") end++
            if ((start == 0 || result[start - 1] in ".!?\n") && end < result.length && result[end] in ".…") {
                end++
                while (end < result.length && result[end] in " \t") end++
            }
            val gap = if (start > 0 && end < result.length && result[start - 1] != '\n' && result[end] !in ".!?;:\n") " " else ""
            result = result.substring(0, start) + gap + result.substring(end)
        }
        val protectedAfter = protectedRanges(result)
        result = repeats.replace(result) { match ->
            if (protectedAfter.any { it.first <= match.range.last && it.last >= match.range.first }) match.value
            else match.groupValues[1]
        }
        if (result.none(Char::isLetterOrDigit)) return text
        if (text.firstOrNull()?.isUpperCase() == true && result.firstOrNull()?.isLowerCase() == true &&
            removals.firstOrNull()?.range?.first == 0 && protectedRanges(result).none { 0 in it })
            result = result.replaceFirstChar { it.titlecase() }
        return result.lines().joinToString("\n") { line ->
            val trimmed = line.trimEnd()
            if (trimmed.isEmpty() || trimmed.any { it in "\"«»“”`" } || trimmed.last() in "?!…" ||
                (!trimmed.last().isLetterOrDigit() && trimmed.last() != '.')) line
            else {
                val prose = trimmed.removeSuffix(".")
                val lastSentence = prose.substring(prose.indexOfLast { it in ".!?\n" } + 1).trimStart()
                if (question.containsMatchIn(lastSentence) && protectedRanges(trimmed).none { trimmed.lastIndex in it }) {
                    val french = Regex("(?iu)^(?:est-ce|qu.est-ce|\\S+-)").containsMatchIn(lastSentence)
                    prose + (if (french) " ?" else "?") + line.takeLastWhile(Char::isWhitespace)
                }
                else line
            }
        }.trim()
    }
}
