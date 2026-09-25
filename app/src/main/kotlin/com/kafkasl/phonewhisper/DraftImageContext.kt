package com.kafkasl.phonewhisper

/** Private draft anchors: ordinary dictated text never contains image markers. */
internal data class DraftImageCapture(
    val id: String,
    val offset: Int,
    val image: NoteImage? = null,
    val groupId: String? = null,
    val visible: Boolean = true,
    val orderAtOffset: Int = 0,
)

internal object DraftImageContext {
    /** Make persisted draft anchors match the native image blocks after edits, moves and deletes. */
    fun synchronize(captures: List<DraftImageCapture>, blocks: List<TranscriptImageBlock>): List<DraftImageCapture> {
        val byImageId = blocks.flatMap { block -> block.images.map { image -> image.id to block } }.toMap()
        return captures.mapNotNull { capture ->
            if (capture.image == null) return@mapNotNull capture // retain an in-flight reservation
            val block = byImageId[capture.id] ?: return@mapNotNull null
            capture.copy(
                offset = block.rawOffset,
                groupId = block.id,
                orderAtOffset = block.orderAtOffset,
                visible = true,
            )
        }
    }

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
        val selected = captures.filter { it.visible && it.image != null && existing.none { old -> old.id == it.id } }
            .distinctBy { it.id }
            .take((NoteImage.MAX_IMAGES - existing.size).coerceAtLeast(0)).sortedBy { it.offset }
        var number = NoteImage.nextNumber(existing, text)
        var cursor = 0
        val images = mutableListOf<NoteImage>()
        val result = buildString {
            val groups = linkedMapOf<String, MutableList<DraftImageCapture>>()
            selected.forEach { capture -> groups.getOrPut(capture.groupId ?: capture.id) { mutableListOf() }.add(capture) }
            val orderedGroups = groups.values.sortedWith(compareBy<MutableList<DraftImageCapture>> { it.first().offset }
                .thenBy { it.first().orderAtOffset })
            orderedGroups.forEach { group ->
                val offset = group.first().offset.coerceIn(cursor, text.length)
                append(text.substring(cursor, offset))
                val numbered = group.mapNotNull { it.image }.map { image -> image.copy(number = number++) }
                images += numbered
                append("\n\n${numbered.joinToString("\n\n") { it.marker }}\n\n")
                cursor = offset
            }
            append(text.substring(cursor))
        }
        return NoteContent(result, existing + images, selected.map { it.id }.toSet())
    }
}
