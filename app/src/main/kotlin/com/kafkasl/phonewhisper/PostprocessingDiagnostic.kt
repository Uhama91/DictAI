package com.kafkasl.phonewhisper

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User-facing runtime labels shared by the benchmark and persisted diagnostic. */
internal object LocalFormatRuntimeLabels {
    private const val PILOT_CONFIGURATION = "CPU · llama.cpp · greedy · thinking désactivé (budget 0)"
    private val GEMMA3_REPAIR_CONFIGURATION =
        "CPU · llama.cpp · greedy · Gemma 3 270M V3 · attente finale maximale ${LocalFormatCpuProfile.Gemma3Final.deadlineMs} ms"
    private const val GPU_CONFIGURATION = "LiteRT-LM 0.17.0 · GPU · MTP activé · thinking désactivé (budget 0)"
    private val KNOWN_RUNTIMES = setOf(
        "arm64-baseline",
        "arm64-dotprod-fp16",
        "not-loaded",
        "loading",
        "loading-timeout",
        "model-missing",
        "cpu-error",
        "cpu-timeout",
        "gpu-error",
        "cancellation-pending",
        "litert-lm-gpu-mtp-thinking-off",
    )

    fun model(pilot: Boolean, gemma3RepairPilot: Boolean = false): String = if (gemma3RepairPilot) {
        "${GemmaModelStore.modelTitleForBuild(gemma4Pilot = false, gemma3RepairPilot = true)} · " +
            GemmaModelStore.modelFileForBuild(gemma4Pilot = false, gemma3RepairPilot = true)
    } else if (pilot) {
        "${GemmaModelStore.modelTitle(pilot = true)} · ${GemmaModelStore.modelFile(pilot = true)}"
    } else {
        GpuLocalFormatEngine.MODEL_FILE
    }

    fun configuration(pilot: Boolean, gemma3RepairPilot: Boolean = false): String = when {
        gemma3RepairPilot -> GEMMA3_REPAIR_CONFIGURATION
        pilot -> PILOT_CONFIGURATION
        else -> GPU_CONFIGURATION
    }

    fun cacheNote(pilot: Boolean, gemma3RepairPilot: Boolean = false): String = if (pilot || gemma3RepairPilot) {
        "Caches système/CPU non vidés."
    } else {
        "Caches système/GPU non vidés."
    }

    fun pssNote(pilot: Boolean, gemma3RepairPilot: Boolean = false): String = if (pilot || gemma3RepairPilot) {
        ""
    } else {
        " (mémoire GPU partagée potentiellement exclue)"
    }

    fun calculation(pilot: Boolean, runtime: String, gemma3RepairPilot: Boolean = false): String {
        val engine = when {
            gemma3RepairPilot -> "CPU · llama.cpp · greedy · Gemma 3 270M V3"
            pilot -> "CPU · llama.cpp · greedy"
            else -> "GPU · LiteRT-LM · MTP · thinking désactivé"
        }
        return "$engine · ${safeRuntime(runtime)}"
    }

    fun failure(pilot: Boolean, runtime: String, code: String?, gemma3RepairPilot: Boolean = false): String {
        val state = if (pilot || gemma3RepairPilot) "État CPU" else "État GPU"
        return "$state : ${safeRuntime(runtime)} ; motif : ${code ?: "indisponible"}"
    }

    private fun safeRuntime(value: String): String = value.takeIf { it in KNOWN_RUNTIMES } ?: "indéterminé"
}

/** One published operation, containing metadata only; never stores dictated text or vocabulary. */
internal object PostprocessingDiagnostic {
    enum class Requested { LOCAL, CLOUD, OFF }
    enum class Applied { LOCAL_LLM, LOCAL_DIRECT, CLOUD, ORIGINAL }
    enum class PublicationResult {
        INSERTED,
        COPIED,
        FAILED,
        NOTE_SAVED;

        companion object {
            fun fromInjection(result: InjectionResult): PublicationResult = when (result) {
                InjectionResult.Inserted -> INSERTED
                InjectionResult.Copied -> COPIED
                InjectionResult.Failed -> FAILED
            }

            /** Maps only an operation that has crossed its publication boundary. */
            fun fromDestination(
                destination: NoteInteractionPolicy.Destination,
                injection: InjectionResult? = null,
            ): PublicationResult? = when {
                destination != NoteInteractionPolicy.Destination.MESSAGE -> NOTE_SAVED
                injection != null -> fromInjection(injection)
                else -> null
            }
        }
    }

    fun report(
        version: String,
        timestampMs: Long,
        formatId: String,
        requested: Requested,
        applied: Applied,
        local: LocalFinishDiagnostic?,
        runtime: String,
        postprocessMs: Long,
        stopToPublicationMs: Long?,
        finalText: String,
        publication: PublicationResult,
        cloudSuppressed: Boolean,
        modelLoadMs: Long? = null,
        lightTextCleanup: Boolean = false,
        hesitationsRemoved: Int = 0,
        pilot: Boolean = BuildConfig.GEMMA4_FINE_TUNED_PILOT,
        gemma3RepairPilot: Boolean = BuildConfig.GEMMA3_REPAIR_PILOT,
        gemma3Refusal: Gemma3RepairRefusal? = null,
        progressive: ProgressiveFormattingDiagnosticSnapshot? = null,
        asrFinalRecoveryMs: Long? = null,
        asrAwaitSessionExitMs: Long? = null,
    ): String = buildString {
        append("DictAI — dernier post-traitement\n")
        append("Application : $version\n")
        append("Date : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(timestampMs))}\n")
        append("Format : ${when (formatId) { "list" -> "Liste"; "email" -> "Mail"; "cleanup" -> "Texte"; "corrected" -> "Texte corrigé"; else -> "Personnalisé" }}\n")
        append("Moteur demandé : ${when (requested) { Requested.LOCAL -> "local"; Requested.CLOUD -> "cloud"; Requested.OFF -> "désactivé" }}\n")
        val refusal = gemma3Refusal?.takeIf { gemma3RepairPilot && it != Gemma3RepairRefusal.NONE }
            ?: if (gemma3RepairPilot && local?.outcome == "profile_refused_language")
                Gemma3RepairRefusal.UNSUPPORTED_LANGUAGE else null
        append("Résultat : ${when {
            refusal != null -> "modèle local refusé (${refusalLabel(refusal)}) ; transcription conservée"
            gemma3RepairPilot && local == null && progressive != null -> progressiveResultLabel(progressive, applied)
            else -> when (applied) {
                Applied.LOCAL_LLM -> if ((pilot || gemma3RepairPilot) && local == null) progressiveResultLabel(progressive) else "LLM local appliqué"
                Applied.LOCAL_DIRECT -> if ((pilot || gemma3RepairPilot) && local == null) progressiveResultLabel(progressive) else "traitement direct, sans appel LLM"
                Applied.CLOUD -> "cloud appliqué"
                Applied.ORIGINAL -> when {
                    hesitationsRemoved > 0 -> "hésitations retirées localement, sans réécriture appliquée"
                    lightTextCleanup -> "traitement léger local"
                    else -> "transcription conservée"
                }
            }
        }}\n")
        if (requested == Requested.LOCAL) {
            append("Modèle : ${LocalFormatRuntimeLabels.model(pilot, gemma3RepairPilot)}\n")
            append("Calcul : ${LocalFormatRuntimeLabels.calculation(pilot, runtime, gemma3RepairPilot)}\n")
            if (gemma3RepairPilot) {
                val waitLimit = progressive?.configuredWaitLimitMs ?: local?.waitLimitMs
                    ?: LocalFormatCpuProfile.Gemma3Final.deadlineMs
                append("Limite d’attente finale locale : $waitLimit ms\n")
            }
            if (pilot) {
                append("Thinking : désactivé · budget 0\n")
            } else if (runtime == "litert-lm-gpu-mtp-thinking-off") {
                append("Thinking : désactivé · budget 0 · MTP activé\n")
            }
            modelLoadMs?.takeIf { it >= 0 }?.let { append("Dernier chargement du moteur partagé : $it ms (peut précéder la dictée)\n") }
            if (gemma3RepairPilot && progressive != null) {
                val finalCall = progressive.calls.lastOrNull { it.phase == GemmaFineTunedPrompt.Phase.FINAL }
                val nativeStarted = local?.nativeStarted == true || finalCall?.nativeStarted == true
                append("Appel natif pour ce résultat : ${if (nativeStarted) "oui" else "non"}\n")
                append("Origine : ${when {
                    refusal != null -> "refus du profil Gemma 3"
                    finalCall == null -> "aucun appel final mesuré"
                    else -> "requête finale Gemma 3"
                }}\n")
            } else if (gemma3RepairPilot && refusal != null) {
                append("Appel natif pour ce résultat : non\n")
                append("Origine : refus avant le moteur Gemma 3\n")
            } else if (pilot && local == null && progressive != null) {
                append("Appel natif pour ce résultat : instrumentation progressive\n")
                append("Origine : trace par appel, sans conservation de texte\n")
            } else if ((pilot || gemma3RepairPilot) && local == null) {
                append("Appel natif pour ce résultat : détail des appels progressifs non mesuré\n")
                append("Origine : détail des appels progressifs non mesuré\n")
            } else {
                append("Appel natif pour ce résultat : ${if (local?.nativeStarted == true) "oui" else "non"}\n")
                append("Origine : ${when (local?.route) {
                    "direct" -> "réponse directe"
                    "cache" -> "résultat déjà calculé pour ce texte et ce format"
                    "in_flight" -> "calcul déjà en cours"
                    "queued" -> "calcul en attente"
                    "generated" -> "nouveau calcul"
                    else -> "non appelé"
                }}\n")
            }
            append("État : ${if (refusal != null) "Refus : ${refusalLabel(refusal)}" else if (gemma3RepairPilot && local == null && progressive != null) {
                progressiveResultLabel(progressive, applied)
            } else when (local?.outcome) {
                "applied" -> "sortie validée (qualité du découpage non garantie)"
                "wait_timeout" -> "délai d’attente finale dépassé"
                "fidelity_rejected" -> "sortie rejetée : modification hors corrections autorisées"
                "vocabulary_rejected" -> "sortie rejetée : vocabulaire non conservé"
                "backend_empty" -> "moteur sans résultat complet"
                "backend_error", "error" -> "erreur du moteur"
                "cancelled", "interrupted" -> "calcul interrompu"
                else -> if (pilot && local == null) {
                    progressiveResultLabel(progressive, applied)
                } else if (formatId == "cleanup" && local == null && applied == Applied.ORIGINAL)
                    "mode Texte : aucun appel au LLM prévu"
                else "traitement non exécuté ou indisponible"
            }}\n")
            if (local?.surfaceEditing == true) append("Corrections : fautes de forme et répétitions limitées autorisées.\n")
            local?.let { append("Attente finale locale : ${it.waitMs} ms\n") }
            local?.waitLimitMs?.let { append("Limite d’attente finale : $it ms\n") }
            local?.restoredSourceWords?.takeIf { it > 0 }?.let {
                append("Mots rétablis depuis la transcription : $it (aucun mot inventé)\n")
            }
        }
        asrFinalRecoveryMs?.takeIf { it >= 0 }?.let { append("ASR — récupération finale : $it ms\n") }
        asrAwaitSessionExitMs?.takeIf { it >= 0 }?.let { append("ASR — attente sortie session : $it ms\n") }
        progressive?.let {
            append(it.summary())
            append('\n')
        }
        if (cloudSuppressed) append("Cloud désactivé pour ce champ sensible.\n")
        if (lightTextCleanup) append("Nettoyage léger du texte : règles locales, sans appel LLM.\n")
        if (hesitationsRemoved > 0) append("Hésitations retirées avant correction : $hesitationsRemoved · règles locales.\n")
        append("Post-traitement : $postprocessMs ms\n")
        stopToPublicationMs?.let {
            val label = if (publication == PublicationResult.NOTE_SAVED) {
                "Fin → enregistrement de la note"
            } else {
                "Arrêt → insertion/copie"
            }
            append("$label : $it ms\n")
        }
        val lines = finalText.lines().filter { it.isNotBlank() }
        append("Lignes non vides : ${lines.size} ; puces : ${lines.count { it.trimStart().startsWith("• ") }}\n")
        append("Publication : ${when (publication) {
            PublicationResult.INSERTED -> "inséré"
            PublicationResult.COPIED -> "copié"
            PublicationResult.FAILED -> "échec"
            PublicationResult.NOTE_SAVED -> "note enregistrée"
        }}\n")
        append("Ce diagnostic ne contient ni dictée, ni vocabulaire, ni clé API.")
    }

    private fun progressiveResultLabel(
        progressive: ProgressiveFormattingDiagnosticSnapshot?,
        applied: Applied? = null,
    ): String = when {
        progressive == null && applied == Applied.ORIGINAL -> "texte conservé"
        progressive == null -> "correction progressive appliquée"
        progressive.resultsApplied + progressive.resultsUnchanged > 0 && progressive.stillDelivered > 0 ->
            "correction progressive appliquée"
        progressive.acceptedThenInvalidated > 0 -> "correction progressive invalidée"
        progressive.resultsRejected > 0 -> "correction progressive rejetée"
        progressive.resultsAbsent > 0 -> "correction progressive sans résultat"
        progressive.resultsNotReturned > 0 || progressive.finalDeadlineExceeded > 0 ->
            "correction progressive non retournée"
        else -> "texte conservé"
    }

    private fun refusalLabel(refusal: Gemma3RepairRefusal): String = when (refusal) {
        Gemma3RepairRefusal.NONE -> "aucun"
        Gemma3RepairRefusal.UNSUPPORTED_FORMAT -> "format non pris en charge"
        Gemma3RepairRefusal.UNSUPPORTED_LANGUAGE -> "langue non prise en charge"
        Gemma3RepairRefusal.NONEMPTY_CONTEXT -> "contexte non vide"
    }
}
