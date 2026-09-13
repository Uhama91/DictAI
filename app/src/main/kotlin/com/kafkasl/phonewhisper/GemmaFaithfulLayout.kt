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
    private val LETTER_WORD = Regex("[\\p{L}\\p{M}]+")
    private val EMAIL_CLOSING = Regex("(?iu)^(?:bien\\s+cordialement|cordialement|sincèrement|respectueusement|merci|thanks|thank\\s+you|best\\s+regards|kind\\s+regards|warm\\s+regards|regards|yours\\s+sincerely)\\b")

    /** Metadata only; callers must already have accepted the complete result. */
    fun restoredWordCount(candidate: String, accepted: String): Int =
        (LEXEME.findAll(accepted).count() - LEXEME.findAll(candidate).count()).coerceAtLeast(0)

    fun accept(request: LocalFormatRequest, output: String?): String? {
        val policy = request.layoutPolicy() ?: return null
        if (output == null || output.length > 32768 || '\u0000' in output) return null
        val clean = LocalFormatOutput.accept(output) ?: return null
        policy.directResult?.takeIf { it == clean }?.let { return it }
        var candidate = when (policy.kind) {
            LocalLayoutKind.LIST -> {
                val lines = clean.lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return null
                lines.map { BULLET.matchEntire(it)?.groupValues?.get(1) ?: return null }.joinToString("\n")
            }
            LocalLayoutKind.EMAIL, LocalLayoutKind.TEXT -> clean
        }
        val sourceWords = LEXEME.findAll(policy.source).toList()
        var candidateWords = LEXEME.findAll(candidate).toList()
        if (policy.kind in setOf(LocalLayoutKind.EMAIL, LocalLayoutKind.TEXT) && sourceWords.size != candidateWords.size) {
            candidate = restoreSparseEmailOmissions(policy.source, sourceWords, candidate, candidateWords, request.validation == LocalFormatValidation.GEMMA_EDITING) ?: return null
            candidateWords = LEXEME.findAll(candidate).toList()
        }
        if (sourceWords.isEmpty() || sourceWords.size != candidateWords.size || sourceWords.size > 2048) return null
        if (sourceWords.indices.any {
                sourceWords[it].value.lowercase(Locale.ROOT) != candidateWords[it].value.lowercase(Locale.ROOT)
            }) return null

        val preserveCase = if (request.validation == LocalFormatValidation.GEMMA_EDITING)
            GemmaConservativeEditing.caseProtectedRanges(policy.source, request.protectedTerms) else emptyList()
        val projected = StringBuilder()
        for (i in 0..sourceWords.size) {
            val sourceGap = gap(policy.source, sourceWords, i)
            val candidateGap = gap(candidate, candidateWords, i)
            val numbers = i > 0 && i < sourceWords.size &&
                sourceWords[i - 1].value.all(Char::isDigit) && sourceWords[i].value.all(Char::isDigit)
            val restored = projectGap(sourceGap, candidateGap, numbers, i > 0 && i < sourceWords.size) ?: return null
            projected.append(restored)
            if (i < sourceWords.size) projected.append(
                if (request.validation == LocalFormatValidation.GEMMA_EDITING && preserveCase.none {
                        it.first <= sourceWords[i].range.last && it.last >= sourceWords[i].range.first
                    }) candidateWords[i].value else sourceWords[i].value
            )
        }
        val text = projected.toString().trim()
        return when (policy.kind) {
            LocalLayoutKind.LIST -> text.lines().filter { it.isNotBlank() }.joinToString("\n") { "• ${it.trim()}" }
            LocalLayoutKind.EMAIL, LocalLayoutKind.TEXT -> text.replace(Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+"), "\n\n")
        }
    }

    /**
     * Some well-formatted mails drop a few dictated words. Restore them from the source;
     * never accept the shortened text. A unique subsequence alignment excludes replacements,
     * additions, reordered words and ambiguous repeated phrases. All strict gap checks still run.
     */
    private fun restoreSparseEmailOmissions(
        source: String, sourceWords: List<MatchResult>, candidate: String, candidateWords: List<MatchResult>,
        surfaceEditing: Boolean,
    ): String? {
        val missing = sourceWords.size - candidateWords.size
        if (missing !in 1..(if (surfaceEditing) 6 else 3) || missing * 20 > sourceWords.size || candidateWords.isEmpty()) return null
        val sourceKeys = sourceWords.map { it.value.lowercase(Locale.ROOT) }
        val candidateKeys = candidateWords.map { it.value.lowercase(Locale.ROOT) }
        val forward = IntArray(candidateKeys.size)
        val backward = IntArray(candidateKeys.size)
        var cursor = 0
        for (i in candidateKeys.indices) {
            while (cursor < sourceKeys.size && sourceKeys[cursor] != candidateKeys[i]) cursor++
            if (cursor == sourceKeys.size) return null
            forward[i] = cursor++
        }
        cursor = sourceKeys.lastIndex
        for (i in candidateKeys.indices.reversed()) {
            while (cursor >= 0 && sourceKeys[cursor] != candidateKeys[i]) cursor--
            if (cursor < 0) return null
            backward[i] = cursor--
        }
        if (!forward.contentEquals(backward) || forward.first() != 0 || forward.last() != sourceWords.lastIndex) return null

        val restored = StringBuilder(candidate.substring(0, candidateWords.first().range.first))
        for (i in candidateWords.indices) {
            var restoreSourceCase = false
            if (i > 0) {
                val previous = forward[i - 1]
                val next = forward[i]
                val proposedGap = gap(candidate, candidateWords, i)
                if (next == previous + 1) restored.append(proposedGap)
                else {
                    // Never infer an omitted digit, address part, contraction or quoted fragment.
                    if ((previous + 1 until next).any { !LETTER_WORD.matches(sourceWords[it].value) }) return null
                    if ((previous + 1..next).any { index ->
                            val sourceGap = gap(source, sourceWords, index)
                            val clitic = surfaceEditing && sourceWords[index - 1].value.equals("c", true) &&
                                sourceWords[index].value.equals("est", true) && sourceGap in setOf("'", "’")
                            !clitic && (sourceGap.none(::isSpace) || sourceGap.any { !isSpace(it) && it !in SENTENCE_PUNCTUATION })
                        }) return null
                    if (proposedGap.any { !isSpace(it) && it !in SENTENCE_PUNCTUATION }) return null
                    val start = sourceWords[previous].range.last + 1
                    if ('\n' in proposedGap || '\r' in proposedGap) {
                        // A word omitted just before an explicit sign-off belongs to the body.
                        // Other missing-word paragraph boundaries remain too uncertain to apply.
                        if (!EMAIL_CLOSING.containsMatchIn(candidate.substring(candidateWords[i].range.first))) return null
                        val omittedStart = sourceWords[previous + 1].range.first
                        val closing = EMAIL_CLOSING.find(source.substring(omittedStart))
                        if (closing != null && closing.range.last >= sourceWords[next].range.first - omittedStart) return null
                        restored.append(source.substring(start, sourceWords[next - 1].range.last + 1))
                        restored.append(proposedGap)
                    } else {
                        restored.append(source.substring(start, sourceWords[next].range.first))
                        // Restoring a source clause can replace the model's sentence break.
                        restoreSourceCase = surfaceEditing && sourceWords[next].value.first().isLowerCase()
                    }
                }
            }
            restored.append(if (restoreSourceCase) sourceWords[forward[i]].value else candidateWords[i].value)
        }
        restored.append(candidate.substring(candidateWords.last().range.last + 1))
        return restored.toString()
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
