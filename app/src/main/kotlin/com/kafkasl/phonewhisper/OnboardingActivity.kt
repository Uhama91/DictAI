package com.kafkasl.phonewhisper

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Assistant d'installation : checklist guidée des permissions/réglages nécessaires.
 * Les étapes détectables passent au vert toutes seules à chaque retour dans l'app (onResume) ;
 * les étapes OEM non-détectables (Xiaomi/HyperOS) se marquent « C'est fait » manuellement.
 * Étapes Xiaomi masquées sur les autres marques → parcours court.
 */
class OnboardingActivity : AppCompatActivity() {

    /** detect: true=fait, false=à faire, null=non détectable (manuel). */
    private data class Step(
        val id: String,
        val title: String,
        val desc: String,
        val detect: () -> Boolean?,
        val actionLabel: String,
        val action: () -> Unit,
        val visible: () -> Boolean = { true }
    )

    private val prefs by lazy { getSharedPreferences("whisperpin", MODE_PRIVATE) }
    private lateinit var stepsContainer: LinearLayout
    private lateinit var startBtn: MaterialButton

    @Volatile private var modelDownloading = false
    private var modelMsg: String? = null
    private var modelStatusView: TextView? = null
    private var modelProgressView: LinearProgressIndicator? = null
    private var modelProgress = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(ThemeTokens.BG)
            isFillViewport = true
        }
        val root = vertical(dp(22)).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        root.addView(TextView(this).apply {
            text = "Configuration de DictAI"
            textSize = 30f
            setTextColor(ThemeTokens.GREEN)
            ResourcesCompat.getFont(this@OnboardingActivity, R.font.caveat)?.let { typeface = it }
            setPadding(0, dp(36), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Quelques autorisations sont nécessaires pour que la pastille reste affichée par-dessus toutes tes apps et transcrive ta voix. Suis les étapes — elles se valident automatiquement."
            textSize = 15f
            setTextColor(ThemeTokens.INK_MUTED)
            setPadding(0, 0, 0, dp(12))
        })

        stepsContainer = vertical(0)
        root.addView(stepsContainer)

        startBtn = MaterialButton(this).apply {
            text = "DictAI est prêt — démarrer"
            textSize = 16f
            cornerRadius = dp(14)
            setBackgroundColor(ThemeTokens.GREEN)
            setTextColor(0xFF0E0E12.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20); bottomMargin = dp(28)
            }
            setOnClickListener { finishSetup() }
        }
        root.addView(startBtn)

        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); build() }

    // ---- étapes ----

    private fun isXiaomi(): Boolean {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    private fun steps(): List<Step> = listOf(
        Step("mic", "Microphone", "Pour enregistrer et transcrire ta voix.",
            { hasPerm(Manifest.permission.RECORD_AUDIO) }, "Autoriser",
            { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1) }),

        Step("model", "Modèle de transcription (FR/EN)",
            "Télécharge Parakeet 0.6B (~465 Mo, WiFi conseillé) pour dicter hors-ligne en français ou en anglais. Le téléchargement continue pendant que tu fais les autres étapes.",
            { ModelDownloader.isInstalled(this, recommendedModel()) }, "Télécharger (~465 Mo)",
            { startModelDownload() }),

        Step("overlay", "Afficher par-dessus les apps", "Pour la pastille flottante au-dessus de toutes les apps.",
            { Settings.canDrawOverlays(this) }, "Ouvrir",
            { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),

        Step("restricted", "Autoriser les paramètres restreints",
            "Xiaomi bloque l'accessibilité des apps installées hors Play Store. Dans la fiche de l'app : descends tout en bas → active « Autoriser les paramètres restreints ».",
            { null }, "Ouvrir la fiche", { openAppDetails() }, visible = { isXiaomi() }),

        Step("accessibility", "Service d'accessibilité", "Pour insérer le texte transcrit dans n'importe quel champ.",
            { WhisperAccessibilityService.controller != null }, "Ouvrir",
            { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),

        Step("interrupt", "Désactiver « Interrompre si non utilisée »",
            "En haut de la fiche de l'app, désactive « Interrompre l'activité si l'app n'est pas utilisée » (sinon HyperOS retire les permissions).",
            { null }, "Ouvrir la fiche", { openAppDetails() }, visible = { isXiaomi() }),

        Step("battery", "Batterie sans restriction", "Pour que le système ne tue pas la pastille en arrière-plan.",
            { isIgnoringBattery() }, "Autoriser",
            { runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }),

        Step("autostart", "Démarrage automatique (autostart)",
            "Active DictAI dans la liste de démarrage automatique pour qu'il survive aux nettoyages de RAM.",
            { null }, "Ouvrir", { openAutostart() }, visible = { isXiaomi() }),

        Step("notif", "Notifications", "Pour la notification persistante qui garde la pastille active.",
            { hasPerm(Manifest.permission.POST_NOTIFICATIONS) }, "Autoriser",
            { if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) },
            visible = { Build.VERSION.SDK_INT >= 33 })
    )

    private fun stepDone(s: Step): Boolean = s.detect() ?: prefs.getBoolean("onb_${s.id}", false)

    private fun build() {
        stepsContainer.removeAllViews()
        val visible = steps().filter { it.visible() }
        var n = 1
        var allDone = true
        for (s in visible) {
            val done = stepDone(s)
            if (!done) allDone = false
            stepsContainer.addView(stepCard(n++, s, done))
        }
        startBtn.isEnabled = allDone
        startBtn.alpha = if (allDone) 1f else 0.45f
    }

    private fun stepCard(index: Int, s: Step, done: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ThemeTokens.dpf(this@OnboardingActivity, 11f)
                setColor(ThemeTokens.SURFACE)
                setStroke(dp(1), if (done) ThemeTokens.GREEN and 0x55FFFFFF else ThemeTokens.STROKE)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            }
        }

        // pastille de statut ✓ / numéro
        card.addView(TextView(this).apply {
            text = if (done) "✓" else index.toString()
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (done) 0xFF0E0E12.toInt() else ThemeTokens.INK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (done) ThemeTokens.GREEN else 0x22FFFFFF)
            }
            val sz = dp(30)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = dp(12) }
        })

        val texts = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = s.title; textSize = 17f
            setTextColor(if (done) ThemeTokens.INK_MUTED else ThemeTokens.INK)
        })
        if (!done) texts.addView(TextView(this).apply {
            text = s.desc; textSize = 13f
            setTextColor(ThemeTokens.INK_MUTED); setPadding(0, dp(3), 0, 0)
        })
        card.addView(texts)

        if (!done && s.id == "model" && modelDownloading) {
            // Installation en cours : on montre la progression déterminée au lieu du bouton.
            val status = vertical(0).apply { gravity = Gravity.END }
            val tv = TextView(this).apply {
                text = modelMsg ?: "Installation\u202F: 0\u202F%"
                textSize = 13f; setTextColor(ThemeTokens.GREEN); gravity = Gravity.END
            }
            val progress = LinearProgressIndicator(this).apply {
                isIndeterminate = false
                this.progress = (modelProgress * 100).toInt()
                layoutParams = LinearLayout.LayoutParams(dp(104), dp(4)).apply {
                    topMargin = dp(6)
                }
            }
            modelStatusView = tv
            modelProgressView = progress
            status.addView(tv)
            status.addView(progress)
            card.addView(status)
        } else if (!done) {
            val actions = vertical(0).apply { gravity = Gravity.END }
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = s.actionLabel; textSize = 13f
                setTextColor(ThemeTokens.GREEN)
                strokeColor = android.content.res.ColorStateList.valueOf(ThemeTokens.GREEN and 0x66FFFFFF)
                setOnClickListener { s.action() }
            })
            // étape non détectable → bouton « C'est fait » pour la valider manuellement
            if (s.detect() == null) actions.addView(TextView(this).apply {
                text = "C'est fait ✓"; textSize = 13f
                setTextColor(ThemeTokens.GREEN); setPadding(dp(8), dp(6), dp(8), 0)
                gravity = Gravity.CENTER
                setOnClickListener { prefs.edit().putBoolean("onb_${s.id}", true).apply(); build() }
            })
            card.addView(actions)
        }
        return card
    }

    private fun finishSetup() {
        val ok = runCatching {
            startForegroundService(Intent(this, OverlayService::class.java))
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_ARM_MIC))
        }.isSuccess
        // Ne marquer terminé que si le service a bien démarré (sinon l'onboarding pourra se relancer).
        if (ok) prefs.edit().putBoolean("onb_complete", true).apply()
        else Toast.makeText(this, "Le démarrage a échoué — réessaie depuis l'app.", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        modelStatusView = null // éviter d'écrire sur une vue détachée depuis un callback de download
        modelProgressView = null
        super.onDestroy()
    }

    private fun recommendedModel() = MODEL_CATALOG.firstOrNull { it.recommended } ?: MODEL_CATALOG.first()

    private fun startModelDownload() {
        val model = recommendedModel()
        if (modelDownloading || ModelDownloader.isInstalled(this, model)) return
        modelDownloading = true; modelProgress = 0f; modelMsg = "Installation\u202F: 0\u202F%"; build()
        Toast.makeText(this, "Téléchargement du modèle (~${model.sizeMb} Mo)…", Toast.LENGTH_LONG).show()
        ModelDownloader.download(this, model) { st ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread // Activity partie → ne pas toucher l'UI
                when (st) {
                    is DownloadState.Downloading, is DownloadState.Extracting -> {
                        modelProgress = installationProgress(st)
                        modelMsg = "Installation\u202F: ${(modelProgress * 100).toInt()}\u202F%"
                        modelStatusView?.text = modelMsg
                        modelProgressView?.progress = (modelProgress * 100).toInt()
                    }
                    DownloadState.Done -> {
                        modelDownloading = false; modelProgress = 0f; modelMsg = null
                        getSharedPreferences("phonewhisper", MODE_PRIVATE)
                            .edit().putString("model_name", model.archive).apply()
                        build()
                    }
                    is DownloadState.Error -> {
                        modelDownloading = false; modelProgress = 0f; modelMsg = null
                        Toast.makeText(this, "Échec du téléchargement : ${st.message}", Toast.LENGTH_LONG).show()
                        build()
                    }
                }
            }
        }
    }

    // ---- helpers détection / intents ----

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openAppDetails() = startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    )

    private fun openAutostart() {
        val tries = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartDetailManagementActivity")
        )
        for (c in tries) {
            val ok = runCatching {
                startActivity(Intent().apply { component = c }); true
            }.getOrDefault(false)
            if (ok) return
        }
        openAppDetails() // repli si l'écran MIUI n'existe pas
    }

    // ---- mini helpers UI ----

    private fun dp(n: Int) = ThemeTokens.dp(this, n)
    private fun vertical(pad: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, 0, pad, 0)
    }
}
