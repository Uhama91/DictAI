package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceImageBatchRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService
    private lateinit var draftStore: DictationDraftStore

    @Before fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        listOf("dictation_draft", "note_capture", "transcript_notes", "note_folders")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        draftStore = DictationDraftStore(context)
        draftStore.purpose = DictationPurpose.MESSAGE
        draftStore.save("Avant Après")
        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
        invoke(service, "showButton")
    }

    @After fun tearDown() {
        runCatching { controller.destroy() }
        NoteImageStore(context).pending()?.let { pending -> runCatching { NoteImageStore(context).clearPending(pending.id) } }
        listOf("dictation_draft", "note_capture", "transcript_notes", "note_folders")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        ShadowSettings.reset()
    }

    @Test fun messageBatchReservesOriginalCaretAndMoveSurvivesDraftReconstruction() {
        setEnumField(service, "state", "PAUSED")
        setField(service, "purpose", DictationPurpose.MESSAGE)
        setField<String?>(service, "activeNoteId", null)
        val editor = field<OverlayTranscriptEditor>(service, "liveText")
        val renderer = TranscriptImageBlockRenderer(context)
        renderer.render(editor, TranscriptImageBlocks.fromBlocks("Avant Après", emptyList()), 5, 5)
        editor.setSelection(5)
        invoke(service, "captureNoteImage", NoteImageKind.CAMERA)

        val store = NoteImageStore(context)
        val pending = requireNotNull(store.pending())
        assertTrue(pending.batch)
        assertEquals("", pending.noteId)
        assertTrue(pending.clipboardOnly)
        assertEquals(DictationPurpose.MESSAGE, field(service, "purpose"))
        val firstBitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(32, 118, 82)) }
        val secondBitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(48, 86, 150)) }
        try {
            store.store(pending.id, firstBitmap, NoteImageKind.CAMERA)
            store.store(pending.id, secondBitmap, NoteImageKind.CAMERA)
        } finally {
            firstBitmap.recycle()
            secondBitmap.recycle()
        }
        RobolectricImageDecoderWarmup.decode(store.thumbnail(requireNotNull(store.pending()).allImages.first().id))
        store.acceptBatch(pending.id)
        invoke(service, "finishPendingImage")

        val captured = renderer.read(editor)
        assertEquals("Avant Après", captured.rawText())
        assertEquals(1, captured.blocks.size)
        assertEquals(2, captured.blocks.single().images.size)
        assertEquals(pending.id, captured.blocks.single().id)
        assertEquals(5, captured.blocks.single().rawOffset)
        assertEquals(2, draftStore.captures().count { it.image != null })
        awaitDelivery()
        assertEquals(DictationPurpose.MESSAGE, field(service, "purpose"))
        val savedNoteId = requireNotNull(field<String?>(service, "activeNoteId"))
        val savedNote = requireNotNull(TranscriptNotes(AndroidTranscriptNoteStorage(context)).get(savedNoteId))
        assertEquals(captured.blocks.single().images.map { it.id }, savedNote.images.map { it.id })

        val block = captured.blocks.single()
        assertTrue(editor.moveImageBlockToEditorOffset(block.id, editor.length()))
        val rebuilt = invokeValue(service, "storedTranscriptProjection", null as String?) as TranscriptImageProjection
        assertEquals("Avant Après", rebuilt.rawText())
        assertEquals(rebuilt.rawText().length, rebuilt.blocks.single().rawOffset)
        assertEquals(block.images.map { it.id }, rebuilt.blocks.single().images.map { it.id })
        assertEquals(0, draftStore.captures().count { it.image != null })

        val width = (320 * context.resources.displayMetrics.density).toInt()
        val height = (104 * context.resources.displayMetrics.density).toInt()
        editor.measure(exact(width), exact(height))
        editor.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(248, 246, 237))
            editor.draw(Canvas(bitmap))
            val output = File("build/reports/media/overlay-image-editor.png").canonicalFile
            output.parentFile?.mkdirs()
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.isFile && output.length() > 1_000L)
        } finally { bitmap.recycle() }
    }

    private fun awaitDelivery() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (field<Boolean>(service, "imageDeliveryBusy") && System.nanoTime() < deadline) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
        assertFalse("gallery delivery callback finishes", field(service, "imageDeliveryBusy"))
        while (Thread.getAllStackTraces().keys.any { it.name == "dictai-note-thumbnail" && it.isAlive } &&
            System.nanoTime() < deadline) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
        assertFalse("native thumbnail decoders finish before service teardown",
            Thread.getAllStackTraces().keys.any { it.name == "dictai-note-thumbnail" && it.isAlive })
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        @Suppress("UNCHECKED_CAST")
        get(target) as T
    }

    private fun <T> setField(target: Any, name: String, value: T) {
        target.javaClass.getDeclaredField(name).run { isAccessible = true; set(target, value) }
    }

    private fun setEnumField(target: Any, name: String, enumName: String) {
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val value = requireNotNull(field.type.enumConstants).single { (it as Enum<*>).name == enumName }
        field.set(target, value)
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
    }

    private fun invokeValue(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
}
