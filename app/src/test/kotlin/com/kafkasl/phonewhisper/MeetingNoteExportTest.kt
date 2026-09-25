package com.kafkasl.phonewhisper

import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingParticipant
import com.kafkasl.phonewhisper.meeting.MeetingTurn
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingNoteExportTest {
    @Test
    fun `share and html exports reproject current turns and escape html without leaking stale speech`() {
        val speaker = MeetingParticipant("speaker", ordinal = 1, channel = 1, name = "Sophie")
        val ignored = MeetingParticipant("ignored", ordinal = 2, channel = 2, name = "Karim", ignored = true)
        val document = MeetingDocument(
            sessionId = "session-a",
            runId = "run-a",
            participants = listOf(speaker, ignored),
            turns = listOf(
                MeetingTurn("hidden", 1, 0, 1, "Secret ancien [[Image 7]]", ignored.id, attributionStable = true),
                MeetingTurn("visible", 2, 1, 2, "Sophie & Karim <d’accord>.", speaker.id, attributionStable = true),
            ),
        )
        val image = NoteImage("00000000-0000-0000-0000-000000000007", 7, NoteImageKind.CAMERA, 0L, 16, 16)
        val stale = TranscriptNote(
            id = "session-a",
            title = "Réunion & <équipe>",
            text = "Secret ancien doit disparaître.",
            updatedAt = 0L,
            images = listOf(image),
            meeting = document,
        )

        val shared = NoteShareText.create(stale)
        assertTrue(shared.contains("[[Image 7]]"))
        assertTrue(shared.contains("Sophie & Karim <d’accord>."))
        assertFalse(shared.contains("Secret ancien"))

        val html = ByteArrayOutputStream().also { output ->
            NoteHtmlExport.write(stale, output) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        }.toString(Charsets.UTF_8.name())
        assertTrue(html.contains("<title>Réunion &amp; &lt;équipe&gt;</title>"))
        assertTrue(html.contains("Sophie &amp; Karim &lt;d’accord&gt;."))
        assertTrue(html.contains("<figure id=\"image-7\">"))
        assertFalse(html.contains("Secret ancien"))
    }
}
