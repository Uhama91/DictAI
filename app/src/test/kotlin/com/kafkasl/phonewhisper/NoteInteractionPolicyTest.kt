package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class NoteInteractionPolicyTest {
    @Test fun `ordinary message retains direct stop and publication`() {
        assertEquals(DictationTapGestureCoordinator.Action.STOP_RECORDING,
            NoteInteractionPolicy.tap(DictationPurpose.MESSAGE, DictationTapGestureCoordinator.Action.STOP_RECORDING))
        assertEquals(NoteInteractionPolicy.Destination.MESSAGE, NoteInteractionPolicy.destination(DictationPurpose.MESSAGE, false, false))
    }

    @Test fun `a note tap pauses and double tap saves instead of discarding`() {
        assertEquals(DictationTapGestureCoordinator.Action.PAUSE_RECORDING,
            NoteInteractionPolicy.tap(DictationPurpose.NOTE, DictationTapGestureCoordinator.Action.STOP_RECORDING))
        for (action in listOf(DictationTapGestureCoordinator.Action.CANCEL_RECORDING,
            DictationTapGestureCoordinator.Action.CANCEL_RECORDING_AND_OPEN_APP)) {
            assertEquals(DictationTapGestureCoordinator.Action.SAVE_AND_CLOSE_NOTE, NoteInteractionPolicy.tap(DictationPurpose.NOTE, action))
        }
    }

    @Test fun `deferred note completion cannot select the message destination`() {
        for (archive in listOf(false, true)) for (export in listOf(false, true)) {
            assertNotEquals(NoteInteractionPolicy.Destination.MESSAGE, NoteInteractionPolicy.destination(DictationPurpose.NOTE, archive, export))
        }
        assertEquals(NoteInteractionPolicy.Destination.NOTE, NoteInteractionPolicy.destination(DictationPurpose.NOTE, false, false))
        assertEquals(NoteInteractionPolicy.Destination.NOTE_LIST, NoteInteractionPolicy.destination(DictationPurpose.MESSAGE, true, false))
        assertEquals(NoteInteractionPolicy.Destination.NOTE_EXPORT, NoteInteractionPolicy.destination(DictationPurpose.MESSAGE, false, true))
    }

    @Test fun `taps during note finalization neither publish nor cancel its saved result`() {
        for (action in listOf(DictationTapGestureCoordinator.Action.ARM_PROCESSING_WINDOW,
            DictationTapGestureCoordinator.Action.CANCEL_PROCESSING))
            assertEquals(DictationTapGestureCoordinator.Action.NONE, NoteInteractionPolicy.tap(DictationPurpose.NOTE, action))
    }

    @Test fun `a real single tap sequence pauses a note and resumes it without insertion`() {
        val taps = DictationTapGestureCoordinator(280)
        val pause = taps.onTap(DictationTapGestureCoordinator.SurfaceState.RECORDING, 1000)
        assertEquals(DictationTapGestureCoordinator.Action.PAUSE_RECORDING,
            NoteInteractionPolicy.tap(DictationPurpose.NOTE, taps.onTimeout(pause.timeout!!, 1280).action))
        taps.reset()
        val resume = taps.onTap(DictationTapGestureCoordinator.SurfaceState.PAUSED, 2000)
        assertEquals(DictationTapGestureCoordinator.Action.RESUME_RECORDING,
            NoteInteractionPolicy.tap(DictationPurpose.NOTE, taps.onTimeout(resume.timeout!!, 2280).action))
        assertEquals(NoteInteractionPolicy.Destination.NOTE,
            NoteInteractionPolicy.destination(DictationPurpose.NOTE, archive = false, export = false))
    }

    @Test fun `restored note stays protected even when its stored note has disappeared`() {
        assertEquals(DictationPurpose.NOTE, DictationPurpose.restore("NOTE", null))
        assertEquals(DictationPurpose.NOTE, DictationPurpose.restore(null, "old-note-id"))
        assertEquals(DictationPurpose.NOTE, DictationPurpose.restore("unknown-new-purpose", null))
        assertEquals(DictationPurpose.MESSAGE, DictationPurpose.restore(null, null))
        assertEquals(DictationPurpose.MESSAGE, DictationPurpose.restore("MESSAGE", null))
    }

    @Test fun `confirmation is single use and bound to the complete current note text`() {
        val gate = NoteInsertionGate()
        val request = gate.request("note-a", "Une note complète")!!
        assertEquals("Une note complète", gate.consume(request, "note-a", "Une note complète"))
        assertNull(gate.consume(request, "note-a", "Une note complète"))
        val edited = gate.request("note-a", "Avant")!!
        assertNull(gate.consume(edited, "note-a", "Après"))
        val other = gate.request("note-a", "Texte")!!
        assertNull(gate.consume(other, "note-b", "Texte"))
    }

    @Test fun `cancel close or resume invalidates confirmation without affecting a newer request`() {
        val gate = NoteInsertionGate()
        val old = gate.request("a", "Texte")!!
        gate.invalidate()
        assertNull(gate.consume(old, "a", "Texte"))
        val current = gate.request("a", "Texte")!!
        assertNull(gate.consume(old, "a", "Texte"))
        assertEquals("Texte", gate.consume(current, "a", "Texte"))
        assertNull(gate.request("a", " \n "))
    }
}
