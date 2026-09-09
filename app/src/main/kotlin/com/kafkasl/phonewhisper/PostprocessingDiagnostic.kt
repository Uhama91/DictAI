package com.kafkasl.phonewhisper

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One published operation, containing metadata only; never stores dictated text or vocabulary. */
internal object PostprocessingDiagnostic {
    enum class Requested { LOCAL, CLOUD, OFF }
    enum class Applied { LOCAL_LLM, LOCAL_DIRECT, CLOUD, ORIGINAL }

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
        injection: InjectionResult,
        cloudSuppressed: Boolean,
        modelLoadMs: Long? = null,
        lightTextCleanup: Boolean = false,
    ): String = buildString {
        append("DictAI — dernier post-traitement\n")
        append("Application : $version\n")
        append("Date : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(timestampMs))}\n")
        append("Format : ${when (formatId) { "list" -> "Liste"; "email" -> "Mail"; "cleanup" -> "Texte"; else -> "Personnalisé" }}\n")
        append("Moteur demandé : ${when (requested) { Requested.LOCAL -> "local"; Requested.CLOUD -> "cloud"; Requested.OFF -> "désactivé" }}\n")
        append("Résultat : ${when (applied) {
            Applied.LOCAL_LLM -> "LLM local appliqué"
            Applied.LOCAL_DIRECT -> "traitement direct, sans appel LLM"
            Applied.CLOUD -> "cloud appliqué"
            Applied.ORIGINAL -> if (lightTextCleanup) "traitement léger local" else "transcription conservée"
        }}\n")
        if (requested == Requested.LOCAL) {
            append("Modèle : ${LocalFormatEngine.MODEL_FILE}\n")
            append("Calcul : ${runtime.takeIf { it in setOf("arm64-baseline", "arm64-dotprod-fp16", "not-loaded", "loading", "loading-timeout", "model-missing", "gpu-error", "cancellation-pending", "litert-lm-gpu-mtp-thinking-off") } ?: "indéterminé"}\n")
            if (runtime == "litert-lm-gpu-mtp-thinking-off") append("Thinking : désactivé · budget 0 · MTP activé\n")
            modelLoadMs?.takeIf { it >= 0 }?.let { append("Dernier chargement du moteur partagé : $it ms (peut précéder la dictée)\n") }
            append("Appel natif pour ce résultat : ${if (local?.nativeStarted == true) "oui" else "non"}\n")
            append("Origine : ${when (local?.route) {
                "direct" -> "réponse directe"
                "cache" -> "résultat déjà calculé pour ce texte et ce format"
                "in_flight" -> "calcul déjà en cours"
                "queued" -> "calcul en attente"
                "generated" -> "nouveau calcul"
                else -> "non appelé"
            }}\n")
            append("État : ${when (local?.outcome) {
                "applied" -> "sortie validée (qualité du découpage non garantie)"
                "wait_timeout" -> "délai d’attente finale dépassé"
                "fidelity_rejected" -> "sortie rejetée : texte non conservé"
                "vocabulary_rejected" -> "sortie rejetée : vocabulaire non conservé"
                "backend_empty" -> "moteur sans résultat complet"
                "backend_error", "error" -> "erreur du moteur"
                "cancelled", "interrupted" -> "calcul interrompu"
                else -> if (formatId == "cleanup" && local == null && applied == Applied.ORIGINAL)
                    "mode Texte : aucun appel au LLM prévu"
                else "traitement non exécuté ou indisponible"
            }}\n")
            local?.let { append("Attente finale locale : ${it.waitMs} ms\n") }
            local?.restoredSourceWords?.takeIf { it > 0 }?.let {
                append("Mots rétablis depuis la transcription : $it (aucun mot inventé)\n")
            }
        }
        if (cloudSuppressed) append("Cloud désactivé pour ce champ sensible.\n")
        if (lightTextCleanup) append("Nettoyage léger du texte : règles locales, sans appel LLM.\n")
        append("Post-traitement : $postprocessMs ms\n")
        stopToPublicationMs?.let { append("Arrêt → insertion/copie : $it ms\n") }
        val lines = finalText.lines().filter { it.isNotBlank() }
        append("Lignes non vides : ${lines.size} ; puces : ${lines.count { it.trimStart().startsWith("• ") }}\n")
        append("Publication : ${when (injection) { InjectionResult.Inserted -> "inséré"; InjectionResult.Copied -> "copié"; InjectionResult.Failed -> "échec" }}\n")
        append("Ce diagnostic ne contient ni dictée, ni vocabulaire, ni clé API.")
    }
}
