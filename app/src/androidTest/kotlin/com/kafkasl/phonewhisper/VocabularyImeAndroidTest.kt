package com.kafkasl.phonewhisper

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android's real Editable/IME events, without claiming to exercise Gboard itself. */
@RunWith(AndroidJUnit4::class)
class VocabularyImeAndroidTest {
    @Test fun backspaceAndComposingReplacementPreserveTheOriginalAf() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = EditText(ApplicationProvider.getApplicationContext<Context>())
            val tracker = VocabularyCorrectionTracker()
            var clock = 0L
            editor.setText("AF"); editor.setSelection(2)
            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    tracker.beforeChange(s.toString(), start, count, after, editor.selectionStart, editor.selectionEnd)
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { tracker.afterChange(s.toString(), clock) }
            })
            val ime = object : BaseInputConnection(editor, true) {
                override fun getEditable(): Editable = editor.editableText
            }
            repeat(2) { ime.deleteSurroundingText(1, 0); clock += 100 }
            assertEquals("", editor.text.toString())
            assertNull(tracker.suggestion("", 0, 0, false, clock + 2_000))
            listOf("C", "CA", "CAF").forEach { ime.setComposingText(it, 1); clock += 100 }
            assertEquals("CAF", editor.text.toString())
            assertEquals(VocabularyCorrectionTracker.Suggestion("AF", "CAF"),
                tracker.suggestion(editor.text.toString(), editor.selectionStart, editor.selectionEnd, true, clock + 1_500))
        }
    }
}
