package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class GemmaConservativeEditingTest {
    private fun request(source: String, kind: LocalLayoutKind = LocalLayoutKind.EMAIL, terms: List<String> = emptyList()) =
        LocalFormatRequest(source, "", "French", terms, kind, LocalFormatValidation.GEMMA_EDITING)

    @Test fun realPhoneOutputRejectedIn084IsAcceptedWithoutRestoringTheSpellingMistake() {
        fun fixture(name: String) = javaClass.getResourceAsStream("/gemma-editing/$name.txt")!!.bufferedReader().use { it.readText() }
        val source = fixture("phone-mail-source")
        val raw = fixture("phone-mail-raw")
        assertEquals(raw, request(source, terms = listOf("Monsieur le Testeur")).acceptOutput(raw))
    }

    @Test fun actualHostOmissionsAreRestoredWithoutLeavingASentenceCapitalAfterDonc() {
        fun fixture(name: String) = javaClass.getResourceAsStream("/gemma-editing/$name.txt")!!.bufferedReader().use { it.readText() }
        val source = fixture("phone-mail-source")
        val candidate = fixture("host-mail-omissions")
        val accepted = request(source, terms = listOf("Monsieur le Testeur")).acceptOutput(candidate)
        assertNotNull(accepted)
        assertTrue(accepted!!.contains("donc le but"))
        assertTrue(accepted.contains("c'est"))
        assertTrue(accepted.contains("post-traitement"))
    }

    @Test fun paragraphMeasureExcludesTheGreetingAndSignature() {
        val body = "Bonjour Nora,\n\nLa réunion aura lieu demain.\n\nLe budget sera examiné vendredi.\n\nCordialement,\nEli"
        assertEquals(2, ProseParagraphs.count(body, LocalLayoutKind.EMAIL))
        assertEquals(1, ProseParagraphs.count(body.replace("demain.\n\nLe", "demain. Le"), LocalLayoutKind.EMAIL))
        assertEquals(2, ProseParagraphs.count("Premier sujet.\n\nAutre sujet.", LocalLayoutKind.TEXT))
    }

    @Test fun phoneGrammarCorrectionCanBePublishedWithBodyParagraphsAndCapitalization() {
        val source = "Bonjour voici le résultat de notre test de mise en forme en locale. Les documents sont prêt pour demain. Merci Anaïs"
        val candidate = "Bonjour,\n\nVoici le résultat de notre test de mise en forme en local.\n\nLes documents sont prêts pour demain.\n\nMerci,\nAnaïs"
        assertEquals(candidate, request(source, terms = listOf("Anaïs")).acceptOutput(candidate))
        assertNull(request(source).copy(validation = LocalFormatValidation.GEMMA_PROJECTION).acceptOutput(candidate))
    }

    @Test fun removesFillersFalseStartsAndAdjacentWordOrPhraseRepetitions() {
        val cases = listOf(
            "Bonjour euh voici le le dossier merci Léa" to "Bonjour,\n\nVoici le dossier.\n\nMerci Léa",
            "Je je souhaite envoyer la le dossier demain" to "Je souhaite envoyer le dossier demain.",
            "We need we need the the documents tomorrow" to "We need the documents tomorrow.",
            "Je prépare le dossier dossier demain" to "Je prépare le dossier demain.",
        )
        for ((source, output) in cases) assertEquals(source, output, request(source).acceptOutput(output))
    }

    @Test fun acceptsLimitedSpellingInFrenchAndEnglish() {
        val cases = listOf("Les eleves arrivent à l'acceuil" to "Les élèves arrivent à l'accueil.",
            "Please recieve the seperate document" to "Please receive the separate document.")
        for ((source, output) in cases) assertEquals(output, request(source).acceptOutput(output))
    }

    @Test fun negationsNamesNumbersConditionsAndTechnicalTokensRemainProtected() {
        val source = "Bonjour Karim ne pas envoyer les 23 documents avant vendredi à info@example.org merci Maëlys"
        val output = "Bonjour Karim,\n\nNe pas envoyer les 23 documents avant vendredi à info@example.org.\n\nMerci Maëlys"
        val req = request(source, terms = listOf("Karim", "Maëlys"))
        assertNotNull(req.acceptOutput(output))
        listOf(output.replace("Ne pas", ""), output.replace("23", "32"), output.replace("23", "vingt-trois"),
            output.replace("avant", "après"), output.replace("Maëlys", "Maëlle"),
            output.replace("example.org", "example.com"), output + "\nSincerely,\n[Your Name]")
            .forEach { assertNull(it, req.acceptOutput(it)) }
    }

    @Test fun preservesQuotedSpellingAndPersonalNamesWhileCapitalizingTheBody() {
        val source = "Bonjour Maëlys voici le texte « le dossier » et le nom Jean-Baptiste merci Léa"
        val candidate = "Bonjour MAËLYS,\n\nVoici le texte « LE DOSSIER » et le nom JEAN-BAPTISTE.\n\nMerci LÉA"
        val accepted = request(source, terms = listOf("Maëlys", "Léa")).acceptOutput(candidate)
        assertNotNull(accepted)
        assertTrue(accepted!!.contains("« le dossier »"))
        assertTrue(accepted.contains("Jean-Baptiste"))
        assertTrue(accepted.contains("Voici"))
    }

    @Test fun keepsTheChosenNumberPresentationForAnExactlyEquivalentCardinal() {
        val source = "Conserver les 2 dossiers et les 3 copies pour demain"
        val output = "Conserver les deux dossiers et les trois copies pour demain."
        assertEquals("Conserver les 2 dossiers et les 3 copies pour demain.", request(source).acceptOutput(output))
        assertNull(request(source).acceptOutput(output.replace("trois", "quatre")))
        assertNull(request("Écrire « 2 » sur le dossier").acceptOutput("Écrire « deux » sur le dossier"))
        assertNull(request("Conserver 2.5 litres demain").acceptOutput("Conserver two.five litres demain"))
        assertNull(request("Prendre un des dossiers avec soin demain").acceptOutput("Prendre des dossiers avec soin demain"))
    }

    @Test fun protectsQuotesVocabularyEmphasisAndReflexives() {
        val cases = listOf(
            "Conserver le mot euh dans cette phrase" to "Conserver le mot dans cette phrase",
            "Il a dit « le le dossier » hier" to "Il a dit « le dossier » hier",
            "Il dit 'j'ai euh envie de venir'." to "Il dit 'j'ai envie de venir'.",
            "Nous nous demandons si c'est très très utile" to "Nous demandons si c'est très utile",
            "Non non je ne veux pas cela" to "Non je ne veux pas cela",
            "I had had time to read that that day" to "I had time to read that day",
        )
        for ((source, output) in cases) assertNull(source, request(source).acceptOutput(output))
        assertNull(request("Conserver le terme locale ici", terms = listOf("locale"))
            .acceptOutput("Conserver le terme local ici"))
    }

    @Test fun doesNotAcceptNewContentParaphraseTruncationOrQuestionsAnsweredByTheModel() {
        val source = "Pouvez-vous confirmer les détails de cette réunion et prévenir chaque participant demain"
        listOf("Oui, je peux confirmer les détails.", "Pouvez-vous annuler les détails de cette réunion et prévenir chaque participant demain",
            "Pouvez-vous confirmer les détails de cette réunion", source + " à Paris")
            .forEach { assertNull(it, request(source).acceptOutput(it)) }
    }

    @Test fun restoresSparseOrdinaryOmissionsAndCliticWithoutRejectingCompoundSpelling() {
        val source = "Bonjour voici un nouveau test de mise en forme en locale donc le but c'est de conserver tous les mots utilisés dans cette phrase qui parle du post traitement et de vérifier aussi la présentation du texte qui sera envoyé là demain avec les documents prévus cordialement Julie"
        val output = "Bonjour,\n\nVoici un nouveau test de mise en forme en local donc le but est de conserver tous les mots utilisés dans cette phrase qui parle du post-traitement et de vérifier aussi la présentation du texte qui sera envoyé là demain avec les documents prévus.\n\nCordialement,\nJulie"
        val accepted = request(source).acceptOutput(output)
        assertNotNull(accepted)
        assertTrue(accepted!!.contains("c'est"))
        assertTrue(accepted.contains("post-traitement"))
    }

    @Test fun sparseOmissionCanStillBeRestoredAlongsideAnAgreementCorrection() {
        val source = "Bonjour voici un nouveau test assez détaillé de mise en forme en locale pour vérifier les paragraphes du message et garder tous les éléments dans leur ordre initial normalement cordialement Julie"
        val output = "Bonjour,\n\nVoici un nouveau test assez détaillé de mise en forme en local pour vérifier les paragraphes du message et garder tous les éléments dans leur ordre initial.\n\nCordialement,\nJulie"
        val accepted = request(source).acceptOutput(output)
        assertNotNull(accepted)
        assertTrue(accepted!!.contains("normalement"))
        assertTrue(accepted.contains("en local"))
    }

    @Test fun explicitCorrectedTextUsesGemmaWithTenSecondsForLongInputs() {
        val short = request("OK, ça marche.", LocalLayoutKind.TEXT)
        assertNull(short.directOutput())
        val long = request("Ceci est une phrase détaillée. ".repeat(14), LocalLayoutKind.TEXT)
        assertTrue(long.isLongText())
        assertNull(long.directOutput())
        assertEquals(10_000L, long.finalWaitMs())
        assertEquals(10_000L, long.copy(layoutKind = LocalLayoutKind.EMAIL).finalWaitMs())
        assertEquals(5_000L, long.copy(layoutKind = LocalLayoutKind.LIST).finalWaitMs())
        assertEquals(8_000L, short.copy(layoutKind = LocalLayoutKind.EMAIL).finalWaitMs())
    }

    @Test fun editedGenerationIsNotPublishedWhileStillStreaming() {
        val req = request("Bonjour euh voici le le dossier merci Léa")
        val output = "Bonjour,\n\nVoici le dossier.\n\nMerci Léa"
        assertNotNull(req.acceptOutput(output))
        assertNull(req.previewOutput(output))
        assertNull(req.acceptOutput("<think>plan</think>$output"))
    }
}
