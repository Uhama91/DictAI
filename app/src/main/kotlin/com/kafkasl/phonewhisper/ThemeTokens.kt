package com.kafkasl.phonewhisper

import android.content.Context
import android.content.res.Configuration

/** Values used by the native notebook surfaces in the active appearance. */
data class ThemePalette(
    val bg: Int,
    val surface: Int,
    val raised: Int,
    val stroke: Int,
    val line: Int,
    val ink: Int,
    val inkMuted: Int,
    val green: Int,
    val blue: Int,
    val red: Int,
    /** Saturated recording mark; kept separate from the softer error accent. */
    val recordingRed: Int,
    /** Ink used for the pause glyph over its small sage marker. */
    val pauseInk: Int,
    val errorText: Int,
    val onGreen: Int,
)

/** Design tokens "carnet sombre" DictAI. Source unique de vérité couleurs/dims. */
object ThemeTokens {
    /** Legacy dark constants retained for non-view formatting and compatibility. */
    const val BG = 0xFF201F1C.toInt()          // charbon chaud
    const val SURFACE = 0xFF2B2925.toInt()      // carte-note
    const val RAISED = 0xFF35322D.toInt()       // commandes et lignes de notes
    const val STROKE = 0x26E8DCC9              // filet crème discret
    const val LINE = 0x12E8DCC9
    const val INK = 0xFFF5EEE4.toInt()          // crème
    const val INK_MUTED = 0xFFBDB2A5.toInt()    // texte secondaire lisible
    const val GREEN = 0xFFAFD0AD.toInt()        // sauge
    const val BLUE = 0xFFA9BEDA.toInt()
    const val RED = 0xFFE89B89.toInt()

    val DARK = ThemePalette(
        bg = BG,
        surface = SURFACE,
        raised = RAISED,
        stroke = STROKE,
        line = LINE,
        ink = INK,
        inkMuted = INK_MUTED,
        green = GREEN,
        blue = BLUE,
        red = RED,
        recordingRed = 0xFFF06B60.toInt(),
        pauseInk = INK,
        errorText = 0xFFFFB4A6.toInt(),
        onGreen = BG,
    )

    /** Light page palette mirrors the selected ivoire/encre/sauge reference. */
    val LIGHT = ThemePalette(
        bg = 0xFFF8F4EB.toInt(),
        surface = 0xFFFFFCF6.toInt(),
        raised = 0xFFF0EEE6.toInt(),
        stroke = 0x26736F67,
        line = 0x1A7C8B84,
        ink = 0xFF253239.toInt(),
        inkMuted = 0xFF5B6870.toInt(),
        green = 0xFF55725E.toInt(),
        blue = 0xFF6D8395.toInt(),
        red = 0xFFD18A78.toInt(),
        recordingRed = 0xFFB63F38.toInt(),
        pauseInk = 0xFF253239.toInt(),
        errorText = 0xFF8F4A40.toInt(),
        onGreen = 0xFFFFFCF6.toInt(),
    )

    fun palette(ctx: Context): ThemePalette {
        val isSystemNight = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return paletteFor(ThemeModeStore.read(ctx), isSystemNight)
    }

    fun paletteFor(mode: ThemeMode, isSystemNight: Boolean): ThemePalette = when (mode) {
        ThemeMode.LIGHT -> LIGHT
        ThemeMode.DARK -> DARK
        ThemeMode.SYSTEM -> if (isSystemNight) DARK else LIGHT
    }

    fun dp(ctx: Context, n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, n: Float) = n * ctx.resources.displayMetrics.density
}
