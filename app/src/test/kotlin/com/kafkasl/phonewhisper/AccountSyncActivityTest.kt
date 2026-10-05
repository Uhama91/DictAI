package com.kafkasl.phonewhisper

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountSyncActivityTest {
    @Test fun localModeIsCompleteAndConnectionOptional() {
        val controller = Robolectric.buildActivity(AccountSyncActivity::class.java).create()
        try {
            val texts = texts(controller.get().findViewById(android.R.id.content))
            assertTrue(texts.any { it.contains("sans compte") })
            assertTrue(texts.any { it.contains("Se connecter avec Google") })
            assertTrue(texts.any { it.contains("Vocabulaire") })
            assertTrue(texts.any { it.contains("notes", ignoreCase = true) })
        } finally { controller.destroy() }
        renderPreferencesEntry()
    }
    @Test fun accountPageRendersInLightDarkAndLargeFonts() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            PersistencePrefs(RuntimeEnvironment.getApplication()).themeMode = theme
            render("account-${theme.preferenceValue}.png", 390, 844)
        }
        render("account-tablet.png", 800, 1100)
        render("account-small.png", 320, 720)
        val config = android.content.res.Configuration(RuntimeEnvironment.getApplication().resources.configuration)
        val oldScale = config.fontScale
        config.fontScale = 1.3f
        RuntimeEnvironment.getApplication().resources.updateConfiguration(config, RuntimeEnvironment.getApplication().resources.displayMetrics)
        try { render("account-large-font.png", 390, 844) }
        finally {
            config.fontScale = oldScale
            RuntimeEnvironment.getApplication().resources.updateConfiguration(config, RuntimeEnvironment.getApplication().resources.displayMetrics)
        }
        PersistencePrefs(RuntimeEnvironment.getApplication()).themeMode = ThemeMode.SYSTEM
    }
    private fun render(name: String, width: Int, height: Int) {
        val controller = Robolectric.buildActivity(AccountSyncActivity::class.java).create()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
            assertTrue("Account page should contain its title", texts(root).any { it.contains("Compte") })
            val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                File("build/robolectric-renders",name).apply { parentFile!!.mkdirs() }.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                }
            } finally { bitmap.recycle() }
        } finally { controller.destroy() }
    }
    private fun renderPreferencesEntry() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("whisperpin", android.content.Context.MODE_PRIVATE)
        val completed = prefs.getBoolean("onb_complete", false)
        prefs.edit().putBoolean("onb_complete", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create(android.os.Bundle().apply { putString("main_screen", "PREFERENCES") })
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 390, 844)
            assertTrue(texts(root).any { it == "Compte et synchronisation" })
            val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                File("build/robolectric-renders/account-entry.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        } finally {
            controller.destroy()
            prefs.edit().putBoolean("onb_complete", completed).commit()
        }
    }
    private fun texts(view: View): List<String> = when(view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
