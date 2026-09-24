package com.kafkasl.phonewhisper

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.UUID

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotBatchBarRobolectricTest {
    @Test fun barShowsEachCaptureCanRemoveItAndRendersACompactNativePreview() {
        val activityController = Robolectric.buildActivity(MediaBlocksHostActivity::class.java).setup()
        val context = activityController.get()
        val store = NoteImageStore(context)
        val images = listOf(
            image(1, NoteImageKind.SCREENSHOT),
            image(2, NoteImageKind.SCAN),
        )
        val sourceBitmaps = images.mapIndexed { index, image ->
            Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index == 0) Color.rgb(38, 125, 87) else Color.rgb(50, 90, 150))
                val file = store.thumbnail(image.id)
                file.parentFile?.mkdirs()
                file.outputStream().use { check(compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            }
        }
        RobolectricImageDecoderWarmup.decode(store.thumbnail(images.first().id))
        val bar = ScreenshotBatchBar(context)
        val pending = PendingNoteCapture(
            UUID.randomUUID().toString(), "", 1, NoteImageKind.SCREENSHOT, 1L, false,
            batch = true, images = images, maxImages = 3,
        )
        try {
            context.setContentView(bar)
            ShadowLooper.idleMainLooper()
            bar.bind(pending)
            assertTrue("the screenshot bar is attached so thumbnail posts can update it", bar.isAttachedToWindow)
            var removedId: String? = null
            bar.onRemoveImage = { removedId = it }
            val status = bar.javaClass.getDeclaredField("status").run { isAccessible = true; get(bar) as TextView }
            val scroll = bar.javaClass.getDeclaredField("thumbScroll").run { isAccessible = true; get(bar) as HorizontalScrollView }
            val thumbs = scroll.getChildAt(0) as LinearLayout
            assertEquals("Captures : 2 / 3", status.text.toString())
            assertEquals(2, thumbs.childCount)
            assertTrue(bar.captureButton.isEnabled)
            assertTrue(bar.acceptButton.isEnabled)
            (thumbs.getChildAt(0) as FrameLayout).performClick()
            assertEquals(images.first().id, removedId)

            val width = (360 * context.resources.displayMetrics.density).toInt()
            val maxHeight = (160 * context.resources.displayMetrics.density).toInt()
            waitForThumbnails(thumbs)

            // Thumbnail updates request a re-layout through the attached Activity. Finish those
            // callbacks first, then measure the floating bar against its compact window bounds.
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
            )
            bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)

            val bitmap = Bitmap.createBitmap(bar.measuredWidth, bar.measuredHeight, Bitmap.Config.ARGB_8888)
            try {
                bar.draw(Canvas(bitmap))
                val output = File("build/reports/media/screenshot-batch-bar.png").canonicalFile
                output.parentFile?.mkdirs()
                output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue("the screenshot bar fits a compact floating control", bitmap.height <= maxHeight)
                assertTrue("the rendered bar contains visible controls and thumbnails", nonTransparentPixels(bitmap) > 4_000)
                assertTrue(output.isFile && output.length() > 1_000L)
            } finally { bitmap.recycle() }
        } finally {
            activityController.pause().stop().destroy()
            sourceBitmaps.forEach(Bitmap::recycle)
            images.forEach { store.delete(it.id) }
        }
    }

    @Test fun acceptedBatchOffersDeliveryRetryAndHidesValidationAndCancel() {
        val activityController = Robolectric.buildActivity(MediaBlocksHostActivity::class.java).setup()
        val context = activityController.get()
        val bar = ScreenshotBatchBar(context)
        val pending = PendingNoteCapture(
            UUID.randomUUID().toString(), "", 1, NoteImageKind.SCREENSHOT, 1L, false,
            batch = true, images = listOf(image(1, NoteImageKind.SCREENSHOT)), accepted = true,
        )
        try {
            context.setContentView(bar)
            bar.bind(pending)
            bar.showDeliveryRetry()
            var retries = 0
            bar.onCapture = { retries++ }

            assertEquals("Réessayer l’ajout des images", bar.captureButton.text.toString())
            assertEquals(View.VISIBLE, bar.captureButton.visibility)
            assertEquals(View.GONE, bar.acceptButton.visibility)
            assertEquals(View.GONE, bar.cancelButton.visibility)
            assertTrue(bar.captureButton.isEnabled)
            bar.captureButton.performClick()
            assertEquals(1, retries)

            bar.bind(pending.copy(id = UUID.randomUUID().toString(), accepted = false))
            assertEquals("Capturer", bar.captureButton.text.toString())
            assertEquals(View.VISIBLE, bar.acceptButton.visibility)
            assertEquals(View.VISIBLE, bar.cancelButton.visibility)
            assertTrue(bar.acceptButton.isEnabled)
        } finally {
            activityController.pause().stop().destroy()
        }
    }

    private fun image(number: Int, kind: NoteImageKind) = NoteImage(
        UUID.randomUUID().toString(), number, kind, number.toLong(), 1080, 1920,
    )

    private fun waitForThumbnails(thumbs: LinearLayout) {
        val deadline = System.nanoTime() + 2_000_000_000L
        fun hasAllBitmaps() = (0 until thumbs.childCount).all { index ->
            ((thumbs.getChildAt(index) as FrameLayout).getChildAt(0) as ImageView).drawable is BitmapDrawable
        }
        while (!hasAllBitmaps() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
        assertTrue("each capture preview resolves its own thumbnail", hasAllBitmaps())
    }

    private fun nonTransparentPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++
        }
        return count
    }
}
