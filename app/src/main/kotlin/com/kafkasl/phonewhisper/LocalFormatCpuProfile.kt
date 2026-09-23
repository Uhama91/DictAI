package com.kafkasl.phonewhisper

import java.util.Locale

/** Immutable CPU generation contracts. Gemma 4 remains the default route. */
internal enum class LocalFormatCpuProfile(
    val contextSize: Int,
    val threads: Int,
    val deadlineMs: Long,
    val maxNewTokens: Int?,
    private val additionalReservedTokens: List<String>,
) {
    Gemma4(
        contextSize = 4096,
        threads = 2,
        deadlineMs = 20_000L,
        maxNewTokens = null,
        additionalReservedTokens = emptyList(),
    ),
    Gemma3Final(
        contextSize = 4096,
        threads = 2,
        deadlineMs = 3_000L,
        maxNewTokens = 256,
        additionalReservedTokens = listOf("<start_of_turn>", "<end_of_turn>"),
    );

    internal fun accepts(request: LocalFormatRequest): Boolean = when (this) {
        Gemma4 -> true
        Gemma3Final -> request.layoutKind == LocalLayoutKind.TEXT &&
            request.language.trim().lowercase(Locale.ROOT).let(FRENCH_LANGUAGE::matches) &&
            request.phase == GemmaFineTunedPrompt.Phase.FINAL &&
            request.contextBefore.isEmpty() &&
            request.instructions.isEmpty() &&
            !request.simpleEmailLayout
    }

    internal fun buildPrompt(request: LocalFormatRequest): String? {
        return when (this) {
            Gemma4 -> {
                val mode = when (request.layoutKind) {
                    LocalLayoutKind.TEXT -> GemmaFineTunedPrompt.Mode.TEXT
                    LocalLayoutKind.LIST -> GemmaFineTunedPrompt.Mode.LIST
                    LocalLayoutKind.EMAIL -> GemmaFineTunedPrompt.Mode.EMAIL
                    null -> return null
                }
                GemmaFineTunedPrompt.buildNativeEnvelope(
                    GemmaFineTunedPrompt.build(
                        request.text,
                        mode,
                        request.phase,
                        request.contextBefore,
                        request.protectedTerms,
                    ),
                )
            }
            Gemma3Final -> try {
                Gemma3RepairPrompt.build(request.text, request.protectedTerms)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    internal fun hasAdditionalReservedToken(value: String): Boolean =
        additionalReservedTokens.any(value::contains)

    companion object {
        private val FRENCH_LANGUAGE = Regex("^(?:french|français|fr|fra)(?:[-_][a-z0-9]+)*$")
    }
}
