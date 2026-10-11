package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudCorrectionNativeRenderTest {
    @Test fun correctionRendersAtPhoneTabletAndSmallScreenSizes() {
        render("correction-phone-light", ThemeMode.LIGHT, 390, 844)
        render("correction-phone-dark", ThemeMode.DARK, 390, 844)
        render("correction-tablet", ThemeMode.LIGHT, 800, 1100)
        render("correction-small", ThemeMode.LIGHT, 320, 720)
    }

    @Test fun engineDialogCanOnlyDisableOrEnableCloudCorrection() {
        val app = RuntimeEnvironment.getApplication()
        val preferences = PersistencePrefs(app)
        preferences.formattingEngine = "cloud"
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create(Bundle().apply { putString("main_screen", "FORMATTING") }).start().resume()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            val label = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == "Correction IA" }
            var clickable: View = label
            while (!clickable.isClickable) clickable = clickable.parent as View
            assertTrue(clickable.performClick())
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(2, dialog.listView.adapter.count)
            assertEquals("Cloud — OpenRouter", dialog.listView.adapter.getItem(0).toString())
            assertEquals("Désactivé", dialog.listView.adapter.getItem(1).toString())
            save(dialog.findViewById(androidx.appcompat.R.id.parentPanel)!!, "correction-engine-choice", 390, 520, naturalHeight = true)
            dialog.listView.performItemClick(null, 1, 1)
            assertEquals("off", preferences.formattingEngine)
            assertFalse(preferences.cloudCleanupEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    private fun render(name: String, theme: ThemeMode, width: Int, height: Int) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).edit().putBoolean("onb_complete", true).commit()
        val preferences = PersistencePrefs(app)
        val oldTheme = preferences.themeMode
        preferences.themeMode = theme
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create(Bundle().apply { putString("main_screen", "FORMATTING") }).start().resume()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            val texts = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.contains("Clé OpenRouter"))
            assertFalse(texts.any { it.contains("Gemma") || it.contains("Format de la dictée") })
            save(root, name, width, height)
        } finally { controller.pause().stop().destroy(); preferences.themeMode = oldTheme }
    }

    private fun save(root: View, name: String, width: Int, height: Int, naturalHeight: Boolean = false) {
        val heightMode = if (naturalHeight) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, heightMode))
        val renderedHeight = if (naturalHeight) root.measuredHeight else height
        root.layout(0, 0, width, renderedHeight)
        val bitmap = Bitmap.createBitmap(width * 2, renderedHeight * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            if (naturalHeight) canvas.drawColor(android.graphics.Color.WHITE)
            canvas.scale(2f, 2f)
            root.draw(canvas)
            val output = File("../docs/research/cloud-only/android/$name.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 1_000)
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
