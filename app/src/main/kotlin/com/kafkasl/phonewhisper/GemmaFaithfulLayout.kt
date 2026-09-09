package com.kafkasl.phonewhisper

import java.util.Locale

/**
 * Gemma proposes layout and sentence punctuation; only source lexemes reach the output.
 * This checks complete lexical fidelity, not whether every proposed list boundary is correct.
 * It deliberately rejects uncertain technical punctuation instead of guessing a correction.
 */
internal object GemmaFaithfulLayout {
    private val LEXEME = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val BULLET = Regex("^[ \\t]*[•*\\-][ \\t]+(.+)$")
    private const val SENTENCE_PUNCTUATION = ",.;:!?…"

    fun accept(request: LocalFormatRequest, output: String?): String? {
        val policy = request.layoutPolicy() ?: return null
        if (output == null || output.length > 32768 || '\u0000' in output) return null
        val clean = LocalFormatOutput.accept(output) ?: return null
        policy.directResult?.takeIf { it == clean }?.let { return it }
        val candidate = when (policy.kind) {
            LocalLayoutKind.LIST -> {
                val lines = clean.lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return null
                lines.map { BULLET.matchEntire(it)?.groupValues?.get(1) ?: return null }.joinToString("\n")
            }
            LocalLayoutKind.EMAIL -> clean
        }
        val sourceWords = LEXEME.findAll(policy.source).toList()
        val candidateWords = LEXEME.findAll(candidate).toList()
        if (sourceWords.isEmpty() || sourceWords.size != candidateWords.size || sourceWords.size > 2048) return null
        if (sourceWords.indices.any {
                sourceWords[it].value.lowercase(Locale.ROOT) != candidateWords[it].value.lowercase(Locale.ROOT)
            }) return null

        val projected = StringBuilder()
        for (i in 0..sourceWords.size) {
            val sourceGap = gap(policy.source, sourceWords, i)
            val candidateGap = gap(candidate, candidateWords, i)
            val numbers = i > 0 && i < sourceWords.size &&
                sourceWords[i - 1].value.all(Char::isDigit) && sourceWords[i].value.all(Char::isDigit)
            val restored = projectGap(sourceGap, candidateGap, numbers, i > 0 && i < sourceWords.size) ?: return null
            projected.append(restored)
            if (i < sourceWords.size) projected.append(sourceWords[i].value)
        }
        val text = projected.toString().trim()
        return when (policy.kind) {
            LocalLayoutKind.LIST -> text.lines().filter { it.isNotBlank() }.joinToString("\n") { "• ${it.trim()}" }
            LocalLayoutKind.EMAIL -> text.replace(Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+"), "\n\n")
        }
    }

    private fun gap(text: String, words: List<MatchResult>, index: Int): String = text.substring(
        if (index == 0) 0 else words[index - 1].range.last + 1,
        if (index == words.size) text.length else words[index].range.first,
    )

    private fun projectGap(source: String, candidate: String, numbers: Boolean, interior: Boolean): String? {
        val sourceMarks = source.filterNot(::isSpace)
        val candidateMarks = candidate.filterNot(::isSpace)
        val joined = interior && sourceMarks.isNotEmpty() && source.none(::isSpace)
        val groupedNumber = numbers && source.any { it == '\u00a0' || it == '\u202f' }
        val preserveAllMarks = joined || groupedNumber || numbers
        val fixedSource = if (preserveAllMarks) sourceMarks else sourceMarks.filter { it !in SENTENCE_PUNCTUATION }
        val fixedCandidate = if (preserveAllMarks) candidateMarks else candidateMarks.filter { it !in SENTENCE_PUNCTUATION }
        if (canonicalMarks(fixedSource) != canonicalMarks(fixedCandidate)) return null
        // Breaking an address, decimal, apostrophe, or compound changes its interpretation.
        if (joined && candidate.any(::isSpace)) return null
        if (groupedNumber && candidate.any { it == '\n' || it == '\r' }) return null
        if (joined) return source
        var mark = 0
        return buildString {
            for (character in candidate) append(
                if (isSpace(character) || (!preserveAllMarks && character in SENTENCE_PUNCTUATION)) character
                else fixedSource[mark++]
            )
        }
    }

    private fun canonicalMarks(value: String): String = value.replace('’', '\'')
        .replace('\u2010', '-').replace('\u2011', '-')

    private fun isSpace(character: Char): Boolean = character.isWhitespace() || Character.isSpaceChar(character)
}
