package com.kafkasl.phonewhisper

import android.text.Editable
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayTranscriptEditorRobolectricTest {
    @Test
    fun `finish composing invokes replay hook without replacing composed text`() {
        val editor = OverlayTranscriptEditor(RuntimeEnvironment.getApplication())
        editor.setText("Texte initial")
        editor.setSelection(editor.length())
        var callbackCount = 0
        var callbackText: String? = null
        var callbackSelection = Int.MIN_VALUE
        editor.onCompositionFinished = {
            callbackCount++
            callbackText = editor.text.toString()
            callbackSelection = editor.selectionStart
        }

        val connection = editor.onCreateInputConnection(EditorInfo())
        assertNotNull(connection)
        assertTrue(connection!!.setComposingText(" en cours", 1))
        val beforeText = editor.text.toString()
        val beforeSelection = editor.selectionStart
        assertTrue(BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
        assertEquals(0, callbackCount)

        assertTrue(connection.finishComposingText())

        assertEquals(1, callbackCount)
        assertEquals(beforeText, callbackText)
        assertEquals(beforeSelection, callbackSelection)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
    }
}
