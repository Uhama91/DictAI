package com.kafkasl.phonewhisper

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class SyncedNoteAsset(val id: String, val sha256: String, val size: Long)

/** Notes are atomic registers: text, marker order and image metadata never merge independently. */
internal class AccountNotesSyncStore(context: Context) {
    private val app = context.applicationContext
    private val notes = app.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
    private val folders = app.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE)
    private val draft = app.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE)

    fun snapshot(before: SyncDocument): Map<String, String> = buildMap {
        // A causal deletion receipt must live as long as its note tombstone.
        putAll(before.liveValues().filterKeys { it.startsWith("deletion:") })
        readFolders().forEach { (id, folder) -> put("folder:$id", encodeFolder(folder)) }
        readNotes().forEach { (id, note) ->
            val key = "note:$id"
            val previous = before.entries[key]
            val previousWire = previous?.value?.let { decodeWire(id, it) }
            val value = if (previousWire?.note == note) previous!!.value!! else {
                val seen = previousWire?.seen.orEmpty().toMutableMap()
                previous?.stamp?.let { acknowledge(seen, it) }
                encodeWire(NoteWire(note, seen))
            }
            put(key, value)
            note.images.forEach { image -> put("asset:${image.id}", encodeAsset(fingerprint(imageFile(app, image.id), image.id))) }
        }
    }

    /** Fingerprinting only; does not modify collections, drafts, or the account journal. */
    fun prepareAssets() {
        readNotes().values.flatMap { it.images }.distinctBy { it.id }.forEach { fingerprint(imageFile(app, it.id), it.id) }
    }

    fun recordDeletions(before: SyncDocument, recorded: SyncDocument): SyncDocument {
        val entries = recorded.entries.toMutableMap()
        before.entries.filterKeys { it.startsWith("note:") }.forEach { (key, previous) ->
            val removed = recorded.entries[key]
            if (previous.value != null && removed != null && removed.value == null && removed.stamp != previous.stamp) {
                val wire = decodeWire(key.removePrefix("note:"), previous.value)
                val seen = wire.seen.toMutableMap().also { acknowledge(it, previous.stamp) }
                // Mirror the note event exactly. Unrelated preference clocks cannot make the
                // winning receipt belong to a different deletion than the winning tombstone.
                entries["deletion:${wire.note.id}"] = SyncEntry(encodeDeletion(NoteDeletion(previous.stamp, seen)), removed.stamp)
            }
        }
        return SyncDocument(entries.toSortedMap()).also { it.toJson() }
    }

    fun preserveRecoveryChanges(before: SyncDocument, pending: SyncDocument, actor: String): SyncDocument {
        validate(pending)
        val source = readNotes()
        val values = pending.liveValues().toMutableMap()
        val knownFolders = (folders.all.keys - pending.entries.keys.filter { it.startsWith("folder:") }.map { it.removePrefix("folder:") }.toSet()) +
            values.keys.filter { it.startsWith("folder:") }.map { it.removePrefix("folder:") }
        val deletions = mutableMapOf<String, NoteDeletion>()
        val active = activeNoteId()

        fun retain(note: TranscriptNote, wire: NoteWire, copy: Boolean) {
            val retained = if (copy) conflictCopy(wire) else wire
            val key = "note:${retained.note.id}"
            if (copy && key in pending.entries) return
            values[key] = encodeWire(retained)
            note.images.forEach { image ->
                val file = imageFile(app, image.id)
                val previous = before.entries["asset:${image.id}"]?.value ?: pending.entries["asset:${image.id}"]?.value
                values["asset:${image.id}"] = if (!file.isFile && previous != null) previous
                    else encodeAsset(fingerprint(file, image.id))
            }
        }

        val ids = (before.entries.keys + pending.entries.keys).filter { it.startsWith("note:") }.map { it.removePrefix("note:") }.toSet() + source.keys
        ids.forEach { id ->
            val oldEntry = before.entries["note:$id"]
            val old = oldEntry?.value?.let { decodeWire(id, it) }
            val target = pending.entries["note:$id"]?.value?.let { decodeWire(id, it) }
            val expected = target?.note?.let { it.copy(folderId = it.folderId?.takeIf { folder -> folder in knownFolders }) }
            val actual = source[id]
            if (actual != null && actual != expected && (actual != old?.note || id == active)) {
                val seen = old?.seen.orEmpty().toMutableMap().also { history -> oldEntry?.stamp?.let { acknowledge(history, it) } }
                val ownWire = NoteWire(actual, seen)
                retain(actual, ownWire, copy = "note:$id" in pending.entries)
            } else if (actual == null && old != null && expected != null) {
                // A local deletion after the failed import also remains a deletion. The unseen
                // incoming body is preserved separately instead of being resurrected as original.
                retain(target!!.note, target, copy = true)
                values.remove("note:$id")
                val seen = old.seen.toMutableMap().also { acknowledge(it, oldEntry.stamp) }
                deletions[id] = NoteDeletion(oldEntry.stamp, seen)
            }
        }
        val recorded = pending.record(values, actor)
        if (deletions.isEmpty()) return recorded
        val entries = recorded.entries.toMutableMap()
        deletions.forEach { (id, receipt) -> entries["deletion:$id"] = SyncEntry(encodeDeletion(receipt), entries.getValue("note:$id").stamp) }
        return SyncDocument(entries.toSortedMap()).also { it.toJson() }
    }

    fun ownsKey(key: String): Boolean = listOf("note:", "folder:", "asset:", "deletion:")
        .any { key.startsWith(it) && SyncDocument.isActor(key.removePrefix(it)) }

    /** Pure metadata validation. Original files can be fetched only after this succeeds. */
    fun validate(document: SyncDocument) {
        document.entries.forEach { (key, entry) ->
            if (key.startsWith("note:") || key.startsWith("folder:") || key.startsWith("asset:") || key.startsWith("deletion:")) {
                require(ownsKey(key)) { "Invalid synchronized content identity" }
                val id = key.substringAfter(':')
                entry.value?.let { value ->
                    when {
                        key.startsWith("note:") -> {
                            val wire = decodeWire(id, value)
                            require(wire.seen.values.all { it < entry.stamp.counter }) { "Invalid note causal history" }
                        }
                        key.startsWith("folder:") -> decodeFolder(id, value, canonical = true)
                        key.startsWith("asset:") -> decodeAsset(id, value)
                        else -> {
                            val receipt = decodeDeletion(value)
                            require(receipt.seen.values.all { it < entry.stamp.counter }) { "Invalid deletion causal history" }
                            val note = document.entries["note:$id"]
                            require(note != null && compare(entry.stamp, note.stamp) <= 0 &&
                                (note.value != null || entry.stamp == note.stamp)) { "Deletion receipt does not match its note" }
                        }
                    }
                }
                if (key.startsWith("deletion:")) require(entry.value != null) { "Deletion receipt cannot be erased" }
                if (key.startsWith("note:") && entry.value == null) {
                    val receipt = document.entries["deletion:$id"]
                    require(receipt?.value != null && receipt.stamp == entry.stamp) { "Deleted note has no causal receipt" }
                }
            }
        }
        // An image may be shared by a deterministic conflict copy; its immutable descriptor is unique.
        referencedImageIds(document).forEach { id ->
            val value = document.entries["asset:$id"]?.value
            require(value != null) { "Synchronized note is missing an original-image descriptor" }
            decodeAsset(id, value)
        }
    }

    fun preserveConflicts(local: SyncDocument, remote: List<SyncDocument>, merged: SyncDocument, actor: String): SyncDocument {
        val values = merged.liveValues().toMutableMap()
        val documents = listOf(local) + remote
        val activeId = activeNoteId()
        val forcedDeletions = mutableMapOf<String, NoteDeletion>()

        fun preserve(id: String, entry: SyncEntry) {
            val raw = entry.value ?: return
            val wire = decodeWire(id, raw)
            val copy = conflictCopy(wire, entry.stamp)
            val copyId = copy.note.id
            // A user deletion of a conflict copy must survive seeing the same conflict again.
            if ("note:$copyId" in merged.entries || "note:$copyId" in values) return
            values["note:$copyId"] = encodeWire(copy)
        }

        merged.entries.filterKeys { it.startsWith("note:") }.forEach { (key, winner) ->
            val id = key.removePrefix("note:")
            val own = local.entries[key]
            val candidates = documents.mapNotNull { it.entries[key] }.distinct()
            val ownWire = own?.value?.let { decodeWire(id, it) }

            val winningWire = winner.value?.let { decodeWire(id, it) }
            val deletion = candidates.filter { it.value == null }.maxWithOrNull { first, second -> compare(first.stamp, second.stamp) }
            val concurrentDeletion = if (winner.value != null && deletion != null && !dominates(winner, deletion)) {
                decodeDeletion(merged.entries["deletion:$id"]?.value ?: throw IllegalArgumentException("Deleted note has no causal receipt"))
            } else null

            if (concurrentDeletion != null) {
                // Deletion keeps the original absent even when unrelated clocks made the offline
                // edit numerically newer. The actual edited bytes remain in a visible copy.
                values.remove(key)
                forcedDeletions[id] = concurrentDeletion
                candidates.filter { it.value != null }.forEach { candidate ->
                    if (id == activeId && candidate == own || (concurrentDeletion.seen[candidate.stamp.actor] ?: 0) < candidate.stamp.counter) preserve(id, candidate)
                }
            } else {
                if (id == activeId && ownWire != null && (winningWire == null || winningWire.note != ownWire.note)) {
                    // Detach the editor to its saved version; the original register can then
                    // converge while both devices keep their live text exactly as it was.
                    preserve(id, own)
                }
                if (winner.value != null) {
                    val winnerWire = decodeWire(id, winner.value)
                    candidates.filter { it.value != null && it != winner }.forEach { candidate ->
                        val candidateWire = decodeWire(id, candidate.value!!)
                        if (winnerWire.note != candidateWire.note && !dominates(winner, candidate) && !dominates(candidate, winner)) {
                            preserve(id, candidate)
                        }
                    }
                } else {
                    val receipt = decodeDeletion(merged.entries["deletion:$id"]?.value
                        ?: throw IllegalArgumentException("Deleted note has no causal receipt"))
                    candidates.filter { it.value != null }.forEach { candidate ->
                        if ((receipt.seen[candidate.stamp.actor] ?: 0) < candidate.stamp.counter) preserve(id, candidate)
                    }
                }
            }
        }

        // Generic per-key deletion may orphan an image kept by a conflict version. Recover only
        // its immutable descriptor, never resurrect a deleted note or folder.
        values.filterKeys { it.startsWith("note:") }.forEach { (key, raw) ->
            decodeWire(key.removePrefix("note:"), raw).note.images.forEach { image ->
                val assetKey = "asset:${image.id}"
                if (values[assetKey] == null) {
                    val descriptor = documents.mapNotNull { it.entries[assetKey]?.takeIf { entry -> entry.value != null } }
                        .maxWithOrNull { first, second -> compare(first.stamp, second.stamp) }
                    require(descriptor != null) { "Missing original-image descriptor for preserved note" }
                    values[assetKey] = descriptor.value!!
                }
            }
        }
        val recorded = merged.record(values, actor)
        if (forcedDeletions.isEmpty()) return recorded
        val entries = recorded.entries.toMutableMap()
        forcedDeletions.forEach { (id, receipt) ->
            entries["deletion:$id"] = SyncEntry(encodeDeletion(receipt), entries.getValue("note:$id").stamp)
        }
        return SyncDocument(entries.toSortedMap()).also { it.toJson() }
    }

    /** Called under the parent's durable pending record; retries do not rewrite unchanged records. */
    fun apply(document: SyncDocument) {
        validate(document)
        val incomingFolders = document.liveValues().filterKeys { it.startsWith("folder:") }
            .mapKeys { it.key.removePrefix("folder:") }.mapValues { (id, value) -> decodeFolder(id, value, canonical = true) }
        val incomingNotes = document.liveValues().filterKeys { it.startsWith("note:") }
            .mapKeys { it.key.removePrefix("note:") }.mapValues { (id, value) -> decodeWire(id, value).note }
        val knownFolders = (folders.all.keys - document.entries.keys.filter { it.startsWith("folder:") }.map { it.removePrefix("folder:") }.toSet()) + incomingFolders.keys
        fun normalized(note: TranscriptNote) = note.copy(folderId = note.folderId?.takeIf { it in knownFolders })
        assetDescriptors(document).forEach { asset ->
            val file = imageFile(app, asset.id)
            require(file.isFile && file.length() == asset.size) { "Synchronized original image is unavailable" }
        }

        // Repoint before replacing or removing the original note. The pending journal recovers a crash
        // between this draft receipt and publishing the conflict copy without losing the draft.
        val active = activeNoteId()
        if (active != null && document.entries.containsKey("note:$active")) {
            val oldRaw = notes.all[active] as? String
            if (oldRaw != null) {
                val old = decodeSourceNote(active, oldRaw)
                if (incomingNotes[active]?.let(::normalized) != old) {
                    val replacement = conflictId(active, old)
                    require(incomingNotes.containsKey(replacement)) { "Open note was not preserved before replacement" }
                    check(draft.edit().putString("note_id", replacement).commit()) { "Protected note draft was not saved" }
                }
            }
        }

        val folderEditor = folders.edit()
        document.entries.keys.filter { it.startsWith("folder:") }.forEach { key ->
            val id = key.removePrefix("folder:")
            val wanted = incomingFolders[id]
            if (wanted == null) {
                if (folders.contains(id)) folderEditor.remove(id)
            } else if (folders.all[id]?.let { decodeFolder(id, it as String, canonical = false) } != wanted) {
                folderEditor.putString(id, encodeFolder(wanted))
            }
        }
        if (document.entries.keys.any { it.startsWith("folder:") }) {
            check(folderEditor.commit()) { "Synchronized folders were not saved" }
        }
        val noteEditor = notes.edit()
        document.entries.keys.filter { it.startsWith("note:") }.forEach { key ->
            val id = key.removePrefix("note:")
            val wanted = incomingNotes[id]?.let(::normalized)
            if (wanted == null) {
                // Files remain immutable and may belong to a preserved version or an offline device.
                if (notes.contains(id)) noteEditor.remove(id)
            } else if (notes.all[id]?.let { decodeSourceNote(id, it as String) } != wanted) {
                noteEditor.putString(id, encodeSource(wanted))
            }
        }
        if (document.entries.keys.any { it.startsWith("note:") }) {
            check(noteEditor.commit()) { "Synchronized notes were not saved" }
        }
    }

    private fun activeNoteId(): String? = draft.getString("note_id", null)?.takeIf {
        SyncDocument.isActor(it) && DictationPurpose.restore(draft.getString("purpose", null), it) == DictationPurpose.NOTE
    }

    private fun readNotes(): Map<String, TranscriptNote> = notes.all.mapValues { (id, raw) ->
        require(SyncDocument.isActor(id) && raw is String) { "Invalid local note record" }
        decodeSourceNote(id, raw)
    }

    private fun readFolders(): Map<String, NoteFolder> = folders.all.mapValues { (id, raw) ->
        require(SyncDocument.isActor(id) && raw is String) { "Invalid local folder record" }
        decodeFolder(id, raw, canonical = false)
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private const val MAX_SEEN_ACTORS = 100
        private val hashes = LinkedHashMap<String, CachedHash>(256, 0.75f, true)
        private data class CachedHash(val length: Long, val modified: Long, val asset: SyncedNoteAsset)
        private data class NoteWire(val note: TranscriptNote, val seen: Map<String, Long>)
        private data class NoteDeletion(val base: SyncStamp, val seen: Map<String, Long>)

        fun assets(document: SyncDocument): Map<String, String> = assetDescriptors(document).associate { it.id to it.sha256 }
        fun assetDescriptors(document: SyncDocument): List<SyncedNoteAsset> = referencedImageIds(document).sorted().map { id ->
            decodeAsset(id, document.entries["asset:$id"]?.value ?: throw IllegalArgumentException("Missing original-image descriptor"))
        }
        fun imageFile(context: Context, imageId: String): File = NoteImageStore(context).file(imageId)

        private fun referencedImageIds(document: SyncDocument): Set<String> = document.liveValues()
            .filterKeys { it.startsWith("note:") }.flatMap { (key, raw) -> decodeWire(key.removePrefix("note:"), raw).note.images }
            .map { it.id }.toSet()

        private fun fingerprint(file: File, id: String): SyncedNoteAsset {
            require(file.isFile && file.length() in 1..MAX_IMAGE_BYTES) { "Original note image is missing or too large" }
            val length = file.length()
            val modified = file.lastModified()
            synchronized(hashes) {
                hashes[file.absolutePath]?.takeIf { it.length == length && it.modified == modified }?.let { return it.asset }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            require(file.length() == length && file.lastModified() == modified) { "Original image changed during synchronization" }
            val asset = SyncedNoteAsset(id, hex(digest.digest()), length)
            synchronized(hashes) {
                hashes[file.absolutePath] = CachedHash(length, modified, asset)
                // Preparing the supported collection on a worker must keep every fingerprint
                // until the UI snapshot; a small LRU would miss and rehash the whole collection.
                while (hashes.size > SyncDocument.MAX_ENTRIES) hashes.remove(hashes.keys.first())
            }
            return asset
        }

        private fun dominates(first: SyncEntry, second: SyncEntry): Boolean {
            if (first.stamp.actor == second.stamp.actor && first.stamp.counter >= second.stamp.counter) return true
            val value = first.value ?: return false
            BoundedSyncJson.check(value)
            val wire = decodeWire(JSONObject(value).getString("id"), value)
            return (wire.seen[second.stamp.actor] ?: 0) >= second.stamp.counter
        }

        private fun compare(first: SyncStamp, second: SyncStamp): Int =
            first.counter.compareTo(second.counter).takeIf { it != 0 } ?: first.actor.compareTo(second.actor)

        private fun acknowledge(seen: MutableMap<String, Long>, stamp: SyncStamp) {
            seen[stamp.actor] = maxOf(seen[stamp.actor] ?: 0, stamp.counter)
            require(seen.size <= MAX_SEEN_ACTORS) { "Too many devices in note causal history" }
        }

        private fun conflictId(id: String, note: TranscriptNote): String = UUID.nameUUIDFromBytes(
            "$id\n${hash(encodeNotePayload(note))}".toByteArray(Charsets.UTF_8)).toString()

        private fun conflictCopy(wire: NoteWire, stamp: SyncStamp? = null): NoteWire {
            val seen = wire.seen.toMutableMap().also { history -> stamp?.let { acknowledge(history, it) } }
            val suffix = " (version conservée)"
            val copy = wire.note.copy(id = conflictId(wire.note.id, wire.note),
                title = wire.note.title.take(TranscriptNotes.MAX_NAME_LENGTH - suffix.length).trimEnd() + suffix,
                renamed = true)
            return NoteWire(copy, seen)
        }

        private fun encodeDeletion(receipt: NoteDeletion): String =
            "{\"baseStamp\":{\"counter\":${receipt.base.counter},\"actor\":${StrictJson.quote(receipt.base.actor)}},\"_seen\":${encodeSeen(receipt.seen)}}"

        private fun decodeDeletion(raw: String): NoteDeletion = parse(raw) { item ->
            require(item.keys().asSequence().toSet() == setOf("baseStamp", "_seen")) { "Invalid note deletion fields" }
            val base = item.get("baseStamp") as? JSONObject ?: throw IllegalArgumentException("Invalid deletion base stamp")
            require(base.keys().asSequence().toSet() == setOf("counter", "actor")) { "Invalid deletion base stamp" }
            val stamp = SyncStamp(integer(base, "counter"), string(base, "actor"))
            val seen = decodeSeen(item)
            require((seen[stamp.actor] ?: 0) >= stamp.counter) { "Deletion receipt omits its base note" }
            NoteDeletion(stamp, seen).also { require(encodeDeletion(it) == raw) { "Invalid deletion receipt encoding" } }
        }

        private fun encodeAsset(asset: SyncedNoteAsset): String =
            "{\"sha256\":${StrictJson.quote(asset.sha256)},\"size\":${asset.size}}"

        private fun decodeAsset(id: String, raw: String): SyncedNoteAsset = parse(raw) { item ->
            require(SyncDocument.isActor(id) && item.keys().asSequence().toSet() == setOf("sha256", "size")) { "Invalid original-image descriptor" }
            val hash = string(item, "sha256")
            val size = integer(item, "size")
            require(Regex("[0-9a-f]{64}").matches(hash) && size in 1..MAX_IMAGE_BYTES) { "Invalid original-image descriptor" }
            SyncedNoteAsset(id, hash, size).also { require(encodeAsset(it) == raw) { "Invalid original-image descriptor encoding" } }
        }

        private fun encodeFolder(folder: NoteFolder): String =
            "{\"id\":${StrictJson.quote(folder.id)},\"name\":${StrictJson.quote(folder.name)},\"created\":${folder.createdAt},\"updated\":${folder.updatedAt}}"

        private fun decodeFolder(id: String, raw: String, canonical: Boolean): NoteFolder = parse(raw) { item ->
            SyncDocument.validateValue(raw)
            val fields = item.keys().asSequence().toSet()
            require(fields.containsAll(setOf("id", "name", "created")) && fields.all { it in setOf("id", "name", "created", "updated") }) { "Invalid folder fields" }
            val folder = NoteFolder(string(item, "id"), string(item, "name"), integer(item, "created"),
                if (item.has("updated")) integer(item, "updated") else integer(item, "created"))
            require(SyncDocument.isActor(id) && folder.id == id && folder.name.isNotBlank() && folder.name.length <= TranscriptNotes.MAX_NAME_LENGTH &&
                folder.createdAt >= 0 && folder.updatedAt >= 0) { "Invalid folder content" }
            if (canonical) require(encodeFolder(folder) == raw) { "Invalid synchronized folder encoding" }
            folder
        }

        private fun decodeSourceNote(id: String, raw: String): TranscriptNote = parse(raw) { item ->
            SyncDocument.validateEntryValue("note:$id", raw)
            decodeNote(id, item, wire = false)
        }

        private fun decodeWire(id: String, raw: String): NoteWire = parse(raw) { item ->
            SyncDocument.validateEntryValue("note:$id", raw)
            val note = decodeNote(id, item, wire = true)
            val seen = decodeSeen(item)
            NoteWire(note, seen).also { require(encodeWire(it) == raw) { "Invalid synchronized note encoding" } }
        }

        private fun decodeNote(id: String, item: JSONObject, wire: Boolean): TranscriptNote {
            val required = setOf("id", "title", "text", "updated")
            val optional = setOf("renamed", "images", "folderId", "folderChoicePrompted")
            val keys = item.keys().asSequence().toSet()
            require(keys.containsAll(required) && keys.all { it in required + optional + if (wire) setOf("_seen") else emptySet() }) { "Invalid note fields" }
            if (wire) require(keys == required + optional + "_seen") { "Incomplete synchronized note fields" }
            val images = if (item.has("images")) {
                val array = item.get("images") as? JSONArray ?: throw IllegalArgumentException("Invalid note images")
                require(array.length() <= NoteImage.MAX_IMAGES) { "Too many note images" }
                (0 until array.length()).map { index ->
                    val image = array.getJSONObject(index)
                    require(image.keys().asSequence().toSet() == setOf("id", "number", "kind", "capturedAt", "width", "height")) { "Invalid note image fields" }
                    NoteImage(string(image, "id"), int(image, "number"), NoteImageKind.valueOf(string(image, "kind")),
                        integer(image, "capturedAt"), int(image, "width"), int(image, "height")).also {
                        require(it.capturedAt >= 0 && it.width <= 2048 && it.height <= 2048) { "Invalid note image metadata" }
                    }
                }
            } else emptyList()
            val folderId = if (!item.has("folderId") || item.isNull("folderId")) null else string(item, "folderId")
            val note = TranscriptNote(string(item, "id"), string(item, "title"), string(item, "text"), integer(item, "updated"),
                if (item.has("renamed")) boolean(item, "renamed") else false, images, folderId,
                if (item.has("folderChoicePrompted")) boolean(item, "folderChoicePrompted") else true)
            require(SyncDocument.isActor(id) && note.id == id && note.title.isNotBlank() && note.title.length <= TranscriptNotes.MAX_NAME_LENGTH &&
                note.updatedAt >= 0 && (folderId == null || SyncDocument.isActor(folderId)) &&
                images.distinctBy { it.id }.size == images.size && images.distinctBy { it.number }.size == images.size) { "Invalid note content" }
            return note
        }

        private fun encodeSource(note: TranscriptNote): String = JSONObject().put("id", note.id).put("title", note.title).put("text", note.text)
            .put("updated", note.updatedAt).put("renamed", note.renamed).put("images", NoteImageJson.writeList(note.images))
            .apply { note.folderId?.let { put("folderId", it) }; put("folderChoicePrompted", note.folderChoicePrompted) }.toString()

        private fun encodeNotePayload(note: TranscriptNote): String = buildString {
            append("{\"id\":${StrictJson.quote(note.id)},\"title\":${StrictJson.quote(note.title)},\"text\":${StrictJson.quote(note.text)},")
            append("\"updated\":${note.updatedAt},\"renamed\":${note.renamed},\"images\":[")
            note.images.forEachIndexed { index, image ->
                if (index > 0) append(',')
                append("{\"id\":${StrictJson.quote(image.id)},\"number\":${image.number},\"kind\":${StrictJson.quote(image.kind.name)},")
                append("\"capturedAt\":${image.capturedAt},\"width\":${image.width},\"height\":${image.height}}")
            }
            append("],\"folderId\":${note.folderId?.let(StrictJson::quote) ?: "null"},\"folderChoicePrompted\":${note.folderChoicePrompted}}")
        }

        private fun encodeWire(wire: NoteWire): String = (encodeNotePayload(wire.note).dropLast(1) + ",\"_seen\":" + encodeSeen(wire.seen) + "}")
            .also { SyncDocument.validateEntryValue("note:${wire.note.id}", it) }

        private fun encodeSeen(seen: Map<String, Long>): String = buildString {
            append('{')
            seen.toSortedMap().entries.forEachIndexed { index, (actor, counter) ->
                if (index > 0) append(',')
                append("${StrictJson.quote(actor)}:$counter")
            }
            append('}')
        }

        private fun decodeSeen(item: JSONObject): Map<String, Long> {
            val history = item.get("_seen") as? JSONObject ?: throw IllegalArgumentException("Invalid note causal history")
            require(history.length() <= MAX_SEEN_ACTORS) { "Too many devices in note causal history" }
            return history.keys().asSequence().associateWith { actor ->
                require(SyncDocument.isActor(actor)) { "Invalid note history device" }
                integer(history, actor).also { require(it in 1..SyncDocument.MAX_COUNTER) { "Invalid note history clock" } }
            }
        }

        private fun string(item: JSONObject, key: String): String = item.get(key) as? String ?: throw IllegalArgumentException("Invalid note string")
        private fun boolean(item: JSONObject, key: String): Boolean = item.get(key) as? Boolean ?: throw IllegalArgumentException("Invalid note flag")
        private fun integer(item: JSONObject, key: String): Long {
            val value = item.get(key)
            require(value is Int || value is Long) { "Invalid note number" }
            return (value as Number).toLong()
        }
        private fun int(item: JSONObject, key: String): Int = integer(item, key).also {
            require(it in 1..Int.MAX_VALUE.toLong()) { "Invalid note dimension or ordinal" }
        }.toInt()

        private inline fun <T> parse(raw: String, decode: (JSONObject) -> T): T = try {
            BoundedSyncJson.check(raw)
            decode(JSONObject(raw))
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid synchronized note, folder or original-image metadata")
        }
        private fun hash(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Platform JSON readers recurse; reject excessive structure before giving them any input. */
internal object BoundedSyncJson {
    fun check(raw: String) {
        require(raw.length <= SyncDocument.MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= SyncDocument.MAX_BYTES) { "Synchronization JSON too large" }
        val stack = CharArray(8)
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in raw) {
            if (quoted) {
                if (escaped) escaped = false
                else when (character) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> {
                    require(depth < stack.size) { "Synchronization JSON nested too deeply" }
                    stack[depth++] = character
                }
                '}', ']' -> {
                    require(depth > 0 && stack[--depth] == if (character == '}') '{' else '[') { "Invalid synchronization JSON structure" }
                }
            }
        }
        require(depth == 0 && !quoted && !escaped) { "Unclosed synchronization JSON structure" }
    }
}
