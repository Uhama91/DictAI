package com.kafkasl.phonewhisper

/** Explicit reasons the frozen Gemma 3 V3 route must preserve the original text. */
internal enum class Gemma3RepairRefusal {
    NONE,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_LANGUAGE,
    NONEMPTY_CONTEXT,
}

/** The V3 pilot is limited to the built-in corrected-text French preset. */
internal fun gemma3RepairRefusal(
    format: PostProcessingFormat,
    language: DictationLanguage,
): Gemma3RepairRefusal {
    val correctedText = PostProcessingFormats.builtins.single { it.id == "corrected" }
    if (format != correctedText) return Gemma3RepairRefusal.UNSUPPORTED_FORMAT
    if (language != DictationLanguage.FRENCH) return Gemma3RepairRefusal.UNSUPPORTED_LANGUAGE
    return Gemma3RepairRefusal.NONE
}

/** Builds the exact final-only CPU request accepted by the Gemma 3 V3 profile. */
internal fun gemma3FinalLocalFormatRequest(
    source: String,
    language: DictationLanguage,
    protectedTerms: List<String> = emptyList(),
    contextBefore: String = "",
): LocalFormatRequest {
    require(language == DictationLanguage.FRENCH) { "Gemma 3 V3 is available for French only." }
    return LocalFormatRequest(
        text = source,
        instructions = "",
        language = language.cleanupLanguageName,
        protectedTerms = protectedTerms,
        layoutKind = LocalLayoutKind.TEXT,
        validation = LocalFormatValidation.GEMMA3_CONTEXTUAL,
        simpleEmailLayout = false,
        phase = GemmaFineTunedPrompt.Phase.FINAL,
        contextBefore = contextBefore,
    )
}

/** Same six frozen latency transcripts, routed through the exact G3 final-only contract. */
internal fun gemma3FinalLatencyBenchmarkRequests(): List<LocalFormatRequest> =
    LocalLatencyBenchmarkCases.all.map { sample ->
        gemma3FinalLocalFormatRequest(
            source = sample.source,
            language = DictationLanguage.FRENCH,
            protectedTerms = sample.protectedTerms,
        )
    }
