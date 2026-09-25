package com.kafkasl.phonewhisper.meeting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.BuildConfig
import com.kafkasl.phonewhisper.CursiveWaveView
import com.kafkasl.phonewhisper.MainActivity
import com.kafkasl.phonewhisper.ThemeTokens
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native screenshots of the actual view; capture is explicitly simulated and uses no audio/model. */
@RunWith(AndroidJUnit4::class)
class CursiveWaveMeetingFixtureCaptureAndroidTest {
    @Test
    fun capturesStaticActiveSilentPausedAndDictationViewStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("captures run only in the isolated meeting prototype", BuildConfig.MEETING_PROTOTYPE)
        assertEquals("com.uhama.whisperpin.meetingtest", target.packageName)

        val preferences = target.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        val hadOnboardingValue = preferences.contains("onb_complete")
        val oldOnboardingValue = preferences.getBoolean("onb_complete", false)
        var scenario: ActivityScenario<MainActivity>? = null
        var waves: List<CursiveWaveView> = emptyList()
        var caption: TextView? = null
        var fixtureRoot: View? = null
        val output = File(
            target.getExternalFilesDir(null) ?: target.filesDir,
            "cursive-wave-meeting-fixtures-simulated",
        ).apply { check(mkdirs() || isDirectory) }

        try {
            preferences.edit().putBoolean("onb_complete", true).commit()
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity ->
                val palette = ThemeTokens.palette(activity)
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(activity, 24), dp(activity, 24), dp(activity, 24), dp(activity, 24))
                    setBackgroundColor(palette.bg)
                }
                val title = TextView(activity).apply {
                    text = "CursiveWaveView — aperçu natif"
                    textSize = 20f
                    setTextColor(palette.ink)
                    gravity = Gravity.CENTER
                }
                val note = TextView(activity).apply {
                    text = "UI simulée · aucun audio ni modèle"
                    textSize = 14f
                    setTextColor(palette.inkMuted)
                    gravity = Gravity.CENTER
                }
                val smallPill = FrameLayout(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(activity, 22).toFloat()
                        setColor(palette.surface)
                        setStroke(1, palette.stroke)
                    }
                }
                val actualPillWave = CursiveWaveView(activity).apply {
                    setStrokeColor(palette.ink)
                    setMeetingMode(true)
                    setMeetingCaptureActive(false)
                    contentDescription = "Repère visuel Réunion, rotation figée"
                }
                smallPill.addView(
                    actualPillWave,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 32), Gravity.CENTER),
                )
                val zoomSurface = FrameLayout(activity).apply { setBackgroundColor(palette.surface) }
                val zoomWave = CursiveWaveView(activity).apply {
                    setStrokeColor(palette.ink)
                    setMeetingMode(true)
                    setMeetingCaptureActive(false)
                    contentDescription = "Aperçu agrandi du repère visuel Réunion"
                }
                zoomSurface.addView(
                    zoomWave,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 40)))
                root.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 32)))
                root.addView(TextView(activity).apply {
                    text = "Pastille réelle · 74 × 44 dp"
                    textSize = 14f
                    setTextColor(palette.inkMuted)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 28)))
                root.addView(smallPill, LinearLayout.LayoutParams(dp(activity, 74), dp(activity, 44)))
                root.addView(TextView(activity).apply {
                    text = "Réunion — pause, rotation figée"
                    textSize = 16f
                    setTextColor(palette.inkMuted)
                    gravity = Gravity.CENTER
                    tag = "cursive-wave-meeting-caption"
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 40)))
                caption = root.findViewWithTag("cursive-wave-meeting-caption")
                root.addView(TextView(activity).apply {
                    text = "Aperçu agrandi · 216 × 216 dp"
                    textSize = 14f
                    setTextColor(palette.inkMuted)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 28)))
                root.addView(zoomSurface, LinearLayout.LayoutParams(dp(activity, 216), dp(activity, 216)))
                waves = listOf(actualPillWave, zoomWave)
                fixtureRoot = root
                activity.setContentView(root)
            }
            assertCaption(instrumentation, requireNotNull(caption), "Réunion — pause, rotation figée")
            capture(instrumentation, requireNotNull(fixtureRoot), output, "01-meeting-paused-static.png")

            activeScenario.onActivity {
                requireNotNull(caption).text = "Réunion — capture simulée, aucun audio"
                waves.forEach {
                    it.contentDescription = "Repère Réunion, animation simulée sans audio"
                    it.setMeetingCaptureActive(true)
                }
            }
            SystemClock.sleep(650L)
            assertCaption(instrumentation, requireNotNull(caption), "Réunion — capture simulée, aucun audio")
            capture(instrumentation, requireNotNull(fixtureRoot), output, "02-meeting-silent-animation-a.png")
            SystemClock.sleep(650L)
            assertCaption(instrumentation, requireNotNull(caption), "Réunion — capture simulée, aucun audio")
            capture(instrumentation, requireNotNull(fixtureRoot), output, "03-meeting-silent-animation-b.png")

            activeScenario.onActivity {
                waves.forEach {
                    it.setMeetingCaptureActive(false)
                    it.contentDescription = "Repère visuel Réunion, rotation figée"
                }
                requireNotNull(caption).text = "Réunion — pause, rotation figée"
            }
            assertCaption(instrumentation, requireNotNull(caption), "Réunion — pause, rotation figée")
            capture(instrumentation, requireNotNull(fixtureRoot), output, "04-meeting-paused-again.png")

            activeScenario.onActivity {
                waves.forEach {
                    it.setMeetingMode(false)
                    it.contentDescription = "Onde Dictée existante"
                }
                requireNotNull(caption).text = "Dictée — géométrie existante"
            }
            assertCaption(instrumentation, requireNotNull(caption), "Dictée — géométrie existante")
            capture(instrumentation, requireNotNull(fixtureRoot), output, "05-dictation-existing-wave.png")
        } finally {
            scenario?.onActivity {
                waves.forEach {
                    it.setMeetingCaptureActive(false)
                    it.stop()
                }
            }
            scenario?.close()
            preferences.edit().apply {
                if (hadOnboardingValue) putBoolean("onb_complete", oldOnboardingValue) else remove("onb_complete")
            }.commit()
        }
    }

    private fun capture(
        instrumentation: android.app.Instrumentation,
        root: View,
        output: File,
        name: String,
    ) {
        awaitPresentedFrames(instrumentation, root)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(output, name)
        try {
            FileOutputStream(file).use { stream ->
                assertTrue("PNG encoding succeeds for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue("native UI fixture capture is non-empty", file.length() > 10_000L)
        android.util.Log.i(TAG, "SIMULATED_UI_CAPTURE=${file.absolutePath} bytes=${file.length()}")
    }

    private fun assertCaption(
        instrumentation: android.app.Instrumentation,
        caption: TextView,
        expected: String,
    ) {
        instrumentation.runOnMainSync { assertEquals(expected, caption.text.toString()) }
    }

    /** Wait for two actual animation callbacks, then allow the committed frame to reach display. */
    private fun awaitPresentedFrames(
        instrumentation: android.app.Instrumentation,
        root: View,
        frameCount: Int = 2,
    ) {
        val frames = CountDownLatch(frameCount)
        instrumentation.runOnMainSync {
            check(root.isAttachedToWindow && root.isShown) { "fixture root must be attached and visible" }
            fun scheduleNext(remaining: Int) {
                root.postOnAnimation {
                    frames.countDown()
                    if (remaining > 1) scheduleNext(remaining - 1)
                }
            }
            scheduleNext(frameCount)
        }
        check(frames.await(2, TimeUnit.SECONDS)) { "timed out waiting for fixture frames" }
        SystemClock.sleep(120L)
        instrumentation.runOnMainSync {
            check(root.isAttachedToWindow && root.isShown && root.width > 0 && root.height > 0) {
                "fixture root must remain laid out and visible for capture"
            }
        }
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CursiveWaveFixture"
    }
}
