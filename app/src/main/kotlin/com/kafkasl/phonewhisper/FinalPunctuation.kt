package com.kafkasl.phonewhisper

/** Final prose-only pass; never run on partial ASR text or on a constrained LLM request. */
internal object FinalPunctuation {
    fun apply(text: String, formatId: String): String {
        if (formatId !in setOf("cleanup", "email")) return text
        var afterClosing = false
        return text.split('\n').joinToString("\n") { line ->
            val content = line.trim()
            if (afterClosing && content.isNotEmpty() && !signatureLine.matches(content) && !closing.matches(content)) afterClosing = false
            val emailDecoration = formatId == "email" && (afterClosing || greeting.matches(content) || closing.matches(content))
            if (formatId == "email" && closing.matches(content)) afterClosing = true
            if (emailDecoration || content.isEmpty() || !content.last().isLetterOrDigit() ||
                bullet.containsMatchIn(content) || signature.matches(content) ||
                content.split(Regex("\\s+")).size < 2 || protectedTail.containsMatchIn(content)
            ) line else line.trimEnd() + "." + line.takeLastWhile { it.isWhitespace() }
        }
    }

    private val bullet = Regex("^(?:[•*−–-]\\s|[0-9]+[.)]\\s)")
    private val signature = Regex("(?u)^(?i:m\\.|mme|mr\\.?|mrs\\.?|ms\\.?|dr\\.?)\\s+\\p{Lu}[\\p{L}’'-]*(?:\\s+(?:\\p{Lu}[\\p{L}’'-]*|de|du|van)){0,4}$")
    private val signatureLine = Regex("(?u)^(?:(?:(?i:m\\.|mme|mr\\.?|mrs\\.?|ms\\.?|dr\\.?)\\s+)?\\p{Lu}[\\p{L}’'-]*(?:\\s+(?:\\p{Lu}[\\p{L}’'-]*|de|du|van)){0,4}[.]?|\\S+@\\S+|\\+?[0-9][0-9 ()-]{7,})$")
    private val greeting = Regex("(?u)^(?i:bonjour|bonsoir|salut|hello|hi|dear|good morning)(?:\\s+(?:(?i:madame|monsieur|tout le monde|à tous|team|everyone)|\\p{Lu}[\\p{L}’'.,-]*)){0,4}[,!]?$")
    private val closing = Regex("(?u)^(?i:bien cordialement|cordialement|bien à vous|à bientôt|merci|merci et (?:bonne journée|à bientôt)|best regards|kind regards|regards|sincerely|thanks|thank you)[,.!]?(?:\\s+(?:(?:M\\.|Mme|Mr\\.?|Ms\\.?|Dr\\.?)\\s+)?\\p{Lu}[\\p{L}’'-]*(?:\\s+\\p{Lu}[\\p{L}’'-]*){0,3})?$")
    private val protectedTail = Regex("(?iu)(?:https?://\\S+|www\\.\\S+|\\S+@\\S+|\\S*[/\\\\]\\S+|\\S+\\.[a-z]{2,8}|[\\p{L}_]+[-_]?[0-9]+|\\+?[0-9][0-9 ()-]{7,})$")
}
