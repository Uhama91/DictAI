package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmStripThinkTest {
    @Test fun stripsThinkBlock() {
        assertEquals("Salut, ça va ?",
            LlmPostProcessor.stripThink("<think>\nL'utilisateur dit bonjour\n</think>Salut, ça va ?"))
    }
    @Test fun keepsPlainText() { assertEquals("Bonjour", LlmPostProcessor.stripThink("Bonjour")) }
    @Test fun stripsUnclosedThink() { assertEquals("", LlmPostProcessor.stripThink("<think> en cours")) }
}
