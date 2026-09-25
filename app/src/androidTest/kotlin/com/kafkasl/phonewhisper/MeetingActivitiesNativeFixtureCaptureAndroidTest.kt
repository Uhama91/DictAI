package com.kafkasl.phonewhisper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native captures of the real Activities; meeting state is simulated and no audio/model runs. */
@RunWith(AndroidJUnit4::class)
class MeetingActivitiesNativeFixtureCaptureAndroidTest {
    @Test
    fun capturesActualMeetingActivitiesAcrossThemesAndLargerTextWithoutNetworkOrAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("captures require the isolated meeting prototype", BuildConfig.MEETING_PROTOTYPE)
        assertEquals("com.uhama.whisperpin.meetingtest", target.packageName)
        assertEquals(
            "pregrant RECORD_AUDIO externally so MainActivity does not show a permission dialog",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            target.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )

        val preferences = target.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        val prior = listOf("onb_complete", "transcription_mode", "theme_mode")
            .associateWith { key -> preferences.all[key] }
        val oldSystemFontScale = readSystemFontScale(instrumentation)
        val oldNightMode = AppCompatDelegate.getDefaultNightMode()
        val captureDirectory = File(
            target.getExternalFilesDir(null) ?: target.filesDir,
            "meeting-activities-fixture-${System.currentTimeMillis()}",
        ).apply { check(mkdirs() || isDirectory) }
        val networkCalls = AtomicInteger()
        val networkClient = OkHttpClient.Builder().addInterceptor {
            networkCalls.incrementAndGet()
            throw IOException("network disabled in meeting Activity fixture")
        }.build()

        try {
            captureMain(
                instrumentation, target, preferences, networkClient, networkCalls, captureDirectory,
                ThemeMode.LIGHT, 1.0f, "01-main-home-light-100.png", "02-main-settings-light-100.png",
            )
            captureMain(
                instrumentation, target, preferences, networkClient, networkCalls, captureDirectory,
                ThemeMode.DARK, 1.0f, "03-main-home-dark-100.png", "04-main-settings-dark-100.png",
            )
            captureMain(
                instrumentation, target, preferences, networkClient, networkCalls, captureDirectory,
                ThemeMode.DARK, 1.35f, null, "05-main-settings-dark-135.png",
            )
            captureOnboarding(
                instrumentation, target, preferences, networkClient, networkCalls, captureDirectory,
                ThemeMode.LIGHT, 1.0f, "06-onboarding-meeting-light-100.png",
            )
            captureOnboarding(
                instrumentation, target, preferences, networkClient, networkCalls, captureDirectory,
                ThemeMode.DARK, 1.0f, "07-onboarding-meeting-dark-100.png",
            )
            captureOnboarding(
                instrumentation, target, preferences, networkClient, networkCalls, captureDirectory,
                ThemeMode.LIGHT, 1.35f, "08-onboarding-meeting-light-135.png",
            )

            assertEquals("opening and displaying the models must not use the network", 0, networkCalls.get())
            android.util.Log.i(TAG, "MEETING_ACTIVITIES_CAPTURE_DIR=${captureDirectory.absolutePath}")
            android.util.Log.i(TAG, "MEETING_ACTIVITIES_CAPTURE_COUNT=8")
        } finally {
            TranscriptionModeCoordinator.clearProcessForTest()
            restorePreferences(preferences, prior)
            restoreSystemFontScale(instrumentation, oldSystemFontScale)
            AppCompatDelegate.setDefaultNightMode(oldNightMode)
        }
    }

    private fun captureMain(
        instrumentation: android.app.Instrumentation,
        target: Context,
        preferences: android.content.SharedPreferences,
        networkClient: OkHttpClient,
        networkCalls: AtomicInteger,
        output: File,
        theme: ThemeMode,
        fontScale: Float,
        homeName: String?,
        settingsName: String,
    ) {
        configure(instrumentation, target, preferences, theme, fontScale,
            TranscriptionMode.MEETING, onbComplete = true)
        TranscriptionModeCoordinator.clearProcessForTest()
        val (store, storeDirectory) = newEmptyStore(target, networkClient)
        val inspection = StoreInspectionObserver(store)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.meetingModelStoreProvider = { store }
                assertEquals(TranscriptionMode.MEETING,
                    TranscriptionModeCoordinator.process(activity).snapshot().mode)
            }
            if (homeName != null) captureActivity(instrumentation, scenario, output, homeName, theme, fontScale)
            scenario.onActivity { activity ->
                clickText(activity.window.decorView, "Réglages Réunion")
            }
            awaitMissingModels(instrumentation, scenario, store, inspection)
            assertNoRecording(target)
            captureActivity(instrumentation, scenario, output, settingsName, theme, fontScale)
            assertEquals("model inspection must not issue HTTP requests", 0, networkCalls.get())
        } finally {
            scenario.close()
            inspection.close()
            store.shutdownForTests()
            storeDirectory.deleteRecursively()
            TranscriptionModeCoordinator.clearProcessForTest()
        }
    }

    private fun captureOnboarding(
        instrumentation: android.app.Instrumentation,
        target: Context,
        preferences: android.content.SharedPreferences,
        networkClient: OkHttpClient,
        networkCalls: AtomicInteger,
        output: File,
        theme: ThemeMode,
        fontScale: Float,
        imageName: String,
    ) {
        // Start in Dictée so the isolated MeetingModelStore can be injected before its view opens.
        configure(instrumentation, target, preferences, theme, fontScale,
            TranscriptionMode.DICTATION, onbComplete = false)
        TranscriptionModeCoordinator.clearProcessForTest()
        val (store, storeDirectory) = newEmptyStore(target, networkClient)
        val inspection = StoreInspectionObserver(store)
        val scenario = ActivityScenario.launch(OnboardingActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.meetingModelStoreProvider = { store }
                clickText(activity.window.decorView, "Réunion")
            }
            awaitMissingModels(instrumentation, scenario, store, inspection)
            assertNoRecording(target)
            captureActivity(instrumentation, scenario, output, imageName, theme, fontScale)
            assertEquals("opening Meeting onboarding must not issue HTTP requests", 0, networkCalls.get())
        } finally {
            scenario.close()
            inspection.close()
            store.shutdownForTests()
            storeDirectory.deleteRecursively()
            TranscriptionModeCoordinator.clearProcessForTest()
        }
    }

    private fun configure(
        instrumentation: android.app.Instrumentation,
        target: Context,
        preferences: android.content.SharedPreferences,
        theme: ThemeMode,
        fontScale: Float,
        mode: TranscriptionMode,
        onbComplete: Boolean,
    ) {
        preferences.edit()
            .putBoolean("onb_complete", onbComplete)
            .putString("transcription_mode", mode.preferenceValue)
            .putString("theme_mode", theme.preferenceValue)
            .commit()
        ThemeModeController.apply(target, theme)
        setSystemFontScale(instrumentation, target, fontScale)
    }

    private fun setSystemFontScale(
        instrumentation: android.app.Instrumentation,
        target: Context,
        fontScale: Float,
    ) {
        executeShell(instrumentation, "settings put system font_scale $fontScale")
        val deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (SystemClock.uptimeMillis() < deadline) {
            if (kotlin.math.abs(target.resources.configuration.fontScale - fontScale) <= 0.02f) return
            SystemClock.sleep(50L)
        }
        assertEquals("system font scale propagates before the real Activity launches",
            fontScale, target.resources.configuration.fontScale, 0.02f)
    }

    private fun readSystemFontScale(instrumentation: android.app.Instrumentation): String? =
        executeShell(instrumentation, "settings get system font_scale")
            .trim()
            .takeUnless { it.isEmpty() || it == "null" }

    private fun restoreSystemFontScale(
        instrumentation: android.app.Instrumentation,
        previous: String?,
    ) {
        val command = if (previous == null) {
            "settings delete system font_scale"
        } else {
            check(previous.toFloatOrNull() != null) { "unexpected prior system font scale" }
            "settings put system font_scale $previous"
        }
        executeShell(instrumentation, command)
        assertEquals("the system font scale is restored exactly", previous, readSystemFontScale(instrumentation))
    }

    private fun executeShell(
        instrumentation: android.app.Instrumentation,
        command: String,
    ): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

    private fun newEmptyStore(target: Context, httpClient: OkHttpClient): Pair<MeetingModelStore, File> {
        val directory = File(target.cacheDir, "meeting-activity-${UUID.randomUUID()}")
        check(directory.mkdirs() || directory.isDirectory)
        return MeetingModelStore(filesDirectory = directory, httpClient = httpClient) to directory
    }

    private fun <A : Activity> awaitMissingModels(
        instrumentation: android.app.Instrumentation,
        scenario: ActivityScenario<A>,
        store: MeetingModelStore,
        inspection: StoreInspectionObserver,
    ) {
        assertTrue("the empty local package inspection completes before the page is captured",
            inspection.awaitMissing() && store.currentState == MeetingModelStoreState.Missing)
        val activity = AtomicReference<Activity>()
        scenario.onActivity { activity.set(it) }
        val deadlineForView = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5)
        var visibleStatus: String? = null
        while (SystemClock.uptimeMillis() < deadlineForView) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                visibleStatus = findView(activity.get().window.decorView, MeetingModelSettingsPanel.STATUS_TAG)
                    ?.text?.toString()
            }
            if (visibleStatus == "Modèles absents") break
            SystemClock.sleep(40L)
        }
        assertEquals("the real page reports absent models", "Modèles absents", visibleStatus)
    }

    private fun assertNoRecording(target: Context) {
        val snapshot = TranscriptionModeCoordinator.process(target).snapshot()
        assertEquals("fixture must not start microphone capture", null, snapshot.activeRunMode)
        assertEquals("fixture must not preload Dictée runtime", 0, snapshot.pendingDictationLoads)
    }

    private fun <A : Activity> captureActivity(
        instrumentation: android.app.Instrumentation,
        scenario: ActivityScenario<A>,
        output: File,
        name: String,
        theme: ThemeMode,
        fontScale: Float,
    ) {
        val root = AtomicReference<View>()
        scenario.onActivity { activity ->
            root.set(activity.window.decorView)
            assertTrue("actual Activity window is attached for capture", root.get().isAttachedToWindow)
            assertTrue("actual Activity window is visible for capture", root.get().isShown)
            assertEquals("font scaling is applied to the real Activity resources", fontScale,
                activity.resources.configuration.fontScale, 0.02f)
            assertEquals("the selected manual theme is active", theme, PersistencePrefs(activity).themeMode)
            val pageScroll = allViews(root.get()).filterIsInstance<ScrollView>().firstOrNull()
            assertNotNull("the real Activity page is scrollable", pageScroll)
            val expectedBackground = if (theme == ThemeMode.LIGHT) ThemeTokens.LIGHT.bg else ThemeTokens.DARK.bg
            assertEquals("the real page uses the selected theme palette", expectedBackground,
                (pageScroll!!.background as ColorDrawable).color)
            root.get().invalidate()
            root.get().requestLayout()
        }
        val frames = CountDownLatch(2)
        instrumentation.runOnMainSync {
            root.get().postOnAnimation {
                frames.countDown()
                root.get().postOnAnimation { frames.countDown() }
            }
        }
        assertTrue("two UI frames are presented before capture", frames.await(3, TimeUnit.SECONDS))
        SystemClock.sleep(120L)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(output, name)
        try {
            FileOutputStream(file).use { stream ->
                assertTrue("PNG encoding succeeds for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue("native Activity capture is non-empty", file.length() > 10_000L)
        android.util.Log.i(TAG, "MEETING_ACTIVITY_CAPTURE=${file.absolutePath} theme=${theme.name} fontScale=$fontScale")
    }

    private fun clickText(root: View, text: String) {
        val match = allViews(root).filterIsInstance<TextView>().firstOrNull { it.text.toString().trim() == text }
        assertNotNull("visible Activity contains '$text'", match)
        var current: View? = match
        while (current != null) {
            if (current.isClickable && current.isEnabled && current.visibility == View.VISIBLE) {
                check(current.performClick()) { "click on '$text' was rejected" }
                return
            }
            current = current.parent as? View
        }
        error("no clickable parent for '$text'")
    }

    private fun findView(root: View, tag: Any): TextView? =
        allViews(root).filterIsInstance<TextView>().firstOrNull { it.tag == tag }

    private fun allViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(allViews(root.getChildAt(index)))
        }
    }

    private fun restorePreferences(
        preferences: android.content.SharedPreferences,
        previous: Map<String, Any?>,
    ) {
        val editor = preferences.edit()
        previous.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                else -> error("unexpected preference type for fixture key $key")
            }
        }
        editor.commit()
    }

    private class StoreInspectionObserver(private val store: MeetingModelStore) : AutoCloseable {
        private val states = LinkedBlockingQueue<MeetingModelStoreState>()
        private val listener: (MeetingModelStoreState) -> Unit = { states.offer(it) }

        init {
            // Register before the Activity opens the panel so a fast local inspection is observed.
            store.addListener(listener)
        }

        fun awaitMissing(timeoutSeconds: Long = 5): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var sawChecking = false
            while (System.nanoTime() < deadline) {
                val state = states.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
                if (state == MeetingModelStoreState.Checking) sawChecking = true
                if (sawChecking && state == MeetingModelStoreState.Missing) return true
            }
            return false
        }

        override fun close() = store.removeListener(listener)
    }

    private companion object {
        const val TAG = "MeetingActivitiesFixture"
    }
}
