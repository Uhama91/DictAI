package com.kafkasl.phonewhisper

/** A user-created collection. Notes may deliberately keep a null folderId. */
internal data class NoteFolder(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

internal data class TranscriptNote(
    val id: String,
    val title: String,
    val text: String,
    val updatedAt: Long,
    val renamed: Boolean = false,
    val images: List<NoteImage> = emptyList(),
    val folderId: String? = null,
    /** Legacy notes are treated as already classified; newly saved notes set this false. */
    val folderChoicePrompted: Boolean = true,
)

internal interface TranscriptNoteStorage {
    fun all(): List<TranscriptNote>
    fun put(note: TranscriptNote)
    fun remove(id: String)

    /** Folder methods have defaults so existing storage implementations remain compatible. */
    fun allFolders(): List<NoteFolder> = emptyList()
    fun putFolder(folder: NoteFolder) = Unit
    fun removeFolder(id: String) = Unit
}

internal class TranscriptNotes(
    private val storage: TranscriptNoteStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val cached = storage.all().associateBy { it.id }.toMutableMap()
    private val cachedFolders = storage.allFolders().associateBy { it.id }.toMutableMap()

    init {
        // A crash between moving notes and removing a folder, or a hand-edited
        // legacy preference, must never hide a note behind an unknown folder id.
        cached.values.filter { it.folderId != null && !cachedFolders.containsKey(it.folderId) }.forEach { note ->
            put(note.copy(folderId = null))
        }
    }

    /** All notes, ordered by most recently modified first (the historical API). */
    fun all(): List<TranscriptNote> = sorted(cached.values)

    /** Notes in one folder; null explicitly means the visible “Sans dossier” bucket. */
    fun all(folderId: String?): List<TranscriptNote> = sorted(cached.values.filter { it.folderId == folderId })

    fun get(id: String?) = cached[id]

    fun folders(): List<NoteFolder> = cachedFolders.values
        .sortedWith(compareBy<NoteFolder> { it.createdAt }.thenBy { it.name.lowercase() }.thenBy { it.id })

    fun getFolder(id: String?) = id?.let(cachedFolders::get)

    fun save(id: String?, text: String, images: List<NoteImage>? = null, folderId: String? = null): TranscriptNote {
        val old = get(id)
        val attachments = (images ?: old?.images.orEmpty()).toList()
        require(attachments.size <= NoteImage.MAX_IMAGES)
        require(attachments.distinctBy { it.id }.size == attachments.size && attachments.distinctBy { it.number }.size == attachments.size)
        val targetFolder = when {
            folderId != null -> folderId.also { require(cachedFolders.containsKey(it)) }
            old != null -> old.folderId?.takeIf(cachedFolders::containsKey)
            else -> null
        }
        val note = TranscriptNote(
            old?.id ?: newId(),
            if (old?.renamed == true) old.title else titleFrom(text),
            text,
            now(),
            old?.renamed ?: false,
            attachments,
            targetFolder,
            old?.folderChoicePrompted ?: (folderId != null),
        )
        put(note)
        return note
    }

    fun rename(id: String, title: String) {
        val old = get(id) ?: return
        val name = title.trim().take(MAX_NAME_LENGTH)
        if (name.isNotEmpty()) {
            put(old.copy(title = name, renamed = true, updatedAt = now()))
        }
    }

    fun createFolder(name: String): NoteFolder? {
        val normalized = normalizeName(name) ?: return null
        if (cachedFolders.values.any { it.name.equals(normalized, ignoreCase = true) }) return null
        val timestamp = now()
        val folder = NoteFolder(newId(), normalized, timestamp, timestamp)
        putFolder(folder)
        return folder
    }

    fun renameFolder(id: String, name: String): NoteFolder? {
        val old = cachedFolders[id] ?: return null
        val normalized = normalizeName(name) ?: return null
        if (cachedFolders.values.any { it.id != id && it.name.equals(normalized, ignoreCase = true) }) return null
        val renamed = old.copy(name = normalized, updatedAt = now())
        putFolder(renamed)
        return renamed
    }

    /** Deleting a folder is an unfiling operation and therefore cannot delete note content. */
    fun deleteFolder(id: String): Boolean {
        if (cachedFolders.remove(id) == null) return false
        cached.values.filter { it.folderId == id }.forEach { note ->
            put(note.copy(folderId = null, updatedAt = now(), folderChoicePrompted = true))
        }
        storage.removeFolder(id)
        return true
    }

    /** Move a note to an existing folder, or to null for “Sans dossier”. */
    fun move(id: String, folderId: String?): TranscriptNote? {
        val old = get(id) ?: return null
        require(folderId == null || cachedFolders.containsKey(folderId)) { "Dossier introuvable" }
        if (old.folderId == folderId && old.folderChoicePrompted) return old
        return old.copy(folderId = folderId, updatedAt = now(), folderChoicePrompted = true).also(::put)
    }

    /** Creates a folder only after confirming the note exists, avoiding orphan folders on bad input. */
    fun createFolderAndMove(noteId: String, name: String): NoteFolder? {
        if (get(noteId) == null) return null
        val folder = createFolder(name) ?: return null
        move(noteId, folder.id)
        return folder
    }

    /** Consumes the one-time initial location choice for a note. */
    fun chooseFolder(noteId: String, folderId: String?): TranscriptNote? = move(noteId, folderId)

    fun needsInitialFolderChoice(noteId: String?): Boolean =
        cachedFolders.isNotEmpty() && get(noteId)?.folderChoicePrompted == false

    fun delete(id: String) { storage.remove(id); cached.remove(id) }

    private fun put(note: TranscriptNote) {
        storage.put(note)
        cached[note.id] = note
    }

    private fun putFolder(folder: NoteFolder) {
        storage.putFolder(folder)
        cachedFolders[folder.id] = folder
    }

    private fun sorted(values: Collection<TranscriptNote>): List<TranscriptNote> =
        values.sortedWith(compareByDescending<TranscriptNote> { it.updatedAt }.thenBy { it.id })

    private fun normalizeName(name: String): String? = name.trim().take(MAX_NAME_LENGTH).takeIf { it.isNotEmpty() }

    companion object {
        const val MAX_NAME_LENGTH = 80

        fun titleFrom(text: String): String {
            val line = text.lineSequence().map { it.trim() }.filter { !it.startsWith("[[Image ") }.firstOrNull { it.isNotEmpty() } ?: return "Nouvelle note"
            val words = line.replace(Regex("(?i)^(euh[ ,…]*|hum[ ,…]*)+"), "").split(Regex("\\s+")).take(9).joinToString(" ")
            return words.take(60).trimEnd().ifBlank { "Nouvelle note" }.replaceFirstChar { it.titlecase() }
        }
    }
}
