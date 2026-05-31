package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmStripThinkTest {
    @Test fun stripsThinkBlock() {
        assertEquals("Salut, ça va ?",
            LlmText.stripThink("<think>\nL'utilisateur dit bonjour\n</think>Salut, ça va ?"))
    }
    @Test fun keepsPlainText() { assertEquals("Bonjour", LlmText.stripThink("Bonjour")) }
    @Test fun stripsUnclosedThink() { assertEquals("", LlmText.stripThink("<think> en cours")) }
}
