package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class EditableTranscriptTest {
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
}
