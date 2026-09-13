package com.kafkasl.phonewhisper

internal data class TranscriptNote(val id: String, val title: String, val text: String, val updatedAt: Long, val renamed: Boolean = false, val images: List<NoteImage> = emptyList())
internal interface TranscriptNoteStorage {
    fun all(): List<TranscriptNote>
    fun put(note: TranscriptNote)
    fun remove(id: String)
}
internal class TranscriptNotes(private val storage: TranscriptNoteStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() }) {
    private val cached = storage.all().associateBy { it.id }.toMutableMap()
    fun all() = cached.values.sortedByDescending { it.updatedAt }
    fun get(id: String?) = cached[id]
    fun save(id: String?, text: String, images: List<NoteImage>? = null): TranscriptNote {
        val old = get(id)
        val attachments = (images ?: old?.images.orEmpty()).toList()
        require(attachments.size <= NoteImage.MAX_IMAGES)
        require(attachments.distinctBy { it.id }.size == attachments.size && attachments.distinctBy { it.number }.size == attachments.size)
        val note = TranscriptNote(old?.id ?: newId(), if (old?.renamed == true) old.title else titleFrom(text), text, now(), old?.renamed ?: false, attachments)
        storage.put(note)
        cached[note.id] = note
        return note
    }
    fun rename(id: String, title: String) {
        val old = get(id) ?: return
        val name = title.trim().take(80)
        if (name.isNotEmpty()) {
            val renamed = old.copy(title = name, renamed = true, updatedAt = now())
            storage.put(renamed)
            cached[id] = renamed
        }
    }
    fun delete(id: String) { storage.remove(id); cached.remove(id) }
    companion object {
        fun titleFrom(text: String): String {
            val line = text.lineSequence().map { it.trim() }.filter { !it.startsWith("[[Image ") }.firstOrNull { it.isNotEmpty() } ?: return "Nouvelle note"
            val words = line.replace(Regex("(?i)^(euh[ ,…]*|hum[ ,…]*)+"), "").split(Regex("\\s+")).take(9).joinToString(" ")
            return words.take(60).trimEnd().ifBlank { "Nouvelle note" }.replaceFirstChar { it.titlecase() }
        }
    }
}
