package com.kafkasl.phonewhisper

/** Durations measured from backend invocation, after the benchmark's explicit preparation. */
internal data class LocalGenerationTiming(
    val beforeNativeMs: Long?,
    val firstFragmentMs: Long?,
    val callMs: Long,
    val validationMs: Long,
    val returned: Boolean,
) {
    fun report(): String = buildString {
        append("Mesures hors préparation initiale :\n")
        append("Attente avant appel natif (file et création de conversation) : ${beforeNativeMs?.let { "$it ms" } ?: "aucun appel natif"}\n")
        append("Premier fragment depuis l’appel : ${firstFragmentMs?.let { "$it ms" } ?: "aucun"}\n")
        if (returned) {
            append("Retour du moteur depuis l’appel : $callMs ms\n")
            beforeNativeMs?.let { append("Appel natif → retour moteur : ${(callMs - it).coerceAtLeast(0)} ms\n") }
            append("Validation du texte : $validationMs ms\n")
            val completeMs = callMs + validationMs
            append("Appel + validation : $completeMs ms\n")
            for (limit in listOf(5_000L, 8_000L, 10_000L)) {
                val delta = completeMs - limit
                append("Écart au seuil de $limit ms : ${if (delta > 0) "+$delta" else "$delta"} ms\n")
            }
            append("Ces écarts mesurent la durée, pas la fidélité ni le délai d’insertion d’une dictée.\n")
        } else {
            append("Attente observée : $callMs ms ; aucun résultat complet retourné.\n")
            append("Durée nécessaire pour terminer : inconnue (essai interrompu ou moteur indisponible).\n")
        }
    }
}
