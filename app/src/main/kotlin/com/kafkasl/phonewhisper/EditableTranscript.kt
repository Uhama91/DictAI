package com.kafkasl.phonewhisper

/** User edits own the visible prefix; recognition may only revise the unedited continuation. */
internal class EditableTranscript {
    private var raw = emptyList<String>()
    private var protectedWords = 0
    private var edited: String? = null
    private var userEdited = false
    private var displayed = ""
    private var manualRanges = emptyList<IntRange>()

    @Synchronized fun hasUserEdits(): Boolean = userEdited

    /** Only actual keyboard changes are exempt from explicit hesitation removal. */
    @Synchronized fun manualProtection(text: String): List<IntRange> =
        if (text == displayed) manualRanges.toList()
        else if (userEdited && text.isNotEmpty()) listOf(text.indices) else emptyList()

    @Synchronized fun clear() {
        raw = emptyList()
        protectedWords = 0
        edited = null
        userEdited = false
        displayed = ""
        manualRanges = emptyList()
    }

    @Synchronized fun edit(text: String) {
        trackChange(text, manual = true)
        userEdited = true
        anchor(text)
    }

    /** Insert a context reference without pretending it was a manual wording correction. */
    @Synchronized fun anchor(text: String) {
        trackChange(text, manual = false)
        edited = text
        protectedWords = raw.size
    }

    // Align the recognizer's words, not vocabulary output: a two-word alias can become one name.
    // Transform only the unedited continuation so a user's manual prefix is never rewritten.
    @Synchronized fun update(text: String, transform: (String) -> String = { it }): String {
        val matches = Regex("\\S+").findAll(text).toList()
        val next = matches.map { it.value }
        if (edited == null) {
            raw = next
            return transform(text).also { displayed = it }
        }
        protectedWords = mapBoundary(raw, next, protectedWords)
        raw = next
        val tail = transform(matches.getOrNull(protectedWords)?.range?.first?.let { text.substring(it) }.orEmpty())
        val prefix = edited.orEmpty()
        if (tail.isEmpty()) return prefix.also { displayed = it }
        val sentenceStart = prefix.trimEnd().lastOrNull() in listOf('.', '!', '?', '…') || prefix.endsWith('\n')
        val continuation = if (sentenceStart) tail.replaceFirstChar { it.titlecase() } else tail
        return (prefix + (if (prefix.isEmpty() || prefix.last().isWhitespace()) "" else " ") + continuation)
            .also { displayed = it }
    }

    /** A manually written draft remains publishable when the recognizer returns no speech. */
    @Synchronized fun resolveFinal(recognized: String?, transform: (String) -> String = { it }): String? =
        if (recognized.isNullOrBlank()) edited?.also { displayed = it } else update(recognized, transform)

    private fun trackChange(next: String, manual: Boolean) {
        if (next == displayed) return
        val prefix = displayed.commonPrefixWith(next).length
        val suffix = displayed.drop(prefix).commonSuffixWith(next.drop(prefix)).length
        val oldEnd = displayed.length - suffix
        val newEnd = next.length - suffix
        val shift = newEnd - oldEnd
        val overlapsManual = manualRanges.any { it.first < oldEnd && it.last >= prefix }
        val ranges = mutableListOf<IntRange>()
        for (range in manualRanges) {
            if (range.first < prefix) ranges.add(range.first..minOf(range.last, prefix - 1))
            if (range.last >= oldEnd) ranges.add(maxOf(range.first, oldEnd) + shift..range.last + shift)
        }
        if (manual || overlapsManual) {
            var start = prefix
            var end = newEnd
            // Editing one letter protects the resulting word, including delete-then-type.
            fun wordChar(c: Char) = c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt()
            while (start > 0 && wordChar(next[start - 1])) start--
            while (end < next.length && wordChar(next[end])) end++
            if (start < end) ranges.add(start until end)
        }
        manualRanges = ranges.filter { !it.isEmpty() && it.first >= 0 && it.last < next.length }
            .sortedBy { it.first }.fold(mutableListOf()) { merged, range ->
                if (merged.isNotEmpty() && range.first <= merged.last().last + 1)
                    merged[merged.lastIndex] = merged.last().first..maxOf(merged.last().last, range.last)
                else merged.add(range)
                merged
            }
        displayed = next
    }

    /** Align ASR revisions, including inserted/deleted words, without counting user-added words. */
    private fun mapBoundary(old: List<String>, next: List<String>, boundary: Int): Int {
        var common = 0
        while (common < minOf(old.size, next.size) && old[common] == next[common]) common++
        if (boundary <= common) return boundary
        val a = old.drop(common)
        val b = next.drop(common)
        // Cap pathological complete rewrites: retain the edited prefix and a conservative boundary.
        if (a.size.toLong() * b.size > 1_000_000) return minOf(boundary, next.size)
        val costs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size downTo 0) for (j in b.size downTo 0) {
            costs[i][j] = when {
                i == a.size -> b.size - j
                j == b.size -> a.size - i
                else -> minOf(costs[i + 1][j + 1] + if (a[i] == b[j]) 0 else 1,
                    costs[i + 1][j] + 1, costs[i][j + 1] + 1)
            }
        }
        var i = 0; var j = 0
        while (i < boundary - common) {
            when {
                j == b.size -> i++
                costs[i][j] == costs[i + 1][j + 1] + if (a[i] == b[j]) 0 else 1 -> { i++; j++ }
                costs[i][j] == costs[i + 1][j] + 1 -> i++
                else -> j++
            }
        }
        return common + j
    }
}
