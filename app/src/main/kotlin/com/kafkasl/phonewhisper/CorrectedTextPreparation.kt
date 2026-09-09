package com.kafkasl.phonewhisper

import java.util.Locale

/** The corrected-text source is also the fallback if the model fails or rewrites too much. */
internal object CorrectedTextPreparation {
    data class Result(val text: String, val removed: Int = 0)
    private val filler = Regex("(?iu)(?<![\\p{L}\\p{N}_’'-])(?:euh+|heu+|uh+|um+)(?![\\p{L}\\p{N}_’'-])")
    private val mention = Regex("(?iu)\\b(?:mot|word|interjection)\\s*$")

    fun prepare(text: String, formatId: String, protectedTerms: List<String> = emptyList(),
        manualRanges: List<IntRange> = emptyList()): Result {
        if (formatId != "corrected" || text.length > 32768) return Result(text)
        val locked = protectedRanges(text, protectedTerms) + manualRanges
        fun protected(range: IntRange) = locked.any { it.first <= range.last && it.last >= range.first }
        val matches = filler.findAll(text).filter {
            !protected(it.range) && it.value != it.value.uppercase(Locale.ROOT) &&
                !mention.containsMatchIn(text.take(it.range.first).takeLast(32))
        }.toList()
        if (matches.isEmpty()) return Result(text)
        val removals = mutableListOf<IntRange>()
        for (match in matches) {
            var start = match.range.first
            var end = match.range.last + 1
            while (start > 0 && text[start - 1] in " \t," && !protected(start - 1..start - 1)) start--
            while (end < text.length && text[end] in " \t," && !protected(end..end)) end++
            if ((start == 0 || text[start - 1] in ".!?\n") && end < text.length &&
                text[end] in ".…" && !protected(end..end)) {
                end++
                while (end < text.length && text[end] in " \t" && !protected(end..end)) end++
            }
            if (removals.isNotEmpty() && start <= removals.last().last + 1)
                removals[removals.lastIndex] = removals.last().first..maxOf(removals.last().last, end - 1)
            else removals.add(start until end)
        }
        val capitalizeAt = removals.filter { range ->
            val next = range.last + 1
            next < text.length && text[next].isLowerCase() && !protected(next..next) &&
                (range.first == 0 && text.first().isUpperCase() ||
                    range.first > 0 && text[range.first - 1] in ".!?\n")
        }.map { it.last + 1 }.toSet()
        val result = buildString {
            fun remaining(start: Int, end: Int) {
                val piece = text.substring(start, end)
                append(if (start in capitalizeAt && piece.isNotEmpty()) piece.replaceFirstChar { it.titlecase() } else piece)
            }
            var previous = 0
            for (range in removals) {
                remaining(previous, range.first)
                val end = range.last + 1
                // A comma may separate real enumerated items; retain one between clauses/items.
                if (range.first > 0 && end < text.length && !text[range.first - 1].isWhitespace() &&
                    text[range.first - 1] !in ".!?;:\n," &&
                    text[end] !in ".!?;:\n," && text.substring(range).contains(',') &&
                    lastOrNull { !it.isWhitespace() } !in listOf(null, ',', '.', '!', '?', ';', ':'))
                    append(',')
                if (range.first > 0 && end < text.length && !text[range.first - 1].isWhitespace() &&
                    !text[end].isWhitespace() && text[end] !in ".!?;:\n") append(' ')
                previous = end
            }
            remaining(previous, text.length)
        }
        // A dictation consisting solely of an interjection remains publishable.
        if (result.none(Char::isLetterOrDigit)) return Result(text)
        return Result(result, matches.size)
    }

    private fun protectedRanges(text: String, terms: List<String>): List<IntRange> = buildList {
        var start = -1
        var closing = ' '
        text.forEachIndexed { i, char ->
            if (start >= 0) {
                val apostropheInWord = char == '\'' && i > 0 && i < text.lastIndex &&
                    text[i - 1].isLetter() && text[i + 1].isLetter()
                if (char == closing && !apostropheInWord) { add(start..i); start = -1 }
            } else if (char in "\"«“`" || char == '\'' && (i == 0 || text[i - 1].isWhitespace())) {
                start = i
                closing = when (char) { '«' -> '»'; '“' -> '”'; else -> char }
            }
        }
        if (start >= 0) add(start..text.lastIndex)
        Regex("\\S*(?:https?://|www\\.|@|/|\\\\|\\.[A-Za-z]{2,8})\\S*")
            .findAll(text).forEach { add(it.range) }
        terms.filter(String::isNotBlank).forEach { term ->
            Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(text).forEach { add(it.range) }
        }
    }
}
