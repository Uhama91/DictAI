package com.kafkasl.phonewhisper

internal object NoteShareText {
    fun create(note: TranscriptNote): String {
        val projected = note.withMeetingProjection()
        return buildString {
            append(projected.title).append("\n\n").append(projected.text)
            if (projected.images.isNotEmpty()) {
                append("\n\nImages jointes — les repères [[Image N]] situent chaque image dans la note.\n")
                NoteImageMarkers.parts(projected).filterIsInstance<NoteImageMarkers.Part.Image>().forEach {
                    append("Image ${it.image.number} : ${it.image.kind.label}")
                    if (it.missingMarker) append(" (repère retiré du texte)")
                    append('\n')
                }
            }
        }
    }
}
