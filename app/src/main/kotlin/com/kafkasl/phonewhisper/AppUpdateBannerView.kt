package com.kafkasl.phonewhisper

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class AppUpdateBannerView(context: Context, update: AppUpdate) : LinearLayout(context) {
    init {
        val palette = ThemeTokens.palette(context)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        orientation = VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(10))
        background = GradientDrawable().apply { setColor(palette.surface); cornerRadius = dp(14).toFloat(); setStroke(dp(1), palette.green) }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
        val open = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "Aucun navigateur disponible pour ouvrir le téléchargement", Toast.LENGTH_LONG).show()
            }
        }
        isClickable = true; isFocusable = true
        contentDescription = "DictAI ${update.version} est disponible. Télécharger la mise à jour."
        setOnClickListener { open() }
        addView(TextView(context).apply {
            text = "DictAI ${update.version} est disponible"
            textSize = 16f; setTextColor(palette.ink); setTypeface(typeface, Typeface.BOLD)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        })
        addView(TextView(context).apply {
            text = "Télécharger la mise à jour ↗"
            textSize = 15f; setTextColor(palette.green); gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }
}
