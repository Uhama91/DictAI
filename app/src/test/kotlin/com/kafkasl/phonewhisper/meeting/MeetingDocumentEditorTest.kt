package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingDocumentEditorTest {
    @Test
    fun `restored document preserves identity and ignores matching late ASR`() {
        val original = documentWithSpeech()
        val editor = MeetingDocumentEditor.restore(original)

        editor.apply(hypothesis(revision = 99, transcript = "late replacement"))

        assertEquals(original, editor.snapshot())
        assertEquals("Bonjour tout le monde", editor.snapshot().turns.single().recognizedText)
    }

    @Test
    fun `live finish rejects late ASR while document edits remain available`() {
        val editor = MeetingDocumentEditor.create(SESSION_ID, RUN_ID)
        editor.apply(hypothesis(revision = 1, transcript = "Bonjour"))
        val first = editor.snapshot()
        val turn = first.turns.single()

        val finished = editor.finish()
        editor.apply(hypothesis(revision = 2, transcript = "Bonjour tardif"))
        editor.edit(turn.id, "")

        assertTrue(finished.finished)
        assertEquals(finished.turns.single().id, editor.snapshot().turns.single().id)
        assertEquals("Bonjour", editor.snapshot().turns.single().recognizedText)
        assertEquals("", editor.snapshot().turns.single().editedText)
        assertTrue(editor.snapshot().finished)
    }

    @Test
    fun `document turn is stable reserved at zero and remains before speech`() {
        val editor = MeetingDocumentEditor.create(SESSION_ID, RUN_ID)
        val documentTurn = editor.ensureDocumentTurn()
        editor.apply(hypothesis(revision = 1, transcript = "Premières paroles"))
        val sameTurn = editor.ensureDocumentTurn()

        assertEquals(documentTurn, sameTurn)
        assertEquals(0L, documentTurn.utteranceId)
        assertNull(documentTurn.automaticParticipantId)
        assertEquals(documentTurn.id, editor.snapshot().turns.first().id)

        val restored = MeetingDocumentEditor.restore(editor.snapshot())
        assertEquals(documentTurn, restored.ensureDocumentTurn())
        restored.apply(hypothesis(revision = 2, transcript = "ne remplace pas le tour image"))
        assertEquals(documentTurn.id, restored.snapshot().turns.first().id)
    }

    @Test
    fun `assignment validates references and explicit unknown remains manual`() {
        val editor = MeetingDocumentEditor.create(SESSION_ID, RUN_ID)
        editor.apply(hypothesis(revision = 1, transcript = "Bonjour"))
        val turn = editor.snapshot().turns.single()

        editor.assign(turn.id, null)

        assertTrue(editor.snapshot().turns.single().hasManualAttribution)
        assertNull(editor.snapshot().turns.single().manualParticipantId)
        assertIllegalArgument { editor.assign(turn.id, "missing-participant") }
        assertIllegalArgument { editor.edit("missing-turn", "texte") }
        assertIllegalArgument { editor.rename("missing-participant", "Nom") }
        assertIllegalArgument { editor.setIgnored("missing-participant", true) }
    }

    @Test
    fun `restore supports empty edit normalized homonyms and raw text despite filter`() {
        val participantA = MeetingParticipant("$SESSION_ID:participant:1", 1, 1, "Zoë")
        val participantB = MeetingParticipant("$SESSION_ID:participant:2", 2, 2, "Zoë")
        val original = MeetingDocument(
            sessionId = SESSION_ID,
            runId = RUN_ID,
            participants = listOf(participantA, participantB),
            turns = listOf(
                MeetingTurn("turn-a", 1, 10, 20, "texte brut A", participantA.id, attributionStable = true),
                MeetingTurn("turn-b", 2, 30, 40, "texte brut B", participantB.id, attributionStable = true),
            ),
        )
        val editor = MeetingDocumentEditor.restore(original)

        editor.edit("turn-a", "")
        editor.rename(participantA.id, "  Élodie\u2028Martin  ")
        editor.rename(participantB.id, "Élodie Martin")
        editor.setIgnored(participantA.id, true)

        val snapshot = editor.snapshot()
        assertEquals(original.turns.map { it.id }, snapshot.turns.map { it.id })
        assertEquals("texte brut A", snapshot.turns.first().recognizedText)
        assertEquals("", snapshot.turns.first().editedText)
        assertEquals("Élodie Martin", snapshot.participants[0].name)
        assertEquals("Élodie Martin", snapshot.participants[1].name)
        assertNotEquals(snapshot.participants[0].id, snapshot.participants[1].id)
        assertFalse(MeetingProjection.text(snapshot).contains("texte brut A"))
        assertTrue(MeetingProjection.text(snapshot).contains("texte brut B"))
    }

    private fun documentWithSpeech(): MeetingDocument {
        val participant = MeetingParticipant("$SESSION_ID:participant:1", 1, 1)
        return MeetingDocument(
            sessionId = SESSION_ID,
            runId = RUN_ID,
            participants = listOf(participant),
            turns = listOf(
                MeetingTurn("turn-1", 1, 100, 300, "Bonjour tout le monde", participant.id, attributionStable = true),
            ),
        )
    }

    private fun hypothesis(revision: Long, transcript: String) = MeetingHypothesis(
        runId = RUN_ID,
        utteranceId = 1,
        revision = revision,
        words = listOf(MeetingWord(transcript, 100, 300, channel = 1)),
        transcript = transcript,
        isFinal = false,
        stableSpeakerThroughMs = 300,
        audioProcessedMs = 300,
    )

    private fun assertIllegalArgument(action: () -> Unit) {
        try {
            action()
            throw AssertionError("expected invalid document reference to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private companion object {
        const val SESSION_ID = "session-editor"
        const val RUN_ID = "run-editor"
    }
}
