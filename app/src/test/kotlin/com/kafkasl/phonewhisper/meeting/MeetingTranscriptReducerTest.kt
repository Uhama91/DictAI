package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingTranscriptReducerTest {
    @Test
    fun correctionSurvivesRevisionAndNewWordsContinueAfterItsAnchor() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalWords = listOf(word("Le", 100, 150), word("mardi", 150, 200))
        reducer.apply(hypothesis(utteranceId = 4, revision = 1, words = originalWords, transcript = "Le mardi"))
        val original = reducer.snapshot().turns.single()
        val participantId = requireNotNull(original.automaticParticipantId)
        reducer.edit(original.id, "Le jeudi")
        reducer.rename(participantId, "Sophie")
        reducer.setIgnored(participantId, true)
        assertEquals("", MeetingProjection.text(reducer.snapshot()))
        reducer.setIgnored(participantId, false)

        reducer.apply(
            hypothesis(
                utteranceId = 4,
                revision = 2,
                words = originalWords + word("matin", 200, 250),
                transcript = "Le mardi matin",
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals(original.id, updated.id)
        assertEquals("Le mardi matin", updated.recognizedText)
        assertEquals("Le jeudi matin", updated.editedText)
        assertEquals(250L, updated.endMs)
        assertEquals("Sophie\nLe jeudi matin", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun androidPunctuationFixtureKeepsTheEditedTerminalPeriodWithoutTheWordInsertedBeforeIt() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalText = "La séance aura lieu mardi."
        val revisedText = "La séance aura lieu mardi matin."
        val (originalWords, originalProcessedMs) = fixtureTimedWords(originalText, startMs = 1_000L)
        reducer.apply(
            hypothesis(
                utteranceId = 1,
                revision = 1,
                words = originalWords,
                transcript = originalText,
                isFinal = true,
                stableThrough = originalProcessedMs,
                processedMs = originalProcessedMs,
            ),
        )
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "La séance aura lieu jeudi.")
        val (revisedWords, revisedProcessedMs) = fixtureTimedWords(revisedText, startMs = 1_000L)

        reducer.apply(
            hypothesis(
                utteranceId = 1,
                revision = 2,
                words = revisedWords,
                transcript = revisedText,
                isFinal = true,
                stableThrough = revisedProcessedMs,
                processedMs = revisedProcessedMs,
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals("the raw revised words remain available", revisedText, updated.recognizedText)
        assertEquals("the Android edit stays exactly as entered", "La séance aura lieu jeudi.", updated.editedText)
        assertEquals(MeetingEditAlignmentStatus.ALIGNED, reducer.alignmentDiagnostics.last().status)
    }

    @Test
    fun lexicalContinuationAfterTheProtectedPunctuationRemainsAfterTheEdit() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalText = "La séance aura lieu mardi."
        val revisedText = "La séance aura lieu mardi matin. Nous préparons la suite."
        val (originalWords, originalProcessedMs) = fixtureTimedWords(originalText, startMs = 1_000L)
        reducer.apply(
            hypothesis(
                utteranceId = 1,
                revision = 1,
                words = originalWords,
                transcript = originalText,
                isFinal = true,
                stableThrough = originalProcessedMs,
                processedMs = originalProcessedMs,
            ),
        )
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "La séance aura lieu jeudi.")
        val (revisedWords, revisedProcessedMs) = fixtureTimedWords(revisedText, startMs = 1_000L)

        reducer.apply(
            hypothesis(
                utteranceId = 1,
                revision = 2,
                words = revisedWords,
                transcript = revisedText,
                isFinal = true,
                stableThrough = revisedProcessedMs,
                processedMs = revisedProcessedMs,
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals(revisedText, updated.recognizedText)
        assertEquals("La séance aura lieu jeudi. Nous préparons la suite.", updated.editedText)
        assertEquals(MeetingEditAlignmentStatus.ALIGNED, reducer.alignmentDiagnostics.last().status)
    }

    @Test
    fun attachedTerminalPunctuationAlignsToTheRevisedSentenceBoundary() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalText = "La séance aura lieu mardi."
        val revisedText = "La séance aura lieu mardi matin."
        val (originalTokenizedWords, originalProcessedMs) = fixtureTimedWords(originalText, startMs = 1_000L)
        val originalWords = attachTerminalPunctuation(originalTokenizedWords)
        reducer.apply(
            hypothesis(
                utteranceId = 1,
                revision = 1,
                words = originalWords,
                transcript = originalText,
                isFinal = true,
                stableThrough = originalProcessedMs,
                processedMs = originalProcessedMs,
            ),
        )
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "La séance aura lieu jeudi.")

        val (revisedTokenizedWords, revisedProcessedMs) = fixtureTimedWords(revisedText, startMs = 1_000L)
        reducer.apply(
            hypothesis(
                utteranceId = 1,
                revision = 2,
                words = attachTerminalPunctuation(revisedTokenizedWords),
                transcript = revisedText,
                isFinal = true,
                stableThrough = revisedProcessedMs,
                processedMs = revisedProcessedMs,
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals("La séance aura lieu jeudi.", updated.editedText)
        assertEquals(MeetingEditAlignmentStatus.ALIGNED, reducer.alignmentDiagnostics.last().status)
    }

    @Test
    fun wordlessRepeatedFinalKeepsOnlyTheEditedTerminalPeriod() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalText = "La séance aura lieu mardi."
        reducer.apply(hypothesis(utteranceId = 1, revision = 1, words = emptyList(), transcript = originalText))
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "La séance aura lieu jeudi.")

        reducer.apply(hypothesis(utteranceId = 1, revision = 2, words = emptyList(), transcript = originalText))

        val updated = reducer.snapshot().turns.single()
        assertEquals("the raw repeated hypothesis is retained", originalText, updated.recognizedText)
        assertEquals("a punctuation-only suffix does not duplicate the user's final mark", "La séance aura lieu jeudi.", updated.editedText)
    }

    @Test
    fun wordlessRevisionKeepsARealContinuationAfterTheProtectedTerminalPeriod() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalText = "La séance aura lieu mardi."
        reducer.apply(hypothesis(utteranceId = 1, revision = 1, words = emptyList(), transcript = originalText))
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "La séance aura lieu jeudi.")

        reducer.apply(
            hypothesis(
                utteranceId = 1,
                revision = 2,
                words = emptyList(),
                transcript = "La séance aura lieu mardi. Nous préparons la suite.",
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals("La séance aura lieu mardi. Nous préparons la suite.", updated.recognizedText)
        assertEquals("La séance aura lieu jeudi. Nous préparons la suite.", updated.editedText)
    }

    @Test
    fun changedTerminalMarkDoesNotBecomeAContinuationOfTheEditedPeriod() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalText = "La séance aura lieu mardi."
        val revisedText = "La séance aura lieu mardi? Nous préparons la suite."
        reducer.apply(hypothesis(utteranceId = 1, revision = 1, words = emptyList(), transcript = originalText))
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "La séance aura lieu jeudi.")

        reducer.apply(hypothesis(utteranceId = 1, revision = 2, words = emptyList(), transcript = revisedText))

        val updated = reducer.snapshot().turns.single()
        assertEquals("the changed raw mark remains inspectable", revisedText, updated.recognizedText)
        assertEquals("an incompatible question mark does not append to the edited period", "La séance aura lieu jeudi.", updated.editedText)
        assertEquals(MeetingEditAlignmentStatus.UNRESOLVED, reducer.alignmentDiagnostics.last().status)
    }

    @Test
    fun missingTerminalMarkKeepsTheEditAndReportsUnresolvedAlignment() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalText = "La séance aura lieu mardi."
        val revisedText = "La séance aura lieu mardi Nous préparons la suite."
        reducer.apply(hypothesis(utteranceId = 1, revision = 1, words = emptyList(), transcript = originalText))
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "La séance aura lieu jeudi.")

        reducer.apply(hypothesis(utteranceId = 1, revision = 2, words = emptyList(), transcript = revisedText))

        val updated = reducer.snapshot().turns.single()
        assertEquals(revisedText, updated.recognizedText)
        assertEquals("La séance aura lieu jeudi.", updated.editedText)
        assertEquals(MeetingEditAlignmentStatus.UNRESOLVED, reducer.alignmentDiagnostics.last().status)
    }

    @Test
    fun anIntentionallyEmptyWordlessEditStaysEmptyAcrossRepeatedTerminalPunctuation() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalText = "La séance aura lieu mardi."
        reducer.apply(hypothesis(utteranceId = 1, revision = 1, words = emptyList(), transcript = originalText))
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "")

        reducer.apply(hypothesis(utteranceId = 1, revision = 2, words = emptyList(), transcript = originalText))

        val updated = reducer.snapshot().turns.single()
        assertEquals("the raw final is not discarded", originalText, updated.recognizedText)
        assertEquals("a punctuation token cannot resurrect a deleted edit", "", updated.editedText)
    }

    @Test
    fun editedWordlessTranscriptAlignsTextWithoutInventingSpeakerAttribution() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 5,
                revision = 1,
                words = listOf(word("mardi", 100, 200)),
                transcript = "mardi",
            ),
        )
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "jeudi")

        reducer.apply(
            hypothesis(
                utteranceId = 5,
                revision = 2,
                words = listOf(word("mardi", -10, 0)),
                transcript = "mardi matin",
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals(original.id, updated.id)
        assertEquals("mardi matin", updated.recognizedText)
        assertEquals("jeudi matin", updated.editedText)
        assertNull(updated.automaticParticipantId)
        assertFalse(updated.attributionStable)
        assertEquals("Intervenant à confirmer\njeudi matin", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun unalignableFallbackKeepsTheEditAndExposesAnUnresolvedDiagnostic() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 6,
                revision = 1,
                words = listOf(word("mardi", 100, 200)),
                transcript = "mardi",
            ),
        )
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "jeudi")

        reducer.apply(
            hypothesis(
                utteranceId = 6,
                revision = 2,
                words = listOf(word("vendredi", -10, 0)),
                transcript = "vendredi réunion",
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals(original.id, updated.id)
        assertEquals("jeudi", updated.editedText)
        assertEquals("vendredi réunion", updated.recognizedText)
        assertEquals(MeetingEditAlignmentStatus.UNRESOLVED, reducer.alignmentDiagnostics.last().status)
        assertEquals("Intervenant à confirmer\njeudi", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun deletingTheWholeTurnCannotBeUndoneByANewerFinalOrDuplicateFinal() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val words = listOf(word("texte", 100, 150), word("supprimé", 150, 200))
        reducer.apply(hypothesis(utteranceId = 7, revision = 1, words = words, transcript = "texte supprimé"))
        val turnId = reducer.snapshot().turns.single().id
        reducer.edit(turnId, "")

        reducer.apply(
            hypothesis(
                utteranceId = 7,
                revision = 2,
                words = words,
                transcript = "texte supprimé",
                isFinal = true,
            ),
        )
        val afterFinal = reducer.snapshot()
        reducer.apply(
            hypothesis(
                utteranceId = 7,
                revision = 2,
                words = words + word("encore", 200, 250),
                transcript = "texte supprimé encore",
                isFinal = true,
            ),
        )

        assertEquals("", afterFinal.turns.single().editedText)
        assertEquals("", MeetingProjection.text(afterFinal))
        assertEquals(afterFinal, reducer.snapshot())
    }

    @Test
    fun finalWithoutWordsKeepsAnExistingManualEdit() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 8,
                revision = 1,
                words = listOf(word("bonjour", 100, 200)),
                transcript = "bonjour",
            ),
        )
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "salut")

        reducer.apply(
            hypothesis(
                utteranceId = 8,
                revision = 2,
                words = emptyList(),
                transcript = "",
                isFinal = true,
            ),
        )

        val afterFinal = reducer.snapshot().turns.single()
        assertEquals(original.id, afterFinal.id)
        assertEquals("salut", afterFinal.editedText)
        assertEquals("salut", MeetingProjection.text(reducer.snapshot()).substringAfter('\n'))
    }

    @Test
    fun manualUnknownAndManualParticipantOverrideAutomaticAttributions() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(utteranceId = 9, revision = 1, words = listOf(word("choix", 100, 200, 1)), transcript = "choix"),
        )
        reducer.apply(
            hypothesis(utteranceId = 10, revision = 1, words = listOf(word("suite", 300, 400, 2)), transcript = "suite"),
        )
        val firstTurn = reducer.snapshot().turns.first { it.utteranceId == 9L }
        val ignoredId = requireNotNull(firstTurn.automaticParticipantId)
        val manualId = requireNotNull(reducer.snapshot().turns.first { it.utteranceId == 10L }.automaticParticipantId)
        reducer.rename(manualId, "Karim")
        reducer.edit(firstTurn.id, "Décision acceptée.")
        reducer.assign(firstTurn.id, null)

        reducer.apply(
            hypothesis(utteranceId = 9, revision = 2, words = listOf(word("choix", 100, 200, 1)), transcript = "choix"),
        )

        var updated = reducer.snapshot().turns.first { it.utteranceId == 9L }
        assertTrue(updated.hasManualAttribution)
        assertNull(updated.manualParticipantId)
        assertTrue(MeetingProjection.text(reducer.snapshot()).startsWith("Intervenant à confirmer\nDécision acceptée."))

        reducer.setIgnored(ignoredId, true)
        reducer.assign(firstTurn.id, manualId)
        updated = reducer.snapshot().turns.first { it.utteranceId == 9L }

        assertEquals(manualId, updated.manualParticipantId)
        assertTrue(updated.hasManualAttribution)
        assertTrue(MeetingProjection.text(reducer.snapshot()).startsWith("Karim\nDécision acceptée."))
    }

    @Test
    fun anOlderUtteranceCanReceiveANewerTagRevisionWhileStaleEventsAreIgnored() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val firstWord = word("premier", 100, 200, 1)
        val secondWord = word("suivant", 300, 400, 2)
        reducer.apply(hypothesis(utteranceId = 1, revision = 1, words = listOf(firstWord), transcript = "premier"))
        reducer.apply(hypothesis(utteranceId = 2, revision = 1, words = listOf(secondWord), transcript = "suivant"))
        val firstId = reducer.snapshot().turns.first { it.utteranceId == 1L }.id

        reducer.apply(
            hypothesis(utteranceId = 1, revision = 2, words = listOf(firstWord.copy(channel = 3)), transcript = "premier"),
        )
        val updated = reducer.snapshot()
        reducer.apply(hypothesis(utteranceId = 1, revision = 3, words = listOf(firstWord), transcript = "autre run", runId = "old-run"))
        reducer.apply(hypothesis(utteranceId = 1, revision = 1, words = listOf(firstWord), transcript = "stale"))
        reducer.apply(hypothesis(utteranceId = 1, revision = 2, words = listOf(firstWord), transcript = "duplicate"))

        val first = reducer.snapshot().turns.first { it.utteranceId == 1L }
        assertEquals(firstId, first.id)
        assertNotEquals("stale", first.recognizedText)
        assertEquals(updated, reducer.snapshot())
        assertEquals(2, reducer.snapshot().turns.size)
    }

    @Test
    fun zeroOneTwoThreeAndReturningSpeakerCreateStableSpeakerTurns() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val words = listOf(
            word("bonjour", 100, 150, 1),
            word("salut", 150, 200, 2),
            word("merci", 200, 250, 3),
            word("encore", 250, 300, 1),
        )

        reducer.apply(hypothesis(utteranceId = 11, revision = 1, words = words, transcript = "bonjour salut merci encore"))

        val turns = reducer.snapshot().turns
        assertEquals(4, turns.size)
        assertEquals(turns.first().automaticParticipantId, turns.last().automaticParticipantId)
        assertEquals(3, reducer.snapshot().participants.size)
        assertEquals(4, turns.map { it.id }.distinct().size)
        assertEquals(
            "Personne 1\nbonjour\n\nPersonne 2\nsalut\n\nPersonne 3\nmerci\n\nPersonne 1\nencore",
            MeetingProjection.text(reducer.snapshot()),
        )
    }

    @Test
    fun provisionalUnknownChannelCanBecomeKnownWithoutChangingTurnIdentity() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val provisional = word("bonjour", 100, 200, 0)
        reducer.apply(
            hypothesis(utteranceId = 12, revision = 1, words = listOf(provisional), transcript = "bonjour", stableThrough = 0),
        )
        val before = reducer.snapshot().turns.single()

        reducer.apply(
            hypothesis(utteranceId = 12, revision = 2, words = listOf(provisional.copy(channel = 1)), transcript = "bonjour", stableThrough = 200),
        )

        val after = reducer.snapshot().turns.single()
        assertEquals(before.id, after.id)
        assertNull(before.automaticParticipantId)
        assertTrue(after.automaticParticipantId != null)
        assertTrue(after.attributionStable)
        assertEquals("Personne 1\nbonjour", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun invalidWordTimesKeepTranscriptVisibleWithoutSpeakerAttribution() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)

        reducer.apply(
            hypothesis(
                utteranceId = 13,
                revision = 1,
                words = listOf(word("paroles", 300, 200, 1)),
                transcript = "paroles sans temps fiable",
            ),
        )

        val turn = reducer.snapshot().turns.single()
        assertEquals("paroles sans temps fiable", turn.recognizedText)
        assertNull(turn.automaticParticipantId)
        assertEquals("Intervenant à confirmer\nparoles sans temps fiable", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun transcriptWithoutWordsRemainsVisibleAsUnconfirmed() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)

        reducer.apply(
            hypothesis(utteranceId = 14, revision = 1, words = emptyList(), transcript = "texte sans mots horodatés"),
        )

        assertEquals(1, reducer.snapshot().turns.size)
        assertEquals("Intervenant à confirmer\ntexte sans mots horodatés", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun changedSpeakerBoundaryInsideAnEditedTurnKeepsOneUnconfirmedTurn() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        val originalWords = listOf(word("bonjour", 100, 150, 1), word("tout", 150, 200, 1))
        reducer.apply(hypothesis(utteranceId = 15, revision = 1, words = originalWords, transcript = "bonjour tout"))
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "Bonjour à tous")

        reducer.apply(
            hypothesis(
                utteranceId = 15,
                revision = 2,
                words = listOf(originalWords[0], originalWords[1].copy(channel = 2)),
                transcript = "bonjour tout",
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals(1, reducer.snapshot().turns.size)
        assertEquals(original.id, updated.id)
        assertEquals("Bonjour à tous", updated.editedText)
        assertNull(updated.automaticParticipantId)
        assertEquals("Intervenant à confirmer\nBonjour à tous", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun latePunctuationUpdatesAnUneditedTurnWithoutDuplicatingIt() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 16,
                revision = 1,
                words = listOf(word("Bonjour", 100, 150, 1), word("Sophie", 150, 200, 1)),
                transcript = "Bonjour Sophie",
            ),
        )
        val turnId = reducer.snapshot().turns.single().id

        reducer.apply(
            hypothesis(
                utteranceId = 16,
                revision = 2,
                words = listOf(word("Bonjour,", 100, 150, 1), word("Sophie.", 150, 200, 1)),
                transcript = "Bonjour, Sophie.",
                isFinal = true,
            ),
        )

        val turns = reducer.snapshot().turns
        assertEquals(1, turns.size)
        assertEquals(turnId, turns.single().id)
        assertEquals("Bonjour, Sophie.", turns.single().recognizedText)
    }

    @Test
    fun removedFillerBeforeTheAnchorDoesNotDropEditedContinuation() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 22,
                revision = 1,
                words = listOf(word("euh", 100, 150), word("mardi", 150, 200)),
                transcript = "euh mardi",
            ),
        )
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "jeudi")

        reducer.apply(
            hypothesis(
                utteranceId = 22,
                revision = 2,
                words = listOf(word("mardi", 150, 200), word("matin", 200, 250)),
                transcript = "mardi matin",
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals(original.id, updated.id)
        assertEquals("jeudi matin", updated.editedText)
        assertEquals("mardi matin", updated.recognizedText)
    }

    @Test
    fun manualAttributionSurvivesSpeakerBoundaryChangeWithoutTextEdit() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 23,
                revision = 1,
                words = listOf(word("bonjour", 100, 150), word("tout", 150, 200)),
                transcript = "bonjour tout",
            ),
        )
        val turn = reducer.snapshot().turns.single()
        reducer.apply(
            hypothesis(utteranceId = 24, revision = 1, words = listOf(word("autre", 300, 350, 2)), transcript = "autre"),
        )
        val manualParticipantId = requireNotNull(
            reducer.snapshot().turns.first { it.utteranceId == 24L }.automaticParticipantId,
        )
        reducer.assign(turn.id, manualParticipantId)

        reducer.apply(
            hypothesis(
                utteranceId = 23,
                revision = 2,
                words = listOf(word("bonjour", 100, 150, 1), word("tout", 150, 200, 2)),
                transcript = "bonjour tout",
            ),
        )

        val updated = reducer.snapshot().turns.single { it.utteranceId == 23L }
        assertEquals(turn.id, updated.id)
        assertTrue(updated.hasManualAttribution)
        assertEquals(manualParticipantId, updated.manualParticipantId)
        assertNull(updated.automaticParticipantId)
        assertTrue(MeetingProjection.text(reducer.snapshot()).startsWith("Personne 2\nbonjour tout"))
    }

    @Test
    fun twoEditedTurnsKeepSeparateIdsWhenSpeakerSegmentationMerges() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 25,
                revision = 1,
                words = listOf(word("un", 100, 150, 1), word("deux", 150, 200, 2)),
                transcript = "un deux",
            ),
        )
        val before = reducer.snapshot().turns
        assertEquals(2, before.size)
        reducer.edit(before[0].id, "Premier")
        reducer.edit(before[1].id, "Second")

        reducer.apply(
            hypothesis(
                utteranceId = 25,
                revision = 2,
                words = listOf(word("un", 100, 150, 1), word("deux", 150, 200, 1)),
                transcript = "un deux",
            ),
        )

        val after = reducer.snapshot().turns
        assertEquals(2, after.size)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(listOf("Premier", "Second"), after.map { it.editedText })
    }

    @Test
    fun uneditedUnknownTurnSplitsWhenSpeakerChannelsBecomeKnown() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 26,
                revision = 1,
                words = listOf(word("bonjour", 100, 150, 0), word("salut", 150, 200, 0)),
                transcript = "bonjour salut",
                stableThrough = 0,
            ),
        )
        val previousId = reducer.snapshot().turns.single().id

        reducer.apply(
            hypothesis(
                utteranceId = 26,
                revision = 2,
                words = listOf(word("bonjour", 100, 150, 1), word("salut", 150, 200, 2)),
                transcript = "bonjour salut",
            ),
        )

        val turns = reducer.snapshot().turns
        assertEquals(2, turns.size)
        assertEquals(previousId, turns.first().id)
        assertTrue(turns[0].automaticParticipantId != null)
        assertTrue(turns[1].automaticParticipantId != null)
        assertEquals("bonjour", turns[0].recognizedText)
        assertEquals("salut", turns[1].recognizedText)
    }

    @Test
    fun removedUneditedWordGroupIsRemovedFromTheDocument() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 27,
                revision = 1,
                words = listOf(word("supprimer", 100, 150, 1), word("garder", 150, 200, 2)),
                transcript = "supprimer garder",
            ),
        )
        val retainedId = reducer.snapshot().turns.last().id

        reducer.apply(
            hypothesis(
                utteranceId = 27,
                revision = 2,
                words = listOf(word("garder", 150, 200, 2)),
                transcript = "garder",
            ),
        )

        val turns = reducer.snapshot().turns
        assertEquals(1, turns.size)
        assertEquals(retainedId, turns.single().id)
        assertEquals("garder", turns.single().recognizedText)
    }

    @Test
    fun wordlessEditCanContinueWhenTimedWordsArriveLater() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(hypothesis(utteranceId = 28, revision = 1, words = emptyList(), transcript = "mardi"))
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "jeudi")

        reducer.apply(
            hypothesis(
                utteranceId = 28,
                revision = 2,
                words = listOf(word("mardi", 100, 150), word("matin", 150, 200)),
                transcript = "mardi matin",
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals(original.id, updated.id)
        assertEquals("mardi matin", updated.recognizedText)
        assertEquals("jeudi matin", updated.editedText)
    }

    @Test
    fun wordlessFallbackDistributesTextAcrossUneditedTurnsWithoutDuplication() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 29,
                revision = 1,
                words = listOf(word("bonjour", 100, 150, 1), word("salut", 150, 200, 2)),
                transcript = "bonjour salut",
            ),
        )

        reducer.apply(
            hypothesis(
                utteranceId = 29,
                revision = 2,
                words = emptyList(),
                transcript = "bonjour salut encore",
            ),
        )

        val turns = reducer.snapshot().turns
        assertEquals(2, turns.size)
        assertEquals(listOf("bonjour", "salut encore"), turns.map { it.recognizedText })
        assertTrue(turns.all { it.automaticParticipantId == null && !it.attributionStable })
        assertEquals(
            "Intervenant à confirmer\nbonjour\n\nIntervenant à confirmer\nsalut encore",
            MeetingProjection.text(reducer.snapshot()),
        )
    }

    @Test
    fun wordlessFallbackKeepsEachEditAndAddsContinuationOnlyAfterTheLastAnchor() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 30,
                revision = 1,
                words = listOf(word("mardi", 100, 150, 1), word("oui", 150, 200, 2)),
                transcript = "mardi oui",
            ),
        )
        val original = reducer.snapshot().turns
        reducer.edit(original[0].id, "jeudi")
        reducer.edit(original[1].id, "non")

        reducer.apply(
            hypothesis(
                utteranceId = 30,
                revision = 2,
                words = emptyList(),
                transcript = "mardi oui soir",
            ),
        )

        val turns = reducer.snapshot().turns
        assertEquals(original.map { it.id }, turns.map { it.id })
        assertEquals(listOf("jeudi", "non soir"), turns.map { it.editedText })
        assertEquals(listOf("mardi", "oui soir"), turns.map { it.recognizedText })
        assertTrue(turns.all { it.automaticParticipantId == null && !it.attributionStable })
    }

    @Test
    fun ambiguousWordlessFallbackPreservesEditAndReportsUnresolvedAlignment() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(hypothesis(utteranceId = 33, revision = 1, words = listOf(word("oui", 100, 200)), transcript = "oui"))
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "bien sûr")

        reducer.apply(
            hypothesis(
                utteranceId = 33,
                revision = 2,
                words = emptyList(),
                transcript = "oui puis oui encore",
            ),
        )

        val updated = reducer.snapshot().turns.single()
        assertEquals(original.id, updated.id)
        assertEquals("bien sûr", updated.editedText)
        assertEquals(MeetingEditAlignmentStatus.UNRESOLVED, reducer.alignmentDiagnostics.last().status)
        assertEquals("Intervenant à confirmer\nbien sûr", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun ambiguousMixedFallbackKeepsUneditedBodyOnceAndStoresRawTextOnEditedTurn() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 35,
                revision = 1,
                words = listOf(word("bonjour", 100, 150, 1), word("oui", 150, 200, 2)),
                transcript = "bonjour oui",
            ),
        )
        val before = reducer.snapshot().turns
        reducer.edit(before[1].id, "non")

        reducer.apply(
            hypothesis(
                utteranceId = 35,
                revision = 2,
                words = emptyList(),
                transcript = "bonjour oui puis oui encore",
            ),
        )

        val turns = reducer.snapshot().turns
        assertEquals(before.map { it.id }, turns.map { it.id })
        assertEquals("bonjour", turns[0].recognizedText)
        assertEquals("bonjour oui puis oui encore", turns[1].recognizedText)
        assertEquals("non", turns[1].editedText)
        assertEquals(MeetingEditAlignmentStatus.UNRESOLVED, reducer.alignmentDiagnostics.last().status)
        assertEquals(
            "Intervenant à confirmer\nbonjour\n\nIntervenant à confirmer\nnon",
            MeetingProjection.text(reducer.snapshot()),
        )
    }

    @Test
    fun ambiguousManualOnlyFallbackPreservesTheOtherSpeakersVisibleBody() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 36,
                revision = 1,
                words = listOf(word("bonjour", 100, 150, 1), word("oui", 150, 200, 2)),
                transcript = "bonjour oui",
            ),
        )
        val before = reducer.snapshot().turns
        val firstParticipantId = requireNotNull(before[0].automaticParticipantId)
        reducer.assign(before[0].id, firstParticipantId)
        reducer.setIgnored(firstParticipantId, ignored = true)

        reducer.apply(
            hypothesis(
                utteranceId = 36,
                revision = 2,
                words = emptyList(),
                transcript = "bonjour oui puis oui encore",
            ),
        )

        val snapshot = reducer.snapshot()
        val turns = snapshot.turns
        assertEquals(before.map { it.id }, turns.map { it.id })
        assertEquals("Intervenant à confirmer\noui", MeetingProjection.text(snapshot))
        assertTrue(turns[0].hasManualAttribution)
        assertEquals(firstParticipantId, turns[0].manualParticipantId)
        assertEquals("bonjour", turns[0].editedText)
        assertEquals("bonjour oui puis oui encore", turns[0].recognizedText)
        assertEquals("oui", turns[1].recognizedText)
        assertNull(turns[1].automaticParticipantId)
        assertFalse(turns[1].attributionStable)
        assertEquals(MeetingEditAlignmentStatus.UNRESOLVED, reducer.alignmentDiagnostics.last().status)
    }

    @Test
    fun turnIdsRemainUniqueWhenAnAnchorMovesAndSameTimeChannelsAreBorn() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 31,
                revision = 1,
                words = listOf(word("un", 100, 200, 1), word("deux", 200, 300, 1)),
                transcript = "un deux",
            ),
        )
        val originalId = reducer.snapshot().turns.single().id
        reducer.apply(
            hypothesis(
                utteranceId = 31,
                revision = 2,
                words = listOf(word("deux", 200, 300, 1)),
                transcript = "deux",
            ),
        )
        reducer.apply(
            hypothesis(
                utteranceId = 31,
                revision = 3,
                words = listOf(word("un", 100, 200, 2), word("deux", 200, 300, 1)),
                transcript = "un deux",
            ),
        )

        val movedAnchorTurns = reducer.snapshot().turns
        assertEquals(2, movedAnchorTurns.size)
        assertEquals(originalId, movedAnchorTurns.single { it.recognizedText == "deux" }.id)
        assertEquals(2, movedAnchorTurns.map { it.id }.distinct().size)
        assertNotEquals(originalId, movedAnchorTurns.single { it.recognizedText == "un" }.id)

        reducer.apply(
            hypothesis(
                utteranceId = 32,
                revision = 1,
                words = listOf(word("gauche", 400, 500, 1), word("droite", 400, 500, 2)),
                transcript = "gauche droite",
            ),
        )
        val sameTimeIds = reducer.snapshot().turns.filter { it.utteranceId == 32L }.map { it.id }
        assertEquals(2, sameTimeIds.distinct().size)
    }

    @Test
    fun wordlessEditDoesNotAbsorbContinuationAssignedToAnotherSpeaker() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 34,
                revision = 1,
                words = emptyList(),
                transcript = "mardi",
                processedMs = 200,
            ),
        )
        val original = reducer.snapshot().turns.single()
        reducer.edit(original.id, "jeudi")

        reducer.apply(
            hypothesis(
                utteranceId = 34,
                revision = 2,
                words = listOf(word("mardi", 100, 200, 1), word("matin", 300, 400, 2)),
                transcript = "mardi matin",
                processedMs = 400,
            ),
        )

        val turns = reducer.snapshot().turns
        assertEquals(2, turns.size)
        val editedTurn = turns.single { it.id == original.id }
        val continuation = turns.single { it.recognizedText == "matin" }
        assertEquals("jeudi", editedTurn.editedText)
        assertEquals("meeting-a:participant:1", editedTurn.automaticParticipantId)
        assertTrue(continuation.automaticParticipantId != null)
        assertEquals("Personne 1\njeudi\n\nPersonne 2\nmatin", MeetingProjection.text(reducer.snapshot()))
    }

    @Test
    fun wordlessLaterUtteranceFollowsEarlierAudioEvenWhenItsRevisionArrivesLate() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 40,
                revision = 1,
                words = listOf(word("premier", 100, 200, 1)),
                transcript = "premier",
            ),
        )
        reducer.apply(
            hypothesis(
                utteranceId = 41,
                revision = 1,
                words = emptyList(),
                transcript = "suivant",
                processedMs = 500,
            ),
        )

        assertEquals(listOf(40L, 41L), reducer.snapshot().turns.map { it.utteranceId })

        reducer.apply(
            hypothesis(
                utteranceId = 40,
                revision = 2,
                words = listOf(word("premier", 100, 200, 1)),
                transcript = "premier.",
            ),
        )

        assertEquals(listOf(40L, 41L), reducer.snapshot().turns.map { it.utteranceId })
        assertEquals(
            "Personne 1\npremier\n\nIntervenant à confirmer\nsuivant",
            MeetingProjection.text(reducer.snapshot()),
        )
    }

    @Test
    fun aLateEndingWordPreventsTheWholeGroupFromBeingMarkedStableOrIgnored() {
        val reducer = MeetingTranscriptReducer("meeting-a", RUN_ID)
        reducer.apply(
            hypothesis(
                utteranceId = 42,
                revision = 1,
                words = listOf(word("premier", 100, 500, 1), word("retardé", 150, 200, 1)),
                transcript = "premier retardé",
                processedMs = 500,
                stableThrough = 300,
            ),
        )
        val turn = reducer.snapshot().turns.single()
        val participantId = requireNotNull(turn.automaticParticipantId)
        reducer.setIgnored(participantId, true)

        assertFalse(turn.attributionStable)
        assertEquals("Intervenant à confirmer\npremier retardé", MeetingProjection.text(reducer.snapshot()))
    }

    private fun hypothesis(
        utteranceId: Long,
        revision: Long,
        words: List<MeetingWord>,
        transcript: String,
        runId: String = RUN_ID,
        isFinal: Boolean = false,
        stableThrough: Long = 1_000,
        processedMs: Long = 1_000,
    ) = MeetingHypothesis(
        runId = runId,
        utteranceId = utteranceId,
        revision = revision,
        words = words,
        transcript = transcript,
        isFinal = isFinal,
        stableSpeakerThroughMs = stableThrough,
        audioProcessedMs = processedMs,
    )

    private fun word(text: String, startMs: Long, endMs: Long, channel: Int = 1) =
        MeetingWord(text, startMs, endMs, channel)

    private fun fixtureTimedWords(text: String, startMs: Long): Pair<List<MeetingWord>, Long> {
        val words = Regex("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}\\s]").findAll(text).mapIndexed { index, match ->
            val wordStart = startMs + index * 160L
            word(match.value, wordStart, wordStart + 120L)
        }.toList()
        return words to (startMs + words.size * 160L + 40L)
    }

    private fun attachTerminalPunctuation(words: List<MeetingWord>): List<MeetingWord> {
        val punctuation = words.last()
        val lastLexical = words[words.lastIndex - 1]
        return words.dropLast(2) + lastLexical.copy(
            text = lastLexical.text + punctuation.text,
            endMs = punctuation.endMs,
        )
    }

    private companion object {
        const val RUN_ID = "run-a"
    }
}
