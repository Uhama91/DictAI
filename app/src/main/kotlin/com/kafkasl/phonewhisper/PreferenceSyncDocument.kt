package com.kafkasl.phonewhisper

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** A Lamport timestamp; the actor is a stable, randomly generated device UUID. */
internal data class SyncStamp(val counter: Long, val actor: String) {
    init {
        require(counter in 1..SyncDocument.MAX_COUNTER) { "Invalid synchronization clock" }
        require(SyncDocument.isActor(actor)) { "Invalid synchronization actor" }
    }
}
internal data class SyncEntry(val value: String?, val stamp: SyncStamp)

/** Per-key LWW registers. Tombstones are retained so an offline replica cannot undo a deletion. */
internal data class SyncDocument(val entries: Map<String, SyncEntry> = emptyMap()) {
    init {
        require(entries.size <= MAX_ENTRIES) { "Too many synchronized entries" }
        entries.forEach { (key, entry) -> validateKey(key); entry.value?.let { validateEntryValue(key, it) } }
    }

    fun merge(other: SyncDocument): SyncDocument {
        val result = entries.toMutableMap()
        other.entries.forEach { (key, remote) ->
            val local = result[key]
            val order = local?.let { compare(remote.stamp, it.stamp) } ?: 1
            require(order != 0 || local?.value == remote.value) { "Contradictory synchronization timestamp" }
            if (order > 0) result[key] = remote
        }
        return checked(result)
    }

    /** The snapshot must represent current local state, after any received merge has been applied. */
    fun record(snapshot: Map<String, String>, actor: String): SyncDocument {
        require(isActor(actor)) { "Invalid synchronization actor" }
        require(snapshot.size <= MAX_ENTRIES) { "Too many synchronized entries" }
        snapshot.forEach { (key, value) -> validateKey(key); validateEntryValue(key, value) }
        val changed = (entries.keys + snapshot.keys).sorted().filter { entries[it]?.value != snapshot[it] }
        if (changed.isEmpty()) return this
        var counter = entries.values.maxOfOrNull { it.stamp.counter } ?: 0
        require(changed.size.toLong() <= MAX_COUNTER - counter) { "Synchronization clock exhausted" }
        val result = entries.toMutableMap()
        changed.forEach { key -> result[key] = SyncEntry(snapshot[key], SyncStamp(++counter, actor)) }
        return checked(result)
    }

    fun liveValues(): Map<String, String> = entries.mapNotNull { (key, entry) -> entry.value?.let { key to it } }.toMap()

    fun toJson(): String {
        val json = StringBuilder("{\"version\":1,\"entries\":{")
        var bytes = json.length + 2
        entries.toSortedMap().entries.forEachIndexed { index, (key, entry) ->
            val encoded = "${StrictJson.quote(key)}:{\"value\":${entry.value?.let(StrictJson::quote) ?: "null"}," +
                "\"stamp\":{\"counter\":${entry.stamp.counter},\"actor\":${StrictJson.quote(entry.stamp.actor)}}}"
            bytes += encoded.toByteArray(Charsets.UTF_8).size + if (index > 0) 1 else 0
            require(bytes <= MAX_BYTES) { "Synchronization document too large" }
            if (index > 0) json.append(',')
            json.append(encoded)
        }
        return json.append("}}").toString()
    }

    private fun checked(values: Map<String, SyncEntry>): SyncDocument = SyncDocument(values.toSortedMap()).also { it.toJson() }

    companion object {
        const val MAX_BYTES = 8_388_608
        const val MAX_ENTRIES = 10_000
        const val MAX_VALUE_LENGTH = 8_192
        const val MAX_NOTE_VALUE_BYTES = 1_048_576
        const val MAX_COUNTER = Long.MAX_VALUE - 1
        private val actorPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        internal fun isActor(actor: String): Boolean = actorPattern.matches(actor)

        private fun compare(a: SyncStamp, b: SyncStamp): Int =
            a.counter.compareTo(b.counter).takeIf { it != 0 } ?: a.actor.compareTo(b.actor)

        internal fun validateKey(key: String) {
            require(key.length <= 256 && key.none { it.code < 0x20 } && validUnicode(key) &&
                listOf("pref:", "vocab:", "format:", "note:", "folder:", "asset:", "deletion:").any { key.startsWith(it) && key.length > it.length }) {
                "Invalid synchronized key"
            }
        }

        internal fun validateValue(value: String) {
            require(value.length <= MAX_VALUE_LENGTH && validUnicode(value)) { "Invalid synchronized value" }
        }

        internal fun validateEntryValue(key: String, value: String) {
            if (!key.startsWith("note:")) return validateValue(value)
            require(value.length <= MAX_NOTE_VALUE_BYTES && validUnicode(value) &&
                value.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_VALUE_BYTES) { "Synchronized note too large or invalid" }
        }

        internal fun validateDecodedString(value: String) {
            require(value.length <= MAX_NOTE_VALUE_BYTES && validUnicode(value)) { "Invalid synchronization string" }
        }

        private fun validUnicode(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char.isHighSurrogate()) {
                    if (index == value.length || !value[index++].isLowSurrogate()) return false
                } else if (char.isLowSurrogate()) return false
            }
            return true
        }

        fun fromJson(raw: String): SyncDocument {
            require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Synchronization document too large" }
            val envelope = SyncJsonReader(raw).read()
            require(envelope.keys == setOf("version", "entries") && envelope["version"] == 1L) { "Unsupported synchronization schema" }
            val remoteEntries = envelope["entries"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid synchronization entries")
            require(remoteEntries.size <= MAX_ENTRIES) { "Too many synchronized entries" }
            val entries = remoteEntries.entries.associate { (key, rawEntry) ->
                val name = key as? String ?: throw IllegalArgumentException("Invalid synchronized key")
                val entry = rawEntry as? Map<*, *> ?: throw IllegalArgumentException("Invalid synchronization entry")
                require(entry.keys == setOf("value", "stamp") && (entry["value"] == null || entry["value"] is String)) { "Invalid synchronization entry" }
                val stamp = entry["stamp"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid synchronization stamp")
                require(stamp.keys == setOf("counter", "actor")) { "Invalid synchronization stamp" }
                val counter = stamp["counter"] as? Long ?: throw IllegalArgumentException("Invalid synchronization clock")
                val actor = stamp["actor"] as? String ?: throw IllegalArgumentException("Invalid synchronization actor")
                name to SyncEntry(entry["value"] as String?, SyncStamp(counter, actor))
            }
            return SyncDocument(entries.toSortedMap())
        }
    }
}

/** Only the JSON types and nesting needed for the version 1 envelope are accepted. */
private class SyncJsonReader(private val input: String) {
    private var index = 0
    fun read(): Map<String, Any?> {
        val result = objectValue(0)
        space()
        require(index == input.length) { "Trailing synchronization data" }
        return result
    }

    private fun objectValue(depth: Int): Map<String, Any?> {
        require(depth <= 3) { "Synchronization document nested too deeply" }
        expect('{')
        val result = linkedMapOf<String, Any?>()
        if (take('}')) return result
        do {
            val key = string(256)
            require(!result.containsKey(key) && result.size < SyncDocument.MAX_ENTRIES) { "Duplicate or excessive synchronization fields" }
            expect(':')
            space()
            val value: Any? = when (input.getOrNull(index)) {
                '{' -> objectValue(depth + 1)
                '"' -> string()
                'n' -> { require(input.startsWith("null", index)) { "Invalid synchronization JSON" }; index += 4; null }
                in '0'..'9' -> number()
                else -> throw IllegalArgumentException("Invalid synchronization JSON")
            }
            result[key] = value
            if (take('}')) return result
            expect(',')
        } while (true)
    }

    private fun number(): Long {
        val start = index
        if (input[index] == '0') index++ else while (input.getOrNull(index) in '0'..'9') index++
        return input.substring(start, index).toLongOrNull() ?: throw IllegalArgumentException("Invalid synchronization number")
    }

    private fun string(limit: Int = SyncDocument.MAX_NOTE_VALUE_BYTES): String {
        expect('"')
        val value = StringBuilder()
        while (index < input.length) {
            val char = input[index++]
            if (char == '"') return value.toString().also(SyncDocument::validateDecodedString)
            require(char.code >= 0x20) { "Unescaped synchronization control character" }
            if (char != '\\') value.append(char) else {
                when (val escaped = input.getOrNull(index++)) {
                    '"', '\\', '/' -> value.append(escaped)
                    'b' -> value.append('\b')
                    'f' -> value.append('\u000c')
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'u' -> {
                        require(index + 4 <= input.length) { "Invalid synchronization unicode escape" }
                        val hex = input.substring(index, index + 4)
                        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid synchronization unicode escape" }
                        value.append(hex.toInt(16).toChar())
                        index += 4
                    }
                    else -> throw IllegalArgumentException("Invalid synchronization escape")
                }
            }
            require(value.length <= limit) { "Synchronization string too large" }
        }
        throw IllegalArgumentException("Unclosed synchronization string")
    }

    private fun expect(char: Char) { require(take(char)) { "Invalid synchronization JSON" } }
    private fun take(char: Char): Boolean { space(); return if (input.getOrNull(index) == char) { index++; true } else false }
    private fun space() {
        while (when (input.getOrNull(index)) { ' ', '\t', '\n', '\r' -> true; else -> false }) index++
    }
}

internal object SyncVocabulary {
    private val keyPattern = Regex("vocab:([0-9a-f]{64})(?::duplicate:([0-9a-f]{64}):(0|[1-9][0-9]{0,3}))?")
    fun isKey(key: String): Boolean = keyPattern.matches(key)
    fun matchesEntry(key: String, value: String): Boolean {
        val match = keyPattern.matchEntire(key) ?: return false
        if (value.isBlank() || value != value.trim() || value.any { it == '\n' || it == '\r' } ||
            runCatching { SyncDocument.validateValue(value) }.isFailure) return false
        return match.groupValues[1] == hash(identity(value)) &&
            (match.groupValues[2].isEmpty() || match.groupValues[2] == hash(value))
    }
    private val spacing = Regex("[\\s\\u00a0]+")
    private fun normalized(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFC).replace(spacing, " ").lowercase(Locale.ROOT)

    private fun identity(line: String): String = if (line.contains("=>")) "rule:" + normalized(line.substringBefore("=>")) else "term:" + normalized(line)
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Ambiguous repeated sources retain all lines, including identical repetitions. */
    fun entries(raw: String): Map<String, String> {
        require(raw.length <= SyncDocument.MAX_BYTES) { "Vocabulary too large" }
        val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size <= SyncDocument.MAX_ENTRIES) { "Too many vocabulary entries" }
        lines.forEach(SyncDocument::validateValue)
        val result = linkedMapOf<String, String>()
        lines.groupBy(::identity).forEach { (source, group) ->
            val semanticKey = "vocab:" + hash(source)
            if (group.size == 1) result[semanticKey] = group.single() else {
                val repetitions = mutableMapOf<String, Int>()
                group.forEach { line ->
                    val occurrence = repetitions.getOrDefault(line, 0)
                    repetitions[line] = occurrence + 1
                    result["$semanticKey:duplicate:${hash(line)}:$occurrence"] = line
                }
            }
        }
        return result.toSortedMap()
    }

    fun render(values: Map<String, String>): String {
        val result = values.filterKeys { it.startsWith("vocab:") }.toSortedMap().values.joinToString("\n")
        require(result.length <= SyncDocument.MAX_BYTES) { "Vocabulary too large" }
        return result
    }

    /** Preserve received changes to untouched lines while committing the user's actual edits. */
    fun reconcileEdit(baseRaw: String, editedRaw: String, currentRaw: String): String {
        val base = entries(baseRaw)
        val edited = entries(editedRaw)
        val current = entries(currentRaw).toMutableMap()
        base.forEach { (key, value) -> if (key !in edited && current[key] == value) current.remove(key) }
        edited.forEach { (key, value) -> if (base[key] != value) current[key] = value }
        return render(current)
    }
}
