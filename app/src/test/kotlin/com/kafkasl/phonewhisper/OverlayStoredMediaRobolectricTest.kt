package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayStoredMediaRobolectricTest {
    private lateinit var context: Context
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService
    private lateinit var imageStore: NoteImageStore
    private lateinit var note: TranscriptNote
    private lateinit var images: List<NoteImage>
    private val preferencesBefore = mutableMapOf<String, Map<String, Any?>>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf("transcript_notes", "transcript_note_folders", "dictation_draft", "note_capture", "dictai_formats")
            .forEach(::snapshotAndClear)
        context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE).edit()
            .putString("selected", "cleanup").commit()
        ShadowSettings.setCanDrawOverlays(true)

        imageStore = NoteImageStore(context)
        images = listOf(
            NoteImage(UUID.randomUUID().toString(), 7, NoteImageKind.CAMERA, 1_790_000_000_001L, 640, 480),
            NoteImage(UUID.randomUUID().toString(), 8, NoteImageKind.SCAN, 1_790_000_000_002L, 700, 980),
        )
        images.forEachIndexed { index, image -> writeImage(image, if (index == 0) Color.GREEN else Color.BLUE) }
        RobolectricImageDecoderWarmup.decode(imageStore.thumbnail(images.first().id))
        note = TranscriptNote(
            id = UUID.randomUUID().toString(),
            title = "Note historique",
            text = "Avant [[Image 7]][[Image 8]] après.",
            updatedAt = 1_790_000_000_000L,
            renamed = true,
            images = images,
        )
        AndroidTranscriptNoteStorage(context).put(note)

        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
        // Some Robolectric hosts decline the service's foreground start; the normal overlay
        // setup is still created by showButton(), as in OverlayServiceGestureRobolectricTest.
        invoke(service, "showButton")
        invoke(service, "openNote", note)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        images.forEach { imageStore.delete(it.id) }
        context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).edit().remove(note.id).commit()
        preferencesBefore.entries.forEach { entry -> restore(entry.key, entry.value) }
        ShadowSettings.reset()
    }

    @Test
    fun historicalNoteOpensAsNativeBlockAndKeepsImagesThroughEditSaveAndReopen() {
        val editor = field<OverlayTranscriptEditor>(service, "liveText")
        val renderer = TranscriptImageBlockRenderer(context)
        assertEquals("Avant ${TranscriptImageBlocks.OBJECT_REPLACEMENT} après.", editor.text.toString())
        assertFalse(editor.text.contains("[[Image"))
        assertEquals("Avant  après.", renderer.read(editor).rawText())
        assertBlock(editor, images)

        editor.text.insert(0, "Contexte relu : ")
        editor.text.insert(editor.length(), " suite.")
        val rawText = renderer.read(editor).rawText()
        assertEquals("Contexte relu : Avant  après. suite.", rawText)

        val saved = invokeValue(service, "saveNoteWithCaptures", rawText) as TranscriptNote
        assertEquals(note.id, saved.id)
        assertEquals(images, saved.images)
        assertEquals(
            "Contexte relu : Avant [[Image 7]][[Image 8]] après. suite.",
            storedNote(note.id)?.text,
        )

        invoke(service, "openNote", saved)
        val reopened = field<OverlayTranscriptEditor>(service, "liveText")
        assertEquals("Contexte relu : Avant ${TranscriptImageBlocks.OBJECT_REPLACEMENT} après. suite.", reopened.text.toString())
        assertEquals(rawText, renderer.read(reopened).rawText())
        assertBlock(reopened, images)
        assertEquals(2, field<LinearLayout>(service, "imageStrip").childCount)
    }

    @Test
    fun deletingNativeImageBlockRemovesItsReferencesAndImagesFromTheNoteAndStrip() {
        val editor = field<OverlayTranscriptEditor>(service, "liveText")
        assertBlock(editor, images)
        val spanned = editor.text as Spanned
        val span = spanned.getSpans(0, spanned.length, OverlayImageBlockSpan::class.java).single()
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        editor.text.delete(start, end)
        ShadowLooper.idleMainLooper()

        val persisted = storedNote(note.id)
        assertTrue("deleting the one native block removes both grouped attachments", persisted?.images.isNullOrEmpty())
        assertTrue("raw note text no longer contains serialized image markers", NoteImageMarkers.markers(persisted?.text.orEmpty()).isEmpty())
        assertEquals("Avant  après.", persisted?.text)
        assertFalse(editor.text.contains(TranscriptImageBlocks.OBJECT_REPLACEMENT))
        assertEquals(View.GONE, field<android.widget.HorizontalScrollView>(service, "imageStripScroll").visibility)
        val strip = field<LinearLayout>(service, "imageStrip")
        assertTrue("the hidden strip has no image thumbnail frames",
            (0 until strip.childCount).none { strip.getChildAt(it) is android.widget.FrameLayout })
    }

    private fun assertBlock(editor: OverlayTranscriptEditor, expectedImages: List<NoteImage>) {
        val spans = (editor.text as Spanned).getSpans(0, editor.length(), OverlayImageBlockSpan::class.java)
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals(expectedImages.map { it.id }, span.block.images.map { it.id })
        assertEquals(expectedImages, span.block.images)
        assertEquals(1, editor.text.count { it == TranscriptImageBlocks.OBJECT_REPLACEMENT })
    }

    private fun writeImage(image: NoteImage, color: Int) {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try {
            listOf(imageStore.file(image.id), imageStore.thumbnail(image.id)).forEach { file ->
                file.parentFile?.mkdirs()
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun storedNote(id: String): TranscriptNote? =
        AndroidTranscriptNoteStorage(context).all().firstOrNull { it.id == id }

    private fun snapshotAndClear(name: String) {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        preferencesBefore[name] = prefs.all.toMap()
        prefs.edit().clear().commit()
    }

    private fun restore(name: String, values: Map<String, Any?>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(target) as T
        }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first {
            it.name == name && it.parameterTypes.size == args.size
        }
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private fun invokeValue(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first {
            it.name == name && it.parameterTypes.size == args.size
        }
        method.isAccessible = true
        return method.invoke(target, *args)
    }
}
