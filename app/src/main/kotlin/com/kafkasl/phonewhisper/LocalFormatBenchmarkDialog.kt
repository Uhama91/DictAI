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
import java.util.concurrent.atomic.AtomicLong

/** Synthetic local benchmark. No dictation, credentials or cloud are read. */
internal class LocalFormatBenchmarkDialog(
    activity: AppCompatActivity,
    private val longMailsOnly: Boolean = false,
    private val latencyOnly: Boolean = false,
    private val pilotOverride: Boolean? = null,
    private val workerLauncher: ((Runnable, String) -> Unit)? = null,
) : AutoCloseable {
    private val activityRef = WeakReference(activity)
    private val pilot = pilotOverride ?: BuildConfig.GEMMA4_FINE_TUNED_PILOT
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
    private val examples by lazy {
        if (latencyOnly) latencyCases()
        else cases().filter { !longMailsOnly || it.longMail }
    }
    private val passCount get() = if (latencyOnly) LocalLatencyBenchmarkCases.PASS_COUNT else 2
    private val totalRuns get() = examples.size * passCount
    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        if ((!BuildConfig.LOCAL_FORMAT_PROTOTYPE && pilotOverride == null) ||
            (latencyOnly && !pilot) || closed.get() || !started.compareAndSet(false, true)
        ) return
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
            text = if (latencyOnly) "Six textes français synthétiques, trois passages chacun, sans score de qualité.\nLe chargement est mesuré séparément. Si le modèle est déjà chargé, le rapport le précise. Prévoir quelques minutes.\nLaissez la dictée au repos et gardez cet écran ouvert jusqu’à la fin."
                else if (longMailsOnly) "Deux mails longs, deux passages chacun, entièrement traités par Gemma.\nChaque calcul peut durer jusqu’à 20 secondes. La préparation initiale est mesurée séparément.\nLaissez la dictée au repos et gardez cet écran ouvert jusqu’à la fin."
                else "${totalRuns} essais français/anglais, dont ${examples.count { it.request.directOutput() != null } * 2} réponses directes sans appel LLM.\nLe chargement est mesuré si Gemma n’est pas déjà prêt.\nLaissez la dictée au repos et gardez cet écran ouvert jusqu’à la fin."
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(8))
        }
        reportView = report
        content.addView(ScrollView(activity).apply { addView(report) },
            LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.43f).toInt()))
        val popup = AlertDialog.Builder(activity)
            .setTitle(when {
                latencyOnly -> "Mesurer la latence · 6 textes"
                longMailsOnly -> "Durée des mails longs · Gemma"
                else -> "Tester le post-traitement local"
            })
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
        val workerName = if (latencyOnly) "dictai-local-latency" else "dictai-local-benchmark"
        workerLauncher?.invoke(Runnable(::runBenchmark), workerName) ?: Thread(::runBenchmark, workerName)
            .apply { isDaemon = true; start() }
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
        val expected: List<String>, val forbidden: List<String> = emptyList(), val grouping: LayoutGroupingExpectation? = null,
        val longMail: Boolean = false, val minBodyParagraphs: Int? = null,
        val latencyCase: LocalLatencyBenchmarkCase? = null)

    private fun latencyCases(): List<Case> = LocalLatencyBenchmarkCases.all.map { spec ->
        Case(
            name = "${spec.id} · ${spec.label}",
            request = spec.request,
            expected = emptyList(),
            latencyCase = spec,
        )
    }

    private fun cases(): List<Case> {
        val list = PostProcessingFormats.builtins.first { it.id == "list" }.instructions
        val email = PostProcessingFormats.builtins.first { it.id == "email" }.instructions
        val longMail = "Bonjour, voici un nouveau test pour voir si le format a bien été pris en compte lors de la création et la génération de ce nouveau texte qui sans être mis en forme sous forme de mail. La première fois quand ça a été un message assez court la mise en forme a été réalisée, la deuxième, la troisième quatrième fois n'a pas fonctionné comme il faut la mise en forme n'a pas été prise en compte, et en voici la preuve à nouveau normalement, cordialement, Monsieur le Testeur"
        val latestLongMail = "Bonjour, voici le nouveau test concernant la génération d'un nouveau mail qui doit être mis en forme par le modèle en local, donc le but, c'est de rédiger un mail suffisamment long pour solliciter le travail du modèle en local et qu'il fasse la mise en forme du texte comme il faut l'intérêt d'avoir la mise en forme en locale, c'est aussi de ne pas fournir de données au modèle cloud et d'avoir une génération peut-être un peu plus lente, mais l'idée, c'est aussi d'avoir une latence suffisamment basse pour que l'expérience reste agréable pour un utilisateur qui veut envoyer un mail et que le post traitement soit correct aussi je ne sais pas si le mail est suffisamment long là actuellement mais l'idée est vraiment que je puisse réaliser ce travail là en étant suffisamment satisfait au niveau latence. Cordialement, Monsieur le Testeur"
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
            Case("EN · mail sans ponctuation · signature", LocalFormatRequest(
                "Hello Nora please keep the 2 blue folders do not delete the originals thank you Eli",
                email, "English", listOf("Nora", "Eli"), LocalLayoutKind.EMAIL),
                listOf("Nora", "2", "not", "originals", "Eli"),
                grouping = LayoutGroupingExpectation(setOf(2, 13), allowed = setOf(2, 8, 13, 15))),
            Case("FR · courses avec compléments", LocalFormatRequest(
                "des tomates cerises du pain de campagne du café moulu des pommes",
                list, "French", layoutKind = LocalLayoutKind.LIST),
                listOf("tomates", "cerises", "campagne", "café", "pommes"),
                grouping = LayoutGroupingExpectation(setOf(3, 7, 10), allowed = setOf(3, 7, 10))),
            Case("EN · liste avec compléments", LocalFormatRequest(
                "green tea whole wheat bread apple juice", list, "English", layoutKind = LocalLayoutKind.LIST),
                listOf("green", "tea", "wheat", "bread", "juice"),
                grouping = LayoutGroupingExpectation(setOf(2, 5), allowed = setOf(2, 5))),
            Case("FR · mail long · Gemma seul (comparaison 0.8.2)", LocalFormatRequest(
                longMail, email, "French", listOf("Monsieur le Testeur"), LocalLayoutKind.EMAIL),
                listOf("Bonjour", "pas", "normalement", "cordialement", "Monsieur", "Testeur"),
                grouping = LayoutGroupingExpectation(setOf(1, longMail.split(' ').indexOf("cordialement,"))), longMail = true),
            Case("FR · mail plus long · délai dépassé en dictée · Gemma seul", LocalFormatRequest(
                latestLongMail, email, "French", listOf("Monsieur le Testeur"), LocalLayoutKind.EMAIL),
                listOf("Bonjour", "ne", "pas", "latence", "Cordialement", "Testeur"),
                grouping = LayoutGroupingExpectation(setOf(1, latestLongMail.split(' ').indexOf("Cordialement,"))), longMail = true),
            Case("FR · texte long · répétitions et deux sujets", LocalFormatRequest(
                "Je je prépare la le réunion de demain avec les 23 élèves et je souhaite que les documents soient prêt avant leur arrivée. Nous garderons les cahiers bleus dans la classe et nous ne distribuerons pas les copies avant lundi. Ensuite euh je voudrais parler de la sortie de vendredi. Maëlys confirmera les horaires du bus et Karim vérifiera les 2 autorisations manquantes. Merci de conserver les coordonnées des familles dans le dossier habituel.",
                "", "French", listOf("Maëlys", "Karim"), LocalLayoutKind.TEXT),
                listOf("23", "ne", "pas", "lundi", "vendredi", "Maëlys", "Karim", "2"), minBodyParagraphs = 2),
            Case("EN · long prose · repetitions and two topics", LocalFormatRequest(
                "We need we need the the documents for the meeting tomorrow and everyone should recieve a separate copy before arriving. Please keep the 2 blue folders in Nora's office and do not remove the original invoices from the shared cabinet. Now I want to discuss Friday's trip. Eli will confirm the bus schedule and Karim will check the missing forms. Please keep every family's contact details in the usual folder so that we can contact them if necessary.",
                "", "English", listOf("Nora", "Eli", "Karim"), LocalLayoutKind.TEXT),
                listOf("2", "Nora", "not", "original", "Eli", "Karim"), minBodyParagraphs = 2),
            Case("FR · mail long · paragraphes du corps", LocalFormatRequest(
                "Bonjour Nora je vous écris pour préparer la réunion de demain. Les 23 dossiers seront prêt à neuf heures et les documents seront disponibles dans la salle habituelle. Merci de ne pas déplacer les originaux avant notre arrivée. Ensuite je voudrais évoquer la sortie de vendredi. Karim confirmera le transport et Maëlys vérifiera les autorisations des familles. Enfin pour le budget nous devons garder les 2 devis reçus et attendre votre confirmation avant de commander les fournitures. Cordialement Eli",
                email, "French", listOf("Nora", "Karim", "Maëlys", "Eli"), LocalLayoutKind.EMAIL),
                listOf("23", "ne", "pas", "Karim", "Maëlys", "2", "Eli"), minBodyParagraphs = 3),
        ).map { it.copy(request = it.request.copy(validation = LocalFormatValidation.GEMMA_EDITING,
            simpleEmailLayout = !it.longMail)) }
    }

    private fun runBenchmark() {
        if (latencyOnly) runLatencyBenchmark() else runLegacyBenchmark()
    }

    private fun runLatencyBenchmark() {
        val report = StringBuilder().apply {
            append("DictAI — mesure de latence du pilote\n")
            append("Modèle : ${LocalFormatRuntimeLabels.model(pilot)}\n")
            append("Application : ${BuildConfig.VERSION_NAME}\n")
            append("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
            append("${examples.size} textes synthétiques × ${LocalLatencyBenchmarkCases.PASS_COUNT} passages, sans cloud ni score de qualité.\n")
            append("Le moteur est partagé avec l’overlay. Le chargement est mesuré séparément ; si le modèle est déjà chargé, le rapport le précise.\n")
            append("Configuration : ${LocalFormatRuntimeLabels.configuration(pilot)} · ${LocalFormatCpuEngine.CPU_THREADS} threads CPU · contexte ${LocalFormatCpuEngine.CPU_CONTEXT_SIZE}. Validation : GEMMA_EDITING · Texte · phase finale · e-mail simplifié désactivé.\n")
            append("Premier fragment = texte non blanc reçu ; fin = retour complet du moteur. Les temps de calcul et de validation sont séparés.\n")
            append("Le banc ne mesure ni l’ASR, ni l’affichage, ni l’insertion dans une autre application, et ne compare pas Gemma 3. Les réponses brutes et finales restent copiables pour une revue humaine.\n\n")
            append("Avant les essais : ${deviceSample()}\n\n")
        }
        var completed = 0
        var failed = false
        var transportFailures = 0
        var lease: LocalMeasurementAdmission.Lease? = null
        val observations = mutableListOf<LocalLatencyObservation>()
        val dispositions = linkedMapOf(
            LocalLatencyDisposition.ACCEPTED_UNCHANGED to 0,
            LocalLatencyDisposition.ACCEPTED_MODIFIED to 0,
            LocalLatencyDisposition.FALLBACK_SOURCE to 0,
        )
        try {
            lease = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)
            if (lease == null) {
                failed = true
                report.append("Banc indisponible : une dictée ou une autre mesure est déjà en cours. Aucun moteur n’a été préparé.\n")
            } else {
                runs@ for (pass in 1..LocalLatencyBenchmarkCases.PASS_COUNT) {
                    for ((index, example) in examples.withIndex()) {
                        if (cancelled.get()) break@runs
                        val firstRun = completed == 0
                        postUpdate("Passage $pass/${LocalLatencyBenchmarkCases.PASS_COUNT} · exemple ${index + 1}/${examples.size}\n${example.name}", completed, report.toString())
                        val start = SystemClock.elapsedRealtime()
                        val preparation = if (firstRun) engine.prepareForBenchmarkInfo() else null
                        if (cancelled.get()) break@runs
                        val callStarted = SystemClock.elapsedRealtime()
                        val firstFragmentAt = AtomicLong(-1)
                        val nativeStartedAt = AtomicLong(-1)
                        val generated = runCatching {
                            backend.generate(example.request, { chunk ->
                                if (chunk.isNotBlank()) firstFragmentAt.compareAndSet(-1, SystemClock.elapsedRealtime())
                            }, { nativeStartedAt.compareAndSet(-1, SystemClock.elapsedRealtime()) })
                        }
                        val returnedAt = SystemClock.elapsedRealtime()
                        val raw = generated.getOrNull()
                        if (cancelled.get()) break@runs
                        val output = example.request.acceptOutput(raw)
                        val timing = LocalGenerationTiming(
                            nativeStartedAt.get().takeIf { it >= 0 }?.minus(callStarted),
                            firstFragmentAt.get().takeIf { it >= 0 }?.minus(callStarted),
                            returnedAt - callStarted,
                            SystemClock.elapsedRealtime() - returnedAt,
                            generated.isSuccess && !raw.isNullOrBlank(),
                        )
                        val direct = example.request.directOutput() != null
                        val outcome = classifyLocalLatencyOutcome(
                            source = example.request.text,
                            raw = raw,
                            output = output,
                            generationError = generated.exceptionOrNull()?.javaClass?.simpleName,
                            direct = direct,
                        )
                        dispositions[outcome.disposition] = dispositions.getValue(outcome.disposition) + 1
                        observations += LocalLatencyObservation(
                            totalMs = timing.callMs + timing.validationMs,
                            firstFragmentMs = timing.firstFragmentMs,
                            complete = outcome.complete,
                            disposition = outcome.disposition,
                            direct = outcome.direct,
                        )
                        if (!outcome.complete) transportFailures++
                        report.append("Passage $pass/${LocalLatencyBenchmarkCases.PASS_COUNT} · ${example.name}\n")
                        preparation?.let {
                            val state = if (it.wasAlreadyLoaded) "moteur déjà chargé" else "chargement effectué"
                            report.append("Préparation initiale : $state · attente ${it.waitMs} ms")
                            if (!it.wasAlreadyLoaded) report.append(" · chargement ${it.loadMs} ms")
                            report.append("\n")
                        }
                        report.append("Traitement : ${if (direct) "réponse directe locale · durée LLM exclue" else "Gemma · appel LLM mesuré"}\n")
                        report.append("Premier fragment depuis l’appel : ${timing.firstFragmentMs?.let { "$it ms" } ?: "aucun"} ; retour total appel + validation : ${timing.callMs + timing.validationMs} ms\n")
                        report.append(timing.report())
                        report.append("Résultat : ${outcome.disposition.label}\n")
                        outcome.failureCode?.let { report.append("Code de rejet/absence : $it\n") }
                        report.append("Entrée synthétique [${example.latencyCase?.id}] : ${example.request.text}\n")
                        if (raw != null) report.append("Brut :\n$raw\n") else report.append("Brut : aucun texte retourné\n")
                        if (output != null) report.append("Final validé :\n$output\n\n")
                        else report.append("Final validé : aucune sortie ; repli de l’application = source conservée\n\n")
                        completed++
                        postUpdate("$completed/$totalRuns essais terminés", completed, report.toString())
                        if (generated.isFailure) {
                            failed = true
                            break@runs
                        }
                    }
                }
                val summary = summarizeLocalLatencyObservations(observations)
                report.append("\nRésumé des mesures de latence\n")
                report.append("Résultats : accepté inchangé ${dispositions.getValue(LocalLatencyDisposition.ACCEPTED_UNCHANGED)}, accepté modifié ${dispositions.getValue(LocalLatencyDisposition.ACCEPTED_MODIFIED)}, repli ${dispositions.getValue(LocalLatencyDisposition.FALLBACK_SOURCE)}.\n")
                report.append("Réponses complètes mesurables pour le LLM : ${summary.completeCount}/${observations.size} ; échecs de transport/absence : $transportFailures.\n")
                report.append("Retour total appel + validation (réponses complètes) : médiane ${median(summary.completeDurationsMs)?.let { "$it ms" } ?: "n/a"}, maximum ${summary.completeDurationsMs.maxOrNull()?.let { "$it ms" } ?: "n/a"}.\n")
                report.append("Premier fragment (réponses complètes) : médiane ${median(summary.completeFirstFragmentsMs)?.let { "$it ms" } ?: "n/a"}, maximum ${summary.completeFirstFragmentsMs.maxOrNull()?.let { "$it ms" } ?: "n/a"}.\n")
                report.append("Retour total appel + validation (sorties acceptées uniquement) : médiane ${median(summary.acceptedDurationsMs)?.let { "$it ms" } ?: "n/a"}, maximum ${summary.acceptedDurationsMs.maxOrNull()?.let { "$it ms" } ?: "n/a"}.\n")
                report.append("Premier fragment (sorties acceptées uniquement) : médiane ${median(summary.acceptedFirstFragmentsMs)?.let { "$it ms" } ?: "n/a"}, maximum ${summary.acceptedFirstFragmentsMs.maxOrNull()?.let { "$it ms" } ?: "n/a"}.\n")
                report.append("Réponses acceptées parmi ces sorties : ${summary.acceptedCount}/${summary.completeCount}. Réponses directes exclues : ${summary.directCount}. Les sorties incomplètes restent hors des agrégats.\n")
                if (completed < totalRuns || cancelled.get()) report.append("Banc incomplet ou annulé : les agrégats ne sont pas comparables à un passage complet.\n")
                report.append("Ces nombres décrivent ce banc synthétique et ne démontrent ni qualité sémantique générale ni vitesse sur tous les téléphones.\n")
            }
        } catch (error: Throwable) {
            if (!cancelled.get()) {
                failed = true
                report.append("Le banc de latence n'a pas pu se terminer (${error.javaClass.simpleName}).\n")
                report.append("${LocalFormatRuntimeLabels.failure(pilot, engine.runtimeName(), engine.failureCode())}. Le calcul CPU est le moteur principal ; aucun repli cloud.\n")
            }
        } finally {
            report.append("Après les essais : ${deviceSample()}\n")
            engine.close()
            lease?.close()
            val status = when {
                cancelled.get() -> "Mesure interrompue · $completed/$totalRuns essais terminés"
                failed -> "Mesure indisponible · $completed/$totalRuns essais terminés"
                else -> "Mesure de latence terminée · $completed/$totalRuns essais"
            }
            if (cancelled.get()) report.append("Banc interrompu par l'utilisateur ou la fermeture de l'écran.\n")
            postUpdate(status, completed, report.toString(), done = true)
        }
    }

    private fun median(values: List<Long>): Long? = medianLatencyMs(values)

    private fun runLegacyBenchmark() {
        val lease = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)
        if (lease == null) {
            engine.close()
            postUpdate(
                "Test local indisponible · une dictée ou une autre mesure est en cours",
                0,
                "Banc indisponible : une dictée ou une autre mesure est déjà en cours. Aucun moteur n’a été préparé.\n",
                done = true,
            )
            return
        }
        try {
            runLegacyBenchmarkBody()
        } finally {
            lease.close()
        }
    }

    private fun runLegacyBenchmarkBody() {
        val report = StringBuilder().apply {
            append("DictAI — test local du post-traitement\nModèle : ${LocalFormatRuntimeLabels.model(pilot)}\n")
            append("Application : ${BuildConfig.VERSION_NAME}\n")
            append("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
            append("${examples.size} exemples synthétiques × 2 passages, sans cloud.\n")
            append("Moteur partagé avec l’overlay. Le premier essai indique si Gemma était déjà chargé. ${LocalFormatRuntimeLabels.cacheNote(pilot)}\n")
            append("Configuration : ${LocalFormatRuntimeLabels.configuration(pilot)}.\n")
            append("Mails longs : Gemma seul, sans disposition directe. Les réponses directes des autres cas ne mesurent pas le LLM.\n")
            append("Limite du banc : ${LocalFormatEngine.GENERATION_DEADLINE_MS} ms par appel après préparation initiale, file comprise ; aucune coupure à 5, 8 ou 10 secondes.\n")
            append("Premier fragment = texte non blanc reçu ; fin = retour complet du moteur. Le texte est validé avant publication.\n")
            append("Mesure isolée : ne comprend pas l'arrêt ASR, l'affichage ni l'insertion dans une autre application.\n")
            append("Corrections de forme et répétitions autorisées ; contrôles lexicaux et structure évalués séparément. Le score de paragraphes ne vérifie pas leur pertinence. Un critère réussi ne valide pas tous les formats.\n\n")
            append("Avant les essais : ${deviceSample()}\n\n")
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
                    val preparation = if (cold) engine.prepareForBenchmarkInfo() else null
                    if (cancelled.get()) break@runs
                    val callStarted = SystemClock.elapsedRealtime()
                    val firstFragmentAt = AtomicLong(-1)
                    val nativeStartedAt = AtomicLong(-1)
                    val generated = runCatching {
                        backend.generate(example.request, { chunk ->
                            if (chunk.isNotBlank()) firstFragmentAt.compareAndSet(-1, SystemClock.elapsedRealtime())
                        }, { nativeStartedAt.compareAndSet(-1, SystemClock.elapsedRealtime()) })
                    }
                    val returnedAt = SystemClock.elapsedRealtime()
                    val raw = generated.getOrNull()
                    val firstTextMs = firstFragmentAt.get().takeIf { it >= 0 }?.minus(start)
                    val totalMs = returnedAt - start
                    if (cancelled.get()) break@runs
                    val output = example.request.acceptOutput(raw)
                    val timing = LocalGenerationTiming(
                        nativeStartedAt.get().takeIf { it >= 0 }?.minus(callStarted),
                        firstFragmentAt.get().takeIf { it >= 0 }?.minus(callStarted),
                        returnedAt - callStarted, SystemClock.elapsedRealtime() - returnedAt,
                        generated.isSuccess && !raw.isNullOrBlank(),
                    )
                    if (cold) report.append("Calcul : ${LocalFormatRuntimeLabels.calculation(pilot, engine.runtimeName())}\n\n")
                    if (cold) engine.lastLoadMs()?.let { report.append("Dernière initialisation du moteur partagé : $it ms\n\n") }
                    report.append("Passage $pass · ${example.name} · ${if (preparation?.wasAlreadyLoaded == false) "chargement effectué" else "moteur chargé"}\n")
                    report.append("Chargement : ${preparation?.takeUnless { it.wasAlreadyLoaded }?.let { "${it.loadMs} ms" } ?: "déjà effectué"}\n")
                    preparation?.let { report.append("Attente de préparation (file comprise) : ${it.waitMs} ms\n") }
                    report.append("Traitement : ${if (example.request.directOutput() != null) "direct local, sans appel LLM" else "Gemma"}\n")
                    report.append("Premier fragment : ${firstTextMs?.let { "$it ms" } ?: if (example.request.directOutput() != null) "sans appel LLM" else "aucun"} ; fin : $totalMs ms\n")
                    report.append(timing.report())
                    if (generated.isFailure) {
                        failed = true
                        report.append("Calcul interrompu (${generated.exceptionOrNull()!!.javaClass.simpleName}), sans publication de résultat partiel.\n")
                    }
                    report.append("Contrôle des modifications : ${if (output != null) "validé" else "rejeté"}\n")
                    val restoredWords = if (output != null && raw != null)
                        GemmaFaithfulLayout.restoredWordCount(raw, output) else 0
                    if (restoredWords > 0) report.append("Mots rétablis depuis la transcription : $restoredWords\n")
                    val grouping = example.grouping?.evaluate(example.request, output)
                    report.append("Regroupement ciblé : " + when {
                        example.grouping == null -> "critère non défini"
                        grouping == null -> if (output == null) "non évalué (sortie rejetée)" else "non évalué (nombre de mots modifié)"
                        grouping.passed -> "réussi"
                        else -> "échoué"
                    } + "\n")
                    if (grouping != null && !grouping.passed) {
                        report.append("Coupures après le mot n° : manquantes ${grouping.missing.sorted()} ; inattendues ${grouping.unexpected.sorted()}\n")
                    }
                    if (output != null && example.request.layoutKind != LocalLayoutKind.LIST) {
                        val paragraphs = ProseParagraphs.count(output, example.request.layoutKind)
                        report.append("Paragraphes du corps : $paragraphs")
                        example.minBodyParagraphs?.let { minimum ->
                            report.append(" ; minimum ciblé $minimum : ${if (paragraphs >= minimum) "réussi" else "échoué"}")
                        }
                        report.append("\n")
                    }
                    if (output == null) {
                        report.append("Repères de contenu : non évalués (aucune sortie validée)")
                    } else {
                        val missing = example.expected.filterNot { hasTerm(output, it) }
                        val unwanted = example.forbidden.filter { hasTerm(output, it) }
                        report.append("Repères de contenu : ${example.expected.size - missing.size}/${example.expected.size}")
                        if (missing.isNotEmpty()) report.append(" ; absents : ${missing.joinToString()}")
                        if (unwanted.isNotEmpty()) report.append(" ; ajouts à vérifier : ${unwanted.joinToString()}")
                    }
                    report.append("\nEntrée : ${example.request.text}\n")
                    if (output != null) report.append("Sortie :\n$output\n\n")
                    else report.append("Sortie absente ou rejetée par les contrôles techniques/vocabulaire.\nBrut : ${raw ?: "aucun texte retourné"}\n\n")
                    if (restoredWords > 0) report.append("Brut avant rétablissement des mots sources :\n$raw\n\n")
                    completed++
                    postUpdate("$completed/$totalRuns essais terminés", completed, report.toString())
                    if (generated.isFailure) break@runs
                }
            }
        } catch (error: Throwable) {
            if (!cancelled.get()) {
                failed = true
                report.append("Le test local n'a pas pu se terminer (${error.javaClass.simpleName}).\n")
                report.append("${LocalFormatRuntimeLabels.failure(pilot, engine.runtimeName(), engine.failureCode())}. ")
                if (pilot) {
                    report.append("Le calcul CPU est le moteur principal ; aucun repli cloud.\n")
                } else {
                    report.append("Aucun repli CPU ou cloud.\n")
                }
            }
        } finally {
            report.append("Après les essais : ${deviceSample()}\n")
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

    private fun deviceSample(): String = runCatching {
        val activity = activityRef.get() ?: return "échantillon indisponible"
        val thermal = (activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
        val memory = android.app.ActivityManager.MemoryInfo()
        (activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
        "PSS processus ${android.os.Debug.getPss() / 1024} Mio${LocalFormatRuntimeLabels.pssNote(pilot)}, RAM disponible ${memory.availMem / (1024 * 1024)} Mio, état thermique Android $thermal"
    }.getOrDefault("échantillon indisponible")

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
