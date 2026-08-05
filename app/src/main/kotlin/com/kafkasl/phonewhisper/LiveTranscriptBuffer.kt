package com.kafkasl.phonewhisper

/** Bounds the live overlay while always keeping the latest complete words visible. */
class LiveTranscriptBuffer(private val maxChars: Int = 4_096) {
    init { require(maxChars > 0) }

    fun clear() = Unit

    fun render(committed: String, tentative: String): String {
        val combined = listOf(committed.trim(), tentative.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (combined.length <= maxChars) return combined
        val firstBoundary = combined.indexOf(' ', combined.length - maxChars)
        return if (firstBoundary >= 0) combined.substring(firstBoundary + 1) else combined.takeLast(maxChars)
    }
}
