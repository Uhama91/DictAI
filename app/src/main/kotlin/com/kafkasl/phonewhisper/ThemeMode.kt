package com.kafkasl.phonewhisper

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** User-facing appearance choices persisted with the rest of DictAI preferences. */
enum class ThemeMode(val preferenceValue: String, val label: String) {
    SYSTEM("system", "Système"),
    LIGHT("light", "Clair"),
    DARK("dark", "Sombre");

    companion object {
        fun fromPreference(value: String?): ThemeMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

internal object ThemeModeStore {
    private const val PREFS = "whisperpin"
    private const val KEY = "theme_mode"

    fun read(context: Context): ThemeMode = ThemeMode.fromPreference(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    )
}

/** Applies the persisted appearance without touching dictation/session state. */
object ThemeModeController {
    fun delegateMode(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    fun apply(context: Context, mode: ThemeMode = ThemeModeStore.read(context)) {
        AppCompatDelegate.setDefaultNightMode(delegateMode(mode))
    }
}
