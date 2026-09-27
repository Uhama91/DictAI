package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun adjacentShortTurnsForOneSpeakerShareAHeadingButARealSpeakerChangeRestartsIt() {
        val sophie = MeetingParticipant("p-sophie", ordinal = 1, channel = 1, name = "Sophie")
        val karim = MeetingParticipant("p-karim", ordinal = 2, channel = 2, name = "Karim")
        val document = document(
            listOf(sophie, karim),
            listOf(
                timedTurn("a-first", 11, 1_000, 1_180, "On commence", sophie.id),
                timedTurn("a-continuation", 12, 1_200, 1_380, "Et on continue", sophie.id),
                timedTurn("a-after-pause", 13, 3_000, 3_200, "Je reprends après une pause", sophie.id),
                timedTurn("b-reply", 14, 3_500, 3_760, "Je réponds", karim.id),
                timedTurn("a-return", 15, 3_900, 4_140, "Je reprends", sophie.id),
            ),
        )

        val rows = MeetingProjection.rows(document)

        assertEquals(
            listOf("a-first", "a-continuation", "a-after-pause", "b-reply", "a-return"),
            rows.map { it.turnId },
        )
        assertEquals(
            listOf(sophie.id, sophie.id, sophie.id, karim.id, sophie.id),
            rows.map { it.participantId },
        )
        assertEquals(listOf("Sophie", "Sophie", "Sophie", "Karim", "Sophie"), rows.map { it.label })
        assertEquals(listOf(true, false, true, true, true), rows.map { it.showSpeakerHeading })
    }

    @Test
    fun alignedSegmentsFollowAudioTimeInsteadOfUtteranceSnapshotOrder() {
        val reducer = MeetingTranscriptReducer("meeting-timeline", "run-timeline")
        reducer.apply(alignedHypothesis(utteranceId = 1, text = "Retour", startMs = 3_000, channel = 1))
        reducer.apply(alignedHypothesis(utteranceId = 2, text = "Milieu", startMs = 2_000, channel = 2))
        reducer.apply(alignedHypothesis(utteranceId = 3, text = "Début", startMs = 1_000, channel = 1))
        val snapshot = reducer.snapshot()

        assertEquals(
            "The reducer snapshot is in utterance order, which need not be the audio timeline.",
            listOf("Retour", "Milieu", "Début"),
            snapshot.turns.map { it.recognizedText },
        )
        assertEquals(
            listOf("Début", "Milieu", "Retour"),
            MeetingProjection.rows(snapshot).map { it.body },
        )
        assertTrue("word-aligned turns retain timing provenance", snapshot.turns.all { it.timingKnown })
    }

    @Test
    fun equalAudioStartTimesPreserveTheSourceOrderAcrossRevisions() {
        val sophie = MeetingParticipant("person-a", ordinal = 1, channel = 1, name = "Sophie")
        val sourceOrderedTurns = listOf(
            timedTurn("z-first-source", 1, 2_000, 2_800, "Premier segment", sophie.id),
            timedTurn("a-second-source", 2, 2_000, 2_300, "Segment suivant", sophie.id),
        )

        val rows = MeetingProjection.rows(document(listOf(sophie), sourceOrderedTurns))

        assertEquals(
            "A revised end bound must not reorder segments with the same audio start.",
            sourceOrderedTurns.map { it.id },
            rows.map { it.turnId },
        )
    }

    @Test
    fun audioIntervalsSurviveProjectionButAnUnalignedFallbackGetsNoInventedTime() {
        val reducer = MeetingTranscriptReducer("meeting-fallback", "run-fallback")
        reducer.apply(
            alignedHypothesis(
                utteranceId = 1,
                text = "Avant",
                startMs = 1_000,
                channel = 1,
                runId = "run-fallback",
            ),
        )
        reducer.apply(
            MeetingHypothesis(
                runId = "run-fallback",
                utteranceId = 2,
                revision = 1,
                words = emptyList(),
                transcript = "Passage sans repère",
                isFinal = false,
                stableSpeakerThroughMs = 3_000,
                audioProcessedMs = 3_000,
            ),
        )
        reducer.apply(
            alignedHypothesis(
                utteranceId = 3,
                text = "Après",
                startMs = 3_500,
                channel = 2,
                runId = "run-fallback",
            ),
        )
        val snapshot = reducer.snapshot()
        val rows = MeetingProjection.rows(snapshot)
        val before = rows.single { it.body == "Avant" }
        val fallback = rows.single { it.body == "Passage sans repère" }
        val after = rows.single { it.body == "Après" }

        assertEquals("Intervenant à confirmer", fallback.label)
        assertEquals(listOf(before.turnId, fallback.turnId, after.turnId), rows.map { it.turnId })
        assertTrue(snapshot.turns.single { it.recognizedText == "Avant" }.timingKnown)
        assertFalse(
            "fallback transcript bounds are not word alignment",
            snapshot.turns.single { it.recognizedText == "Passage sans repère" }.timingKnown,
        )
        assertTrue(snapshot.turns.single { it.recognizedText == "Après" }.timingKnown)
        assertEquals(1_000L to 1_120L, projectedAudioInterval(before))
        assertNull("untimed fallback text has no word-aligned audio interval", projectedAudioInterval(fallback))
        assertEquals(3_500L to 3_620L, projectedAudioInterval(after))
    }

    @Test
    fun chronologicalSortDoesNotCrossAnUntimedTranscriptBarrier() {
        val participant = MeetingParticipant("person-a", ordinal = 1, channel = 1, name = "Sophie")
        val document = document(
            listOf(participant),
            listOf(
                timedTurn("audio-later", 1, 3_000, 3_200, "Plus tard", participant.id),
                turn("untimed-middle", "Passage sans repère"),
                timedTurn("audio-earlier", 3, 1_000, 1_200, "Plus tôt", participant.id),
            ),
        )

        val rows = MeetingProjection.rows(document)

        assertEquals(listOf("audio-later", "untimed-middle", "audio-earlier"), rows.map { it.turnId })
        assertEquals(listOf(3_000L to 3_200L, null, 1_000L to 1_200L), rows.map(::projectedAudioInterval))
        assertEquals(listOf(true, true, true), rows.map { it.showSpeakerHeading })
    }

    @Test
    fun onlyTheAlignedWordsInATranscriptWithUnalignedEdgesGetAudioTimes() {
        val reducer = MeetingTranscriptReducer("meeting-edges", "run-edges")
        reducer.apply(
            MeetingHypothesis(
                runId = "run-edges",
                utteranceId = 1,
                revision = 1,
                words = listOf(MeetingWord("bonjour", 1_000, 1_120, channel = 1)),
                transcript = "Avant bonjour ensuite",
                isFinal = true,
                stableSpeakerThroughMs = 1_120,
                audioProcessedMs = 3_000,
            ),
        )

        val turns = reducer.snapshot().turns
        assertEquals(listOf("Avant", "bonjour", "ensuite"), turns.map { it.recognizedText })
        assertEquals(listOf(false, true, false), turns.map { it.timingKnown })
        val rows = MeetingProjection.rows(reducer.snapshot())
        assertEquals(listOf(null, 1_000L to 1_120L, null), rows.map(::projectedAudioInterval))
    }

    @Test
    fun unknownOrManuallyUnassignedSpeakersNeverShareAVisualHeading() {
        val person = MeetingParticipant("person-a", ordinal = 1, channel = 1, name = "Sophie")
        val manualUnknownTurns = listOf(
            timedTurn("unknown-a", 1, 1_000, 1_120, "Intervention à vérifier", person.id)
                .copy(hasManualAttribution = true, manualParticipantId = null),
            timedTurn("unknown-b", 2, 1_200, 1_320, "Toujours à confirmer", person.id)
                .copy(hasManualAttribution = true, manualParticipantId = null),
        )

        val rows = MeetingProjection.rows(document(listOf(person), manualUnknownTurns))

        assertEquals(listOf(null, null), rows.map { it.participantId })
        assertEquals(listOf("Intervenant à confirmer", "Intervenant à confirmer"), rows.map { it.label })
        assertEquals(listOf(true, true), rows.map { it.showSpeakerHeading })
    }

    @Test
    fun ignoredImageAndDocumentaryRowsBreakSpeakerGrouping() {
        val sophie = MeetingParticipant("person-a", ordinal = 1, channel = 1, name = "Sophie")
        val ignored = MeetingParticipant("person-hidden", ordinal = 2, channel = 2, name = "Karim", ignored = true)
        val document = document(
            listOf(sophie, ignored),
            listOf(
                timedTurn("sophie-before-image", 1, 1_000, 1_150, "Avant l’image", sophie.id),
                timedTurn("ignored-image", 2, 1_180, 1_240, "[[Image 7]]", ignored.id),
                timedTurn("sophie-after-image", 3, 1_260, 1_400, "Après l’image", sophie.id),
                turn("documentary", "Note libre", utteranceId = 0),
                timedTurn("sophie-after-document", 4, 1_420, 1_560, "Après la note", sophie.id),
            ),
        )

        val rows = MeetingProjection.rows(document, imageNumbers = setOf(7))

        assertEquals(
            listOf("sophie-before-image", "ignored-image", "sophie-after-image", "documentary", "sophie-after-document"),
            rows.map { it.turnId },
        )
        assertEquals(listOf(true, false, true, false, true), rows.map { it.showSpeakerHeading })
        assertFalse(rows.single { it.turnId == "ignored-image" }.editableSpeech)
        assertNull(rows.single { it.turnId == "documentary" }.participantId)
    }

    private fun projectedAudioInterval(row: MeetingProjection.Row): Pair<Long, Long>? {
        val start = row.audioStartMs
        val end = row.audioEndMs
        if (start == null && end == null) return null
        assertNotNull("audio end is present whenever audio start is present", start)
        assertNotNull("audio start is present whenever audio end is present", end)
        return requireNotNull(start) to requireNotNull(end)
    }

    private fun alignedHypothesis(
        utteranceId: Long,
        text: String,
        startMs: Long,
        channel: Int,
        runId: String = "run-timeline",
    ) = MeetingHypothesis(
        runId = runId,
        utteranceId = utteranceId,
        revision = 1,
        words = listOf(MeetingWord(text, startMs, startMs + 120, channel)),
        transcript = text,
        isFinal = true,
        stableSpeakerThroughMs = startMs + 120,
        audioProcessedMs = startMs + 200,
    )

    private fun timedTurn(
        id: String,
        utteranceId: Long,
        startMs: Long,
        endMs: Long,
        text: String,
        participantId: String,
    ): MeetingTurn = MeetingTurn(
        id = id,
        utteranceId = utteranceId,
        startMs = startMs,
        endMs = endMs,
        recognizedText = text,
        automaticParticipantId = participantId,
        attributionStable = true,
        timingKnown = true,
    )

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
        utteranceId: Long = id.hashCode().toLong(),
    ) = MeetingTurn(
        id = id,
        utteranceId = utteranceId,
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
