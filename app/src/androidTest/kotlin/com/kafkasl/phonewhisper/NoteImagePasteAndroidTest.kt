package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Controlled native receiver only; this cannot certify ChatGPT, Claude or messaging apps. */
@RunWith(AndroidJUnit4::class)
class NoteImagePasteAndroidTest {
    @Test fun nativeReceiverReadsJpegFromAnImageOnlyClip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "note_exports/test-paste-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(directory, "Image-1.jpg")
            val bitmap = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { bitmap.recycle() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.note_files", file)
            val clip = NoteImagePaste.imageClip(uri)
            assertEquals("image/jpeg", clip.description.getMimeType(0))
            assertEquals(uri, clip.getItemAt(0).uri)
            assertEquals(1, clip.itemCount)
            assertNull(clip.getItemAt(0).text)
            assertNull(clip.getItemAt(0).intent)
            var received: Uri? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val editor = EditText(context).apply { setText("Texte à conserver"); setSelection(length()) }
                ViewCompat.setOnReceiveContentListener(editor, arrayOf("image/*")) { _, payload ->
                    received = payload.clip.getItemAt(0).uri
                    null
                }
                assertNull(ViewCompat.performReceiveContent(editor, ContentInfoCompat.Builder(clip, ContentInfoCompat.SOURCE_CLIPBOARD).build()))
                assertEquals("Texte à conserver", editor.text.toString())
            }
            assertEquals(uri, received)
            assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(received!!)!!.use { it.readBytes() })
        } finally { directory.deleteRecursively() }
    }
    @Test fun copyingWithoutAnyEditorReplacesTheCurrentImageOneAtATime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val directory = File(context.cacheDir, "note_exports/test-clipboard-${UUID.randomUUID()}").apply { mkdirs() }
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        try {
            val uris = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
                val file = File(directory, "Image-$index.jpg")
                val bitmap = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                finally { bitmap.recycle() }
                FileProvider.getUriForFile(context, "${context.packageName}.note_files", file)
            }
            for (uri in uris) instrumentation.runOnMainSync {
                assertTrue(NoteImagePaste.copy(context, uri))
                val clip = clipboard.primaryClip!!
                assertEquals(1, clip.itemCount)
                assertTrue(clip.description.hasMimeType("image/jpeg"))
                assertNull(clip.getItemAt(0).text)
                assertEquals(uri, clip.getItemAt(0).uri)
            }
            // A keyboard that already imported the first URI must not see the next image's bytes.
            assertFalse(context.contentResolver.openInputStream(uris[0])!!.use { it.readBytes() }.contentEquals(
                context.contentResolver.openInputStream(uris[1])!!.use { it.readBytes() }))
        } finally {
            instrumentation.runOnMainSync { clipboard.clearPrimaryClip() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            directory.deleteRecursively()
        }
    }

}
