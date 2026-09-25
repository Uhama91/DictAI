package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityMeetingModeRobolectricTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private var controller: ActivityController<MainActivity>? = null
    private var activeRun: TranscriptionRunLease? = null
    private var storeFixture: MeetingActivityStoreFixture? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putBoolean("onb_complete", true)
            .putString("transcription_mode", TranscriptionMode.DICTATION.preferenceValue)
            .commit()
        context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE).edit().clear().commit()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        TranscriptionModeCoordinator.clearProcessForTest()
    }

    @After
    fun tearDown() {
        activeRun?.close()
        activeRun = null
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        storeFixture?.close()
        storeFixture = null
        TranscriptionModeCoordinator.clearProcessForTest()
        preferences.edit().clear().commit()
        context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun selectingMeetingChangesTheProcessModeWithoutChangingTheDictationFormatOrStartingAudio() {
        val formats = PostProcessingFormats(context)
        formats.select(PostProcessingFormats.builtins.first { it.id == "list" })
        val coordinator = TranscriptionModeCoordinator.process(context)
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        val fixture = newStoreFixture()
        val activity = launchMainActivity(fixture)
        val meetingChoice = clickableChoice(activity.window.decorView, "Réunion")
        assertNotNull("home must expose an explicit Réunion mode choice", meetingChoice)

        meetingChoice!!.performClick()

        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        assertEquals("list", formats.selected().id)
        assertNull("choosing a mode must not reserve a recording run", coordinator.snapshot().activeRunMode)
        assertEquals("mode selection must not download models", 0, fixture.requests.get())
    }

    @Test
    fun activeDictationRunKeepsTheCurrentModeAndExplainsTheRefusal() {
        val coordinator = TranscriptionModeCoordinator.process(context)
        activeRun = requireNotNull(coordinator.reserveRun(TranscriptionMode.DICTATION))
        val activity = launchMainActivity()
        val meetingChoice = clickableChoice(activity.window.decorView, "Réunion")
        assertNotNull("home must expose a Réunion mode choice while a run is active", meetingChoice)

        meetingChoice!!.performClick()

        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        assertEquals(
            "Terminer l’enregistrement avant de changer de mode",
            ShadowToast.getTextOfLatestToast().toString(),
        )
        assertTrue("the running lease remains current", coordinator.isCurrentRun(activeRun!!))
    }

    @Test
    fun externalModeChangeRefreshesTheVisibleHomeSelection() {
        val coordinator = TranscriptionModeCoordinator.process(context)
        val activity = launchMainActivity()

        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        shadowOf(Looper.getMainLooper()).idle()

        val meetingButton = clickableChoice(activity.window.decorView, "Réunion") as? android.widget.Button
        assertNotNull(meetingButton)
        assertTrue("a mode changed elsewhere is reflected on the visible screen", meetingButton!!.isSelected)
        assertTrue(meetingButton.contentDescription.toString().contains("sélectionné"))
    }

    @Test
    fun meetingHomeResumeArmsTheVisibleOverlayWithoutStartingARecording() {
        val previousOverlayPermission = Settings.canDrawOverlays(context)
        val coordinator = TranscriptionModeCoordinator.process(context)
        val previousMode = coordinator.snapshot().mode
        try {
            ShadowSettings.setCanDrawOverlays(true)
            assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()

            val activity = launchMainActivity()

            assertTrue(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            val overlayActions = Shadows.shadowOf(RuntimeEnvironment.getApplication()).allStartedServices
                .filter { it.component?.className == OverlayService::class.java.name }
                .map { it.action }
            assertEquals("a visible Meeting Activity arms only the overlay service", listOf(OverlayService.ACTION_ARM_MIC), overlayActions)
            assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
            assertNull("arming the overlay does not reserve a recording run", coordinator.snapshot().activeRunMode)
        } finally {
            if (coordinator.snapshot().activeRunMode == null) coordinator.changeMode(previousMode)
            ShadowSettings.setCanDrawOverlays(previousOverlayPermission)
        }
    }

    @Test
    fun poisonedEngineShowsUnavailableBeforeRetainedRunRefusal() {
        val coordinator = TranscriptionModeCoordinator.process(context)
        activeRun = requireNotNull(coordinator.reserveRun(TranscriptionMode.DICTATION))
        coordinator.reportUncertainClose()
        val activity = launchMainActivity()
        val meetingChoice = clickableChoice(activity.window.decorView, "Réunion")

        assertNotNull(meetingChoice)
        meetingChoice!!.performClick()

        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().activeRunMode)
        assertEquals(TRANSCRIPTION_MODE_UNAVAILABLE_MESSAGE, ShadowToast.getTextOfLatestToast().toString())
    }

    @Test
    fun openingMeetingSettingsInspectsModelsWithoutDownloadingOrStartingAudio() {
        val fixture = newStoreFixture()
        val states = fixture.observeStates()
        val coordinator = TranscriptionModeCoordinator.process(context)
        val activity = launchMainActivity(fixture)
        val settings = clickableChoice(activity.window.decorView, "Réglages Réunion")
        assertNotNull("meeting settings must be reachable next to Dictée", settings)

        settings!!.performClick()

        assertNotNull(textView(activity.window.decorView, "Modèles Réunion"))
        assertNotNull(clickableChoice(activity.window.decorView, "Télécharger les modèles Réunion"))
        assertTrue("opening the settings should finish a local model inspection", states.awaitInspection())
        val status = activity.window.decorView.findViewWithTag<TextView>(MeetingModelSettingsPanel.STATUS_TAG)
        assertNotNull(status)
        assertNull("TalkBack should read the changing status text, not a constant description", status!!.contentDescription)
        assertEquals("Modèles absents", status.text.toString())
        assertEquals("opening the page must not issue an HTTP request", 0, fixture.requests.get())
        assertNull("opening settings must not start a recording", coordinator.snapshot().activeRunMode)
        assertEquals(0, coordinator.snapshot().pendingDictationLoads)
        states.close()
    }

    @Test
    fun modelProgressUpdatesTheSamePanelAndLateCallbackAfterDestroyCannotRender() {
        val fixture = newStoreFixture(blockRequests = true)
        val states = fixture.observeStates()
        val activity = launchMainActivity(fixture)
        clickableChoice(activity.window.decorView, "Réglages Réunion")!!.performClick()
        assertTrue(states.awaitInspection())
        val panelBefore = activity.window.decorView.findViewWithTag<View>(MeetingModelSettingsPanel.PANEL_TAG)
        val status = activity.window.decorView.findViewWithTag<TextView>(MeetingModelSettingsPanel.STATUS_TAG)
        assertNotNull(panelBefore)
        assertNotNull(status)

        clickableChoice(activity.window.decorView, "Télécharger les modèles Réunion")!!.performClick()
        assertTrue("the explicit download action should reach the isolated HTTP interceptor",
            fixture.requestEntered.await(3, TimeUnit.SECONDS))
        assertTrue(states.awaitState { it is MeetingModelStoreState.Downloading } is MeetingModelStoreState.Downloading)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val downloadingText = status!!.text.toString()
        assertTrue("progress is reflected in the existing status view", downloadingText.contains("Téléchargement"))
        assertSame(panelBefore, activity.window.decorView.findViewWithTag<View>(MeetingModelSettingsPanel.PANEL_TAG))

        controller!!.pause().stop().destroy()
        controller = null
        fixture.releasePendingRequest()
        assertTrue(states.awaitState { it is MeetingModelStoreState.Error } is MeetingModelStoreState.Error)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("a callback after destruction cannot mutate the detached panel", downloadingText, status.text.toString())
        states.close()
    }

    private fun newStoreFixture(blockRequests: Boolean = false): MeetingActivityStoreFixture =
        MeetingActivityStoreFixture(context, blockRequests).also { storeFixture = it }

    private fun launchMainActivity(fixture: MeetingActivityStoreFixture? = null): MainActivity {
        val next = Robolectric.buildActivity(MainActivity::class.java)
        controller = next
        if (fixture != null) next.get().meetingModelStoreProvider = { fixture.store }
        return next.setup().get()
    }
}

private fun clickableChoice(root: View, label: String): View? =
    allActivityViews(root).asSequence()
        .filterIsInstance<TextView>()
        .filter { it.text.toString().trim() == label }
        .mapNotNull { node ->
            var current: View? = node
            while (current != null) {
                if (current.isClickable && current.isEnabled && current.visibility == View.VISIBLE) return@mapNotNull current
                current = current.parent as? ViewGroup
            }
            null
        }
        .firstOrNull()

private fun textView(root: View, label: String): TextView? =
    allActivityViews(root).filterIsInstance<TextView>().firstOrNull { it.text.toString().trim() == label }

private fun allActivityViews(root: View): List<View> = buildList {
    add(root)
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) addAll(allActivityViews(root.getChildAt(index)))
    }
}
