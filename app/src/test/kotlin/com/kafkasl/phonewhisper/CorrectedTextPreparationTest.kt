package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class CorrectedTextPreparationTest {
    private fun prepare(text: String, terms: List<String> = emptyList(), ranges: List<IntRange> = emptyList()) =
        CorrectedTextPreparation.prepare(text, "corrected", terms, ranges)

    @Test fun hesitationRemovalDoesNotDependOnWhatTheModelChoosesToCopy() {
        val source = "Euh, je consulte Grok et euh je garde les 23 dossiers."
        val request = LocalFormatRequest(source, "", "French", listOf("Grok"), LocalLayoutKind.TEXT,
            LocalFormatValidation.GEMMA_EDITING)
        // Reproduce the existing validator accepting an unchanged result with its fillers.
        assertEquals(source, request.acceptOutput(source))
        val prepared = prepare(source, listOf("Grok"))
        assertEquals("Je consulte Grok et je garde les 23 dossiers.", prepared.text)
        assertEquals(2, prepared.removed)
        assertEquals(prepared.text, request.copy(text = prepared.text).acceptOutput(prepared.text))
    }

    @Test fun modelFailureStillPublishesThePreparedSource() {
        val source = prepare("Je veux euh consulter Grok.", listOf("Grok"))
        val request = LocalFormatRequest(source.text, "", "French", listOf("Grok"), LocalLayoutKind.TEXT,
            LocalFormatValidation.GEMMA_EDITING)
        for (output in listOf(null, "", "Voici un résumé inventé.")) {
            val published = request.acceptOutput(output) ?: source.text
            assertEquals("Je veux consulter Grok.", published)
        }
    }

    @Test fun otherFormatsAreUnchangedAndTextCleanupPreferenceIsIndependent() {
        val source = "Euh, du lait."
        for (format in listOf("cleanup", "email", "list", "custom"))
            assertEquals(source, CorrectedTextPreparation.prepare(source, format).text)
        assertEquals("Du lait.", prepare(source).text)
    }

    @Test fun quotationsMentionsTechnicalTokensAndVocabularyStayVerbatim() {
        for (source in listOf("Le mot euh est présent.", "Il dit « euh ».", "Il écrit \"euh\".",
            "The word um is present.", "Le dossier /tmp/euh et https://euh.fr et um@example.org.",
            "Citer « euh sans fermer", "Il dit 'j'ai euh envie de venir'.", "Code UM", "euh")) {
            assertEquals(source, prepare(source).text)
        }
        assertEquals("Le nom Euh reste ici.", prepare("Le nom Euh reste euh ici.", listOf("Euh reste")).text)
    }

    @Test fun onlyActualManualSpansAreKeptWhileOtherFillersAreRemoved() {
        val source = "Je garde euh ici et euh ensuite."
        val first = source.indexOf("euh")
        assertEquals("Je garde euh ici et ensuite.", prepare(source, ranges = listOf(first..first + 2)).text)
        assertEquals("Titre\n  suite.", prepare("Titre\n  euh, suite.", ranges = listOf(0..7)).text)
        assertEquals("Je viens.", prepare("Je euh, viens.", ranges = listOf(0..2)).text)
    }

    @Test fun repeatedFillersPunctuationParagraphsAndEnglishStayWellFormed() {
        val cases = listOf(
            "Euh. Je veux euh euh du lait." to "Je veux du lait.",
            "Je viens, euh, demain.\n\nUm, I need uh the file." to "Je viens, demain.\n\nI need the file.",
            "Je veux du riz, euh des pâtes." to "Je veux du riz, des pâtes.",
            "Je veux du riz euh, des pâtes." to "Je veux du riz, des pâtes.",
            "Je viens euh." to "Je viens.",
            "Je ne veux pas euh supprimer les 23 dossiers de Grok." to "Je ne veux pas supprimer les 23 dossiers de Grok.",
            "Euh, c'est très très bien et nous nous retrouvons demain." to "C'est très très bien et nous nous retrouvons demain."
        )
        for ((source, expected) in cases) {
            val cleaned = prepare(source)
            assertEquals(source, expected, cleaned.text)
            assertEquals(cleaned.text, prepare(cleaned.text).text)
            assertEquals(0, prepare(cleaned.text).removed)
        }
    }
}
