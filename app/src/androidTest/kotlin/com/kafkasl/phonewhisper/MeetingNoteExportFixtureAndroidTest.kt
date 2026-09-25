package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingParticipant
import com.kafkasl.phonewhisper.meeting.MeetingTurn
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real native PDF/HTML/text export of a synthetic meeting and two private fixture images. */
@RunWith(AndroidJUnit4::class)
class MeetingNoteExportFixtureAndroidTest {
    @Test
    fun exportsProjectedMeetingTextAndImagesWithoutStaleCacheOrIgnoredSpeech() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(BuildConfig.MEETING_PROTOTYPE && app.packageName == "com.uhama.whisperpin.meetingtest") {
            "Export fixture requires the isolated meeting prototype application"
        }
        val fixtureId = UUID.randomUUID().toString()
        val privateRoot = File(app.cacheDir, "meeting-export-$fixtureId").apply {
            check(mkdirs() || isDirectory)
        }
        val isolatedContext = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(privateRoot, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(privateRoot, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("meeting-export-$fixtureId-$name", mode)
        }
        val store = NoteImageStore(isolatedContext)
        var batchId: String? = null

        val artifactBase = app.getExternalFilesDir("meeting-export-fixtures")
            ?: error("Répertoire privé d’artefacts indisponible")
        val artifactDir = File(artifactBase, "meeting-note-$fixtureId").apply {
            check(mkdirs() || isDirectory)
        }

        try {
            val batch = store.beginBatch(
                kind = NoteImageKind.SCREENSHOT,
                resume = false,
                number = 1,
                maxImages = 2,
            )
            batchId = batch.id
            listOf(
                SyntheticImage("Plan de salle", PLAN_GREEN, isRoomPlan = true),
                SyntheticImage("Liste du matériel", MATERIAL_BLUE, isRoomPlan = false),
            ).forEach { fixture ->
                val bitmap = drawSyntheticImage(fixture)
                try {
                    store.store(batch.id, bitmap, NoteImageKind.SCREENSHOT)
                } finally {
                    bitmap.recycle()
                }
            }

            val images = requireNotNull(store.pending()) { "Réservation image absente" }.images
            assertEquals(listOf(1, 2), images.map { it.number })
            assertTrue(images.all { store.file(it.id).isFile && store.file(it.id).length() > 0L })

            val document = MeetingDocument(
                sessionId = fixtureId,
                runId = "run-$fixtureId",
                participants = listOf(
                    MeetingParticipant("sophie", ordinal = 1, channel = 1, name = "Sophie"),
                    MeetingParticipant("karim", ordinal = 2, channel = 2, name = "Karim"),
                    MeetingParticipant("ignored", ordinal = 3, channel = 3, name = "Personne 3", ignored = true),
                ),
                turns = listOf(
                    MeetingTurn(
                        id = "utterance-1",
                        utteranceId = 1,
                        startMs = 0,
                        endMs = 1_800,
                        recognizedText = "La séance aura lieu mardi.",
                        automaticParticipantId = "sophie",
                        attributionStable = true,
                        editedText = "La séance aura lieu jeudi.",
                    ),
                    MeetingTurn(
                        id = "utterance-2",
                        utteranceId = 2,
                        startMs = 2_000,
                        endMs = 3_600,
                        recognizedText = "Je prépare les documents.",
                        automaticParticipantId = "karim",
                        attributionStable = true,
                    ),
                    MeetingTurn(
                        id = "utterance-3",
                        utteranceId = 3,
                        startMs = 3_800,
                        endMs = 5_400,
                        recognizedText = "Nous vérifierons ensemble le matériel.",
                        automaticParticipantId = "sophie",
                        attributionStable = true,
                    ),
                    MeetingTurn(
                        id = "utterance-4",
                        utteranceId = 4,
                        startMs = 5_600,
                        endMs = 6_400,
                        recognizedText = "BRUIT À MASQUER [[Image 1]]",
                        automaticParticipantId = "ignored",
                        attributionStable = true,
                    ),
                    MeetingTurn(
                        id = "utterance-5",
                        utteranceId = 5,
                        startMs = 6_600,
                        endMs = 7_300,
                        recognizedText = "La salle reste à confirmer.",
                        automaticParticipantId = "ignored",
                        attributionStable = false,
                    ),
                    MeetingTurn(
                        id = "document-turn",
                        utteranceId = 0,
                        startMs = 0,
                        endMs = 0,
                        recognizedText = "Annexe de la réunion [[Image 2]]",
                        automaticParticipantId = null,
                        attributionStable = false,
                    ),
                ),
                finished = true,
            )
            val note = TranscriptNote(
                id = fixtureId,
                title = "Réunion de préparation",
                text = "CACHE OBSOLÈTE À EXCLURE",
                updatedAt = 1_790_290_000_000L,
                images = images,
                meeting = document,
            )

            val expectedText = expectedShareText()
            val shareText = NoteShareText.create(note)
            val shareFile = File(artifactDir, "meeting-share.txt").apply { writeText(shareText) }
            val expectedFile = File(artifactDir, "meeting-share-expected.txt").apply { writeText(expectedText) }
            assertEquals("TXT uses the exact projected transcript, not the cached text", expectedText, shareText)
            assertFalse(shareText.contains("mardi"))
            assertFalse(shareText.contains("BRUIT À MASQUER"))
            assertFalse(shareText.contains("CACHE OBSOLÈTE"))
            assertEquals(2, shareText.lines().count { it == "Sophie" })
            assertEquals(1, shareText.lines().count { it == "Karim" })
            assertEquals(1, shareText.lines().count { it == "Intervenant à confirmer" })
            assertTrue(shareText.indexOf("[[Image 1]]") < shareText.indexOf("[[Image 2]]"))

            val htmlFile = File(artifactDir, "meeting-note.html")
            htmlFile.outputStream().buffered().use { output ->
                NoteHtmlExport.write(note, output) { image -> store.file(image.id).inputStream() }
            }
            val html = htmlFile.readText(Charsets.UTF_8)
            assertTrue(html.startsWith("<!doctype html>"))
            assertTrue(html.contains("<html lang=\"fr\">"))
            assertTrue(html.contains("<main><h1>Réunion de préparation</h1>"))
            assertTrue(html.contains("</main></body></html>"))
            assertTrue(html.contains("Content-Security-Policy"))
            assertEquals(2, html.occurrences("<figure id=\"image-"))
            assertEquals(2, html.occurrences("data:image/jpeg;base64,"))
            assertTrue(html.indexOf("id=\"image-1\"") < html.indexOf("id=\"image-2\""))
            assertTrue(html.indexOf("id=\"image-1\"") < html.indexOf("La salle reste à confirmer."))
            assertTrue(html.indexOf("La salle reste à confirmer.") < html.indexOf("id=\"image-2\""))
            assertEquals(2, html.occurrences("Sophie"))
            assertEquals(1, html.occurrences("Karim"))
            assertEquals(1, html.occurrences("Intervenant à confirmer"))
            assertFalse(html.contains("mardi"))
            assertFalse(html.contains("BRUIT À MASQUER"))
            assertFalse(html.contains("CACHE OBSOLÈTE"))
            assertFalse(html.contains("http://"))
            assertFalse(html.contains("https://"))
            assertFalse(html.contains("<script"))

            val pdfFile = File(artifactDir, "meeting-note.pdf")
            pdfFile.outputStream().buffered().use { output -> NotePdfExport.write(note, output, store) }
            assertTrue("Native PDF is non-empty", pdfFile.isFile && pdfFile.length() > MIN_PDF_BYTES)
            val rendered = renderPdfAt150Dpi(pdfFile, File(artifactDir, "pdf-pages-150dpi"))
            assertTrue("PDF page count is bounded", rendered.pageCount in 1..MAX_PDF_PAGES)
            assertEquals(rendered.pageCount, rendered.pageFiles.size)
            assertTrue(rendered.pageFiles.all { it.isFile && it.length() > MIN_PAGE_PNG_BYTES })
            assertTrue("PDF contains rendered page content", rendered.nonWhiteSamples.all { it > MIN_NON_WHITE_SAMPLES })
            assertTrue("The ignored speaker's first attached image remains in the PDF", rendered.planColorSamples > MIN_COLOR_SAMPLES)
            assertTrue("The documentary turn's second image remains in the PDF", rendered.materialColorSamples > MIN_COLOR_SAMPLES)
            assertTrue(
                "PDF images follow marker order 1 then 2",
                comparePosition(rendered.planPosition, rendered.materialPosition) < 0,
            )

            Log.i(
                TAG,
                "status=pass artifactDir=${artifactDir.absolutePath} " +
                    "txtBytes=${shareFile.length()} expectedTxtBytes=${expectedFile.length()} " +
                    "htmlBytes=${htmlFile.length()} pdfBytes=${pdfFile.length()} " +
                    "images=${images.size} pdfPages=${rendered.pageCount} renderDpi=$RENDER_DPI " +
                    "pagePngs=${rendered.pageFiles.size}",
            )
        } finally {
            batchId?.let { runCatching { store.clearPending(it) } }
            privateRoot.deleteRecursively()
            app.deleteSharedPreferences("meeting-export-$fixtureId-note_capture")
        }
    }

    private fun expectedShareText(): String = """
        Réunion de préparation

        Sophie
        La séance aura lieu jeudi.

        Karim
        Je prépare les documents.

        Sophie
        Nous vérifierons ensemble le matériel.

        [[Image 1]]

        Intervenant à confirmer
        La salle reste à confirmer.

        Annexe de la réunion [[Image 2]]

        Images jointes — les repères [[Image N]] situent chaque image dans la note.
        Image 1 : Capture d’écran
        Image 2 : Capture d’écran
    """.trimIndent() + "\n"

    private fun drawSyntheticImage(fixture: SyntheticImage): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 251, 248))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(37, 43, 51)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 64f
        canvas.drawText(fixture.title, 60f, 112f, paint)

        paint.color = fixture.accentColor
        canvas.drawRoundRect(RectF(42f, 155f, 1_158f, 755f), 28f, 28f, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 8f
        paint.style = Paint.Style.STROKE
        if (fixture.isRoomPlan) {
            canvas.drawRect(RectF(110f, 225f, 1_090f, 690f), paint)
            canvas.drawLine(600f, 225f, 600f, 690f, paint)
            canvas.drawLine(110f, 458f, 1_090f, 458f, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("Entrée", 135f, 275f, paint)
            canvas.drawText("Tables", 640f, 275f, paint)
            canvas.drawCircle(350f, 350f, 34f, paint)
            canvas.drawCircle(835f, 350f, 34f, paint)
            canvas.drawCircle(350f, 570f, 34f, paint)
            canvas.drawCircle(835f, 570f, 34f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            for (index in 0..2) {
                val top = 245f + index * 155f
                canvas.drawRect(RectF(120f, top, 175f, top + 55f), paint)
            }
            paint.style = Paint.Style.FILL
            paint.textSize = 36f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("Bloc-notes", 220f, 290f, paint)
            canvas.drawText("Marqueurs", 220f, 445f, paint)
            canvas.drawText("Supports", 220f, 600f, paint)
        }
        return bitmap
    }

    private fun renderPdfAt150Dpi(pdfFile: File, outputDirectory: File): PdfArtifactSummary {
        check(outputDirectory.mkdirs() || outputDirectory.isDirectory)
        return ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue("PDF exporter must not create an unbounded document", renderer.pageCount in 1..MAX_PDF_PAGES)
                val pageFiles = mutableListOf<File>()
                val nonWhiteSamples = mutableListOf<Int>()
                var planColorSamples = 0
                var materialColorSamples = 0
                var planPosition: PixelPosition? = null
                var materialPosition: PixelPosition? = null

                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val scale = RENDER_DPI / PDF_POINTS_PER_INCH
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt(),
                            (page.height * scale).toInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            nonWhiteSamples += countSamples(bitmap) { color -> color != Color.WHITE }

                            val plan = scanColor(bitmap) { red, green, blue ->
                                green > red + 50 && green > blue + 30
                            }
                            planColorSamples += plan.samples
                            if (plan.samples > 0 && planPosition == null) planPosition = PixelPosition(index, plan.firstY)

                            val material = scanColor(bitmap) { red, green, blue ->
                                blue > red + 60 && blue > green + 45
                            }
                            materialColorSamples += material.samples
                            if (material.samples > 0 && materialPosition == null) {
                                materialPosition = PixelPosition(index, material.firstY)
                            }

                            val pageFile = File(outputDirectory, "page-${(index + 1).toString().padStart(2, '0')}-$RENDER_DPI-dpi.png")
                            FileOutputStream(pageFile).use { output ->
                                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                            }
                            pageFiles += pageFile
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                PdfArtifactSummary(
                    pageCount = renderer.pageCount,
                    pageFiles = pageFiles,
                    nonWhiteSamples = nonWhiteSamples,
                    planColorSamples = planColorSamples,
                    materialColorSamples = materialColorSamples,
                    planPosition = requireNotNull(planPosition) { "Plan image color not found in PDF pages" },
                    materialPosition = requireNotNull(materialPosition) { "Materials image color not found in PDF pages" },
                )
            }
        }
    }

    private fun countSamples(bitmap: Bitmap, matches: (Int) -> Boolean): Int {
        var samples = 0
        for (y in 0 until bitmap.height step PIXEL_SAMPLE_STEP) {
            for (x in 0 until bitmap.width step PIXEL_SAMPLE_STEP) {
                if (matches(bitmap.getPixel(x, y))) samples++
            }
        }
        return samples
    }

    private fun scanColor(bitmap: Bitmap, matches: (Int, Int, Int) -> Boolean): ColorScan {
        var samples = 0
        var firstY = -1
        for (y in 0 until bitmap.height step PIXEL_SAMPLE_STEP) {
            var rowMatches = 0
            for (x in 0 until bitmap.width step PIXEL_SAMPLE_STEP) {
                val color = bitmap.getPixel(x, y)
                if (matches(Color.red(color), Color.green(color), Color.blue(color))) {
                    samples++
                    rowMatches++
                }
            }
            if (firstY < 0 && rowMatches >= MIN_COLOR_ROW_SAMPLES) firstY = y
        }
        return ColorScan(samples, firstY)
    }

    private fun comparePosition(first: PixelPosition, second: PixelPosition): Int =
        if (first.page != second.page) first.page.compareTo(second.page) else first.y.compareTo(second.y)

    private fun String.occurrences(value: String): Int = windowed(value.length).count { it == value }

    private data class SyntheticImage(val title: String, val accentColor: Int, val isRoomPlan: Boolean)
    private data class ColorScan(val samples: Int, val firstY: Int)
    private data class PixelPosition(val page: Int, val y: Int)
    private data class PdfArtifactSummary(
        val pageCount: Int,
        val pageFiles: List<File>,
        val nonWhiteSamples: List<Int>,
        val planColorSamples: Int,
        val materialColorSamples: Int,
        val planPosition: PixelPosition,
        val materialPosition: PixelPosition,
    )

    private companion object {
        const val TAG = "MeetingNoteExportFixture"
        const val IMAGE_WIDTH = 1_200
        const val IMAGE_HEIGHT = 800
        const val PLAN_GREEN = 0xff1f8352.toInt()
        const val MATERIAL_BLUE = 0xff2857aa.toInt()
        const val PDF_POINTS_PER_INCH = 72f
        const val RENDER_DPI = 150
        const val PIXEL_SAMPLE_STEP = 4
        const val MIN_COLOR_ROW_SAMPLES = 4
        const val MIN_COLOR_SAMPLES = 100
        const val MIN_NON_WHITE_SAMPLES = 100
        const val MIN_PDF_BYTES = 10_000L
        const val MIN_PAGE_PNG_BYTES = 10_000L
        const val MAX_PDF_PAGES = 8
    }
}
