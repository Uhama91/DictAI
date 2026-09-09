package com.kafkasl.phonewhisper

/** Fast layout for an explicit greeting, body, sign-off and name. No body rewriting. */
internal object SimpleEmailLayout {
    private val greeting = Regex("(?iu)^(bonjour|bonsoir|hello|hi)\\b")
    private val bodyStart = Regex("(?iu)\\b(?:voici|je|nous|vous|merci de|please|i|we|do not|the|this)\\b")
    private val closing = Regex("(?iu)\\b(?:bien cordialement|cordialement|bien à vous|best regards|kind regards|yours sincerely|sincerely)\\b")
    private val name = Regex("(?u)(?:(?i:monsieur|madame|m\\.|mme|mr\\.?|mrs\\.?|ms\\.?|dr\\.?)\\s+)?(?:(?i:le|la)\\s+)?\\p{Lu}[\\p{L}\\p{M}’'-]*(?:\\s+(?:(?i:de|du|van|le|la)\\s+)?\\p{Lu}[\\p{L}\\p{M}’'-]*){0,3}[.]?")

    fun format(source: String): String? {
        val text = source.trim()
        if (text.length > 16000 || text.any { it in "\n\r\"«»“”<>`" }) return null
        val hello = greeting.find(text) ?: return null
        val endings = closing.findAll(text).toList()
        if (endings.size != 1) return null
        val ending = endings.single()
        if (Regex("(?iu)\\b(?:dire|dit|écrire|écrit|mot|formule|say|says|word|write)\\s+$")
                .containsMatchIn(text.substring(0, ending.range.first))) return null
        val signature = text.substring(ending.range.last + 1).trim().trimStart(',').trim()
        if (!name.matches(signature)) return null
        val openingTail = text.substring(hello.range.last + 1, ending.range.first)
        val body = bodyStart.find(openingTail) ?: return null
        val recipient = openingTail.substring(0, body.range.first).trim().trimEnd(',').trim()
        if (recipient.isNotEmpty() && !name.matches(recipient)) return null
        val bodyOffset = hello.range.last + 1 + body.range.first
        val content = text.substring(bodyOffset, ending.range.first).trim()
        if (content.split(Regex("\\s+")).size < 6 || content.none(Char::isLetter)) return null
        // The caller still checks all source words, protected spellings and technical punctuation.
        val salutation = text.substring(0, bodyOffset).trim().trimEnd(',') + ","
        val paragraph = when {
            content.last() in ",;:" -> content.dropLast(1) + "."
            content.last().isLetterOrDigit() -> "$content."
            else -> content
        }
        return "$salutation\n\n$paragraph\n\n${ending.value},\n\n$signature"
    }
}
