package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class FinalPunctuationTest {
    @Test fun completesProseParagraphsAtPublicationOnly() {
        assertEquals("Test numéro 2.", FinalPunctuation.apply("Test numéro 2", "cleanup"))
        assertEquals("Ça fonctionne.\n\nOn recommence demain.  ", FinalPunctuation.apply("Ça fonctionne\n\nOn recommence demain  ", "cleanup"))
    }
    @Test fun preservesExistingPunctuationAndSpecialTails() {
        listOf("Ça marche !", "C’est prêt…", "Merci 😊", "Consulte https://exemple.fr/test", "Écrire user@example.com", "Lire rapport.pdf", "Code AB-23", "Appelez 06 12 34 56 78", "Il a dit « demain »", "M. Martin").forEach {
            assertEquals(it, FinalPunctuation.apply(it, "cleanup"))
        }
    }
    @Test fun emailAddsPointsOnlyToBody() {
        val text = "Bonjour Julie,\n\nVoici le document demandé\n\nCordialement\n\nM. Martin"
        assertEquals(text.replace("demandé", "demandé."), FinalPunctuation.apply(text, "email"))
        val english = "Hi Zoë,\n\nThe test works\n\nBest regards,\nMae"
        assertEquals(english.replace("works", "works."), FinalPunctuation.apply(english, "email"))
        assertEquals("Merci de vérifier le dossier.", FinalPunctuation.apply("Merci de vérifier le dossier", "email"))
    }
    @Test fun listAndCustomFormatsKeepTheirExactPresentation() {
        val text = "• De la farine\n• Du lait"
        assertEquals(text, FinalPunctuation.apply(text, "list"))
        assertEquals("Un titre", FinalPunctuation.apply("Un titre", "custom-id"))
    }
    @Test fun greetingsSignaturesAndPostscriptAreDistinctFromProse() {
        assertEquals("Bonjour voici mon test.", FinalPunctuation.apply("Bonjour voici mon test", "email"))
        listOf("Bonjour Julie", "Bonjour madame", "Bonjour à tous", "Dear team", "Best regards\nMary Jane").forEach {
            assertEquals(it, FinalPunctuation.apply(it, "email"))
        }
        assertEquals("Dr. Martin confirme sa venue.", FinalPunctuation.apply("Dr. Martin confirme sa venue", "cleanup"))
        assertEquals("Merci\n\nAlice\n\nP.S. Pense au dossier.", FinalPunctuation.apply("Merci\n\nAlice\n\nP.S. Pense au dossier", "email"))
    }
}
