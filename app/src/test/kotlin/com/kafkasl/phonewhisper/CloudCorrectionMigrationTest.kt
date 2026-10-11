package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudCorrectionMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)

    @Test fun retiredLocalChoiceMigratesDurablyWithoutChangingVoiceOrVocabulary() {
        preferences.edit().putString("formatting_engine", "local").putBoolean("cloud_cleanup_enabled", false)
            .putString("dictation_language", "en").putString("vocab_raw", "didi => Dydy")
            .putString("cloud_cleanup_model", "mistral-small-3-2").commit()
        val settings = PersistencePrefs(context)
        assertEquals("cloud", settings.formattingEngine)
        assertEquals("cloud", preferences.getString("formatting_engine", null))
        assertTrue(preferences.getBoolean("cloud_cleanup_enabled", false))
        assertEquals("cloud", PersistencePrefs(context).formattingEngine)
        assertEquals("en", preferences.getString("dictation_language", null))
        assertEquals("didi => Dydy", preferences.getString("vocab_raw", null))
        assertEquals("mistral-small-3-2", settings.cloudModel().preferenceValue)
    }

    @Test fun existingOffAndCloudChoicesSurviveMigration() {
        for (value in listOf("off", "cloud")) {
            preferences.edit().putString("formatting_engine", value)
                .putBoolean("cloud_cleanup_enabled", value == "cloud").commit()
            assertEquals(value, PersistencePrefs(context).formattingEngine)
        }
        preferences.edit().remove("formatting_engine").putBoolean("cloud_cleanup_enabled", false).commit()
        assertEquals("off", PersistencePrefs(context).formattingEngine)
    }

    @Test fun legacyFormatsRemainStoredButCannotInfluenceRuntimeCorrection() {
        val formats = PostProcessingFormats(context)
        formats.save("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Historique", "Invent a summary and a mail.")
        for (id in listOf("corrected", "list", "email", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")) {
            context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE).edit().putString("selected", id).commit()
            assertEquals("cleanup", PostProcessingFormats(context).selected().id)
            assertEquals("", PostProcessingFormats(context).selected().instructions)
            assertEquals(id, context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE).getString("selected", null))
        }
        assertEquals("Invent a summary and a mail.", formats.custom().single().instructions)
    }

    @Test fun correctionSettingsExposeOnlyCloudOrOffAndNoFormatEditor() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create(Bundle().apply { putString("main_screen", "FORMATTING") })
        try {
            val texts = descendants(controller.get().findViewById(android.R.id.content))
                .filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.any { it == "Correction du texte" })
            assertTrue(texts.any { it == "Clé OpenRouter" })
            assertFalse(texts.any { it.contains("Format de la dictée") || it.contains("Gemma") || it.contains("Choix disponibles") })
        } finally { controller.destroy() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
