package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptNoteFoldersTest {
    private class MemoryStorage : TranscriptNoteStorage {
        val notes = linkedMapOf<String, TranscriptNote>()
        val folders = linkedMapOf<String, NoteFolder>()

        override fun all() = notes.values.toList()
        override fun put(note: TranscriptNote) { notes[note.id] = note }
        override fun remove(id: String) { notes.remove(id) }
        override fun allFolders() = folders.values.toList()
        override fun putFolder(folder: NoteFolder) { folders[folder.id] = folder }
        override fun removeFolder(id: String) { folders.remove(id) }
    }

    @Test fun `new notes stay folderless and old notes remain readable`() {
        val storage = MemoryStorage()
        val notes = TranscriptNotes(storage, now = { 1L }, newId = { "note-1" })

        val note = notes.save(null, "Une note ancienne")

        assertNull(note.folderId)
        assertFalse(note.folderChoicePrompted)
        assertEquals(note, TranscriptNotes(storage).get(note.id))
    }

    @Test fun `empty folders can be renamed and deleting one safely unfiles its notes`() {
        val storage = MemoryStorage()
        var tick = 0L
        val notes = TranscriptNotes(storage, now = { ++tick }, newId = { "note-1" })

        val folder = notes.createFolder("  Projet  ")!!
        assertTrue(notes.folders().contains(folder))
        notes.renameFolder(folder.id, "Travail")
        assertEquals("Travail", notes.getFolder(folder.id)!!.name)

        val note = notes.save(null, "Compte rendu")
        notes.move(note.id, folder.id)
        notes.deleteFolder(folder.id)

        assertTrue(notes.folders().isEmpty())
        assertNull(notes.get(note.id)!!.folderId)
        assertEquals("Compte rendu", notes.get(note.id)!!.text)
    }

    @Test fun `notes move into an existing or newly created folder and back to no folder`() {
        val storage = MemoryStorage()
        val notes = TranscriptNotes(storage, now = { 1L }, newId = { "note-1" })
        val note = notes.save(null, "À classer")
        val folder = notes.createFolder("Projet")!!

        assertEquals(folder.id, notes.move(note.id, folder.id)!!.folderId)
        assertEquals(folder.id, notes.get(note.id)!!.folderId)
        assertNull(notes.move(note.id, null)!!.folderId)

        val created = notes.createFolderAndMove(note.id, "Urgent")!!
        assertEquals(created.id, notes.get(note.id)!!.folderId)
        assertEquals(listOf(note.id), notes.all(created.id).map { it.id })
    }

    @Test fun `initial folder choice is consumed once and does not repeat on later saves`() {
        val storage = MemoryStorage()
        val notes = TranscriptNotes(storage, now = { 1L }, newId = { "note-1" })
        val folder = notes.createFolder("Projet")!!
        val note = notes.save(null, "Première version")

        assertTrue(notes.needsInitialFolderChoice(note.id))
        val chosen = notes.chooseFolder(note.id, folder.id)!!
        assertEquals(folder.id, chosen.folderId)
        assertTrue(chosen.folderChoicePrompted)
        assertFalse(notes.needsInitialFolderChoice(note.id))
        assertEquals(folder.id, notes.save(note.id, "Deuxième version").folderId)
    }
}
