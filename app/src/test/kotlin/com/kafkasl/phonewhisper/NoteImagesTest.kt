package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class NoteImagesTest {
    private fun image(number: Int) = NoteImage("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
        number, NoteImageKind.SCREENSHOT, 0L, 1080, 2400)
    private class Storage : TranscriptNoteStorage {
        val map = mutableMapOf<String, TranscriptNote>()
        override fun all() = map.values.toList()
        override fun put(note: TranscriptNote) { map[note.id] = note }
        override fun remove(id: String) { map.remove(id) }
    }
    @Test fun `existing text-only notes migrate without attachments and save preserves images`() {
        val storage = Storage()
        storage.put(TranscriptNote("n", "Ancienne", "Texte", 0))
        val notes = TranscriptNotes(storage)
        assertTrue(notes.get("n")!!.images.isEmpty())
        notes.save("n", "Texte [[Image 1]] suite", listOf(image(1)))
        notes.rename("n", "Contexte")
        notes.save("n", "Texte corrigé [[Image 1]] suite")
        val reopened = TranscriptNotes(storage).get("n")!!
        assertEquals("Contexte", reopened.title)
        assertEquals(listOf(image(1)), reopened.images)
    }
    @Test fun `ten images are allowed but eleventh and duplicate metadata are rejected without writing`() {
        val storage = Storage()
        val notes = TranscriptNotes(storage, newId = { "n" })
        val ten = (1..10).map(::image)
        notes.save(null, "A", ten)
        assertThrows(IllegalArgumentException::class.java) { notes.save("n", "B", ten + image(11)) }
        assertEquals("A", storage.map["n"]!!.text)
        assertThrows(IllegalArgumentException::class.java) { notes.save("n", "B", listOf(image(1), image(1))) }
        assertEquals(10, storage.map["n"]!!.images.size)
    }
    @Test fun `context marker anchors at tap before continued ASR and survives revision`() {
        val transcript = EditableTranscript()
        transcript.update("bonjour tout monde")
        transcript.anchor(NoteImageMarkers.append("bonjour tout monde", 1))
        assertFalse(transcript.hasUserEdits())
        val next = transcript.update("bonjour tout le monde voici mon commentaire")
        assertEquals("bonjour tout monde\n\n[[Image 1]]\n\nVoici mon commentaire", next)
        transcript.edit(next.replace("tout monde", "tout le monde"))
        transcript.anchor(NoteImageMarkers.append(next, 2))
        assertTrue(transcript.hasUserEdits())
        assertTrue(transcript.resolveFinal("bonjour tout le monde voici mon commentaire suite")!!.endsWith("[[Image 2]]\n\nSuite"))
    }
    @Test fun `camera cancellation removes only its marker and retains later words and other images`() {
        val source = "Avant\n[[Image 1]]\nAprès [[Image 2]] et encore après."
        val cancelled = NoteImageMarkers.remove(source, 2)
        assertEquals("Avant\n[[Image 1]]\nAprès  et encore après.", cancelled)
        assertEquals(listOf(1), NoteImageMarkers.numbers(cancelled))
    }
    @Test fun `parts preserve the exact reading order and put unanchored image at the end`() {
        val note = TranscriptNote("n", "A", "Avant [[Image 2]] milieu [[Image 1]] après", 0,
            images = listOf(image(1), image(2), image(3)))
        val parts = NoteImageMarkers.parts(note)
        assertEquals(listOf("Avant ", " milieu ", " après"), parts.filterIsInstance<NoteImageMarkers.Part.Text>().map { it.text })
        assertEquals(listOf(2, 1, 3), parts.filterIsInstance<NoteImageMarkers.Part.Image>().map { it.image.number })
        assertTrue((parts.last() as NoteImageMarkers.Part.Image).missingMarker)
    }
    @Test fun `unknown and duplicate references remain visible without duplicating image bytes`() {
        val note = TranscriptNote("n", "A", "[[Image 99]] [[Image 1]] [[Image 1]]", 0, images = listOf(image(1)))
        val parts = NoteImageMarkers.parts(note)
        assertEquals(1, parts.filterIsInstance<NoteImageMarkers.Part.Image>().size)
        assertTrue(parts.filterIsInstance<NoteImageMarkers.Part.Text>().any { "[[Image 99]]" in it.text })
        assertTrue(parts.filterIsInstance<NoteImageMarkers.Part.Text>().any { "[[Image 1]]" in it.text })
    }
    @Test fun `HTML is self-contained escaped and preserves byte-identical JPEGs and context order`() {
        val bytes = byteArrayOf(-1, -40, 0, 1, 2, 3, -1, -39)
        val note = TranscriptNote("n", "<script>alert('title')</script>",
            "Texte <img src=x onerror=alert(1)>\n[[Image 1]]\nTexte après éàê", 0, images = listOf(image(1)))
        val out = ByteArrayOutputStream()
        NoteHtmlExport.write(note, out) { bytes.inputStream() }
        val html = out.toString("UTF-8")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        val embedded = Regex("data:image/jpeg;base64,([^\"]+)").find(html)!!.groupValues[1]
        assertArrayEquals(bytes, Base64.getDecoder().decode(embedded))
        assertTrue(html.indexOf("Texte &lt;img") < html.indexOf("<figure"))
        assertTrue(html.indexOf("</figure>") < html.indexOf("Texte après"))
        assertFalse(html.contains("file://")); assertFalse(html.contains("https://"))
        assertTrue(html.endsWith("</main></body></html>\n"))
    }
    @Test fun `share text includes complete long note and image correspondence`() {
        val long = "Contenu complet. ".repeat(20000)
        val text = NoteShareText.create(TranscriptNote("n", "Note", "$long[[Image 1]]", 0, images = listOf(image(1))))
        assertTrue(text.contains(long))
        assertTrue(text.contains("Image 1 : Capture d’écran"))
    }
    @Test fun `LLM cannot drop duplicate reorder or renumber context references`() {
        val source = "Avant [[Image 1]] puis [[Image 2]] après"
        val request = LocalFormatRequest(source, "correct", "French")
        assertNotNull(request.acceptOutput("Avant.\n[[Image 1]]\nPuis\n[[Image 2]]\naprès."))
        assertNull(request.acceptOutput("Avant puis après"))
        assertNull(request.acceptOutput("Avant [[Image 2]] puis [[Image 1]] après"))
        assertNull(request.acceptOutput("Avant [[Image 1]] puis [[Image 1]] [[Image 2]] après"))
        assertNull(request.acceptOutput("Avant [[Image 1]] puis [[Image 3]] après"))
    }
    @Test fun `Gemma correction accepts faithful context markers in prose`() {
        val request = LocalFormatRequest("Bonjour. [[Image 1]] Voici mon observation. [[Image 2]] Fin.", "", "French",
            protectedTerms = listOf("[[Image 1]]", "[[Image 2]]"), layoutKind = LocalLayoutKind.TEXT,
            validation = LocalFormatValidation.GEMMA_EDITING)
        val output = "Bonjour.\n\n[[Image 1]]\n\nVoici mon observation.\n\n[[Image 2]]\n\nFin."
        assertNotNull(request.acceptOutput(output))
        assertNull(request.acceptOutput("Bonjour. Voici mon observation. [[Image 1]] [[Image 2]] Fin."))
        assertTrue(GemmaFormattingPrompt.system(request).contains("between the same surrounding passages"))
    }
    @Test fun `image path identifiers cannot traverse private directories`() {
        assertFalse(NoteImage.validId("../secret"))
        assertFalse(NoteImage.validId("00000000-0000-0000-0000-000000000001.jpg"))
        assertTrue(NoteImage.validId(image(1).id))
    }
}
