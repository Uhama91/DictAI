package com.kafkasl.phonewhisper

internal enum class NoteImageKind(val label: String) {
    SCREENSHOT("Capture d’écran"), CAMERA("Photo"), SCAN("Document scanné")
}

/** One-step movement commands exposed by the note editor. */
internal enum class NoteImageMove(val delta: Int) { UP(-1), DOWN(1) }

internal data class NoteImage(
    val id: String,
    val number: Int,
    val kind: NoteImageKind,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
) {
    init { require(validId(id) && number > 0 && width > 0 && height > 0) }
    val marker: String get() = NoteImageMarkers.marker(number)
    companion object {
        const val MAX_IMAGES = 10
        fun validId(id: String) = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id)
        fun nextNumber(images: List<NoteImage>, text: String): Int =
            maxOf(images.maxOfOrNull { it.number } ?: 0, NoteImageMarkers.numbers(text).maxOrNull() ?: 0) + 1
    }
}

/** Visible, portable references: no file paths or image bytes ever enter a language model. */
internal object NoteImageMarkers {
    private val pattern = Regex("\\[\\[Image ([1-9][0-9]{0,5})]]")
    fun marker(number: Int) = "[[Image $number]]"
    fun markers(text: String) = pattern.findAll(text).map { it.value }.toList()
    fun numbers(text: String) = pattern.findAll(text).map { it.groupValues[1].toInt() }.toList()
    fun append(text: String, number: Int) = text.trimEnd() +
        (if (text.isBlank()) "" else "\n\n") + marker(number) + "\n\n"
    fun remove(text: String, number: Int) = text.replace(marker(number), "")
    fun preserved(source: String, output: String): Boolean = markers(source) == markers(output)
    const val INSTRUCTIONS = " Keep every [[Image N]] reference exactly once, unchanged, in the same order and between the same surrounding passages. Put it on a separate line. It refers to an attached image; do not describe or interpret an image."

    /**
     * Moves one image block by one neighbouring block while keeping all ordinary
     * text characters and the image metadata untouched. A missing legacy marker
     * is materialized at the end before it can be moved, so every saved image
     * remains reachable from the editor.
     */
    fun move(note: TranscriptNote, number: Int, direction: NoteImageMove): TranscriptNote {
        val known = note.images.map { it.number }.toSet()
        if (number !in known) return note
        val parts = editableParts(note)
        val index = parts.indexOfFirst { it is EditablePart.Image && it.number == number }
        if (index < 0) return note
        var target = index + direction.delta
        // Blank separator chunks belong to the neighbouring image blocks. Skip them so a
        // single Monter/Descendre action swaps two consecutive images instead of merely
        // consuming their visual blank line.
        while (target in parts.indices && parts[target] is EditablePart.Text &&
            (parts[target] as EditablePart.Text).value.isBlank()) {
            target += direction.delta
        }
        if (target !in parts.indices) return note
        parts[index] = parts[target].also { parts[target] = parts[index] }
        return note.copy(text = render(parts))
    }

    fun move(note: TranscriptNote, image: NoteImage, direction: NoteImageMove): TranscriptNote =
        move(note, image.number, direction)

    fun move(note: TranscriptNote, imageId: String, direction: NoteImageMove): TranscriptNote =
        note.images.firstOrNull { it.id == imageId }?.let { move(note, it.number, direction) } ?: note

    fun canMove(note: TranscriptNote, number: Int, direction: NoteImageMove): Boolean =
        move(note, number, direction).text != note.text

    fun readingOrder(note: TranscriptNote): List<Int> = parts(note)
        .filterIsInstance<Part.Image>()
        .map { it.image.number }

    private sealed class EditablePart {
        data class Text(val value: String) : EditablePart()
        data class Image(val number: Int, val lineSeparated: Boolean = false) : EditablePart()
    }

    private fun render(parts: List<EditablePart>): String = buildString {
        parts.forEachIndexed { index, part ->
            when (part) {
                is EditablePart.Text -> {
                    val previous = parts.getOrNull(index - 1)
                    if (previous is EditablePart.Image && previous.lineSeparated) {
                        ensureLeadingGap(this, part.value)
                    }
                    appendTextBlock(this, part.value)
                }
                is EditablePart.Image -> {
                    val previous = parts.getOrNull(index - 1)
                    if (part.lineSeparated && previous != null) ensureTrailingGap(this)
                    append(marker(part.number))
                    val next = parts.getOrNull(index + 1)
                    if (part.lineSeparated && (next is EditablePart.Image || next == null)) ensureTrailingGap(this)
                    if (part.lineSeparated && next is EditablePart.Text && !next.value.startsWith('\n')) ensureTrailingGap(this)
                }
            }
        }
    }

    private fun ensureTrailingGap(output: StringBuilder) {
        val newlineCount = output.takeLastWhile { it == '\n' }.count()
        repeat((2 - newlineCount).coerceAtLeast(0)) { output.append('\n') }
    }

    private fun ensureLeadingGap(output: StringBuilder, value: String) {
        val existing = output.takeLastWhile { it == '\n' }.count()
        val newlineCount = value.takeWhile { it == '\n' }.count()
        repeat((2 - existing - newlineCount).coerceAtLeast(0)) { output.append('\n') }
    }

    private fun appendTextBlock(output: StringBuilder, value: String) {
        if (value.isEmpty()) return
        val previous = output.lastOrNull()
        if (previous == '\n' && value.first() == '\n') {
            // A marker's two surrounding line breaks become adjacent when the
            // marker crosses this text block. Keep the normal blank-line gap.
            while (output.lastOrNull() == '\n') output.deleteCharAt(output.lastIndex)
            output.append("\n\n")
            output.append(value.dropWhile { it == '\n' })
        } else output.append(value)
    }

    /** Tokenization recognizes only the first occurrence of each known image. */
    private fun editableParts(note: TranscriptNote): MutableList<EditablePart> {
        val known = note.images.map { it.number }.toSet()
        val used = mutableSetOf<Int>()
        val result = mutableListOf<EditablePart>()
        var offset = 0
        pattern.findAll(note.text).forEach { match ->
            val number = match.groupValues[1].toInt()
            if (number !in known || !used.add(number)) return@forEach
            val before = note.text.substring(offset, match.range.first)
            if (before.isNotEmpty()) result += EditablePart.Text(before)
            val after = note.text.substring(match.range.last + 1)
            result += EditablePart.Image(number, lineSeparated = before.endsWith('\n') || after.startsWith('\n'))
            offset = match.range.last + 1
        }
        val tail = note.text.substring(offset)
        if (tail.isNotEmpty()) result += EditablePart.Text(tail)
        note.images.filter { it.number !in used }.forEach { image ->
            if (result.isNotEmpty() && result.last() !is EditablePart.Text) result += EditablePart.Text("\n\n")
            else if (result.isNotEmpty() && (result.last() as EditablePart.Text).value.isNotEmpty()) {
                result += EditablePart.Text("\n\n")
            }
            result += EditablePart.Image(image.number, lineSeparated = true)
            result += EditablePart.Text("\n\n")
        }
        return result
    }

    sealed class Part {
        data class Text(val text: String) : Part()
        data class Image(val image: NoteImage, val missingMarker: Boolean = false) : Part()
    }

    /** A deleted reference never silently discards a photograph: unanchored images go at the end. */
    fun parts(note: TranscriptNote): List<Part> = buildList {
        val byNumber = note.images.associateBy { it.number }
        val used = mutableSetOf<Int>()
        var offset = 0
        pattern.findAll(note.text).forEach { match ->
            val image = byNumber[match.groupValues[1].toInt()] ?: return@forEach
            if (!used.add(image.number)) return@forEach
            if (match.range.first > offset) add(Part.Text(note.text.substring(offset, match.range.first)))
            add(Part.Image(image))
            offset = match.range.last + 1
        }
        if (offset < note.text.length) add(Part.Text(note.text.substring(offset)))
        note.images.filter { it.number !in used }.forEach { add(Part.Image(it, missingMarker = true)) }
    }
}
