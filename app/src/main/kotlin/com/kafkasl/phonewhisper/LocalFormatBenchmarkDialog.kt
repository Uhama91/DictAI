package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic local benchmark. No dictation, credentials or cloud are read. */
internal class LocalFormatBenchmarkDialog(activity: AppCompatActivity) : AutoCloseable {
    private val activityRef = WeakReference(activity)
    private val main = Handler(Looper.getMainLooper())
    private val engine = LocalFormatEngine(activity.applicationContext)
    private val backend = engine.backend()
    private val closed = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val started = AtomicBoolean()
    private var dialog: AlertDialog? = null
    private var statusView: TextView? = null
    private var progressView: ProgressBar? = null
    private var reportView: TextView? = null
    private var latestReport = ""
    private var finished = false
    private val examples by lazy { cases() }
    private val totalRuns get() = examples.size * 2
    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        if (!BuildConfig.LOCAL_FORMAT_PROTOTYPE || closed.get() || !started.compareAndSet(false, true)) return
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        statusView = TextView(activity).apply {
            text = "Chargement du modèle…"
            textSize = 16f
            content.addView(this)
        }
        progressView = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = totalRuns
            isIndeterminate = true
            content.addView(this, LinearLayout.LayoutParams(-1, dp(12)))
        }
        val report = TextView(activity).apply {
            text = "${totalRuns} essais français/anglais, dont 2 messages courts sans appel LLM.\nLe premier appel au modèle inclut son chargement.\nLaissez la dictée au repos et gardez cet écran ouvert jusqu’à la fin."
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(8))
        }
        reportView = report
        content.addView(ScrollView(activity).apply { addView(report) },
            LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.43f).toInt()))
        val popup = AlertDialog.Builder(activity)
            .setTitle("Tester le post-traitement local")
            .setView(content)
            .setPositiveButton("Copier les résultats", null)
            .setNegativeButton("Annuler", null)
            .create()
        dialog = popup
        popup.setOnDismissListener { close() }
        popup.show()
        popup.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = false
            setOnClickListener {
                if (latestReport.isNotBlank()) {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Test local DictAI", latestReport))
                    Toast.makeText(activity, "Résultats copiés", Toast.LENGTH_SHORT).show()
                }
            }
        }
        popup.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (finished) popup.dismiss() else cancel()
        }
        Thread(::runBenchmark, "dictai-local-benchmark").apply { isDaemon = true; start() }
    }

    /** Lifecycle and UI callers; cancellation never joins the inference worker. */
    fun cancel() {
        if (finished || closed.get() || !cancelled.compareAndSet(false, true)) return
        backend.cancel()
        engine.close()
        statusView?.text = "Annulation en cours…"
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
    }

    private data class Case(val name: String, val request: LocalFormatRequest,
        val expected: List<String>, val forbidden: List<String> = emptyList(), val grouping: LayoutGroupingExpectation? = null)

    private fun cases(): List<Case> {
        val list = PostProcessingFormats.builtins.first { it.id == "list" }.instructions
        val email = PostProcessingFormats.builtins.first { it.id == "email" }.instructions
        return listOf(
            Case("FR · liste avec noms et nombres", LocalFormatRequest(
                "Demain, appeler Maëlys pour confirmer les 23 élèves, imprimer 2 fiches par élève et apporter les cahiers bleus.",
                list, "French", listOf("Maëlys"), LocalLayoutKind.LIST), listOf("demain", "Maëlys", "23", "2", "bleus"),
                grouping = LayoutGroupingExpectation(setOf(8, 13), allowed = setOf(8, 13))),
            Case("EN · mail avec interdiction", LocalFormatRequest(
                "Hello Karim, do not send the documents today. Wait until Friday and contact Maëlys first. Thank you.",
                email, "English", listOf("Karim", "Maëlys"), LocalLayoutKind.EMAIL), listOf("Karim", "not", "today", "Friday", "Maëlys"),
                grouping = LayoutGroupingExpectation(setOf(2, 15), allowed = setOf(2, 8, 15))),
            Case("FR · mail très court", LocalFormatRequest("OK, ça marche.", email, "French", layoutKind = LocalLayoutKind.EMAIL),
                listOf("marche"), listOf("cordialement", "objet", "madame", "monsieur"),
                grouping = LayoutGroupingExpectation(emptySet(), allowed = emptySet())),
            Case("FR · courses sans virgules · régression", LocalFormatRequest(
                "du lait des oranges du pain du chocolat, des roses du riz", list, "French", layoutKind = LocalLayoutKind.LIST),
                listOf("lait", "oranges", "pain", "chocolat", "roses", "riz"),
                grouping = LayoutGroupingExpectation(setOf(2, 4, 6, 8, 10), allowed = setOf(2, 4, 6, 8, 10))),
            Case("FR · complément à conserver", LocalFormatRequest(
                "du lait de la ferme du pain", list, "French", layoutKind = LocalLayoutKind.LIST),
                listOf("lait", "ferme", "pain"), grouping = LayoutGroupingExpectation(setOf(5), allowed = setOf(5))),
            Case("FR · mail sans ponctuation · régression", LocalFormatRequest(
                "Bonjour voici un premier test qui vise à vérifier que le post-traitement sur le mail fonctionne comme il faut cordialement M. l’utilisateur.",
                email, "French", layoutKind = LocalLayoutKind.EMAIL), listOf("Bonjour", "test", "cordialement", "utilisateur"),
                grouping = LayoutGroupingExpectation(setOf(1, 19), forbidden = setOf(21))),
        )
    }

    private fun runBenchmark() {
        val report = StringBuilder().apply {
            append("DictAI — test local du post-traitement\nModèle : ${LocalFormatEngine.MODEL_FILE}\n")
            append("Application : ${BuildConfig.VERSION_NAME}\n")
            append("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
            append("${examples.size} exemples synthétiques × 2 passages, sans cloud.\n")
            append("Premier appel : moteur neuf, chargement inclus. Le cache de fichiers du système n'est pas vidé.\n")
            append("Premier fragment = texte non blanc reçu ; fin = retour complet du moteur. Le texte est validé avant publication.\n")
            append("Mesure isolée : ne comprend pas l'arrêt ASR, l'affichage ni l'insertion dans une autre application.\n")
            append("La conservation du texte et les critères ciblés de regroupement sont évalués séparément. Un critère réussi ne valide pas tous les formats.\n\n")
        }
        var completed = 0
        var failed = false
        try {
            runs@ for (pass in 1..2) {
                for ((index, example) in examples.withIndex()) {
                    if (cancelled.get()) break@runs
                    val cold = completed == 0
                    postUpdate("Passage $pass/2 · exemple ${index + 1}/${examples.size}\n${example.name}", completed, report.toString())
                    val start = SystemClock.elapsedRealtime()
                    val loadMs = if (cold) engine.prepareForBenchmark() else null
                    if (cancelled.get()) break@runs
                    var firstTextMs: Long? = null
                    val raw = backend.generate(example.request) { chunk ->
                        if (chunk.isNotBlank() && firstTextMs == null) firstTextMs = SystemClock.elapsedRealtime() - start
                    }
                    val totalMs = SystemClock.elapsedRealtime() - start
                    if (cancelled.get()) break@runs
                    val output = example.request.acceptOutput(raw)
                    val missing = example.expected.filterNot { hasTerm(output.orEmpty(), it) }
                    val unwanted = example.forbidden.filter { hasTerm(output.orEmpty(), it) }
                    if (cold) report.append("Calcul : ${engine.runtimeName()} · 2 threads\n\n")
                    report.append("Passage $pass · ${example.name} · ${if (cold) "moteur neuf" else "moteur chargé"}\n")
                    report.append("Chargement : ${loadMs?.let { "$it ms" } ?: "déjà effectué"}\n")
                    report.append("Premier fragment : ${firstTextMs?.let { "$it ms" } ?: if (example.request.layoutPolicy()?.directResult != null) "sans appel LLM" else "aucun"} ; fin : $totalMs ms\n")
                    report.append("Conservation du texte : ${if (output != null) "validée" else "rejetée"}\n")
                    val grouping = example.grouping?.evaluate(example.request, output)
                    report.append("Regroupement ciblé : " + when {
                        example.grouping == null -> "critère non défini"
                        grouping == null -> "non évalué (sortie rejetée)"
                        grouping.passed -> "réussi"
                        else -> "échoué"
                    } + "\n")
                    if (grouping != null && !grouping.passed) {
                        report.append("Coupures après le mot n° : manquantes ${grouping.missing.sorted()} ; inattendues ${grouping.unexpected.sorted()}\n")
                    }
                    report.append("Repères de contenu : ${example.expected.size - missing.size}/${example.expected.size}")
                    if (missing.isNotEmpty()) report.append(" ; absents : ${missing.joinToString()}")
                    if (unwanted.isNotEmpty()) report.append(" ; ajouts à vérifier : ${unwanted.joinToString()}")
                    report.append("\nEntrée : ${example.request.text}\n")
                    if (output != null) report.append("Sortie :\n$output\n\n")
                    else report.append("Sortie absente ou rejetée par les contrôles techniques/vocabulaire.\nBrut : ${raw ?: "aucun texte retourné"}\n\n")
                    completed++
                    postUpdate("$completed/$totalRuns essais terminés", completed, report.toString())
                }
            }
        } catch (error: Throwable) {
            if (!cancelled.get()) {
                failed = true
                report.append("Le test local n'a pas pu se terminer (${error.javaClass.simpleName}).\n")
            }
        } finally {
            engine.close()
            val status = when {
                cancelled.get() -> "Test interrompu · $completed/$totalRuns essais terminés"
                failed -> "Test local indisponible · $completed/$totalRuns essais terminés"
                else -> "Test terminé · $completed/$totalRuns essais"
            }
            if (cancelled.get()) report.append("Test interrompu par l'utilisateur ou la fermeture de l'écran.\n")
            postUpdate(status, completed, report.toString(), done = true)
        }
    }

    private fun hasTerm(text: String, term: String): Boolean =
        Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])").containsMatchIn(text)

    private fun postUpdate(status: String, completed: Int, report: String, done: Boolean = false) {
        if (closed.get()) return
        main.post {
            val activity = activityRef.get()
            if (closed.get() || activity == null || activity.isDestroyed || activity.isFinishing) return@post
            latestReport = report
            statusView?.text = status
            reportView?.text = report
            progressView?.apply {
                isIndeterminate = completed == 0 && !done
                progress = completed
                visibility = if (done) View.GONE else View.VISIBLE
            }
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = report.isNotBlank()
            if (done) {
                finished = true
                dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { text = "Fermer"; isEnabled = true }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelled.set(true)
        backend.cancel()
        engine.close()
        main.removeCallbacksAndMessages(null)
        val popup = dialog
        dialog = null
        statusView = null
        progressView = null
        reportView = null
        activityRef.clear()
        popup?.setOnDismissListener(null)
        popup?.dismiss()
    }
}
