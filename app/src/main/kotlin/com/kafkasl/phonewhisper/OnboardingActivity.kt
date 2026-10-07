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
import android.os.Handler
import android.os.Looper
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
        val visible: () -> Boolean = { true },
        val required: Boolean = true
    )

    private val prefs by lazy { getSharedPreferences("whisperpin", MODE_PRIVATE) }
    private lateinit var stepsContainer: LinearLayout
    private lateinit var startBtn: MaterialButton
    private var extrasExpanded = false
    private val main = Handler(Looper.getMainLooper())
    private var connectionChecks = 0
    private var lastConnectionStatus: AccessibilityServiceStatus? = null
    private val refreshConnection = object : Runnable {
        override fun run() {
            val status = AccessibilitySetup.status(this@OnboardingActivity)
            if (status != lastConnectionStatus) build()
            if (++connectionChecks < 10) main.postDelayed(this, 500)
        }
    }

    @Volatile private var modelDownloading = false
    private var modelMsg: String? = null
    private var modelStatusView: TextView? = null
    private var modelProgressView: LinearProgressIndicator? = null
    private var modelProgress = 0f
    private val palette: ThemePalette
        get() = ThemeTokens.palette(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            isFillViewport = true
        }
        val root = vertical(dp(22)).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        root.addView(TextView(this).apply {
            text = "Configuration de DictAI"
            textSize = 30f
            setTextColor(palette.green)
            ResourcesCompat.getFont(this@OnboardingActivity, R.font.caveat)?.let { typeface = it }
            setPadding(0, dp(36), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Trois étapes pour dicter. Activez ensuite l’insertion automatique si vous souhaitez écrire directement dans les autres applications."
            textSize = 15f
            setTextColor(palette.inkMuted)
            setPadding(0, 0, 0, dp(12))
        })

        stepsContainer = vertical(0)
        root.addView(stepsContainer)

        startBtn = MaterialButton(this).apply {
            text = "DictAI est prêt — démarrer"
            textSize = 16f
            cornerRadius = dp(14)
            setBackgroundColor(palette.green)
            setTextColor(palette.onGreen)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20); bottomMargin = dp(28)
            }
            setOnClickListener { finishSetup() }
        }

        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
        connectionChecks = 0
        main.postDelayed(refreshConnection, 500)
    }

    override fun onPause() {
        main.removeCallbacks(refreshConnection)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
    }

    // ---- étapes ----

    private fun isXiaomi(): Boolean {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    private fun steps(): List<Step> {
        val onboardingModel = recommendedModel()
        return listOf(
        Step("mic", "Microphone", "Pour enregistrer et transcrire votre voix.",
            { hasPerm(Manifest.permission.RECORD_AUDIO) }, "Autoriser",
            { requestRuntimePermission(Manifest.permission.RECORD_AUDIO, 1) }),

        Step("model", "Modèle de transcription (FR/EN)",
            "Téléchargez ${onboardingModel.name} (~${onboardingModel.sizeMb} Mo, WiFi conseillé) pour dicter hors-ligne en français ou en anglais. Le téléchargement continue pendant les autres étapes.",
            { MODEL_CATALOG.any { ModelDownloader.isInstalled(this, it) } }, "Télécharger (~${onboardingModel.sizeMb} Mo)",
            { startModelDownload() }),

        Step("overlay", "Afficher par-dessus les apps", "Pour la pastille flottante au-dessus de toutes les apps.",
            { Settings.canDrawOverlays(this) }, "Ouvrir",
            { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),

        Step("accessibility", "Insérer dans les applications",
            AccessibilitySetup.explanation(AccessibilitySetup.status(this)),
            { AccessibilitySetup.status(this) == AccessibilityServiceStatus.CONNECTED },
            if (AccessibilitySetup.status(this) == AccessibilityServiceStatus.ENABLED_NOT_CONNECTED) "Reconnecter" else "Activer l’insertion",
            { AccessibilitySetup.showHelp(this) }, required = false),

        Step("interrupt", "Désactiver « Interrompre si non utilisée »",
            "En haut de la fiche de l'app, désactivez « Interrompre l'activité si l'app n'est pas utilisée » (sinon HyperOS retire les permissions).",
            { null }, "Ouvrir la fiche", { openAppDetails() }, visible = { isXiaomi() }, required = false),

        Step("battery", "Batterie sans restriction", "Pour que le système ne tue pas la pastille en arrière-plan.",
            { isIgnoringBattery() }, "Autoriser",
            { runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }, required = false),

        Step("autostart", "Démarrage automatique (autostart)",
            "Activez DictAI dans la liste de démarrage automatique pour qu'il survive aux nettoyages de RAM.",
            { null }, "Ouvrir", { openAutostart() }, visible = { isXiaomi() }, required = false),

        Step("notif", "Notifications", "Pour retrouver les commandes de DictAI dans les notifications. Vous pourrez l’activer plus tard.",
            { hasPerm(Manifest.permission.POST_NOTIFICATIONS) }, "Autoriser",
            { if (Build.VERSION.SDK_INT >= 33) requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS, 2) },
            visible = { Build.VERSION.SDK_INT >= 33 }, required = false)
        )
    }

    private fun stepDone(s: Step): Boolean = s.detect() ?: prefs.getBoolean("onb_${s.id}", false)

    private fun build() {
        stepsContainer.removeAllViews()
        modelStatusView = null
        modelProgressView = null
        val visible = steps().filter { it.visible() }
        val core = visible.filter { it.required }
        lastConnectionStatus = AccessibilitySetup.status(this)
        stepsContainer.addView(section("Pour commencer · ${core.count(::stepDone)}/${core.size}"))
        core.forEachIndexed { index, step -> stepsContainer.addView(stepCard(index + 1, step, stepDone(step))) }
        val insertion = visible.single { it.id == "accessibility" }
        stepsContainer.addView(section("Insertion automatique · facultative"))
        stepsContainer.addView(stepCard(null, insertion, stepDone(insertion)))
        if (!stepDone(insertion) && Build.VERSION.SDK_INT >= 33) {
            stepsContainer.addView(MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Activation grisée ou bloquée ?"
                setTextColor(palette.green)
                setOnClickListener { AccessibilitySetup.showRestrictedHelp(this@OnboardingActivity) }
            })
        }
        startBtn.isEnabled = core.all(::stepDone)
        startBtn.text = if (stepDone(insertion)) "Démarrer DictAI" else "Continuer avec la copie manuelle"
        startBtn.alpha = if (startBtn.isEnabled) 1f else .45f
        stepsContainer.addView(startBtn)
        stepsContainer.addView(MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = if (extrasExpanded) "Masquer les réglages complémentaires" else "Batterie et notifications · conseillé"
            setTextColor(palette.green)
            setOnClickListener { extrasExpanded = !extrasExpanded; build() }
        })
        if (extrasExpanded) visible.filter { !it.required && it.id != "accessibility" }.forEach {
            stepsContainer.addView(stepCard(null, it, stepDone(it)))
        }
    }

    private fun section(label: String) = TextView(this).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(palette.inkMuted)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun stepCard(index: Int?, s: Step, done: Boolean): View {
        val card = vertical(14).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = ThemeTokens.dpf(this@OnboardingActivity, 11f)
                setColor(palette.surface)
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(5) }
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply {
            text = if (done) "✓" else index?.toString() ?: "+"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (done) palette.onGreen else palette.ink)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (done) palette.green else palette.raised) }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) }
        })
        heading.addView(TextView(this).apply {
            text = s.title
            textSize = 17f
            setTextColor(if (done) palette.inkMuted else palette.ink)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        card.addView(heading)
        if (!done) {
            card.addView(TextView(this).apply {
                text = s.desc
                textSize = 14f
                setTextColor(palette.inkMuted)
                setPadding(0, dp(8), 0, dp(4))
            })
            if (s.id == "model" && modelDownloading) {
                val tv = TextView(this).apply {
                    text = modelMsg ?: "Installation : 0 %"; textSize = 14f; setTextColor(palette.green)
                }
                val progress = LinearProgressIndicator(this).apply {
                    isIndeterminate = false
                    this.progress = (modelProgress * 100).toInt()
                    layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) }
                }
                modelStatusView = tv; modelProgressView = progress
                card.addView(tv); card.addView(progress)
            } else {
                card.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = s.actionLabel
                    textSize = 14f
                    setTextColor(palette.green)
                    strokeColor = android.content.res.ColorStateList.valueOf(palette.green and 0x66FFFFFF)
                    setOnClickListener { s.action() }
                })
                if (s.detect() == null) card.addView(MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "C’est fait"
                    setTextColor(palette.green)
                    setOnClickListener { prefs.edit().putBoolean("onb_${s.id}", true).apply(); build() }
                })
            }
        }
        return card
    }

    private fun finishSetup() {
        if (!steps().filter { it.required && it.visible() }.all(::stepDone)) { build(); return }
        ModelDownloader.reconcileSelectedModel(this)
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

    private fun requestRuntimePermission(permission: String, code: Int) {
        val key = "onb_requested_$code"
        if (prefs.getBoolean(key, false) && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            openAppDetails()
        } else {
            prefs.edit().putBoolean(key, true).apply()
            ActivityCompat.requestPermissions(this, arrayOf(permission), code)
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
