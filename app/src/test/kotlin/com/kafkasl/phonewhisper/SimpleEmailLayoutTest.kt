package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SimpleEmailLayoutTest {
    private fun request(text: String, terms: List<String> = emptyList()) = LocalFormatRequest(
        text, "Mail", "French", terms, LocalLayoutKind.EMAIL, LocalFormatValidation.GEMMA_PROJECTION, true)

    @Test fun longMailRemainsStructurableButProductionSendsItToGemma() {
        val source = LONG_MAIL
        val request = request(source, listOf("Monsieur le Testeur"))
        assertNull(request.directOutput())
        val output = request.acceptOutput(SimpleEmailLayout.format(source))!!
        assertEquals("Bonjour,\n\n" + source.substringAfter("Bonjour, ").substringBefore(" Cordialement,") +
            "\n\nCordialement,\n\nMonsieur le Testeur", output)
        assertEquals(words(source), words(output))
        assertTrue(output.contains("ne pas fournir de données"))
    }

    @Test fun preservesNegationDigitsAccentsAndTechnicalPunctuationInEnglish() {
        val source = "Hello Nora, please keep the 2 blue folders and do not delete Maëlys’s originals at user@example.com. Best regards, Eli"
        assertEquals("Hello Nora,\n\nplease keep the 2 blue folders and do not delete Maëlys’s originals at user@example.com.\n\nBest regards,\n\nEli",
            request(source, listOf("2", "Maëlys", "user@example.com")).directOutput())
    }

    @Test fun noPunctuationMailAndHonorificAreSupported() {
        val text = "Bonjour voici mon nouveau message pour confirmer les 23 élèves cordialement M. Martin"
        assertEquals("Bonjour,\n\nvoici mon nouveau message pour confirmer les 23 élèves.\n\ncordialement,\n\nM. Martin", request(text).directOutput())
    }

    @Test fun ambiguousOrQuotedClosingsAndExistingParagraphsRemainForGemma() {
        listOf(
            "Bonjour voici un message sans signature à mettre en forme",
            "Bonjour voici un message disant cordialement à ton père",
            "Bonjour voici un message où je veux dire cordialement M. Martin",
            "Bonjour voici le texte « cordialement M. Martin »",
            "Bonjour voici un message cordialement Julie et ensuite cordialement M. Martin",
            "Bonjour,\n\nVoici le message à conserver. Cordialement, M. Martin",
            "Bonjour voici un message à confirmer cordialement M. Martin P.S. apporte le dossier",
            "Bonjour voici le document utile pour demain cordialement",
        ).forEach { assertNull(it, request(it).directOutput()) }
    }

    @Test fun constrainedAndUnsupportedLayoutsDoNotUseNewDirectPath() {
        assertNull(request(LONG_MAIL).copy(simpleEmailLayout = false).directOutput())
        assertNull(request(LONG_MAIL).copy(validation = LocalFormatValidation.EXACT_LAYOUT).directOutput())
        assertNull(request(LONG_MAIL).copy(layoutKind = LocalLayoutKind.LIST).directOutput())
        assertNull(request(LONG_MAIL, listOf("mot absent")).directOutput())
        assertNull(request("Bonjour voici " + "test ".repeat(520) + "cordialement Julie").directOutput())
    }

    @Test fun bypassesEvenAnUnresponsiveObsoleteGenerationAndReportsNoNativeCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? {
                entered.countDown(); release.await(2, TimeUnit.SECONDS); return null
            }
            override fun cancel() = Unit // Simulate a native call that does not stop immediately.
        }
        try {
            LocalFormattingSession(backend).use { session ->
                session.offer(request("Bonjour voici mon mail encore incomplet"))
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val mail = request("Bonjour voici mon nouveau message pour confirmer les 23 élèves cordialement M. Martin")
                val result = session.finish(mail, 1L) { fail("No generated fragment") }
                assertEquals(mail.directOutput(), result)
                assertEquals("direct", session.lastFinish?.route)
                assertEquals(false, session.lastFinish?.nativeStarted)
                assertEquals("applied", session.lastFinish?.outcome)
            }
        } finally { release.countDown() }
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(text).map { it.value }.toList()

    companion object {
        const val LONG_MAIL = "Bonjour, voici le nouveau test concernant la génération d'un nouveau mail qui doit être mis en forme par le modèle en local, donc le but, c'est de rédiger un mail suffisamment long pour solliciter le travail du modèle en local et qu'il fasse la mise en forme du texte comme il faut l'intérêt d'avoir la mise en forme en locale, c'est aussi de ne pas fournir de données au modèle cloud et d'avoir une génération peut-être un peu plus lente, mais l'idée, c'est aussi d'avoir une latence suffisamment basse pour que l'expérience reste agréable pour un utilisateur qui veut envoyer un mail et que le post traitement soit correct aussi je ne sais pas si le mail est suffisamment long là actuellement mais l'idée est vraiment que je puisse réaliser ce travail là en étant suffisamment satisfait au niveau latence. Cordialement, Monsieur le Testeur"
    }
}
