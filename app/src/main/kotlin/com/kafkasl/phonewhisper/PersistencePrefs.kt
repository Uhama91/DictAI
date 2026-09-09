package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

class PersistencePrefs internal constructor(private val p: SharedPreferences) {
    constructor(ctx: Context) : this(ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE))

    init { migrateCloudCleanupPreferences() }

    var showTranscript: Boolean
        get() = p.getBoolean("show_transcript", true)
        set(value) { p.edit().putBoolean("show_transcript", value).apply() }

    var buttonX: Int
        get() = p.getInt("btn_x", -1)
        set(v) { p.edit().putInt("btn_x", v).apply() }
    var buttonY: Int
        get() = p.getInt("btn_y", -1)
        set(v) { p.edit().putInt("btn_y", v).apply() }

    private var savedAnchor: Anchor?
        get() {
            val edge = edgeFromPreference(p.getString(KEY_ANCHOR_EDGE, null)) ?: return null
            if (!p.contains(KEY_ANCHOR_OFFSET)) return null
            val offset = try { p.getFloat(KEY_ANCHOR_OFFSET, Float.NaN) } catch (_: ClassCastException) { return null }
            return if (offset.isFinite() && offset in 0f..1f) Anchor(edge, offset) else null
        }
        set(value) {
            if (value == null) p.edit().remove(KEY_ANCHOR_EDGE).remove(KEY_ANCHOR_OFFSET).apply()
            else p.edit().putString(KEY_ANCHOR_EDGE, value.edge.name)
                .putFloat(KEY_ANCHOR_OFFSET, value.offset.coerceIn(0f, 1f)).apply()
        }

    fun loadAnchor(buttonW: Int, buttonH: Int, screenW: Int, screenH: Int): Anchor {
        savedAnchor?.let { return it }
        return migrateLegacyAnchor(buttonX, buttonY, buttonW, buttonH, screenW, screenH).also { savedAnchor = it }
    }

    fun saveAnchor(anchor: Anchor) { savedAnchor = anchor }

    var lastError: String?
        get() = p.getString("last_error", null)
        set(v) { p.edit().putString("last_error", v).apply() }

    internal val lastPostprocessingDiagnostic: String?
        get() = p.getString("last_postprocessing_diagnostic", null)

    internal val lastFormatPostprocessingDiagnostic: String?
        get() = p.getString("last_format_postprocessing_diagnostic", null)

    /** A plain dictation used to explain an unsuccessful mail must not erase its diagnostic. */
    internal fun recordPostprocessingDiagnostic(report: String, formatRequested: Boolean) {
        p.edit().apply {
            putString("last_postprocessing_diagnostic", report)
            if (formatRequested) putString("last_format_postprocessing_diagnostic", report)
        }.apply()
    }

    /** Ajoute automatiquement une espace à la fin de chaque transcription insérée. */
    var trailingSpace: Boolean
        get() = p.getBoolean("trailing_space", false)
        set(v) { p.edit().putBoolean("trailing_space", v).apply() }

    /** Global preference; an active recording snapshots this value at its start. */
    var dictationLanguage: DictationLanguage
        get() = DictationLanguage.fromPreference(p.getString("dictation_language", null))
        set(v) { p.edit().putString("dictation_language", v.preferenceValue).apply() }

    internal var numberStyle: NumberStyle
        get() = NumberStyle.entries.firstOrNull { it.name == p.getString("number_style", null) } ?: NumberStyle.DIGITS
        set(value) { p.edit().putString("number_style", value.name).apply() }

    var cloudCleanupEnabled: Boolean
        get() = p.getBoolean("cloud_cleanup_enabled", false)
        set(v) { p.edit().putBoolean("cloud_cleanup_enabled", v).apply() }

    /** Keep existing cloud preferences. Unvalidated local models are restricted to prototype builds. */
    var formattingEngine: String
        get() {
            val requested = p.getString("formatting_engine", null)
            return when {
                requested == "cloud" -> "cloud"
                requested == "off" -> "off"
                requested == "local" -> if (BuildConfig.LOCAL_FORMAT_PROTOTYPE) "local" else "off"
                cloudCleanupEnabled -> "cloud"
                BuildConfig.LOCAL_FORMAT_PROTOTYPE -> "local"
                else -> "off"
            }
        }
        set(value) {
            require(value in listOf("local", "cloud", "off"))
            require(value != "local" || BuildConfig.LOCAL_FORMAT_PROTOTYPE)
            p.edit().putString("formatting_engine", value).putBoolean("cloud_cleanup_enabled", value == "cloud").apply()
        }

    fun cloudModel(): CuratedCloudModel =
        CloudModelCatalog.selected(p.getString(CloudModelPreferences.KEY, null))

    fun setCloudModel(model: CuratedCloudModel) {
        p.edit().putString(CloudModelPreferences.KEY, model.preferenceValue).apply()
    }

    private fun migrateCloudCleanupPreferences() {
        val migratedModel = if (p.contains(CloudModelPreferences.KEY)) null else {
            CloudCleanupPreferencesMigration.modelValue(
                currentValue = null,
                legacyOpenRouterValue = p.getString(LEGACY_OPENROUTER_MODEL_KEY, null),
            )
        }
        p.edit().apply {
            migratedModel?.let { putString(CloudModelPreferences.KEY, it) }
            CloudCleanupPreferencesMigration.obsoleteKeys.forEach(::remove)
        }.apply()
    }

    companion object {
        private const val KEY_ANCHOR_EDGE = "btn_anchor_edge"
        private const val KEY_ANCHOR_OFFSET = "btn_anchor_offset"
        private const val LEGACY_OPENROUTER_MODEL_KEY = "cloud_cleanup_model_openrouter"

        fun clampX(x: Int, w: Int, screenW: Int) = x.coerceIn(0, (screenW - w).coerceAtLeast(0))
        fun clampY(y: Int, h: Int, screenH: Int) = y.coerceIn(0, (screenH - h).coerceAtLeast(0))

        fun edgeFromPreference(value: String?): Edge? =
            Edge.entries.firstOrNull { it.name == value }

        fun migrateLegacyAnchor(buttonX: Int, buttonY: Int, buttonW: Int, buttonH: Int, screenW: Int, screenH: Int): Anchor {
            val screen = Rect(0, 0, screenW.coerceAtLeast(0), screenH.coerceAtLeast(0))
            val pill = Rect(0, 0, buttonW.coerceAtLeast(0), buttonH.coerceAtLeast(0))
            val legacy = Point(
                if (buttonX >= 0) clampX(buttonX, pill.width, screen.width) else screen.width - pill.width,
                if (buttonY >= 0) clampY(buttonY, pill.height, screen.height) else (screen.height - pill.height) / 2,
            )
            return OverlayPlacement.snap(legacy, pill, screen)
        }
    }
}

object CloudCleanupPreferencesMigration {
    val obsoleteKeys = setOf(
        "cloud_cleanup_provider",
        "cloud_cleanup_model_openai",
        "cloud_cleanup_model_openrouter",
        "cloud_cleanup_model_google",
        "cloud_cleanup_model_mistral",
        "cloud_cleanup_model_deepseek",
    )

    fun modelValue(currentValue: String?, legacyOpenRouterValue: String?): String? = currentValue ?: when (legacyOpenRouterValue) {
        "openrouter-mistral-small-3-2" -> "mistral-small-3-2"
        "openrouter-gpt-5-4-nano" -> "gpt-5-4-nano"
        else -> null
    }
}
