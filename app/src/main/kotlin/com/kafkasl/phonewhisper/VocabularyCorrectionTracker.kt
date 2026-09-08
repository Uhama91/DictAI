package com.kafkasl.phonewhisper

/** Tracks explicit replacements; recognition changes must use onProgrammaticTextChanged. */
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
    fun reset() { change = null; pending = null }
    fun beforeChange(text: String, start: Int, count: Int, afterCount: Int, selectionStart: Int, selectionEnd: Int) {
        if (start !in 0..text.length || count < 0 || start + count > text.length || afterCount < 0) {
            reset(); return
        }
        change = Change(text, start, count, afterCount, selectionStart, selectionEnd)
    }
    fun afterChange(text: String, nowMillis: Long) {
        val edit = change ?: return
        change = null
        val insertedEnd = edit.start + edit.afterCount
        if (insertedEnd > text.length || text != edit.text.take(edit.start) + text.substring(edit.start, insertedEnd) + edit.text.drop(edit.start + edit.count)) {
            pending = null; return
        }
        val previous = pending
        if (previous != null && edit.text == previous.text && nowMillis - previous.changedAt in 0..editTimeoutMillis &&
            edit.start >= previous.start && edit.start + edit.count <= previous.end) {
            val replacementLength = previous.replacement.length - edit.count + edit.afterCount
            pending = previous.copy(replacement = text.substring(previous.start, previous.start + replacementLength), changedAt = nowMillis)
            return
        }
        pending = null
        val first = minOf(edit.selectionStart, edit.selectionEnd)
        val last = maxOf(edit.selectionStart, edit.selectionEnd)
        if (first < 0 || first == last || edit.start != first || edit.count != last - first) return
        val selected = edit.text.substring(first, last)
        val from = selected.trim()
        if (!isVocabularyTerm(from)) return
        val trimmedStart = first + selected.indexOfFirst { !it.isWhitespace() }
        val trimmedEnd = trimmedStart + from.length
        if ((trimmedStart > 0 && isWordCharacter(edit.text[trimmedStart - 1])) ||
            (trimmedEnd < edit.text.length && isWordCharacter(edit.text[trimmedEnd]))) return
        pending = Pending(from, edit.text.take(edit.start), edit.text.drop(edit.start + edit.count), text.substring(edit.start, insertedEnd), nowMillis)
    }
    fun onSelectionChanged(text: String, start: Int, end: Int) {
        val candidate = pending ?: return
        if (change != null || text != candidate.text) return
        if (candidate.replacement.isEmpty() && (start != candidate.start || end != candidate.start)) pending = null
    }
    fun onProgrammaticTextChanged(text: String) {
        change = null
        val candidate = pending ?: return
        if (text.startsWith(candidate.text)) {
            pending = candidate.copy(suffix = candidate.suffix + text.substring(candidate.text.length))
        } else pending = null
    }
    fun suggestion(text: String, selectionStart: Int, selectionEnd: Int, hasComposingText: Boolean, nowMillis: Long): Suggestion? {
        val candidate = pending ?: return null
        if (text != candidate.text || hasComposingText || selectionStart < 0 || selectionStart != selectionEnd ||
            nowMillis - candidate.changedAt < settleDelayMillis) return null
        if (selectionStart > candidate.start && selectionStart < candidate.end) return null
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
