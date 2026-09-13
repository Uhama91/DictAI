package com.kafkasl.phonewhisper

import java.text.Normalizer
import java.util.Locale

/** Bounded surface edits, followed by the existing lexical/technical-punctuation checks.
 * This is not a semantic equivalence judge. Unrecognised rewrites keep the original dictation.
 */
internal object GemmaConservativeEditing {
    private val word = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val fillers = setOf("euh", "heu", "uh", "um")
    private val articles = setOf("le", "la", "les", "un", "une", "des", "du", "the", "a", "an")
    private val functions = articles + setOf("je", "tu", "il", "elle", "ils", "elles", "i", "we", "he", "she", "they", "que", "qui", "de")
    private val immutable = setOf("ne", "n", "pas", "non", "jamais", "aucun", "aucune", "sans", "plus", "moins", "not", "no", "never", "without", "don", "t", "before", "after", "avant", "après", "hier", "demain")
    private val emphatic = setOf("très", "vraiment", "oui", "nous", "vous", "on", "very", "really", "so", "had", "that")
    private val agreements = listOf(
        "local locale locaux locales", "vert verte verts vertes", "bleu bleue bleus bleues",
        "prêt prête prêts prêtes", "correct correcte corrects correctes", "complet complète complets complètes",
        "court courte courts courtes", "long longue longs longues", "nouveau nouvelle nouveaux nouvelles",
        "premier première premiers premières", "dernier dernière derniers dernières", "seul seule seuls seules",
        "important importante importants importantes", "différent différente différents différentes",
        "satisfait satisfaite satisfaits satisfaites", "actuel actuelle actuels actuelles",
        "petit petite petits petites", "grand grande grands grandes", "clair claire clairs claires",
        "nécessaire nécessaires", "disponible disponibles", "possible possibles",
    ).map { it.split(' ').toSet() }
    private val spelling = mapOf("acceuil" to "accueil", "acceuillir" to "accueillir", "addresse" to "adresse",
        "apparament" to "apparemment", "independant" to "indépendant", "suffisament" to "suffisamment",
        "suffisemment" to "suffisamment", "paragraphees" to "paragraphes", "transciption" to "transcription",
        "recieve" to "receive", "adress" to "address", "seperate" to "separate", "definately" to "definitely",
        "occured" to "occurred", "untill" to "until")

    fun accept(request: LocalFormatRequest, output: String?): String? {
        val policy = request.layoutPolicy() ?: return null
        var candidate = LocalFormatOutput.accept(output)?.takeIf { it.length <= 32768 && '\u0000' !in it } ?: return null
        val source = policy.source
        val src = word.findAll(source).toList()
        val dst = word.findAll(candidate).toList()
        if (src.isEmpty() || src.size > 2048 || dst.isEmpty() || dst.size > src.size) return null
        val locked = protectedRanges(source, request.protectedTerms)
        fun key(value: String) = value.lowercase(Locale.ROOT)
        val a = src.map { key(it.value) }; val b = dst.map { key(it.value) }
        fun gap(i: Int, j: Int) = source.substring(src[i].range.last + 1, src[j].range.first)
        fun plain(i: Int, j: Int) = gap(i, j).all { it.isWhitespace() || it == ',' }
        fun protected(i: Int): Boolean {
            val token = src[i]
            if (token.value.any(Char::isDigit) || a[i] in immutable || locked.any { overlaps(it, token.range) }) return true
            // A lexical edit inside an address, contraction or compound is never a spelling repair.
            if (i > 0 && gap(i - 1, i).none(Char::isWhitespace) && gap(i - 1, i).any { it !in ",.;:!?…" } &&
                !(gap(i - 1, i) in setOf("'", "’") && a[i - 1] in setOf("l", "d", "qu", "c", "s", "t", "m", "j"))) return true
            if (i < src.lastIndex && gap(i, i + 1).none(Char::isWhitespace) && gap(i, i + 1).any { it !in ",.;:!?…" }) return true
            val nameCase = token.value.first().isUpperCase() && a[i] !in functions && a[i] !in fillers
            return nameCase && (token.value.length > 1 && token.value.all(Char::isUpperCase) ||
                i > 0 && gap(i - 1, i).none { it in ".!?\n" })
        }
        val protected = src.indices.map(::protected)
        fun removable(i: Int): Boolean {
            if (protected[i] || a[i] in emphatic) return false
            if (a[i] in fillers) return !Regex("(?iu)\\b(?:mot|word|interjection)\\s*$")
                .containsMatchIn(source.take(src[i].range.first))
            // Remove only an adjacent duplicate (up to four words), retaining the other copy.
            for (length in 1..4) for (offset in 0 until length) {
                val start = i - offset
                if (start >= 0 && start + 2 * length <= src.size &&
                    (start until start + 2 * length - 1).all { plain(it, it + 1) } &&
                    (0 until length).all { a[start + it] == a[start + length + it] } &&
                    (start until start + 2 * length).none { protected[it] || a[it] in emphatic }) return true
            }
            // Dictated false start such as "la le dossier"; keep the following determiner.
            fun restart(other: Int) = other in src.indices &&
                setOf(a[i], a[other]) in setOf(setOf("le", "la"), setOf("un", "une"), setOf("a", "an")) &&
                plain(minOf(i, other), maxOf(i, other))
            return restart(i - 1) || restart(i + 1)
        }
        val removable = src.indices.map(::removable)
        val width = dst.size + 1
        val inf = 100_000
        val cost = IntArray((src.size + 1) * width) { inf }
        cost[0] = 0
        val unaccentedCandidates = b.distinct().groupBy(::unaccent)
        val corrections = a.distinct().associateWith { sourceWord ->
            if (sourceWord in immutable) emptySet() else buildSet {
                spelling[sourceWord]?.let(::add)
                agreements.filter { sourceWord in it }.forEach { addAll(it) }
                if (sourceWord.length >= 5 && sourceWord == unaccent(sourceWord))
                    addAll(unaccentedCandidates[sourceWord].orEmpty())
                removeAll(immutable)
            }
        }
        fun sameNumber(i: Int, j: Int): Boolean {
            if (numberValue(a[i]) == null || numberValue(a[i]) != numberValue(b[j])) return false
            if (locked.any { overlaps(it, src[i].range) }) return false
            // Do not normalize fragments of decimals, identifiers, addresses or quoted numbers.
            return (i == 0 || gap(i - 1, i).any(Char::isWhitespace)) &&
                (i == src.lastIndex || gap(i, i + 1).any(Char::isWhitespace))
        }
        fun replacement(i: Int, j: Int) = !protected[i] && b[j] in corrections.getValue(a[i])
        // No added words. A few ordinary omissions can still be restored by the strict mail validator.
        fun deletion(i: Int) = if (removable[i]) 1 else if (!protected[i] ||
            a[i] == "c" && i < src.lastIndex && a[i + 1] == "est" && gap(i, i + 1) in setOf("'", "’") &&
            locked.none { overlaps(it, src[i].range) }) 5 else inf
        for (i in 1..src.size) {
            cost[i * width] = (cost[(i - 1) * width] + deletion(i - 1)).coerceAtMost(inf)
            for (j in 1..minOf(i, dst.size)) {
                val edit = when { a[i - 1] == b[j - 1] || sameNumber(i - 1, j - 1) -> 0; replacement(i - 1, j - 1) -> 2; else -> inf }
                cost[i * width + j] = minOf(cost[(i - 1) * width + j - 1] + edit,
                    cost[(i - 1) * width + j] + deletion(i - 1), inf)
            }
        }
        if (cost.last() >= inf) return null
        val edits = mutableListOf<Pair<IntRange, String>>()
        val candidateEdits = mutableListOf<Pair<IntRange, String>>()
        val alignment = mutableMapOf<Int, Int>()
        var i = src.size; var j = dst.size; var substitutions = 0; var removals = 0
        while (i > 0) {
            val edit = if (j == 0) inf else when { a[i - 1] == b[j - 1] || sameNumber(i - 1, j - 1) -> 0; replacement(i - 1, j - 1) -> 2; else -> inf }
            if (j > 0 && edit < inf && cost[i * width + j] == cost[(i - 1) * width + j - 1] + edit) {
                alignment[i - 1] = j - 1
                if (edit == 0 && a[i - 1] != b[j - 1]) candidateEdits.add(dst[j - 1].range to src[i - 1].value)
                if (edit > 0) { edits.add(src[i - 1].range to dst[j - 1].value); substitutions++ }
                i--; j--
            } else {
                val index = --i
                if (removable[index]) {
                    val end = if (index < src.lastIndex && plain(index, index + 1)) src[index + 1].range.first - 1 else src[index].range.last
                    edits.add(src[index].range.first..end to ""); removals++
                }
                // Leave other omitted words in the source for sparse, uniquely aligned restoration.
            }
        }
        if (j != 0 || substitutions > maxOf(2, minOf(16, src.size / 10)) || removals > maxOf(3, src.size / 4)) return null
        // Recognised compound spelling; never normalize arbitrary address/number punctuation.
        for ((sourceIndex, candidateIndex) in alignment) {
            if (sourceIndex < src.lastIndex && candidateIndex < dst.lastIndex &&
                alignment[sourceIndex + 1] == candidateIndex + 1 &&
                listOf(a[sourceIndex], a[sourceIndex + 1]) in listOf(listOf("post", "traitement"), listOf("post", "processing")) &&
                !protected[sourceIndex] && !protected[sourceIndex + 1] && gap(sourceIndex, sourceIndex + 1).all(Char::isWhitespace) &&
                candidate.substring(dst[candidateIndex].range.last + 1, dst[candidateIndex + 1].range.first) == "-")
                edits.add(src[sourceIndex].range.last + 1 until src[sourceIndex + 1].range.first to "-")
        }
        var correctedSource = source
        for ((range, value) in edits.sortedByDescending { it.first.first })
            correctedSource = correctedSource.replaceRange(range, value)
        for ((range, value) in candidateEdits.sortedByDescending { it.first.first })
            candidate = candidate.replaceRange(range, value)
        return GemmaFaithfulLayout.accept(request.copy(text = correctedSource), candidate)
    }

    // Exact equivalents only: restore the source presentation, never a changed numeric value.
    private val numberWords = listOf(
        "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty",
        "zéro un deux trois quatre cinq six sept huit neuf dix onze douze treize quatorze quinze seize",
    ).flatMap { it.split(' ').mapIndexed { index, value -> value to index } }.toMap()
    private fun numberValue(value: String): Int? = numberWords[value] ?: value.toIntOrNull()?.takeIf {
        it in 0..20 && it.toString() == value
    }

    private val accents = Regex("\\p{M}+")
    private fun unaccent(value: String) = accents.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "")

    fun caseProtectedRanges(source: String, terms: List<String>): List<IntRange> =
        protectedRanges(source, terms) + word.findAll(source).filter { it.value.first().isUpperCase() }.map { it.range }.toList()

    private fun overlaps(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last
    private fun protectedRanges(source: String, terms: List<String>): List<IntRange> = buildList {
        var start = -1; var close = ' '
        source.forEachIndexed { i, c ->
            if (start >= 0) {
                val apostropheInWord = c == '\'' && i > 0 && i < source.lastIndex &&
                    source[i - 1].isLetter() && source[i + 1].isLetter()
                if (c == close && !apostropheInWord) { add(start..i); start = -1 }
            }
            else if (c in "\"«“`" || c == '\'' && (i == 0 || source[i - 1].isWhitespace())) {
                start = i; close = when (c) { '«' -> '»'; '“' -> '”'; else -> c }
            }
        }
        if (start >= 0) add(start..source.lastIndex)
        Regex("\\S*(?:https?://|www\\.|@|/|\\\\|\\.[A-Za-z]{2,8})\\S*").findAll(source).forEach { add(it.range) }
        terms.filter(String::isNotBlank).forEach { term ->
            Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(source).forEach { add(it.range) }
        }
    }
}
