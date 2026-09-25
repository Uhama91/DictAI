package com.kafkasl.phonewhisper.meeting

import java.util.Locale

/** A bounded, utterance-local anchor for text the user has already edited. */
class MeetingEditAnchor private constructor(
    internal val sourceWords: List<MeetingWord>,
    internal val sourceText: String,
    val protectedThroughMs: Long?,
) {
    data class Alignment<T>(
        val isAligned: Boolean,
        val value: T,
    )

    internal data class WordMatch(
        val isAligned: Boolean,
        val matchedIndices: List<Int>,
    ) {
        val lastIndex: Int? get() = matchedIndices.lastOrNull()
    }

    internal data class TextMatch(
        val isAligned: Boolean,
        val startChar: Int = -1,
        val endChar: Int = -1,
        val nextTokenIndex: Int = 0,
    )

    internal data class TextWordMatch(
        val isAligned: Boolean,
        val matchedWordIndices: List<Int> = emptyList(),
    ) {
        val lastWordIndex: Int? get() = matchedWordIndices.lastOrNull()
        val firstWordIndex: Int? get() = matchedWordIndices.firstOrNull()
    }

    fun continuationWords(revisedWords: List<MeetingWord>): Alignment<List<MeetingWord>> {
        val match = locateWords(revisedWords)
        val lastIndex = match.lastIndex
        return Alignment(
            isAligned = match.isAligned,
            value = if (lastIndex == null) emptyList() else revisedWords.drop(lastIndex + 1),
        )
    }

    /** Returns the raw recognized suffix after the old words, preserving punctuation and Unicode. */
    fun continuationText(revisedTranscript: String): Alignment<String> {
        val match = locateText(revisedTranscript)
        return if (match.isAligned) {
            Alignment(true, revisedTranscript.substring(match.endChar).trimStart())
        } else {
            Alignment(false, "")
        }
    }

    /** Matches the whole final anchor token in order, tolerating removed earlier words. */
    internal fun locateText(revisedTranscript: String, fromTokenIndex: Int = 0): TextMatch {
        val sourceTokens = tokenize(sourceText)
        val anchorTokens = sourceTokens.filter { it.value.hasWordCharacter() }
        val revisedAllTokens = tokenize(revisedTranscript)
        val revisedTokens = revisedAllTokens.filter { it.value.hasWordCharacter() }
        if (anchorTokens.isEmpty() || revisedTokens.size > MAX_TEXT_TOKENS) return TextMatch(false)

        val startAt = fromTokenIndex.coerceIn(0, revisedTokens.size)
        val finalAnchor = anchorTokens.last().value.normalized()
        val candidateEnds = revisedTokens.indices.filter { index ->
            index >= startAt && revisedTokens[index].value.normalized() == finalAnchor
        }.filter { endIndex ->
            var cursor = startAt
            anchorTokens.dropLast(1).forEach { anchorToken ->
                val found = (cursor until endIndex).firstOrNull { index ->
                    revisedTokens[index].value.normalized() == anchorToken.value.normalized()
                }
                if (found != null) cursor = found + 1
            }
            cursor <= endIndex
        }
        // More than one possible end means the transcript does not identify which occurrence
        // belongs to the protected edit. Preserve it and let the caller report UNRESOLVED.
        if (candidateEnds.size != 1) return TextMatch(false)

        val lastTokenIndex = candidateEnds.single()
        var cursor = startAt
        var first: Token? = null
        anchorTokens.dropLast(1).forEach { anchorToken ->
            val found = (cursor until lastTokenIndex).firstOrNull { index ->
                revisedTokens[index].value.normalized() == anchorToken.value.normalized()
            }
            if (found != null) {
                if (first == null) first = revisedTokens[found]
                cursor = found + 1
            }
        }
        if (first == null) first = revisedTokens[lastTokenIndex]
        val last = revisedTokens[lastTokenIndex]
        var endChar = last.end
        val terminalMarks = terminalPunctuation(sourceTokens).map { it.value }
        if (terminalMarks.isNotEmpty()) {
            val lastTokenPosition = revisedAllTokens.indexOfFirst { it.start == last.start && it.end == last.end }
            if (lastTokenPosition < 0) return TextMatch(false)
            val boundaryMarks = revisedAllTokens.drop(lastTokenPosition + 1)
                .takeWhile { !it.value.hasWordCharacter() }
            if (boundaryMarks.map { it.value } != terminalMarks) return TextMatch(false)
            endChar = boundaryMarks.lastOrNull()?.end ?: return TextMatch(false)
        }

        return TextMatch(
            isAligned = true,
            startChar = first.start,
            endChar = endChar,
            nextTokenIndex = lastTokenIndex + 1,
        )
    }

    internal fun locateWords(revisedWords: List<MeetingWord>): WordMatch {
        if (sourceWords.isEmpty() || revisedWords.size > MAX_WORDS) return WordMatch(false, emptyList())
        val sourceTokens = timedTokens(sourceWords).toMutableList()
        val revisedTokens = timedTokens(revisedWords)
        if (sourceTokens.size > MAX_ALIGNMENT_TOKENS || revisedTokens.size > MAX_ALIGNMENT_TOKENS) {
            return WordMatch(false, emptyList())
        }

        val terminalMarks = terminalPunctuation(tokenize(sourceText)).map { it.value }
        if (terminalMarks.isNotEmpty()) {
            val sourceWordMarks = terminalPunctuationValues(sourceTokens.map { it.value })
            when {
                sourceWordMarks == terminalMarks -> Unit
                sourceWordMarks.isEmpty() -> {
                    val lastLexical = sourceTokens.lastOrNull { it.value.hasWordCharacter() }
                        ?: return WordMatch(false, emptyList())
                    terminalMarks.forEach { mark ->
                        sourceTokens += TimedToken(mark, lastLexical.wordIndex, lastLexical.startMs, lastLexical.endMs)
                    }
                }
                else -> return WordMatch(false, emptyList())
            }
        }

        val matches = mutableListOf<Int>()
        var nextIndex = 0
        var lastMatchedSource = -1
        for ((sourceIndex, source) in sourceTokens.withIndex()) {
            val candidate = (nextIndex until revisedTokens.size).firstOrNull { index ->
                val revised = revisedTokens[index]
                wordsMatch(source, revised)
            }
            if (candidate == null) continue
            matches += revisedTokens[candidate].wordIndex
            nextIndex = candidate + 1
            lastMatchedSource = sourceIndex
        }
        val aligned = lastMatchedSource == sourceTokens.lastIndex
        return WordMatch(aligned, if (aligned) matches.distinct() else emptyList())
    }

    /** Maps a transcript-text anchor back onto word timings when the original turn had no words. */
    internal fun locateTextWords(
        revisedWords: List<MeetingWord>,
        fromWordIndex: Int = 0,
        revisedTranscript: String? = null,
    ): TextWordMatch {
        val anchorTokens = tokenize(sourceText).filter { it.value.hasWordCharacter() }
        if (anchorTokens.isEmpty() || revisedWords.size > MAX_WORDS) return TextWordMatch(false)
        var tokenSequence = 0
        val revisedTokens = revisedWords.withIndex().drop(fromWordIndex.coerceIn(0, revisedWords.size))
            .flatMap { (wordIndex, word) ->
                tokenize(word.text).map { token ->
                    IndexedToken(token.value, wordIndex, tokenSequence++)
                }
            }
        if (revisedTokens.size > MAX_TEXT_TOKENS) return TextWordMatch(false)
        val revisedLexicalTokens = revisedTokens.filter { it.value.hasWordCharacter() }

        val finalAnchor = anchorTokens.last().value.normalized()
        val candidateEnds = revisedLexicalTokens.indices.filter { index ->
            revisedLexicalTokens[index].value.normalized() == finalAnchor
        }
        if (candidateEnds.size != 1) return TextWordMatch(false)

        val lastTokenIndex = candidateEnds.single()
        var nextToken = 0
        val matchedWords = mutableListOf<Int>()
        anchorTokens.dropLast(1).forEach { anchorToken ->
            val found = (nextToken until lastTokenIndex).firstOrNull { index ->
                revisedLexicalTokens[index].value.normalized() == anchorToken.value.normalized()
            }
            if (found != null) {
                matchedWords += revisedLexicalTokens[found].wordIndex
                nextToken = found + 1
            }
        }
        val lastLexicalToken = revisedLexicalTokens[lastTokenIndex]
        matchedWords += lastLexicalToken.wordIndex

        val terminalMarks = terminalPunctuation(tokenize(sourceText)).map { it.value }
        if (terminalMarks.isNotEmpty()) {
            val transcript = revisedTranscript ?: return TextWordMatch(false)
            val prefixLexicalCount = revisedWords.take(fromWordIndex.coerceIn(0, revisedWords.size))
                .sumOf { word -> tokenize(word.text).count { it.value.hasWordCharacter() } }
            val textMatch = locateText(transcript, prefixLexicalCount)
            val matchedTranscriptTokenIndex = textMatch.nextTokenIndex - 1
            if (!textMatch.isAligned || matchedTranscriptTokenIndex - prefixLexicalCount != lastTokenIndex) {
                return TextWordMatch(false)
            }

            val boundaryMarks = revisedTokens.drop(lastLexicalToken.sequenceIndex + 1)
                .takeWhile { !it.value.hasWordCharacter() }
            if (boundaryMarks.isNotEmpty() && boundaryMarks.map { it.value } != terminalMarks) {
                return TextWordMatch(false)
            }
            matchedWords += boundaryMarks.lastOrNull()?.wordIndex ?: lastLexicalToken.wordIndex
        }

        return TextWordMatch(true, matchedWords.distinct())
    }

    companion object {
        private const val MAX_WORDS = 512
        private const val MAX_TEXT_TOKENS = 512
        private const val MAX_ALIGNMENT_TOKENS = 1_024
        private val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}]+)?|[^\\s]")

        fun capture(
            sourceWords: List<MeetingWord>,
            editedText: String,
            sourceText: String = sourceWords.joinToString(" ") { it.text }.ifBlank { editedText },
        ): MeetingEditAnchor {
            val boundedWords = sourceWords.takeLast(MAX_WORDS).toList()
            return MeetingEditAnchor(
                sourceWords = boundedWords,
                sourceText = sourceText.takeLast(8_192),
                protectedThroughMs = boundedWords.maxOfOrNull { it.endMs },
            )
        }

        internal fun tokenize(text: String): List<Token> =
            tokenPattern.findAll(text).take(MAX_TEXT_TOKENS + 1).map { match ->
                Token(match.value, match.range.first, match.range.last + 1)
            }.toList()

        private fun timedTokens(words: List<MeetingWord>): List<TimedToken> {
            val result = ArrayList<TimedToken>(minOf(words.size, MAX_ALIGNMENT_TOKENS))
            for ((wordIndex, word) in words.withIndex()) {
                for (token in tokenize(word.text)) {
                    result += TimedToken(token.value, wordIndex, word.startMs, word.endMs)
                    if (result.size > MAX_ALIGNMENT_TOKENS) return result
                }
            }
            return result
        }

        private fun terminalPunctuationValues(tokens: List<String>): List<String> {
            val lastLexicalIndex = tokens.indexOfLast { it.hasWordCharacter() }
            if (lastLexicalIndex < 0) return emptyList()
            return tokens.drop(lastLexicalIndex + 1).takeWhile { !it.hasWordCharacter() }
        }

        private fun wordsMatch(source: TimedToken, revised: TimedToken): Boolean {
            val sourceHasWordCharacter = source.value.hasWordCharacter()
            val revisedHasWordCharacter = revised.value.hasWordCharacter()
            if (sourceHasWordCharacter != revisedHasWordCharacter) return false

            val sourceText = source.value.normalized()
            val revisedText = revised.value.normalized()
            val closeTime = source.startMs >= 0 && revised.startMs >= 0 &&
                kotlin.math.abs(source.startMs - revised.startMs) <= TIME_TOLERANCE_MS &&
                kotlin.math.abs(source.endMs - revised.endMs) <= TIME_TOLERANCE_MS
            val overlaps = source.startMs < revised.endMs && revised.startMs < source.endMs

            if (!sourceHasWordCharacter) {
                val samePunctuation = source.value.trim() == revised.value.trim()
                return samePunctuation && (closeTime || overlaps)
            }

            val sameText = sourceText.isNotEmpty() && sourceText == revisedText
            return (sameText && (closeTime || overlaps)) || (closeTime && overlaps)
        }

        private fun String.normalized(): String =
            filter { Character.isLetterOrDigit(it.code) || Character.getType(it.code) == Character.NON_SPACING_MARK.toInt() }
                .lowercase(Locale.ROOT)

        private fun String.hasWordCharacter(): Boolean = any(Character::isLetterOrDigit)

        private data class TimedToken(
            val value: String,
            val wordIndex: Int,
            val startMs: Long,
            val endMs: Long,
        )

        private fun terminalPunctuation(tokens: List<Token>): List<Token> {
            val lastLexicalIndex = tokens.indexOfLast { it.value.hasWordCharacter() }
            if (lastLexicalIndex < 0) return emptyList()
            return tokens.drop(lastLexicalIndex + 1).takeWhile { !it.value.hasWordCharacter() }
        }

        internal data class Token(val value: String, val start: Int, val end: Int)
        private data class IndexedToken(val value: String, val wordIndex: Int, val sequenceIndex: Int)

        private const val TIME_TOLERANCE_MS = 500L
    }
}

enum class MeetingEditAlignmentStatus {
    ALIGNED,
    UNRESOLVED,
}

data class MeetingEditAlignmentDiagnostic(
    val utteranceId: Long,
    val turnId: String,
    val status: MeetingEditAlignmentStatus,
)
