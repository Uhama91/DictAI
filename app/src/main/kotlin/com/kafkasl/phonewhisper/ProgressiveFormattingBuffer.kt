package com.kafkasl.phonewhisper

/** A raw-offset request produced by the progressive formatter. */
internal data class ProgressiveFormatRequest(
    val id: Long,
    val humanEpoch: Long,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val source: String,
    val contextBefore: String,
    val mode: LocalLayoutKind,
    val phase: GemmaFineTunedPrompt.Phase,
)

/** A source range that has passed the caller's existing output validation. */
internal data class ProgressiveAcceptedSegment(
    val id: Long,
    val humanEpoch: Long,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val source: String,
    val output: String,
    val contextBefore: String,
    val mode: LocalLayoutKind,
    val phase: GemmaFineTunedPrompt.Phase,
) {
    val formattedText: String get() = output
}

internal data class ProgressiveBufferState(
    val rawText: String,
    val stableWordCount: Int,
    val totalDictationWordCount: Int,
    val humanEpoch: Long,
    val humanPrefix: String,
    val renderedText: String,
    val acceptedSegments: List<ProgressiveAcceptedSegment>,
    val inFlight: ProgressiveFormatRequest?,
)

/**
 * Pure state for progressive formatting of the unowned ASR continuation.
 *
 * The class deliberately has no model or editor dependency. A caller must pass
 * an output that already passed LocalFormatRequest.acceptOutput before calling
 * [acceptValidated].
 */
internal class ProgressiveFormattingBuffer(
    private val minimumStableWords: Int = 60,
    private val reservedTailWords: Int = 12,
    private val minimumSegmentWords: Int = 20,
    private val maximumSegmentWords: Int = 80,
    private val maxContextWords: Int = 120,
    private val normalizer: (String) -> String = { it },
) {
    private data class Settlement(
        val request: ProgressiveFormatRequest,
        val output: String?,
    )

    private var rawText = ""
    private var stableWordCount = 0
    private var totalDictationWordCount = 0
    private var humanEpoch = 0L
    private var humanPrefix = ""
    private var nextId = 1L
    private var inFlight: ProgressiveFormatRequest? = null
    private val settlements = mutableListOf<Settlement>()

    @Synchronized
    fun update(
        nextRawText: String,
        stableWordCount: Int,
        totalDictationWordCount: Int = wordCount(nextRawText),
    ): ProgressiveBufferState {
        val changedAt = firstChangedOffset(rawText, nextRawText)
        if (changedAt != null) {
            val firstTouched = settlements.indexOfFirst { changedAt < it.request.sourceEndExclusive }
            if (firstTouched >= 0) settlements.subList(firstTouched, settlements.size).clear()
            if (inFlight?.let { changedAt < it.sourceEndExclusive } == true) inFlight = null
        }
        rawText = nextRawText
        this.stableWordCount = stableWordCount.coerceIn(0, wordCount(rawText))
        this.totalDictationWordCount = totalDictationWordCount.coerceAtLeast(0)
        if (inFlight?.phase == GemmaFineTunedPrompt.Phase.PARTIAL && !partialRequestIsStable(inFlight!!))
            inFlight = null
        return snapshot()
    }

    /** Alias that makes the ASR update boundary explicit for integrations. */
    @Synchronized
    fun onAsrUpdate(
        nextRawText: String,
        stableWordCount: Int,
        totalDictationWordCount: Int = wordCount(nextRawText),
    ): ProgressiveBufferState = update(nextRawText, stableWordCount, totalDictationWordCount)

    @Synchronized
    fun request(
        mode: LocalLayoutKind,
        phase: GemmaFineTunedPrompt.Phase,
    ): ProgressiveFormatRequest? {
        inFlight?.let { existing ->
            if (existing.mode == mode && existing.phase == phase && requestStillCurrent(existing))
                return existing
            inFlight = null
        }

        val start = settledEnd()
        if (start >= rawText.length) return null
        val end = when (phase) {
            GemmaFineTunedPrompt.Phase.FINAL -> rawText.length
            GemmaFineTunedPrompt.Phase.PARTIAL -> partialEnd(start) ?: return null
        }
        if (end <= start) return null
        return ProgressiveFormatRequest(
            id = nextId++,
            humanEpoch = humanEpoch,
            sourceStart = start,
            sourceEndExclusive = end,
            source = rawText.substring(start, end),
            contextBefore = contextBefore(start),
            mode = mode,
            phase = phase,
        ).also { inFlight = it }
    }

    /**
     * Applies an output already accepted by LocalFormatRequest.acceptOutput.
     * A null or blank output settles the source as raw fallback and returns false.
     */
    @Synchronized
    fun acceptValidated(request: ProgressiveFormatRequest, validatedOutput: String?): Boolean {
        if (inFlight != request || !requestStillCurrent(request)) return false
        val accepted = !validatedOutput.isNullOrBlank()
        settlements += Settlement(request, validatedOutput?.takeIf { accepted })
        inFlight = null
        return accepted
    }

    @Synchronized
    fun accept(request: ProgressiveFormatRequest, validatedOutput: String?): Boolean =
        acceptValidated(request, validatedOutput)

    /** A human edit owns the visible prefix and invalidates all automatic work. */
    @Synchronized
    fun resetForHumanEdit(visiblePrefix: String): ProgressiveBufferState {
        humanEpoch++
        humanPrefix = visiblePrefix
        rawText = ""
        stableWordCount = 0
        totalDictationWordCount = 0
        inFlight = null
        settlements.clear()
        return snapshot()
    }

    @Synchronized
    fun state(): ProgressiveBufferState = snapshot()

    private fun requestStillCurrent(request: ProgressiveFormatRequest): Boolean =
        request.humanEpoch == humanEpoch &&
            request.sourceStart >= 0 &&
            request.sourceEndExclusive <= rawText.length &&
            request.sourceStart <= request.sourceEndExclusive &&
            rawText.substring(request.sourceStart, request.sourceEndExclusive) == request.source &&
            (request.phase != GemmaFineTunedPrompt.Phase.PARTIAL || partialRequestIsStable(request))

    private fun partialRequestIsStable(request: ProgressiveFormatRequest): Boolean =
        totalDictationWordCount >= minimumStableWords &&
            request.sourceEndExclusive <= stableEndExclusive()

    private fun stableEndExclusive(): Int {
        val stableWords = words(rawText)
        if (stableWordCount <= 0 || stableWords.isEmpty()) return 0
        val index = stableWordCount.coerceAtMost(stableWords.size) - 1
        return stableWords[index].range.last + 1
    }

    private fun settledEnd(): Int = settlements.lastOrNull()?.request?.sourceEndExclusive ?: 0

    private fun partialEnd(start: Int): Int? {
        if (totalDictationWordCount < minimumStableWords) return null
        val words = words(rawText)
        if (words.isEmpty()) return null
        val stableEndIndex = stableWordCount.coerceAtMost(words.size)
        val lastEligibleIndex = stableEndIndex - reservedTailWords - 1
        if (lastEligibleIndex < 0) return null
        val firstIndex = words.indexOfFirst { it.range.first >= start }
        if (firstIndex < 0) return null
        val lastIndex = minOf(lastEligibleIndex, firstIndex + maximumSegmentWords - 1)
        val firstBoundaryIndex = firstIndex + minimumSegmentWords - 1
        if (firstBoundaryIndex > lastIndex) return null
        for (index in firstBoundaryIndex..lastIndex) {
            val word = words[index]
            if (isSafeBoundary(word.value, word.range.last + 1, words.getOrNull(index + 1)?.range?.first))
                return word.range.last + 1
        }
        return null
    }

    private fun isSafeBoundary(token: String, tokenEnd: Int, nextTokenStart: Int?): Boolean {
        val trimmed = token.trimEnd('"', '\'', '»', '”', ')', ']', '}')
        val paragraphBoundary = nextTokenStart != null &&
            rawText.substring(tokenEnd, nextTokenStart).contains("\n\n")
        if (trimmed.isEmpty() || (trimmed.last() !in ".!?…" && !paragraphBoundary)) return false
        if (trimmed.lowercase() in ABBREVIATIONS) return false
        if (Regex("(?:^|\\D)\\d+\\.\\d+\\.$").matches(trimmed)) return false
        return true
    }

    private fun contextBefore(cursor: Int): String {
        val visible = renderBody(cursor)
        val combined = joinPrefix(humanPrefix, visible)
        val matches = WORD.findAll(combined).toList()
        if (matches.size <= maxContextWords) return combined
        return combined.substring(matches[matches.size - maxContextWords].range.first)
    }

    private fun renderBody(until: Int = rawText.length): String {
        val limit = until.coerceIn(0, rawText.length)
        val builder = StringBuilder()
        var position = 0
        for (settlement in settlements) {
            val request = settlement.request
            if (request.sourceStart >= limit) break
            if (request.sourceStart > position) appendRaw(builder, position, minOf(request.sourceStart, limit))
            val end = minOf(request.sourceEndExclusive, limit)
            if (end > request.sourceStart) {
                if (end == request.sourceEndExclusive && settlement.output != null) {
                    builder.append(request.source.takeWhile(Char::isWhitespace))
                    builder.append(settlement.output)
                    builder.append(request.source.takeLastWhile(Char::isWhitespace))
                } else appendRaw(builder, request.sourceStart, end)
            }
            position = request.sourceEndExclusive
            if (position >= limit) break
        }
        if (position < limit) appendRaw(builder, position, limit)
        return builder.toString()
    }

    private fun appendRaw(builder: StringBuilder, start: Int, end: Int) {
        if (end > start) builder.append(normalizer(rawText.substring(start, end)))
    }

    private fun snapshot(): ProgressiveBufferState {
        val accepted = settlements.filter { it.output != null }.map { settled ->
            val request = settled.request
            ProgressiveAcceptedSegment(
                id = request.id,
                humanEpoch = request.humanEpoch,
                sourceStart = request.sourceStart,
                sourceEndExclusive = request.sourceEndExclusive,
                source = request.source,
                output = settled.output!!,
                contextBefore = request.contextBefore,
                mode = request.mode,
                phase = request.phase,
            )
        }
        return ProgressiveBufferState(
            rawText = rawText,
            stableWordCount = stableWordCount,
            totalDictationWordCount = totalDictationWordCount,
            humanEpoch = humanEpoch,
            humanPrefix = humanPrefix,
            renderedText = renderBody(),
            acceptedSegments = accepted,
            inFlight = inFlight,
        )
    }

    private fun joinPrefix(prefix: String, body: String): String = when {
        prefix.isEmpty() -> body
        body.isEmpty() -> prefix
        prefix.last().isWhitespace() || body.first().isWhitespace() -> prefix + body
        else -> "$prefix $body"
    }

    private fun firstChangedOffset(old: String, next: String): Int? {
        val common = old.commonPrefixWith(next).length
        return if (common == old.length && common == next.length) null else common
    }

    private fun wordCount(value: String): Int = WORD.findAll(value).count()

    private fun words(value: String): List<MatchResult> = WORD.findAll(value).toList()

    private companion object {
        val WORD = Regex("\\S+")
        val ABBREVIATIONS = setOf("m.", "mme.", "mlle.", "dr.", "pr.", "etc.", "p.", "n°.")
    }
}
