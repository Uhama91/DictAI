package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class GemmaEmailRestorationTest {
    private val source = "Bonjour, voici un nouveau test pour voir si le format a bien été pris en compte lors de la création et la génération de ce nouveau texte qui sans être mis en forme sous forme de mail. La première fois quand ça a été un message assez court la mise en forme a été réalisée, la deuxième, la troisième quatrième fois n'a pas fonctionné comme il faut la mise en forme n'a pas été prise en compte, et en voici la preuve à nouveau normalement, cordialement, Monsieur le Testeur"
    private fun request(text: String = source) = LocalFormatRequest(text, "Mail", "French",
        listOf("Monsieur le Testeur"), LocalLayoutKind.EMAIL, LocalFormatValidation.GEMMA_PROJECTION)
    private fun formatted(text: String = source) = text.replace("Bonjour, ", "Bonjour,\n\n")
        .replace("cordialement, ", "cordialement,\n\n")
        .replace("normalement, cordialement", "normalement.\n\ncordialement")

    @Test fun reproducedLongMailRestoresNormallyBeforeTheClosing() {
        val raw = formatted().replace("nouveau normalement.", "nouveau.")
        val output = request().acceptOutput(raw)
        assertEquals(formatted(), output)
        assertTrue(output!!.contains("nouveau normalement.\n\ncordialement"))
    }

    @Test fun aSparseOmissionInsideTheBodyRestoresTheOriginalWordsAndPunctuation() {
        val raw = formatted().replace("a bien été pris", "a été pris")
        assertEquals(formatted(), request().acceptOutput(raw))
    }

    @Test fun englishEmailRestoresAnOmissionWhilePreservingNumbersNamesAndNegation() {
        val text = "Hello Nora, this longer email confirms that the 23 blue folders are ready for Friday and that you must not delete the originals under any circumstances. Please keep the copies in their current order and send the signed receipt to Maëlys normally, kind regards, Eli."
        val expected = text.replace("Nora, ", "Nora,\n\n")
            .replace("normally, kind", "normally.\n\nkind").replace("regards, ", "regards,\n\n")
        val raw = expected.replace("Maëlys normally.", "Maëlys.")
        val policy = LocalFormatRequest(text, "Mail", "English", listOf("Nora", "Maëlys", "Eli"),
            LocalLayoutKind.EMAIL, LocalFormatValidation.GEMMA_PROJECTION)
        assertEquals(expected, policy.acceptOutput(raw))
        assertNull(policy.acceptOutput(raw.replace("23", "twenty three")))
    }

    @Test fun omittedNegationCannotDisappearFromAnAcceptedMail() {
        val raw = formatted().replace("pas fonctionné", "fonctionné")
        val result = request().acceptOutput(raw)
        assertEquals(formatted(), result)
        assertTrue(result!!.contains("n'a pas fonctionné"))
    }

    @Test fun inventionsSubstitutionsAndReorderingRemainRejected() {
        val raw = formatted().replace("nouveau normalement.", "nouveau.")
        listOf(raw + "\nVotre assistant", raw.replace("Testeur", "Dupont"),
            raw.replace("un nouveau test", "un test nouveau"),
            raw.replace("Bonjour,", "Hello,")).forEach { assertNull(it, request().acceptOutput(it)) }
    }

    @Test fun repeatedWordsMustHaveAnUnambiguousAlignment() {
        val repeated = source.replace("un nouveau test", "un nouveau test test")
        assertNull(request(repeated).acceptOutput(formatted(repeated).replace("test test", "test")))
    }

    @Test fun truncationAndLargeDeletionsAreNotRepaired() {
        val raw = formatted()
        assertNull(request().acceptOutput(raw.removePrefix("Bonjour,\n\n")))
        assertNull(request().acceptOutput(raw.removeSuffix("Testeur")))
        assertNull(request().acceptOutput(raw.replace("lors de la création et la génération", "")))
    }

    @Test fun digitsTechnicalPartsAndQuotedFragmentsAreNotInferred() {
        for ((original, replacement) in listOf(
            "les 23 dossiers" to "les dossiers",
            "atelier@example.org" to "atelier@org",
            "« normalement »" to "« »",
        )) {
            val text = source.replace("un nouveau test", "un nouveau test concernant $original")
            assertNull(original, request(text).acceptOutput(formatted(text).replace(original, replacement)))
        }
    }

    @Test fun uncertainParagraphBoundariesAndPartialClosingsAreRejected() {
        val raw = formatted().replace("a bien été pris", "a\n\nété pris")
        assertNull(request().acceptOutput(raw))
        val text = source.replace("cordialement", "bien cordialement")
        val shortened = formatted(text).replace("normalement, bien cordialement", "normalement.\n\ncordialement")
        assertNull(request(text).acceptOutput(shortened))
    }

    @Test fun everyAcceptedSingleWordDeletionReconstructsTheWholeSource() {
        val words = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(source).toList()
        for (word in words) {
            val shortened = source.removeRange(word.range)
            val output = request().acceptOutput(shortened) ?: continue
            val actual = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(output).map { it.value }.toList()
            assertEquals("Omitted ${word.value}", words.map { it.value }, actual)
        }
    }

    @Test fun repairedEmailGoesThroughNormalFinalizationWithoutAnotherGeneration() {
        var calls = 0
        val raw = formatted().replace("nouveau normalement.", "nouveau.")
        val backend = object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String {
                calls++; onChunk(raw); return raw
            }
            override fun cancel() = Unit
        }
        LocalFormattingSession(backend).use { session ->
            assertEquals(formatted(), session.finish(request(), 2_000L) {})
            assertEquals(1, calls)
            assertEquals("applied", session.lastFinish?.outcome)
            assertEquals(1, session.lastFinish?.restoredSourceWords)
        }
    }
}
