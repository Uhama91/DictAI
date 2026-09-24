package com.kafkasl.phonewhisper

/** One atomic editor object may serialize to several adjacent image markers. */
internal data class TranscriptImageBlock(
    val id: String,
    val images: List<NoteImage>,
    val rawOffset: Int,
    val markerSeparators: List<String> = emptyList(),
    val orderAtOffset: Int = 0,
)

/** A caret has an affinity between adjacent image objects that share one raw-text offset. */
internal data class TranscriptImageCaret(val rawOffset: Int, val orderAtOffset: Int)

/** Text used by EditText plus image blocks anchored in offsets with object characters removed. */
internal data class TranscriptImageProjection(
    val editorText: String,
    val blocks: List<TranscriptImageBlock>,
) {
    fun rawText(): String = TranscriptImageBlocks.rawText(editorText)
    fun serializedNoteText(): String = TranscriptImageBlocks.serialize(this)
    fun editorOffsetForRaw(rawOffset: Int): Int = TranscriptImageBlocks.editorOffsetForRaw(this, rawOffset)
    fun rawOffsetForEditor(editorOffset: Int): Int = TranscriptImageBlocks.rawOffsetForEditor(this, editorOffset)
    fun caretForEditor(editorOffset: Int): TranscriptImageCaret = TranscriptImageBlocks.caretForEditor(this, editorOffset)
    fun editorOffsetForCaret(caret: TranscriptImageCaret): Int = TranscriptImageBlocks.editorOffsetForCaret(this, caret)
}

/**
 * Maps the private note-marker format to one-character editor objects. Marker text is kept
 * solely as note serialization; speech, LLM input, and message insertion consume rawText().
 */
internal object TranscriptImageBlocks {
    const val OBJECT_REPLACEMENT: Char = '\uFFFC'
    private val markerPattern = Regex("\\[\\[Image ([1-9][0-9]{0,5})]]")

    fun fromNote(text: String, images: List<NoteImage>): TranscriptImageProjection {
        if (images.isEmpty()) return fromBlocks(text, emptyList())
        val byNumber = images.associateBy { it.number }
        val matches = markerPattern.findAll(text).toList()
        val used = mutableSetOf<Int>()
        val blocks = mutableListOf<TranscriptImageBlock>()
        val raw = StringBuilder()
        var cursor = 0
        var index = 0
        while (index < matches.size) {
            val first = matches[index]
            val firstImage = byNumber[first.groupValues[1].toInt()]
            if (firstImage == null || firstImage.number in used) {
                index++
                continue
            }

            raw.append(text, cursor, first.range.first)
            val blockOffset = raw.length
            val grouped = mutableListOf(firstImage)
            used += firstImage.number
            var last = first
            val separators = mutableListOf<String>()
            var nextIndex = index + 1
            while (nextIndex < matches.size) {
                val next = matches[nextIndex]
                val gap = text.substring(last.range.last + 1, next.range.first)
                val nextImage = byNumber[next.groupValues[1].toInt()]
                if (gap.any { !it.isWhitespace() } || nextImage == null || nextImage.number in used) break
                separators += gap
                grouped += nextImage
                used += nextImage.number
                last = next
                nextIndex++
            }
            blocks += TranscriptImageBlock(
                id = "note:${grouped.joinToString(":") { it.id }}",
                images = grouped,
                rawOffset = blockOffset,
                markerSeparators = separators,
                orderAtOffset = blocks.count { it.rawOffset == blockOffset },
            )
            cursor = last.range.last + 1
            index = nextIndex
        }
        raw.append(text, cursor, text.length)

        // Old notes could have attachment metadata without a marker. Keep those images reachable.
        images.filter { it.number !in used }.forEach { image ->
            if (raw.isNotEmpty() && !raw.endsWith("\n\n")) raw.append("\n\n")
            blocks += TranscriptImageBlock("note:${image.id}", listOf(image), raw.length)
            raw.append("\n\n")
        }
        return fromBlocks(raw.toString(), blocks)
    }

    fun fromDraft(text: String, captures: List<DraftImageCapture>): TranscriptImageProjection {
        val groups = linkedMapOf<String, MutableList<DraftImageCapture>>()
        captures.asSequence()
            .filter { it.visible && it.image != null }
            .distinctBy { it.id }
            .forEach { capture -> groups.getOrPut(capture.groupId ?: capture.id) { mutableListOf() }.add(capture) }
        val blocks = groups.map { (id, group) ->
            TranscriptImageBlock(
                id = id,
                images = group.mapNotNull { it.image },
                rawOffset = group.first().offset.coerceIn(0, text.length),
                orderAtOffset = group.first().orderAtOffset,
            )
        }
        return fromBlocks(text, blocks)
    }

    /** Migrates drafts written by older versions that stored a note's marker text as draft text. */
    fun fromLegacyNoteDraft(text: String, images: List<NoteImage>): TranscriptImageProjection? {
        val knownNumbers = images.map { it.number }.toSet()
        if (NoteImageMarkers.numbers(text).none { it in knownNumbers }) return null
        return fromNote(text, images)
    }

    fun combine(base: TranscriptImageProjection, captures: List<DraftImageCapture>): TranscriptImageProjection {
        val draft = fromDraft(base.rawText(), captures)
        val knownIds = base.blocks.flatMap { it.images }.map { it.id }.toSet()
        val additions = draft.blocks.mapNotNull { block ->
            val unique = block.images.filter { it.id !in knownIds }
            block.takeIf { unique.isNotEmpty() }?.copy(images = unique)
        }
        return fromBlocks(base.rawText(), base.blocks + additions)
    }

    fun fromBlocks(rawText: String, blocks: List<TranscriptImageBlock>): TranscriptImageProjection {
        // U+FFFC is reserved for blocks. If a paste or an older draft left an unspanned
        // replacement character in ordinary text, discard it and remap all following anchors.
        val cleanText = rawText.filter { it != OBJECT_REPLACEMENT }
        val ordered = blocks.asSequence()
            .filter { it.images.isNotEmpty() }
            .distinctBy { it.id }
            .map {
                val inputOffset = it.rawOffset.coerceIn(0, rawText.length)
                val cleanOffset = rawText.take(inputOffset).count { char -> char != OBJECT_REPLACEMENT }
                it.copy(rawOffset = cleanOffset.coerceIn(0, cleanText.length))
            }
            .sortedWith(compareBy<TranscriptImageBlock> { it.rawOffset }.thenBy { it.orderAtOffset })
            .toList()
        val text = StringBuilder(cleanText.length + ordered.size)
        var rawCursor = 0
        ordered.forEach { block ->
            text.append(cleanText, rawCursor, block.rawOffset)
            text.append(OBJECT_REPLACEMENT)
            rawCursor = block.rawOffset
        }
        text.append(cleanText, rawCursor, cleanText.length)
        return TranscriptImageProjection(text.toString(), ordered)
    }

    fun move(projection: TranscriptImageProjection, blockId: String, rawOffset: Int): TranscriptImageProjection {
        val moving = projection.blocks.firstOrNull { it.id == blockId } ?: return projection
        val offset = rawOffset.coerceIn(0, projection.rawText().length)
        val remaining = fromBlocks(projection.rawText(), projection.blocks.filterNot { it.id == blockId })
        return insertAtCaret(remaining, moving, TranscriptImageCaret(offset,
            remaining.blocks.count { it.rawOffset == offset }))
    }

    /** Reorders an object to the exact editor caret, including the gap between adjacent blocks. */
    fun moveToCaret(
        projection: TranscriptImageProjection,
        blockId: String,
        caret: TranscriptImageCaret,
    ): TranscriptImageProjection {
        val moving = projection.blocks.firstOrNull { it.id == blockId } ?: return projection
        val targetEditorOffset = projection.editorOffsetForCaret(caret)
        val movingEditorOffset = blockEditorOffset(projection, moving)
        val adjustedEditorOffset = targetEditorOffset - if (movingEditorOffset < targetEditorOffset) 1 else 0
        val remaining = fromBlocks(projection.rawText(), projection.blocks.filterNot { it.id == blockId })
        val remainingCaret = remaining.caretForEditor(adjustedEditorOffset)
        return insertAtCaret(remaining, moving, remainingCaret)
    }

    private fun insertAtCaret(
        projection: TranscriptImageProjection,
        moving: TranscriptImageBlock,
        caret: TranscriptImageCaret,
    ): TranscriptImageProjection {
        val offset = caret.rawOffset.coerceIn(0, projection.rawText().length)
        val atOffset = projection.blocks.filter { it.rawOffset == offset }
        val order = caret.orderAtOffset.coerceIn(0, atOffset.size)
        val before = atOffset.take(order)
        val after = atOffset.drop(order)
        val ordered = buildList {
            projection.blocks.filter { it.rawOffset < offset }.forEach(::add)
            before.forEachIndexed { index, block -> add(block.copy(orderAtOffset = index)) }
            add(moving.copy(rawOffset = offset, orderAtOffset = order))
            after.forEachIndexed { index, block -> add(block.copy(orderAtOffset = order + index + 1)) }
            projection.blocks.filter { it.rawOffset > offset }.forEach(::add)
        }
        return fromBlocks(projection.rawText(), ordered)
    }

    fun remove(projection: TranscriptImageProjection, blockId: String): TranscriptImageProjection =
        fromBlocks(projection.rawText(), projection.blocks.filterNot { it.id == blockId })

    /** Remove one image from a grouped block while keeping the remaining block id and order. */
    fun removeImage(
        projection: TranscriptImageProjection,
        blockId: String,
        imageId: String,
    ): TranscriptImageProjection {
        val block = projection.blocks.firstOrNull { it.id == blockId } ?: return projection
        val index = block.images.indexOfFirst { it.id == imageId }
        if (index < 0) return projection
        if (block.images.size == 1) return remove(projection, blockId)

        val gaps = (0 until block.images.lastIndex).map { block.markerSeparators.getOrNull(it) ?: "\n\n" }
        val remainingGaps = when (index) {
            0 -> gaps.drop(1)
            block.images.lastIndex -> gaps.dropLast(1)
            else -> gaps.take(index - 1) + (gaps[index - 1] + gaps[index]) + gaps.drop(index + 1)
        }
        val replacement = block.copy(
            images = block.images.filterIndexed { itemIndex, _ -> itemIndex != index },
            markerSeparators = remainingGaps,
        )
        return fromBlocks(projection.rawText(), projection.blocks.map { if (it.id == blockId) replacement else it })
    }

    /** Drops both linked and unlinked U+FFFC characters so an IME can never inject them. */
    fun rawText(editorText: CharSequence): String = buildString(editorText.length) {
        editorText.forEach { if (it != OBJECT_REPLACEMENT) append(it) }
    }

    fun serialize(projection: TranscriptImageProjection): String {
        val blocksByEditorOffset = mutableMapOf<Int, TranscriptImageBlock>()
        projection.blocks.forEachIndexed { index, block ->
            val previousAtOrBefore = projection.blocks.take(index).count { it.rawOffset <= block.rawOffset }
            blocksByEditorOffset[block.rawOffset + previousAtOrBefore] = block
        }
        return buildString(projection.editorText.length + projection.blocks.size * 18) {
            projection.editorText.forEachIndexed { index, char ->
                if (char != OBJECT_REPLACEMENT) append(char)
                else blocksByEditorOffset[index]?.let { block -> append(serializeBlock(block)) }
            }
        }
    }

    fun editorOffsetForRaw(projection: TranscriptImageProjection, rawOffset: Int): Int {
        val clamped = rawOffset.coerceIn(0, projection.rawText().length)
        return clamped + projection.blocks.count { it.rawOffset <= clamped }
    }

    fun caretForEditor(projection: TranscriptImageProjection, editorOffset: Int): TranscriptImageCaret {
        val clamped = editorOffset.coerceIn(0, projection.editorText.length)
        val rawOffset = rawOffsetForEditor(projection, clamped)
        val order = projection.blocks.filter { it.rawOffset == rawOffset }
            .count { block -> blockEditorOffset(projection, block) < clamped }
        return TranscriptImageCaret(rawOffset, order)
    }

    fun editorOffsetForCaret(projection: TranscriptImageProjection, caret: TranscriptImageCaret): Int {
        val rawOffset = caret.rawOffset.coerceIn(0, projection.rawText().length)
        val preceding = projection.blocks.count { it.rawOffset < rawOffset }
        val sameOffset = projection.blocks.count { it.rawOffset == rawOffset }
        return rawOffset + preceding + caret.orderAtOffset.coerceIn(0, sameOffset)
    }

    fun rawOffsetForEditor(projection: TranscriptImageProjection, editorOffset: Int): Int {
        val clamped = editorOffset.coerceIn(0, projection.editorText.length)
        val removedBefore = projection.editorText.take(clamped).count { it == OBJECT_REPLACEMENT }
        return (clamped - removedBefore).coerceIn(0, projection.rawText().length)
    }

    private fun blockEditorOffset(projection: TranscriptImageProjection, block: TranscriptImageBlock): Int {
        val index = projection.blocks.indexOf(block)
        val preceding = projection.blocks.take(index).count { it.rawOffset <= block.rawOffset }
        return block.rawOffset + preceding
    }

    private fun serializeBlock(block: TranscriptImageBlock): String = buildString {
        block.images.forEachIndexed { index, image ->
            if (index > 0) append(block.markerSeparators.getOrNull(index - 1) ?: "\n\n")
            append(image.marker)
        }
    }
}
