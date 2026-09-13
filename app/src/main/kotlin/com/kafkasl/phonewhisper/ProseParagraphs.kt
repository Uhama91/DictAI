package com.kafkasl.phonewhisper

/** Structural benchmark indicator only; it does not grade the meaning of paragraph boundaries. */
internal object ProseParagraphs {
    private val greeting = Regex("(?iu)^(bonjour|bonsoir|salut|cher|chère|hello|hi|dear|good morning|good evening)\\b")
    private val closing = Regex("(?iu)^(cordialement|bien cordialement|merci|thank you|thanks|kind regards|best regards|regards|sincerely)\\b")
    fun count(text: String, kind: LocalLayoutKind?): Int {
        var paragraphs = text.trim().split(Regex("\\r?\\n[ \\t]*\\r?\\n+")).filter(String::isNotBlank)
        if (kind == LocalLayoutKind.EMAIL) {
            if (paragraphs.firstOrNull()?.let { greeting.containsMatchIn(it) && it.split(Regex("\\s+")).size <= 10 } == true)
                paragraphs = paragraphs.drop(1)
            val end = paragraphs.indexOfLast { closing.containsMatchIn(it) && it.split(Regex("\\s+")).size <= 12 }
            if (end >= 0) paragraphs = paragraphs.take(end)
        }
        return paragraphs.size
    }
}
