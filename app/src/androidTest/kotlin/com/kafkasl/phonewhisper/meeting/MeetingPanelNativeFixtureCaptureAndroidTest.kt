package com.kafkasl.phonewhisper.meeting

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.BuildConfig
import com.kafkasl.phonewhisper.MainActivity
import com.kafkasl.phonewhisper.NoteImage
import com.kafkasl.phonewhisper.NoteImageKind
import com.kafkasl.phonewhisper.OverlayTranscriptEditor
import com.kafkasl.phonewhisper.ThemeTokens
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Captures native Android layouts of the real meeting panel using synthetic UI-only data. */
@RunWith(AndroidJUnit4::class)
class MeetingPanelNativeFixtureCaptureAndroidTest {
    @Test
    fun capturesMeetingPanelStatesWithoutLoadingModelsOrUsingAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("captures run only in the isolated meeting prototype", BuildConfig.MEETING_PROTOTYPE)
        assertEquals("com.uhama.whisperpin.meetingtest", target.packageName)

        val preferences = target.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        val hadOnboardingValue = preferences.contains("onb_complete")
        val oldOnboardingValue = preferences.getBoolean("onb_complete", false)
        val hadThemeValue = preferences.contains("theme_mode")
        val oldThemeValue = preferences.getString("theme_mode", null)
        val hadMicrophonePermission = target.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val initialFontScale = target.resources.configuration.fontScale
        var scenario: ActivityScenario<MainActivity>? = null
        var harness: PanelHarness? = null

        val captureDirectory = File(
            target.getExternalFilesDir(null) ?: target.filesDir,
            "meeting-ui-fixtures-simulated",
        ).apply { check(mkdirs() || isDirectory) }
        try {
            preferences.edit().putBoolean("onb_complete", true).putString("theme_mode", "light").commit()
            grantMicrophonePermission(target)
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity -> harness = attachPanel(activity, "three-voices") }
            capture(instrumentation, captureDirectory, "01-three-voices-return.png")

            activeScenario.onActivity { activity ->
                val active = requireNotNull(harness)
                findText(active.controller.view, "Intervenants (3)")
                    ?.performClick() ?: error("Le bouton Intervenants (3) est absent")
            }
            instrumentation.waitForIdleSync()
            capture(instrumentation, captureDirectory, "02-homonyms-native-dialog.png")

            activeScenario.onActivity { requireNotNull(harness).dialogs.choose("profile-1") }
            instrumentation.waitForIdleSync()
            activeScenario.onActivity { requireNotNull(harness).dialogs.choose("profile:rename") }
            instrumentation.waitForIdleSync()
            activeScenario.onActivity {
                requireNotNull(harness).dialogs.setText("Sophie de la commune de Saint-Aubin-des-Bois")
            }
            capture(instrumentation, captureDirectory, "03-long-rename-native-dialog.png")
            activeScenario.onActivity { requireNotNull(harness).dialogs.submitText() }
            instrumentation.waitForIdleSync()
            capture(instrumentation, captureDirectory, "04-renamed-homonyms.png")

            harness = showScene(activeScenario, harness, "ignored-image")
            capture(instrumentation, captureDirectory, "05-ignored-voice-image-action.png")

            harness = showScene(activeScenario, harness, "focused-edit")
            activeScenario.onActivity { activity ->
                val editor = requireNotNull(activity.window.decorView.findViewWithTag<OverlayTranscriptEditor>("meeting-editor:turn-1"))
                editor.beginEditing()
                editor.setSelection((editor.length() / 2).coerceAtLeast(0))
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(350)
            capture(instrumentation, captureDirectory, "06-focused-edit-field.png")
            activeScenario.onActivity { requireNotNull(harness).controller.dispose() }
            harness = null

            harness = showScene(activeScenario, harness, "save-error")
            capture(instrumentation, captureDirectory, "07-live-save-error.png")
            harness = showScene(activeScenario, harness, "download-progress")
            capture(instrumentation, captureDirectory, "08-model-download-progress.png")
            harness = showScene(activeScenario, harness, "light")
            capture(instrumentation, captureDirectory, "09-manual-light-theme.png")
            harness = showScene(activeScenario, harness, "dark")
            capture(instrumentation, captureDirectory, "10-manual-dark-theme.png")

            activeScenario.onActivity { activity ->
                requireNotNull(harness).controller.dispose()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            assertTrue("Activity reaches landscape", awaitOrientation(activeScenario, Configuration.ORIENTATION_LANDSCAPE))
            activeScenario.onActivity { activity ->
                setFontScale(activity, initialFontScale)
                harness = attachPanel(activity, "compact-landscape")
            }
            instrumentation.waitForIdleSync()
            activeScenario.onActivity { activity ->
                val active = requireNotNull(harness)
                val density = activity.resources.displayMetrics.density
                assertEquals((240 * density).toInt(), active.controller.view.width)
                assertEquals((112 * density).toInt(), active.controller.view.height)
                assertEquals(initialFontScale, activity.resources.configuration.fontScale, 0.01f)
                val editor = requireNotNull(activity.window.decorView.findViewWithTag<OverlayTranscriptEditor>("meeting-editor:turn-1"))
                val visible = Rect()
                assertTrue(editor.getGlobalVisibleRect(visible))
                val visibleTextHeight = visible.height() - editor.paddingTop - editor.paddingBottom
                assertTrue(
                    "nominal compact fixture exposes two full text lines after editor padding",
                    visibleTextHeight >= editor.lineHeight * 2,
                )
            }
            capture(instrumentation, captureDirectory, "11-landscape-compact-240x112-normal-font.png")

            activeScenario.onActivity { activity ->
                requireNotNull(harness).controller.dispose()
                setFontScale(activity, 1.35f)
                harness = attachPanel(activity, "compact-landscape")
            }
            instrumentation.waitForIdleSync()
            activeScenario.onActivity { activity ->
                val active = requireNotNull(harness)
                val density = activity.resources.displayMetrics.density
                assertEquals((240 * density).toInt(), active.controller.view.width)
                assertEquals((112 * density).toInt(), active.controller.view.height)
                assertTrue(activity.resources.configuration.fontScale >= 1.3f)
                val editor = requireNotNull(activity.window.decorView.findViewWithTag<OverlayTranscriptEditor>("meeting-editor:turn-1"))
                assertTrue("large-font compact editor remains visible", editor.getGlobalVisibleRect(Rect()))
            }
            capture(instrumentation, captureDirectory, "12-landscape-compact-240x112-large-font.png")
        } finally {
            scenario?.onActivity { activity ->
                harness?.dialogs?.dismissAll()
                harness?.controller?.dispose()
                setFontScale(activity, initialFontScale)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            scenario?.close()
            preferences.edit().apply {
                if (hadOnboardingValue) putBoolean("onb_complete", oldOnboardingValue) else remove("onb_complete")
                if (hadThemeValue) putString("theme_mode", oldThemeValue) else remove("theme_mode")
            }.commit()
            if (!hadMicrophonePermission) revokeMicrophonePermission(target)
        }
    }

    private fun showScene(
        scenario: ActivityScenario<MainActivity>,
        oldHarness: PanelHarness?,
        scene: String,
    ): PanelHarness {
        var next: PanelHarness? = null
        scenario.onActivity { activity ->
            oldHarness?.let {
                it.dialogs.dismissAll()
                it.controller.dispose()
            }
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            next = attachPanel(activity, scene)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(next)
    }

    private fun attachPanel(activity: MainActivity, scene: String): PanelHarness {
        val themeValue = if (scene == "dark") "dark" else "light"
        activity.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", themeValue)
            .commit()
        val state = fixtureState(scene)
        val dialogs = FixtureDialogHost(activity)
        lateinit var controller: MeetingPanelController
        fun renderUpdatedState() = controller.render(state.document, state.images, state.status)
        val actions = MeetingPanelActions(
            edit = { turnId, text ->
                state.document = state.document.copy(turns = state.document.turns.map { turn ->
                    if (turn.id == turnId) turn.copy(editedText = text) else turn
                })
                renderUpdatedState()
            },
            rename = { profileId, name ->
                state.document = state.document.copy(participants = state.document.participants.map { profile ->
                    if (profile.id == profileId) profile.copy(name = name) else profile
                })
                renderUpdatedState()
            },
            setIgnored = { profileId, ignored ->
                state.document = state.document.copy(participants = state.document.participants.map { profile ->
                    if (profile.id == profileId) profile.copy(ignored = ignored) else profile
                })
                renderUpdatedState()
            },
        )
        controller = MeetingPanelController(activity, dialogs, actions)
        val root = FrameLayout(activity).apply { setBackgroundColor(ThemeTokens.palette(activity).bg) }
        val panelLayout = if (scene == "compact-landscape") {
            FrameLayout.LayoutParams(dp(activity, 240), dp(activity, 112), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(activity, 24)
                topMargin = dp(activity, 24)
            }
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(controller.view, panelLayout)
        activity.setContentView(root)
        controller.render(state.document, state.images, state.status)
        return PanelHarness(controller, dialogs)
    }

    private fun fixtureState(scene: String): FixtureState {
        val base = fixtureDocument()
        val ignoredImage = noteImage(4)
        return when (scene) {
            "ignored-image" -> FixtureState(
                base.copy(
                    participants = base.participants.map { if (it.id == "profile-2") it.copy(ignored = true) else it },
                    turns = listOf(MeetingTurn(
                        "image-turn", 5L, 4_100L, 4_800L,
                        "Paroles masquées ${ignoredImage.marker}", "profile-2", attributionStable = true,
                    )),
                ),
                listOf(ignoredImage),
                MeetingPanelStatus(phase = MeetingPanelStatus.Phase.PAUSED),
            )
            "save-error" -> FixtureState(
                base,
                emptyList(),
                MeetingPanelStatus(
                    phase = MeetingPanelStatus.Phase.LISTENING,
                    saveError = "Espace disque momentanément indisponible",
                ),
            )
            "download-progress" -> FixtureState(
                base,
                emptyList(),
                MeetingPanelStatus(
                    phase = MeetingPanelStatus.Phase.DOWNLOADING,
                    progressPercent = 63,
                    modelSize = "849 Mo",
                ),
            )
            "compact-landscape" -> FixtureState(
                base.copy(turns = listOf(base.turns.first().copy(
                    recognizedText = "Retour sur le compte rendu : Sophie et Karim valident la suite."
                ))),
                emptyList(),
                MeetingPanelStatus(
                    phase = MeetingPanelStatus.Phase.LISTENING,
                    saveError = "Sauvegarde à réessayer",
                ),
            )
            else -> FixtureState(base, emptyList(), MeetingPanelStatus(phase = MeetingPanelStatus.Phase.LISTENING))
        }
    }

    private fun fixtureDocument() = MeetingDocument(
        sessionId = "fixture-session-2026-09-24",
        runId = "fixture-run-1",
        participants = listOf(
            MeetingParticipant("profile-1", 1, 1, name = "Sophie"),
            MeetingParticipant("profile-2", 2, 2, name = "Sophie"),
            MeetingParticipant("profile-3", 3, 3, name = "Karim Benali"),
        ),
        turns = listOf(
            MeetingTurn("turn-1", 1L, 0L, 1_050L, "On reprend le compte rendu après la pause.", "profile-1", attributionStable = true),
            MeetingTurn("turn-2", 2L, 1_100L, 2_080L, "Le premier point reste à vérifier avec l’équipe.", "profile-2", attributionStable = true),
            MeetingTurn("turn-3", 3L, 2_150L, 3_240L, "Je confirme la seconde hypothèse.", "profile-3", attributionStable = true),
            MeetingTurn("turn-4", 4L, 3_300L, 4_050L, "Je reviens sur le premier point : gardons la synthèse.", "profile-1", editedText = "Je reviens sur le premier point : gardons la synthèse.", attributionStable = true),
        ),
    )

    private fun capture(
        instrumentation: android.app.Instrumentation,
        output: File,
        fileName: String,
    ) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(output, fileName)
        try {
            FileOutputStream(file).use { stream ->
                assertTrue("PNG encoding succeeds for $fileName", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue("native fixture capture is non-empty", file.length() > 10_000L)
        android.util.Log.i(TAG, "SIMULATED_UI_CAPTURE=${file.absolutePath} bytes=${file.length()}")
    }

    private fun awaitOrientation(scenario: ActivityScenario<MainActivity>, expected: Int): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var orientation = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity { orientation = it.resources.configuration.orientation }
            if (orientation == expected) return true
            SystemClock.sleep(100)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun setFontScale(activity: MainActivity, fontScale: Float) {
        val updated = Configuration(activity.resources.configuration).apply { this.fontScale = fontScale }
        activity.resources.updateConfiguration(updated, activity.resources.displayMetrics)
    }

    private fun grantMicrophonePermission(context: Context) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}",
        )
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
        assertTrue("isolated fixture permission setup failed: $output", output.isBlank())
    }

    private fun revokeMicrophonePermission(context: Context) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm revoke ${context.packageName} ${Manifest.permission.RECORD_AUDIO}",
        )
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
        assertTrue("isolated fixture permission cleanup failed: $output", output.isBlank())
    }

    private fun noteImage(number: Int) = NoteImage(
        id = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
        number = number,
        kind = NoteImageKind.CAMERA,
        capturedAt = number.toLong(),
        width = 640,
        height = 480,
    )

    private fun findText(root: View, expected: String): TextView? {
        if (root is TextView && root.text.toString() == expected) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) findText(root.getChildAt(index), expected)?.let { return it }
        return null
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private data class FixtureState(
        var document: MeetingDocument,
        val images: List<NoteImage>,
        val status: MeetingPanelStatus,
    )

    private data class PanelHarness(
        val controller: MeetingPanelController,
        val dialogs: FixtureDialogHost,
    )

    private class FixtureDialogHost(private val activity: MainActivity) : MeetingPanelDialogHost {
        private data class PendingChoice(
            val request: MeetingPanelChoicesRequest,
            val callback: (String?) -> Unit,
            val dialog: AlertDialog,
        )

        private data class PendingText(
            val callback: (String?) -> Unit,
            val dialog: AlertDialog,
            val field: EditText,
        )

        private var pendingChoice: PendingChoice? = null
        private var pendingText: PendingText? = null

        override fun showChoices(request: MeetingPanelChoicesRequest, onChoice: (String?) -> Unit) {
            val dialog = AlertDialog.Builder(activity)
                .setTitle(request.title)
                .setItems(request.choices.map { it.label }.toTypedArray()) { _, index ->
                    onChoice(request.choices.getOrNull(index)?.id)
                }
                .setNegativeButton("Annuler") { _, _ -> onChoice(null) }
                .create()
            pendingChoice = PendingChoice(request, onChoice, dialog)
            dialog.show()
        }

        override fun showTextInput(request: MeetingPanelTextInputRequest, onSubmit: (String?) -> Unit) {
            val field = EditText(activity).apply {
                setText(request.initialText)
                minLines = 1
                maxLines = 3
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(request.title)
                .setView(field)
                .setPositiveButton("Renommer", null)
                .setNegativeButton("Annuler") { _, _ -> onSubmit(null) }
                .create()
            pendingText = PendingText(onSubmit, dialog, field)
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = field.text.toString()
                    pendingText = null
                    dialog.dismiss()
                    onSubmit(value)
                }
            }
            dialog.show()
        }

        fun choose(id: String) {
            val current = requireNotNull(pendingChoice) { "A participant choice dialog is not open" }
            check(current.request.choices.any { it.id == id }) { "Choice $id was not offered" }
            pendingChoice = null
            current.dialog.dismiss()
            current.callback(id)
        }

        fun setText(value: String) {
            requireNotNull(pendingText) { "A text input dialog is not open" }.field.setText(value)
        }

        fun submitText() {
            val current = requireNotNull(pendingText) { "A text input dialog is not open" }
            val value = current.field.text.toString()
            pendingText = null
            current.dialog.dismiss()
            current.callback(value)
        }

        fun dismissAll() {
            pendingChoice?.dialog?.dismiss()
            pendingText?.dialog?.dismiss()
            pendingChoice = null
            pendingText = null
        }
    }

    private companion object {
        const val TAG = "MeetingPanelNativeFixture"
    }
}
