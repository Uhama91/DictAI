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
    @Test fun clipboardCaptureNeedsNoNoteAndKeepsASmallPreviewForOptionalNotes() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val dir = File(app.cacheDir, "clipboard-test-$id").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("test-$id-$name", mode)
        }
        val store = NoteImageStore(context)
        try {
            // More than the old ten-image note limit; each gesture is an independent clipboard item.
            repeat(11) {
                val pending = store.beginClipboard(NoteImageKind.SCREENSHOT, true)
                assertTrue(pending.clipboardOnly)
                assertEquals("", pending.noteId)
                val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
                try { store.store(pending.id, bitmap) } finally { bitmap.recycle() }
                val restored = NoteImageStore(context).pending()!!
                assertTrue(restored.clipboardOnly)
                assertNotNull(restored.image)
                assertTrue(store.file(pending.id).length() > 0)
                assertTrue(store.thumbnail(pending.id).exists())
                store.clearPending(pending.id)
                store.delete(pending.id)
            }
        } finally {
            dir.deleteRecursively()
            app.deleteSharedPreferences("test-$id-note_capture")
        }
    }

    @Test fun galleryCopySurvivesTheDraftAndNoteWithTheSameJpegBytes() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val dir = File(app.cacheDir, "gallery-test-$id").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("test-$id-$name", mode)
        }
        var galleryUri: android.net.Uri? = null
        try {
            val store = NoteImageStore(context)
            val draft = DictationDraftStore(context)
            draft.save("Avant")
            val pending = store.beginClipboard(NoteImageKind.CAMERA, false)
            assertTrue(draft.reserveCapture(pending.id, 0))
            val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try { store.store(pending.id, bitmap) } finally { bitmap.recycle() }
            val image = store.pending()!!.image!!
            draft.completeCapture(image)
            draft.save("Avant après")
            val restored = DictationDraftStore(context)
            assertEquals(5, restored.captures().single().offset)
            val jpeg = store.file(image.id).readBytes()
            galleryUri = CapturedImageGallery.save(context, image)
            assertEquals("image/jpeg", app.contentResolver.getType(galleryUri))
            app.contentResolver.query(galleryUri, arrayOf(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.provider.MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Pictures/DictAI/", it.getString(0)); assertEquals(0, it.getInt(1))
            }
            val content = DraftImageContext.materialize(restored.load()!!, emptyList(), restored.captures())
            val notes = TranscriptNotes(AndroidTranscriptNoteStorage(context))
            val note = notes.save(null, content.text, content.images)
            restored.detachCaptures(content.attachedIds)
            restored.clear()
            assertTrue(store.file(image.id).isFile)
            notes.delete(note.id)
            assertFalse(store.file(image.id).exists())
            assertArrayEquals(jpeg, app.contentResolver.openInputStream(galleryUri)!!.use { it.readBytes() })
        } finally {
            galleryUri?.let { app.contentResolver.delete(it, null, null) }
            dir.deleteRecursively()
            listOf("transcript_notes", "note_capture", "dictation_draft").forEach { app.deleteSharedPreferences("test-$id-$it") }
        }
    }

}
