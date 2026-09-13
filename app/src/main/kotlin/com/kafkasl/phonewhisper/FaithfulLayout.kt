package com.kafkasl.phonewhisper

internal enum class LocalLayoutKind(val assetName: String) { LIST("list"), EMAIL("email"), TEXT("text") }

/** The LLM chooses whitespace only. Source words, case, punctuation and order are immutable. */
internal class FaithfulLayout private constructor(
    val source: String,
    val kind: LocalLayoutKind,
    private val words: List<String>,
) {
    // A short acknowledgment needs no generation, even when email is selected.
    val directResult: String? get() = when {
        kind == LocalLayoutKind.EMAIL && words.size <= 5 && !GREETING.containsMatchIn(source) -> source
        kind == LocalLayoutKind.LIST && words.size == 1 -> "• $source"
        else -> null
    }

    fun prompt(prefix: String): String = prefix + "<|im_start|>user\n" +
        source.replace("<|", "< |") + "<|im_end|>\n<|im_start|>assistant\n"

    fun grammar(): String {
        val first = if (kind == LocalLayoutKind.LIST) quote("• ") + " " else ""
        val gap = if (kind == LocalLayoutKind.LIST) "\n• " else "\n\n"
        return "root ::= " + first + words.joinToString(" sep ", transform = ::quote) +
            "\nsep ::= " + quote(" ") + " | " + quote(gap) + "\n"
    }

    /** Apply only verified layout choices to the FULL source, including its ungenerated tail. */
    fun preview(prefix: String): String? {
        if (prefix.isEmpty() || prefix.length > MAX_OUTPUT_CHARS) return null
        val gaps = Array(words.size) { " " }
        val lineBreak = if (kind == LocalLayoutKind.LIST) "\n• " else "\n\n"
        var cursor = 0
        // 1 = consumed; 0 = valid partial literal; -1 = invalid.
        fun literal(value: String): Int {
            val count = minOf(prefix.length - cursor, value.length)
            if (!prefix.regionMatches(cursor, value, 0, count)) return -1
            if (count < value.length) return 0
            cursor += count
            return 1
        }
        fun render(): String = buildString {
            if (kind == LocalLayoutKind.LIST) append("• ")
            for (i in words.indices) {
                if (i > 0) append(gaps[i])
                append(words[i])
            }
        }
        if (kind == LocalLayoutKind.LIST) when (literal("• ")) {
            -1 -> return null
            0 -> return render()
        }
        for (i in words.indices) {
            when (literal(words[i])) {
                -1 -> return null
                0 -> return render()
            }
            if (cursor == prefix.length) return render()
            if (i == words.lastIndex) return null
            if (prefix[cursor] == ' ') {
                cursor++
            } else {
                when (literal(lineBreak)) {
                    -1 -> return null
                    0 -> { gaps[i + 1] = lineBreak; return render() }
                    else -> gaps[i + 1] = lineBreak
                }
            }
        }
        return render().takeIf { cursor == prefix.length }
    }

    /** Validate the complete result, then rebuild it from the source's exact units. */
    fun accept(output: String?): String? {
        if (output == null || output.length > MAX_OUTPUT_CHARS) return null
        val clean = output.trim()
        if (directResult != null && clean == directResult) return directResult
        if (clean.isEmpty()) return null
        val lines = clean.replace("\r\n", "\n").split('\n')
        if (kind == LocalLayoutKind.LIST) {
            val content = lines.filter { it.isNotBlank() }
            if (content.isEmpty() || content.any { !it.startsWith("• ") }) return null
            val counts = ArrayList<Int>()
            val tokens = ArrayList<String>()
            for (line in content) {
                val next = WORDS.findAll(line.drop(2)).map { it.value }.toList()
                if (next.isEmpty()) return null
                counts.add(next.size)
                tokens.addAll(next)
            }
            if (tokens != words) return null
            var offset = 0
            return counts.joinToString("\n") { count ->
                "• " + words.subList(offset, offset + count).joinToString(" ").also { offset += count }
            }
        }
        val matches = WORDS.findAll(clean).toList()
        if (matches.map { it.value } != words) return null
        return buildString {
            for (i in words.indices) {
                if (i > 0) {
                    val gap = clean.substring(matches[i - 1].range.last + 1, matches[i].range.first)
                    append(if ('\n' in gap || '\r' in gap) "\n\n" else " ")
                }
                append(words[i])
            }
        }
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 32768
        private val GREETING = Regex("(?iu)^(bonjour|bonsoir|salut|cher|chère|hello|hi|dear|good morning)\\b")
        private val WORDS = Regex("[^\\s\\p{Z}\\u0085]+")
        fun create(text: String, kind: LocalLayoutKind): FaithfulLayout? {
            if (text.length > 16000 || text.any { it == '\u0000' }) return null
            val words = WORDS.findAll(text).take(513).map { it.value }.toList()
            if (words.isEmpty() || words.size > 512) return null
            return FaithfulLayout(text.trim(), kind, words)
        }
        private fun quote(value: String): String = buildString {
            append('"')
            for (character in value) when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
            }
            append('"')
        }
    }
}
