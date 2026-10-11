package com.kafkasl.phonewhisper

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DictationHistoryActivityTest {
    @Test fun `history opens the full transcript and copies it without making a note`() {
        val context = RuntimeEnvironment.getApplication()
        val text = "Une dictée corrigée 🐈\n\n" + "Le texte intégral reste disponible. ".repeat(60)
        DictationHistory(context).record(text, "one", 1)
        val controller = Robolectric.buildActivity(DictationHistoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            descendants(root).filterIsInstance<TextView>().first { it.text.startsWith("Une dictée corrigée") }.performClick()
            val detail = activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue(descendants(detail).filterIsInstance<TextView>().any { it.text.toString() == text.trim() })
            descendants(detail).filterIsInstance<TextView>().first { it.text.toString() == "Copier le texte" }.performClick()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(text.trim(), clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).all.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun `history renders both notebook appearances with ten entries`() {
        val context = RuntimeEnvironment.getApplication()
        for (i in 1..11) DictationHistory(context).record("Dictée $i. Préparer les activités de jeudi, puis envoyer le document à l’équipe.", "session-$i", 1_791_717_600_000L + i * 60000L)
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            PersistencePrefs(context).themeMode = mode
            val controller = Robolectric.buildActivity(DictationHistoryActivity::class.java).setup()
            try {
                val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
                val views = descendants(root).filterIsInstance<TextView>()
                assertEquals(10, views.count { it.text.startsWith("Dictée ") })
                val width = 860; val height = 1864
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val output = File("build/robolectric-renders/history-${mode.name.lowercase()}.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } finally { controller.pause().stop().destroy() }
        }
        PersistencePrefs(context).themeMode = ThemeMode.SYSTEM
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
