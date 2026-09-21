package com.kafkasl.phonewhisper

/** Length bucket used only to keep the latency sample balanced across input sizes. */
internal enum class LocalLatencyBucket { SHORT, MEDIUM, LONG }

private val CORRECTED_TEXT_INSTRUCTIONS = PostProcessingFormats.builtins
    .first { it.id == "corrected" }
    .instructions

/** A fixed, synthetic French transcript for the pilot latency bench. */
internal data class LocalLatencyBenchmarkCase(
    val id: String,
    val bucket: LocalLatencyBucket,
    val label: String,
    val source: String,
    val protectedTerms: List<String> = emptyList(),
) {
    val language: String = "French"

    /** All six cases deliberately take the real final editing path. */
    val request: LocalFormatRequest
        get() = LocalFormatRequest(
            text = source,
            instructions = CORRECTED_TEXT_INSTRUCTIONS,
            language = language,
            protectedTerms = protectedTerms,
            layoutKind = LocalLayoutKind.TEXT,
            validation = LocalFormatValidation.GEMMA_EDITING,
            simpleEmailLayout = false,
            phase = GemmaFineTunedPrompt.Phase.FINAL,
    )
}

internal enum class LocalLatencyDisposition(val label: String) {
    ACCEPTED_UNCHANGED("accepté inchangé"),
    ACCEPTED_MODIFIED("accepté modifié"),
    FALLBACK_SOURCE("repli · source conservée"),
}

internal data class LocalLatencyOutcome(
    val complete: Boolean,
    val disposition: LocalLatencyDisposition,
    val direct: Boolean,
    val failureCode: String?,
)

/** One measured output, kept separate from the UI so aggregate rules stay testable. */
internal data class LocalLatencyObservation(
    val totalMs: Long,
    val firstFragmentMs: Long?,
    val complete: Boolean,
    val disposition: LocalLatencyDisposition,
    val direct: Boolean,
)

internal data class LocalLatencySummary(
    val completeDurationsMs: List<Long>,
    val acceptedDurationsMs: List<Long>,
    val completeFirstFragmentsMs: List<Long>,
    val acceptedFirstFragmentsMs: List<Long>,
    val directCount: Int,
    val incompleteCount: Int,
) {
    val completeCount: Int get() = completeDurationsMs.size
    val acceptedCount: Int get() = acceptedDurationsMs.size
}

/** Direct outputs and rejected/incomplete calls never inflate accepted LLM latency. */
internal fun summarizeLocalLatencyObservations(
    observations: List<LocalLatencyObservation>,
): LocalLatencySummary {
    val complete = observations.filter { it.complete && !it.direct }
    val accepted = complete.filter { it.disposition != LocalLatencyDisposition.FALLBACK_SOURCE }
    return LocalLatencySummary(
        completeDurationsMs = complete.map { it.totalMs },
        acceptedDurationsMs = accepted.map { it.totalMs },
        completeFirstFragmentsMs = complete.mapNotNull { it.firstFragmentMs },
        acceptedFirstFragmentsMs = accepted.mapNotNull { it.firstFragmentMs },
        directCount = observations.count { it.direct },
        incompleteCount = observations.count { !it.complete },
    )
}

internal fun classifyLocalLatencyOutcome(
    source: String,
    raw: String?,
    output: String?,
    generationError: String?,
    direct: Boolean,
): LocalLatencyOutcome {
    val complete = generationError == null && !raw.isNullOrBlank()
    val disposition = when {
        output == null -> LocalLatencyDisposition.FALLBACK_SOURCE
        output == source -> LocalLatencyDisposition.ACCEPTED_UNCHANGED
        else -> LocalLatencyDisposition.ACCEPTED_MODIFIED
    }
    val failureCode = when {
        generationError != null -> "generation_$generationError"
        raw.isNullOrBlank() -> "sortie_absente"
        output == null -> "validation_rejetee"
        else -> null
    }
    return LocalLatencyOutcome(complete, disposition, direct, failureCode)
}

internal fun medianLatencyMs(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle]
    else (sorted[middle - 1] + sorted[middle]) / 2L
}

internal object LocalLatencyBenchmarkCases {
    const val PASS_COUNT = 3

    val all: List<LocalLatencyBenchmarkCase> = listOf(
        LocalLatencyBenchmarkCase(
            id = "latency-1",
            bucket = LocalLatencyBucket.SHORT,
            label = "Déterminant, hésitation, conjonction abandonnée, négation, date et nombre",
            source = "Je prends le dossier, euh, le la version signée pour la réunion du mardi, non du mercredi, et… enfin, non, mais je ne supprime pas les trois pages annexes avant midi.",
        ),
        LocalLatencyBenchmarkCase(
            id = "latency-2",
            bucket = LocalLatencyBucket.SHORT,
            label = "Prose déjà propre",
            source = "La réunion commence à neuf heures dans la salle bleue. Les trois participants apporteront le dossier complet et resteront jusqu'à midi pour préparer calmement la séance.",
        ),
        LocalLatencyBenchmarkCase(
            id = "latency-3",
            bucket = LocalLatencyBucket.MEDIUM,
            label = "Reformulation de date et informations voisines",
            source = "Pour la visite de jeudi, je confirme d'abord le rendez-vous du mardi quatorze octobre, enfin du mercredi quinze octobre, avec Nadia et Karim. Le taxi doit partir de l'entrée nord et attendre devant la bibliothèque municipale. Merci de garder le reçu, le plan d'accès et le numéro du chauffeur dans le dossier partagé afin que chacun retrouve les informations avant le départ. Si le groupe arrive en avance, prévenir l'accueil et rester dans le hall jusqu'à l'ouverture de la salle.",
            protectedTerms = listOf("Nadia", "Karim"),
        ),
        LocalLatencyBenchmarkCase(
            id = "latency-4",
            bucket = LocalLatencyBucket.MEDIUM,
            label = "Énumération locale, introduction et conclusion",
            source = "Pour préparer la sortie de vendredi, il faut vérifier les autorisations des vingt-quatre élèves, réserver le minibus et prévenir les familles avant mercredi. On gardera la liste dans le classeur bleu, puis on remettra les fiches signées au secrétariat. La sortie commencera à huit heures et se terminera avant seize heures, sans changer le rendez-vous prévu. Après le retour, compter les sacs, noter les objets oubliés et transmettre le bilan à la direction avant lundi.",
        ),
        LocalLatencyBenchmarkCase(
            id = "latency-5",
            bucket = LocalLatencyBucket.LONG,
            label = "Consignes scolaires, hésitations, répétitions et négations",
            source = "Pour la séance de sciences de lundi, euh, il faut préparer les les douze béchers et et vérifier que les robinets sont fermés avant l'arrivée des élèves. Je répète, les groupes de Maëlys, Karim et Inès doivent rester dans la salle deux, et personne ne doit toucher au matériel avant mon signal.\n\nD'abord, noter la température de départ dans le tableau, ensuite distribuer une fiche par groupe, puis attendre cinq minutes avant de comparer les résultats. Ne pas jeter les échantillons, même si une mesure paraît fausse, et ne pas déplacer la boîte rouge près de la fenêtre. Après la séance, ranger les éprouvettes dans l'armoire du fond et laisser les feuilles signées sur mon bureau. Enfin, prévenir la direction si les douze résultats ne sont pas lisibles. La classe voisine commencera son activité à dix heures, donc fermer la porte et conserver le silence pendant les observations. Si un élève renverse de l'eau, ne pas utiliser le matériel de secours sans me prévenir et noter l'incident dans le cahier bleu.",
            protectedTerms = listOf("Maëlys", "Karim", "Inès"),
        ),
        LocalLatencyBenchmarkCase(
            id = "latency-6",
            bucket = LocalLatencyBucket.LONG,
            label = "Prose longue déjà propre",
            source = "Le centre culturel ouvre ses portes à huit heures trente et accueille les visiteurs jusqu'à dix-huit heures, du mardi au samedi. À l'entrée, une équipe présente les salles, remet un plan imprimé et indique les horaires des visites guidées. Le parcours principal traverse la galerie des peintures, la bibliothèque et l'atelier de restauration, où les visiteurs peuvent observer les gestes précis des professionnels sans toucher aux œuvres. Une pause est prévue à midi dans la cour intérieure, puis les activités reprennent avec une conférence consacrée aux archives locales. Les réservations restent possibles en ligne, tandis que les groupes scolaires doivent confirmer leur horaire au moins une semaine à l'avance. En cas de changement, l'accueil prévient chaque responsable et conserve la trace de l'information dans le registre quotidien. La journée se termine par la fermeture des vitrines, la vérification des lumières et le rangement des plans restants. Le dimanche, le bâtiment reste fermé au public, mais l'équipe technique peut entrer pour préparer une exposition et contrôler l'éclairage des salles. Les visiteurs doivent suivre les indications affichées afin de protéger les objets fragiles et de laisser les couloirs accessibles aux personnes à mobilité réduite.",
        ),
    )
}
