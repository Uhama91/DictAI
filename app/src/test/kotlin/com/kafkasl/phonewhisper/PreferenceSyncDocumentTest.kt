package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class PreferenceSyncDocumentTest {
    private val phone = "00000000-0000-4000-8000-000000000001"
    private val tablet = "00000000-0000-4000-8000-000000000002"
    private val laptop = "00000000-0000-4000-8000-000000000003"

    @Test fun `independent offline vocabulary additions survive convergence`() {
        val a = SyncDocument().record(mapOf("vocab:a" to "mari => Marie"), phone)
        val b = SyncDocument().record(mapOf("vocab:b" to "cloud => Claude"), tablet)
        assertEquals(mapOf("vocab:a" to "mari => Marie", "vocab:b" to "cloud => Claude"), a.merge(b).liveValues())
        assertEquals(a.merge(b), b.merge(a))
    }

    @Test fun `new local changes advance beyond observed remote clocks`() {
        val initial = SyncDocument().record(mapOf("pref:language" to "fr"), phone)
        val remote = initial.record(mapOf("pref:language" to "en", "pref:theme" to "dark"), tablet)
        val local = initial.merge(remote).record(mapOf("pref:language" to "fr", "pref:theme" to "dark"), phone)
        assertEquals(4L, local.entries.getValue("pref:language").stamp.counter)
        assertEquals("fr", local.merge(remote).liveValues()["pref:language"])
    }

    @Test fun `recording an unchanged snapshot does not manufacture new edits`() {
        val initial = SyncDocument().record(mapOf("pref:language" to "fr"), phone)
        assertEquals(initial, initial.record(mapOf("pref:language" to "fr"), tablet))
    }

    @Test fun `offline deletion stays deleted after merging a stale device`() {
        val initial = SyncDocument().record(mapOf("vocab:a" to "mari => Marie", "pref:language" to "fr"), phone)
        val deleted = initial.record(mapOf("pref:language" to "fr"), tablet)
        assertEquals(mapOf("pref:language" to "fr"), deleted.merge(initial).liveValues())
        assertNull(deleted.entries.getValue("vocab:a").value)
        assertEquals(deleted, SyncDocument.fromJson(deleted.toJson()).merge(initial))
    }

    @Test fun `a deliberately readded deleted entry gets a newer stamp`() {
        val initial = SyncDocument().record(mapOf("vocab:a" to "Marie"), phone)
        val deleted = initial.record(emptyMap(), tablet)
        val restored = deleted.record(mapOf("vocab:a" to "Marie"), phone)
        assertEquals("Marie", restored.merge(deleted).liveValues()["vocab:a"])
        assertEquals(3L, restored.entries.getValue("vocab:a").stamp.counter)
    }

    @Test fun `simultaneous same field edits use actor identity as deterministic tie breaker`() {
        val a = SyncDocument().record(mapOf("pref:language" to "fr"), phone)
        val b = SyncDocument().record(mapOf("pref:language" to "en"), tablet)
        assertEquals("en", a.merge(b).liveValues()["pref:language"])
        assertEquals(a.merge(b), b.merge(a))
    }

    @Test fun `merge is commutative idempotent and associative with edits and tombstones`() {
        val shared = SyncDocument().record(mapOf("vocab:a" to "Marie", "pref:language" to "fr"), phone)
        val states = listOf(
            shared.record(mapOf("vocab:a" to "Marion", "pref:language" to "fr"), phone),
            shared.record(mapOf("pref:language" to "en"), tablet),
            shared.record(mapOf("vocab:a" to "Marie", "pref:language" to "fr", "format:new" to "List"), laptop),
        )
        for (a in states) {
            assertEquals(a, a.merge(a))
            for (b in states) {
                assertEquals(a.merge(b), b.merge(a))
                for (c in states) assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
            }
        }
        assertEquals(mapOf("pref:language" to "en", "format:new" to "List"), states.reduce(SyncDocument::merge).liveValues())
    }

    @Test fun `contradictory values with the exact same stamp are rejected`() {
        val stamp = SyncStamp(1, phone)
        val a = SyncDocument(mapOf("pref:language" to SyncEntry("fr", stamp)))
        val b = SyncDocument(mapOf("pref:language" to SyncEntry("en", stamp)))
        assertThrows(IllegalArgumentException::class.java) { a.merge(b) }
        assertThrows(IllegalArgumentException::class.java) { b.merge(a) }
    }

    @Test fun `json round trip preserves quotes unicode newlines and tombstones`() {
        val doc = SyncDocument(mapOf(
            "vocab:a" to SyncEntry("Élise \\\"😀\n\t", SyncStamp(7, phone)),
            "pref:theme" to SyncEntry(null, SyncStamp(8, tablet)),
        ))
        assertEquals(doc, SyncDocument.fromJson(doc.toJson()))
        assertEquals(doc.toJson(), SyncDocument(doc.entries.entries.reversed().associate { it.toPair() }).toJson())
    }

    @Test fun `json rejects newer schema missing fields and unknown fields`() {
        for (raw in listOf(
            "{\"version\":2,\"entries\":{}}",
            "{\"version\":1}",
            "{\"entries\":{}}",
            "{\"version\":1,\"entries\":{},\"extra\":1}",
            "{\"version\":\"1\",\"entries\":{}}",
            "{\"version\":1.0,\"entries\":{}}",
            envelope("{\"value\":\"fr\",\"stamp\":{\"counter\":1,\"actor\":\"$phone\"},\"extra\":1}"),
            envelope("{\"value\":\"fr\",\"stamp\":{\"counter\":1,\"actor\":\"$phone\",\"extra\":1}}"),
            envelope("{\"stamp\":{\"counter\":1,\"actor\":\"$phone\"}}"),
        )) assertThrows(raw, IllegalArgumentException::class.java) { SyncDocument.fromJson(raw) }
    }

    @Test fun `json rejects malformed duplicate trailing and deeply nested input`() {
        for (raw in listOf(
            "{\"version\":1,\"version\":1,\"entries\":{}}",
            "{\"version\":1,\"entries\":{}} garbage",
            "{'version':1,'entries':{}}",
            "{\"version\":1,\"entries\":{},}",
            "{\"version\":1,\"entries\":{\"pref:a\":{} ,\"pref:a\":{}}}",
            envelope("{\"value\":null,\"value\":\"fr\",\"stamp\":{\"counter\":1,\"actor\":\"$phone\"}}"),
            envelope("{\"value\":\"\\uD800\",\"stamp\":{\"counter\":1,\"actor\":\"$phone\"}}"),
            "{".repeat(2000),
        )) assertThrows(IllegalArgumentException::class.java) { SyncDocument.fromJson(raw) }
    }

    @Test fun `untrusted clocks actors field types and key namespaces are rejected`() {
        for (counter in listOf("0", "-1", "1e2", "9223372036854775807", "9223372036854775808", "\"1\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                SyncDocument.fromJson(envelope("{\"value\":\"fr\",\"stamp\":{\"counter\":$counter,\"actor\":\"$phone\"}}"))
            }
        }
        for (actor in listOf("", "phone", "00000000-0000-4000-8000-00000000000Z")) {
            assertThrows(IllegalArgumentException::class.java) {
                SyncDocument.fromJson(envelope("{\"value\":\"fr\",\"stamp\":{\"counter\":1,\"actor\":\"$actor\"}}"))
            }
        }
        for (key in listOf("openrouter_key", "sync:email", "pref:", "vocab:\n")) {
            assertThrows(IllegalArgumentException::class.java) { SyncDocument().record(mapOf(key to "secret"), phone) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncDocument.fromJson("{\"version\":1,\"entries\":{\"secret:token\":{\"value\":null,\"stamp\":{\"counter\":1,\"actor\":\"$phone\"}}}}")
        }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument().record(mapOf("pref:x" to "x"), "phone") }
        assertThrows(IllegalArgumentException::class.java) {
            SyncDocument.fromJson(envelope("{\"value\":42,\"stamp\":{\"counter\":1,\"actor\":\"$phone\"}}"))
        }
    }

    @Test fun `oversized remote and local documents are rejected before use`() {
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.fromJson(" ".repeat(8_388_609)) }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument().record(mapOf("pref:a" to "a".repeat(8193)), phone) }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument().record(mapOf("pref:" + "x".repeat(252) to "a"), phone) }
        assertThrows(IllegalArgumentException::class.java) {
            SyncDocument.fromJson(envelope("{\"value\":\"${"a".repeat(8193)}\",\"stamp\":{\"counter\":1,\"actor\":\"$phone\"}}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncDocument().record((0..10_000).associate { "vocab:$it" to "x" }, phone)
        }
        val entry = "{\"value\":\"fr\",\"stamp\":{\"counter\":1,\"actor\":\"$phone\"}}"
        val raw = "{\"version\":1,\"entries\":{" + (0..10_000).joinToString(",") { "\"vocab:$it\":$entry" } + "}}"
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.fromJson(raw) }
    }

    @Test fun `clock exhaustion preserves state and does not wrap into old timestamps`() {
        val nearLimit = SyncDocument.fromJson(envelope("{\"value\":\"fr\",\"stamp\":{\"counter\":9223372036854775806,\"actor\":\"$phone\"}}"))
        assertEquals(nearLimit, nearLimit.record(mapOf("pref:language" to "fr"), phone))
        assertThrows(IllegalArgumentException::class.java) { nearLimit.record(mapOf("pref:language" to "en"), phone) }
        assertEquals("fr", nearLimit.liveValues()["pref:language"])
    }

    @Test fun `notes folders and assets converge and retain deletion tombstones`() {
        val initial = SyncDocument().record(mapOf("note:$phone" to "Saved note", "folder:$phone" to "Class"), phone)
        val deleted = initial.record(mapOf("folder:$phone" to "Class"), tablet)
        val attachment = initial.record(mapOf("note:$phone" to "Saved note", "folder:$phone" to "Class", "asset:$tablet" to "hash"), laptop)
        assertEquals(mapOf("folder:$phone" to "Class", "asset:$tablet" to "hash"), deleted.merge(attachment).liveValues())
        assertEquals(deleted.merge(attachment), attachment.merge(deleted))
        assertEquals(deleted, SyncDocument.fromJson(deleted.toJson()).merge(initial))
        for (key in listOf("note:", "folder:", "asset:", "file:/private/path")) {
            assertThrows(IllegalArgumentException::class.java) { SyncDocument().record(mapOf(key to "private"), phone) }
        }
    }

    @Test fun `causal note deletion receipts survive merge beside note tombstones`() {
        val noteKey = "note:$phone"
        val receiptKey = "deletion:$phone"
        val initial = SyncDocument().record(mapOf(noteKey to "Saved note"), phone)
        val stamp = SyncStamp(2, tablet)
        val receipt = "{\"baseStamp\":{\"counter\":1,\"actor\":\"$phone\"},\"_seen\":{\"$phone\":1}}"
        val deleted = SyncDocument(mapOf(noteKey to SyncEntry(null, stamp), receiptKey to SyncEntry(receipt, stamp)))
        val merged = deleted.merge(initial)
        assertNull(merged.entries.getValue(noteKey).value)
        assertEquals(mapOf(receiptKey to receipt), merged.liveValues())
        assertEquals(merged, initial.merge(deleted))
        assertEquals(merged, SyncDocument.fromJson(merged.toJson()))
        assertThrows(IllegalArgumentException::class.java) {
            SyncDocument().record(mapOf(receiptKey to "x".repeat(8193)), phone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncDocument().record(mapOf("deletion:" to receipt), phone)
        }
    }

    @Test fun `long note content round trips without expanding preference or metadata limits`() {
        val content = "Long meeting. ".repeat(5000)
        val document = SyncDocument().record(mapOf("note:$phone" to content), phone)
        assertEquals(content, SyncDocument.fromJson(document.toJson()).liveValues()["note:$phone"])
        for (key in listOf("pref:title", "vocab:name", "format:$phone", "folder:$phone", "asset:$phone", "deletion:$phone")) {
            assertThrows(IllegalArgumentException::class.java) { SyncDocument().record(mapOf(key to content), phone) }
        }
    }

    @Test fun `note size is bounded by UTF8 bytes as well as decoded length`() {
        val atLimit = "é".repeat(524_288)
        val document = SyncDocument().record(mapOf("note:$phone" to atLimit), phone)
        assertEquals(atLimit, SyncDocument.fromJson(document.toJson()).liveValues()["note:$phone"])
        assertThrows(IllegalArgumentException::class.java) { SyncDocument().record(mapOf("note:$phone" to atLimit + "é"), phone) }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument().record(mapOf("note:$phone" to "a".repeat(1_048_577)), phone) }
        val entry = "{\"value\":\"${atLimit}é\",\"stamp\":{\"counter\":1,\"actor\":\"$phone\"}}"
        assertThrows(IllegalArgumentException::class.java) {
            SyncDocument.fromJson("{\"version\":1,\"entries\":{\"note:$phone\":$entry}}")
        }
    }

    @Test fun `multiple large notes fit the document while an oversized history fails without mutation`() {
        val value = "a".repeat(700_000)
        val document = SyncDocument().record(mapOf("note:$phone" to value, "note:$tablet" to value), phone)
        assertEquals(2, SyncDocument.fromJson(document.toJson()).liveValues().size)
        val oversized = (0..8).associate { "note:$it" to "a".repeat(1_048_576) }
        assertThrows(IllegalArgumentException::class.java) { document.record(oversized, tablet) }
        assertEquals(mapOf("note:$phone" to value, "note:$tablet" to value), document.liveValues())
    }

    @Test fun `bare terms and heard phrases normalize unicode case and spacing`() {
        val a = SyncVocabulary.entries(" e\u0301LISE \n Ma  Yotte => Maillot ")
        val b = SyncVocabulary.entries("Élise\nma\u00a0yotte=>Maillot")
        assertEquals(a.keys, b.keys)
        assertEquals(setOf("e\u0301LISE", "Ma  Yotte => Maillot"), a.values.toSet())
        assertTrue(a.keys.all { it.startsWith("vocab:") && it.length <= 256 })
    }

    @Test fun `a changed correction target retains its semantic identity`() {
        assertEquals(SyncVocabulary.entries("mari => Marie").keys, SyncVocabulary.entries("MARI => Marion").keys)
    }

    @Test fun `duplicate heard phrases and repeated lines are preserved`() {
        val raw = "mari => Marie\nMARI => Marion\nmari => Marie\n# Mes noms\n\nClaude"
        val entries = SyncVocabulary.entries(raw)
        assertEquals(5, entries.size)
        assertEquals(2, entries.values.count { it == "mari => Marie" })
        val rendered = SyncVocabulary.render(entries)
        assertEquals(entries, SyncVocabulary.entries(rendered))
        assertEquals(rendered, SyncVocabulary.render(entries.entries.reversed().associate { it.toPair() }))
    }

    @Test fun `very long vocabulary lines fail instead of being silently truncated`() {
        assertThrows(IllegalArgumentException::class.java) { SyncVocabulary.entries("a".repeat(8193)) }
    }

    @Test fun `vocabulary validation binds each live value to its semantic key`() {
        val entries = SyncVocabulary.entries("mari => Marie\nmari => Marion\nClaude\nmari => Marie")
        entries.forEach { (key, value) ->
            assertTrue(SyncVocabulary.isKey(key))
            assertTrue(SyncVocabulary.matchesEntry(key, value))
            assertFalse(SyncVocabulary.matchesEntry(key, "Unrelated"))
            assertFalse(SyncVocabulary.matchesEntry(key, value + "\nOther"))
        }
        val singleton = SyncVocabulary.entries("mari => Marie").keys.single()
        assertTrue(SyncVocabulary.matchesEntry(singleton, "MARI => Marion"))
        assertFalse(SyncVocabulary.matchesEntry(singleton, ""))
        assertFalse(SyncVocabulary.matchesEntry(singleton, " mari => Marie "))
    }

    @Test fun `malformed vocabulary tombstone keys are rejected`() {
        for (key in listOf("vocab:a", "vocab:" + "a".repeat(64) + ":duplicate:x:0",
            "vocab:" + "a".repeat(64) + ":duplicate:" + "b".repeat(64) + ":00",
            "vocab:" + "a".repeat(64) + ":duplicate:" + "b".repeat(64) + ":10000")) {
            assertFalse(SyncVocabulary.isKey(key))
        }
    }

    @Test fun `saving an open editor preserves remote additions and changes to untouched lines`() {
        val result = SyncVocabulary.reconcileEdit(
            "mari => Marie\ncloud => Claude",
            "mari => Marion\ncloud => Claude\nLocal",
            "mari => Marie\ncloud => Cloudine\nRemote",
        )
        assertEquals(setOf("mari => Marion", "cloud => Cloudine", "Local", "Remote"), result.lines().toSet())
    }

    @Test fun `editor deletion does not erase a remotely updated correction`() {
        val result = SyncVocabulary.reconcileEdit("mari => Marie\nClaude", "Claude", "mari => Marion\nClaude")
        assertEquals(setOf("mari => Marion", "Claude"), result.lines().toSet())
    }

    @Test fun `editor does not resurrect a remote deletion of an untouched entry`() {
        assertEquals("New", SyncVocabulary.reconcileEdit("Old", "Old\nNew", ""))
    }

    @Test fun `an explicit local change wins a simultaneous edit of the same correction`() {
        assertEquals("mari => Marion", SyncVocabulary.reconcileEdit("mari => Marie", "mari => Marion", "mari => Maria"))
    }

    private fun envelope(entry: String) = "{\"version\":1,\"entries\":{\"pref:language\":$entry}}"
}
