package com.kafkasl.phonewhisper

/** Helpers texte LLM, purs (sans dépendance kmp-ai) → testables en JVM. */
object LlmText {
    /** Retire un éventuel bloc de raisonnement Qwen3 `<think>...</think>`. */
    fun stripThink(raw: String): String {
        val s = raw.trim()
        if (!s.contains("<think>")) return s
        val close = s.indexOf("</think>")
        return if (close >= 0) s.substring(close + "</think>".length).trim() else ""
    }
}
