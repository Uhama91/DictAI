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
import android.text.InputType
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
    private lateinit var keyRowSub: TextView
    private lateinit var modelContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton
    )

    private data class PromptRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView
    )

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

        // --- Engine Section ---
        
        val isCloud = !prefs().getBoolean("use_local", true)
        
        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
            greenTint()
        }
        val cloudRow = settingsRow("Use cloud transcription", "Requires OpenAI API key", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        root.addView(cloudRow)

        // Local Models section
        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))
        root.addView(modelContainer)

        // --- Post-traitement LLM local ---
        val ppSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = PostProcessPrompts.isEnabled(this@MainActivity)
            greenTint()
            setOnCheckedChangeListener { _, on ->
                PostProcessPrompts.setEnabled(this@MainActivity, on)
                if (on) {
                    android.widget.Toast.makeText(this@MainActivity,
                        "Préparation du modèle (~378 Mo en WiFi au 1er coup)…", android.widget.Toast.LENGTH_LONG).show()
                    kotlin.concurrent.thread { LlmPostProcessor.ensureLoaded(this@MainActivity) }
                } else {
                    kotlin.concurrent.thread { LlmPostProcessor.unload() }
                }
            }
        }
        root.addView(settingsRow("Post-traitement (local)", "Reformule la dictée avec un LLM hors-ligne", ppSwitch))

        root.addView(settingsRow("Modèle Qwen3-0.6B", if (LlmPostProcessor.ready) "Prêt" else "À préparer (active le toggle)") {
            android.widget.Toast.makeText(this@MainActivity, "Préparation du modèle…", android.widget.Toast.LENGTH_SHORT).show()
            kotlin.concurrent.thread { LlmPostProcessor.ensureLoaded(this@MainActivity) }
        })

        root.addView(settingsRow("Prompt de post-traitement", "Choisir / éditer (utilise \${output})") {
            showPromptManager()
        })

        // --- Settings Section ---
        root.addView(sectionHeader("Settings"))
        
        val keyRow = settingsRow("OpenAI API Key", "Tap to set") { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

        // Espace automatique en fin de dictée
        val spaceSwitch = MaterialSwitch(this).apply {
            isChecked = PersistencePrefs(this@MainActivity).trailingSpace
            greenTint()
            setOnCheckedChangeListener { _, on ->
                PersistencePrefs(this@MainActivity).trailingSpace = on
            }
        }
        root.addView(settingsRow("Espace après chaque dictée",
            "Ajoute une espace en fin de transcription", spaceSwitch))

        val vocabRow = settingsRow("Mon vocabulaire", "Mots favorisés + corrections (un par ligne)") {
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
                .setMessage("Un par ligne :\n• \"entendu => voulu\" = correction automatique (marche partout, y compris Parakeet local)\n• un mot seul = favorisé pour la transcription cloud (Whisper)")
                .setView(et)
                .setPositiveButton("Enregistrer") { _, _ ->
                    Vocabulary.setRaw(this, et.text.toString())
                    Toast.makeText(this, "Vocabulaire enregistré", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        root.addView(vocabRow)

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
            WhisperAccessibilityService.controller == null
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
            "${model.quality} · ${model.sizeMb} MB",
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
        views.subtitle.text = "Starting download..."

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading -> {
                        views.progress.progress = (state.progress * 100).toInt()
                        views.subtitle.text = "Downloading: ${(state.progress * 100).toInt()}%"
                    }
                    is DownloadState.Extracting -> {
                        views.progress.isIndeterminate = true
                        views.subtitle.text = "Extracting..."
                    }
                    is DownloadState.Done -> {
                        views.progress.visibility = View.GONE
                        selectModel(model.archive)
                        toast("${model.name} ready!")
                    }
                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = "Error: ${state.message}"
                        views.dlBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        refreshAllCards(); refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)
        
        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        
        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // --- Prompt Rows ---

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }

        val row = settingsRow(preset.title, preset.subtitle, radio) {
            selectPrompt(preset.key)
        }

        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        val prompt = when (key) {
            "custom" -> customPrompt()
            else -> promptPresets().firstOrNull { it.key == key }?.prompt
        } ?: return
        prefs().edit().putString("post_processing_prompt", prompt).apply()
        refreshPromptRows(); refresh()
    }

    private fun refreshPromptRow(preset: PromptPreset) {
        val views = promptRows[preset.key] ?: return
        val current = currentPrompt()
        val active = when (preset.key) {
            "custom" -> current != PostProcessor.DEV_PROMPT && current != PostProcessor.SIMPLE_PROMPT
            else -> current == preset.prompt
        }
        views.radio.isChecked = active
        views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
    }

    private fun refreshPromptRows() = promptPresets().forEach { refreshPromptRow(it) }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.controller != null
        val useLocal = prefs().getBoolean("use_local", true)
        val hasKey = !prefs().getString("api_key", "").isNullOrBlank()
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE

        val apiKey = prefs().getString("api_key", "") ?: ""
        keyRowSub.text = if (apiKey.isBlank()) "Tap to set"
                         else if (apiKey.length > 7) "sk-...${apiKey.takeLast(4)}"
                         else "sk-...***"

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur.isBlank() || !File(filesDir, "models/$cur").exists()) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }

        // Ready logic
        val localReady = useLocal && hasModel
        val cloudReady = !useLocal && hasKey
        val ready = audio && acc && (localReady || cloudReady)

        statusSubtitle.text = if (ready) "Ready — tap the overlay dot to dictate" else "Setup required"
        statusSubtitle.setTextColor(if (ready) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
        
        refreshAllCards()
        refreshPromptRows()
    }

    private fun promptApiKey() {
        val input = EditText(this).apply {
            hint = "sk-..."
            setText(prefs().getString("api_key", ""))
            inkColors()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("OpenAI API Key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("api_key", input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPostProcessing() {
        val input = EditText(this).apply {
            hint = "Prompt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(currentPrompt())
            inkColors()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val finalPrompt = if (text.isBlank()) PostProcessor.DEFAULT_PROMPT else text
                prefs().edit()
                    .putString("custom_post_processing_prompt", finalPrompt)
                    .putString("post_processing_prompt", finalPrompt)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPromptManager() {
        val prompts = PostProcessPrompts.all(this).toMutableList()
        val labels = prompts.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Prompt sélectionné")
            .setSingleChoiceItems(labels, PostProcessPrompts.selectedIndex(this)) { d, which ->
                PostProcessPrompts.setSelectedIndex(this, which); d.dismiss()
            }
            .setPositiveButton("Éditer") { _, _ -> editPrompt(prompts, PostProcessPrompts.selectedIndex(this)) }
            .setNeutralButton("Nouveau") { _, _ -> editPrompt(prompts, -1) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun editPrompt(prompts: MutableList<PostProcessPrompts.Prompt>, index: Int) {
        val existing = prompts.getOrNull(index)
        val labelEt = EditText(this).apply { hint = "Libellé"; setText(existing?.label ?: ""); inkColors() }
        val tplEt = EditText(this).apply {
            hint = "Instructions (utilise \${output})"; setText(existing?.template ?: "")
            isSingleLine = false; minLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            inkColors()
        }
        val box = vertical(dp(16), dp(8)).apply { addView(labelEt); addView(tplEt) }
        val b = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nouveau prompt" else "Modifier le prompt")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val p = PostProcessPrompts.Prompt(labelEt.text.toString().ifBlank { "Prompt" }, tplEt.text.toString())
                if (index >= 0 && index < prompts.size) prompts[index] = p else prompts.add(p)
                PostProcessPrompts.save(this, prompts)
                Toast.makeText(this, "Enregistré", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
        if (existing != null && prompts.size > 1) b.setNeutralButton("Supprimer") { _, _ ->
            prompts.removeAt(index); PostProcessPrompts.save(this, prompts)
            PostProcessPrompts.setSelectedIndex(this, 0)
            Toast.makeText(this, "Supprimé", Toast.LENGTH_SHORT).show()
        }
        b.show()
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

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt == PostProcessor.DEFAULT_PROMPT) "Your edited prompt"
        else prompt.replace("\n", " ")
    }

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset(
            key = "dev",
            title = "Dev cleanup",
            subtitle = "Best for coding, CLI, and project names",
            prompt = PostProcessor.DEV_PROMPT
        ),
        PromptPreset(
            key = "simple",
            title = "Simple cleanup",
            subtitle = "Grammar, punctuation, and light cleanup",
            prompt = PostProcessor.SIMPLE_PROMPT
        ),
        PromptPreset(
            key = "custom",
            title = "Custom",
            subtitle = customPromptSummary(),
            prompt = customPrompt()
        )
    )

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
    }
}
