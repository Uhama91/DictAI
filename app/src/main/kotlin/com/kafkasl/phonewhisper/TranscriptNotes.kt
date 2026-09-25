package com.kafkasl.phonewhisper

import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingDocumentJson
import com.kafkasl.phonewhisper.meeting.MeetingProjection

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
    val meeting: MeetingDocument? = null,
    val meetingRaw: String? = null,
) {
    init { require(meeting == null || meetingRaw == null) { "A note cannot hold a parsed and opaque meeting payload together" } }
}

internal interface TranscriptNoteStorage {
    fun all(): List<TranscriptNote>
    fun put(note: TranscriptNote)
    /** Implementations with durable storage should verify this write before returning. */
    fun putMeeting(note: TranscriptNote) = put(note)
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
        require(old?.meeting == null && old?.meetingRaw == null) {
            "Une note Réunion ne peut pas être modifiée par une sauvegarde de texte plate"
        }
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

    fun saveMeeting(id: String?, meeting: MeetingDocument, images: List<NoteImage>? = null): TranscriptNote {
        MeetingDocumentJson.encode(meeting) // Validate the full structure before any storage write.
        val noteId = meeting.sessionId
        require(id == null || id == noteId) { "L’identifiant d’une note Réunion est sa session" }
        val old = get(noteId)
        require(old == null || (old.meeting?.sessionId == meeting.sessionId && old.meetingRaw == null)) {
            "Cette note appartient à une autre session ou contient un payload opaque"
        }

        val attachments = (images ?: old?.images.orEmpty()).toList()
        require(attachments.size <= NoteImage.MAX_IMAGES)
        require(attachments.distinctBy { it.id }.size == attachments.size && attachments.distinctBy { it.number }.size == attachments.size)
        val imageNumbers = attachments.mapTo(mutableSetOf()) { it.number }
        val projection = MeetingProjection.text(meeting, imageNumbers)
        val title = if (old?.renamed == true) old.title else titleFromMeeting(meeting, imageNumbers)
        val note = TranscriptNote(
            id = noteId,
            title = title,
            text = projection,
            updatedAt = now(),
            renamed = old?.renamed ?: false,
            images = attachments,
            folderId = old?.folderId?.takeIf(cachedFolders::containsKey),
            folderChoicePrompted = old?.folderChoicePrompted ?: false,
            meeting = meeting,
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
        if (cachedFolders[id] == null) return false
        cached.values.filter { it.folderId == id }.forEach { note ->
            put(note.copy(folderId = null, updatedAt = now(), folderChoicePrompted = true))
        }
        storage.removeFolder(id)
        cachedFolders.remove(id)
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
        if (note.meeting != null || note.meetingRaw != null) storage.putMeeting(note) else storage.put(note)
        cached[note.id] = note
    }

    private fun putFolder(folder: NoteFolder) {
        storage.putFolder(folder)
        cachedFolders[folder.id] = folder
    }

    private fun sorted(values: Collection<TranscriptNote>): List<TranscriptNote> =
        values.sortedWith(compareByDescending<TranscriptNote> { it.updatedAt }.thenBy { it.id })

    private fun normalizeName(name: String): String? = name.trim().take(MAX_NAME_LENGTH).takeIf { it.isNotEmpty() }

    private fun titleFromMeeting(meeting: MeetingDocument, imageNumbers: Set<Int>): String {
        val speech = MeetingProjection.rows(meeting, imageNumbers)
            .asSequence()
            .filter { it.editableSpeech }
            .map { imageMarker.replace(it.body, " ").trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return "Nouvelle réunion"
        return titleFrom(speech).takeUnless { it == "Nouvelle note" } ?: "Nouvelle réunion"
    }

    companion object {
        const val MAX_NAME_LENGTH = 80
        private val imageMarker = Regex("\\[\\[Image [1-9][0-9]{0,5}]]")

        fun titleFrom(text: String): String {
            val line = text.lineSequence().map { it.trim() }.filter { !it.startsWith("[[Image ") }.firstOrNull { it.isNotEmpty() } ?: return "Nouvelle note"
            val words = line.replace(Regex("(?i)^(euh[ ,…]*|hum[ ,…]*)+"), "").split(Regex("\\s+")).take(9).joinToString(" ")
            return words.take(60).trimEnd().ifBlank { "Nouvelle note" }.replaceFirstChar { it.titlecase() }
        }
    }
}

internal fun TranscriptNote.withMeetingProjection(): TranscriptNote {
    val document = meeting ?: return this
    val imageNumbers = images.mapTo(mutableSetOf()) { it.number }
    return copy(text = MeetingProjection.text(document, imageNumbers))
}
