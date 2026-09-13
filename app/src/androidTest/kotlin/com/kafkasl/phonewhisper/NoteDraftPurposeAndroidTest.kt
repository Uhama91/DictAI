package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Uses isolated real preferences. Does not alter the user's draft or notes. */
class NoteDraftPurposeAndroidTest {
    @Test fun recreationPreservesNoteIntentWithoutDependingOnTheNoteId() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "note-intent-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        try {
            val draft = DictationDraftStore(context)
            draft.purpose = DictationPurpose.NOTE
            draft.save("Une note à conserver")
            assertEquals(DictationPurpose.NOTE, DictationDraftStore(context).purpose)
            assertNull(DictationDraftStore(context).noteId)
            assertEquals("Une note à conserver", DictationDraftStore(context).load())
            // Upgrade from a version that only stored a note ID must remain safe too.
            context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE).edit()
                .remove("purpose").putString("note_id", "old-or-deleted-note").commit()
            assertEquals(DictationPurpose.NOTE, DictationDraftStore(context).purpose)
            draft.clear()
            assertEquals(DictationPurpose.MESSAGE, DictationDraftStore(context).purpose)
            assertNull(DictationDraftStore(context).load())
        } finally { app.deleteSharedPreferences(prefix + "dictation_draft") }
    }
}
