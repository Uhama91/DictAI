package com.kafkasl.phonewhisper

import androidx.appcompat.app.AppCompatDelegate
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `unknown or missing preference follows the system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPreference(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPreference("legacy-value"))
    }

    @Test
    fun `preference values round trip to the three supported modes`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromPreference(mode.preferenceValue))
        }
    }

    @Test
    fun `delegate mode maps explicit appearance choices`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeModeController.delegateMode(ThemeMode.SYSTEM))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeModeController.delegateMode(ThemeMode.LIGHT))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeModeController.delegateMode(ThemeMode.DARK))
    }

    @Test
    fun `theme preference survives reopening without changing another preference`() {
        val values = mutableMapOf<String, Any?>("show_transcript" to false)
        val storage = memoryPreferences(values)
        PersistencePrefs(storage).themeMode = ThemeMode.DARK

        val reopened = PersistencePrefs(storage)
        assertEquals(ThemeMode.DARK, reopened.themeMode)
        assertFalse(reopened.showTranscript)
        reopened.themeMode = ThemeMode.LIGHT
        assertEquals(ThemeMode.LIGHT, PersistencePrefs(storage).themeMode)
        assertFalse(PersistencePrefs(storage).showTranscript)
    }

    @Test
    fun `explicit appearance wins over either system appearance`() {
        assertEquals(ThemeTokens.LIGHT, ThemeTokens.paletteFor(ThemeMode.LIGHT, isSystemNight = true))
        assertEquals(ThemeTokens.LIGHT, ThemeTokens.paletteFor(ThemeMode.LIGHT, isSystemNight = false))
        assertEquals(ThemeTokens.DARK, ThemeTokens.paletteFor(ThemeMode.DARK, isSystemNight = true))
        assertEquals(ThemeTokens.DARK, ThemeTokens.paletteFor(ThemeMode.DARK, isSystemNight = false))
        assertEquals(ThemeTokens.LIGHT, ThemeTokens.paletteFor(ThemeMode.SYSTEM, isSystemNight = false))
        assertEquals(ThemeTokens.DARK, ThemeTokens.paletteFor(ThemeMode.SYSTEM, isSystemNight = true))
    }

    @Test
    fun `light and dark text roles meet normal text contrast`() {
        listOf(ThemeTokens.LIGHT, ThemeTokens.DARK).forEach { palette ->
            assertContrast(palette.ink, palette.bg)
            assertContrast(palette.inkMuted, palette.bg)
            assertContrast(palette.green, palette.bg)
            assertContrast(palette.green, palette.raised)
            assertContrast(palette.onGreen, palette.green)
            assertContrast(palette.errorText, palette.raised)
        }
    }

    private fun assertContrast(foreground: Int, background: Int) {
        assertTrue("contrast=${contrast(foreground, background)}", contrast(foreground, background) >= 4.5)
    }

    private fun contrast(foreground: Int, background: Int): Double {
        fun channel(color: Int, shift: Int): Double {
            val srgb = ((color ushr shift) and 0xFF) / 255.0
            return if (srgb <= 0.03928) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        fun luminance(color: Int) = .2126 * channel(color, 16) + .7152 * channel(color, 8) + .0722 * channel(color, 0)
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun memoryPreferences(values: MutableMap<String, Any?>): SharedPreferences {
        fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean", "putInt" -> {
                        pending[args[0] as String] = args[1]
                        proxy
                    }
                    "remove" -> {
                        pending[args[0] as String] = null
                        proxy
                    }
                    "apply", "commit" -> {
                        pending.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        pending.clear()
                        if (method.name == "commit") true else null
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args[0] as String)
                "getString" -> values[args[0] as String] as String? ?: args[1]
                "getBoolean" -> values[args[0] as String] as? Boolean ?: args[1] as Boolean
                "getInt" -> values[args[0] as String] as? Int ?: args[1] as Int
                "edit" -> editor()
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }
}
