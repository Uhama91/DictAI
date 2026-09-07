package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class TranscriptNotesTest {
    private class MemoryStorage : TranscriptNoteStorage {
        val values = linkedMapOf<String, TranscriptNote>()
        override fun all() = values.values.toList()
        override fun put(note: TranscriptNote) { values[note.id] = note }
        override fun remove(id: String) { values.remove(id) }
    }
    @Test fun `new manual note can be reopened and keeps exact text`() {
        val storage = MemoryStorage()
        val notes = TranscriptNotes(storage, { 1L }, { "a" })
        val created = notes.save(null, "")
        assertEquals("Nouvelle note", created.title)
        notes.save(created.id, "Mon idée.\n\nArgument corrigé.")
        assertEquals("Mon idée.\n\nArgument corrigé.", TranscriptNotes(storage).get(created.id)?.text)
        assertEquals(1, notes.all().size)
    }
    @Test fun `manual rename survives dictation updates`() {
        val notes = TranscriptNotes(MemoryStorage(), { 1L }, { "a" })
        notes.save(null, "première idée")
        notes.rename("a", "Mon projet")
        notes.save("a", "Autre première ligne et nouvelle suite")
        assertEquals("Mon projet", notes.get("a")?.title)
    }
    @Test fun `recent notes sort first and deleting one preserves others`() {
        var tick = 0L
        var id = 0
        val notes = TranscriptNotes(MemoryStorage(), { ++tick }, { (++id).toString() })
        notes.save(null, "A")
        notes.save(null, "B")
        notes.save("1", "A modifiée")
        assertEquals(listOf("1", "2"), notes.all().map { it.id })
        notes.delete("1")
        assertEquals("B", notes.all().single().text)
    }
    @Test fun `local titles ignore empty lines and cap long prose`() {
        assertEquals("Une idée précise", TranscriptNotes.titleFrom("\n euh, une idée précise\nSuite"))
        assertTrue(TranscriptNotes.titleFrom("argument ".repeat(200)).length <= 60)
    }
}
