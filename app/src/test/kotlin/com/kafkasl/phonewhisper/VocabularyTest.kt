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
}
