package com.kafkasl.phonewhisper

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationHistoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun `only actual speech populates history and image references are excluded`() {
        val store = DictationHistory(context)
        store.recordTranscript(null, "Note saisie au clavier", "typed")
        store.recordTranscript("", "[[Image 1]]", "capture")
        assertTrue(store.all().isEmpty())
        store.recordTranscript("Une idée dictée", "Une idée corrigée.\n\n[[Image 1]]", "spoken")
        assertEquals(listOf("Une idée corrigée."), store.all().map { it.text })
    }
    @Test fun `eleven dictations retain only the newest ten after reopening`() {
        val store = DictationHistory(context)
        for (i in 1..11) store.record("Dictée $i 🐈\nTexte intégral", "session-$i", i.toLong())
        assertEquals((11 downTo 2).map { "session-$it" }, store.all().map { it.id })
        assertEquals(store.all(), DictationHistory(context).all())
        assertEquals("Dictée 11 🐈\nTexte intégral", store.all().first().text)
    }
    @Test fun `same session updates one entry and blank output cannot evict a dictation`() {
        val store = DictationHistory(context)
        store.record("Texte initial", "one", 1)
        store.record("Texte corrigé", "one", 2)
        store.record(" \n ", "empty", 3)
        assertEquals(listOf("Texte corrigé"), store.all().map { it.text })
    }
    @Test fun `clearing history does not modify permanent notes or preferences`() {
        val notes = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
        val prefs = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        notes.edit().putString("existing", "Note conservée").commit()
        prefs.edit().putString("vocab_raw", "DictAI").commit()
        val store = DictationHistory(context)
        store.record("Ma dictée", "one", 1)
        store.clear()
        assertTrue(DictationHistory(context).all().isEmpty())
        assertEquals("Note conservée", notes.getString("existing", null))
        assertEquals("DictAI", prefs.getString("vocab_raw", null))
    }
    @Test fun `corrupt history remains untouched until explicit clear`() {
        val prefs = context.getSharedPreferences("dictation_history", Context.MODE_PRIVATE)
        prefs.edit().putString("entries", "broken JSON").commit()
        val store = DictationHistory(context)
        assertThrows(Exception::class.java) { store.all() }
        assertThrows(Exception::class.java) { store.record("Nouvelle dictée", "one", 1) }
        assertEquals("broken JSON", prefs.getString("entries", null))
        store.clear()
        assertTrue(store.all().isEmpty())
    }
}
