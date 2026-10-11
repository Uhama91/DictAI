package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechCapitalizationTest {
    @Test fun punctuationOnlyStartsANewSentenceWhenItSaysSo() {
        for (boundary in listOf(".", "!", "?", "…", "\n", "\r\n", ":")) {
            assertEquals("Tu vois", SpeechCapitalization.normalize("Tu vois", after = "Je réfléchis$boundary"))
        }
        for (boundary in listOf("", ",", ";", " ", "\u00a0")) {
            assertEquals("tu vois", SpeechCapitalization.normalize("Tu vois", after = "Je réfléchis$boundary"))
        }
        assertEquals("Je réfléchis. Tu vois ! Je continue\nEnsuite on revient",
            SpeechCapitalization.normalize("Je réfléchis. Tu vois ! Je continue\nEnsuite on revient"))
    }

    @Test fun namesAcronymsAndTechnicalLiteralsRetainTheirCasing() {
        val text = "Je consulte Marie à Paris et Le Mans avec L’Oréal et D'Artagnan puis API et DictAI " +
            "sur https://Tu.fr/Je dans Tu.txt avec Tu_variable et Tu123"
        assertEquals(text, SpeechCapitalization.normalize(text))
    }

    @Test fun quotedWordsAndUnclosedQuotesStayVerbatimAcrossPartialResults() {
        for (text in listOf("Je cite « Tu Je Voilà » et tu vois", "Je cite \"Tu Je\"", "Je cite `Tu Je`", "Je cite « Tu Je")) {
            assertEquals(text, SpeechCapitalization.normalize(text))
        }
    }

    @Test fun personalVocabularyProtectionChangesWithoutStaleDecisions() {
        val text = "Je parle de Voilà et Tu Vois"
        assertEquals(text, SpeechCapitalization.normalize(text, protectedTerms = listOf("Voilà", "Tu Vois")))
        assertEquals("Je parle de voilà et tu vois", SpeechCapitalization.normalize(text))
        assertEquals(text, SpeechCapitalization.normalize(text, protectedTerms = listOf("Voilà", "Tu Vois")))
        assertEquals("Je parle de voilà et tutu", SpeechCapitalization.normalize("Je parle de Voilà et tutu", protectedTerms = listOf("Tu")))
    }

    @Test fun accentedContinuationsAndBothKindsOfApostropheAreCorrected() {
        assertEquals("C’est ça et voilà alors j’ai repris l'application qu’il utilise",
            SpeechCapitalization.normalize("C’est Ça et Voilà Alors J’ai repris L'application Qu’il utilise"))
    }

    @Test fun englishRecognitionIsNotChangedByFrenchRules() {
        val text = "I work with La Company and Tu Linux"
        assertEquals(text, SpeechCapitalization.normalize(text, language = DictationLanguage.ENGLISH))
    }

    @Test fun emptyAndFirstWordsRemainUnchanged() {
        assertEquals("", SpeechCapitalization.normalize("", after = "Je continue"))
        assertEquals("Voilà", SpeechCapitalization.normalize("Voilà"))
        assertEquals("TU VOIS", SpeechCapitalization.normalize("TU VOIS", after = "Je continue"))
    }
}
