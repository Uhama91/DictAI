package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class EditorKeyboardRequestTest {
    @Test fun `one tap waits for both editor and overlay focus and finger release`() {
        val request = EditorKeyboardRequest()
        request.beginTouch()
        request.request()
        assertFalse(request.consumeIfReady(true, true, true))
        request.endTouch()
        assertFalse(request.consumeIfReady(false, true, true))
        assertFalse(request.consumeIfReady(true, false, true))
        assertFalse(request.consumeIfReady(true, true, false))
        assertTrue(request.consumeIfReady(true, true, true))
        assertFalse(request.consumeIfReady(true, true, true))
    }

    @Test fun `hiding the editor cancels a late focus callback`() {
        val request = EditorKeyboardRequest()
        request.request()
        request.cancel()
        assertFalse(request.consumeIfReady(true, true, true))
        request.request()
        assertTrue(request.consumeIfReady(true, true, true))
    }
}
