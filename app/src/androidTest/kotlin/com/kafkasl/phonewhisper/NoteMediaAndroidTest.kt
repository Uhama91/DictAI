package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Platform tests, isolated from any real notes. Require an Android device/emulator. */
@RunWith(AndroidJUnit4::class)
class NoteMediaAndroidTest {
    @Test fun captureStoragePdfAndHtmlPreserveImageAndTextOrder() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val dir = File(app.cacheDir, "note-test-$id").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("test-$id-$name", mode)
        }
        try {
            val notes = TranscriptNotes(AndroidTranscriptNoteStorage(context))
            val note = notes.save(null, "Texte avant la capture.\n[[Image 1]]\nTexte après la capture.")
            val store = NoteImageStore(context)
            // Use the reserved ordinal rather than relying on any production capture state.
            val pending = store.begin(note.copy(text = ""), NoteImageKind.SCREENSHOT, false)
            val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try { store.store(pending.id, bitmap) } finally { bitmap.recycle() }
            val image = store.pending()!!.image!!
            assertEquals(2048, image.height)
            assertTrue(store.thumbnail(image.id).length() > 0)
            val saved = notes.save(note.id, note.text, listOf(image))
            assertEquals(listOf(image), TranscriptNotes(AndroidTranscriptNoteStorage(context)).get(note.id)!!.images)
            val pdf = File(dir, "note.pdf")
            pdf.outputStream().use { NotePdfExport.write(saved, it, store) }
            val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount >= 1)
                var sawImage = false
                for (page in 0 until renderer.pageCount) renderer.openPage(page).use {
                    val rendered = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
                    try {
                        it.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        for (y in 0 until rendered.height step 5) if (Color.red(rendered.getPixel(rendered.width / 2, y)) > 200 &&
                            Color.green(rendered.getPixel(rendered.width / 2, y)) < 30) sawImage = true
                    } finally { rendered.recycle() }
                }
                assertTrue("Image must actually render inside the PDF", sawImage)
            }
            val html = File(dir, "note.html")
            html.outputStream().use { NoteHtmlExport.write(saved, it) { store.file(it.id).inputStream() } }
            val text = html.readText()
            assertTrue(text.indexOf("Texte avant") < text.indexOf("data:image/jpeg;base64,"))
            assertTrue(text.indexOf("data:image/jpeg;base64,") < text.indexOf("Texte après"))
            notes.delete(note.id)
            assertFalse(store.file(image.id).exists())
        } finally {
            dir.deleteRecursively()
            listOf("transcript_notes", "note_capture").forEach { app.deleteSharedPreferences("test-$id-$it") }
        }
    }
}
