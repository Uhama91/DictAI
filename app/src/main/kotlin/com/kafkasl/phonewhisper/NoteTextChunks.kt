package com.kafkasl.phonewhisper

internal object NoteTextChunks {
    /** Bound PDF layout allocation without splitting ordinary words or surrogate pairs. */
    fun split(text: String, maximum: Int = 8000): Sequence<String> = sequence {
        require(maximum >= 2)
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + maximum, text.length)
            if (end < text.length) {
                val space = (end - 1 downTo offset + maximum / 2).firstOrNull { text[it].isWhitespace() }
                if (space != null) end = space + 1
                else if (text[end - 1].isHighSurrogate()) end--
            }
            yield(text.substring(offset, end))
            offset = end
        }
    }
}
