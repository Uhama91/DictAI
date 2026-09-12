package com.kafkasl.phonewhisper

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Native Canvas render of the selected VIVID compact-wave correction. It uses the production
 * pill size first and then a faithful 2x scale of that same 74x44 view for inspection. The
 * deterministic RMS sequence is the one used by the archived A/B/C comparison, so the old B
 * column can be compared from that archive without recreating the old renderer here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactMotionVariantsNativeRenderTest {
    private var controller: ActivityController<Activity>? = null

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
    }

    @Test
    fun writesSynchronizedNativeVividRestoredHeightAtRealPillSizeAndZoom() {
        val context = RuntimeEnvironment.getApplication()
        val activityController = Robolectric.buildActivity(Activity::class.java)
        controller = activityController
        val activity = activityController.create().start().resume().visible().get()
        val palette = ThemeTokens.palette(context)
        val margin = 8
        val labelHeight = 24
        val normalWidth = 74
        val normalHeight = 44
        val zoomWidth = 148
        val zoomHeight = 88
        val gap = 12
        val width = margin * 2 + zoomWidth
        val height = labelHeight + normalHeight + gap + zoomHeight + margin
        val root = FrameLayout(activity).apply { setBackgroundColor(palette.bg) }
        activity.setContentView(root)

        val normalLeft = (width - normalWidth) / 2
        val normalWave = addVivid(
            root = root,
            palette = palette,
            left = normalLeft,
            top = labelHeight,
            zoom = false,
        )
        val zoomWave = addVivid(
            root = root,
            palette = palette,
            left = margin,
            top = labelHeight + normalHeight + gap,
            zoom = true,
        )
        val label = TextView(activity).apply {
            text = "B · Vif · repos restauré"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(palette.ink)
        }
        root.addView(label, FrameLayout.LayoutParams(width, labelHeight).apply {
            leftMargin = 0
            topMargin = 0
        })

        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, width, height)
        val outputDirectory = File("build/robolectric-renders/motion-vivid-restored-height").apply { mkdirs() }
        var vividRestBounds: android.graphics.Rect? = null
        var vividStrongBounds: android.graphics.Rect? = null
        val vividLeft = normalLeft
        val vividTop = labelHeight
        try {
            repeat(360) { frame ->
                val rms = rmsForFrame(frame)
                val level = visualWaveLevelFromRms(rms)
                listOf(normalWave, zoomWave).forEach { wave ->
                    wave.setLevel(level)
                    wave.advanceForTest(1f / 60f)
                }
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(palette.bg)
                    root.draw(canvas)
                    if (frame == 0) {
                        vividRestBounds = inkBounds(
                            bitmap,
                            vividLeft + 2,
                            vividLeft + normalWidth - 2,
                            vividTop + 2,
                            vividTop + normalHeight - 2,
                            palette,
                        )
                    } else if (frame == 225) {
                        vividStrongBounds = inkBounds(
                            bitmap,
                            vividLeft + 2,
                            vividLeft + normalWidth - 2,
                            vividTop + 2,
                            vividTop + normalHeight - 2,
                            palette,
                        )
                    }
                    val output = File(outputDirectory, "frame-%03d.png".format(frame))
                    output.outputStream().use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    assertTrue("Vivid frame should contain visible ink", countInk(bitmap, palette) > 40)
                    assertTrue("Vivid frame should stay opaque", hasOpaquePixelsOnly(bitmap))
                } finally {
                    bitmap.recycle()
                }
            }
            assertTrue("All synchronized Vivid frames should be written", outputDirectory.listFiles()?.count { it.name.endsWith(".png") } == 360)
            val rest = vividRestBounds
            val strong = vividStrongBounds
            assertTrue("The real-size Vivid wave should render a rest contour", rest != null)
            assertTrue("The real-size Vivid wave should render a strong contour", strong != null)
            assertTrue("Voice should visibly lift the crest at 74x44", strong!!.top < rest!!.top - 2)
            assertTrue("Voice should visibly lower the valley at 74x44", strong.bottom > rest.bottom + 2)
        } finally {
            normalWave.stop()
            zoomWave.stop()
        }
    }

    private fun addVivid(
        root: FrameLayout,
        palette: ThemePalette,
        left: Int,
        top: Int,
        zoom: Boolean,
    ): CursiveWaveView {
        val density = root.resources.displayMetrics.density
        val pillWidth = 74
        val pillHeight = 44
        val waveWidth = 74
        val pill = FrameLayout(root.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(palette.surface)
                setStroke(density.toInt().coerceAtLeast(1), palette.stroke)
            }
            clipToOutline = true
        }
        val wave = CursiveWaveView(root.context).apply {
            setCompactMotionPreset(CompactMotionPreset.VIVID)
        }
        pill.addView(wave, FrameLayout.LayoutParams(waveWidth, (32 * (pillWidth / 74f)).toInt()).apply {
            gravity = Gravity.CENTER
        })
        val host: View = if (zoom) FrameLayout(root.context).apply {
            clipChildren = false
            clipToPadding = false
            addView(pill, FrameLayout.LayoutParams(pillWidth, pillHeight))
            // Scale the actual production-size pill, rather than re-layouting a second 148x64
            // wave. This keeps stroke weight and curve proportions faithful in the zoom row.
            pill.pivotX = 0f
            pill.pivotY = 0f
            pill.scaleX = 2f
            pill.scaleY = 2f
        } else pill
        root.addView(host, FrameLayout.LayoutParams(if (zoom) 148 else pillWidth, if (zoom) 88 else pillHeight).apply {
            leftMargin = left
            topMargin = top
        })
        return wave
    }

    private fun hasOpaquePixelsOnly(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.all { android.graphics.Color.alpha(it) == 255 }
    }

    private fun rmsForFrame(frame: Int): Double = when (frame) {
        in 0..44, in 101..119, in 181..205, in 271..299, in 331..359 -> 0.008
        in 45..75 -> 0.02
        in 76..100 -> 0.06
        in 120..180 -> 0.06
        in 206..245 -> 0.15
        in 246..270 -> 0.02
        in 300..330 -> 0.12
        else -> 0.008
    }

    private fun countInk(bitmap: Bitmap, palette: ThemePalette): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { pixel ->
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            val ink = android.graphics.Color.red(palette.ink)
            val inkGreen = android.graphics.Color.green(palette.ink)
            val inkBlue = android.graphics.Color.blue(palette.ink)
            kotlin.math.abs(r - ink) + kotlin.math.abs(g - inkGreen) + kotlin.math.abs(b - inkBlue) < 90
        }
    }

    private fun inkBounds(
        bitmap: Bitmap,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        palette: ThemePalette,
    ): android.graphics.Rect? {
        val inkR = android.graphics.Color.red(palette.ink)
        val inkG = android.graphics.Color.green(palette.ink)
        val inkB = android.graphics.Color.blue(palette.ink)
        var minX = right
        var minY = bottom
        var maxX = left - 1
        var maxY = top - 1
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val distance = kotlin.math.abs(android.graphics.Color.red(pixel) - inkR) +
                kotlin.math.abs(android.graphics.Color.green(pixel) - inkG) +
                kotlin.math.abs(android.graphics.Color.blue(pixel) - inkB)
            if (distance < 150) {
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        return if (maxX >= minX && maxY >= minY) android.graphics.Rect(minX, minY, maxX + 1, maxY + 1) else null
    }
}
