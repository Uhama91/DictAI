package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteExportFixtureTest {
    @Test
    fun `mixed note HTML export follows edited order with compact image rules`() {
        val store = NoteImageStore(RuntimeEnvironment.getApplication())
        val images = listOf(
            image(1, NoteImageKind.CAMERA, 1200, 675),
            image(2, NoteImageKind.CAMERA, 675, 1200),
            image(3, NoteImageKind.SCREENSHOT, 360, 2400),
            image(4, NoteImageKind.SCAN, 700, 980),
        )
        images.forEach { drawSyntheticImage(store.file(it.id), it) }
        val note = TranscriptNote(
            "media-export-fixture",
            "Compte rendu & suite",
            "Avant la capture d’écran, les mesures sont stables.\n\n" +
                "Cette ligne vide reste dans le bloc.\n\n" +
                "[[Image 3]]\n\nAprès la capture d’écran, le texte reprend.\n\n" +
                "[[Image 4]]\n\nLe compte rendu continue après le document scanné.\n\n" +
                "[[Image 1]]\n\nPuis vient la photo portrait.\n\n" +
                "[[Image 2]]\n\nFin du texte avec <script>à échapper</script>.",
            1_790_000_000_000L,
            images = images,
        )

        val outputDir = File("build/reports/media-export").canonicalFile.apply { mkdirs() }
        val htmlFile = File(outputDir, "mixed-note.html")
        htmlFile.outputStream().use { output ->
            NoteHtmlExport.write(note, output) { store.file(it.id).inputStream() }
        }

        val html = htmlFile.readText()
        assertEquals(listOf(3, 4, 1, 2), NoteImageMarkers.readingOrder(note))
        assertTrue(html.indexOf("Avant la capture d’écran") < html.indexOf("<figure id=\"image-3\""))
        assertTrue(html.indexOf("</figure>") < html.indexOf("Après la capture d’écran"))
        assertTrue(html.contains("<div class=\"text\">Avant la capture d’écran, les mesures sont stables.\n\nCette ligne vide reste dans le bloc.</div>"))
        assertTrue(html.contains("<div class=\"text\">Après la capture d’écran, le texte reprend.</div>"))
        assertTrue(html.indexOf("id=\"image-3\"") < html.indexOf("id=\"image-4\""))
        assertTrue(html.indexOf("id=\"image-4\"") < html.indexOf("id=\"image-1\""))
        assertTrue(html.indexOf("id=\"image-1\"") < html.indexOf("id=\"image-2\""))
        assertTrue(html.contains("<details class=\"image-size\"><summary><span class=\"image-size-label\">Agrandir / Réduire</span><img class=\"document\" alt=\"Image 3 · Capture d’écran"))
        assertTrue(html.contains("details.image-size summary:focus-visible{outline:2px solid #226349"))
        assertTrue(html.contains("figure{margin:.75rem 0;break-inside:avoid}"))
        assertTrue(html.contains("img.camera{max-width:min(100%,453px);max-height:307px}"))
        assertTrue(html.contains("img.document{max-width:min(100%,573px);max-height:560px}"))
        assertTrue(html.contains("img.camera{max-width:340pt;max-height:230pt}"))
        assertTrue(html.contains("img.document{max-width:430pt;max-height:420pt}"))
        assertTrue(html.contains("details.image-size[open] img.document{max-width:100%;max-height:none}"))
        assertTrue(html.contains("details.image-size[open] img.camera,img.camera{max-width:340pt;max-height:230pt}"))
        assertTrue(html.contains("details.image-size[open] img.document,img.document{max-width:430pt;max-height:420pt}"))
        assertTrue(html.contains("&lt;script&gt;à échapper&lt;/script&gt;"))
        assertFalse(html.contains("<script>"))
        assertEquals(4, Regex("data:image/jpeg;base64,").findAll(html).count())
        assertTrue(htmlFile.length() > 10_000L)
    }

    private fun image(number: Int, kind: NoteImageKind, width: Int, height: Int) = NoteImage(
        id = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
        number = number,
        kind = kind,
        capturedAt = 1_790_000_000_000L + number,
        width = width,
        height = height,
    )

    private fun drawSyntheticImage(file: File, image: NoteImage) {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (image.kind == NoteImageKind.SCAN) Color.WHITE else Color.rgb(238, 243, 247))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (image.kind) {
                NoteImageKind.CAMERA -> Color.rgb(36, 116, 84)
                NoteImageKind.SCREENSHOT -> Color.rgb(62, 95, 150)
                else -> Color.rgb(35, 40, 48)
            }
            strokeWidth = 5f
        }
        if (image.kind == NoteImageKind.SCAN) {
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 32f
            canvas.drawText("Document de synthèse", 34f, 70f, paint)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 20f
            canvas.drawText("Compte rendu des observations", 34f, 126f, paint)
            paint.textSize = 18f
            canvas.drawText("Les mesures restent lisibles après le scan.", 34f, 166f, paint)
            canvas.drawText("Chaque étape est présentée dans son ordre.", 34f, 202f, paint)
        } else {
            canvas.drawRect(0f, 0f, image.width.toFloat(), (image.height * .12f), paint)
            paint.color = Color.rgb(75, 90, 110)
        }
        var y = (image.height * .18f).toInt()
        val gap = (image.height / 16).coerceAtLeast(32)
        while (y < image.height - 20) {
            canvas.drawLine(24f, y.toFloat(), image.width - 24f, y.toFloat(), paint)
            y += gap
        }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        bitmap.recycle()
    }
}
