package com.kafkasl.phonewhisper

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Native Canvas contract for the static mark beside the DictAI wordmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandLogoTest {

    @Test
    fun brandMarkUsesRepeatedCompactLoopsAcrossItsViewport() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.create().start().resume().visible().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val mark = CursiveWaveView(activity).apply {
            setBrandMode(true)
            settle()
        }
        val width = 200
        val height = 80
        root.addView(mark, FrameLayout.LayoutParams(width, height))
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            mark.draw(Canvas(bitmap))
            val ranges = (0 until 4).map { quadrant ->
                verticalInkRange(bitmap, quadrant * width / 4, (quadrant + 1) * width / 4)
            }
            assertTrue("Each quarter should contain a visible loop", ranges.all { it >= 24 })
            assertTrue("Loop height should stay consistent across the mark", ranges.max() - ranges.min() <= 12)
            assertTrue("The mark should retain vertical breathing room", ranges.max() <= 50)
            val output = File("build/robolectric-renders", "brand-logo.png").apply { parentFile?.mkdirs() }
            output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        } finally {
            bitmap.recycle()
            runCatching { controller.pause().stop().destroy() }
        }
    }

    @Test
    fun wordmarkReservesTheCapitalIOverhangInWrapContentMeasurement() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.create().start().resume().visible().get()
        try {
            val typeface = ResourcesCompat.getFont(activity, R.font.caveat)
            val plain = TextView(activity).apply {
                text = "DictAI"
                typeface?.let { this.typeface = android.graphics.Typeface.create(it, 600, false) }
                textSize = 58f
                includeFontPadding = true
                setPadding(0, 4, 0, 4)
            }
            val corrected = BrandWordmarkView(activity).apply {
                text = "DictAI"
                typeface?.let { this.typeface = android.graphics.Typeface.create(it, 600, false) }
                textSize = 58f
                includeFontPadding = true
                setPadding(0, 4, 0, 4)
            }
            measure(plain)
            measure(corrected)

            val bounds = Rect()
            corrected.paint.getTextBounds("DictAI", 0, 6, bounds)
            val overhang = (bounds.right - corrected.paint.measureText("DictAI")).coerceAtLeast(0f)
            assertTrue("Caveat should expose a measurable capital-I overhang", overhang > 0f)
            assertTrue(
                "Wordmark width should include the final capital-I stroke",
                corrected.measuredWidth >= plain.measuredWidth + kotlin.math.ceil(overhang).toInt(),
            )
        } finally {
            runCatching { controller.pause().stop().destroy() }
        }
    }

    private fun verticalInkRange(bitmap: Bitmap, startX: Int, endX: Int): Int {
        var top = bitmap.height
        var bottom = -1
        for (x in startX until endX) {
            for (y in 0 until bitmap.height) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 20) {
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (bottom >= top) bottom - top + 1 else 0
    }

    private fun measure(view: View) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST)
        view.measure(widthSpec, heightSpec)
    }
}
