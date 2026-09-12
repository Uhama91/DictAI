package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
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

/** Native Android Canvas render used to inspect the responsive home composition off-device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityNativeRenderTest {
    private lateinit var context: android.content.Context
    private lateinit var preferences: android.content.SharedPreferences
    private var controller: ActivityController<MainActivity>? = null
    private var previousOnboarding = false

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences("whisperpin", android.content.Context.MODE_PRIVATE)
        previousOnboarding = preferences.getBoolean("onb_complete", false)
        preferences.edit().putBoolean("onb_complete", true).apply()
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        preferences.edit().putBoolean("onb_complete", previousOnboarding).apply()
        PersistencePrefs(context).themeMode = ThemeMode.SYSTEM
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun homeLightRendersAtTargetCanvasSize() {
        render(ThemeMode.LIGHT, "main-light.png")
    }

    @Test
    fun homeDarkRendersAtTargetCanvasSize() {
        render(ThemeMode.DARK, "main-dark.png")
    }

    @Test
    fun homeSmallAndLargeFontRendersWithoutClipping() {
        val previousConfiguration = Configuration(context.resources.configuration)
        val previousMetrics = android.util.DisplayMetrics().also { it.setTo(context.resources.displayMetrics) }
        val enlarged = Configuration(previousConfiguration).apply { fontScale = 1.3f }
        context.resources.updateConfiguration(enlarged, previousMetrics)
        try {
            assertTrue(
                "Native render should use the enlarged font scale",
                context.resources.configuration.fontScale >= 1.29f,
            )
            render(ThemeMode.LIGHT, "main-small-large-font.png", width = 320, height = 640)
        } finally {
            context.resources.updateConfiguration(previousConfiguration, previousMetrics)
        }
    }

    @Test
    fun compactWaveRendersRestAndVoiceStatesToTheEdges() {
        val hostController = Robolectric.buildActivity(android.app.Activity::class.java)
        val host = hostController.create().start().resume().visible().get()
        val hostRoot = FrameLayout(host)
        host.setContentView(hostRoot)
        val wave = CursiveWaveView(host)
        val width = 74
        val height = 32
        hostRoot.addView(wave, FrameLayout.LayoutParams(width, height))
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        hostRoot.measure(widthSpec, heightSpec)
        hostRoot.layout(0, 0, width, height)

        wave.settle()
        val rest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        wave.draw(Canvas(rest))

        wave.setLevel(1f)
        repeat(12) { wave.advanceForTest(1f / 60f) }
        val voice = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        wave.draw(Canvas(voice))

        try {
            val restBounds = alphaBounds(rest)
            val voiceBounds = alphaBounds(voice)
            assertTrue("Resting compact wave should be visible", restBounds != null)
            assertTrue("Voice compact wave should be visible", voiceBounds != null)
            assertTrue("Voice should increase compact wave amplitude", voiceBounds!!.height() > restBounds!!.height())
            assertTrue("Resting wave should reach the right fade edge", restBounds.right >= width - 4)
            assertTrue("Voice wave should reach the right fade edge", voiceBounds.right >= width - 4)

            val output = File("build/robolectric-renders", "wave-compact.png").apply { parentFile?.mkdirs() }
            val combined = Bitmap.createBitmap(width * 2, height, Bitmap.Config.ARGB_8888)
            try {
                val combinedCanvas = Canvas(combined)
                combinedCanvas.drawBitmap(rest, 0f, 0f, null)
                combinedCanvas.drawBitmap(voice, width.toFloat(), 0f, null)
                output.outputStream().use { stream -> check(combined.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            } finally {
                combined.recycle()
            }
            assertTrue("Compact wave render should be written", output.length() > 100L)
        } finally {
            rest.recycle()
            voice.recycle()
            runCatching { hostController.pause().stop().destroy() }
        }
    }

    @Test
    fun compactWaveWritesNativeAnimationFramesForReview() {
        val hostController = Robolectric.buildActivity(android.app.Activity::class.java)
        val host = hostController.create().start().resume().visible().get()
        val hostRoot = FrameLayout(host)
        host.setContentView(hostRoot)
        val width = 148
        val height = 64
        val wave = CursiveWaveView(host)
        hostRoot.addView(wave, FrameLayout.LayoutParams(width, height))
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        hostRoot.measure(widthSpec, heightSpec)
        hostRoot.layout(0, 0, width, height)
        val directory = File("build/robolectric-renders/wave-animation").apply { mkdirs() }
        try {
            repeat(360) { frame ->
                // Synthetic RMS syllables follow the same visual mapping as AudioRecord. The
                // 60 fps sequence is long enough to show attack, release, several peaks, calm,
                // and a complete spatial cycle without running ASR or opening a microphone.
                val rms = when (frame) {
                    in 0..59, in 101..119, in 181..205, in 271..299, in 331..359 -> 0.008
                    in 60..100 -> 0.02
                    in 120..180 -> 0.06
                    in 206..245 -> 0.15
                    in 246..270 -> 0.02
                    in 300..330 -> 0.12
                    else -> 0.008
                }
                val level = visualWaveLevelFromRms(rms)
                wave.setLevel(level)
                wave.advanceForTest(1f / 60f)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    wave.draw(Canvas(bitmap))
                    val output = File(directory, "frame-%03d.png".format(frame))
                    output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                    assertTrue("Native animation frame should contain pixels", alphaBounds(bitmap) != null)
                } finally {
                    bitmap.recycle()
                }
            }
            assertTrue("Native animation frames should be written", directory.listFiles()?.size == 360)
        } finally {
            runCatching { hostController.pause().stop().destroy() }
        }
    }

    private fun render(mode: ThemeMode, fileName: String, width: Int = 390, height: Int = 844) {
        PersistencePrefs(context).themeMode = mode
        controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller!!.create().start().resume().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        content.measure(widthSpec, heightSpec)
        content.layout(0, 0, width, height)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // The narrow-font brand can change orientation in its layout listener. A second
        // measure/layout pass captures that responsive branch in the native bitmap.
        content.measure(widthSpec, heightSpec)
        content.layout(0, 0, width, height)
        val scroll = content.getChildAt(0) as? ScrollView
        val page = scroll?.getChildAt(0) as? ViewGroup
        assertTrue("ScrollView should be measured", scroll != null && scroll.width == width && scroll.height == height)
        assertTrue("Home page should be measured", page != null && page.width > 0 && page.height > 0)
        assertTrue("Rendered tree should contain the DictAI wordmark", page?.let { containsText(it, "DictAI") } == true)
        val brand = page?.getChildAt(0) as? ViewGroup
        assertTrue("Brand row should be measured", brand != null && brand.width > 0 && brand.height > 0)
        assertTrue(
            "Native brand wave should have a drawable viewport",
            brand?.let { group ->
                (0 until group.childCount).map { group.getChildAt(it) }
                    .filterIsInstance<CursiveWaveView>()
                    .any { it.width > 0 && it.height > 0 }
            } == true,
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            content.draw(Canvas(bitmap))
            val output = File("build/robolectric-renders", fileName).apply { parentFile?.mkdirs() }
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue("Native render should produce a non-empty PNG", output.length() > 1_000L)
            assertTrue(
                "Native render should contain visible ink pixels",
                countInkPixels(bitmap, ThemeTokens.palette(activity)) > 100,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun containsText(view: View, needle: String): Boolean = when (view) {
        is TextView -> view.text?.toString()?.contains(needle) == true
        is ViewGroup -> (0 until view.childCount).any { containsText(view.getChildAt(it), needle) }
        else -> false
    }

    private fun countInkPixels(bitmap: Bitmap, palette: ThemePalette): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { pixel ->
            val distance = kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(palette.ink)) +
                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(palette.ink)) +
                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(palette.ink))
            android.graphics.Color.alpha(pixel) > 180 && distance < 150
        }
    }

    private fun alphaBounds(bitmap: Bitmap): android.graphics.Rect? {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 24) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right >= left && bottom >= top) android.graphics.Rect(left, top, right, bottom) else null
    }
}
