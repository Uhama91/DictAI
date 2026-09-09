package com.kafkasl.phonewhisper

internal object NoteShareText {
    fun create(note: TranscriptNote): String = buildString {
        append(note.title).append("\n\n").append(note.text)
        if (note.images.isNotEmpty()) {
            append("\n\nImages jointes — les repères [[Image N]] situent chaque image dans la note.\n")
            NoteImageMarkers.parts(note).filterIsInstance<NoteImageMarkers.Part.Image>().forEach {
                append("Image ${it.image.number} : ${it.image.kind.label}")
                if (it.missingMarker) append(" (repère retiré du texte)")
                append('\n')
            }
        }
    }
}
