package com.kafkasl.phonewhisper

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityDiagnosticUiTest {
    private val oldFormatReport = "Application : 0.9.5\nFormat : Mail\nÉtat : ancien rapport"
    private val latestDictationReport = "Application : ${BuildConfig.VERSION_NAME}\nFormat : Texte\nÉtat : rapport actuel"
    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        ShadowDialog.reset()
    }

    @Test
    fun defaultDiagnosticOpensTheLatestDictationAndOffersOlderFormatSeparately() {
        val activity = activityWithReports(
            latest = latestDictationReport,
            formatted = oldFormatReport,
        )

        showDiagnostic(activity)
        val dialog = currentDialog()

        assertEquals("Dernière dictée", dialogTitle(dialog))
        assertTrue(dialogMessage(dialog).contains(latestDictationReport))
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text == "Dernier format")
    }

    @Test
    fun oldReportAfterUpdateIsIdentifiedWithoutInventingANewAttempt() {
        val activity = activityWithReports(latest = oldFormatReport, formatted = null)

        showDiagnostic(activity)
        val message = dialogMessage(currentDialog())

        assertTrue(message.contains("Version installée actuelle : ${BuildConfig.VERSION_NAME}"))
        assertTrue(message.contains("Ancien rapport conservé · version : 0.9.5"))
        assertTrue(message.endsWith(oldFormatReport))
    }

    @Test
    fun migrationFallsBackToTheOnlyPersistedFormatReport() {
        val activity = activityWithReports(latest = null, formatted = oldFormatReport)

        showDiagnostic(activity)

        val dialog = currentDialog()
        assertEquals("Dernier format demandé", dialogTitle(dialog))
        assertTrue(dialogMessage(dialog).endsWith(oldFormatReport))
    }

    @Test
    fun noReportMessageInvitesACompletedDictationOrNote() {
        val activity = activityWithReports(latest = null, formatted = null)

        showDiagnostic(activity)
        val message = dialogMessage(currentDialog())

        assertTrue(message.contains("Aucun post-traitement enregistré."))
        assertTrue(message.contains("Terminez une dictée ou une note pour produire un diagnostic."))
    }

    @Test
    fun copiedDiagnosticContainsInstalledVersionAndExactIdentifiableReport() {
        val activity = activityWithReports(latest = oldFormatReport, formatted = null)

        showDiagnostic(activity)
        currentDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()

        assertTrue("copied=$copied", copied.contains("Version installée actuelle : ${BuildConfig.VERSION_NAME}"))
        assertTrue("copied=$copied", copied.contains("Ancien rapport conservé · version : 0.9.5"))
        assertTrue("copied=$copied", copied.endsWith(oldFormatReport))
    }

    @Test
    fun neutralFormatViewCanReturnToLatestDictation() {
        val activity = activityWithReports(latest = latestDictationReport, formatted = oldFormatReport)

        showDiagnostic(activity)
        currentDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val formatDialog = currentDialog()
        assertEquals("Dernier format demandé", dialogTitle(formatDialog))
        assertTrue(dialogMessage(formatDialog).contains(oldFormatReport))
        assertEquals("Dernière dictée", formatDialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)

        formatDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Dernière dictée", dialogTitle(currentDialog()))
    }

    @Test
    fun oldDiagnosticDialogRendersForVisualReview() {
        val activity = activityWithReports(latest = oldFormatReport, formatted = null)
        showDiagnostic(activity)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val dialog = currentDialog()
        val copyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertTrue("Copy button should be visible in the rendered dialog", copyButton.visibility == View.VISIBLE)
        assertTrue("Close button should be visible in the rendered dialog", closeButton.visibility == View.VISIBLE)
        assertEquals("Copier", copyButton.text.toString())
        assertEquals("Fermer", closeButton.text.toString())
        val decor = dialog.window?.decorView ?: error("Diagnostic dialog has no decor view")
        val width = 390
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST)
        decor.measure(widthSpec, heightSpec)
        decor.layout(0, 0, width, decor.measuredHeight)
        val output = File("../reports/note_diagnostic_fix/ui/diagnostic-old-report.png")
            .apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(width, decor.measuredHeight, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue("Diagnostic dialog render should be written", output.length() > 1_000L)
    }

    private fun activityWithReports(latest: String?, formatted: String?): MainActivity {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("onb_complete", true)
            .apply()
        app.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).edit()
            .apply {
                latest?.let { putString("last_postprocessing_diagnostic", it) }
                formatted?.let { putString("last_format_postprocessing_diagnostic", it) }
            }
            .apply()
        controller = Robolectric.buildActivity(MainActivity::class.java)
        return controller!!.create().start().resume().get()
    }

    private fun showDiagnostic(activity: MainActivity) {
        val method = MainActivity::class.java.getDeclaredMethod(
            "showPostprocessingDiagnostic",
            DiagnosticPanel::class.java,
        ).apply { isAccessible = true }
        method.invoke(activity, DiagnosticPanel.LATEST_DICTATION)
    }

    private fun currentDialog(): AlertDialog =
        ShadowDialog.getLatestDialog() as AlertDialog

    private fun dialogTitle(dialog: AlertDialog): String =
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString().orEmpty()

    private fun dialogMessage(dialog: AlertDialog): String =
        dialog.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
}
