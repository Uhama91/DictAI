package com.kafkasl.phonewhisper

import android.content.Context

/** Design tokens "carnet sombre" DictAI. Source unique de vérité couleurs/dims. */
object ThemeTokens {
    const val BG = 0xFF16161A.toInt()          // page charcoal
    const val SURFACE = 0xFF1F1F25.toInt()      // carte-note
    const val STROKE = 0x14FFFFFF              // filet carte ~8% blanc
    const val LINE = 0x10FFFFFF                // ligne réglée ~6% blanc
    const val INK = 0xFFE8E6E0.toInt()          // texte argenté
    const val INK_MUTED = 0xFF9A988F.toInt()    // texte atténué
    const val GREEN = 0xFF5BCB95.toInt()        // encre verte (accent)
    const val BLUE = 0xFF6BA6FF.toInt()         // encre bleue
    const val RED = 0xFFE5705F.toInt()          // rouge correction
    fun dp(ctx: Context, n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, n: Float) = n * ctx.resources.displayMetrics.density
}
