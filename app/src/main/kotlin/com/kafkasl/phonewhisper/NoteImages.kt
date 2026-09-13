package com.kafkasl.phonewhisper

internal enum class NoteImageKind(val label: String) { SCREENSHOT("Capture d’écran"), CAMERA("Photo") }

internal data class NoteImage(
    val id: String,
    val number: Int,
    val kind: NoteImageKind,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
) {
    init { require(validId(id) && number > 0 && width > 0 && height > 0) }
    val marker: String get() = NoteImageMarkers.marker(number)
    companion object {
        const val MAX_IMAGES = 10
        fun validId(id: String) = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id)
        fun nextNumber(images: List<NoteImage>, text: String): Int =
            maxOf(images.maxOfOrNull { it.number } ?: 0, NoteImageMarkers.numbers(text).maxOrNull() ?: 0) + 1
    }
}

/** Visible, portable references: no file paths or image bytes ever enter a language model. */
internal object NoteImageMarkers {
    private val pattern = Regex("\\[\\[Image ([1-9][0-9]{0,5})]]")
    fun marker(number: Int) = "[[Image $number]]"
    fun markers(text: String) = pattern.findAll(text).map { it.value }.toList()
    fun numbers(text: String) = pattern.findAll(text).map { it.groupValues[1].toInt() }.toList()
    fun append(text: String, number: Int) = text.trimEnd() +
        (if (text.isBlank()) "" else "\n\n") + marker(number) + "\n\n"
    fun remove(text: String, number: Int) = text.replace(marker(number), "")
    fun preserved(source: String, output: String): Boolean = markers(source) == markers(output)
    const val INSTRUCTIONS = " Keep every [[Image N]] reference exactly once, unchanged, in the same order and between the same surrounding passages. Put it on a separate line. It refers to an attached image; do not describe or interpret an image."

    sealed class Part {
        data class Text(val text: String) : Part()
        data class Image(val image: NoteImage, val missingMarker: Boolean = false) : Part()
    }

    /** A deleted reference never silently discards a photograph: unanchored images go at the end. */
    fun parts(note: TranscriptNote): List<Part> = buildList {
        val byNumber = note.images.associateBy { it.number }
        val used = mutableSetOf<Int>()
        var offset = 0
        pattern.findAll(note.text).forEach { match ->
            val image = byNumber[match.groupValues[1].toInt()] ?: return@forEach
            if (!used.add(image.number)) return@forEach
            if (match.range.first > offset) add(Part.Text(note.text.substring(offset, match.range.first)))
            add(Part.Image(image))
            offset = match.range.last + 1
        }
        if (offset < note.text.length) add(Part.Text(note.text.substring(offset)))
        note.images.filter { it.number !in used }.forEach { add(Part.Image(it, missingMarker = true)) }
    }
}
