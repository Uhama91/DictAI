package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class EditableTranscriptTest {
    @Test fun `correcting Grok does not protect unrelated dictated hesitations`() {
        val buffer = EditableTranscript()
        buffer.update("Je consulte grek euh demain")
        buffer.edit("Je consulte Grok euh demain")
        val final = buffer.resolveFinal("Je consulte grek euh demain et euh après")!!
        val prepared = CorrectedTextPreparation.prepare(final, "corrected", listOf("Grok"), buffer.manualProtection(final))
        assertEquals("Je consulte Grok demain et après", prepared.text)
        assertEquals(2, prepared.removed)
    }

    @Test fun `typed fillers stay protected across backspace and a second edit`() {
        val buffer = EditableTranscript()
        buffer.update("Je dis bon ici")
        buffer.edit("Je dis  ici")
        buffer.edit("Je dis e ici")
        buffer.edit("Je dis eu ici")
        buffer.edit("Je dis euh ici")
        buffer.update("Je dis bon ici et euh ensuite")
        buffer.edit("Donc je dis euh ici et euh ensuite")
        val final = buffer.resolveFinal("Je dis bon ici et euh ensuite demain")!!
        assertEquals("Donc je dis euh ici et ensuite demain",
            CorrectedTextPreparation.prepare(final, "corrected", manualRanges = buffer.manualProtection(final)).text)
    }

    @Test fun `restored drafts are protected but resumed speech can be cleaned`() {
        val buffer = EditableTranscript()
        buffer.edit("euh, texte tapé.")
        val final = buffer.update("euh voici la suite")
        assertEquals("euh, texte tapé. Voici la suite",
            CorrectedTextPreparation.prepare(final, "corrected", manualRanges = buffer.manualProtection(final)).text)
        buffer.clear()
        val next = buffer.update("euh une nouvelle dictée")
        assertTrue(buffer.manualProtection(next).isEmpty())
        assertEquals("une nouvelle dictée", CorrectedTextPreparation.prepare(next, "corrected").text)
    }

    @Test fun `automatic vocabulary normalization does not create manual protection`() {
        val buffer = EditableTranscript()
        val final = buffer.update("Je consulte grek euh demain") { it.replace("grek", "Grok") }
        assertFalse(buffer.hasUserEdits())
        assertTrue(buffer.manualProtection(final).isEmpty())
        assertEquals("Je consulte Grok demain", CorrectedTextPreparation.prepare(final, "corrected").text)
    }

    @Test fun `restored draft remains verbatim before a new recognition session`() {
        val buffer = EditableTranscript()
        buffer.edit("Mon argument corrigé.\n\n")
        assertTrue(buffer.hasUserEdits())
        assertEquals("Mon argument corrigé.\n\nNouvelle idée", buffer.update("nouvelle idée"))
        assertEquals("Mon argument corrigé.\n\nNouvelle idée précise", buffer.resolveFinal("nouvelle idée précise"))
        buffer.clear()
        assertFalse(buffer.hasUserEdits())
    }

    @Test fun `manual-only draft survives an empty recognition result`() {
        val buffer = EditableTranscript()
        buffer.edit("Texte rédigé en pause.\nDeuxième ligne.")
        assertEquals("Texte rédigé en pause.\nDeuxième ligne.", buffer.resolveFinal(null))
        assertEquals("Texte rédigé en pause.\nDeuxième ligne.", buffer.resolveFinal(""))
        buffer.clear()
        assertEquals(null, buffer.resolveFinal(null))
    }

    @Test fun `manual additions while paused survive resumed streaming and final revision`() {
        val buffer = EditableTranscript()
        buffer.update("bonjour mari")
        buffer.edit("Bonjour Marie.\nTexte ajouté au clavier.")
        assertEquals("Bonjour Marie.\nTexte ajouté au clavier. Voici la suite",
            buffer.update("Bonjour Marie voici la suite"))
        assertEquals("Bonjour Marie.\nTexte ajouté au clavier. Voici la suite.",
            buffer.update("Bonjour Marie voici la suite."))
    }

    @Test fun `uncorrected preview and final retain all text`() {
        val buffer = EditableTranscript()
        val long = "bonjour ".repeat(1000)
        assertEquals(long, buffer.update(long))
        assertEquals("Bonjour !", buffer.update("Bonjour !"))
    }
    @Test fun `replacement and punctuation survive revisions and capitalize continuation`() {
        val buffer = EditableTranscript()
        buffer.update("bonjour mari")
        buffer.edit("Bonjour Marie.")
        assertEquals("Bonjour Marie. Comment allez vous", buffer.update("bonjour Marie comment allez vous"))
        assertEquals("Bonjour Marie. Comment allez-vous ?", buffer.update("Bonjour Marie comment allez-vous ?"))
    }
    @Test fun `recognizer word insertion before edit boundary does not duplicate text`() {
        val buffer = EditableTranscript()
        buffer.update("bonjour tout monde")
        buffer.edit("Bonjour tout le monde !")
        assertEquals("Bonjour tout le monde ! Voici la suite", buffer.update("bonjour tout le monde voici la suite"))
    }
    @Test fun `second edit captures new words and preserves inserted line break`() {
        val buffer = EditableTranscript()
        buffer.update("premier")
        buffer.edit("Premier.")
        buffer.update("premier second")
        buffer.edit("Premier. Deuxième.\n")
        assertEquals("Premier. Deuxième.\nTroisième", buffer.update("premier second troisième"))
    }
    @Test fun `deleting all visible text and resetting are supported`() {
        val buffer = EditableTranscript()
        buffer.update("à supprimer")
        buffer.edit("")
        assertEquals("", buffer.update("à supprimer"))
        assertEquals("suite", buffer.update("à supprimer suite"))
        buffer.clear()
        assertEquals("nouvelle dictée", buffer.update("nouvelle dictée"))
    }
    @Test fun `normalization cannot move the raw ASR boundary after a manual edit`() {
        val buffer = EditableTranscript()
        val normalize: (String) -> String = { it.replace("vingt trois", "23").replace("ma yotte", "Maillot") }
        assertEquals("Les 23 élèves", buffer.update("Les vingt trois élèves", normalize))
        buffer.edit("Les 23 élèves.")
        assertEquals("Les 23 élèves. Appellent Maillot", buffer.update("Les vingt trois élèves appellent ma yotte", normalize))
        buffer.edit("Les 23 élèves. Appellent M. Maillot !")
        assertEquals("Les 23 élèves. Appellent M. Maillot ! Demain", buffer.resolveFinal("Les vingt trois élèves appellent ma yotte demain", normalize))
    }

}
