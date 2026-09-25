package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState

/** Shared, lifecycle-bound model controls for the home settings page and onboarding. */
internal class MeetingModelSettingsPanel(
    context: Context,
    private val store: MeetingModelStore,
) : LinearLayout(context) {
    companion object {
        const val PANEL_TAG = "meeting_model_settings_panel"
        const val STATUS_TAG = "meeting_model_settings_status"
        const val DOWNLOAD_LABEL = "Télécharger les modèles Réunion"
    }

    private val palette = ThemeTokens.palette(context)
    private val status = TextView(context).apply {
        tag = STATUS_TAG
        textSize = 14f
        setTextColor(palette.inkMuted)
    }
    private val progress = LinearProgressIndicator(context).apply {
        isIndeterminate = true
        visibility = View.GONE
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, ThemeTokens.dp(context, 4)).apply {
            topMargin = ThemeTokens.dp(context, 8)
        }
    }
    private val download = MaterialButton(context).apply {
        text = DOWNLOAD_LABEL
        minimumHeight = ThemeTokens.dp(context, 48)
        setOnClickListener { store.download() }
    }
    private val cancel = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = "Annuler le téléchargement"
        minimumHeight = ThemeTokens.dp(context, 48)
        visibility = View.GONE
        setOnClickListener { store.cancelDownload() }
    }
    private var listening = false
    private var disposed = false

    var onStateChanged: ((MeetingModelStoreState) -> Unit)? = null

    private val stateListener: (MeetingModelStoreState) -> Unit = { next ->
        post {
            if (listening && !disposed) render(next)
        }
    }

    init {
        tag = PANEL_TAG
        orientation = VERTICAL
        setPadding(ThemeTokens.dp(context, 16), ThemeTokens.dp(context, 14),
            ThemeTokens.dp(context, 16), ThemeTokens.dp(context, 14))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ThemeTokens.dpf(context, 18f)
            setColor(palette.surface)
            setStroke(ThemeTokens.dp(context, 1), palette.stroke)
        }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = ThemeTokens.dp(context, 6)
            bottomMargin = ThemeTokens.dp(context, 8)
        }
        addView(TextView(context).apply {
            text = "Modèles Réunion"
            textSize = 18f
            setTextColor(palette.ink)
        })
        val totalMb = (store.catalog.totalBytes + 999_999L) / 1_000_000L
        addView(TextView(context).apply {
            text = "Transcription et suivi des voix · environ $totalMb Mo"
            textSize = 14f
            setTextColor(palette.inkMuted)
            setPadding(0, ThemeTokens.dp(context, 3), 0, ThemeTokens.dp(context, 4))
        })
        addView(status)
        addView(progress)
        addView(download)
        addView(cancel)
        render(store.currentState)
    }

    /** Inspects only local files; download remains an explicit button action. */
    fun start() {
        if (disposed || listening) return
        listening = true
        store.addListener(stateListener)
        store.refresh()
    }

    fun stop() {
        if (!listening) return
        listening = false
        store.removeListener(stateListener)
    }

    fun close() {
        disposed = true
        stop()
    }

    private fun render(state: MeetingModelStoreState) {
        onStateChanged?.invoke(state)
        when (state) {
            MeetingModelStoreState.Missing -> {
                status.text = "Modèles absents"
                progress.visibility = View.GONE
                download.visibility = View.VISIBLE
                download.isEnabled = true
                cancel.visibility = View.GONE
            }
            MeetingModelStoreState.Checking -> {
                status.text = "Vérification des modèles…"
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                download.visibility = View.VISIBLE
                download.isEnabled = false
                cancel.visibility = View.GONE
            }
            is MeetingModelStoreState.Downloading -> {
                val percent = if (state.totalBytes > 0L) {
                    ((state.bytesDownloaded * 100L) / state.totalBytes).toInt().coerceIn(0, 100)
                } else 0
                status.text = "Téléchargement des modèles Réunion · $percent %"
                progress.isIndeterminate = false
                progress.progress = percent
                progress.visibility = View.VISIBLE
                download.visibility = View.GONE
                cancel.visibility = View.VISIBLE
            }
            is MeetingModelStoreState.Ready -> {
                status.text = "Modèles prêts · Réunion hors ligne"
                progress.visibility = View.GONE
                download.visibility = View.GONE
                cancel.visibility = View.GONE
            }
            is MeetingModelStoreState.Error -> {
                status.text = state.message
                progress.visibility = View.GONE
                download.visibility = View.VISIBLE
                download.isEnabled = true
                cancel.visibility = View.GONE
            }
        }
    }
}

internal fun TranscriptionMode.displayLabel(): String = when (this) {
    TranscriptionMode.DICTATION -> "Dictée"
    TranscriptionMode.MEETING -> "Réunion"
}
