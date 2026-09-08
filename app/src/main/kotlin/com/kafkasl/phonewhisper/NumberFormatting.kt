package com.kafkasl.phonewhisper

import android.icu.text.MessageFormat
import java.text.Normalizer
import java.util.Locale

internal enum class NumberStyle { DIGITS, WORDS, UNCHANGED }

/** Local number presentation without a LLM; reuses Android's public ICU MessageFormat API. */
internal object NumberFormatting {
    private val french by lazy { engine(DictationLanguage.FRENCH, Locale.FRANCE) }
    private val english by lazy { engine(DictationLanguage.ENGLISH, Locale.US) }

    fun apply(text: String, language: DictationLanguage, style: NumberStyle, protectedTerms: List<String> = emptyList()): String {
        if (style == NumberStyle.UNCHANGED || text.isBlank()) return text
        val engine = if (language == DictationLanguage.FRENCH) french else english
        return synchronized(engine) { engine.apply(text, style, protectedTerms) }
    }

    private fun engine(language: DictationLanguage, locale: Locale): NumberFormattingEngine {
        val formatter by lazy { MessageFormat("{0,spellout}", locale) }
        return NumberFormattingEngine(language) { number -> formatter.format(arrayOf(number)) }
    }
}

/**
 * Conservative cardinal-only conversion: 0..999,999,999 and up to six fractional digits.
 * Parsing reuses ICU spellings, normalizes spaces/hyphens and common ASR variants, then
 * checks the entire cardinal by round-trip. Invalid sequences are never partly rewritten.
 * Articles, ordinals, obvious names/IDs, URLs, dates/times and leading zeroes are retained.
 * This is not named-entity recognition: lowercase names resembling numbers remain ambiguous.
 * Words use the locale's default cardinal form; grammatical gender is not inferred.
 * The injectable spellout permits real ICU4J JVM tests without adding it to the APK.
 */
internal class NumberFormattingEngine(
    private val language: DictationLanguage,
    private val spellout: (Long) -> String,
) {
    private val french = language == DictationLanguage.FRENCH
    private val decimalWord = if (french) "virgule" else "point"
    private val minusWord = if (french) "moins" else "minus"
    private val connector = if (french) "et" else "and"
    private val thousandWord = if (french) "mille" else "thousand"
    private val lexicon by lazy { (0L..999L).associateBy { signature(spellout(it)) } }
    private val numberWords = ((if (french)
        "zero un une deux trois quatre cinq six sept huit neuf dix onze douze treize quatorze quinze seize vingt vingts trente quarante cinquante soixante cent cents mille et virgule moins septante huitante nonante"
    else
        "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety hundred thousand and point minus") +
        " million millions milliard milliards billion billions trillion trillions").split(' ').toSet()

    fun apply(text: String, style: NumberStyle, protectedTerms: List<String> = emptyList()): String {
        if (style == NumberStyle.UNCHANGED || text.isBlank()) return text
        val protected = protectedRanges(text) + protectedTerms.filter { it.isNotBlank() }.flatMap { term -> Regex(Regex.escape(term)).findAll(text).map { it.range }.toList() }
        return if (style == NumberStyle.WORDS) digitsToWords(text, protected)
        else wordsToDigits(text, protected)
    }

    private fun digitsToWords(text: String, protected: List<IntRange>): String =
        digits.replace(text) { match ->
            if (isProtected(match.range, protected) || identifierContext(text, match.range.first)) {
                match.value
            } else spellDigits(match.value) ?: match.value
        }

    private fun spellDigits(raw: String): String? {
        val compact = raw.replace(groupSpaces, "").replace('−', '-')
        if (compact.startsWith('+')) return null
        val unsigned = compact.removePrefix("-")
        val separator = if (french) ',' else '.'
        if (unsigned.contains(if (french) '.' else ',')) return null
        val parts = unsigned.split(separator)
        if (parts.size > 2 || parts[0].length > 1 && parts[0].startsWith('0')) return null
        val whole = parts[0].toLongOrNull()?.takeIf { it <= MAX_CARDINAL } ?: return null
        val fraction = parts.getOrNull(1)
        if (fraction != null && fraction.length !in 1..6) return null
        val sign = if (compact.startsWith('-')) "$minusWord " else ""
        return sign + spellout(whole) + if (fraction == null) "" else
            " $decimalWord " + fraction.map { spellout(it.digitToInt().toLong()) }.joinToString(" ")
    }

    private fun wordsToDigits(text: String, protected: List<IntRange>): String {
        val tokens = word.findAll(text).toList()
        val result = StringBuilder(text.length)
        var copiedUntil = 0
        var index = 0
        while (index < tokens.size) {
            val first = tokens[index]
            if (!isNumberToken(first.value) || splitWords(first.value) == listOf(connector)) {
                index++
                continue
            }
            var endIndex = index
            while (endIndex + 1 < tokens.size &&
                numberGap.matches(text.substring(tokens[endIndex].range.last + 1, tokens[endIndex + 1].range.first)) &&
                isNumberToken(tokens[endIndex + 1].value)
            ) endIndex++
            var lastNumberIndex = endIndex
            while (lastNumberIndex > index && splitWords(tokens[lastNumberIndex].value) == listOf(connector)) lastNumberIndex--
            val last = tokens[lastNumberIndex]
            val range = first.range.first..last.range.last
            val parts = splitWords(text.substring(range))
            val named = (index..lastNumberIndex).any { position ->
                tokens[position].value.first().isUpperCase() &&
                    !(position == index && sentenceStart(text, range.first))
            }
            val nextToken = tokens.getOrNull(lastNumberIndex + 1)
            val nextNamed = nextToken != null && nextToken.value.first().isUpperCase() &&
                numberGap.matches(text.substring(range.last + 1, nextToken.range.first))
            val replacement = if (isProtected(range, protected) || named || nextNamed ||
                identifierContext(text, range.first) || ambiguousSingleton(text, range, parts)
            ) null else parseWords(parts)
            if (replacement != null) {
                result.append(text, copiedUntil, range.first).append(replacement)
                copiedUntil = range.last + 1
            }
            index = endIndex + 1
        }
        return result.append(text, copiedUntil, text.length).toString()
    }

    private fun parseWords(original: List<String>): String? {
        if (original.size > 28) return null
        val negative = original.firstOrNull() == minusWord
        val parts = if (negative) original.drop(1) else original
        val decimalIndex = parts.indexOf(decimalWord)
        val integerWords = if (decimalIndex < 0) parts else parts.take(decimalIndex)
        val whole = parseCardinal(integerWords) ?: return null
        val fraction = if (decimalIndex < 0) null else {
            val tail = parts.drop(decimalIndex + 1)
            if (tail.isEmpty() || decimalWord in tail) return null
            val individual = tail.map { lexicon[signature(it)]?.takeIf { value -> value in 0..9 } }
            if (individual.all { it != null }) individual.joinToString("")
            else parseCardinal(tail)?.toString() ?: return null
        }
        if (fraction != null && fraction.length !in 1..6) return null
        val sign = if (negative) "-" else ""
        return sign + whole + if (fraction == null) "" else (if (french) "," else ".") + fraction
    }

    private fun parseCardinal(words: List<String>): Long? {
        val normalized = signature(words.joinToString(" "))
        if (normalized.isEmpty()) return null
        lexicon[normalized]?.let { return it }
        var remainder = normalized.split(' ')
        var value = 0L
        for ((scaleWord, scale) in listOf("million" to 1_000_000L, thousandWord to 1_000L)) {
            val at = remainder.indexOf(scaleWord)
            if (at < 0) continue
            val prefix = remainder.take(at).joinToString(" ")
            val multiplier = if (prefix.isEmpty() && french && scale == 1_000L) 1L
                else lexicon[prefix]?.takeIf { it in 1..999 } ?: return null
            value += multiplier * scale
            remainder = remainder.drop(at + 1)
        }
        if (remainder.isNotEmpty()) value += lexicon[remainder.joinToString(" ")] ?: return null
        if (value !in 0..MAX_CARDINAL || signature(spellout(value)) != normalized) return null
        return value
    }

    private fun ambiguousSingleton(text: String, range: IntRange, parts: List<String>): Boolean {
        if (parts.size != 1) return false
        if (french && parts[0] in setOf("un", "une")) return true
        if (french && parts[0] == "neuf") {
            val nextToken = word.find(text, range.last + 1) ?: return true
            if (!numberGap.matches(text.substring(range.last + 1, nextToken.range.first))) return true
            val next = nextToken.value.lowercase(Locale.ROOT)
            return !next.endsWith('s') || next in setOf("dans", "sans", "mais", "plus", "sous", "des", "les", "tous", "vous", "nous", "pas", "après", "alors")
        }
        if (!french && parts[0] == "one") {
            val before = word.findAll(text.substring(0, range.first)).lastOrNull()?.value.orEmpty()
            val after = word.find(text, range.last + 1)?.value.orEmpty()
            return before.lowercase(Locale.ROOT) in setOf("no", "any", "some", "every", "the") ||
                after.lowercase(Locale.ROOT) in setOf("another", "of")
        }
        return false
    }

    private fun isNumberToken(token: String): Boolean =
        splitWords(token).all { it in numberWords } && !token.contains('\'') && !token.contains('’')

    private fun splitWords(value: String): List<String> = wordSeparators.split(
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(accents, ""),
    ).filter { it.isNotEmpty() }

    private fun signature(value: String): String = splitWords(value).mapNotNull {
        when (it) {
            connector -> null
            "cents" -> if (french) "cent" else it
            "vingts" -> if (french) "vingt" else it
            "une" -> if (french) "un" else it
            "millions" -> "million"
            else -> it
        }
    }.joinToString(" ")

    private fun sentenceStart(text: String, at: Int): Boolean {
        val before = text.substring(0, at).trimEnd(' ', '\t', '\u00a0', '\u202f')
        return before.isEmpty() || before.last() in ".!?\n:"
    }

    private fun identifierContext(text: String, at: Int): Boolean =
        identifierLabel.containsMatchIn(text.substring(0, at).takeLast(64))

    private fun protectedRanges(text: String): List<IntRange> =
        protectedPatterns.flatMap { pattern -> pattern.findAll(text).map { it.range }.toList() }

    private fun isProtected(range: IntRange, protected: List<IntRange>): Boolean =
        protected.any { range.first <= it.last && it.first <= range.last }

    companion object {
        private const val MAX_CARDINAL = 999_999_999L
        private val accents = Regex("\\p{M}+")
        private val groupSpaces = Regex("[ \\u00a0\\u202f]")
        private val wordSeparators = Regex("[\\s\\u00a0\\u202f\\-‐‑]+")
        private val numberGap = Regex("[ \\t\\u00a0\\u202f\\-‐‑]+")
        private val word = Regex("[\\p{L}]+(?:[\\-‐‑’'][\\p{L}]+)*")
        private val digits = Regex("(?<![\\p{L}\\p{N}_])[-−+]?(?:[0-9]{1,3}(?:[ \\u00a0\\u202f][0-9]{3})+|[0-9]+)(?:[.,][0-9]+)?(?![\\p{L}\\p{N}_])")
        private val identifierLabel = Regex(
            "(?:\\b(?:code|référence|reference|ref|numéro|numero|identifiant|téléphone|telephone|tél|tel|phone|id|pin|postcode|postal|version|modèle|model)|n°)\\s*[:#]?\\s*$",
            RegexOption.IGNORE_CASE,
        )
        private val protectedPatterns = listOf(
            Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE),
            Regex("[\\p{L}\\p{N}_.+\\-]+@[\\p{L}\\p{N}.\\-]+"),
            Regex("(?<![\\p{L}\\p{N}_])\\+?[0-9](?:[ \\u00a0\\u202f().\\-]*[0-9]){7,}(?![\\p{L}\\p{N}_])"),
            Regex("[0-9]{1,4}(?:[/:\\-][0-9]{1,4})+|[0-9]{1,4}(?:\\.[0-9]{1,4}){2,}"),
            Regex("\\b[\\p{L}_][\\p{L}\\p{N}_.\\-]*[0-9][\\p{L}\\p{N}_.\\-]*\\b"),
        )
    }
}
