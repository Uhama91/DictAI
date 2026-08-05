package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptBufferTest {
    @Test fun `renders committed text before the tentative tail`() {
        val buffer = LiveTranscriptBuffer(maxChars = 80)

        assertEquals("texte validé essai en cours", buffer.render("texte validé", "essai en cours"))
    }

    @Test fun `keeps recent whole words within its character bound`() {
        val buffer = LiveTranscriptBuffer(maxChars = 24)

        val rendered = buffer.render("alpha beta gamma delta epsilon", "tentative")

        assertEquals("delta epsilon tentative", rendered)
        assertTrue(rendered.length <= 24)
    }
}
