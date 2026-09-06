package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class EditableTranscriptTest {
    @Test fun `tail following works with keyboard focus and resumes at end`() {
        org.junit.Assert.assertTrue(shouldFollowTranscriptTail(false, 0, 0, 20))
        org.junit.Assert.assertTrue(shouldFollowTranscriptTail(true, 0, 0, 0))
        org.junit.Assert.assertTrue(shouldFollowTranscriptTail(true, 20, 20, 20))
        org.junit.Assert.assertFalse(shouldFollowTranscriptTail(true, 5, 5, 20))
        org.junit.Assert.assertFalse(shouldFollowTranscriptTail(true, 5, 20, 20))
        org.junit.Assert.assertFalse(shouldFollowTranscriptTail(true, 20, 5, 20))
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
