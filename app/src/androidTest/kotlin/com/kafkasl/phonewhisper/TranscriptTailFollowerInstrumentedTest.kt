package com.kafkasl.phonewhisper

import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Requires an Android device; exercises real EditText/ScrollView layout, not a JVM geometry mock. */
class TranscriptTailFollowerInstrumentedTest {
    @Test fun correctionKeepsTheViewportAndTailFollowingResumesAfterEditing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext).apply {
                setPadding(0, 0, 0, 0)
                setText((1..20).joinToString("\n") { "Ligne $it du texte" })
            }
            val scroll = ScrollView(instrumentation.targetContext).apply {
                setPadding(12, 0, 12, 10)
                addView(editor, FrameLayout.LayoutParams(-1, -2))
            }
            fun layout(height: Int) {
                scroll.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                scroll.layout(0, 0, 360, height)
            }
            layout(160)
            editor.requestFocus()
            editor.setSelection(4)
            var editing = true
            var clock = 1000L
            val follower = TranscriptTailFollower(editor, scroll, editing = { editing }, clock = { clock }) { true }
            try {
                follower.userInteraction()
                editor.append("\nNouveaux mots dictés")
                editor.setSelection(4)
                follower.changed()
                layout(160)
                scroll.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(4, editor.selectionStart)
                assertEquals(0, editor.scrollY)
                assertEquals(0, scroll.scrollY)
                assertFalse(follower.followsTail)
                clock = 2000L
                layout(100)
                follower.resized()
                scroll.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(4, editor.selectionStart)
                assertTrue(editor.bottom <= scroll.scrollY + scroll.height - scroll.paddingBottom)
                assertTrue(follower.followsTail)
            } finally { follower.reset() }
        }
    }
}
