package com.kafkasl.phonewhisper

import android.content.Context

/** Design tokens "carnet sombre" DictAI. Source unique de vérité couleurs/dims. */
object ThemeTokens {
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
    fun dp(ctx: Context, n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, n: Float) = n * ctx.resources.displayMetrics.density
}
