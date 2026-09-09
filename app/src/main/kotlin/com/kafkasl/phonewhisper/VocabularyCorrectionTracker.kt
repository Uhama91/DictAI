package com.kafkasl.phonewhisper

/** Tracks manual spelling edits and replacements; recognition changes never start learning. */
internal class VocabularyCorrectionTracker(
    val settleDelayMillis: Long = 1_000L,
    private val editTimeoutMillis: Long = 15_000L,
) {
    data class Suggestion(val from: String, val to: String)
    private data class Change(
        val text: String, val start: Int, val count: Int, val afterCount: Int,
        val selectionStart: Int, val selectionEnd: Int,
    )
    private data class Pending(
        val from: String, val prefix: String, val suffix: String,
        val replacement: String, val changedAt: Long,
    ) {
        val start get() = prefix.length
        val end get() = start + replacement.length
        val text get() = prefix + replacement + suffix
    }
    private var change: Change? = null
    private var pending: Pending? = null
    private var typing: Pending? = null
    val hasPending: Boolean get() = pending != null
    private data class Selection(val text: String, val start: Int, val end: Int)
    private var selected: Selection? = null
    fun reset() { change = null; pending = null; selected = null; typing = null }
    fun beforeChange(text: String, start: Int, count: Int, afterCount: Int, selectionStart: Int, selectionEnd: Int) {
        if (start !in 0..text.length || count < 0 || start + count > text.length || afterCount < 0) {
            reset(); return
        }
        // Some IMEs collapse the selection before sending the replacement to TextWatcher.
        // Keep a selected phrase only when the actual edit is contained in that same source range.
        val remembered = selected?.takeIf { it.text == text }
        change = Change(text, start, count, afterCount,
            remembered?.start ?: selectionStart, remembered?.end ?: selectionEnd)
        selected = null
    }
    fun afterChange(text: String, nowMillis: Long) {
        val edit = change ?: return
        change = null
        // Gboard may report a whole composing region although only one letter changed.
        // Compute the actual edit before extending the corrected word or selected phrase.
        val precise = (edit.count == 0 || edit.afterCount == 0) &&
            text.length == edit.text.length - edit.count + edit.afterCount &&
            text.startsWith(edit.text.take(edit.start)) && text.endsWith(edit.text.drop(edit.start + edit.count))
        // With adjacent identical spaces/letters, a minimal diff can move a backspace to the
        // other side of the caret. Prefer the verified IME position for pure insertions/deletions.
        val start = if (precise) edit.start else edit.text.commonPrefixWith(text).length
        val suffixLength = edit.text.drop(start).commonSuffixWith(text.drop(start)).length
        val oldEnd = if (precise) start + edit.count else edit.text.length - suffixLength
        val newEnd = if (precise) start + edit.afterCount else text.length - suffixLength
        if (start == oldEnd && start == newEnd) return
        val newlyTyped = typing
        if (newlyTyped != null && edit.text == newlyTyped.text && start >= newlyTyped.start && oldEnd <= newlyTyped.end) {
            val length = newlyTyped.replacement.length - (oldEnd - start) + (newEnd - start)
            typing = newlyTyped.copy(replacement = text.substring(newlyTyped.start, newlyTyped.start + length), changedAt = nowMillis)
            return
        }
        typing = null
        val previous = pending
        if (previous != null && edit.text == previous.text && nowMillis - previous.changedAt in 0..editTimeoutMillis &&
            previous.replacement.isEmpty() && oldEnd == previous.start && newEnd == start) {
            // Backspace can cross the space between two misrecognized words. Keep both source
            // words instead of forgetting the first deleted word when the next deletion starts.
            var first = start
            while (first > 0 && isWordCharacter(edit.text[first - 1])) first--
            val from = (edit.text.substring(first, previous.start) + previous.from).trim()
            if (isVocabularyTerm(from)) {
                pending = Pending(from, text.take(first), previous.suffix, text.substring(first, start), nowMillis)
                return
            }
        }
        if (previous != null && edit.text == previous.text && nowMillis - previous.changedAt in 0..editTimeoutMillis &&
            start >= previous.start && oldEnd <= previous.end) {
            val replacementLength = previous.replacement.length - (oldEnd - start) + (newEnd - start)
            pending = previous.copy(replacement = text.substring(previous.start, previous.start + replacementLength), changedAt = nowMillis)
            return
        }
        pending = null
        var first = minOf(edit.selectionStart, edit.selectionEnd)
        var last = maxOf(edit.selectionStart, edit.selectionEnd)
        if (first < 0 || first == last || start < first || oldEnd > last) {
            // In-place spelling edits count too: AF -> CAF, Haron -> Haroun, repeated backspace.
            // Ordinary words typed after a space have no existing source word to learn from.
            val inserted = text.substring(start, newEnd)
            if (start == oldEnd && inserted.any { it.isWhitespace() }) return
            first = start
            last = oldEnd
        }
        while (first > 0 && isWordCharacter(edit.text[first - 1])) first--
        while (last < edit.text.length && isWordCharacter(edit.text[last])) last++
        val source = edit.text.substring(first, last)
        val from = source.trim()
        val replacementEnd = last + text.length - edit.text.length
        if (replacementEnd < first || replacementEnd > text.length) return
        if (!isVocabularyTerm(from)) {
            if (from.isEmpty()) typing = Pending("", edit.text.take(first), edit.text.drop(last), text.substring(first, replacementEnd), nowMillis)
            return
        }
        pending = Pending(from, edit.text.take(first), edit.text.drop(last), text.substring(first, replacementEnd), nowMillis)
    }
    fun onSelectionChanged(text: String, start: Int, end: Int) {
        if (typing?.let { text == it.text && (start !in it.start..it.end || end !in it.start..it.end) } == true) typing = null
        if (change == null) {
            if (start >= 0 && end >= 0 && start != end) selected = Selection(text, minOf(start, end), maxOf(start, end))
            else if (selected?.let { it.text != text || start !in listOf(it.start, it.end) } == true) selected = null
        }
        val candidate = pending ?: return
        if (change != null || text != candidate.text) return
        if (candidate.replacement.isEmpty() && (start != candidate.start || end != candidate.start)) pending = null
    }
    fun onProgrammaticTextChanged(text: String) {
        change = null
        selected = null
        typing = typing?.takeIf { text.startsWith(it.prefix + it.replacement) }
            ?.let { it.copy(suffix = text.substring(it.end)) }
        val candidate = pending ?: return
        // Recognition may revise its unedited continuation while the replacement stays intact.
        if (text.startsWith(candidate.prefix + candidate.replacement) &&
            !(candidate.replacement.lastOrNull()?.let(::isWordCharacter) == true &&
                text.getOrNull(candidate.end)?.let(::isWordCharacter) == true)) {
            pending = candidate.copy(suffix = text.substring(candidate.end))
        } else pending = null
    }
    fun suggestion(text: String, selectionStart: Int, selectionEnd: Int, hasComposingText: Boolean, nowMillis: Long): Suggestion? {
        val candidate = pending ?: return null
        val settledFor = nowMillis - candidate.changedAt
        // An IME can keep the last completed name composing until a space is typed. An explicit
        // Save tap may confirm that stable spelling; no rule is ever added by this tracker alone.
        if (text != candidate.text || selectionStart < 0 || selectionStart != selectionEnd ||
            settledFor < if (hasComposingText) maxOf(settleDelayMillis, 1_500L) else settleDelayMillis) return null
        val to = candidate.replacement.trim()
        if (!isVocabularyTerm(to) || candidate.from == to) return null
        return Suggestion(candidate.from, to)
    }
    fun consume(suggestion: Suggestion, text: String): Boolean {
        val candidate = pending ?: return false
        if (text != candidate.text || candidate.from != suggestion.from || candidate.replacement.trim() != suggestion.to) return false
        reset()
        return true
    }
    private fun isVocabularyTerm(text: String): Boolean =
        text.isNotBlank() && text.length <= 100 && text.none { it == '\n' || it == '\r' } &&
            !text.contains("=>") && text.any { it.isLetterOrDigit() } && text.split(Regex("\\s+")).size <= 8
    private fun isWordCharacter(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || Character.getType(char) in listOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
        )
}
