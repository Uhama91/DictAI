package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One account-scoped journal protects preferences, saved notes and folders across interrupted imports. */
internal class PreferenceSyncStore(context: Context, accountId: String, recoveryDeferred: Boolean = false) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
    private val formats = app.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
    private val content = AccountNotesSyncStore(app)
    private val journal: SharedPreferences
    private var applyingDocument: SyncDocument? = null
    val actor: String

    init {
        require(accountId.isNotBlank() && accountId.length <= 200) { "Invalid synchronization account" }
        val scope = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        journal = app.getSharedPreferences("dictai_sync_journal_$scope", Context.MODE_PRIVATE)
        actor = journal.getString("actor", null) ?: UUID.randomUUID().toString().also { generated ->
            check(journal.edit().putString("actor", generated).commit()) { "Synchronization identity was not saved" }
        }
        require(SyncDocument.isActor(actor)) { "Invalid synchronization identity" }
        // Recover first: absent fields after a partial apply are not intentional local deletions.
        if (!recoveryDeferred) recoverPending()
    }

    @Synchronized fun current(): SyncDocument {
        applyingDocument?.let { return it }
        recoverPending()
        return readCurrent()
    }

    @Synchronized fun captureLocal(): SyncDocument {
        // SharedPreferences listeners can re-enter on the main looper during a commit.
        applyingDocument?.let { return it }
        val before = current()
        val document = content.recordDeletions(before, before.record(snapshot(before), actor))
        validateDocument(document)
        persistCurrent(document)
        return document
    }

    /** Pure schema/allowlist validation; safe before any remote attachment is downloaded. */
    fun validateIncoming(documents: List<SyncDocument>) {
        documents.forEach(::validateDocument)
    }

    /** Warm immutable original-image fingerprints on a worker before taking a UI-thread snapshot. */
    fun prepareAssets() = content.prepareAssets()

    /** Read a write-ahead document before a worker restores its required originals. */
    @Synchronized fun pendingDocument(): SyncDocument? = journal.getString("pending", null)
        ?.let(SyncDocument::fromJson)?.also(::validateDocument)

    /** Build the candidate without a receipt or import, so only live originals are downloaded. */
    @Synchronized fun previewRemote(documents: List<SyncDocument>): SyncDocument {
        check(applyingDocument == null && !journal.contains("pending")) { "Synchronization recovery is required" }
        val before = readCurrent()
        val local = content.recordDeletions(before, before.record(snapshot(before), actor))
        validateDocument(local)
        documents.forEach(::validateDocument)
        val merged = documents.fold(local) { accumulated, document -> accumulated.merge(document) }
        return content.preserveConflicts(local, documents, merged, actor).also(::validateDocument)
    }

    @Synchronized fun mergeRemote(documents: List<SyncDocument>): SyncDocument {
        check(applyingDocument == null) { "Synchronization apply is already in progress" }
        val before = current()
        val local = content.recordDeletions(before, before.record(snapshot(before), actor))
        validateDocument(local)
        documents.forEach(::validateDocument)
        val ordinaryMerge = documents.fold(local) { accumulated, document -> accumulated.merge(document) }
        val merged = content.preserveConflicts(local, documents, ordinaryMerge, actor)
        validateDocument(merged)
        if (merged == local) {
            persistCurrent(local)
            return local
        }
        // One durable write-ahead record spans preferences and saved collections. Keep the last backup.
        val previousJson = local.toJson()
        check(journal.edit().putString("current", previousJson).putString("backup", previousJson)
            .putString("pending", merged.toJson()).commit()) { "Synchronization merge was not saved" }
        recoverPending()
        return readCurrent()
    }

    private fun readCurrent(): SyncDocument = journal.getString("current", null)
        ?.let(SyncDocument::fromJson)?.also(::validateDocument) ?: SyncDocument()

    private fun persistCurrent(document: SyncDocument) {
        val encoded = document.toJson()
        val editor = journal.edit()
        if (journal.getString("current", null) != encoded) editor.putString("current", encoded)
        // A failed commit can leave this exact document in memory. Always verify durability
        // before handing it to the network, even when no fields need to be rewritten.
        check(editor.commit()) { "Synchronization changes were not saved" }
    }

    private fun recoverPending() {
        if (applyingDocument != null) return
        val pendingJson = journal.getString("pending", null) ?: return
        val decodedPending = SyncDocument.fromJson(pendingJson)
        validateDocument(decodedPending)
        val before = readCurrent()
        val pending = content.preserveRecoveryChanges(before, decodedPending, actor)
        validateDocument(pending)
        val recoveryJson = pending.toJson()
        // commit() may update the memory map even when disk persistence fails; retry durability first.
        check(journal.edit().putString("pending", recoveryJson).commit()) { "Pending synchronization was not saved" }
        applyingDocument = pending
        try {
            applyDocument(pending)
            if (!journal.edit().putString("current", recoveryJson).remove("pending").commit()) {
                // A failed receipt may already remove pending from Android's memory map.
                // Restore the baseline and WAL there as well as retrying disk, so stale UI
                // autosave is compared with the unseen import before any upload is captured.
                journal.edit().putString("current", before.toJson()).putString("pending", recoveryJson).commit()
                throw IllegalStateException("Synchronization receipt was not saved")
            }
        } finally {
            applyingDocument = null
        }
    }

    /** Absent defaults are intentionally absent: a new device must not overwrite the remote account. */
    private fun snapshot(before: SyncDocument): Map<String, String> = buildMap {
        val stored = preferences.all
        for (key in scalarKeys) if (stored.containsKey(key)) {
            val value = stored[key]
            require(if (key in booleanKeys) value is Boolean else value is String) { "Invalid local synchronization preference" }
            put("pref:$key", value.toString())
        }
        if (stored.containsKey("formatting_engine") || stored.containsKey("cloud_cleanup_enabled")) {
            val requested = stored["formatting_engine"]
            val legacyEnabled = stored["cloud_cleanup_enabled"]
            require(requested == null || requested is String) { "Invalid local formatting engine" }
            require(legacyEnabled == null || legacyEnabled is Boolean) { "Invalid local formatting engine" }
            require(requested == null || requested in setOf("cloud", "off", "local")) { "Invalid local formatting engine" }
            put("pref:formatting_engine", when {
                requested == "cloud" -> "cloud"
                requested == "off" || requested == "local" -> "off"
                legacyEnabled == true -> "cloud"
                else -> "off"
            })
        }
        if (stored.containsKey("vocab_raw")) {
            require(stored["vocab_raw"] is String) { "Invalid local vocabulary" }
            val localEntries = SyncVocabulary.entries(stored["vocab_raw"] as String)
            val previousEntries = before.liveValues().filterKeys(SyncVocabulary::isKey)
            // A valid merge can combine ordinary and duplicate IDs for one source. Keep
            // those wire identities when the rendered vocabulary has not actually changed.
            val previousSemantics = SyncVocabulary.entries(SyncVocabulary.render(previousEntries))
            putAll(if (localEntries == previousSemantics) previousEntries else localEntries)
        }
        putAll(readFormats())
        if (formats.contains("selected")) {
            val selected = formats.all["selected"]
            require(selected is String) { "Invalid local selected format" }
            put("pref:selected_format", selected)
        }
        putAll(content.snapshot(before))
    }

    private fun readFormats(): Map<String, String> {
        if (!formats.contains("custom")) return emptyMap()
        val raw = formats.all["custom"]
        require(raw is String && raw.length <= SyncDocument.MAX_BYTES) { "Invalid local custom formats" }
        return try {
            BoundedSyncJson.check(raw)
            val array = JSONArray(raw)
            require(array.length() <= SyncDocument.MAX_ENTRIES) { "Too many custom formats" }
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    require(item.keys().asSequence().toSet() == setOf("id", "name", "instructions")) { "Invalid custom format fields" }
                    val id = item.get("id") as? String ?: throw IllegalArgumentException("Invalid format identity")
                    require(isFormatId(id) && !containsKey("format:$id")) { "Invalid or duplicate format identity" }
                    val name = item.get("name") as? String ?: throw IllegalArgumentException("Invalid format name")
                    val instructions = item.get("instructions") as? String ?: throw IllegalArgumentException("Invalid format instructions")
                    validateFormatFields(name, instructions)
                    put("format:$id", encodeFormat(name, instructions))
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid local custom formats")
        }
    }

    private fun validateDocument(document: SyncDocument) {
        // Enforce parser envelope/size/stamp constraints even for documents constructed in memory.
        SyncDocument.fromJson(document.toJson())
        document.entries.forEach { (key, entry) ->
            when {
                key in synchronizedPreferences -> entry.value?.let { value ->
                    val valid = when (key) {
                        "pref:dictation_language" -> value in setOf("fr", "en")
                        "pref:number_style" -> value in NumberStyle.entries.map { it.name }
                        "pref:theme_mode" -> value in ThemeMode.entries.map { it.preferenceValue }
                        "pref:formatting_engine" -> value in setOf("cloud", "off")
                        "pref:cloud_cleanup_model" -> value in CloudModelCatalog.all.map { it.preferenceValue }
                        "pref:selected_format" -> value in builtins || isFormatId(value)
                        else -> value == "true" || value == "false"
                    }
                    require(valid) { "Invalid synchronized preference" }
                }
                SyncVocabulary.isKey(key) -> entry.value?.let { value ->
                    require(SyncVocabulary.matchesEntry(key, value)) { "Invalid synchronized vocabulary entry" }
                }
                key.startsWith("format:") && isFormatId(key.removePrefix("format:")) -> entry.value?.let(::decodeFormat)
                content.ownsKey(key) -> Unit
                else -> throw IllegalArgumentException("Unknown synchronized preference")
            }
        }
        // Aggregate vocabulary size is bounded as well as individual entry sizes.
        SyncVocabulary.render(document.liveValues())
        content.validate(document)
    }

    private fun decodeFormat(value: String): Pair<String, String> = try {
        BoundedSyncJson.check(value)
        val item = JSONObject(value)
        require(item.keys().asSequence().toSet() == setOf("name", "instructions")) { "Invalid synchronized format fields" }
        val name = item.get("name") as? String ?: throw IllegalArgumentException("Invalid synchronized format name")
        val instructions = item.get("instructions") as? String ?: throw IllegalArgumentException("Invalid synchronized format instructions")
        validateFormatFields(name, instructions)
        // Canonical wire encoding excludes duplicate fields and permissive Android JSON syntax.
        require(value == encodeFormat(name, instructions)) { "Invalid synchronized format encoding" }
        name to instructions
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid synchronized format")
    }

    private fun applyDocument(document: SyncDocument) {
        val values = document.liveValues()
        val editor = preferences.edit()
        var changed = false
        for (key in scalarKeys) if (document.entries.containsKey("pref:$key")) {
            val value = values["pref:$key"]
            val desired: Any? = if (key in booleanKeys) value?.toBooleanStrict() else value
            if (preferences.all[key] != desired || desired == null && preferences.contains(key)) {
                changed = true
                when (desired) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, desired)
                    is String -> editor.putString(key, desired)
                }
            }
        }
        if (document.entries.containsKey("pref:formatting_engine")) {
            val engine = values["pref:formatting_engine"]
            val enabled = engine?.let { it == "cloud" }
            if (preferences.all["formatting_engine"] != engine || preferences.all["cloud_cleanup_enabled"] != enabled) {
                changed = true
                if (engine == null) editor.remove("formatting_engine").remove("cloud_cleanup_enabled")
                else editor.putString("formatting_engine", engine).putBoolean("cloud_cleanup_enabled", enabled == true)
            }
        }
        if (document.entries.keys.any(SyncVocabulary::isKey)) {
            val desired = values.filterKeys(SyncVocabulary::isKey)
            val existing = SyncVocabulary.entries(preferences.getString("vocab_raw", "").orEmpty())
            val desiredSemantics = SyncVocabulary.entries(SyncVocabulary.render(desired))
            if (existing != desiredSemantics) {
                changed = true
                editor.putString("vocab_raw", SyncVocabulary.render(desired))
            }
        }
        val preferencesAffected = document.entries.keys.any { key ->
            key in scalarKeys.map { "pref:$it" } || key == "pref:formatting_engine" || SyncVocabulary.isKey(key)
        }
        // An earlier failed commit may already expose these values in memory. An empty editor
        // retries disk durability without rewriting values or notifying listeners about a change.
        if (changed || preferencesAffected) check(editor.commit()) { "Synchronized dictation preferences were not saved" }

        val formatEditor = formats.edit()
        var formatsChanged = false
        val desiredFormats = values.filterKeys { it.startsWith("format:") }
        if (document.entries.keys.any { it.startsWith("format:") } && readFormats() != desiredFormats) {
            val array = JSONArray()
            desiredFormats.toSortedMap().forEach { (key, wire) ->
                val (name, instructions) = decodeFormat(wire)
                array.put(JSONObject().put("id", key.removePrefix("format:")).put("name", name).put("instructions", instructions))
            }
            formatEditor.putString("custom", array.toString())
            formatsChanged = true
        }
        if (document.entries.containsKey("pref:selected_format")) {
            val requested = values["pref:selected_format"]
            val selected = requested?.takeIf { it in builtins || "format:$it" in desiredFormats } ?: requested?.let { "cleanup" }
            if (formats.all["selected"] != selected || selected == null && formats.contains("selected")) {
                if (selected == null) formatEditor.remove("selected") else formatEditor.putString("selected", selected)
                formatsChanged = true
            }
        }
        val formatsAffected = document.entries.keys.any { it.startsWith("format:") || it == "pref:selected_format" }
        if (formatsChanged || formatsAffected) check(formatEditor.commit()) { "Synchronized custom formats were not saved" }
        content.apply(document)
    }

    private fun validateFormatFields(name: String, instructions: String) {
        require(name.isNotBlank() && name.length <= 60 && instructions.isNotBlank() && instructions.length <= 4000) {
            "Invalid synchronized format content"
        }
    }

    private fun encodeFormat(name: String, instructions: String): String =
        "{\"name\":${StrictJson.quote(name)},\"instructions\":${StrictJson.quote(instructions)}}"

    private fun isFormatId(value: String): Boolean = SyncDocument.isActor(value)

    companion object {
        private val booleanKeys = setOf("trailing_space", "show_transcript", "light_text_cleanup")
        private val scalarKeys = booleanKeys + setOf("dictation_language", "number_style", "theme_mode", "cloud_cleanup_model")
        private val synchronizedPreferences = scalarKeys.map { "pref:$it" }.toSet() + setOf("pref:formatting_engine", "pref:selected_format")
        private val builtins = setOf("cleanup", "corrected", "list", "email")
    }
}
