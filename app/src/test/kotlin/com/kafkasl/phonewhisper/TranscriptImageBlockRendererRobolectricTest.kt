package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptImageBlockRendererRobolectricTest {
    @Test
    fun groupedImagesRoundTripThroughNeighborEditsAndDeletingObjectRemovesBlock() {
        val context = RuntimeEnvironment.getApplication()
        val store = NoteImageStore(context)
        val images = createImages(store)
        val original = "Avant [[Image 1]][[Image 2]] après."
        val projection = TranscriptImageBlocks.fromNote(original, images)
        assertEquals(1, projection.blocks.size)
        assertEquals(images, projection.blocks.single().images)
        assertEquals("Avant ${TranscriptImageBlocks.OBJECT_REPLACEMENT} après.", projection.editorText)

        val editor = EditText(context)
        val renderer = TranscriptImageBlockRenderer(context)
        try {
            renderer.render(editor, projection)
            assertEquals(original, renderer.read(editor).serializedNoteText())

            val editable = editor.text
            editable.insert(0, "Début ")
            editable.insert(editable.length, " fin")
            val afterNeighborEdits = renderer.read(editor)
            assertEquals("Début Avant  après. fin", afterNeighborEdits.rawText())
            assertEquals("Début Avant [[Image 1]][[Image 2]] après. fin", afterNeighborEdits.serializedNoteText())
            assertEquals(images, afterNeighborEdits.blocks.single().images)

            val spanned = editor.text as Spanned
            val imageSpan = spanned.getSpans(0, spanned.length, OverlayImageBlockSpan::class.java).single()
            val start = spanned.getSpanStart(imageSpan)
            val end = spanned.getSpanEnd(imageSpan)
            assertEquals(TranscriptImageBlocks.OBJECT_REPLACEMENT, spanned[start])
            assertEquals(start + 1, end)
            editor.text.delete(start, end)

            val afterDelete = renderer.read(editor)
            assertTrue(afterDelete.blocks.isEmpty())
            assertFalse(editor.text.contains(TranscriptImageBlocks.OBJECT_REPLACEMENT))
            assertEquals("Début Avant  après. fin", afterDelete.serializedNoteText())
        } finally {
            images.forEach { store.delete(it.id) }
        }
    }

    @Test
    fun spanCacheRefreshesWhenGroupedImagesChangeAndAsrAppendPreservesOtherSpans() {
        val context = RuntimeEnvironment.getApplication()
        val store = NoteImageStore(context)
        val images = createImages(store)
        val blockId = "draft:${UUID.randomUUID()}"
        val rawText = "Transcription en cours "
        val fullBlock = TranscriptImageBlock(blockId, images, rawText.length)
        val fullProjection = TranscriptImageBlocks.fromBlocks(rawText, listOf(fullBlock))
        val editor = EditText(context)
        val renderer = TranscriptImageBlockRenderer(context)
        try {
            renderer.render(editor, fullProjection)
            val firstSpan = (editor.text as Spanned)
                .getSpans(0, editor.length(), OverlayImageBlockSpan::class.java).single()

            val reducedBlock = fullBlock.copy(images = listOf(images.first()))
            val reducedProjection = TranscriptImageBlocks.fromBlocks(rawText, listOf(reducedBlock))
            renderer.render(editor, reducedProjection)
            val refreshedSpan = (editor.text as Spanned)
                .getSpans(0, editor.length(), OverlayImageBlockSpan::class.java).single()
            assertNotSame(firstSpan, refreshedSpan)
            assertEquals(listOf(images.first()), refreshedSpan.block.images)

            val text = editor.text as Editable
            val styled = StyleSpan(android.graphics.Typeface.BOLD)
            val colored = ForegroundColorSpan(Color.rgb(30, 90, 150))
            val composing = UnderlineSpan()
            text.setSpan(styled, 0, "Transcription".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val composingStart = text.indexOf("en cours")
            val composingEnd = composingStart + "en cours".length
            text.setSpan(composing, composingStart, composingEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
            text.setSpan(colored, composingEnd, rawText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val withAsr = TranscriptImageBlocks.fromBlocks(
                rawText + "audio reconnue",
                reducedProjection.blocks,
            )
            renderer.render(editor, withAsr)

            assertEquals("Transcription en cours audio reconnue", renderer.read(editor).rawText())
            val renderedText = editor.text as Spanned
            assertSame(styled, renderedText.getSpans(0, renderedText.length, StyleSpan::class.java).single())
            assertSame(composing, renderedText.getSpans(0, renderedText.length, UnderlineSpan::class.java).single())
            assertSame(colored, renderedText.getSpans(0, renderedText.length, ForegroundColorSpan::class.java).single())
            assertEquals(composingStart, renderedText.getSpanStart(composing))
            assertEquals(composingEnd, renderedText.getSpanEnd(composing))
            assertTrue((renderedText.getSpanFlags(composing) and Spanned.SPAN_COMPOSING) != 0)
            assertEquals(1, renderedText.getSpans(0, renderedText.length, OverlayImageBlockSpan::class.java).size)
        } finally {
            images.forEach { store.delete(it.id) }
        }
    }

    @Test
    fun groupedBlockRendersAsACompactNativeInlinePreview() {
        val context = RuntimeEnvironment.getApplication()
        val store = NoteImageStore(context)
        val images = createImages(store)
        val projection = TranscriptImageBlocks.fromNote(
            "Avant [[Image 1]][[Image 2]] après",
            images,
        )
        val editor = EditText(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.rgb(32, 35, 41))
            setBackgroundColor(Color.WHITE)
            setPadding(12, 10, 12, 10)
            setSingleLine(true)
        }
        try {
            TranscriptImageBlockRenderer(context).render(editor, projection)
            val width = (420 * context.resources.displayMetrics.density).toInt()
            editor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((120 * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.AT_MOST),
            )
            editor.layout(0, 0, editor.measuredWidth, editor.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, editor.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                editor.draw(Canvas(bitmap))
                val output = File("build/reports/media/renderer-inline-image-block.png").canonicalFile
                output.parentFile?.mkdirs()
                output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                var nonWhitePixels = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) != Color.WHITE) nonWhitePixels++
                }
                assertTrue("The native inline block should be rendered in the preview", nonWhitePixels > 500)
                assertTrue("The chip should stay compact in a single editor line", bitmap.height < (100 * context.resources.displayMetrics.density))
                assertTrue(output.isFile && output.length() > 1_000L)
            } finally { bitmap.recycle() }
        } finally {
            images.forEach { store.delete(it.id) }
        }
    }

    private fun createImages(store: NoteImageStore): List<NoteImage> = listOf(
        NoteImage(UUID.randomUUID().toString(), 1, NoteImageKind.CAMERA, 1_790_000_000_001L, 640, 480),
        NoteImage(UUID.randomUUID().toString(), 2, NoteImageKind.SCAN, 1_790_000_000_002L, 700, 980),
    ).onEachIndexed { index, image ->
        val thumbnail = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        try {
            thumbnail.eraseColor(if (index == 0) Color.rgb(38, 125, 87) else Color.rgb(50, 90, 150))
            val file = store.thumbnail(image.id)
            file.parentFile?.mkdirs()
            file.outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { thumbnail.recycle() }
    }
}
