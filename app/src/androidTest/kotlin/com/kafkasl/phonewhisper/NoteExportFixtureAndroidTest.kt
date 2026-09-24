package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Produces a real Android PdfDocument fixture using synthetic image data only. */
@RunWith(AndroidJUnit4::class)
class NoteExportFixtureAndroidTest {
    @Test
    fun mixedPhotoScreenshotAndScanExportToReadableAndroidPdf() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val privateDir = File(app.cacheDir, "export-fixture-$id").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(privateDir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(privateDir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("export-fixture-$id-$name", mode)
        }
        val store = NoteImageStore(context)
        val session = store.beginBatch(NoteImageKind.SCREENSHOT, resume = false, number = 1, maxImages = 4)
        try {
            listOf(
                ImageFixture(NoteImageKind.CAMERA, 1200, 675),
                ImageFixture(NoteImageKind.CAMERA, 675, 1200),
                ImageFixture(NoteImageKind.SCREENSHOT, 360, 2400),
                ImageFixture(NoteImageKind.SCAN, 700, 980),
            ).forEach { fixture ->
                val bitmap = drawSyntheticImage(fixture)
                try { store.store(session.id, bitmap, fixture.kind) } finally { bitmap.recycle() }
            }
            val images = store.pending()?.images ?: error("Les images de la fixture n’ont pas été enregistrées")
            assertTrue("Expected four synthetic images", images.size == 4)
            val note = TranscriptNote(
                id = id,
                title = "Compte rendu des observations",
                text = "Texte avant les images : les mesures sont stables.\n\n" +
                    "[[Image 3]]\n\nAprès la capture d’écran, le relevé continue.\n\n" +
                    "[[Image 4]]\n\nLe document scanné confirme les résultats.\n\n" +
                    "[[Image 1]]\n\nLa photo paysage montre la première étape.\n\n" +
                    "[[Image 2]]\n\nLa photo portrait clôt le compte rendu.",
                updatedAt = System.currentTimeMillis(),
                images = images,
            )
            assertTrue(NoteImageMarkers.readingOrder(note) == listOf(3, 4, 1, 2))

            val artifactDir = File(
                app.getExternalFilesDir("media-export-fixtures") ?: error("Répertoire de fixture externe indisponible"),
                "mixed-note-$id",
            ).apply { mkdirs() }
            val pdfFile = File(artifactDir, "mixed-note.pdf")
            pdfFile.outputStream().use { NotePdfExport.write(note, it, store) }
            assertTrue("Android PDF export should produce a substantial PDF", pdfFile.length() > 8_000L)
            Log.i(TAG, "PDF_PATH=${pdfFile.absolutePath}")

            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue("Expected multiple pages with four images", renderer.pageCount in 2..8)
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            try {
                                rendered.eraseColor(Color.WHITE)
                                page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                var nonWhite = 0
                                for (y in 0 until rendered.height step 4) {
                                    for (x in 0 until rendered.width step 4) {
                                        if (rendered.getPixel(x, y) != Color.WHITE) nonWhite++
                                    }
                                }
                                assertTrue("PDF page ${index + 1} should contain rendered content", nonWhite > 100)
                            } finally { rendered.recycle() }
                        }
                    }
                }
            }
        } finally {
            store.clearPending(session.id)
            privateDir.deleteRecursively()
            app.deleteSharedPreferences("export-fixture-$id-note_capture")
        }
    }

    private data class ImageFixture(val kind: NoteImageKind, val width: Int, val height: Int)

    private fun drawSyntheticImage(fixture: ImageFixture): Bitmap {
        val bitmap = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 45, 60) }
        when (fixture.kind) {
            NoteImageKind.SCAN -> {
                canvas.drawColor(Color.WHITE)
                ink.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                ink.textSize = 34f
                canvas.drawText("Document de synthèse", 36f, 76f, ink)
                ink.typeface = Typeface.DEFAULT
                ink.textSize = 23f
                canvas.drawText("Compte rendu des observations", 36f, 132f, ink)
                ink.textSize = 19f
                canvas.drawText("Les mesures restent lisibles après le scan.", 36f, 177f, ink)
                canvas.drawText("Chaque étape est présentée dans son ordre.", 36f, 218f, ink)
                var y = 270f
                while (y < fixture.height - 40f) {
                    canvas.drawLine(36f, y, fixture.width - 36f, y, ink)
                    y += 48f
                }
            }
            NoteImageKind.SCREENSHOT -> {
                canvas.drawColor(Color.rgb(248, 249, 252))
                val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(52, 92, 150) }
                canvas.drawRect(0f, 0f, fixture.width.toFloat(), 105f, bar)
                ink.color = Color.WHITE
                ink.textSize = 25f
                ink.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("Compte rendu", 24f, 67f, ink)
                ink.color = Color.rgb(35, 45, 60)
                ink.textSize = 16f
                ink.typeface = Typeface.DEFAULT
                var y = 150f
                var row = 1
                while (y < fixture.height - 40f) {
                    canvas.drawText("Observation $row : résultat confirmé", 20f, y, ink)
                    y += 56f
                    row++
                }
            }
            NoteImageKind.CAMERA -> {
                canvas.drawColor(Color.rgb(227, 237, 230))
                val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 116, 84) }
                canvas.drawRoundRect(RectF(24f, 24f, fixture.width - 24f, fixture.height * .7f), 18f, 18f, accent)
                ink.color = Color.rgb(24, 65, 46)
                ink.textSize = 25f
                canvas.drawText("Étape observée", 34f, fixture.height * .82f, ink)
            }
        }
        return bitmap
    }

    private companion object { const val TAG = "NoteExportFixture" }
}
