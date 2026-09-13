package com.kafkasl.phonewhisper

/** Maps an editor selection across a replacement made by a later ASR revision. */
internal object TranscriptSelectionMapping {
    data class Selection(val start: Int, val end: Int)

    fun afterReplacement(
        selectionStart: Int,
        selectionEnd: Int,
        oldStart: Int,
        oldEnd: Int,
        newEnd: Int,
        newLength: Int,
    ): Selection? {
        if (selectionStart < 0 || selectionEnd < 0) return null
        fun map(offset: Int): Int = when {
            offset <= oldStart -> offset
            offset >= oldEnd -> offset + (newEnd - oldEnd)
            else -> newEnd
        }.coerceIn(0, newLength)
        return Selection(map(selectionStart), map(selectionEnd))
    }
}
