package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class LightTextCleanupTest {
    @Test fun removesFrenchAndEnglishHesitationsWithoutTouchingOtherWords() {
        assertEquals("Je veux du lait", LightTextCleanup.apply("Euh, je je veux, euh, du lait"))
        assertEquals("I need the file", LightTextCleanup.apply("Um, I I need uh the file"))
        assertEquals("Demain. On reprend", LightTextCleanup.apply("Demain. Euh, On reprend"))
        assertEquals("Je veux du lait", LightTextCleanup.apply("Euh. Je veux du lait"))
        assertEquals("Je veux du lait", LightTextCleanup.apply("Je euh euh veux du lait"))
    }

    @Test fun quotationsMentionsIdentifiersAndVocabularyArePreserved() {
        listOf("Le mot euh est une hésitation", "The word um is present", "Il écrit « euh je je »", "Il dit \"euh\"", "Il dit 'euh'",
            "URL https://euh.fr et fichier um.txt", "Dossier /tmp/euh/test et um@example.com", "Code UM", "euh").forEach {
            assertEquals(it, LightTextCleanup.apply(it))
        }
        assertEquals("Le nom Euh est correct", LightTextCleanup.apply("Le nom Euh est correct", listOf("Euh")))
        assertEquals("Citer « euh sans fermer", LightTextCleanup.apply("Citer « euh sans fermer"))
    }

    @Test fun meaningfulAndReflexiveRepetitionsRemainUntouched() {
        listOf("C'est très très bien", "Non non je ne veux pas", "Nous nous retrouvons demain", "Vous vous trompez", "Oui oui", "1 1 2 3", "Elle, elle viendra").forEach {
            assertEquals(it, LightTextCleanup.apply(it))
        }
    }

    @Test fun onlyExplicitQuestionsGetQuestionMarks() {
        assertEquals("Est-ce que le fichier est prêt ?", LightTextCleanup.apply("Est-ce que le fichier est prêt."))
        assertEquals("Peux-tu venir demain ?", LightTextCleanup.apply("Peux-tu venir demain"))
        assertEquals("Bonjour. Est-ce que tu viens ?", LightTextCleanup.apply("Bonjour. Est-ce que tu viens."))
        assertEquals("Can you send the 2 files?", LightTextCleanup.apply("Can you send the 2 files."))
        listOf("Je me demande si tu viens", "Tu viens demain", "Est-ce que tu viens !", "Il demande « est-ce que tu viens »", "Can you open https://euh.fr").forEach {
            assertEquals(it, LightTextCleanup.apply(it))
        }
    }

    @Test fun cleanupIsIdempotentAndPreservesAllOtherContent() {
        val source = "Euh, je je ne dois pas envoyer les 23 dossiers à Maëlys. Est-ce que tu peux attendre vendredi."
        val cleaned = LightTextCleanup.apply(source)
        assertTrue(cleaned.contains("ne dois pas envoyer les 23 dossiers à Maëlys"))
        assertEquals(cleaned, LightTextCleanup.apply(cleaned))
        assertEquals("Un paragraphe\n\nUn autre", LightTextCleanup.apply("Un paragraphe\n\nUn autre"))
    }
}
