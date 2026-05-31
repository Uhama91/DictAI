package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class PostProcessPromptsTest {
    @Test fun substitutesOutput() {
        assertEquals("Corrige : bonjour", PostProcessPrompts.fillTemplate("Corrige : \${output}", "bonjour"))
    }
    @Test fun appendsWhenNoPlaceholder() {
        assertEquals("Résume.\n\nbonjour", PostProcessPrompts.fillTemplate("Résume.", "bonjour"))
    }
}
