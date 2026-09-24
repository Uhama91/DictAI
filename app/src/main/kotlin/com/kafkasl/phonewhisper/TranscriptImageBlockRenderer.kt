package com.kafkasl.phonewhisper

import android.content.Context
import android.text.Spanned
import android.text.Editable
import android.widget.EditText
import java.util.LinkedHashMap

/** Installs one stable native span per image block and reads spans back after native edits. */
internal class TranscriptImageBlockRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val spans = LinkedHashMap<String, OverlayImageBlockSpan>()

    fun read(editor: EditText): TranscriptImageProjection {
        val editable = editor.text
        val raw = TranscriptImageBlocks.rawText(editable)
        val spanned = editable as? Spanned
            ?: return TranscriptImageBlocks.fromBlocks(raw, emptyList())
        val rawBlocks = spanned.getSpans(0, spanned.length, OverlayImageBlockSpan::class.java)
            .mapNotNull { span ->
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                if (start < 0 || end <= start) return@mapNotNull null
                val rawOffset = TranscriptImageBlocks.rawText(spanned.subSequence(0, start)).length
                Triple(start, rawOffset, span.block)
            }
            .sortedBy { it.first }
        val orderByOffset = mutableMapOf<Int, Int>()
        val blocks = rawBlocks.map { (_, offset, block) ->
            val order = orderByOffset.getOrDefault(offset, 0)
            orderByOffset[offset] = order + 1
            block.copy(rawOffset = offset, orderAtOffset = order)
        }
        return TranscriptImageBlocks.fromBlocks(raw, blocks)
    }

    fun render(
        editor: EditText,
        projection: TranscriptImageProjection,
        selectionStart: Int? = null,
        selectionEnd: Int? = selectionStart,
    ) {
        val editable = editor.text
        if (editable.toString() != projection.editorText) {
            val before = editable.toString()
            val after = projection.editorText
            val prefix = before.commonPrefixWith(after).length
            val suffix = before.drop(prefix).commonSuffixWith(after.drop(prefix)).length
            editable.replace(prefix, before.length - suffix, after.substring(prefix, after.length - suffix))
        }
        val text = editor.text as? Editable ?: return
        text.getSpans(0, text.length, OverlayImageBlockSpan::class.java).forEach(text::removeSpan)
        projection.blocks.forEach { block ->
            val sameOffsetOrder = projection.blocks
                .filter { it.rawOffset == block.rawOffset }
                .indexOfFirst { it.id == block.id }
                .coerceAtLeast(0)
            val start = projection.editorOffsetForCaret(
                TranscriptImageCaret(block.rawOffset, sameOffsetOrder),
            )
            if (start in 0 until text.length && text[start] == TranscriptImageBlocks.OBJECT_REPLACEMENT) {
                val span = spans[block.id]?.takeIf {
                    it.block.images == block.images && it.block.markerSeparators == block.markerSeparators
                } ?: OverlayImageBlockSpan(appContext, block).also { spans[block.id] = it }
                text.setSpan(span, start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val activeIds = projection.blocks.map { it.id }.toSet()
        spans.keys.retainAll(activeIds)
        if (selectionStart != null && selectionEnd != null) {
            val start = selectionStart.coerceIn(0, editor.length())
            val end = selectionEnd.coerceIn(0, editor.length())
            runCatching { editor.setSelection(start, end) }
        }
    }

    fun blockAtEditorOffset(editor: EditText, offset: Int): TranscriptImageBlock? {
        val spanned = editor.text as? Spanned ?: return null
        val target = offset.coerceIn(0, (spanned.length - 1).coerceAtLeast(0))
        return spanned.getSpans(target, target + 1, OverlayImageBlockSpan::class.java)
            .firstOrNull { spanned.getSpanStart(it) == target }
            ?.block
    }

    fun blockRange(editor: EditText, blockId: String): Pair<Int, Int>? {
        val spanned = editor.text as? Spanned ?: return null
        val span = spanned.getSpans(0, spanned.length, OverlayImageBlockSpan::class.java)
            .firstOrNull { it.block.id == blockId } ?: return null
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        return if (start >= 0 && end > start) start to end else null
    }
}
