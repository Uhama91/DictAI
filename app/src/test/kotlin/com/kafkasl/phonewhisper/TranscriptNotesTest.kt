package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingParticipant
import com.kafkasl.phonewhisper.meeting.MeetingTurn
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptNotesTest {
    private class MemoryStorage : TranscriptNoteStorage {
        val values = linkedMapOf<String, TranscriptNote>()
        override fun all() = values.values.toList()
        override fun put(note: TranscriptNote) { values[note.id] = note }
        override fun remove(id: String) { values.remove(id) }
    }

    private class FailingStorage : TranscriptNoteStorage {
        val values = linkedMapOf<String, TranscriptNote>()
        var failMeetingWrites = false
        var failNoteId: String? = null
        override fun all() = values.values.toList()
        override fun put(note: TranscriptNote) {
            if (note.id == failNoteId) throw IOException("note write failed")
            if (failMeetingWrites && (note.meeting != null || note.meetingRaw != null)) {
                throw IOException("storage commit failed")
            }
            values[note.id] = note
        }
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

    @Test fun `meeting note uses session id and title from visible speech rather than label or image markers`() {
        val notes = TranscriptNotes(MemoryStorage(), { 10L }, { "random-id" })
        val speaker = MeetingParticipant("p-sophie", ordinal = 1, channel = 1, name = "Sophie")
        val ignored = MeetingParticipant("p-ignored", ordinal = 2, channel = 2, name = "Karim", ignored = true)
        val document = MeetingDocument(
            sessionId = "session-a",
            runId = "run-a",
            participants = listOf(speaker, ignored),
            turns = listOf(
                MeetingTurn("image-only-hidden", 1, 0, 1, "secret [[Image 2]]", ignored.id, attributionStable = true),
                MeetingTurn("image-only", 2, 1, 2, "[[Image 1]]", speaker.id, attributionStable = true),
                MeetingTurn("speech", 3, 2, 3, "Une réunion porte sur le budget.", speaker.id, attributionStable = true),
            ),
        )
        val image1 = image(1)
        val image2 = image(2)

        val saved = notes.saveMeeting(null, document, listOf(image1, image2))

        assertEquals("session-a", saved.id)
        assertEquals("Une réunion porte sur le budget.", saved.title)
        assertEquals("[[Image 2]]\n\nSophie\n[[Image 1]]\n\nSophie\nUne réunion porte sur le budget.", saved.text)
        assertEquals(document, saved.meeting)
        assertNull(saved.meetingRaw)
    }

    @Test fun `meeting updates preserve manual title folder and images while flat edits are refused`() {
        val storage = MemoryStorage()
        val notes = TranscriptNotes(storage, { 10L }, { "ignored-id" })
        val speaker = MeetingParticipant("p-sophie", ordinal = 1, channel = 1, name = "Sophie")
        val first = MeetingDocument(
            sessionId = "session-a", runId = "run-a", participants = listOf(speaker),
            turns = listOf(MeetingTurn("turn-1", 1, 0, 1, "Première décision.", speaker.id, attributionStable = true)),
        )
        val attached = image(3)
        val created = notes.saveMeeting(null, first, listOf(attached))
        val folder = requireNotNull(notes.createFolder("Réunion"))
        notes.move(created.id, folder.id)
        notes.rename(created.id, "Titre conservé")
        val revised = first.copy(runId = "run-b", turns = listOf(first.turns.single().copy(recognizedText = "Décision mise à jour.")))

        val updated = notes.saveMeeting(created.id, revised)

        assertEquals("session-a", updated.id)
        assertEquals("Titre conservé", updated.title)
        assertEquals(folder.id, updated.folderId)
        assertEquals(listOf(attached), updated.images)
        assertEquals(revised, updated.meeting)
        assertEquals("Sophie\nDécision mise à jour.", updated.text)
        assertThrows(IllegalArgumentException::class.java) { notes.save(updated.id, "Texte plat") }
        assertEquals(revised, notes.get(updated.id)?.meeting)
    }

    @Test fun `session collision opaque payload and failed commit never overwrite the cached note`() {
        val collisionStorage = MemoryStorage()
        val collisionNotes = TranscriptNotes(collisionStorage, { 1L }, { "session-a" })
        val plain = collisionNotes.save(null, "Ancienne note")
        val document = simpleMeeting()
        assertThrows(IllegalArgumentException::class.java) { collisionNotes.saveMeeting(null, document) }
        assertEquals(plain, collisionNotes.get("session-a"))

        val opaqueStorage = MemoryStorage()
        val opaque = TranscriptNote("session-a", "Repli", "Repli préservé", 2L, meetingRaw = " { \"schemaVersion\" : 9 } ")
        opaqueStorage.values[opaque.id] = opaque
        val opaqueNotes = TranscriptNotes(opaqueStorage)
        assertThrows(IllegalArgumentException::class.java) { opaqueNotes.save("session-a", "Écrasement plat") }
        assertThrows(IllegalArgumentException::class.java) { opaqueNotes.saveMeeting("session-a", document) }
        assertEquals(opaque, opaqueNotes.get("session-a"))

        val failingStorage = FailingStorage()
        val failingNotes = TranscriptNotes(failingStorage, { 3L }, { "unused" })
        val saved = failingNotes.saveMeeting(null, document)
        failingStorage.failMeetingWrites = true
        val update = document.copy(runId = "run-c", turns = listOf(document.turns.single().copy(recognizedText = "Nouvelle parole.")))
        assertThrows(IOException::class.java) { failingNotes.saveMeeting(saved.id, update) }
        assertEquals(saved, failingNotes.get(saved.id))
        assertEquals(saved, failingStorage.values[saved.id])
    }

    @Test fun `folder stays available when note migration fails and can be retried`() {
        val storage = FailingStorage()
        var nextId = 0
        val notes = TranscriptNotes(storage, { 5L }, { (++nextId).toString() })
        val first = notes.save(null, "Première note")
        val second = notes.save(null, "Deuxième note")
        val folder = requireNotNull(notes.createFolder("Réunions"))
        notes.move(first.id, folder.id)
        notes.move(second.id, folder.id)
        storage.failNoteId = second.id

        assertThrows(IOException::class.java) { notes.deleteFolder(folder.id) }

        assertEquals(folder, notes.getFolder(folder.id))
        assertNull(notes.get(first.id)?.folderId)
        assertEquals(folder.id, notes.get(second.id)?.folderId)
        assertEquals(listOf(second.id), notes.all(folder.id).map { it.id })

        storage.failNoteId = null
        assertTrue(notes.deleteFolder(folder.id))
        assertNull(notes.getFolder(folder.id))
        assertNull(notes.get(first.id)?.folderId)
        assertNull(notes.get(second.id)?.folderId)
    }

    private fun simpleMeeting(): MeetingDocument {
        val speaker = MeetingParticipant("p-one", ordinal = 1, channel = 1, name = "Sophie")
        return MeetingDocument(
            sessionId = "session-a", runId = "run-a", participants = listOf(speaker),
            turns = listOf(MeetingTurn("turn-1", 1, 0, 1, "Bonjour.", speaker.id, attributionStable = true)),
        )
    }

    private fun image(number: Int) = NoteImage("00000000-0000-0000-0000-%012d".format(number), number, NoteImageKind.CAMERA, 0L, 12, 12)
}
