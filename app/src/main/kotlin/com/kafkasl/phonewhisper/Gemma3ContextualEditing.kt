package com.kafkasl.phonewhisper

import java.util.Locale

/**
 * One candidate-proposed local repair for the Gemma 3 final-text route.
 * Any remaining differences go through the unchanged conservative editing gate.
 */
internal object Gemma3ContextualEditing {
    private val word = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val weekdays = setOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val months = setOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
    private val pronouns = setOf("je", "j", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles")
    private val fillers = setOf("euh", "heu", "uh", "um")
    private val abandonedConnectorPairs = mapOf("et" to setOf("mais"))
    private val emphaticWords = setOf("oui", "vraiment", "très")
    private val sentencePunctuation = ",.;:!?…"

    private data class Token(val value: String, val start: Int, val end: Int) {
        val key: String get() = value.lowercase(Locale.ROOT)
    }

    private data class WordDiff(
        val sourceStart: Int,
        val sourceEnd: Int,
        val candidateStart: Int,
        val candidateEnd: Int,
    ) {
        val sourceCount: Int get() = sourceEnd - sourceStart
        val candidateCount: Int get() = candidateEnd - candidateStart
    }

    private enum class ChangeKind { REPLACE_WORD, INSERT_BE, DELETE_SPAN }

    private data class CertifiedChange(
        val kind: ChangeKind,
        val diff: WordDiff,
        val replacement: String = "",
        val dateCorrection: Boolean = false,
    )

    fun accept(request: LocalFormatRequest, output: String?): String? {
        val candidate = LocalFormatOutput.accept(output)
            ?.takeIf { it.length <= 32768 && '\u0000' !in it }
            ?: return null
        val source = request.text
        val sourceWords = words(source)
        val candidateWords = words(candidate)
        if (sourceWords.isEmpty() || sourceWords.size > 2048 || candidateWords.isEmpty() || candidateWords.size > 2048)
            return null

        val diff = singleDiff(sourceWords, candidateWords)
        if (diff == null) return legacyAccept(request, candidate)

        // Removing only the hesitation from a connector + hesitation + connector leaves the bad half-repair.
        if (isHalfAbandonedConnector(sourceWords, diff)) return null

        val change = certify(source, sourceWords, candidateWords, diff) ?: return legacyAccept(request, candidate)
        if (changesProtectedSource(source, sourceWords, request.protectedTerms, change)) return null
        val adjustedSource = adjustSource(source, sourceWords, candidate, candidateWords, change) ?: return null
        return legacyAccept(request.copy(text = adjustedSource), candidate)
    }

    private fun legacyAccept(request: LocalFormatRequest, candidate: String): String? =
        GemmaConservativeEditing.accept(
            request.copy(validation = LocalFormatValidation.GEMMA_EDITING),
            candidate,
        )

    /** Only one lexical span is certified at a time; a larger diff keeps legacy behavior. */
    private fun singleDiff(source: List<Token>, candidate: List<Token>): WordDiff? {
        var prefix = 0
        while (prefix < source.size && prefix < candidate.size && source[prefix].key == candidate[prefix].key) prefix++
        var suffix = 0
        val suffixLimit = minOf(source.size - prefix, candidate.size - prefix)
        while (suffix < suffixLimit &&
            source[source.lastIndex - suffix].key == candidate[candidate.lastIndex - suffix].key
        ) suffix++
        if (prefix == source.size && prefix == candidate.size) return null
        return WordDiff(prefix, source.size - suffix, prefix, candidate.size - suffix)
    }

    private fun certify(source: String, sourceWords: List<Token>, candidateWords: List<Token>, diff: WordDiff): CertifiedChange? {
        val sourceKeys = sourceWords.map(Token::key)
        val sourceTokenCount = diff.sourceCount
        val candidateTokenCount = diff.candidateCount

        if (sourceTokenCount == 1 && candidateTokenCount == 1) {
            val index = diff.sourceStart
            val from = sourceKeys[index]
            val to = candidateWords[diff.candidateStart].key
            val target = when {
                from == "est" && to == "ait" && hasSubjunctiveAvoirContext(source, sourceWords, sourceKeys, index) -> "ait"
                from == "permet" && to == "permets" && hasJeMePermetsContext(source, sourceWords, sourceKeys, index) -> "permets"
                from == "akaby" && to == "acabit" && hasCetAcabitContext(source, sourceWords, sourceKeys, index) -> "acabit"
                else -> return null
            }
            val proposed = candidateWords[diff.candidateStart].value
            if (proposed != target) return null
            return CertifiedChange(ChangeKind.REPLACE_WORD, diff, proposed)
        }

        if (sourceTokenCount == 0 && candidateTokenCount == 1 &&
            candidateWords[diff.candidateStart].value == "être" &&
            hasDevoirEtreContext(source, sourceWords, sourceKeys, diff.sourceStart)
        ) return CertifiedChange(ChangeKind.INSERT_BE, diff, " être")

        if (candidateTokenCount == 0 && sourceTokenCount > 0) {
            if (isCompletePronounRepetition(source, sourceWords, sourceKeys, diff))
                return CertifiedChange(ChangeKind.DELETE_SPAN, diff)
            if (isHesitatedConnector(source, sourceWords, sourceKeys, diff))
                return CertifiedChange(ChangeKind.DELETE_SPAN, diff)
            if (isExplicitDateCorrection(source, sourceWords, sourceKeys, diff))
                return CertifiedChange(ChangeKind.DELETE_SPAN, diff, dateCorrection = true)
        }
        return null
    }

    private fun hasSubjunctiveAvoirContext(source: String, words: List<Token>, keys: List<String>, index: Int): Boolean =
        index >= 4 && index + 1 < words.size &&
            keys.subList(index - 4, index) == listOf("il", "faut", "qu", "il") && keys[index + 1] == "accès" &&
            separatedByWhitespace(source, words, index - 4, index - 3) &&
            separatedByWhitespace(source, words, index - 3, index - 2) &&
            source.substring(words[index - 2].end, words[index - 1].start) in setOf("'", "’") &&
            separatedByWhitespace(source, words, index - 1, index) &&
            separatedByWhitespace(source, words, index, index + 1)

    private fun hasJeMePermetsContext(source: String, words: List<Token>, keys: List<String>, index: Int): Boolean =
        index >= 2 && index + 1 < words.size &&
            keys.subList(index - 2, index) == listOf("je", "me") && keys[index + 1] == "de" &&
            separatedByWhitespace(source, words, index - 2, index - 1) &&
            separatedByWhitespace(source, words, index - 1, index) &&
            separatedByWhitespace(source, words, index, index + 1)

    private fun hasCetAcabitContext(source: String, words: List<Token>, keys: List<String>, index: Int): Boolean =
        index >= 2 && keys.subList(index - 2, index) == listOf("de", "cet") &&
            separatedByWhitespace(source, words, index - 2, index - 1) &&
            separatedByWhitespace(source, words, index - 1, index)

    private fun hasDevoirEtreContext(source: String, words: List<Token>, keys: List<String>, insertionIndex: Int): Boolean =
        insertionIndex >= 4 && insertionIndex + 1 < words.size &&
            keys.subList(insertionIndex - 4, insertionIndex) == listOf("tu", "dois", "peut", "être") &&
            keys[insertionIndex] == "au" && keys[insertionIndex + 1] == "courant" &&
            source.substring(words[insertionIndex - 2].end, words[insertionIndex - 1].start) == "-" &&
            separatedByWhitespace(source, words, insertionIndex - 4, insertionIndex - 3) &&
            separatedByWhitespace(source, words, insertionIndex - 3, insertionIndex - 2) &&
            separatedByWhitespace(source, words, insertionIndex - 1, insertionIndex) &&
            separatedByWhitespace(source, words, insertionIndex, insertionIndex + 1)

    private fun isCompletePronounRepetition(source: String, words: List<Token>, keys: List<String>, diff: WordDiff): Boolean {
        val length = diff.sourceCount
        val secondStart = diff.sourceStart
        if (diff.sourceEnd != secondStart + length) return false
        if (length == 1) {
            return keys[secondStart] == "on" && secondStart > 0 && keys[secondStart - 1] == "on" &&
                source.substring(words[secondStart - 1].end, words[secondStart].start).let { gap ->
                    gap.isNotEmpty() && gap.all(Char::isWhitespace)
                }
        }
        if (length !in 2..20) return false
        val firstStart = secondStart - length
        if (firstStart < 0) return false
        if (keys.subList(firstStart, secondStart) != keys.subList(secondStart, secondStart + length)) return false
        val repeatedGroup = keys.subList(firstStart, secondStart)
        if (repeatedGroup.first() !in pronouns) return false
        if (repeatedGroup.last() in setOf("qu", "que", "qui")) return false
        if (repeatedGroup.any { it in emphaticWords }) return false
        if ((firstStart until secondStart - 1).any { hasSentenceBoundary(source, words, it, it + 1) }) return false
        if ((secondStart until secondStart + length - 1).any { hasSentenceBoundary(source, words, it, it + 1) }) return false
        for (offset in 0 until length - 1) {
            val firstGap = source.substring(words[firstStart + offset].end, words[firstStart + offset + 1].start)
            val secondGap = source.substring(words[secondStart + offset].end, words[secondStart + offset + 1].start)
            if (firstGap != secondGap) return false
        }
        return isPlainPhraseGap(source.substring(words[secondStart - 1].end, words[secondStart].start))
    }

    private fun isHesitatedConnector(source: String, words: List<Token>, keys: List<String>, diff: WordDiff): Boolean {
        if (diff.sourceCount != 2 || diff.sourceStart == 0 || diff.sourceEnd + 1 >= words.size) return false
        val start = diff.sourceStart
        if (keys[start + 1] !in fillers || keys[diff.sourceEnd] !in abandonedConnectorPairs[keys[start]].orEmpty()) return false
        if (!isPlainPhraseGap(source.substring(words[start].end, words[start + 1].start))) return false
        if (!isPlainPhraseGap(source.substring(words[start + 1].end, words[diff.sourceEnd].start))) return false
        return !hasSentenceBoundary(source, words, start - 1, start) &&
            !hasSentenceBoundary(source, words, diff.sourceEnd, diff.sourceEnd + 1)
    }

    private fun isHalfAbandonedConnector(sourceWords: List<Token>, diff: WordDiff): Boolean {
        if (diff.sourceCount != 1 || diff.candidateCount != 0 || diff.sourceStart == 0 || diff.sourceEnd >= sourceWords.size)
            return false
        val keys = sourceWords.map(Token::key)
        val index = diff.sourceStart
        return keys[index] in fillers && keys[index - 1] == "et" &&
            keys[index + 1] in abandonedConnectorPairs[keys[index - 1]].orEmpty()
    }

    private fun isExplicitDateCorrection(source: String, words: List<Token>, keys: List<String>, diff: WordDiff): Boolean {
        if (diff.sourceEnd >= words.size ||
            (diff.sourceStart > 0 && hasSentenceBoundary(source, words, diff.sourceStart - 1, diff.sourceStart)) ||
            hasSentenceBoundary(source, words, diff.sourceEnd - 1, diff.sourceEnd)
        ) return false

        val start = diff.sourceStart
        val end = diff.sourceEnd
        if (diff.sourceCount == 2 && keys[start] in weekdays && keys[start + 1] in setOf("non", "pardon") &&
            keys[end] in weekdays && keys[start] != keys[end]
        ) {
            if (!onlyWhitespaceOrCommaBetween(source, words, start, end)) return false
            if (keys[start + 1] == "non" && !hasCommaOnBothSides(source, words, start, start + 1, end)) return false
            return true
        }

        if (hasNumericDateCorrection(source, words, keys, diff)) return true

        // The common-prefix alignment can pair the first "le" with the corrected date's "le".
        // In that case the deletion span ends with the second "le"; its first copy remains in place.
        if (start == 0 || keys[start - 1] != "le" || keys[end - 1] != "le") return false
        val oldDay = keys.getOrNull(start)?.toIntOrNull()?.takeIf { it in 1..31 } ?: return false
        val oldMonth = keys.getOrNull(start + 1)?.takeIf { it in months }
        val cueIndex = start + 1 + if (oldMonth == null) 0 else 1
        if (cueIndex + 1 != end - 1 || keys.getOrNull(cueIndex) !in setOf("non", "pardon")) return false
        if ((start - 1 until end).any { hasSentenceBoundary(source, words, it, it + 1) }) return false
        val finalDay = keys.getOrNull(end)?.toIntOrNull()?.takeIf { it in 1..31 } ?: return false
        val finalMonth = keys.getOrNull(end + 1)?.takeIf { it in months }
        if (oldDay == finalDay && oldMonth == finalMonth) return false
        if (!onlyWhitespaceOrCommaBetween(source, words, start - 1, end + if (finalMonth == null) 0 else 1)) return false
        if (keys[cueIndex] == "non" && !hasCommaOnBothSides(source, words, cueIndex - 1, cueIndex, cueIndex + 1))
            return false
        return (oldMonth != null && finalMonth != null) || oldMonth == null
    }

    private fun hasNumericDateCorrection(source: String, words: List<Token>, keys: List<String>, diff: WordDiff): Boolean {
        val start = diff.sourceStart
        val end = diff.sourceEnd
        var oldDayIndex = start
        val oldHasLe = keys.getOrNull(start) == "le"
        if (oldHasLe) oldDayIndex++
        val oldDay = keys.getOrNull(oldDayIndex)?.toIntOrNull()?.takeIf { it in 1..31 } ?: return false
        val oldMonth = keys.getOrNull(oldDayIndex + 1)?.takeIf { it in months }
        val cueIndex = oldDayIndex + 1 + if (oldMonth == null) 0 else 1
        if (cueIndex + 1 != end || keys.getOrNull(cueIndex) !in setOf("non", "pardon")) return false
        if ((start until end).any { hasSentenceBoundary(source, words, it, it + 1) }) return false

        var finalDayIndex = end
        val finalHasLe = keys.getOrNull(end) == "le"
        if (finalHasLe) finalDayIndex++
        val finalDay = keys.getOrNull(finalDayIndex)?.toIntOrNull()?.takeIf { it in 1..31 } ?: return false
        val finalMonth = keys.getOrNull(finalDayIndex + 1)?.takeIf { it in months }
        if (oldDay == finalDay && oldMonth == finalMonth) return false
        val firstDateWord = if (oldHasLe) oldDayIndex - 1 else oldDayIndex
        val lastDateWord = if (finalMonth == null) finalDayIndex else finalDayIndex + 1
        if (!onlyWhitespaceOrCommaBetween(source, words, firstDateWord, lastDateWord)) return false
        if (keys[cueIndex] == "non" && !hasCommaOnBothSides(source, words, cueIndex - 1, cueIndex, cueIndex + 1))
            return false
        return (oldHasLe && finalHasLe) || (oldMonth != null && finalMonth != null)
    }

    private fun hasCommaOnBothSides(
        source: String,
        words: List<Token>,
        beforeIndex: Int,
        cueIndex: Int,
        afterIndex: Int,
    ): Boolean {
        if (beforeIndex < 0 || afterIndex >= words.size) return false
        val before = source.substring(words[beforeIndex].end, words[cueIndex].start)
        val after = source.substring(words[cueIndex].end, words[afterIndex].start)
        return before.contains(',') && after.contains(',') &&
            before.all { it.isWhitespace() || it == ',' } &&
            after.all { it.isWhitespace() || it == ',' }
    }

    private fun onlyWhitespaceOrCommaBetween(source: String, words: List<Token>, first: Int, last: Int): Boolean {
        if (first < 0 || last >= words.size || first > last) return false
        return (first until last).all { left ->
            source.substring(words[left].end, words[left + 1].start).all { it.isWhitespace() || it == ',' }
        }
    }

    private fun sourceDeletionGapsAreWhitespaceOrComma(source: String, words: List<Token>, diff: WordDiff): Boolean {
        val left = if (diff.sourceStart == 0) 0 else words[diff.sourceStart - 1].end
        val right = if (diff.sourceEnd == words.size) source.length else words[diff.sourceEnd].start
        var cursor = left
        for (index in diff.sourceStart until diff.sourceEnd) {
            val gap = source.substring(cursor, words[index].start)
            if (gap.any { !it.isWhitespace() && it != ',' }) return false
            cursor = words[index].end
        }
        return source.substring(cursor, right).all { it.isWhitespace() || it == ',' }
    }

    private fun changesProtectedSource(
        source: String,
        sourceWords: List<Token>,
        protectedTerms: List<String>,
        change: CertifiedChange,
    ): Boolean {
        val ranges = GemmaConservativeEditing.caseProtectedRanges(source, protectedTerms)
        val diff = change.diff
        if (diff.sourceCount == 0) {
            val offset = if (diff.sourceStart == 0) 0 else sourceWords[diff.sourceStart - 1].end
            return ranges.any { offset >= it.first && offset <= it.last }
        }
        val first = sourceWords[diff.sourceStart].start
        val last = sourceWords[diff.sourceEnd - 1].end - 1
        return ranges.any { it.first <= last && first <= it.last }
    }

    private fun adjustSource(
        source: String,
        sourceWords: List<Token>,
        candidate: String,
        candidateWords: List<Token>,
        change: CertifiedChange,
    ): String? {
        val diff = change.diff
        return when (change.kind) {
            ChangeKind.REPLACE_WORD -> source.replaceRange(
                sourceWords[diff.sourceStart].start,
                sourceWords[diff.sourceStart].end,
                change.replacement,
            )
            ChangeKind.INSERT_BE -> {
                val offset = if (diff.sourceStart == 0) 0 else sourceWords[diff.sourceStart - 1].end
                source.replaceRange(offset, offset, change.replacement)
            }
            ChangeKind.DELETE_SPAN -> {
                val sourceStart = if (diff.sourceStart == 0) 0 else sourceWords[diff.sourceStart - 1].end
                val sourceEnd = if (diff.sourceEnd == sourceWords.size) source.length else sourceWords[diff.sourceEnd].start
                val candidateStart = if (diff.candidateStart == 0) 0 else candidateWords[diff.candidateStart - 1].end
                val candidateEnd = if (diff.candidateEnd == candidateWords.size) candidate.length else candidateWords[diff.candidateEnd].start
                val candidateGap = candidate.substring(candidateStart, candidateEnd)
                if (change.dateCorrection) {
                    if (!sourceDeletionGapsAreWhitespaceOrComma(source, sourceWords, diff)) return null
                    if (candidateGap.any { !it.isWhitespace() && it != ',' }) return null
                } else if (candidateGap.any { !it.isWhitespace() && it !in sentencePunctuation }) return null
                source.replaceRange(sourceStart, sourceEnd, candidateGap)
            }
        }
    }

    private fun separatedByWhitespace(source: String, words: List<Token>, left: Int, right: Int): Boolean {
        val gap = source.substring(words[left].end, words[right].start)
        return gap.isNotEmpty() && gap.all(Char::isWhitespace)
    }

    private fun isPlainPhraseGap(gap: String): Boolean = gap.any(Char::isWhitespace) &&
        gap.all { it.isWhitespace() || it == ',' }

    private fun hasSentenceBoundary(source: String, words: List<Token>, left: Int, right: Int): Boolean =
        source.substring(words[left].end, words[right].start).any { it in ".;:!?…\r\n" }

    private fun words(text: String): List<Token> = word.findAll(text).map {
        Token(it.value, it.range.first, it.range.last + 1)
    }.toList()
}
