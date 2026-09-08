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
}
