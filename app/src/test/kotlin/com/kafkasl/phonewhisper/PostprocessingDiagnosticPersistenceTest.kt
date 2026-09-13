package com.kafkasl.phonewhisper

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PostprocessingDiagnosticPersistenceTest {
    @Test fun failedMailSurvivesPlainDictationAndPreferenceRecreation() {
        val storage = memoryPreferences()
        val prefs = PersistencePrefs(storage)
        val failedMail = "Format : Mail\nÉtat : délai d’attente finale dépassé"
        prefs.recordPostprocessingDiagnostic(failedMail, formatRequested = true)
        repeat(3) { prefs.recordPostprocessingDiagnostic("Format : Texte\nDictée $it", formatRequested = false) }

        val reopened = PersistencePrefs(storage)
        assertEquals(failedMail, reopened.lastFormatPostprocessingDiagnostic)
        assertEquals("Format : Texte\nDictée 2", reopened.lastPostprocessingDiagnostic)

        val nextList = "Format : Liste\nÉtat : sortie validée"
        reopened.recordPostprocessingDiagnostic(nextList, formatRequested = true)
        assertEquals(nextList, reopened.lastFormatPostprocessingDiagnostic)
        assertEquals(nextList, reopened.lastPostprocessingDiagnostic)
    }

    @Test fun legacyLatestReportRemainsAvailableWithoutInventingAFormatAttempt() {
        val oldReport = "Format : Texte\nÉtat : traitement non exécuté ou indisponible"
        val storage = memoryPreferences(mutableMapOf("last_postprocessing_diagnostic" to oldReport))
        val prefs = PersistencePrefs(storage)
        assertEquals(oldReport, prefs.lastPostprocessingDiagnostic)
        assertNull(prefs.lastFormatPostprocessingDiagnostic)
        prefs.recordPostprocessingDiagnostic("Format : Texte", formatRequested = false)
        assertNull(PersistencePrefs(storage).lastFormatPostprocessingDiagnostic)
    }

    /** JVM fixture models atomic editor application and persisted strings used by these preferences. */
    private fun memoryPreferences(values: MutableMap<String, String?> = mutableMapOf()): SharedPreferences {
        fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { pending[args[0] as String] = args[1] as String?; proxy }
                    "remove" -> { pending[args[0] as String] = null; proxy }
                    "apply", "commit" -> {
                        pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        pending.clear()
                        if (method.name == "commit") true else null
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args[0] as String)
                "getString" -> values[args[0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }
}
