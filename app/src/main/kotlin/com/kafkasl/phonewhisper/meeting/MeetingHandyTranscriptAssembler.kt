package com.kafkasl.phonewhisper.meeting

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Turns Handy's append-only UTF-8/token snapshots into bounded, revisable meeting chunks. */
internal class MeetingHandyTranscriptAssembler(
    private val maxWordsPerChunk: Int = MAX_WORDS_PER_CHUNK,
    private val maxRetainedAudioMs: Long = MAX_RETAINED_AUDIO_MS,
    private val maxRetainedChunks: Int = MAX_RETAINED_CHUNKS,
) {
    private data class Lexeme(
        val start: Int,
        val endExclusive: Int,
        val surfaceStart: Int,
        val surfaceEndExclusive: Int,
    )

    private data class TimedLexeme(
        val word: MeetingWord?,
        val complete: Boolean,
    )

    private data class ChunkState(
        val utteranceId: Long,
        var revision: Long,
        var rawWords: List<MeetingWord?>,
        var transcript: String,
        var words: List<MeetingWord?>,
        var isFinal: Boolean,
        var stableSpeakerThroughMs: Long,
        var audioProcessedMs: Long,
        var retainedAudioEndMs: Long,
    )

    private data class NormalizedText(
        val text: String,
        val sourceIndices: IntArray,
        val tokenIndices: List<IntArray>,
    )

    private data class DecodedTokenText(
        val text: String,
        val tokenForChar: Array<IntArray?>,
    )

    private data class DecodedFullText(
        val text: String,
        val completeByteCount: Int,
    )

    private data class ProcessedSnapshot(
        val fullTextUtf8: ByteArray,
        val firstTokenIndex: Int,
        val totalTokenCount: Int,
        val committedTokenCount: Int,
        val tokenBytes: ByteArray,
        val tokenByteEnds: IntArray,
        val tokenStartsMs: LongArray,
        val tokenEndsMs: LongArray,
        val isFinal: Boolean,
        val retryBoundary: Int?,
    )

    private val chunks = LinkedHashMap<Long, ChunkState>()
    private var lastFullTextByteCount = 0
    private var lastFullTextPrefixDigest = sha256(ByteArray(0))
    private var lastTotalTokenCount = 0
    private var lastCommittedTokenCount = 0
    private var alignmentBroken = false
    private var retryFirstTokenIndex: Int? = null
    private var evictedBeforeGroupIndex = 0L
    private var nextUnpublishedGroupIndex = 0L
    private var latestDiarization: DiarizationFrameWindow? = null
    private var latestAudioProcessedMs = 0L
    private var lastProcessedSnapshot: ProcessedSnapshot? = null

    init {
        require(maxWordsPerChunk in 1..MAX_REDUCER_WORDS) { "maxWordsPerChunk exceeds the reducer bound" }
        require(maxRetainedAudioMs > 0L) { "maxRetainedAudioMs must be positive" }
        require(maxRetainedChunks > 0) { "maxRetainedChunks must be positive" }
    }

    /** The next snapshot starts slightly before the last known token to cover a split word. */
    val nextSnapshotFirstTokenIndex: Int
        get() = retryFirstTokenIndex ?: (lastTotalTokenCount - SNAPSHOT_OVERLAP_TOKENS).coerceAtLeast(0)

    /** Number of utterances still retained for voice revisions; emitted document text is external. */
    val retainedChunkCount: Int
        get() = chunks.size

    /** Sticky signal that the bounded voice-revision window had to drop an unexamined chunk. */
    @Volatile
    var attributionRetentionExhausted: Boolean = false
        private set

    @Synchronized
    fun update(
        window: HandyTokenWindow,
        audioProcessedMs: Long,
        isFinal: Boolean,
    ): List<MeetingNativeUpdate> {
        val previousAudioProcessedMs = latestAudioProcessedMs
        latestAudioProcessedMs = maxOf(latestAudioProcessedMs, audioProcessedMs.coerceAtLeast(0L))
        if (canSkipUnchangedSnapshot(window, isFinal, previousAudioProcessedMs)) {
            purgeExpiredChunksWithoutRescanningTranscript()
            return emptyList()
        }
        val decodedFullText = decodeFullText(window.fullTextUtf8)
        val text = decodedFullText.text
        if (text.isEmpty() && chunks.isEmpty()) {
            checkAppendOnlyPrefix(window, decodedFullText.completeByteCount)
            recordCheckpoint(window, decodedFullText.completeByteCount)
            rememberSnapshot(window, isFinal)
            return emptyList()
        }
        checkAppendOnlyPrefix(window, decodedFullText.completeByteCount)
        val lexemes = lexicalSpans(text)
        val mapping = if (alignmentBroken) null else tokenMapping(window, text)
        val leadingContinuation = hasLeadingContinuationByte(window.tokenBytes)
        val leadingTokenBoundaryCut = leadingContinuation && window.firstTokenIndex > 0
        val trailingTokenCharacterIncomplete = hasIncompleteUtf8Suffix(window.tokenBytes)
        val temporaryTokenBoundary = mapping == null && (leadingTokenBoundaryCut ||
            (trailingTokenCharacterIncomplete && !leadingContinuation))
        if (mapping == null && !temporaryTokenBoundary) alignmentBroken = true
        if (leadingTokenBoundaryCut) {
            // Back up one token per request so even a continuation-token split eventually
            // reaches its lead byte, regardless of tokenization phase.
            retryFirstTokenIndex = (window.firstTokenIndex - 1).coerceAtLeast(0)
        } else if (mapping != null) {
            retryFirstTokenIndex = null
        }

        val groupCount = maxOf(1, ceil(lexemes.size.toDouble() / maxWordsPerChunk).toInt())
        val firstRetainedGroup = (groupCount - maxRetainedChunks).coerceAtLeast(0)
        evictedBeforeGroupIndex = maxOf(evictedBeforeGroupIndex, firstRetainedGroup.toLong())
        val safeAudioEnd = latestAudioProcessedMs
        val retentionCutoff = revisionRetentionCutoffMs()
        val updated = mutableListOf<MeetingNativeUpdate>()

        val groupsToProcess = sortedSetOf<Int>()
        chunks.keys.forEach { id ->
            val groupIndex = (id - 1L).coerceAtLeast(0L)
            if (groupIndex < groupCount && groupIndex >= evictedBeforeGroupIndex) groupsToProcess += groupIndex.toInt()
        }
        val newGroupStart = nextUnpublishedGroupIndex.coerceAtMost(groupCount.toLong()).toInt()
        if (newGroupStart < groupCount) groupsToProcess.addAll(newGroupStart until groupCount)

        for (groupIndex in groupsToProcess) {
            val startWord = groupIndex * maxWordsPerChunk
            val endWord = minOf(startWord + maxWordsPerChunk, lexemes.size)
            val groupLexemes = lexemes.subList(startWord.coerceAtMost(lexemes.size), endWord)
            val nextStart = lexemes.getOrNull(endWord)?.surfaceStart ?: text.length
            val startChar = groupLexemes.firstOrNull()?.surfaceStart ?: 0
            val transcript = text.substring(startChar.coerceIn(0, text.length), nextStart.coerceIn(startChar, text.length))
            val utteranceId = groupIndex.toLong() + 1L
            val old = chunks[utteranceId]
            val rawWords = groupLexemes.mapIndexed { localIndex, lexeme ->
                val aligned = mapping?.let { alignedWord(window, text, lexeme, it, isFinal) }
                val prior = old?.rawWords?.getOrNull(localIndex)
                when {
                    aligned?.complete == true && aligned.word != null -> aligned.word
                    prior != null && prior.text == text.substring(lexeme.surfaceStart, lexeme.surfaceEndExclusive).trim() ->
                        prior.copy(channel = UNKNOWN_CHANNEL)
                    mapping == null && temporaryTokenBoundary -> prior
                    else -> null
                }
            }
            val currentWords = rawWords.mapIndexed { index, raw -> raw?.let { word ->
                val classified = channelFor(word, latestDiarization)
                val prior = old?.words?.getOrNull(index)
                val oldStableThrough = old?.stableSpeakerThroughMs ?: 0L
                val retainedStable = prior?.takeIf {
                    it.channel != UNKNOWN_CHANNEL && it.endMs <= oldStableThrough
                }?.channel
                word.copy(channel = if (classified != UNKNOWN_CHANNEL) classified else retainedStable ?: UNKNOWN_CHANNEL)
            } }
            val groupAudioEnd = rawWords.filterNotNull().maxOfOrNull { it.endMs }
                ?: old?.retainedAudioEndMs
                ?: if (groupIndex == groupCount - 1) safeAudioEnd else 0L
            val outsideAudioWindow = groupAudioEnd < retentionCutoff &&
                groupIndex != groupCount - 1 &&
                !hasUnexaminedDiarization(rawWords)
            val outsideChunkWindow = groupIndex < firstRetainedGroup
            if (old != null && (outsideAudioWindow || outsideChunkWindow)) {
                if (outsideChunkWindow && hasUnexaminedDiarization(rawWords)) {
                    attributionRetentionExhausted = true
                }
                chunks.remove(utteranceId)
                evictedBeforeGroupIndex = maxOf(evictedBeforeGroupIndex, groupIndex.toLong() + 1L)
                continue
            }

            // Every lexical word remains in the transcript, including an uncommitted trailing word.
            // Only complete, token-aligned words receive timing or a speaker channel.
            val boundaryWordCommitted = rawWords.lastOrNull() != null
            val chunkIsFinal = isFinal || old?.isFinal == true ||
                (groupIndex < groupCount - 1 && boundaryWordCommitted)
            val stableThrough = stableThroughMs(latestDiarization)
            val state = old ?: ChunkState(
                utteranceId = utteranceId,
                revision = 0L,
                rawWords = emptyList(),
                transcript = "",
                words = emptyList(),
                isFinal = false,
                stableSpeakerThroughMs = 0L,
                audioProcessedMs = 0L,
                retainedAudioEndMs = groupAudioEnd,
            )
            val changed = old == null || state.transcript != transcript || state.rawWords != rawWords ||
                state.words != currentWords || state.isFinal != chunkIsFinal
            state.stableSpeakerThroughMs = stableThrough
            state.audioProcessedMs = latestAudioProcessedMs
            if (!changed) continue

            state.revision += 1L
            state.rawWords = rawWords
            state.transcript = transcript
            state.words = currentWords
            state.isFinal = chunkIsFinal
            state.stableSpeakerThroughMs = stableThrough
            state.audioProcessedMs = latestAudioProcessedMs
            state.retainedAudioEndMs = groupAudioEnd
            updated += state.toNativeUpdate()
            if (outsideAudioWindow || outsideChunkWindow) {
                if (outsideChunkWindow && hasUnexaminedDiarization(state.rawWords)) {
                    attributionRetentionExhausted = true
                }
                chunks.remove(utteranceId)
                evictedBeforeGroupIndex = maxOf(evictedBeforeGroupIndex, groupIndex.toLong() + 1L)
            } else {
                chunks[utteranceId] = state
            }
        }

        nextUnpublishedGroupIndex = maxOf(nextUnpublishedGroupIndex, groupCount.toLong())

        val expiredIds = chunks.entries.filter { (id, state) ->
            val groupIndex = (id - 1L).coerceAtLeast(0L)
            val outsideChunkLimit = groupIndex < firstRetainedGroup
            val outsideAudioWindow = state.retainedAudioEndMs < retentionCutoff &&
                groupIndex != groupCount.toLong() - 1L &&
                !hasUnexaminedDiarization(state.rawWords)
            outsideChunkLimit || outsideAudioWindow
        }.map { it.key }
        expiredIds.forEach { id ->
            val state = chunks[id] ?: return@forEach
            val groupIndex = (id - 1L).coerceAtLeast(0L)
            if (groupIndex < firstRetainedGroup && hasUnexaminedDiarization(state.rawWords)) {
                attributionRetentionExhausted = true
            }
            chunks.remove(id)
            evictedBeforeGroupIndex = maxOf(evictedBeforeGroupIndex, id)
        }
        while (chunks.size > maxRetainedChunks) {
            val oldestId = chunks.keys.first()
            val oldestState = chunks[oldestId]
            if (oldestState != null && hasUnexaminedDiarization(oldestState.rawWords)) {
                attributionRetentionExhausted = true
            }
            chunks.remove(oldestId)
            evictedBeforeGroupIndex = maxOf(evictedBeforeGroupIndex, oldestId)
        }
        recordCheckpoint(window, decodedFullText.completeByteCount)
        rememberSnapshot(window, isFinal)
        return updated
    }

    /** Keeps a revision margin behind both Handy and the diarization stable frontier. */
    private fun revisionRetentionCutoffMs(): Long {
        val progressedThroughMs = minOf(latestAudioProcessedMs, stableThroughMs(latestDiarization))
        return (progressedThroughMs - maxRetainedAudioMs).coerceAtLeast(0L)
    }

    private fun hasUnexaminedDiarization(words: List<MeetingWord?>): Boolean {
        val stableThrough = stableThroughMs(latestDiarization)
        return words.any { word -> word == null || word.endMs > stableThrough }
    }

    /** Skips full-history decoding only for a byte-for-byte unchanged, already time-eligible snapshot. */
    private fun canSkipUnchangedSnapshot(
        window: HandyTokenWindow,
        isFinal: Boolean,
        previousAudioProcessedMs: Long,
    ): Boolean {
        val previous = lastProcessedSnapshot ?: return false
        if (window.tokenEndsMs.any { it > previousAudioProcessedMs }) return false
        return previous.firstTokenIndex == window.firstTokenIndex &&
            previous.totalTokenCount == window.totalTokenCount &&
            previous.committedTokenCount == window.committedTokenCount &&
            previous.isFinal == isFinal &&
            previous.retryBoundary == retryFirstTokenIndex &&
            previous.fullTextUtf8.contentEquals(window.fullTextUtf8) &&
            previous.tokenBytes.contentEquals(window.tokenBytes) &&
            previous.tokenByteEnds.contentEquals(window.tokenByteEnds) &&
            previous.tokenStartsMs.contentEquals(window.tokenStartsMs) &&
            previous.tokenEndsMs.contentEquals(window.tokenEndsMs)
    }

    /** Preserve revision retention even when an unchanged snapshot takes the fast path. */
    private fun purgeExpiredChunksWithoutRescanningTranscript() {
        val retentionCutoff = revisionRetentionCutoffMs()
        val activeGroupIndex = nextUnpublishedGroupIndex - 1L
        val expiredIds = chunks.entries.filter { (id, state) ->
            val groupIndex = (id - 1L).coerceAtLeast(0L)
            groupIndex != activeGroupIndex &&
                state.retainedAudioEndMs < retentionCutoff &&
                !hasUnexaminedDiarization(state.rawWords)
        }.map { it.key }
        expiredIds.forEach { id ->
            chunks.remove(id)
            evictedBeforeGroupIndex = maxOf(evictedBeforeGroupIndex, id)
        }
    }

    private fun rememberSnapshot(window: HandyTokenWindow, isFinal: Boolean) {
        lastProcessedSnapshot = ProcessedSnapshot(
            fullTextUtf8 = window.fullTextUtf8.copyOf(),
            firstTokenIndex = window.firstTokenIndex,
            totalTokenCount = window.totalTokenCount,
            committedTokenCount = window.committedTokenCount,
            tokenBytes = window.tokenBytes.copyOf(),
            tokenByteEnds = window.tokenByteEnds.copyOf(),
            tokenStartsMs = window.tokenStartsMs.copyOf(),
            tokenEndsMs = window.tokenEndsMs.copyOf(),
            isFinal = isFinal,
            retryBoundary = retryFirstTokenIndex,
        )
    }

    @Synchronized
    fun reviseDiarization(
        window: DiarizationFrameWindow?,
        audioProcessedMs: Long,
    ): List<MeetingNativeUpdate> {
        if (window != null) latestDiarization = window
        latestAudioProcessedMs = maxOf(latestAudioProcessedMs, audioProcessedMs.coerceAtLeast(0L))
        val diarization = latestDiarization
        val stableThrough = stableThroughMs(diarization)
        val updates = mutableListOf<MeetingNativeUpdate>()
        chunks.values.forEach { state ->
            val revisedWords = state.rawWords.mapIndexed { index, word -> word?.let { raw ->
                val candidate = raw.copy(channel = channelFor(raw, diarization))
                val previous = state.words.getOrNull(index)
                if (candidate.channel == UNKNOWN_CHANNEL && previous != null &&
                    previous.channel != UNKNOWN_CHANNEL && previous.endMs <= state.stableSpeakerThroughMs
                ) {
                    candidate.copy(channel = previous.channel)
                } else {
                    candidate
                }
            } }
            state.stableSpeakerThroughMs = stableThrough
            state.audioProcessedMs = latestAudioProcessedMs
            if (revisedWords == state.words) return@forEach
            state.revision += 1L
            state.words = revisedWords
            updates += state.toNativeUpdate()
        }
        purgeExpiredChunksWithoutRescanningTranscript()
        return updates
    }

    private fun checkAppendOnlyPrefix(window: HandyTokenWindow, completeByteCount: Int) {
        if (completeByteCount < lastFullTextByteCount ||
            !sha256(window.fullTextUtf8.copyOfRange(0, lastFullTextByteCount)).contentEquals(lastFullTextPrefixDigest) ||
            window.totalTokenCount < lastTotalTokenCount ||
            window.committedTokenCount < lastCommittedTokenCount ||
            (lastTotalTokenCount > 0 && window.firstTokenIndex > lastTotalTokenCount)
        ) {
            alignmentBroken = true
        }
    }

    private fun recordCheckpoint(window: HandyTokenWindow, completeByteCount: Int) {
        lastFullTextByteCount = completeByteCount
        lastFullTextPrefixDigest = sha256(window.fullTextUtf8.copyOfRange(0, completeByteCount))
        lastTotalTokenCount = maxOf(lastTotalTokenCount, window.totalTokenCount)
        lastCommittedTokenCount = maxOf(lastCommittedTokenCount, window.committedTokenCount)
    }

    private fun tokenMapping(window: HandyTokenWindow, fullText: String): Array<IntArray?>? {
        if (window.firstTokenIndex + window.tokenStartsMs.size != window.totalTokenCount) return null
        if (window.tokenByteEnds.lastOrNull() != window.tokenBytes.size) {
            if (window.tokenByteEnds.isNotEmpty() || window.tokenBytes.isNotEmpty()) return null
        }
        val tokenText = decodeTokenBytes(window) ?: return null
        val normalizedFullText = normalizeAsciiSpaces(fullText)
        val normalizedTokenText = normalizeAsciiSpaces(tokenText.text, tokenText.tokenForChar)
        val sourceStart = when {
            window.firstTokenIndex == 0 && normalizedTokenText.text == normalizedFullText.text -> 0
            normalizedFullText.text.endsWith(normalizedTokenText.text) ->
                normalizedFullText.text.length - normalizedTokenText.text.length
            else -> return null
        }
        val tokensByFullTextChar = arrayOfNulls<IntArray>(fullText.length)
        normalizedTokenText.text.indices.forEach { normalizedOffset ->
            val fullNormalizedOffset = sourceStart + normalizedOffset
            val fullTextOffset = normalizedFullText.sourceIndices.getOrNull(fullNormalizedOffset) ?: return null
            val tokenIndices = normalizedTokenText.tokenIndices[normalizedOffset]
            if (tokenIndices.isNotEmpty()) {
                tokensByFullTextChar[fullTextOffset] = tokenIndices
            }
        }
        if (window.firstTokenIndex == 0 && sourceStart != 0) return null
        return tokensByFullTextChar
    }

    private fun decodeTokenBytes(window: HandyTokenWindow): DecodedTokenText? {
        val completeByteCount = incompleteUtf8SuffixStart(window.tokenBytes) ?: window.tokenBytes.size
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(window.tokenBytes, 0, completeByteCount))
                .toString()
        } catch (_: Throwable) {
            return null
        }
        if (window.tokenByteEnds.isEmpty()) {
            return if (window.tokenBytes.isEmpty()) DecodedTokenText(decoded, emptyArray()) else null
        }

        val tokenForChar = arrayOfNulls<IntArray>(decoded.length)
        var byteOffset = 0
        var charOffset = 0
        while (byteOffset < completeByteCount) {
            val firstByte = window.tokenBytes[byteOffset].toInt() and 0xff
            val byteLength = when {
                firstByte and 0x80 == 0 -> 1
                firstByte and 0xE0 == 0xC0 -> 2
                firstByte and 0xF0 == 0xE0 -> 3
                firstByte and 0xF8 == 0xF0 -> 4
                else -> return null
            }
            val byteEnd = byteOffset + byteLength
            if (byteEnd > completeByteCount) return null
            val tokenIndices = tokenIndicesForBytes(window, byteOffset, byteEnd) ?: return null
            val codePoint = decodeCodePoint(window.tokenBytes, byteOffset, byteLength)
            val chars = Character.toChars(codePoint)
            if (decoded.regionMatches(charOffset, String(chars), 0, chars.size).not()) return null
            chars.indices.forEach { charPart -> tokenForChar[charOffset + charPart] = tokenIndices }
            byteOffset = byteEnd
            charOffset += chars.size
        }
        if (charOffset != decoded.length) return null
        return DecodedTokenText(decoded, tokenForChar)
    }

    private fun tokenIndicesForBytes(window: HandyTokenWindow, byteStart: Int, byteEndExclusive: Int): IntArray? {
        val found = linkedSetOf<Int>()
        for (offset in byteStart until byteEndExclusive) {
            val localIndex = window.tokenByteEnds.indexOfFirst { end -> offset < end }
            if (localIndex < 0) return null
            found += window.firstTokenIndex + localIndex
        }
        return found.toIntArray()
    }

    private fun decodeCodePoint(bytes: ByteArray, offset: Int, byteLength: Int): Int {
        val first = bytes[offset].toInt() and 0xff
        return when (byteLength) {
            1 -> first
            2 -> ((first and 0x1f) shl 6) or (bytes[offset + 1].toInt() and 0x3f)
            3 -> ((first and 0x0f) shl 12) or
                ((bytes[offset + 1].toInt() and 0x3f) shl 6) or
                (bytes[offset + 2].toInt() and 0x3f)
            else -> ((first and 0x07) shl 18) or
                ((bytes[offset + 1].toInt() and 0x3f) shl 12) or
                ((bytes[offset + 2].toInt() and 0x3f) shl 6) or
                (bytes[offset + 3].toInt() and 0x3f)
        }
    }

    private fun normalizeAsciiSpaces(text: String, tokenForChar: Array<IntArray?>? = null): NormalizedText {
        val normalized = StringBuilder(text.length)
        val sourceIndices = ArrayList<Int>(text.length)
        val tokenIndices = ArrayList<IntArray>(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == ' ') {
                val firstSpace = index
                while (index < text.length && text[index] == ' ') index++
                if (normalized.isNotEmpty() && normalized.last() != ' ') {
                    normalized.append(' ')
                    sourceIndices += firstSpace
                    tokenIndices += intArrayOf()
                }
            } else {
                normalized.append(text[index])
                sourceIndices += index
                tokenIndices += tokenForChar?.getOrNull(index) ?: intArrayOf()
                index++
            }
        }
        if (normalized.endsWith(" ")) {
            normalized.setLength(normalized.length - 1)
            sourceIndices.removeAt(sourceIndices.lastIndex)
            tokenIndices.removeAt(tokenIndices.lastIndex)
        }
        return NormalizedText(normalized.toString(), sourceIndices.toIntArray(), tokenIndices)
    }

    private fun alignedWord(
        window: HandyTokenWindow,
        fullText: String,
        lexeme: Lexeme,
        tokenByChar: Array<IntArray?>,
        isFinal: Boolean,
    ): TimedLexeme? {
        val complete = isFinal || lexeme.endExclusive < fullText.length
        val tokenIds = linkedSetOf<Int>()
        for (offset in lexeme.start until lexeme.endExclusive) {
            val charTokens = tokenByChar.getOrNull(offset) ?: intArrayOf()
            if (charTokens.isEmpty()) return TimedLexeme(null, complete)
            charTokens.forEach(tokenIds::add)
        }
        if (tokenIds.isEmpty() || tokenIds.any { it >= window.committedTokenCount }) return TimedLexeme(null, complete)
        val times = tokenIds.map { tokenIndex ->
            val localIndex = tokenIndex - window.firstTokenIndex
            if (localIndex !in window.tokenStartsMs.indices) return TimedLexeme(null, complete)
            window.tokenStartsMs[localIndex] to window.tokenEndsMs[localIndex]
        }
        val startMs = times.minOf { it.first }
        val endMs = times.maxOf { it.second }
        if (startMs < 0L || endMs <= startMs || endMs > latestAudioProcessedMs) return TimedLexeme(null, complete)
        return TimedLexeme(
            word = MeetingWord(
                text = fullText.substring(lexeme.surfaceStart, lexeme.surfaceEndExclusive),
                startMs = startMs,
                endMs = endMs,
                channel = UNKNOWN_CHANNEL,
            ),
            complete = complete,
        )
    }

    private fun lexicalSpans(text: String): List<Lexeme> {
        val matches = WORD_PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val nextStart = matches.getOrNull(index + 1)?.range?.first ?: text.length
            var surfaceEndExclusive = nextStart
            while (surfaceEndExclusive > match.range.last + 1 && text[surfaceEndExclusive - 1].isWhitespace()) {
                surfaceEndExclusive--
            }
            Lexeme(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                surfaceStart = if (index == 0) 0 else match.range.first,
                surfaceEndExclusive = surfaceEndExclusive,
            )
        }
    }

    private fun channelFor(word: MeetingWord, diarization: DiarizationFrameWindow?): Int {
        return MeetingSpeakerAttribution.channelFor(word.startMs, word.endMs, diarization)
    }

    private fun stableThroughMs(window: DiarizationFrameWindow?): Long {
        if (window == null) return 0L
        val value = window.stableFrameCount.toDouble() * window.secondsPerFrame * MILLIS_PER_SECOND
        return if (value.isFinite() && value >= 0.0) value.roundToLong() else 0L
    }

    private fun decodeFullText(bytes: ByteArray): DecodedFullText {
        try {
            return DecodedFullText(decodeUtf8(bytes, bytes.size), bytes.size)
        } catch (failure: java.nio.charset.CharacterCodingException) {
            val completeByteCount = incompleteUtf8SuffixStart(bytes) ?: throw failure
            return DecodedFullText(decodeUtf8(bytes, completeByteCount), completeByteCount)
        }
    }

    private fun decodeUtf8(bytes: ByteArray, length: Int): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, length))
        .toString()

    private fun hasLeadingContinuationByte(bytes: ByteArray): Boolean = bytes.firstOrNull()
        ?.toInt()
        ?.and(0xff)
        ?.let { it in UTF8_CONTINUATION_MIN..UTF8_CONTINUATION_MAX } == true

    private fun hasIncompleteUtf8Suffix(bytes: ByteArray): Boolean = incompleteUtf8SuffixStart(bytes) != null

    /** Returns an offset only for a valid but not-yet-complete final UTF-8 code point. */
    private fun incompleteUtf8SuffixStart(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        var leadIndex = bytes.lastIndex
        while (leadIndex >= 0 && isContinuationByte(bytes[leadIndex])) leadIndex--
        if (leadIndex < 0) return null

        val lead = bytes[leadIndex].toInt() and 0xff
        val expectedLength = when (lead) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> return null
        }
        val availableLength = bytes.size - leadIndex
        if (availableLength >= expectedLength) return null
        if ((leadIndex + 1 until bytes.size).any { !isContinuationByte(bytes[it]) }) return null

        if (availableLength >= 2) {
            val second = bytes[leadIndex + 1].toInt() and 0xff
            val validSecond = when (lead) {
                0xE0 -> second >= 0xA0
                0xED -> second <= 0x9F
                0xF0 -> second >= 0x90
                0xF4 -> second <= 0x8F
                else -> true
            }
            if (!validSecond) return null
        }
        return leadIndex
    }

    private fun isContinuationByte(byte: Byte): Boolean {
        val value = byte.toInt() and 0xff
        return value in UTF8_CONTINUATION_MIN..UTF8_CONTINUATION_MAX
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ChunkState.toNativeUpdate() = MeetingNativeUpdate(
        utteranceId = utteranceId,
        revision = revision,
        words = words.filterNotNull(),
        transcript = transcript,
        isFinal = isFinal,
        stableSpeakerThroughMs = stableSpeakerThroughMs,
        audioProcessedMs = audioProcessedMs,
    )

    private companion object {
        val WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-‐‑][\\p{L}\\p{M}\\p{N}]+)*")
        const val MILLIS_PER_SECOND = 1_000.0
        const val UNKNOWN_CHANNEL = MeetingSpeakerAttribution.UNKNOWN_CHANNEL
        const val MAX_WORDS_PER_CHUNK = 64
        const val MAX_REDUCER_WORDS = 512
        const val MAX_RETAINED_AUDIO_MS = 120_000L
        const val MAX_RETAINED_CHUNKS = 256
        const val SNAPSHOT_OVERLAP_TOKENS = 128
        const val UTF8_CONTINUATION_MIN = 0x80
        const val UTF8_CONTINUATION_MAX = 0xBF
    }
}
