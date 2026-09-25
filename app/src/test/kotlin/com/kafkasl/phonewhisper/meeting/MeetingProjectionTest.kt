package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingProjectionTest {
    @Test
    fun renamingUpdatesOnlyTheLabelAndUsesFirstDiscoveryOrder() {
        val profiles = MeetingParticipants("meeting-a")
        val first = requireNotNull(profiles.observe(6))
        val second = requireNotNull(profiles.observe(2))
        val turns = listOf(
            turn("turn-1", "Personne 1 parle à Sophie.", automaticParticipantId = first.id, attributionStable = true),
            turn("turn-2", "Personne 2 répond.", automaticParticipantId = second.id, attributionStable = true),
        )

        assertEquals(
            "Personne 1\nPersonne 1 parle à Sophie.\n\nPersonne 2\nPersonne 2 répond.",
            MeetingProjection.text(document(profiles.all(), turns)),
        )

        profiles.rename(first.id, "Sophie")

        assertEquals(
            "Sophie\nPersonne 1 parle à Sophie.\n\nPersonne 2\nPersonne 2 répond.",
            MeetingProjection.text(document(profiles.all(), turns)),
        )
    }

    @Test
    fun unstableIgnoredCandidateRemainsVisibleAsUnconfirmed() {
        val profiles = MeetingParticipants("meeting-a")
        val ignored = requireNotNull(profiles.observe(3))
        profiles.setIgnored(ignored.id, true)
        val turns = listOf(
            turn(
                "turn-1",
                "Paroles encore incertaines.",
                automaticParticipantId = ignored.id,
                attributionStable = false,
            ),
        )

        val document = document(profiles.all(), turns)
        assertEquals("Intervenant à confirmer\nParoles encore incertaines.", MeetingProjection.text(document))
        val row = MeetingProjection.rows(document).single()
        assertEquals("Intervenant à confirmer", row.label)
        assertNull(row.participantId)
        assertFalse(row.attributionStable)
        assertTrue(row.editableSpeech)
    }

    @Test
    fun filtersIgnoredStableAndManuallyAssignedTurns() {
        val profiles = MeetingParticipants("meeting-a")
        val ignored = requireNotNull(profiles.observe(3))
        profiles.setIgnored(ignored.id, true)
        val turns = listOf(
            turn("stable", "Texte stable masqué.", automaticParticipantId = ignored.id, attributionStable = true),
            turn(
                "manual",
                "Texte attribué manuellement masqué.",
                automaticParticipantId = null,
                manualParticipantId = ignored.id,
                hasManualAttribution = true,
            ),
            turn("unknown", "Texte conservé."),
        )

        assertEquals(
            "Intervenant à confirmer\nTexte conservé.",
            MeetingProjection.text(document(profiles.all(), turns)),
        )
    }

    @Test
    fun reShowingParticipantRestoresTheExactEditedText() {
        val profiles = MeetingParticipants("meeting-a")
        val participant = requireNotNull(profiles.observe(3))
        profiles.rename(participant.id, "Sophie")
        profiles.setIgnored(participant.id, true)
        val turns = listOf(
            turn(
                "turn-1",
                "Le rendez-vous est mardi.",
                automaticParticipantId = participant.id,
                editedText = "Le rendez-vous est jeudi.",
                attributionStable = true,
            ),
        )
        val document = document(profiles.all(), turns)

        assertEquals("", MeetingProjection.text(document))
        profiles.setIgnored(participant.id, false)
        assertEquals("Sophie\nLe rendez-vous est jeudi.", MeetingProjection.text(document(profiles.all(), turns)))
    }

    @Test
    fun manualVisibleParticipantOverridesAnIgnoredAutomaticCandidate() {
        val profiles = MeetingParticipants("meeting-a")
        val ignored = requireNotNull(profiles.observe(3))
        val visible = requireNotNull(profiles.observe(1))
        profiles.setIgnored(ignored.id, true)
        profiles.rename(visible.id, "Karim")
        val turns = listOf(
            turn(
                "turn-1",
                "La décision est prise.",
                automaticParticipantId = ignored.id,
                manualParticipantId = visible.id,
                hasManualAttribution = true,
            ),
        )

        val document = document(profiles.all(), turns)
        assertEquals("Karim\nLa décision est prise.", MeetingProjection.text(document))
        val row = MeetingProjection.rows(document).single()
        assertEquals(visible.id, row.participantId)
        assertTrue(row.attributionStable)
    }

    @Test
    fun manualUnknownAssignmentOverridesAnIgnoredAutomaticCandidate() {
        val profiles = MeetingParticipants("meeting-a")
        val ignored = requireNotNull(profiles.observe(3))
        profiles.setIgnored(ignored.id, true)
        val turns = listOf(
            turn(
                "turn-1",
                "Cette parole reste visible.",
                automaticParticipantId = ignored.id,
                hasManualAttribution = true,
                manualParticipantId = null,
                attributionStable = true,
            ),
        )

        val document = document(profiles.all(), turns)
        assertEquals("Intervenant à confirmer\nCette parole reste visible.", MeetingProjection.text(document))
        val row = MeetingProjection.rows(document).single()
        assertNull(row.participantId)
        assertTrue(row.attributionStable)
    }

    @Test
    fun emptyOrWhitespaceEditedTextDoesNotFallBackToRecognizedText() {
        val profiles = MeetingParticipants("meeting-a")
        val participant = requireNotNull(profiles.observe(1))
        val turns = listOf(
            turn(
                "turn-1",
                "Texte supprimé volontairement.",
                automaticParticipantId = participant.id,
                editedText = "",
                attributionStable = true,
            ),
            turn("turn-2", "", automaticParticipantId = participant.id, attributionStable = true),
            turn("turn-3", "Texte ignoré aussi.", automaticParticipantId = participant.id, editedText = " \t ", attributionStable = true),
        )

        val projected = MeetingProjection.text(document(profiles.all(), turns))

        assertEquals("", projected)
    }

    @Test
    fun ignoredStableVoiceKeepsOnlyKnownImagesAndNeverExposesItsSpeech() {
        val ignored = MeetingParticipant("p-sophie", ordinal = 1, channel = 1, name = "Sophie", ignored = true)
        val visible = MeetingParticipant("p-karim", ordinal = 2, channel = 2, name = "Karim")
        val turns = listOf(
            turn(
                "turn-hidden",
                "Secret masqué [[Image 2]] texte privé [[Image 99]] [[Image 4]] à cacher.",
                automaticParticipantId = ignored.id,
                attributionStable = true,
            ),
            turn(
                "turn-visible",
                "La décision est confirmée.",
                automaticParticipantId = visible.id,
                attributionStable = true,
            ),
        )
        val document = document(listOf(ignored, visible), turns)

        val rows = MeetingProjection.rows(document, imageNumbers = setOf(2, 4))

        assertEquals(2, rows.size)
        val imageRow = rows[0]
        assertEquals("turn-hidden", imageRow.turnId)
        assertNull(imageRow.label)
        assertEquals("[[Image 2]]\n\n[[Image 4]]", imageRow.body)
        assertEquals(ignored.id, imageRow.participantId)
        assertTrue(imageRow.attributionStable)
        assertFalse(imageRow.editableSpeech)
        assertEquals("Karim", rows[1].label)
        assertEquals("La décision est confirmée.", rows[1].body)
        assertTrue(rows[1].editableSpeech)
        assertEquals(
            "[[Image 2]]\n\n[[Image 4]]\n\nKarim\nLa décision est confirmée.",
            MeetingProjection.text(document, imageNumbers = setOf(2, 4)),
        )
    }

    @Test
    fun keepEmptyTurnsRetainsEditorAnchorsButTextExportOmitsThem() {
        val participant = MeetingParticipant("p-empty", ordinal = 1, channel = 1, name = "Sophie")
        val document = document(
            listOf(participant),
            listOf(turn("turn-empty", "", automaticParticipantId = participant.id, attributionStable = true)),
        )

        assertEquals(emptyList<MeetingProjection.Row>(), MeetingProjection.rows(document))
        val editRows = MeetingProjection.rows(document, keepEmptyTurns = true)
        assertEquals(1, editRows.size)
        assertEquals("turn-empty", editRows.single().turnId)
        assertEquals("Sophie", editRows.single().label)
        assertEquals("", editRows.single().body)
        assertTrue(editRows.single().editableSpeech)
        assertEquals("", MeetingProjection.text(document))
    }

    @Test
    fun documentaryTurnHasNoInventedSpeakerAndRemainsEditable() {
        val documentTurn = MeetingTurn(
            id = "meeting-a:document:turn",
            utteranceId = 0,
            startMs = 0,
            endMs = 0,
            recognizedText = "",
            automaticParticipantId = null,
            editedText = "[[Image 1]]",
        )
        val document = document(emptyList(), listOf(documentTurn))

        val row = MeetingProjection.rows(document, imageNumbers = setOf(1), keepEmptyTurns = true).single()

        assertNull(row.label)
        assertNull(row.participantId)
        assertEquals("[[Image 1]]", row.body)
        assertFalse(row.attributionStable)
        assertTrue(row.editableSpeech)
        assertEquals("[[Image 1]]", MeetingProjection.text(document, imageNumbers = setOf(1)))
    }

    @Test
    fun manualAssignmentOnDocumentaryTurnIsPreserved() {
        val participant = MeetingParticipant("p-manual", ordinal = 1, channel = 1, name = "Sophie")
        val documentTurn = MeetingTurn(
            id = "meeting-a:document:turn",
            utteranceId = 0,
            startMs = 0,
            endMs = 0,
            recognizedText = "",
            automaticParticipantId = null,
            manualParticipantId = participant.id,
            hasManualAttribution = true,
            editedText = "Notes partagées",
        )
        val document = document(listOf(participant), listOf(documentTurn))

        val row = MeetingProjection.rows(document, keepEmptyTurns = true).single()

        assertEquals("Sophie", row.label)
        assertEquals(participant.id, row.participantId)
        assertTrue(row.attributionStable)
        assertTrue(row.editableSpeech)
    }

    private fun document(
        participants: List<MeetingParticipant>,
        turns: List<MeetingTurn>,
    ) = MeetingDocument(
        sessionId = "meeting-a",
        runId = "run-a",
        participants = participants,
        turns = turns,
    )

    private fun turn(
        id: String,
        recognizedText: String,
        automaticParticipantId: String? = null,
        manualParticipantId: String? = null,
        hasManualAttribution: Boolean = false,
        editedText: String? = null,
        attributionStable: Boolean = false,
    ) = MeetingTurn(
        id = id,
        utteranceId = id.hashCode().toLong(),
        startMs = 0L,
        endMs = 1_000L,
        recognizedText = recognizedText,
        automaticParticipantId = automaticParticipantId,
        manualParticipantId = manualParticipantId,
        hasManualAttribution = hasManualAttribution,
        editedText = editedText,
        attributionStable = attributionStable,
    )
}
