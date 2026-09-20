package com.kafkasl.phonewhisper

import java.util.Locale

/**
 * Gemma proposes layout and sentence punctuation; only source lexemes reach the output.
 * This checks complete lexical fidelity, not whether every proposed list boundary is correct.
 * It deliberately rejects uncertain technical punctuation instead of guessing a correction.
 */
internal object GemmaFaithfulLayout {
    private val LEXEME = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val BULLET_PREFIX = Regex("^[ \\t]*([•*\\-])[ \\t]+(.*)$")
    private const val SENTENCE_PUNCTUATION = ",.;:!?…"
    private val LETTER_WORD = Regex("[\\p{L}\\p{M}]+")
    private val EMAIL_CLOSING = Regex("(?iu)^(?:bien\\s+cordialement|cordialement|sincèrement|respectueusement|merci|thanks|thank\\s+you|best\\s+regards|kind\\s+regards|warm\\s+regards|regards|yours\\s+sincerely)\\b")

    private data class ParsedStructure(val text: String, val bulletLines: Set<Int>)

    /** Metadata only; callers must already have accepted the complete result. */
    fun restoredWordCount(candidate: String, accepted: String): Int =
        (LEXEME.findAll(accepted).count() - LEXEME.findAll(candidate).count()).coerceAtLeast(0)

    fun accept(request: LocalFormatRequest, output: String?): String? {
        val policy = request.layoutPolicy() ?: return null
        if (output == null || output.length > 32768 || '\u0000' in output) return null
        val clean = LocalFormatOutput.accept(output) ?: return null
        policy.directResult?.takeIf { it == clean }?.let { return it }
        val structure = parseStructure(clean) ?: return null
        if (policy.kind == LocalLayoutKind.LIST && structure.bulletLines.isEmpty()) return null
        var candidate = structure.text
        val structureLineBreaks = structure.text.count { it == '\n' }
        val sourceWords = LEXEME.findAll(policy.source).toList()
        var candidateWords = LEXEME.findAll(candidate).toList()
        if (structure.bulletLines.isNotEmpty() && sourceWords.size != candidateWords.size) {
            restoreListConnectorOmission(
                policy.source,
                sourceWords,
                candidate,
                candidateWords,
                structure.bulletLines,
                request.protectedTerms,
            )?.let {
                candidate = it
                candidateWords = LEXEME.findAll(candidate).toList()
            }
        }
        if (policy.kind in setOf(LocalLayoutKind.EMAIL, LocalLayoutKind.TEXT) && sourceWords.size != candidateWords.size) {
            candidate = restoreSparseEmailOmissions(policy.source, sourceWords, candidate, candidateWords, request.validation == LocalFormatValidation.GEMMA_EDITING) ?: return null
            if (structure.bulletLines.isNotEmpty() && candidate.count { it == '\n' } != structureLineBreaks) return null
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
        val structured = applyBulletMarkers(text, structure.bulletLines) ?: return null
        return when (policy.kind) {
            LocalLayoutKind.LIST -> structured
            LocalLayoutKind.EMAIL, LocalLayoutKind.TEXT -> structured.replace(Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+"), "\n\n")
        }
    }

    /**
     * Restore exactly one omitted source connector at a local bullet boundary.
     * This is deliberately narrower than the mail omission path: a unique
     * lowercase/lexical `et` between two adjacent bullet items is the only
     * content word this layout adapter may add.
     */
    private fun restoreListConnectorOmission(
        source: String,
        sourceWords: List<MatchResult>,
        candidate: String,
        candidateWords: List<MatchResult>,
        bulletLines: Set<Int>,
        protectedTerms: List<String>,
    ): String? {
        if (sourceWords.size - candidateWords.size != 1 || candidateWords.isEmpty()) return null
        val sourceKeys = sourceWords.map { it.value.lowercase(Locale.ROOT) }
        val candidateKeys = candidateWords.map { it.value.lowercase(Locale.ROOT) }
        val forward = IntArray(candidateKeys.size)
        val backward = IntArray(candidateKeys.size)

        var cursor = 0
        for (index in candidateKeys.indices) {
            while (cursor < sourceKeys.size && sourceKeys[cursor] != candidateKeys[index]) cursor++
            if (cursor == sourceKeys.size) return null
            forward[index] = cursor++
        }
        cursor = sourceKeys.lastIndex
        for (index in candidateKeys.indices.reversed()) {
            while (cursor >= 0 && sourceKeys[cursor] != candidateKeys[index]) cursor--
            if (cursor < 0) return null
            backward[index] = cursor--
        }
        if (!forward.contentEquals(backward) || forward.first() != 0 || forward.last() != sourceWords.lastIndex) return null

        val aligned = forward.toSet()
        val omitted = sourceKeys.indices.singleOrNull { sourceIndex -> sourceIndex !in aligned } ?: return null
        if (!sourceWords[omitted].value.equals("et", ignoreCase = true)) return null
        if (omitted == 0 || omitted == sourceWords.lastIndex) return null
        if (sourceWords[omitted - 1].value.equals("ou", ignoreCase = true) ||
            sourceWords[omitted + 1].value.equals("ou", ignoreCase = true)
        ) return null
        if (isInsideQuote(source, sourceWords[omitted].range.first)) return null
        if (protectedTerms.any { term ->
                term.isNotBlank() && Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
                    .findAll(source)
                    .any { rangesOverlap(it.range, sourceWords[omitted].range) }
            }) return null

        val previousCandidate = forward.indexOf(omitted - 1)
        val nextCandidate = forward.indexOf(omitted + 1)
        if (previousCandidate < 0 || nextCandidate < 0) return null
        val previousWord = candidateWords[previousCandidate]
        val nextWord = candidateWords[nextCandidate]
        val previousLine = lineIndex(candidate, previousWord.range.first)
        val nextLine = lineIndex(candidate, nextWord.range.first)
        val sortedBulletLines = bulletLines.sorted()
        if (previousLine !in bulletLines || nextLine !in bulletLines ||
            sortedBulletLines.indexOf(nextLine) != sortedBulletLines.indexOf(previousLine) + 1
        ) return null

        val leftGap = source.substring(sourceWords[omitted - 1].range.last + 1, sourceWords[omitted].range.first)
        val rightGap = source.substring(sourceWords[omitted].range.last + 1, sourceWords[omitted + 1].range.first)
        if (!leftGap.any(::isSpace) || !rightGap.any(::isSpace)) return null

        val lineStart = candidate.lastIndexOf('\n', nextWord.range.first - 1) + 1
        if (candidate.substring(lineStart, nextWord.range.first).any { it != ' ' && it != '\t' && it != '\r' }) return null
        var insertion = lineStart
        while (insertion < nextWord.range.first && candidate[insertion] in " \t\r") insertion++
        return candidate.substring(0, insertion) + "et " + candidate.substring(insertion)
    }

    private fun lineIndex(text: String, offset: Int): Int = text.take(offset).count { it == '\n' }

    private fun isInsideQuote(text: String, offset: Int): Boolean =
        advanceQuote(null, text.substring(0, offset)) != null

    private fun rangesOverlap(left: IntRange, right: IntRange): Boolean =
        left.first <= right.last && right.first <= left.last

    private fun parseStructure(clean: String): ParsedStructure? {
        val candidate = StringBuilder(clean.length)
        val bulletLines = linkedSetOf<Int>()
        var quoteCloser: Char? = null
        var previousNonBlank: String? = null
        var previousNonBlankWasBullet = false
        var lineIndex = 0
        var start = 0
        while (start <= clean.length) {
            val newline = clean.indexOf('\n', start)
            val end = if (newline >= 0) newline else clean.length
            val rawLine = clean.substring(start, end)
            val line = rawLine.removeSuffix("\r")
            val insideQuote = quoteCloser != null
            val match = if (insideQuote) null else BULLET_PREFIX.matchEntire(line)
            if (match != null) {
                val marker = match.groupValues[1].single()
                val content = match.groupValues[2]
                if (content.isBlank()) return null
                if (isLikelyNumericOperator(marker, content, previousNonBlank, previousNonBlankWasBullet)) {
                    candidate.append(rawLine)
                    previousNonBlankWasBullet = false
                } else {
                    bulletLines += lineIndex
                    candidate.append(content)
                    previousNonBlankWasBullet = true
                }
            } else {
                candidate.append(rawLine)
                if (line.isNotBlank()) previousNonBlankWasBullet = false
            }
            if (newline >= 0) candidate.append('\n')
            if (line.isNotBlank()) previousNonBlank = line
            quoteCloser = advanceQuote(quoteCloser, line)
            lineIndex++
            if (newline < 0) break
            start = newline + 1
        }
        return ParsedStructure(candidate.toString(), bulletLines)
    }

    private fun isLikelyNumericOperator(
        marker: Char,
        content: String,
        previousNonBlank: String?,
        previousNonBlankWasBullet: Boolean,
    ): Boolean {
        if (marker !in setOf('*', '-')) return false
        if (previousNonBlankWasBullet) return false
        val previous = previousNonBlank?.trimEnd(' ', '\t', '\r') ?: return false
        val firstContent = content.firstOrNull { !isSpace(it) } ?: return false
        return previous.lastOrNull()?.isDigit() == true && firstContent.isDigit()
    }

    private fun advanceQuote(current: Char?, line: String): Char? {
        var closer = current
        var escaped = false
        for (index in line.indices) {
            val character = line[index]
            if (closer != null) {
                if ((closer == '"' || closer == '`') && character == '\\' && !escaped) {
                    escaped = true
                    continue
                }
                if ((closer == '\'' || closer == '’') && character == closer && isWordApostrophe(line, index)) {
                    continue
                }
                if (!escaped && character == closer) closer = null
                escaped = false
            } else {
                closer = when (character) {
                    '«' -> '»'
                    '“' -> '”'
                    '‘' -> '’'
                    '"', '`' -> character
                    '\'' -> if (!isWordApostrophe(line, index) &&
                        (index == 0 || !line[index - 1].isLetter())) '\'' else null
                    '’' -> if (!isWordApostrophe(line, index) &&
                        (index == 0 || !line[index - 1].isLetter())) '’' else null
                    else -> null
                }
            }
        }
        return closer
    }

    private fun isWordApostrophe(line: String, index: Int): Boolean =
        index > 0 && index < line.lastIndex &&
            line[index - 1].isLetter() && line[index + 1].isLetter()

    private fun applyBulletMarkers(text: String, bulletLines: Set<Int>): String? {
        if (bulletLines.isEmpty()) return text
        val lines = text.split('\n').toMutableList()
        for (index in bulletLines) {
            if (index !in lines.indices) return null
            val carriageReturn = lines[index].endsWith('\r')
            val line = if (carriageReturn) lines[index].dropLast(1) else lines[index]
            val content = line.dropWhile { it == ' ' || it == '\t' }
            if (content.isBlank()) return null
            lines[index] = "• $content" + if (carriageReturn) "\r" else ""
        }
        return lines.joinToString("\n")
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
