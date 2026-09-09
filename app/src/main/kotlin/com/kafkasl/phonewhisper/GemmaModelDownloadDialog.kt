package com.kafkasl.phonewhisper

import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Phone-only installation flow. Closing the screen pauses safely and preserves partial bytes. */
internal class GemmaModelDownloadDialog(
    activity: AppCompatActivity,
    onInstalled: () -> Unit = {},
) : AutoCloseable {
    private val activityRef = WeakReference(activity)
    private val store = GemmaModelStore(activity.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var installedCallback: (() -> Unit)? = onInstalled
    private var cancellation: GemmaDownloadCancellation? = null
    private var dialog: AlertDialog? = null
    private var content: LinearLayout? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var running = false
    val isShowing: Boolean get() = dialog?.isShowing == true

    /** Called after the user taps the model's download row. No file chooser or computer is needed. */
    fun show() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        content = layout
        layout.addView(TextView(activity).apply {
            text = "Téléchargement du modèle : ${GemmaModelStore.formatBytes(GemmaModelStore.EXPECTED_SIZE_BYTES)}. Une connexion Wi-Fi est conseillée.\n\nGardez cet écran ouvert. Vous pouvez mettre en pause et reprendre plus tard ; une fois installé, Gemma fonctionne sans connexion."
            textSize = 14f
        })
        status = TextView(activity).apply {
            text = "Préparation…"
            textSize = 16f
            setPadding(0, dp(18), 0, dp(8))
            layout.addView(this)
        }
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            layout.addView(this, LinearLayout.LayoutParams(-1, dp(16)))
        }
        val popup = AlertDialog.Builder(activity)
            .setTitle("Installer ${GemmaModelStore.MODEL_TITLE}")
            .setView(layout)
            .setPositiveButton("Reprendre", null)
            .setNegativeButton("Mettre en pause", null)
            .create()
        dialog = popup
        popup.setOnDismissListener { close() }
        popup.show()
        popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { startDownload() }
        popup.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (running) {
                cancellation?.cancel()
                status?.text = "Mise en pause…"
                popup.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            } else popup.dismiss()
        }
        startDownload()
    }

    private fun startDownload() {
        if (closed.get() || running) return
        running = true
        content?.keepScreenOn = true
        status?.text = "Préparation du téléchargement…"
        progress?.isIndeterminate = true
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { text = "Mettre en pause"; isEnabled = true }
        val token = GemmaDownloadCancellation()
        cancellation = token
        Thread({ store.download(token, ::postState) }, "dictai-gemma-download").apply { isDaemon = true; start() }
    }

    private fun postState(state: GemmaInstallState) {
        if (closed.get()) return
        main.post {
            val activity = activityRef.get()
            if (closed.get() || activity == null || activity.isDestroyed || activity.isFinishing) return@post
            when (state) {
                is GemmaInstallState.Downloading -> {
                    val percent = (state.bytes * 100L / state.total).toInt()
                    status?.text = "$percent % · ${GemmaModelStore.formatBytes(state.bytes)} / ${GemmaModelStore.formatBytes(state.total)}"
                    progress?.apply { isIndeterminate = false; progress = percent }
                }
                is GemmaInstallState.Verifying -> {
                    val percent = (state.bytes * 100L / state.total).toInt()
                    status?.text = "Vérification du modèle · $percent %"
                    progress?.apply { isIndeterminate = false; progress = percent }
                }
                is GemmaInstallState.Installed -> {
                    finish("Gemma est installé. Le premier chargement peut prendre quelques secondes.", retry = false)
                    progress?.apply { isIndeterminate = false; progress = 100 }
                    installedCallback?.invoke()
                }
                GemmaInstallState.Paused -> finish("Téléchargement en pause. La reprise conservera les données déjà reçues si le serveur le permet.", retry = true)
                is GemmaInstallState.Error -> finish(state.message, retry = true)
            }
        }
    }

    private fun finish(message: String, retry: Boolean) {
        running = false
        cancellation = null
        content?.keepScreenOn = false
        status?.text = message
        progress?.isIndeterminate = false
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = retry
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { text = "Fermer"; isEnabled = true }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancellation?.cancel()
        cancellation = null
        installedCallback = null
        content?.keepScreenOn = false
        content = null
        status = null
        progress = null
        main.removeCallbacksAndMessages(null)
        activityRef.clear()
        val popup = dialog
        dialog = null
        popup?.setOnDismissListener(null)
        popup?.dismiss()
    }
}
