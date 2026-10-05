package com.kafkasl.phonewhisper

import android.os.Bundle
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date

class AccountSyncActivity : AppCompatActivity() {
    private val coordinator by lazy { PreferenceSyncCoordinator.get(this) }
    private val observer: () -> Unit = { if (!isFinishing && !isDestroyed) render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        consumeCallback(intent)
    }
    override fun onStart() { super.onStart(); coordinator.observe(observer) }
    override fun onStop() { coordinator.unobserve(observer); super.onStop() }
    override fun onResume() { super.onResume(); render(); coordinator.requestSync() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeCallback(intent) }
    private fun consumeCallback(source: Intent?) {
        val callback = source?.data?.toString() ?: return
        source.data = null
        coordinator.finishGoogleLogin(callback)
    }
    private fun render() {
        val palette = ThemeTokens.palette(this)
        val session = coordinator.currentSession()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(28))
            background = NotebookBackgroundDrawable(this@AccountSyncActivity)
        }
        root.addView(TextView(this).apply {
            text = "‹ Retour"
            textSize = 17f
            setTextColor(palette.green)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Compte et synchronisation"
            textSize = 36f
            setTextColor(palette.green)
            ResourcesCompat.getFont(this@AccountSyncActivity, R.font.caveat)?.let { typeface = it }
            setPadding(0, dp(12), 0, dp(16))
        })
        root.addView(body("DictAI fonctionne entièrement en local, sans compte. Connectez-vous pour retrouver vos préférences, dossiers et notes sur vos appareils."))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(palette.surface); setStroke(dp(1), palette.stroke)
            }
            layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(20); bottomMargin = dp(18) }
        }
        card.addView(TextView(this).apply {
            text = session?.email ?: "Mode local"
            textSize = 19f
            setTextColor(palette.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(body(statusLabel(session != null), 14f))
        if (session == null) {
            card.addView(body("En vous connectant, les préférences, dossiers et notes de cet appareil seront partagés avec le compte choisi.", 14f))
            card.addView(button("Se connecter avec Google") {
                try {
                    val url = coordinator.beginGoogleLogin()
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) {
                    Toast.makeText(this, "La connexion est indisponible. Le mode local reste disponible.", Toast.LENGTH_LONG).show()
                }
            }.apply { isEnabled = coordinator.available })
            if (!coordinator.available) card.addView(body("La connexion est temporairement indisponible dans cette version.", 13f))
        } else {
            card.addView(button("Synchroniser maintenant") { coordinator.requestSync() })
            card.addView(button("Déconnecter cet appareil", primary = false) {
                AlertDialog.Builder(this).setTitle("Déconnecter cet appareil ?")
                    .setMessage("Vos notes et préférences restent disponibles ici. La synchronisation de cet appareil s’arrête.")
                    .setPositiveButton("Déconnecter") { _, _ ->
                        try { coordinator.disconnect() }
                        catch (_: Exception) { Toast.makeText(this, "La déconnexion n’a pas abouti. Réessayez.", Toast.LENGTH_LONG).show() }
                    }
                    .setNegativeButton("Annuler",null).show()
            })
        }
        root.addView(card)
        root.addView(section("Partagé entre vos appareils"))
        root.addView(body("Vocabulaire et corrections\nPréférences de dictée et formats personnels\nDossiers, notes et images des notes"))
        root.addView(section("Votre appareil garde ses réglages"))
        root.addView(body("Les clés API, modèles téléchargés, permissions et positions de la pastille restent locaux. Les enregistrements audio ne sont pas synchronisés.", 14f))
        root.addView(body("La connexion est facultative. Vos modifications restent utilisables hors ligne et sont envoyées quand une connexion est disponible.",14f))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.bg)
            addView(root)
        }
        setContentView(scroll)
    }
    private fun statusLabel(connected: Boolean): String = when(coordinator.status()) {
        "connecting" -> "Connexion en cours…"
        "pending" -> "Modifications en attente de synchronisation."
        "syncing" -> "Synchronisation en cours…"
        "synced" -> {
            val last = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(coordinator.lastSyncedAt()))
            "Dernier échange : $last\n${coordinator.deviceCount()} appareil(s) relié(s)."
        }
        "network_error" -> "En attente de connexion. Vos modifications restent sur cet appareil."
        "reconnect" -> "Reconnectez-vous pour reprendre la synchronisation."
        "data_error" -> "La synchronisation est interrompue. Vos données locales restent disponibles."
        "login_error" -> "La connexion n’a pas abouti. Vous pouvez réessayer."
        else -> if (connected) "Compte connecté." else "Vos données restent sur cet appareil."
    }
    private fun section(value: String) = TextView(this).apply {
        text = value; textSize = 18f
        setTextColor(ThemeTokens.palette(this@AccountSyncActivity).ink)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0,dp(16),0,dp(4))
    }
    private fun body(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(ThemeTokens.palette(this@AccountSyncActivity).inkMuted)
        setLineSpacing(dp(3).toFloat(),1f)
        setPadding(0,dp(8),0,dp(6))
    }
    private fun button(value: String, primary: Boolean = true, clicked: () -> Unit) = MaterialButton(this).apply {
        text = value; isAllCaps = false; textSize = 16f
        minHeight = dp(52)
        val palette = ThemeTokens.palette(this@AccountSyncActivity)
        backgroundTintList = ColorStateList.valueOf(if (primary) palette.green else palette.surface)
        setTextColor(if(primary) palette.onGreen else palette.ink)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) }
        setOnClickListener { clicked() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
