package com.kafkasl.phonewhisper.meeting

/** Reconciles revisioned, utterance-local hypotheses into stable editable meeting turns. */
class MeetingTranscriptReducer(
    private val sessionId: String,
    private val runId: String,
) {
    private val participants = MeetingParticipants(sessionId)
    private val turnsByUtterance = linkedMapOf<Long, MutableList<StoredTurn>>()
    private val lastRevisionByUtterance = mutableMapOf<Long, Long>()
    private val diagnostics = mutableListOf<MeetingEditAlignmentDiagnostic>()
    private var nextTurnSequence = 1L

    val alignmentDiagnostics: List<MeetingEditAlignmentDiagnostic>
        get() = diagnostics.toList()

    fun apply(hypothesis: MeetingHypothesis) {
        if (hypothesis.runId != runId) return
        val previousRevision = lastRevisionByUtterance[hypothesis.utteranceId] ?: Long.MIN_VALUE
        if (hypothesis.revision <= previousRevision) return
        lastRevisionByUtterance[hypothesis.utteranceId] = hypothesis.revision

        val existing = turnsByUtterance[hypothesis.utteranceId].orEmpty()
        val validWords = validatedWords(hypothesis)
        if (validWords == null) {
            reconcileTranscript(hypothesis, existing)
        } else {
            reconcileWords(hypothesis, validWords, existing)
        }
    }

    fun edit(turnId: String, text: String) {
        val state = findTurn(turnId) ?: return
        state.editAnchor = MeetingEditAnchor.capture(
            sourceWords = state.identityWords,
            editedText = text,
            sourceText = state.turn.recognizedText,
        )
        state.editBaseText = text
        state.turn = state.turn.copy(editedText = text)
    }

    fun assign(turnId: String, participantId: String?) {
        val state = findTurn(turnId) ?: return
        state.turn = state.turn.copy(
            manualParticipantId = participantId,
            hasManualAttribution = true,
        )
    }

    fun rename(participantId: String, name: String) {
        participants.rename(participantId, name)
    }

    fun setIgnored(participantId: String, ignored: Boolean) {
        participants.setIgnored(participantId, ignored)
    }

    fun snapshot(): MeetingDocument = MeetingDocument(
        sessionId = sessionId,
        runId = runId,
        participants = participants.all(),
        turns = turnsByUtterance.values.flatten()
            .map { it.turn }
            .sortedWith(compareBy<MeetingTurn> { it.utteranceId }.thenBy { it.startMs }.thenBy { it.id }),
    )

    private fun reconcileWords(
        hypothesis: MeetingHypothesis,
        words: List<MeetingWord>,
        existing: List<StoredTurn>,
    ) {
        words.forEach { word ->
            if (word.channel in MIN_CHANNEL..MAX_CHANNEL) participants.observe(word.channel)
        }
        if (existing.isEmpty()) {
            turnsByUtterance[hypothesis.utteranceId] = newTurnsForGroups(hypothesis, words)
            return
        }

        val states = existing.sortedWith(compareBy<StoredTurn> { it.turn.startMs }.thenBy { it.turn.id })
        val protectedStates = states.filter { it.editAnchor != null || it.turn.hasManualAttribution }
        val editTextAlignments = alignTextAnchors(
            protectedStates.filter { it.editAnchor != null },
            hypothesis.transcript,
        )
        val editWordAlignments = alignTextWordAnchors(
            protectedStates.filter { it.editAnchor != null },
            words,
            hypothesis.transcript,
        )
        val mappings = protectedStates.map { state ->
            val matchWords = state.identityWords.ifEmpty { state.editAnchor?.sourceWords.orEmpty() }
            val match = MeetingEditAnchor.capture(matchWords, state.turn.editedText.orEmpty()).locateWords(words)
            val matched = if (match.isAligned) match.matchedIndices else emptyList()
            val timeMatches = if (matched.isEmpty()) {
                words.indices.filter { index ->
                    val word = words[index]
                    word.startMs < state.turn.endMs && state.turn.startMs < word.endMs
                }
            } else emptyList()
            StateMapping(state, matched.ifEmpty { timeMatches })
        }

        val owners = arrayOfNulls<StoredTurn>(words.size)
        mappings.forEach { mapping ->
            val indices = mapping.indices
            if (indices.isEmpty()) return@forEach
            val start = indices.minOrNull() ?: return@forEach
            val end = indices.maxOrNull() ?: return@forEach
            for (index in start..end) {
                val currentOwner = owners[index]
                if (currentOwner == null || isCloser(words[index], mapping.state, currentOwner)) {
                    owners[index] = mapping.state
                }
            }
        }

        // A same-speaker continuation belongs to its anchored turn. New speaker boundaries
        // remain new turns, while words covered by an old anchor never split that turn.
        for (index in words.indices) {
            if (owners[index] != null) continue
            val word = words[index]
            val previous = mappings.lastOrNull { it.indices.isNotEmpty() && (it.indices.maxOrNull() ?: -1) < index }
            val next = mappings.firstOrNull { it.indices.isNotEmpty() && (it.indices.minOrNull() ?: Int.MAX_VALUE) > index }
            val previousEnd = previous?.indices?.maxOrNull() ?: -1
            val nextStart = next?.indices?.minOrNull() ?: Int.MAX_VALUE
            if (previous != null && index in (previousEnd + 1) until nextStart) {
                val alignedAnchorWord = editWordAlignments.firstOrNull { it.state === previous.state }
                    ?.match?.matchedWordIndices?.lastOrNull()?.let(words::get)
                val anchorChannel = previous.state.identityWords.lastOrNull()?.channel?.normalizedChannel()
                    ?: alignedAnchorWord?.channel
                val currentlyMatchedChannels = previous.indices.map { words[it].channel }.distinct()
                if (anchorChannel == word.channel && currentlyMatchedChannels.size <= 1) {
                    owners[index] = previous.state
                    continue
                }
            }
            if (next != null && index < nextStart && next.state.editAnchor == null) {
                val anchorChannel = next.state.identityWords.firstOrNull()?.channel?.normalizedChannel()
                if (anchorChannel == word.channel) owners[index] = next.state
            }
        }

        val protectedResults = mutableListOf<StoredTurn>()
        mappings.forEach { mapping ->
            val state = mapping.state
            val assignedIndices = words.indices.filter { owners[it] === state }
            if (assignedIndices.isEmpty()) {
                if (state.editAnchor != null || state.turn.hasManualAttribution) protectedResults += state
                return@forEach
            }
            val assignedWords = assignedIndices.map(words::get)
            val recognized = joinWords(assignedWords)
            val channels = assignedWords.map { it.channel }.distinct()
            val singleKnownChannel = channels.singleOrNull()?.takeIf { it in MIN_CHANNEL..MAX_CHANNEL }
            val participantId = singleKnownChannel?.let { participants.observe(it)?.id }
            val attributionStable = singleKnownChannel != null &&
                assignedWords.maxOf { it.endMs } <= hypothesis.stableSpeakerThroughMs
            var editedText = state.turn.editedText

            val editAnchor = state.editAnchor
            if (editAnchor != null) {
                val anchorMatch = editAnchor.locateWords(words)
                if (anchorMatch.isAligned) {
                    val matchedEnd = anchorMatch.lastIndex
                    val suffix = if (matchedEnd == null) emptyList() else assignedIndices
                        .filter { it > matchedEnd }
                        .map(words::get)
                    editedText = appendText(state.editBaseText.orEmpty(), joinWords(suffix))
                    addDiagnostic(hypothesis.utteranceId, state.turn.id, MeetingEditAlignmentStatus.ALIGNED)
                } else {
                    val textAlignment = editTextAlignments.firstOrNull { it.state === state }
                    val wordAlignment = editWordAlignments.firstOrNull { it.state === state }
                    if (textAlignment?.isAligned == true && wordAlignment?.match?.isAligned == true) {
                        val suffix = assignedIndices
                            .filter { it in wordAlignment.continuationWordIndices }
                            .map(words::get)
                        editedText = appendText(state.editBaseText.orEmpty(), joinWords(suffix))
                        addDiagnostic(hypothesis.utteranceId, state.turn.id, MeetingEditAlignmentStatus.ALIGNED)
                    } else {
                        editedText = state.editBaseText.orEmpty()
                        addDiagnostic(hypothesis.utteranceId, state.turn.id, MeetingEditAlignmentStatus.UNRESOLVED)
                    }
                }
            }

            state.turn = state.turn.copy(
                startMs = assignedWords.minOf { it.startMs },
                endMs = assignedWords.maxOf { it.endMs },
                recognizedText = recognized,
                automaticParticipantId = participantId,
                editedText = editedText,
                attributionStable = attributionStable,
            )
            state.identityWords = assignedWords
            protectedResults += state
        }

        val unprotectedPool = states.filter { it !in protectedStates }.toMutableList()
        val rebuiltUnprotected = mutableListOf<StoredTurn>()
        contiguousGroups(words.indices.filter { owners[it] == null }, words).forEach { group ->
            val match = unprotectedPool
                .map { state -> state to similarityScore(state.identityWords, group) }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<StoredTurn, Int>> { it.second }
                    .thenBy { -kotlin.math.abs(it.first.turn.startMs - group.first().startMs) })
            if (match == null) {
                rebuiltUnprotected += createState(hypothesis, group)
            } else {
                val state = match.first
                unprotectedPool.remove(state)
                updateUnprotectedState(state, hypothesis, group)
                rebuiltUnprotected += state
            }
        }
        turnsByUtterance[hypothesis.utteranceId] = (protectedResults + rebuiltUnprotected)
            .sortedWith(compareBy<StoredTurn> { it.turn.startMs }.thenBy { it.turn.id })
            .toMutableList()
    }

    private fun updateUnprotectedState(
        state: StoredTurn,
        hypothesis: MeetingHypothesis,
        group: List<MeetingWord>,
    ) {
        val channel = group.map { it.channel }.distinct().singleOrNull()
        val participantId = channel?.takeIf { it in MIN_CHANNEL..MAX_CHANNEL }?.let { participants.observe(it)?.id }
        val attributionStable = channel != null && channel in MIN_CHANNEL..MAX_CHANNEL &&
            group.maxOf { it.endMs } <= hypothesis.stableSpeakerThroughMs
        state.turn = state.turn.copy(
            startMs = group.minOf { it.startMs },
            endMs = group.maxOf { it.endMs },
            recognizedText = joinWords(group),
            automaticParticipantId = participantId,
            editedText = null,
            attributionStable = attributionStable,
        )
        state.identityWords = group.toList()
    }

    private fun similarityScore(oldWords: List<MeetingWord>, newWords: List<MeetingWord>): Int {
        if (oldWords.isEmpty() || newWords.isEmpty()) return 0
        return oldWords.count { oldWord -> newWords.any { newWord -> wordsCorrespond(oldWord, newWord) } }
    }

    private fun wordsCorrespond(old: MeetingWord, new: MeetingWord): Boolean {
        val sameText = old.text.filter { it.isLetterOrDigit() }
            .equals(new.text.filter { it.isLetterOrDigit() }, ignoreCase = true)
        val overlaps = old.startMs < new.endMs && new.startMs < old.endMs
        val near = kotlin.math.abs(old.startMs - new.startMs) <= WORD_MATCH_TOLERANCE_MS &&
            kotlin.math.abs(old.endMs - new.endMs) <= WORD_MATCH_TOLERANCE_MS
        return (sameText && (overlaps || near)) || (overlaps && near)
    }

    private fun reconcileTranscript(hypothesis: MeetingHypothesis, existing: List<StoredTurn>) {
        val transcript = hypothesis.transcript
        if (!transcript.hasVisibleText()) return
        if (existing.isEmpty()) {
            val id = newTurnId(hypothesis.utteranceId)
            val state = StoredTurn(
                turn = MeetingTurn(
                    id = id,
                    utteranceId = hypothesis.utteranceId,
                    startMs = 0,
                    endMs = hypothesis.audioProcessedMs.coerceAtLeast(0),
                    recognizedText = transcript,
                    automaticParticipantId = null,
                    attributionStable = false,
                ),
                identityWords = emptyList(),
            )
            turnsByUtterance[hypothesis.utteranceId] = mutableListOf(state)
            return
        }
        val states = existing.sortedWith(compareBy<StoredTurn> { it.turn.startMs }.thenBy { it.turn.id })
        val textAlignments = alignTextAnchors(states, transcript)
        val allAligned = textAlignments.size == states.size && textAlignments.all { it.isAligned }
        val protectedRawCarrierIndex = if (allAligned) {
            -1
        } else {
            states.indexOfFirst { it.editAnchor != null }
                .takeIf { it >= 0 }
                ?: states.indexOfFirst { it.turn.hasManualAttribution }
        }
        if (protectedRawCarrierIndex >= 0) {
            val carrier = states[protectedRawCarrierIndex]
            if (carrier.editAnchor == null) {
                val visibleBody = carrier.turn.editedText ?: carrier.turn.recognizedText
                carrier.editAnchor = MeetingEditAnchor.capture(
                    sourceWords = carrier.identityWords,
                    editedText = visibleBody,
                    sourceText = carrier.turn.recognizedText,
                )
                carrier.editBaseText = visibleBody
                carrier.turn = carrier.turn.copy(editedText = visibleBody)
            }
        }

        states.forEachIndexed { index, state ->
            val alignment = textAlignments.getOrNull(index)
            if (!allAligned || alignment == null) {
                // Ambiguous protected anchors keep their visible body; retain the raw hypothesis
                // on one protected turn rather than guessing a continuation or duplicating it in
                // an earlier unprotected turn.
                val fallbackRecognizedText = when {
                    protectedRawCarrierIndex >= 0 -> {
                        if (index == protectedRawCarrierIndex) transcript else state.turn.recognizedText
                    }
                    index == 0 -> transcript
                    else -> ""
                }
                state.turn = state.turn.copy(
                    recognizedText = fallbackRecognizedText,
                    automaticParticipantId = null,
                    editedText = state.editBaseText ?: state.turn.editedText,
                    attributionStable = false,
                )
                if (state.editAnchor != null) {
                    addDiagnostic(hypothesis.utteranceId, state.turn.id, MeetingEditAlignmentStatus.UNRESOLVED)
                }
                return@forEachIndexed
            }

            val nextAlignment = textAlignments.getOrNull(index + 1)
            val match = requireNotNull(alignment.match)
            val segmentStart = if (index == 0) 0 else match.startChar
            val segmentEnd = nextAlignment?.match?.startChar ?: transcript.length
            val safeEnd = segmentEnd.coerceAtLeast(segmentStart).coerceAtMost(transcript.length)
            val recognized = transcript.substring(segmentStart.coerceAtMost(safeEnd), safeEnd).trim()
            val editedText = if (state.editAnchor != null) {
                val continuation = transcript.substring(match.endChar.coerceAtMost(safeEnd), safeEnd).trimStart()
                addDiagnostic(hypothesis.utteranceId, state.turn.id, MeetingEditAlignmentStatus.ALIGNED)
                appendText(state.editBaseText.orEmpty(), continuation)
            } else {
                state.turn.editedText
            }
            state.turn = state.turn.copy(
                recognizedText = recognized,
                automaticParticipantId = null,
                editedText = editedText,
                attributionStable = false,
            )
        }
    }

    private fun alignTextAnchors(states: List<StoredTurn>, transcript: String): List<TextAnchorAlignment> {
        if (states.isEmpty()) return emptyList()
        if (states.size > MAX_TEXT_ANCHORED_TURNS || transcript.length > MAX_FALLBACK_TEXT_LENGTH) {
            return states.map { TextAnchorAlignment(it, false, null, "") }
        }

        var nextToken = 0
        val matches = states.map { state ->
            val sourceText = state.editAnchor?.sourceText ?: state.turn.recognizedText
            val match = MeetingEditAnchor.capture(emptyList(), "", sourceText).locateText(transcript, nextToken)
            if (match.isAligned) nextToken = match.nextTokenIndex
            TextAnchorAlignment(state, match.isAligned, match, "")
        }
        if (matches.any { !it.isAligned }) return matches

        return matches.mapIndexed { index, current ->
            val match = requireNotNull(current.match)
            val nextStart = matches.getOrNull(index + 1)?.match?.startChar ?: transcript.length
            val end = nextStart.coerceIn(match.endChar, transcript.length)
            current.copy(continuation = transcript.substring(match.endChar, end).trimStart())
        }
    }

    private fun alignTextWordAnchors(
        states: List<StoredTurn>,
        words: List<MeetingWord>,
        transcript: String,
    ): List<TextWordAnchorAlignment> {
        if (states.isEmpty()) return emptyList()
        if (states.size > MAX_TEXT_ANCHORED_TURNS) {
            return states.map { TextWordAnchorAlignment(it, null, emptyList()) }
        }

        var nextWordIndex = 0
        val matches = states.map { state ->
            val match = requireNotNull(state.editAnchor).locateTextWords(words, nextWordIndex, transcript)
            if (match.isAligned) nextWordIndex = (match.lastWordIndex ?: (nextWordIndex - 1)) + 1
            state to match
        }
        if (matches.any { !it.second.isAligned }) {
            return matches.map { (state, match) -> TextWordAnchorAlignment(state, match, emptyList()) }
        }

        return matches.mapIndexed { index, (state, match) ->
            val lastAnchorWord = requireNotNull(match.lastWordIndex)
            val nextAnchorWord = matches.getOrNull(index + 1)?.second?.firstWordIndex ?: words.size
            val endExclusive = nextAnchorWord.coerceAtLeast(lastAnchorWord + 1).coerceAtMost(words.size)
            TextWordAnchorAlignment(state, match, (lastAnchorWord + 1 until endExclusive).toList())
        }
    }

    private fun newTurnsForGroups(hypothesis: MeetingHypothesis, words: List<MeetingWord>): MutableList<StoredTurn> =
        contiguousGroups(words.indices.toList(), words).map { group -> createState(hypothesis, group) }.toMutableList()

    private fun createState(hypothesis: MeetingHypothesis, group: List<MeetingWord>): StoredTurn {
        val first = group.first()
        val channel = first.channel
        val participantId = if (channel in MIN_CHANNEL..MAX_CHANNEL) participants.observe(channel)?.id else null
        val stable = channel in MIN_CHANNEL..MAX_CHANNEL && group.maxOf { it.endMs } <= hypothesis.stableSpeakerThroughMs
        val id = newTurnId(hypothesis.utteranceId)
        return StoredTurn(
            turn = MeetingTurn(
                id = id,
                utteranceId = hypothesis.utteranceId,
                startMs = group.minOf { it.startMs },
                endMs = group.maxOf { it.endMs },
                recognizedText = joinWords(group),
                automaticParticipantId = participantId,
                attributionStable = stable,
            ),
            identityWords = group.toList(),
        )
    }

    private fun contiguousGroups(indices: List<Int>, words: List<MeetingWord>): List<List<MeetingWord>> {
        if (indices.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<MeetingWord>>()
        var previousIndex = -2
        var previousChannel: Int? = null
        for (index in indices.sorted()) {
            val word = words[index]
            if (groups.isEmpty() || index != previousIndex + 1 || word.channel != previousChannel) {
                groups.add(mutableListOf())
            }
            groups.last() += word
            previousIndex = index
            previousChannel = word.channel
        }
        return groups
    }

    private fun validatedWords(hypothesis: MeetingHypothesis): List<MeetingWord>? {
        if (hypothesis.words.isEmpty()) return null
        var previousStart = -1L
        val result = mutableListOf<MeetingWord>()
        for (word in hypothesis.words) {
            if (!word.text.hasVisibleText() || word.startMs < 0 || word.endMs <= word.startMs ||
                word.endMs > hypothesis.audioProcessedMs || word.startMs < previousStart
            ) return null
            previousStart = word.startMs
            result += word.copy(channel = word.channel.normalizedChannel())
        }
        return result
    }

    private fun findTurn(turnId: String): StoredTurn? =
        turnsByUtterance.values.asSequence().flatten().firstOrNull { it.turn.id == turnId }

    private fun isCloser(word: MeetingWord, candidate: StoredTurn, current: StoredTurn): Boolean {
        val candidateCenter = candidate.turn.startMs + (candidate.turn.endMs - candidate.turn.startMs) / 2
        val currentCenter = current.turn.startMs + (current.turn.endMs - current.turn.startMs) / 2
        val wordCenter = word.startMs + (word.endMs - word.startMs) / 2
        return kotlin.math.abs(wordCenter - candidateCenter) < kotlin.math.abs(wordCenter - currentCenter)
    }

    private fun addDiagnostic(utteranceId: Long, turnId: String, status: MeetingEditAlignmentStatus) {
        diagnostics += MeetingEditAlignmentDiagnostic(utteranceId, turnId, status)
        if (diagnostics.size > MAX_DIAGNOSTICS) diagnostics.removeAt(0)
    }

    private fun newTurnId(utteranceId: Long): String {
        val sequence = nextTurnSequence++
        return "$sessionId:turn:$utteranceId:${sequence.toString().padStart(ID_SEQUENCE_WIDTH, '0')}"
    }

    private fun joinWords(words: List<MeetingWord>): String = words.fold("") { text, word ->
        val token = word.text.trim()
        when {
            token.isEmpty() -> text
            text.isEmpty() -> token
            token.first().isPunctuationOrSymbol() -> text + token
            else -> "$text $token"
        }
    }

    private fun appendText(base: String, continuation: String): String {
        val suffix = continuation.trim()
        if (suffix.isEmpty()) return base
        if (base.isEmpty()) return suffix
        return if (suffix.first().isPunctuationOrSymbol()) base + suffix else "$base $suffix"
    }

    private fun Char.isPunctuationOrSymbol(): Boolean {
        val type = Character.getType(code)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.MATH_SYMBOL.toInt() ||
            type == Character.CURRENCY_SYMBOL.toInt() ||
            type == Character.MODIFIER_SYMBOL.toInt() ||
            type == Character.OTHER_SYMBOL.toInt()
    }

    private fun Int.normalizedChannel(): Int = if (this in MIN_CHANNEL..MAX_CHANNEL) this else UNKNOWN_CHANNEL

    private fun String.hasVisibleText(): Boolean =
        any { !Character.isWhitespace(it) && !Character.isSpaceChar(it) }

    private data class StoredTurn(
        var turn: MeetingTurn,
        var identityWords: List<MeetingWord>,
        var editAnchor: MeetingEditAnchor? = null,
        var editBaseText: String? = null,
    )

    private data class StateMapping(val state: StoredTurn, val indices: List<Int>)

    private data class TextAnchorAlignment(
        val state: StoredTurn,
        val isAligned: Boolean,
        val match: MeetingEditAnchor.TextMatch?,
        val continuation: String,
    )

    private data class TextWordAnchorAlignment(
        val state: StoredTurn,
        val match: MeetingEditAnchor.TextWordMatch?,
        val continuationWordIndices: List<Int>,
    )

    private companion object {
        const val MIN_CHANNEL = 1
        const val MAX_CHANNEL = 8
        const val UNKNOWN_CHANNEL = 0
        const val MAX_DIAGNOSTICS = 64
        const val MAX_TEXT_ANCHORED_TURNS = 64
        const val MAX_FALLBACK_TEXT_LENGTH = 8_192
        const val ID_SEQUENCE_WIDTH = 12
        const val MAX_TEXT_WORDS = 512
        const val WORD_MATCH_TOLERANCE_MS = 500L
    }
}
