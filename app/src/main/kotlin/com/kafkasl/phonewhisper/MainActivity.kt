package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var modelContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton
    )

    private fun showFormatsDialog() {
        val store = PostProcessingFormats(this)
        val formats = store.all()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Formats · ${store.selected().name}")
            .setItems((formats.map { it.name } + "＋ Créer un format").toTypedArray()) { _, index ->
                if (index == formats.size) editFormat(null)
                else {
                    val format = formats[index]
                    val custom = store.custom().any { it.id == format.id }
                    val options = if (custom) arrayOf("Utiliser", "Modifier", "Supprimer") else arrayOf("Utiliser")
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(format.name)
                        .setItems(options) { _, action ->
                            when (action) {
                                0 -> { store.select(format); Toast.makeText(this, "Format : ${format.name}", Toast.LENGTH_SHORT).show() }
                                1 -> editFormat(format)
                                2 -> androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("Supprimer ${format.name} ?")
                                    .setPositiveButton("Supprimer") { _, _ -> store.delete(format.id) }
                                    .setNegativeButton("Annuler", null).show()
                            }
                        }.show()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun editFormat(format: PostProcessingFormat?) {
        val name = EditText(this).apply {
            hint = "Nom du format"; setSingleLine(true); setText(format?.name.orEmpty())
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
        }
        val instructions = EditText(this).apply {
            hint = "Ex. : organise mes idées en liste à puces, sans ajouter d'information."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; setText(format?.instructions.orEmpty())
            filters = arrayOf(android.text.InputFilter.LengthFilter(4000))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(name); addView(instructions)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (format == null) "Créer un format" else "Modifier le format")
            .setMessage("Décrivez la mise en forme souhaitée. Nécessite le nettoyage cloud et une clé OpenRouter ; le texte y est envoyé après la dictée.")
            .setView(content).setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    name.text.isNullOrBlank() -> name.error = "Indiquez un nom"
                    instructions.text.isNullOrBlank() -> instructions.error = "Indiquez les consignes"
                    else -> {
                        PostProcessingFormats(this).save(format?.id, name.text.toString(), instructions.text.toString())
                        dialog.dismiss(); showFormatsDialog()
                    }
                }
            }
        }
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        // Header row : mic icon (encre verte) + "DictAI" en Caveat
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(56), 0, dp(20))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_mic)
                imageTintList = ColorStateList.valueOf(ThemeTokens.GREEN)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    rightMargin = dp(10)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "DictAI"
                ResourcesCompat.getFont(this@MainActivity, R.font.caveat)?.let { typeface = it }
                textSize = 40f
                setTextColor(ThemeTokens.GREEN)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        root.addView(header)

        val spikeBtn = android.widget.Button(this).apply {
            text = "Activer le bouton flottant"
            setTextColor(ThemeTokens.BG)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ThemeTokens.dpf(this@MainActivity, 12f)
                setColor(ThemeTokens.GREEN)
            }
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
            setOnClickListener {
                if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                    return@setOnClickListener
                }
                startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
            }
        }
        root.addView(spikeBtn)

        // Status row
        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Setup Section ---
        root.addView(sectionHeader("Setup"))

        root.addView(settingsRow("Assistant d'installation", "Configurer / vérifier les permissions pas à pas") {
            startActivity(Intent(this, OnboardingActivity::class.java))
        })

        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // --- Modèles locaux ---
        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Modèles locaux"))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))
        root.addView(modelContainer)

        // --- Réglages ---
        root.addView(sectionHeader("Réglages"))

        val languagePrefs = PersistencePrefs(this)
        root.addView(settingsRow("Langue de dictée", languageLabel(languagePrefs.dictationLanguage)) {
            showLanguageDialog()
        })

        // Espace automatique en fin de dictée
        val spaceSwitch = MaterialSwitch(this).apply {
            isChecked = PersistencePrefs(this@MainActivity).trailingSpace
            greenTint()
            setOnCheckedChangeListener { _, on ->
                PersistencePrefs(this@MainActivity).trailingSpace = on
            }
        }
        root.addView(settingsRow("Espace après chaque dictée",
            "Ajoute un espace à la fin de la transcription", spaceSwitch))

        val vocabRow = settingsRow("Mon vocabulaire", "Corrections explicites (une par ligne)") {
            val et = EditText(this).apply {
                setText(Vocabulary.getRaw(this@MainActivity))
                isSingleLine = false
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 5
                gravity = Gravity.TOP or Gravity.START
                hint = "Dydy\ndidi => Dydy"
                setPadding(dp(16), dp(12), dp(16), dp(12))
                inkColors()
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Mon vocabulaire")
                .setMessage("Une correction par ligne, au format « entendu => voulu ».")
                .setView(et)
                .setPositiveButton("Enregistrer") { _, _ ->
                    Vocabulary.setRaw(this, et.text.toString())
                    Toast.makeText(this, "Vocabulaire enregistré", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        root.addView(vocabRow)

        val cleanupSwitch = MaterialSwitch(this).apply {
            isChecked = languagePrefs.cloudCleanupEnabled
            greenTint()
            setOnCheckedChangeListener { _, on -> languagePrefs.cloudCleanupEnabled = on }
        }
        root.addView(settingsRow(
            "Nettoyage cloud (optionnel)",
            "Si activé, la transcription finale est envoyée via OpenRouter, selon les paramètres de confidentialité de votre compte.",
            cleanupSwitch,
        ))

        root.addView(settingsRow("Modèle de nettoyage", languagePrefs.cloudModel().label) {
            showCloudModelDialog()
        })
        root.addView(settingsRow("Formats de post-traitement", "Texte, liste, mail et formats personnalisés · glisser directement vers le haut sur la pastille") {
            showFormatsDialog()
        })
        val credentialStore = SecureCredentialStore(this)
        root.addView(settingsRow(
            "Clé OpenRouter",
            if (credentialStore.has()) "Clé enregistrée (masquée)" else "Aucune clé enregistrée",
        ) {
            showCredentialDialog()
        })

        // Fond "page de carnet" : contenu à droite du filet de marge vert (~30dp)
        root.background = NotebookBackgroundDrawable(this)
        root.minimumHeight = resources.displayMetrics.heightPixels
        root.setPadding(dp(46), root.paddingTop, dp(16), root.paddingBottom)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeTokens.BG)
            addView(root)
        })

        // Nouveaux utilisateurs : si la config de base manque et que l'assistant n'a jamais été
        // terminé, on lance directement l'onboarding d'installation.
        val onbDone = getSharedPreferences("whisperpin", MODE_PRIVATE).getBoolean("onb_complete", false)
        val coreMissing = !hasPerm(Manifest.permission.RECORD_AUDIO) ||
            !android.provider.Settings.canDrawOverlays(this) ||
            InjectionGateway.current() == null
        if (!onbDone && coreMissing) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (android.provider.Settings.canDrawOverlays(this)) {
            startForegroundService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_ARM_MIC)
            )
        }
        refresh()
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Model Rows ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(radio)
        }

        val row = settingsRow(
            if (model.recommended) model.name else model.name,
            modelCardSubtitle(model),
            rightContainer
        ) {
            onModelAction(model)
        }
        
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        
        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn
        )
        refreshCard(model)
        
        return row
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return

        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }

        views.dlBtn.isEnabled = false
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = false
        views.progress.progress = 0
        views.subtitle.text = "Installation\u202F: 0\u202F% · ${model.runtimeLabel}"

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading, is DownloadState.Extracting -> {
                        val progress = installationProgress(state)
                        views.progress.isIndeterminate = false
                        views.progress.progress = (progress * 100).toInt()
                        views.subtitle.text = "Installation\u202F: ${(progress * 100).toInt()}\u202F% · ${model.runtimeLabel}"
                    }
                    is DownloadState.Done -> {
                        views.progress.visibility = View.GONE
                        selectModel(model.archive)
                        toast("${model.name} est prêt")
                    }
                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = "Erreur : ${state.message} · ${model.runtimeLabel}"
                        views.dlBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun selectModel(archive: String) {
        val model = MODEL_CATALOG.firstOrNull { it.archive == archive }
        if (model != null && ModelDownloader.isInstalled(this, model)) {
            prefs().edit().putString("model_name", archive).apply()
        } else {
            ModelDownloader.reconcileSelectedModel(this)
        }
        refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val installed = ModelDownloader.isInstalled(this, model)
        val active = installed && ModelDownloader.reconcileSelectedModel(this) == model.archive
        
        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        
        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = modelCardSubtitle(model)
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    private fun modelCardSubtitle(model: Model) =
        "${model.quality} · ${model.sizeMb} MB · ${model.runtimeLabel}"

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = InjectionGateway.current() != null
        val selectedModel = ModelDownloader.reconcileSelectedModel(this)
        val activeModel = MODEL_CATALOG.firstOrNull { it.archive == selectedModel }
        val hasModel = selectedModel != null

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        // Ready logic
        val ready = audio && acc && hasModel

        statusSubtitle.text = if (ready) {
            "Prêt — ${activeModel?.runtimeLabel} — touchez la pastille pour dicter"
        } else {
            "Configuration requise"
        }
        statusSubtitle.setTextColor(if (ready) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
        
        refreshAllCards()
    }

    // --- UI Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        // Carte-note sobre : surface arrondie + filet fin
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ThemeTokens.dpf(this@MainActivity, 11f)
            setColor(ThemeTokens.SURFACE)
            setStroke(dp(1), ThemeTokens.STROKE)
        }
        val rowBg = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), cardBg, null)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rowBg
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            }
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) setOnClickListener { onClick() }
        }

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }

        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(ThemeTokens.INK)
        })

        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(ThemeTokens.INK_MUTED)
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(ThemeTokens.GREEN)
        setPadding(dp(2), dp(22), dp(2), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    /** Force du texte argenté sur fond sombre pour les champs des dialogs. */
    private fun EditText.inkColors() {
        setTextColor(ThemeTokens.INK)
        setHintTextColor(ThemeTokens.INK_MUTED)
    }

    /** Teinte un switch en encre verte (coché) / atténué (décoché). */
    private fun MaterialSwitch.greenTint() {
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x665BCB95.toInt(), 0x33FFFFFF)
        )
        val thumb = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ThemeTokens.GREEN, ThemeTokens.INK_MUTED)
        )
        trackTintList = track
        thumbTintList = thumb
    }

    private fun showLanguageDialog() {
        val prefs = PersistencePrefs(this)
        val choices = arrayOf("Français", "English")
        val checked = DictationLanguage.entries.indexOf(prefs.dictationLanguage)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Langue de dictée")
            .setSingleChoiceItems(choices, checked) { dialog, which ->
                prefs.dictationLanguage = DictationLanguage.entries[which]
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun showCloudModelDialog() {
        val prefs = PersistencePrefs(this)
        val models = CloudModelCatalog.all
        val checked = models.indexOf(prefs.cloudModel())
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Modèle de nettoyage")
            .setSingleChoiceItems(models.map { it.label }.toTypedArray(), checked) { dialog, which ->
                prefs.setCloudModel(models[which])
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun showCredentialDialog() {
        val field = EditText(this).apply {
            hint = "Clé API"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ThemeTokens.INK)
            setHintTextColor(ThemeTokens.INK_MUTED)
        }
        val store = SecureCredentialStore(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clé OpenRouter")
            .setMessage("La clé est chiffrée sur cet appareil et n’est jamais affichée à nouveau.")
            .setView(field)
            .setPositiveButton("Enregistrer") { _, _ ->
                when (store.save(field.text.toString())) {
                    CredentialSaveResult.Saved -> {
                        toast("Clé enregistrée")
                        recreate()
                    }
                    CredentialSaveResult.Rejected -> toast("Saisissez une clé OpenRouter")
                    CredentialSaveResult.Failed -> toast("Stockage sécurisé indisponible")
                }
            }
            .setNeutralButton("Supprimer") { _, _ ->
                val deleted = store.delete()
                toast(credentialDeletionFeedback(deleted))
                if (deleted) recreate()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun languageLabel(language: DictationLanguage) = when (language) {
        DictationLanguage.FRENCH -> "Français"
        DictationLanguage.ENGLISH -> "English"
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        internal fun credentialDeletionFeedback(deleted: Boolean): String =
            if (deleted) "Clé supprimée" else "Suppression de la clé impossible"
    }
}
