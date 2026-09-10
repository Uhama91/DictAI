package com.kafkasl.phonewhisper

import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Real EditText touch/selection; window focus and Gboard still require the phone checklist. */
class OverlayTranscriptEditorAndroidTest {
    @Test fun firstTapKeepsTheChosenOffsetAndOnlyExplicitEditingSuspendsTheTail() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = OverlayTranscriptEditor(instrumentation.targetContext)
            var acquisitions = 0
            editor.acquireWindow = { acquisitions++ }
            editor.setText("Corriger ce mot sans déplacer le texte.\nUne seconde ligne.")
            editor.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, 600, 300)
            editor.requestFocus()
            assertFalse("Android focus alone is not a correction", editor.isEditing)
            val x = editor.totalPaddingLeft + editor.layout.getPrimaryHorizontal(6)
            val y = (editor.totalPaddingTop + editor.layout.getLineBottom(0) / 2).toFloat()
            val expected = editor.getOffsetForPosition(x, y)
            val down = MotionEvent.obtain(1000L, 1000L, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(1000L, 1100L, MotionEvent.ACTION_UP, x, y, 0)
            try {
                editor.onTouchEvent(down)
                editor.onTouchEvent(up)
                assertEquals(1, acquisitions)
                assertTrue(editor.isEditing)
                assertEquals(expected, editor.selectionStart)
                assertEquals(expected, editor.selectionEnd)
                // A tail-follow update may have moved the ScrollView while the
                // editor stayed focused.  The next tap must use the word under
                // the finger rather than restoring the previous caret.
                val laterX = editor.totalPaddingLeft + editor.layout.getPrimaryHorizontal(20)
                val laterY = (editor.totalPaddingTop + editor.layout.getLineBottom(0) / 2).toFloat()
                val laterExpected = editor.getOffsetForPosition(laterX, laterY)
                val laterDown = MotionEvent.obtain(1200L, 1200L, MotionEvent.ACTION_DOWN, laterX, laterY, 0)
                val laterUp = MotionEvent.obtain(1200L, 1300L, MotionEvent.ACTION_UP, laterX, laterY, 0)
                try {
                    editor.onTouchEvent(laterDown)
                    editor.onTouchEvent(laterUp)
                    assertEquals(laterExpected, editor.selectionStart)
                    assertEquals(laterExpected, editor.selectionEnd)
                } finally { laterDown.recycle(); laterUp.recycle() }
                editor.endEditing()
                assertFalse(editor.isEditing)
                editor.requestFocus()
                assertFalse("A later automatic focus must not reopen correction", editor.isEditing)
            } finally { editor.endEditing(); down.recycle(); up.recycle() }
        }
    }
}
