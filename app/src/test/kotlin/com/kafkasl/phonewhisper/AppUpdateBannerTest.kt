package com.kafkasl.phonewhisper

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import android.view.ViewGroup
import android.widget.TextView
import android.os.Looper
import java.util.concurrent.Executor
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateBannerTest {
    @Test fun updateResponsePreservesControlsDuringModelDownloads() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("update-view-test", 0)
        prefs.edit().clear().putLong("last_attempt", System.currentTimeMillis()).putBoolean("succeeded", true).commit()
        lateinit var pending: Runnable
        val checker = AppUpdateChecker(prefs, 46, 34, System::currentTimeMillis, Executor { pending = it }) {
            """{"schemaVersion":1,"android":{"applicationId":"com.uhama.whisperpin","versionCode":100,"versionName":"0.9.18-dictai","minSdk":30,"downloadUrl":"${AppUpdateManifestTest.URL}"}}"""
        }
        val singleton = AppUpdateChecker::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val originalChecker = singleton.get(null)
        val setup = context.getSharedPreferences("whisperpin", 0)
        val originalSetup = setup.getBoolean("onb_complete", false)
        setup.edit().putBoolean("onb_complete", true).commit()
        singleton.set(null, checker)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            val activity = controller.create().start().resume().visible().get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val dictation = requireNotNull(findText(content, "Dictée"))
            generateSequence(dictation as View?) { it.parent as? View }.first { it.isClickable }.performClick()
            val existingScreen = content.getChildAt(0)
            checker.check(force = true)
            assertSame("Starting an update check must preserve active model controls", existingScreen, content.getChildAt(0))
            controller.pause().stop()
            pending.run()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            controller.start().resume().visible()
            assertSame("Receiving an update must preserve active model controls", existingScreen, content.getChildAt(0))
            assertNotNull(findText(content, "DictAI 0.9.18-dictai est disponible"))
            content.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 390, 844)
            val bitmap = Bitmap.createBitmap(780, 1688, Bitmap.Config.ARGB_8888)
            content.draw(Canvas(bitmap).apply { scale(2f, 2f) })
            File("build/robolectric-renders/updates/android-dictation-update.png").apply { parentFile?.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally {
            runCatching { controller.pause().stop().destroy() }
            singleton.set(null, originalChecker)
            setup.edit().putBoolean("onb_complete", originalSetup).commit()
        }
    }
    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
        return null
    }
    @Test fun bannerOpensTheAndroidDownloadInBrowser() {
        val context = RuntimeEnvironment.getApplication()
        val banner = AppUpdateBannerView(context, AppUpdate("0.9.17-dictai", AppUpdateManifestTest.URL))
        banner.performClick()
        val intent = Shadows.shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(AppUpdateManifestTest.URL, intent.dataString)
    }
    @Test fun bannerRendersOnPhoneTabletAndLargeFont() {
        val context = RuntimeEnvironment.getApplication()
        val original = Configuration(context.resources.configuration)
        val metrics = android.util.DisplayMetrics().also { it.setTo(context.resources.displayMetrics) }
        try {
            for ((width, scale) in listOf(390 to 1f, 320 to 1.3f, 800 to 1f)) {
                context.resources.updateConfiguration(Configuration(original).apply { fontScale = scale }, metrics)
                for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                    PersistencePrefs(context).themeMode = mode
                    val view = AppUpdateBannerView(context, AppUpdate("0.9.18-dictai", AppUpdateManifestTest.URL))
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, width, view.measuredHeight)
                    val bitmap = Bitmap.createBitmap(width * 2, view.height * 2, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap).apply { scale(2f, 2f) }
                    view.draw(canvas)
                    val output = File("build/robolectric-renders/updates/android-$width-${mode.name.lowercase()}.png").apply { parentFile?.mkdirs() }
                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    assertTrue(view.height > 80)
                }
            }
        } finally {
            context.resources.updateConfiguration(original, metrics)
            PersistencePrefs(context).themeMode = ThemeMode.SYSTEM
        }
    }
}
