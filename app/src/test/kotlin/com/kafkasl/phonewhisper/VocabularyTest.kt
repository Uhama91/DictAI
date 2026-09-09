package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyTest {
    @Test fun replacesWholeWordCaseInsensitive() {
        val c = listOf("didi" to "Dydy")
        assertEquals("Salut Dydy ça va", Vocabulary.applyCorrectionsTo("Salut didi ça va", c))
        assertEquals("Dydy arrive", Vocabulary.applyCorrectionsTo("DIDI arrive", c))
    }
    @Test fun doesNotReplaceInsideWord() {
        val c = listOf("did" to "X")
        assertEquals("didier reste", Vocabulary.applyCorrectionsTo("didier reste", c))
    }
    @Test fun replacesAccentedCaseInsensitive() {
        val c = listOf("élise" to "Elise")
        assertEquals("Bonjour Elise", Vocabulary.applyCorrectionsTo("Bonjour ÉLISE", c))
    }
    @Test fun rulesDoNotCascadeAndLongPhrasesWin() {
        assertEquals("Marie Marion", Vocabulary.applyCorrectionsTo("mari Marie", listOf("mari" to "Marie", "Marie" to "Marion")))
        assertEquals("NYC puis New York", Vocabulary.applyCorrectionsTo("new york puis york", listOf("york" to "New York", "new york" to "NYC")))
    }
    @Test fun savingPreservesRawTextAndRejectsConflicts() {
        val raw = "# Mes noms\ndidi => Dydy\n"
        assertEquals(raw + "mari => Marie", Vocabulary.prepareCorrection(raw, " mari ", " Marie ").raw)
        assertEquals(Vocabulary.AddResult.ALREADY_PRESENT, Vocabulary.prepareCorrection("mari => Marie", "MARI", "Marie").result)
        assertEquals(Vocabulary.AddResult.CONFLICT, Vocabulary.prepareCorrection("mari => Marie", "Mari", "Marion").result)
        for ((source, target) in listOf("mari" to "", "mari" to "mari", "a\nb" to "c", "a" to "b => c")) {
            assertEquals(Vocabulary.AddResult.INVALID, Vocabulary.prepareCorrection("", source, target).result)
        }
    }
    @Test fun noReplacementInsideIdentifiersDigitsOrCombiningMarks() {
        val source = "cloud cloud2 my_cloud cloud\u0301 Cloud."
        assertEquals("Claude cloud2 my_cloud cloud\u0301 Claude.", Vocabulary.applyCorrectionsTo(source, listOf("cloud" to "Claude")))
    }
    @Test fun phrasesAllowAsrSpacingButNotParagraphBoundaries() {
        val rules = listOf("ma yotte" to "Maillot")
        assertEquals("Maillot, Maillot et ma\nyotte", Vocabulary.applyCorrectionsTo("ma  yotte, MA\u00a0YOTTE et ma\nyotte", rules))
        assertEquals(Vocabulary.AddResult.ALREADY_PRESENT, Vocabulary.prepareCorrection("ma yotte => Maillot", "ma  yotte", "Maillot").result)
    }
    @Test fun aNewRuleAppliesToLiveSpeechWithoutChangingTheManualCorrection() {
        val transcript = EditableTranscript()
        assertEquals("Je parle à ma yotte", transcript.update("Je parle à ma yotte"))
        transcript.edit("Je parle à Maillot")
        val rules = listOf("ma yotte" to "Maillot", "cloud" to "Claude")
        fun stream(raw: String) = transcript.update(raw) { Vocabulary.applyCorrectionsTo(it, rules) }
        assertEquals("Je parle à Maillot de Claude", stream("Je parle à ma yotte de cloud"))
        assertEquals("Je parle à Maillot de Claude et Maillot", stream("Je parle à ma yotte de cloud et ma yotte"))
    }
    @Test fun aPhraseCanSpanTwoRecognitionUpdates() {
        val rules = listOf("ma yotte" to "Maillot")
        assertEquals("Bonjour ma", Vocabulary.applyCorrectionsTo("Bonjour ma", rules))
        assertEquals("Bonjour Maillot", Vocabulary.applyCorrectionsTo("Bonjour ma yotte", rules))
        assertEquals("Bonjour ma yotte", Vocabulary.applyCorrectionsTo("Bonjour ma yotte", emptyList()))
    }

}
