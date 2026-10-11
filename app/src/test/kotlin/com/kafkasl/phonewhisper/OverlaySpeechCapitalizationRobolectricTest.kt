package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.time.Duration

/** Controlled ASR snapshots exercise the real overlay, final publication and history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlaySpeechCapitalizationRobolectricTest {
    @Test fun frenchPreviewAndFinalInsertionShareCasingAndVocabularyProtection() {
        verify(DictationLanguage.FRENCH, ThemeMode.LIGHT, "Je consulte voila Tu vois Je continue",
            "Je consulte Voilà tu vois je continue", "pause-phone-light")
        verify(DictationLanguage.FRENCH, ThemeMode.DARK, "Je consulte voila Tu vois Je continue",
            "Je consulte Voilà tu vois je continue", "pause-phone-dark")
    }

    @Test fun englishOptionReachesThePreviewAndFinalPublication() {
        verify(DictationLanguage.ENGLISH, ThemeMode.LIGHT, "I work with La Company and Tu Linux",
            "I work with La Company and Tu Linux")
    }

    private fun verify(language: DictationLanguage, theme: ThemeMode, raw: String, expected: String, render: String? = null) {
        val app = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        val prefs = PersistencePrefs(app)
        prefs.themeMode = theme
        prefs.trailingSpace = false
        prefs.showTranscript = true
        Vocabulary.setRaw(app, "voila => Voilà")
        val inserted = mutableListOf<String>()
        val gateway = object : InjectionController {
            override fun inject(text: String): InjectionResult {
                inserted.add(text)
                return InjectionResult.Inserted
            }
        }
        InjectionGateway.register(gateway)
        val controller = Robolectric.buildService(OverlayService::class.java)
        val service = controller.create().get()
        val session = object : DictationAsrSession {
            override fun acceptPcm16(buffer: ByteArray, length: Int) = Unit
            override fun finish(fullPcm: ByteArray) = TranscriptionEngine.Result(raw)
            override fun cancel() = Unit
            override fun cancelAndAwait() = true
        }
        try {
            invoke(service, "showButton")
            val options = construct("RecordingOptions", language, DictationAsrMode.STREAMING, false, false,
                CloudModelCatalog.all.first(), NumberStyle.DIGITS, false)
            val run = construct("ActiveDictationRun", session, options, DictationPurpose.MESSAGE,
                DictationCancellationCoordinator())
            set(service, "activeRun", run)
            set(service, "purpose", DictationPurpose.MESSAGE)
            val state = Class.forName("${OverlayService::class.java.name}\$State").enumConstants!!
                .first { it.toString() == "RECORDING" }
            invoke(service, "setState", state)
            invoke(service, "setLivePreviewVisible", true)
            invoke(service, "renderLivePreview", run, "", raw)
            val editor = field<OverlayTranscriptEditor>(service, "liveText")
            assertEquals(expected, editor.text.toString())
            assertFalse("A pause must not add a point while listening", editor.text.endsWith('.'))
            if (render != null) save(field(service, "livePanel"), render)
            val capture = construct("RecordingCapture", run, byteArrayOf(1, 0), session, options)
            invoke(service, "processStoppedRecording", capture)
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1))
            assertEquals(listOf("$expected."), inserted)
            assertEquals("$expected.", DictationHistory(app).all().first().text)
        } finally {
            InjectionGateway.unregister(gateway)
            controller.destroy()
            ShadowSettings.reset()
        }
    }

    private fun construct(name: String, vararg args: Any): Any =
        Class.forName("${OverlayService::class.java.name}\$$name").declaredConstructors
            .single { it.parameterCount == args.size }.apply { isAccessible = true }.newInstance(*args)

    private fun invoke(target: Any, name: String, vararg args: Any): Any? = target.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(target, *args)

    private fun set(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target) as T

    private fun save(view: View, name: String) {
        val width = view.layoutParams.width.coerceAtLeast(1)
        val height = view.layoutParams.height.coerceAtLeast(1)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.width * 2, view.height * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(2f, 2f)
            view.draw(canvas)
            val output = File("../docs/research/android-pause-capitals/renders/$name.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
