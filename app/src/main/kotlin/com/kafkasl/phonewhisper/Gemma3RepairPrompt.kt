package com.kafkasl.phonewhisper

/**
 * Pure Kotlin port of the finalized-text Gemma 3 V3 prompt.
 *
 * This helper is deliberately not connected to inference. Its envelope is
 * Gemma 3's native format and must not be reused for Gemma 4. It accepts one
 * finalized text segment only; lists, email modes, and partial segments are
 * intentionally outside this API.
 */
internal object Gemma3RepairPrompt {
    const val PROMPT_VERSION_V3 = "v3"

    /** Builds the frozen V3 user prompt inside Gemma 3's native chat envelope. */
    fun build(source: String, protectedTerms: List<String> = emptyList()): String {
        val finalSource = stripPythonWhitespace(source)
        require(finalSource.isNotEmpty()) { "source must not be empty" }

        val protectedTermsBlock = buildProtectedTermsBlock(protectedTerms)
        val dataBlock = if (protectedTermsBlock.isEmpty()) {
            ""
        } else {
            "$protectedTermsBlock\n\n"
        }
        val userContent =
            "$INSTRUCTION_V3\n\n${dataBlock}<transcription>\n$finalSource\n</transcription>"

        return "$NATIVE_BOS$NATIVE_USER_OPEN$userContent$NATIVE_USER_CLOSE$NATIVE_MODEL_OPEN"
    }

    private fun buildProtectedTermsBlock(protectedTerms: List<String>): String {
        if (protectedTerms.isEmpty()) return ""

        protectedTerms.forEach { term ->
            require(stripPythonWhitespace(term).isNotEmpty()) {
                "protected term must be nonempty and single-line"
            }
            require('\r' !in term && '\n' !in term && '<' !in term && '>' !in term) {
                "protected term must be nonempty and single-line"
            }
        }

        return buildString {
            append("<protected_terms>\n")
            protectedTerms.forEachIndexed { index, term ->
                if (index > 0) append('\n')
                append(term)
            }
            append("\n</protected_terms>")
        }
    }

    /** Matches Python 3.12 str.strip()/str.isspace() for the frozen source helper. */
    private fun stripPythonWhitespace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && value[start].isPythonWhitespace()) start++
        while (end > start && value[end - 1].isPythonWhitespace()) end--
        return value.substring(start, end)
    }

    private fun Char.isPythonWhitespace(): Boolean = when (this) {
        in '\u0009'..'\u000D',
        in '\u001C'..'\u0020',
        '\u0085',
        '\u00A0',
        '\u1680',
        in '\u2000'..'\u200A',
        '\u2028',
        '\u2029',
        '\u202F',
        '\u205F',
        '\u3000',
        -> true
        else -> false
    }

    private const val NATIVE_BOS = "<bos>"
    private const val NATIVE_USER_OPEN = "<start_of_turn>user\n"
    private const val NATIVE_USER_CLOSE = "<end_of_turn>\n"
    private const val NATIVE_MODEL_OPEN = "<start_of_turn>model\n"

    private const val INSTRUCTION_V3 =
        "Nettoie cette transcription sans la résumer ni la reformuler. " +
            "Rétablis ponctuation, majuscules et paragraphes. " +
            "Supprime les hésitations et répétitions accidentelles, mais conserve les insistances. " +
            "Lors d'une autocorrection explicite, remplace le passage erroné par la formulation finale. " +
            "Corrige un mot mal transcrit seulement si le contexte rend la correction claire ; " +
            "pour un nom rare, appuie-toi sur une forme correcte présente dans le passage. " +
            "Sinon, conserve le texte ambigu. " +
            "Préserve toutes les autres informations, les nombres, les négations, les incertitudes " +
            "et les erreurs citées. " +
            "Renvoie seulement le texte nettoyé."
}
