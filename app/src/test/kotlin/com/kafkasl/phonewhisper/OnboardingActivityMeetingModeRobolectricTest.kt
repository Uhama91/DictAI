package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingActivityMeetingModeRobolectricTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private var controller: ActivityController<OnboardingActivity>? = null
    private var activeRun: TranscriptionRunLease? = null
    private var storeFixture: MeetingActivityStoreFixture? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putBoolean("onb_complete", false)
            .putString("transcription_mode", TranscriptionMode.MEETING.preferenceValue)
            .commit()
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
    }

    @Test
    fun meetingOnboardingOffersBothModesAndOnlyInspectsTheInjectedMeetingModels() {
        val fixture = newStoreFixture()
        val states = fixture.observeStates()
        val coordinator = TranscriptionModeCoordinator.process(context)
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        val activity = launchOnboardingActivity(fixture)

        assertNotNull("onboarding must expose the Réunion mode choice", clickableChoice(activity.window.decorView, "Réunion"))
        assertNotNull("onboarding must expose the Dictée mode choice", clickableChoice(activity.window.decorView, "Dictée"))
        assertNull("Réunion onboarding must not require the Dictée model", textView(activity, "Modèle de transcription (FR/EN)"))
        assertNull("Réunion onboarding must not require accessibility", textView(activity, "Service d'accessibilité"))
        assertNotNull("meeting setup must show its model installation", textView(activity, "Modèles Réunion"))
        assertTrue("the meeting model package size is visible", allViews(activity.window.decorView)
            .filterIsInstance<TextView>()
            .any { it.text.toString().contains("849") && it.text.toString().contains("Mo") })
        assertNotNull("accessibility remains described as optional", allViews(activity.window.decorView)
            .filterIsInstance<TextView>()
            .firstOrNull { it.text.toString().contains("captures d’écran") })
        assertTrue("the screen inspects local model state", states.awaitInspection())
        assertEquals("opening onboarding must not download models", 0, fixture.requests.get())
        assertNull("opening onboarding must not create a recording", coordinator.snapshot().activeRunMode)
        assertEquals(0, coordinator.snapshot().pendingDictationLoads)
        states.close()
    }

    @Test
    fun choosingDictationRestoresItsModelStepWithoutStartingAudio() {
        val coordinator = TranscriptionModeCoordinator.process(context)
        val activity = launchOnboardingActivity()
        val dictationChoice = clickableChoice(activity.window.decorView, "Dictée")
        assertNotNull("onboarding must expose an explicit Dictée mode choice", dictationChoice)

        dictationChoice!!.performClick()

        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        assertNotNull("Dictée onboarding restores its offline model step", textView(activity, "Modèle de transcription (FR/EN)"))
        assertNull("Dictée choice must not reserve a recording", coordinator.snapshot().activeRunMode)
    }

    @Test
    fun activeRunRefusesModeChangeAndKeepsDictationSelected() {
        preferences.edit().putString("transcription_mode", TranscriptionMode.DICTATION.preferenceValue).commit()
        TranscriptionModeCoordinator.clearProcessForTest()
        val coordinator = TranscriptionModeCoordinator.process(context)
        activeRun = requireNotNull(coordinator.reserveRun(TranscriptionMode.DICTATION))
        val activity = launchOnboardingActivity()
        val meetingChoice = clickableChoice(activity.window.decorView, "Réunion")
        assertNotNull(meetingChoice)

        meetingChoice!!.performClick()

        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        assertEquals("Terminer l’enregistrement avant de changer de mode", ShadowToast.getTextOfLatestToast().toString())
        val selected = allViews(activity.window.decorView).filterIsInstance<TextView>()
            .firstOrNull { it.text.toString().trim() == "Dictée" }
        assertTrue("the rejected change leaves Dictée selected", selected?.contentDescription.toString().contains("sélectionné"))
        assertTrue(coordinator.isCurrentRun(activeRun!!))
    }

    @Test
    fun finishingMeetingOnboardingArmsTheOverlayWithoutStartingARecording() {
        val appShadow = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val previousOverlayPermission = Settings.canDrawOverlays(context)
        val previousMicrophonePermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val previousNotificationPermission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val previousBatteryExemption = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        val shadowPowerManager = Shadows.shadowOf(powerManager)
        val previousOnboardingComplete = preferences.getBoolean("onb_complete", false)
        try {
            ShadowSettings.setCanDrawOverlays(true)
            appShadow.grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, true)

            val fixture = newStoreFixture()
            val coordinator = TranscriptionModeCoordinator.process(context)
            val activity = launchOnboardingActivity(fixture)
            assertTrue(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
            appShadow.clearStartedServices()

            activity.javaClass.getDeclaredMethod("finishSetup").apply { isAccessible = true }.invoke(activity)

            val overlayStarts = appShadow.allStartedServices
                .filter { it.component?.className == OverlayService::class.java.name }
            assertEquals("Meeting setup starts the FGS then sends its arm command", 2, overlayStarts.size)
            assertEquals(listOf(null, OverlayService.ACTION_ARM_MIC), overlayStarts.map { it.action })
            assertTrue("the setup action does not begin audio capture", coordinator.snapshot().activeRunMode == null)
            assertTrue(preferences.getBoolean("onb_complete", false))
        } finally {
            preferences.edit().putBoolean("onb_complete", previousOnboardingComplete).commit()
            ShadowSettings.setCanDrawOverlays(previousOverlayPermission)
            if (previousMicrophonePermission) appShadow.grantPermissions(Manifest.permission.RECORD_AUDIO)
            else appShadow.denyPermissions(Manifest.permission.RECORD_AUDIO)
            if (previousNotificationPermission) appShadow.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            else appShadow.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, previousBatteryExemption)
        }
    }

    @Test
    fun externalModeChangeRefreshesOnboardingAndPreservesTheSelectedMode() {
        val fixture = newStoreFixture()
        val coordinator = TranscriptionModeCoordinator.process(context)
        val activity = launchOnboardingActivity(fixture)

        assertTrue(coordinator.changeMode(TranscriptionMode.DICTATION))
        shadowOf(Looper.getMainLooper()).idle()

        val selected = allViews(activity.window.decorView).filterIsInstance<TextView>()
            .firstOrNull { it.text.toString().trim() == "Dictée" }
        assertTrue("the visible mode choice follows a change made elsewhere",
            selected?.contentDescription.toString().contains("sélectionné"))
        assertNotNull("Dictée-only onboarding steps return with the external mode change",
            textView(activity, "Modèle de transcription (FR/EN)"))
    }

    @Test
    fun xiaomiMeetingOnboardingDoesNotRequireRestrictedSettingsOrAccessibility() {
        preferences.edit().putString("transcription_mode", TranscriptionMode.MEETING.preferenceValue).commit()
        TranscriptionModeCoordinator.clearProcessForTest()
        val activity = launchOnboardingActivity(manufacturer = "Xiaomi")

        assertNull("restricted settings only unlock accessibility, which Meeting does not require",
            textView(activity, "Autoriser les paramètres restreints"))
        assertNull("Meeting must not make accessibility a required step",
            textView(activity, "Service d'accessibilité"))
        assertNotNull("the optional accessibility explanation remains visible",
            allViews(activity.window.decorView).filterIsInstance<TextView>()
                .firstOrNull { it.text.toString().contains("accessibilité permet les captures d’écran") })
    }

    private fun newStoreFixture(): MeetingActivityStoreFixture =
        MeetingActivityStoreFixture(context).also { storeFixture = it }

    private fun launchOnboardingActivity(
        fixture: MeetingActivityStoreFixture? = null,
        manufacturer: String? = null,
    ): OnboardingActivity {
        val next = Robolectric.buildActivity(OnboardingActivity::class.java)
        controller = next
        if (fixture != null) next.get().meetingModelStoreProvider = { fixture.store }
        if (manufacturer != null) next.get().manufacturerForTests = manufacturer
        return next.setup().get()
    }

    private fun textView(activity: OnboardingActivity, label: String): TextView? =
        allViews(activity.window.decorView).filterIsInstance<TextView>()
            .firstOrNull { it.text.toString().trim() == label }
}

private fun clickableChoice(root: View, label: String): View? =
    allViews(root).asSequence()
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

private fun allViews(root: View): List<View> = buildList {
    add(root)
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) addAll(allViews(root.getChildAt(index)))
    }
}
