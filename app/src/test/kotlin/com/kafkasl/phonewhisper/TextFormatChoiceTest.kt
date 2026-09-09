package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class TextFormatChoiceTest {
    @Test fun existingPlainTextSelectionCannotStartLocalOrCloudCleanupEvenForAVeryLongNote() {
        val plain = PostProcessingFormats.builtins.first()
        assertEquals("cleanup", plain.id)
        assertEquals("Texte", plain.name)
        assertFalse(plain.usesLanguageModel)
        assertNull(plain.localLayoutKind)
        // Persisted ID governs the route even if an old name or instruction survives elsewhere.
        assertFalse(plain.copy(name = "Texte corrigé", instructions = "Correct everything").usesLanguageModel)
    }

    @Test fun correctedTextIsAnExplicitSeparateFormatSupportedByBothEngines() {
        val corrected = PostProcessingFormats.builtins.single { it.id == "corrected" }
        assertTrue(corrected.usesLanguageModel)
        assertEquals(LocalLayoutKind.TEXT, corrected.localLayoutKind)
        assertTrue(corrected.instructions.contains("paragraphs"))
        assertEquals(listOf("cleanup", "corrected", "list", "email"), PostProcessingFormats.builtins.map { it.id })
    }
}
