package com.kafkasl.phonewhisper

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
class MainActivity : AppCompatActivity() {
    private enum class Screen { HOME, DICTATION, FORMATTING, PREFERENCES }

    private var localFormatBenchmark: LocalFormatBenchmarkDialog? = null
    private var gemmaDownload: GemmaModelDownloadDialog? = null
    private var gemmaSubtitle: TextView? = null
    private var screen = Screen.HOME
    private var contentScroll: ScrollView? = null

    private val palette: ThemePalette
        get() = ThemeTokens.palette(this)

    override fun onStop() {
        gemmaDownload?.close()
        gemmaDownload = null
        localFormatBenchmark?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        gemmaDownload?.close()
        gemmaDownload = null
        localFormatBenchmark?.close()
        localFormatBenchmark = null
        super.onDestroy()
    }


    private var audioRowSub: TextView? = null
    private var accRowSub: TextView? = null
    private var overlayRowSub: TextView? = null
    private var modelContainer: LinearLayout? = null
    private var formatSummarySubtitle: TextView? = null

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
                                0 -> {
                                    store.select(format)
                                    refreshFormatSummary()
                                    val unavailable = PersistencePrefs(this).formattingEngine == "local" && format.localLayoutKind == null && format.instructions.isNotBlank()
                                    Toast.makeText(this, if (unavailable) "Ce format nécessite le cloud. L’essai local prend en charge les listes et les mails." else "Prochaine dictée : ${format.name}", Toast.LENGTH_LONG).show()
                                }
                                1 -> editFormat(format)
                                2 -> androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("Supprimer ${format.name} ?")
                                    .setPositiveButton("Supprimer") { _, _ ->
                                        store.delete(format.id)
                                        refreshFormatSummary()
                                    }
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
            .setMessage("Décrivez la mise en forme souhaitée. Les formats personnalisés utilisent le cloud avec votre clé OpenRouter. L’essai local prend en charge les listes et les mails.")
            .setView(content).setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    name.text.isNullOrBlank() -> name.error = "Indiquez un nom"
                    instructions.text.isNullOrBlank() -> instructions.error = "Indiquez les consignes"
                    else -> {
                        PostProcessingFormats(this).save(format?.id, name.text.toString(), instructions.text.toString())
                        dialog.dismiss()
                        refreshFormatSummary()
                        showFormatsDialog()
                    }
                }
            }
        }
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen != Screen.HOME) {
                    screen = Screen.HOME
                    renderScreen()
                } else {
                    isEnabled = false
                    try {
                        onBackPressedDispatcher.onBackPressed()
                    } finally {
                        // The launcher may only move this task to the background. Keep the
                        // callback ready if MainActivity is shown again afterwards.
                        isEnabled = true
                    }
                }
            }
        })
        screen = savedInstanceState?.getString(KEY_SCREEN)?.let { value ->
            runCatching { Screen.valueOf(value) }.getOrNull()
        } ?: Screen.HOME
        renderScreen(savedInstanceState?.getInt(KEY_SCROLL_Y, 0) ?: 0)

        // Nouveaux utilisateurs : si la configuration de base manque et que l'assistant n'a
        // jamais été terminé, on lance directement l'onboarding d'installation.
        val onbDone = getSharedPreferences("whisperpin", MODE_PRIVATE).getBoolean("onb_complete", false)
        val coreMissing = !hasPerm(Manifest.permission.RECORD_AUDIO) ||
            !Settings.canDrawOverlays(this) ||
            accessibilityServiceStatus() == AccessibilityServiceStatus.DISABLED
        if (!onbDone && coreMissing) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SCREEN, screen.name)
        outState.putInt(KEY_SCROLL_Y, contentScroll?.scrollY ?: 0)
        super.onSaveInstanceState(outState)
    }

    private fun renderScreen(scrollY: Int = 0) {
        audioRowSub = null
        accRowSub = null
        overlayRowSub = null
        modelContainer = null
        modelRows.clear()
        gemmaSubtitle = null
        formatSummarySubtitle = null

        val root = when (screen) {
            Screen.HOME -> buildHomePage()
            Screen.DICTATION -> buildDictationPage()
            Screen.FORMATTING -> buildFormattingPage()
            Screen.PREFERENCES -> buildPreferencesPage()
        }.apply {
            background = NotebookBackgroundDrawable(this@MainActivity)
            setPadding(dp(18), dp(28), dp(18), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            isFillViewport = true
            clipToPadding = false
            addView(root)
        }
        contentScroll = scroll
        setContentView(scroll)
        if (scrollY > 0) scroll.post { scroll.scrollTo(0, scrollY) }
        refresh()
    }

    private fun buildHomePage(): LinearLayout {
        val root = vertical(0, 0)
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(116)).apply {
                topMargin = dp(44); bottomMargin = dp(4)
            }
        }
        val logo = TextView(this).apply {
            text = "DictAI"
            ResourcesCompat.getFont(this@MainActivity, R.font.caveat)?.let {
                typeface = Typeface.create(it, 600, false)
            }
            textSize = 58f
            setTextColor(palette.ink)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
            val fontScale = resources.configuration.fontScale.coerceAtLeast(1f)
            setPadding(0, dp(4), dp((18f * fontScale).toInt()), dp(4))
            contentDescription = "DictAI"
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_MATCH)
        }
        val brandWave = CursiveWaveView(this).apply {
            setBrandMode(true)
            setStrokeColor(palette.green)
            settle()
            contentDescription = "Boucles cursives DictAI"
            layoutParams = LinearLayout.LayoutParams(0, dp(84), 1f).apply {
                leftMargin = dp(10); rightMargin = dp(2)
            }
        }
        brand.addView(logo)
        brand.addView(brandWave)
        // At a narrow width or enlarged font, keep enough horizontal room for a legible
        // identity stroke by moving it below the wordmark instead of squeezing the path.
        brand.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if (brand.width <= 0 || brand.orientation == LinearLayout.VERTICAL) return
                if (logo.width + dp(120) > brand.width) {
                    brand.removeOnLayoutChangeListener(this)
                    brand.orientation = LinearLayout.VERTICAL
                    brand.gravity = Gravity.START
                    brand.layoutParams = (brand.layoutParams as LinearLayout.LayoutParams).apply {
                        height = LP_WRAP
                    }
                    logo.layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP)
                    brandWave.layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(84)).apply {
                        topMargin = dp(4)
                    }
                    brand.requestLayout()
                }
            }
        })
        root.addView(brand)

        // Keep the visual rhythm of the reference on a normal handset without using the
        // physical screen height as a fixed content minimum. Small screens and large fonts
        // can therefore scroll the same content naturally.
        root.addView(homeSpacer(weight = 0f, minDp = 8))

        root.addView(homeAccessRow(
            "Mes notes",
            "Texte, captures et photos · partager ou exporter",
            R.drawable.ic_note_share,
            minHeightDp = 104,
        ) {
            openNotes()
        })
        root.addView(homeSpacer(weight = 0f, minDp = 16))
        root.addView(sectionHeader("Réglages").apply {
            textSize = 24f
            ResourcesCompat.getFont(this@MainActivity, R.font.caveat)?.let {
                typeface = Typeface.create(it, 600, false)
            }
        })
        root.addView(homeAccessRow("Dictée", dictationSummary(), R.drawable.ic_mic, minHeightDp = 94) { navigateTo(Screen.DICTATION) })
        root.addView(homeAccessRow("Mise en forme", formattingSummary(), android.R.drawable.ic_menu_edit, minHeightDp = 94) { navigateTo(Screen.FORMATTING) })
        root.addView(homeAccessRow("Préférences", "", android.R.drawable.ic_menu_preferences, minHeightDp = 94) {
            navigateTo(Screen.PREFERENCES)
        })
        root.addView(homeSpacer(weight = 1f, minDp = 24))
        return root
    }

    private fun homeAccessRow(
        title: String,
        subtitle: String,
        icon: Int,
        minHeightDp: Int = 94,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = settingsRow(title, subtitle, null, onClick)
        (row.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = dp(12)
            it.bottomMargin = dp(12)
            row.layoutParams = it
        }
        row.minimumHeight = dp(minHeightDp)
        val text = row.getChildAt(0) as LinearLayout
        (text.getChildAt(0) as? TextView)?.let {
            ResourcesCompat.getFont(this, R.font.caveat)?.let { font ->
                it.typeface = Typeface.create(font, 600, false)
            }
            it.textSize = 24f
        }
        row.removeView(text)

        val iconHolder = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.raised)
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) }
            contentDescription = title
        }
        iconHolder.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(palette.green)
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        })
        row.addView(iconHolder, 0)
        row.addView(text)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(palette.inkMuted)
            contentDescription = "Ouvrir $title"
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(48)).apply { leftMargin = dp(4) }
        })
        return row
    }

    private fun buildPreferencesPage(): LinearLayout {
        val root = vertical(0, 0)
        root.addView(navigationHeader("Préférences"))
        root.addView(buildAppearanceCard())
        root.addView(sectionHeader("Installation"))

        root.addView(settingsRow("Assistant d'installation", "Configurer et vérifier les permissions") {
            startActivity(Intent(this, OnboardingActivity::class.java))
        })
        val audioRow = settingsRow("Microphone", if (hasPerm(Manifest.permission.RECORD_AUDIO)) "Autorisé" else "À autoriser") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)
        val accessibilityStatus = accessibilityServiceStatus()
        val accRow = settingsRow("Insertion automatique et captures d’écran", accessibilityStatus.subtitle) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)
        root.addView(TextView(this).apply {
            text = "Le service sert à l’insertion automatique et aux captures d’écran. Les notes et la copie manuelle restent disponibles."
            textSize = 14f
            setTextColor(palette.inkMuted)
            setPadding(dp(14), 0, dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
        })
        val overlayRow = settingsRow("Pastille flottante", overlayStatusLabel()) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            } else {
                runCatching {
                    startForegroundService(Intent(this, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_ARM_MIC))
                }
            }
        }
        overlayRowSub = overlayRow.findViewWithTag("subtitle")
        root.addView(overlayRow)

        root.addView(sectionHeader("Diagnostic"))
        root.addView(settingsRow("Dernier post-traitement", "Diagnostic copiable conservé après la dictée") {
            showPostprocessingDiagnostic()
        })
        root.addView(settingsRow("Dernière copie d'image", "Presse-papier Android · collage manuel depuis Gboard") {
            val report = NoteImagePaste.report(this)
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Dernière copie d'image").setMessage(report)
                .setPositiveButton("Copier") { _, _ -> DictationClipboard.copy(this, report) }
                .setNegativeButton("Fermer", null).show()
        })
        return root
    }

    private fun buildAppearanceCard(): LinearLayout {
        val selected = PersistencePrefs(this).themeMode
        val card = cardContainer().apply { orientation = LinearLayout.VERTICAL }
        card.addView(TextView(this).apply {
            text = "Apparence"
            textSize = 19f
            setTextColor(palette.ink)
        })
        card.addView(TextView(this).apply {
            text = "Choisir le thème de l'application"
            textSize = 14f
            setTextColor(palette.inkMuted)
            setPadding(0, dp(2), 0, dp(12))
        })
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ThemeMode.entries.forEach { mode ->
            val choice = TextView(this).apply {
                text = mode.label
                textSize = 14f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "Thème ${mode.label}${if (mode == selected) ", sélectionné" else ""}"
                setPadding(dp(4), dp(12), dp(4), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    leftMargin = dp(3); rightMargin = dp(3)
                }
                updateAppearanceChoiceBackground(this, mode == selected)
                setOnClickListener { selectTheme(mode) }
            }
            choices.addView(choice)
        }
        card.addView(choices)
        return card
    }

    private fun updateAppearanceChoiceBackground(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) palette.green else palette.surface)
            setStroke(dp(1), if (selected) palette.green else palette.stroke)
        }
        view.setTextColor(if (selected) palette.onGreen else palette.ink)
    }

    private fun buildDictationPage(): LinearLayout {
        val root = vertical(0, 0)
        root.addView(navigationHeader("Dictée"))
        val dictationPrefs = PersistencePrefs(this)

        root.addView(sectionHeader("Langue et modèle"))
        root.addView(settingsRow("Langue", languageLabel(dictationPrefs.dictationLanguage)) { showLanguageDialog() })
        modelContainer = vertical(0, 0)
        for (model in MODEL_CATALOG) modelContainer?.addView(buildModelRow(model))
        root.addView(modelContainer)

        root.addView(sectionHeader("Transcription"))
        val numberRow = settingsRow("Écriture des nombres", numberLabel(dictationPrefs))
        numberRow.setOnClickListener {
            val values = listOf(NumberStyle.DIGITS, NumberStyle.WORDS, NumberStyle.UNCHANGED)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Écriture des nombres")
                .setSingleChoiceItems(arrayOf("En chiffres", "En lettres", "Conserver la transcription"),
                    values.indexOf(dictationPrefs.numberStyle)) { dialog, index ->
                    dictationPrefs.numberStyle = values[index]
                    numberRow.findViewWithTag<TextView>("subtitle").text = numberLabel(dictationPrefs)
                    dialog.dismiss()
                }.setNegativeButton("Annuler", null).show()
        }
        root.addView(numberRow)

        val transcriptSwitch = MaterialSwitch(this).apply {
            isChecked = dictationPrefs.showTranscript
            greenTint()
            setOnCheckedChangeListener { _, on -> dictationPrefs.showTranscript = on }
        }
        root.addView(settingsRow("Afficher le texte pendant la dictée",
            "À la prochaine dictée · glisser vers le haut pour réafficher le panneau", transcriptSwitch))

        val cleanupSwitch = MaterialSwitch(this).apply {
            isChecked = dictationPrefs.lightTextCleanup
            greenTint()
            setOnCheckedChangeListener { _, on -> dictationPrefs.lightTextCleanup = on }
        }
        root.addView(settingsRow("Nettoyage léger du texte",
            "Réduit les « euh » et certaines répétitions, sans attente de modèle", cleanupSwitch))

        val spaceSwitch = MaterialSwitch(this).apply {
            isChecked = dictationPrefs.trailingSpace
            greenTint()
            setOnCheckedChangeListener { _, on -> dictationPrefs.trailingSpace = on }
        }
        root.addView(settingsRow("Espace après chaque dictée",
            "Ajoute une espace à la fin de la transcription", spaceSwitch))
        root.addView(settingsRow("Mon vocabulaire", "Corrections mémorisées depuis l'overlay ou ajoutées ici") {
            showVocabularyDialog()
        })
        return root
    }

    private fun buildFormattingPage(): LinearLayout {
        val root = vertical(0, 0)
        root.addView(navigationHeader("Mise en forme"))
        val formattingPrefs = PersistencePrefs(this)
        val formatStore = PostProcessingFormats(this)
        val selectedFormat = formatStore.selected()
        root.addView(sectionHeader("Format de la dictée"))
        val formatRow = settingsRow("Format de la dictée", selectedFormatLabel(selectedFormat), null) {
            showFormatsDialog()
        }
        formatSummarySubtitle = formatRow.findViewWithTag("subtitle")
        root.addView(formatRow)
        root.addView(settingsRow("Choix disponibles", "Texte · Liste à puces · Mail"))

        root.addView(sectionHeader("Moteur"))
        val engineRow = settingsRow("Moteur de post-traitement", engineLabel(formattingPrefs))
        engineRow.setOnClickListener { showEngineDialog(engineRow) }
        root.addView(engineRow)
        if (BuildConfig.LOCAL_FORMAT_PROTOTYPE) {
            val row = settingsRow("Installer Gemma 4 E2B", gemmaInstallLabel()) { showGemmaDownload() }
            gemmaSubtitle = row.findViewWithTag("subtitle")
            root.addView(row)
        }
        root.addView(settingsRow("Modèle cloud", formattingPrefs.cloudModel().label) { showCloudModelDialog() })
        val credentialStore = SecureCredentialStore(this)
        root.addView(settingsRow(
            "Clé OpenRouter",
            if (credentialStore.has()) "Clé enregistrée (masquée)" else "Aucune clé enregistrée",
        ) { showCredentialDialog() })

        root.addView(sectionHeader("Diagnostics"))
        root.addView(settingsRow("Dernier post-traitement", "Diagnostic copiable conservé après la dictée") {
            showPostprocessingDiagnostic()
        })
        if (BuildConfig.LOCAL_FORMAT_PROTOTYPE) {
            root.addView(settingsRow("Tester Gemma sur ce téléphone", "GPU · sans thinking · vitesse et fidélité FR/EN") {
                if (GemmaModelStore(this).installedModel() == null) showGemmaDownload()
                else if (localFormatBenchmark?.isShowing != true) {
                    localFormatBenchmark?.close()
                    localFormatBenchmark = LocalFormatBenchmarkDialog(this).also { it.show() }
                }
            })
            root.addView(settingsRow("Mesurer les mails longs avec Gemma", "Deux mails × deux passages · résultat copiable") {
                if (GemmaModelStore(this).installedModel() == null) showGemmaDownload()
                else if (localFormatBenchmark?.isShowing != true) {
                    localFormatBenchmark?.close()
                    localFormatBenchmark = LocalFormatBenchmarkDialog(this, longMailsOnly = true).also { it.show() }
                }
            })
        }
        return root
    }

    private fun navigationHeader(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(0, dp(4), 0, dp(18))
        val back = ImageButton(this@MainActivity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(palette.ink)
            background = null
            gravity = Gravity.CENTER
            contentDescription = "Retour à l'accueil"
            isClickable = true
            isFocusable = true
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        back.minimumHeight = dp(52)
        addView(back, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = title
            ResourcesCompat.getFont(this@MainActivity, R.font.caveat)?.let { typeface = it }
            textSize = 38f
            setTextColor(palette.green)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(palette.surface)
            setStroke(dp(1), palette.stroke)
        }
        layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
            topMargin = dp(6); bottomMargin = dp(8)
        }
    }

    private fun navigateTo(next: Screen) {
        if (screen == next) return
        screen = next
        renderScreen()
    }

    private fun openNotes() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
        } else startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_OPEN_NOTES))
    }

    private fun selectTheme(mode: ThemeMode) {
        val preferences = PersistencePrefs(this)
        if (preferences.themeMode == mode) return
        val currentNight = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        preferences.themeMode = mode
        ThemeModeController.apply(this, mode)
        if (Settings.canDrawOverlays(this)) {
            runCatching {
                startForegroundService(
                    Intent(this, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_THEME_CHANGED)
                )
            }
        }
        // AppCompat recreates activities when the effective uiMode changes. When the user
        // switches between two choices with the same effective system appearance, redraw the
        // current page so the selected control still updates immediately.
        val targetNight = when (mode) {
            ThemeMode.DARK -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            ThemeMode.SYSTEM -> currentNight
        }
        if (targetNight == currentNight) renderScreen(contentScroll?.scrollY ?: 0)
    }

    private fun numberLabel(preferences: PersistencePrefs): String = when (preferences.numberStyle) {
        NumberStyle.DIGITS -> "En chiffres · 23, 2,5"
        NumberStyle.WORDS -> "En lettres · vingt-trois, deux virgule cinq"
        NumberStyle.UNCHANGED -> "Conserver la transcription"
    }

    private fun selectedFormatLabel(format: PostProcessingFormat): String =
        if (format.id == "cleanup") "Texte sans LLM" else format.name

    private fun refreshFormatSummary() {
        formatSummarySubtitle?.text = selectedFormatLabel(PostProcessingFormats(this).selected())
    }

    private fun engineLabel(preferences: PersistencePrefs): String = when (preferences.formattingEngine) {
        "local" -> "Local · Gemma · essai"
        "cloud" -> "Cloud · OpenRouter"
        else -> "Désactivé · vocabulaire et nombres conservés"
    }

    private fun dictationSummary(): String {
        val model = ModelDownloader.reconcileSelectedModel(this)
            ?.let { id -> MODEL_CATALOG.firstOrNull { it.archive == id } }
        return "${languageLabel(PersistencePrefs(this).dictationLanguage)} · ${model?.name ?: "modèle vocal à choisir"}"
    }

    private fun formattingSummary(): String {
        val preferences = PersistencePrefs(this)
        val format = selectedFormatLabel(PostProcessingFormats(this).selected())
        val engine = when (preferences.formattingEngine) {
            "cloud" -> "Cloud"
            "local" -> "Local · essai"
            else -> "Sans moteur"
        }
        return "$format · $engine"
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
        gemmaSubtitle?.text = gemmaInstallLabel()
    }

    private fun showVocabularyDialog() {
        val field = EditText(this).apply {
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
            .setView(field)
            .setPositiveButton("Enregistrer") { _, _ ->
                Vocabulary.setRaw(this, field.text.toString())
                toast("Vocabulaire enregistré")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showEngineDialog(row: LinearLayout) {
        val preferences = PersistencePrefs(this)
        val values = if (BuildConfig.LOCAL_FORMAT_PROTOTYPE) listOf("local", "cloud", "off") else listOf("cloud", "off")
        val labels = values.map {
            when (it) {
                "local" -> "Local — texte corrigé, listes et mails (essai)"
                "cloud" -> "Cloud — OpenRouter"
                else -> "Désactivé"
            }
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Moteur de post-traitement")
            .setSingleChoiceItems(labels, values.indexOf(preferences.formattingEngine)) { dialog, index ->
                preferences.formattingEngine = values[index]
                row.findViewWithTag<TextView>("subtitle").text = engineLabel(preferences)
                dialog.dismiss()
                if (values[index] == "local") {
                    if (GemmaModelStore(this).installedModel() == null) showGemmaDownload()
                    else prepareLocalFormatter()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showPostprocessingDiagnostic(latestDictation: Boolean = false) {
        val prefs = PersistencePrefs(this)
        val latest = prefs.lastPostprocessingDiagnostic
        val formatted = prefs.lastFormatPostprocessingDiagnostic
        val report = if (latestDictation) latest else formatted ?: latest
        val title = if (!latestDictation && formatted != null) "Dernier format demandé" else "Dernière dictée"
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(report ?: "Aucun traitement enregistré. Après un essai Mail ou Liste, son diagnostic restera disponible ici même après une dictée en mode Texte.")
            .setPositiveButton("Copier") { _, _ -> report?.let { DictationClipboard.copy(this, it) } }
            .setNegativeButton("Fermer", null)
        if (!latestDictation && formatted != null && latest != null && latest != formatted) {
            dialog.setNeutralButton("Dernière dictée") { _, _ -> showPostprocessingDiagnostic(latestDictation = true) }
        }
        dialog.show()
    }

    private fun gemmaInstallLabel(): String = if (GemmaModelStore(this).installedModel() != null)
        "Installé · hors ligne · texte corrigé, listes et mails"
    else "2,6 Go · téléchargement reprenable · puis utilisation hors ligne"

    private fun showGemmaDownload() {
        if (gemmaDownload?.isShowing == true) return
        gemmaDownload?.close()
        gemmaDownload = GemmaModelDownloadDialog(this, onInstalled = {
            gemmaSubtitle?.text = gemmaInstallLabel()
            prepareLocalFormatter()
        }).also { it.show() }
    }

    private fun prepareLocalFormatter() {
        if (BuildConfig.LOCAL_FORMAT_PROTOTYPE && Settings.canDrawOverlays(this) &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            runCatching {
                startForegroundService(Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_PREPARE_LOCAL_FORMAT))
            }
        }
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Model Rows ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(palette.green)
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(palette.green)
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
        val accessibilityStatus = accessibilityServiceStatus()

        audioRowSub?.text = if (audio) "Autorisé" else "À autoriser"
        accRowSub?.text = accessibilityStatus.subtitle
        overlayRowSub?.text = overlayStatusLabel()

        refreshAllCards()
    }

    // --- UI Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val colors = palette
        // Carte-note sobre : surface arrondie + filet fin
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ThemeTokens.dpf(this@MainActivity, 20f)
            setColor(colors.surface)
            setStroke(dp(1), colors.stroke)
        }
        val rowBg = RippleDrawable(ColorStateList.valueOf(withAlpha(colors.green, 0x33)), cardBg, null)

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
            textSize = 17f
            setTextColor(colors.ink)
        })

        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(colors.inkMuted)
            setPadding(0, dp(2), 0, 0)
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        ResourcesCompat.getFont(this@MainActivity, R.font.caveat)?.let { typeface = it }
        textSize = 21f
        setTextColor(palette.green)
        setPadding(dp(2), dp(18), dp(2), dp(6))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun homeSpacer(weight: Float, minDp: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LP_MATCH,
            if (weight > 0f) 0 else dp(minDp),
            weight,
        )
        minimumHeight = dp(minDp)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255) and 0xFF) shl 24)

    /** Force du texte argenté sur fond sombre pour les champs des dialogs. */
    private fun EditText.inkColors() {
        setTextColor(palette.ink)
        setHintTextColor(palette.inkMuted)
    }

    /** Teinte un switch en encre verte (coché) / atténué (décoché). */
    private fun MaterialSwitch.greenTint() {
        val colors = palette
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(withAlpha(colors.green, 0x99), colors.raised)
        )
        val thumb = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colors.green, colors.inkMuted)
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
            setTextColor(palette.ink)
            setHintTextColor(palette.inkMuted)
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

    private fun accessibilityServiceStatus(): AccessibilityServiceStatus {
        val manager = getSystemService(AccessibilityManager::class.java)
        val enabledInAndroid = runCatching {
            manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val service = info.resolveInfo?.serviceInfo ?: return@any false
                    service.packageName == packageName &&
                        service.name == WhisperAccessibilityService::class.java.name
                } == true
        }.getOrDefault(false)
        return AccessibilityServiceStatus.resolve(
            enabledInAndroid = enabledInAndroid,
            connected = InjectionGateway.current() != null,
        )
    }

    private fun overlayStatusLabel(): String = if (Settings.canDrawOverlays(this)) {
        "Autorisation accordée · afficher la pastille"
    } else {
        "À autoriser pour afficher la pastille"
    }

    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_SCREEN = "main_screen"
        private const val KEY_SCROLL_Y = "main_scroll_y"
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        internal fun credentialDeletionFeedback(deleted: Boolean): String =
            if (deleted) "Clé supprimée" else "Suppression de la clé impossible"
    }
}
