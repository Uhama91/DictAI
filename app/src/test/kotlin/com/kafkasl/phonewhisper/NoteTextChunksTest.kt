package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class NoteTextChunksTest {
    @Test fun `long note keeps every character and ordinary word across bounded PDF layouts`() {
        val text = ("écran 🧭 observation très détaillée.\n\n".repeat(10000))
        val chunks = NoteTextChunks.split(text).toList()
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 8000 })
        assertTrue(chunks.dropLast(1).all { it.last().isWhitespace() })
    }
    @Test fun `oversized unbroken unicode token never creates a dangling surrogate`() {
        val text = "x" + "🧭".repeat(10000)
        val chunks = NoteTextChunks.split(text, 100).toList()
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }
}
