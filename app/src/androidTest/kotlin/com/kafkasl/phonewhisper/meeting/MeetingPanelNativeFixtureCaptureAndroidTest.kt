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
import android.view.WindowInsets
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
    fun capturesDelayedAttributionStatesForVisualReview() {
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
        var scenario: ActivityScenario<MainActivity>? = null
        var harness: PanelHarness? = null
        val captureDirectory = File(
            target.getExternalFilesDir(null) ?: target.filesDir,
            "meeting-ui-fixtures-delayed-attribution",
        ).apply { check(mkdirs() || isDirectory) }

        try {
            preferences.edit().putBoolean("onb_complete", true).putString("theme_mode", "light").commit()
            grantMicrophonePermission(target)

            val sessionId = "fixture-delayed-attribution-2026-09-27"
            val runId = "fixture-delayed-attribution-run"
            val utteranceId = 91L
            val transcript = "Bonjour les amis nous reprenons la réunion je confirme cette version je partage la synthèse"
            val reducer = MeetingTranscriptReducer(sessionId, runId)
            reducer.apply(
                MeetingHypothesis(
                    runId = runId,
                    utteranceId = utteranceId,
                    revision = 1,
                    words = listOf(MeetingWord("Bonjour", 100L, 180L, channel = 0)),
                    transcript = "Bonjour les",
                    isFinal = false,
                    stableSpeakerThroughMs = 0L,
                    audioProcessedMs = 500L,
                ),
            )
            val initial = reducer.snapshot()
            assertEquals(listOf("Bonjour", "les"), initial.turns.map { it.recognizedText })
            reducer.apply(
                MeetingHypothesis(
                    runId = runId,
                    utteranceId = utteranceId,
                    revision = 2,
                    words = timedWords(transcript) { 0 },
                    transcript = transcript,
                    isFinal = false,
                    stableSpeakerThroughMs = 0L,
                    audioProcessedMs = 2_000L,
                ),
            )
            val provisional = reducer.snapshot()
            val provisionalText = provisional.turns.joinToString(" ") { it.recognizedText }
            assertEquals(listOf(transcript), provisional.turns.map { it.recognizedText })

            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity ->
                harness = attachPanel(
                    activity,
                    "delayed-provisional",
                    FixtureState(
                        document = provisional,
                        images = emptyList(),
                        status = MeetingPanelStatus(phase = MeetingPanelStatus.Phase.LISTENING),
                    ),
                )
            }
            capture(instrumentation, captureDirectory, "01-provisional-continuous-unknown.png")

            reducer.apply(
                MeetingHypothesis(
                    runId = runId,
                    utteranceId = utteranceId,
                    revision = 3,
                    words = timedWords(transcript) { index ->
                        when (index) {
                            in 0..6 -> 1
                            in 7..10 -> 2
                            else -> 1
                        }
                    },
                    transcript = transcript,
                    isFinal = false,
                    stableSpeakerThroughMs = 2_000L,
                    audioProcessedMs = 2_000L,
                ),
            )
            reducer.rename("$sessionId:participant:1", "Sophie")
            reducer.rename("$sessionId:participant:2", "Karim")
            val revised = reducer.snapshot()
            val revisedText = revised.turns.joinToString(" ") { it.recognizedText }
            assertEquals("a late speaker revision preserves the complete text", provisionalText, revisedText)
            assertEquals(
                listOf("Sophie", "Karim", "Sophie"),
                revised.turns.map { turn ->
                    revised.participants.single { it.id == turn.automaticParticipantId }.name
                },
            )
            activeScenario.onActivity {
                val active = requireNotNull(harness)
                active.controller.render(
                    revised,
                    emptyList(),
                    MeetingPanelStatus(phase = MeetingPanelStatus.Phase.LISTENING),
                )
            }
            capture(instrumentation, captureDirectory, "02-revised-voice-aaba.png")

            harness = showScene(activeScenario, harness, "delayed-active-edit")
            activeScenario.onActivity { activity ->
                val editor = requireNotNull(
                    activity.window.decorView.findViewWithTag<OverlayTranscriptEditor>("meeting-editor:edit-draft"),
                )
                editor.beginEditing()
                editor.setSelection((editor.length() / 2).coerceAtLeast(0))
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            assertTrue("the keyboard settles on the active editor", awaitImeVisible(activeScenario))
            activeScenario.onActivity { activity ->
                val editor = requireNotNull(
                    activity.window.decorView.findViewWithTag<OverlayTranscriptEditor>("meeting-editor:edit-draft"),
                )
                assertTrue("the text field remains focused", editor.isFocused)
                assertTrue("the text field is in edit mode", editor.isEditing)
                editor.setSelection((editor.length() / 2).coerceAtLeast(0))
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
            capture(instrumentation, captureDirectory, "03-active-edit-preserved.png")
        } finally {
            scenario?.onActivity { activity ->
                harness?.dialogs?.dismissAll()
                harness?.controller?.dispose()
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
            "meeting-ui-fixtures-simulated-v2",
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
            activeScenario.onActivity {
                val expectedPackageSize = expectedModelPackageSizeLabel()
                assertTrue(
                    "catalog v2 fixture visibly shows the $expectedPackageSize maximum package size",
                    findTextContaining(requireNotNull(harness).controller.view, expectedPackageSize) != null,
                )
            }
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
            settleAfterOrientationChange(instrumentation)
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

            activeScenario.onActivity { activity ->
                setFontScale(activity, initialFontScale)
                requireNotNull(harness).controller.dispose()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            assertTrue("Activity returns to portrait", awaitOrientation(activeScenario, Configuration.ORIENTATION_PORTRAIT))
            settleAfterOrientationChange(instrumentation)
            activeScenario.onActivity { activity -> harness = attachPanel(activity, "conversation-flow-normal") }
            instrumentation.waitForIdleSync()
            activeScenario.onActivity {
                val active = requireNotNull(harness)
                active.controller.view.recyclerView.scrollToPosition(0)
                assertEquals(
                    listOf("a-first", "a-continuation", "b-reply", "a-return", "uncertain"),
                    (0 until active.controller.view.adapter.itemCount).mapNotNull {
                        active.controller.view.adapter.rowAt(it)?.row?.turnId
                    },
                )
                assertEquals(true, active.controller.view.adapter.rowAt(1)?.row?.showSpeakerHeading == false)
                assertEquals(1_000L, active.controller.view.adapter.rowAt(0)?.row?.audioStartMs)
                assertEquals(null, active.controller.view.adapter.rowAt(4)?.row?.audioStartMs)
                assertEquals(
                    "La prochaine étape sera de partager les résultats. ${noteImage(7).marker}",
                    active.controller.view.adapter.rowAt(3)?.row?.body,
                )
                assertEquals(7, active.controller.view.adapter.rowAt(3)?.images?.singleOrNull()?.number)
                active.controller.updateLiveProgress(
                    progress(pendingAudioMs = 5_000L),
                    MeetingVoiceProgress(MeetingVoiceState.ACTIVE, pendingAudioMs = 4_000L),
                )
                val status = findTextContaining(active.controller.view, "Écoute en cours")
                assertTrue("normal fixture visibly shows the ASR backlog", status?.text?.contains("5 s d’audio à traiter") == true)
                val title = (active.controller.view.getChildAt(0) as ViewGroup).getChildAt(0) as TextView
                assertTrue(
                    "normal fixture exposes voice backlog to accessibility",
                    title.contentDescription?.contains("Voix : 4 s d’audio en attente") == true,
                )
                assertTrue(
                    "normal portrait fixture starts with the beginning of the conversation visible",
                    active.controller.view.recyclerView.findViewHolderForAdapterPosition(0) != null,
                )
            }
            capture(instrumentation, captureDirectory, "13-conversation-aaba-normal-top-portrait-simulated.png")

            activeScenario.onActivity {
                val active = requireNotNull(harness)
                active.controller.view.recyclerView.scrollToPosition(active.controller.view.adapter.itemCount - 1)
            }
            instrumentation.waitForIdleSync()
            activeScenario.onActivity {
                val active = requireNotNull(harness)
                assertTrue(
                    "normal portrait fixture reaches the end of the conversation",
                    active.controller.view.recyclerView.findViewHolderForAdapterPosition(
                        active.controller.view.adapter.itemCount - 1,
                    ) != null,
                )
            }
            capture(instrumentation, captureDirectory, "14-conversation-aaba-normal-bottom-portrait-simulated.png")

            activeScenario.onActivity { activity ->
                requireNotNull(harness).controller.dispose()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            assertTrue("Activity returns to landscape", awaitOrientation(activeScenario, Configuration.ORIENTATION_LANDSCAPE))
            settleAfterOrientationChange(instrumentation)
            activeScenario.onActivity { activity -> harness = attachPanel(activity, "conversation-flow-compact") }
            instrumentation.waitForIdleSync()
            activeScenario.onActivity { activity ->
                val active = requireNotNull(harness)
                val density = activity.resources.displayMetrics.density
                assertEquals((240 * density).toInt(), active.controller.view.width)
                assertEquals((112 * density).toInt(), active.controller.view.height)
                active.controller.updateLiveProgress(
                    progress(pendingAudioMs = 5_000L),
                    MeetingVoiceProgress(MeetingVoiceState.ACTIVE, pendingAudioMs = 4_000L),
                )
                active.controller.view.recyclerView.scrollToPosition(0)
            }
            instrumentation.waitForIdleSync()
            capture(instrumentation, captureDirectory, "15-conversation-aaba-compact-top-backlog-simulated.png")
            activeScenario.onActivity {
                val active = requireNotNull(harness)
                active.controller.updateLiveProgress(
                    progress(pendingAudioMs = 0L),
                    MeetingVoiceProgress(
                        MeetingVoiceState.UNAVAILABLE,
                        pendingAudioMs = 0L,
                        unavailableReason = MeetingVoiceUnavailableReason.MODEL_LOAD_FAILED,
                    ),
                )
                assertTrue(
                    "compact fixture visibly distinguishes unavailable voice identification",
                    findTextContaining(active.controller.view, "Voix indisponibles") != null,
                )
                val title = (active.controller.view.getChildAt(0) as ViewGroup).getChildAt(0) as TextView
                assertTrue(
                    "compact fixture keeps the full voice failure available to accessibility",
                    title.contentDescription?.contains("Identification des voix indisponible") == true,
                )
            }
            capture(instrumentation, captureDirectory, "16-conversation-aaba-compact-voices-unavailable-simulated.png")
            activeScenario.onActivity { activity ->
                val active = requireNotNull(harness)
                active.controller.updateLiveProgress(
                    progress(pendingAudioMs = 5_000L),
                    MeetingVoiceProgress(MeetingVoiceState.ACTIVE, pendingAudioMs = 4_000L),
                )
                active.controller.view.recyclerView.scrollToPosition(active.controller.view.adapter.itemCount - 1)
            }
            instrumentation.waitForIdleSync()
            capture(instrumentation, captureDirectory, "17-conversation-aaba-compact-bottom-simulated.png")
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

    private fun attachPanel(
        activity: MainActivity,
        scene: String,
        overrideState: FixtureState? = null,
    ): PanelHarness {
        val themeValue = if (scene == "dark") "dark" else "light"
        activity.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", themeValue)
            .commit()
        val state = overrideState ?: fixtureState(scene)
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
        val panelLayout = if (scene == "compact-landscape" || scene == "conversation-flow-compact") {
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
                    modelSize = expectedModelPackageSizeLabel(),
                ),
            )
            "delayed-active-edit" -> FixtureState(
                MeetingDocument(
                    sessionId = "fixture-delayed-active-edit-2026-09-27",
                    runId = "fixture-delayed-active-edit-run",
                    participants = emptyList(),
                    turns = listOf(
                        MeetingTurn(
                            id = "edit-draft",
                            utteranceId = 93L,
                            startMs = 2_200L,
                            endMs = 2_700L,
                            recognizedText = "Cette phrase reste en cours de correction.",
                            automaticParticipantId = null,
                            attributionStable = false,
                            timingKnown = true,
                        ),
                    ),
                ),
                emptyList(),
                MeetingPanelStatus(phase = MeetingPanelStatus.Phase.LISTENING),
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
            "conversation-flow-normal", "conversation-flow-compact" -> FixtureState(
                conversationFlowDocument(),
                listOf(noteImage(7)),
                MeetingPanelStatus(phase = MeetingPanelStatus.Phase.LISTENING),
            )
            else -> FixtureState(base, emptyList(), MeetingPanelStatus(phase = MeetingPanelStatus.Phase.LISTENING))
        }
    }

    private fun conversationFlowDocument() = MeetingDocument(
        sessionId = "fixture-conversation-flow-2026-09-27",
        runId = "fixture-conversation-flow-run",
        participants = listOf(
            MeetingParticipant("profile-a", ordinal = 1, channel = 1, name = "Sophie"),
            MeetingParticipant("profile-b", ordinal = 2, channel = 2, name = "Karim Benali"),
        ),
        turns = listOf(
            MeetingTurn(
                id = "a-continuation",
                utteranceId = 2L,
                startMs = 1_600L,
                endMs = 2_050L,
                recognizedText = "La décision sera prise après la vérification.",
                automaticParticipantId = "profile-a",
                attributionStable = true,
                timingKnown = true,
            ),
            MeetingTurn(
                id = "b-reply",
                utteranceId = 3L,
                startMs = 2_400L,
                endMs = 2_900L,
                recognizedText = "Je confirme, nous gardons ce point à l’ordre du jour.",
                automaticParticipantId = "profile-b",
                attributionStable = true,
                timingKnown = true,
            ),
            MeetingTurn(
                id = "a-return",
                utteranceId = 4L,
                startMs = 3_300L,
                endMs = 3_900L,
                recognizedText = "La prochaine étape sera de partager les résultats.",
                editedText = "La prochaine étape sera de partager les résultats. ${noteImage(7).marker}",
                automaticParticipantId = null,
                manualParticipantId = "profile-a",
                hasManualAttribution = true,
                attributionStable = true,
                timingKnown = true,
            ),
            MeetingTurn(
                id = "a-first",
                utteranceId = 1L,
                startMs = 1_000L,
                endMs = 1_450L,
                recognizedText = "Nous avons avancé sur la préparation.",
                automaticParticipantId = "profile-a",
                attributionStable = true,
                timingKnown = true,
            ),
            MeetingTurn(
                id = "uncertain",
                utteranceId = 5L,
                startMs = 0L,
                endMs = 0L,
                recognizedText = "Passage à attribuer après la reprise.",
                automaticParticipantId = null,
                attributionStable = false,
                timingKnown = false,
            ),
        ),
    )

    private fun progress(pendingAudioMs: Long) = MeetingProgressSnapshot(
        capturedAudioMs = pendingAudioMs,
        processedAudioMs = 0L,
        pendingAudioMs = pendingAudioMs,
        queuedAudioMs = pendingAudioMs,
        inFlightAudioMs = 0L,
        discardedAudioMs = 0L,
        nativeProcessingMs = 0L,
        inFlightProcessingMs = 0L,
        processingCostRatio = null,
        captureElapsedMs = 0L,
    )

    private fun timedWords(transcript: String, channelForWord: (Int) -> Int): List<MeetingWord> =
        transcript.split(' ').mapIndexed { index, word ->
            val startMs = 100L + index * 120L
            MeetingWord(
                text = word,
                startMs = startMs,
                endMs = startMs + 80L,
                channel = channelForWord(index),
            )
        }

    private fun expectedModelPackageSizeLabel(): String {
        val packageBytes = MeetingModelCatalog.production.totalBytes
        val packageMegabytes = (packageBytes + 999_999L) / 1_000_000L
        return "$packageMegabytes Mo"
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

    private fun awaitImeVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The emulator's first Gboard render can be slow while it initializes keyboard views.
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var visible = false
            scenario.onActivity { activity ->
                visible = activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
            if (visible) return true
            SystemClock.sleep(100L)
        }
        return false
    }

    private fun settleAfterOrientationChange(instrumentation: android.app.Instrumentation) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(800)
        instrumentation.waitForIdleSync()
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

    private fun findTextContaining(root: View, expected: String): TextView? {
        if (root is TextView && expected in root.text.toString()) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) findTextContaining(root.getChildAt(index), expected)?.let { return it }
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
