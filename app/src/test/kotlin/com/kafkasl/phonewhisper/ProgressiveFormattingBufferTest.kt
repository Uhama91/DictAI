package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveFormattingBufferTest {
    @Test fun `short dictation waits for final request`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = "Bonjour Marie merci pour ton message"

        buffer.update(raw, stableWordCount = raw.words())

        assertNull(buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL))
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)
        assertNotNull(request)
        assertEquals(raw, request!!.source)
        assertEquals(GemmaFineTunedPrompt.Phase.FINAL, request.phase)
    }

    @Test fun `long stable sentence starts before unstable tail`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }

        buffer.update(raw, stableWordCount = raw.words())
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)

        assertNotNull(request)
        assertTrue(request!!.source.split(Regex("\\s+")).size in 20..80)
        assertTrue(request.source.endsWith("mot25."))
        assertFalse(request.source.contains("suite65"))
        assertTrue(raw.substring(request.sourceEndExclusive).contains("suite65"))
    }

    @Test fun `total dictation threshold does not require sixty stable continuation words`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..60).joinToString(" ") { "suite$it" }

        buffer.update(raw, stableWordCount = 40)

        assertNotNull(buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL))
    }

    @Test fun `caller can provide total count after a human reset`() {
        val buffer = ProgressiveFormattingBuffer()
        buffer.resetForHumanEdit((1..20).joinToString(" ") { "prefix$it" })
        val raw = sentence(25) + " " + (26..40).joinToString(" ") { "suite$it" }

        buffer.update(raw, stableWordCount = 40, totalDictationWordCount = 60)

        assertNotNull(buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL))
    }

    @Test fun `partial waits for stability and a safe boundary`() {
        val noStable = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }
        noStable.update(raw, stableWordCount = 40, totalDictationWordCount = 40)
        assertNull(noStable.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL))

        val noBoundary = ProgressiveFormattingBuffer()
        val unfinished = (1..65).joinToString(" ") { "mot$it" }
        noBoundary.update(unfinished, stableWordCount = 65)
        assertNull(noBoundary.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL))
    }

    @Test fun `abbreviation and decimal are not sentence boundaries`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = (1..19).joinToString(" ") { "mot$it" } +
            " Dr. suite vingt-cinq mots sont encore dits et restent dans la phrase." +
            " " + (1..30).joinToString(" ") { "tail$it" }
        buffer.update(raw, stableWordCount = raw.words())
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)
        assertNotNull(request)
        assertFalse(request!!.source.endsWith("Dr."))

        val decimalBuffer = ProgressiveFormattingBuffer()
        val decimal = (1..19).joinToString(" ") { "mot$it" } +
            " 3.14. suite continue sans fin " + (1..45).joinToString(" ") { "tail$it" }
        decimalBuffer.update(decimal, stableWordCount = decimal.words())
        val decimalRequest = decimalBuffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)
        assertNull(decimalRequest)
    }

    @Test fun `append after in flight keeps the same request`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }
        buffer.update(raw, stableWordCount = 65)
        val first = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!

        buffer.update("$raw ajout", stableWordCount = 66)
        val again = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!

        assertEquals(first, again)
    }

    @Test fun `revision inside in flight invalidates the request`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }
        buffer.update(raw, stableWordCount = 65)
        val first = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!

        val revised = raw.replace("mot7", "nouveau7")
        buffer.update(revised, stableWordCount = 65)
        val second = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!

        assertNotEquals(first.id, second.id)
        assertTrue(second.source.contains("nouveau7"))
    }

    @Test fun `accepted output is retained while raw remainder stays visible`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }
        buffer.update(raw, stableWordCount = 65)
        val first = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!
        assertTrue(buffer.acceptValidated(first, "Segment corrigé."))

        val state = buffer.state()
        assertEquals("Segment corrigé.", state.acceptedSegments.single().output)
        assertTrue(state.renderedText.startsWith("Segment corrigé."))
        assertTrue(state.renderedText.contains("suite65"))
        assertTrue(state.renderedText.indexOf("Segment corrigé.") < state.renderedText.indexOf("suite65"))
    }

    @Test fun `revision of accepted source restores raw and drops later segments`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }
        buffer.update(raw, stableWordCount = 65)
        val first = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!
        buffer.acceptValidated(first, "Segment corrigé.")

        buffer.update(raw.replace("mot9", "révisé9"), stableWordCount = 65)

        assertTrue(buffer.state().acceptedSegments.isEmpty())
        assertTrue(buffer.state().renderedText.contains("révisé9"))
        assertFalse(buffer.state().renderedText.contains("Segment corrigé."))
    }

    @Test fun `human reset invalidates old result and starts a new epoch`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }
        buffer.update(raw, stableWordCount = 65)
        val old = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!
        val before = buffer.state().humanEpoch

        buffer.resetForHumanEdit("Texte corrigé par la personne")
        assertFalse(buffer.acceptValidated(old, "sortie tardive"))
        buffer.update("suite après retouche", stableWordCount = 4)
        val final = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)!!
        assertTrue(final.humanEpoch > before)
        buffer.acceptValidated(final, "suite après retouche.")

        assertEquals("suite après retouche.", buffer.state().renderedText)
    }

    @Test fun `human prefix is context only and is not rendered by the continuation buffer`() {
        val buffer = ProgressiveFormattingBuffer()
        buffer.resetForHumanEdit("Préfixe déjà visible")
        buffer.update("suite", stableWordCount = 1)
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)!!
        buffer.acceptValidated(request, "suite corrigée")

        assertEquals("suite corrigée", buffer.state().renderedText)
        assertTrue(request.contextBefore.contains("Préfixe déjà visible"))
    }

    @Test fun `stability rollback invalidates partial request but final remains available`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }
        buffer.update(raw, stableWordCount = 65)
        val partial = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!

        buffer.update(raw, stableWordCount = 0)

        assertFalse(buffer.acceptValidated(partial, "sortie devenue instable"))
        assertNull(buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL))
        assertNotNull(buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL))
    }

    @Test fun `raw normalizer affects fallback and context but not source or accepted output`() {
        val buffer = ProgressiveFormattingBuffer(normalizer = { it.replace("euh ", "") })
        val raw = "Bonjour euh Marie"
        buffer.update(raw, stableWordCount = raw.words())
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)!!

        assertEquals(raw, request.source)
        assertEquals("Bonjour Marie", buffer.state().renderedText)
        buffer.acceptValidated(request, "Bonjour euh Marie.")
        assertEquals("Bonjour euh Marie.", buffer.state().renderedText)
    }

    @Test fun `raw normalizer does not rewrite the human context`() {
        val buffer = ProgressiveFormattingBuffer(normalizer = { it.replace("euh ", "") })
        buffer.resetForHumanEdit("Préfixe humain euh")
        buffer.update("suite", stableWordCount = 1)
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)!!

        assertTrue(request.contextBefore.contains("Préfixe humain euh"))
    }

    @Test fun `blank output settles raw once and is not requested again`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = "Bonjour tout le monde"
        buffer.update(raw, stableWordCount = raw.words())
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)!!

        assertFalse(buffer.acceptValidated(request, "   "))
        assertNull(buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL))
        assertEquals(raw, buffer.state().renderedText)
    }

    @Test fun `context is capped and never rendered twice`() {
        val buffer = ProgressiveFormattingBuffer()
        val prefix = (1..130).joinToString(" ") { "prefix$it" }
        buffer.resetForHumanEdit(prefix)
        buffer.update("suite finale", stableWordCount = 2)
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)!!

        assertEquals(120, request.contextBefore.words())
        assertFalse(request.contextBefore.wordsList().contains("prefix1"))
        buffer.acceptValidated(request, "suite finale.")
        assertEquals("suite finale.", buffer.state().renderedText)
        assertEquals(1, buffer.state().renderedText.split("suite finale.").size - 1)
    }

    @Test fun `final request covers only the short remainder after a partial segment`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " reste très court"
        buffer.update(raw, stableWordCount = raw.words())
        val partial = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)
        assertNull(partial)

        val final = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)!!
        assertEquals(raw, final.source)
        assertEquals(GemmaFineTunedPrompt.Phase.FINAL, final.phase)
    }

    @Test fun `new mode or phase gets a new identity and exact source`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " " + (26..65).joinToString(" ") { "suite$it" }
        buffer.update(raw, stableWordCount = 65)
        val text = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.PARTIAL)!!
        val list = buffer.request(LocalLayoutKind.LIST, GemmaFineTunedPrompt.Phase.PARTIAL)!!
        val email = buffer.request(LocalLayoutKind.EMAIL, GemmaFineTunedPrompt.Phase.FINAL)!!

        assertNotEquals(text.id, list.id)
        assertNotEquals(list.id, email.id)
        assertEquals(text.source, list.source)
        assertEquals(GemmaFineTunedPrompt.Phase.FINAL, email.phase)
        assertEquals(raw, email.source)
    }

    @Test fun `newlines and list output are kept as returned`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = "Préparer la salle\nVérifier le cahier\nFermer la fenêtre"
        buffer.update(raw, stableWordCount = raw.words())
        val request = buffer.request(LocalLayoutKind.LIST, GemmaFineTunedPrompt.Phase.FINAL)!!
        val output = "• Préparer la salle\n• Vérifier le cahier\n• Fermer la fenêtre"

        buffer.acceptValidated(request, output)
        assertEquals(output, buffer.state().renderedText)
    }

    @Test fun `raw offsets remain offsets even when output changes word count`() {
        val buffer = ProgressiveFormattingBuffer()
        val raw = sentence(25) + " suite26 suite27"
        buffer.update(raw, stableWordCount = raw.words())
        val request = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)!!
        buffer.acceptValidated(request, "Deux mots au lieu de la source")
        buffer.update("$raw suite28", stableWordCount = raw.words() + 1)
        val next = buffer.request(LocalLayoutKind.TEXT, GemmaFineTunedPrompt.Phase.FINAL)

        assertNotNull(next)
        assertEquals(request.sourceEndExclusive, next!!.sourceStart)
        assertEquals(" suite28", next.source)
    }

    private fun sentence(count: Int): String =
        (1..count).joinToString(" ") { "mot$it" } + "."

    private fun String.words(): Int = trim().split(Regex("\\s+")).filter(String::isNotEmpty).size

    private fun String.wordsList(): List<String> = trim().split(Regex("\\s+")).filter(String::isNotEmpty)
}
