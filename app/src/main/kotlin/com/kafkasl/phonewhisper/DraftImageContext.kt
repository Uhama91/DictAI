package com.kafkasl.phonewhisper

/** Private draft anchors: ordinary dictated text never contains image markers. */
internal data class DraftImageCapture(val id: String, val offset: Int, val image: NoteImage? = null)

internal object DraftImageContext {
    fun move(captures: List<DraftImageCapture>, before: String, after: String): List<DraftImageCapture> {
        if (before == after) return captures
        val prefix = before.commonPrefixWith(after).length
        val suffix = before.drop(prefix).commonSuffixWith(after.drop(prefix)).length
        val oldEnd = before.length - suffix
        val newEnd = after.length - suffix
        return captures.map { capture ->
            val position = when {
                capture.offset <= prefix -> capture.offset
                capture.offset >= oldEnd -> capture.offset + after.length - before.length
                else -> newEnd
            }
            capture.copy(offset = position.coerceIn(0, after.length))
        }
    }

    data class NoteContent(val text: String, val images: List<NoteImage>, val attachedIds: Set<String>)
    fun materialize(text: String, existing: List<NoteImage>, captures: List<DraftImageCapture>): NoteContent {
        val selected = captures.filter { it.image != null && existing.none { old -> old.id == it.id } }
            .take((NoteImage.MAX_IMAGES - existing.size).coerceAtLeast(0)).sortedBy { it.offset }
        var number = NoteImage.nextNumber(existing, text)
        var cursor = 0
        val images = mutableListOf<NoteImage>()
        val result = buildString {
            selected.forEach { capture ->
                val offset = capture.offset.coerceIn(cursor, text.length)
                append(text.substring(cursor, offset))
                val image = capture.image!!.copy(number = number++)
                images += image
                append("\n\n${image.marker}\n\n")
                cursor = offset
            }
            append(text.substring(cursor))
        }
        return NoteContent(result, existing + images, selected.map { it.id }.toSet())
    }
}
