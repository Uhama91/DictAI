package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipExecutor
import com.kafkasl.phonewhisper.meeting.MeetingHypothesis
import com.kafkasl.phonewhisper.meeting.MeetingMicrophoneFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingMicrophonePort
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailability
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailabilityPort
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingNativeAdmission
import com.kafkasl.phonewhisper.meeting.MeetingPanelSessionCommand
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPhase
import com.kafkasl.phonewhisper.meeting.MeetingSession
import com.kafkasl.phonewhisper.meeting.MeetingSessionFactoryPort
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceMeetingLifecycleRobolectricTest {
    private lateinit var context: Context
    private lateinit var coordinator: TranscriptionModeCoordinator
    private var previousMode = TranscriptionMode.DICTATION
    private var previousFormat: String? = null
    private var previousAnimatorDurationScale = 1f
    private var serviceController: ServiceController<OverlayService>? = null
    private var service: OverlayService? = null
    private var ownedController: MeetingRecordingController? = null
    private var sessionFactory: ControlledSessionFactory? = null
    private var microphoneFactory: ControlledMicrophoneFactory? = null
    private var draftDirectory: File? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        previousAnimatorDurationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val prefs = PersistencePrefs(context)
        previousMode = prefs.transcriptionMode
        previousFormat = context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
            .getString("selected", null)
        TranscriptionModeCoordinator.clearProcessForTest()
        prefs.transcriptionMode = TranscriptionMode.DICTATION
        coordinator = TranscriptionModeCoordinator.process(context)
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val prior = cleanupFailure
                if (prior == null) cleanupFailure = failure else prior.addSuppressed(failure)
            }
        }

        val runningService = service
        val activeController = ownedController
        var writerClaimPresent = false
        if (runningService != null) cleanup {
            writerClaimPresent = onMain {
                field<Any?>(runningService, "meetingDraftClaim") != null ||
                    field<Any?>(runningService, "meetingDraftClaimRequest") != null
            }
        }
        var draftCloseConfirmed = activeController == null && !writerClaimPresent
        cleanup { OverlayService.clearMeetingTestOverridesFactoryForTest() }
        cleanup { onMain { serviceController?.destroy(); Unit } }
        microphoneFactory?.microphones?.forEach { microphone ->
            cleanup { microphone.stopCompletion.complete(Unit) }
        }
        sessionFactory?.sessions?.forEach { session ->
            cleanup { session.closed.complete(Unit) }
        }
        var destroyFuture: CompletableFuture<Unit>? = null
        if (activeController != null) cleanup { destroyFuture = onMain { activeController.destroy() } }
        cleanup {
            destroyFuture?.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.let { draftCloseConfirmed = true }
        }
        cleanup {
            awaitMainCondition {
                coordinator.snapshot().activeRunMode == null
            }
        }

        cleanup { PersistencePrefs(context).transcriptionMode = previousMode }
        cleanup {
            val prefs = context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (previousFormat == null) editor.remove("selected") else editor.putString("selected", previousFormat)
            check(editor.commit()) { "Could not restore the Dictation format preference" }
        }
        cleanup { TranscriptionModeCoordinator.clearProcessForTest() }
        cleanup {
            check(Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                previousAnimatorDurationScale,
            )) { "Could not restore the animator duration scale" }
        }
        cleanup { draftDirectory?.let { directory ->
            if (directory.exists()) {
                check(draftCloseConfirmed) { "Refusing to remove this fixture's draft before writer closure is confirmed" }
                check(directory.deleteRecursively()) { "Could not remove this fixture's draft directory" }
            }
        } }
        cleanup { ShadowSettings.reset() }

        cleanupFailure?.let { throw AssertionError("Meeting lifecycle fixture cleanup failed", it) }
    }

    @Test
    fun `pause and resume keep the same native session and meeting lease`() {
        startServiceWithFakes(deferMicrophoneStop = false, closeNativeOnCancel = true)
        openMeetingPanel()
        val controller = requireNotNull(ownedController)

        sendCommand(MeetingPanelSessionCommand.START)
        awaitMainCondition {
            onMain {
                controller.state.phase == MeetingRecordingPhase.LISTENING &&
                    requireNotNull(microphoneFactory).microphones.size == 1
            }
        }

        val session = requireNotNull(sessionFactory).sessions.single()
        val sessionId = onMain { controller.state.document.sessionId }
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
        assertFalse("an active meeting run must reject switching back to Dictation", coordinator.changeMode(TranscriptionMode.DICTATION))

        sendCommand(MeetingPanelSessionCommand.PAUSE)
        awaitMainCondition { onMain { controller.state.phase == MeetingRecordingPhase.PAUSED } }

        assertEquals("pause keeps the same document", sessionId, onMain { controller.state.document.sessionId })
        assertEquals("pause retains the meeting lease", TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
        assertFalse("the lease remains held while paused", coordinator.changeMode(TranscriptionMode.DICTATION))
        assertEquals("pausing must not cancel the native session", 0, session.cancelCalls.get())
        assertEquals("the reader stopped before the controller entered PAUSED", 1, requireNotNull(microphoneFactory).microphones.first().stopCalls.get())

        sendCommand(MeetingPanelSessionCommand.RESUME)
        awaitMainCondition {
            onMain {
                controller.state.phase == MeetingRecordingPhase.LISTENING &&
                    requireNotNull(microphoneFactory).microphones.size == 2
            }
        }

        assertEquals("resume continues the same meeting document", sessionId, onMain { controller.state.document.sessionId })
        assertEquals("resume must not create a second native session", 1, requireNotNull(sessionFactory).sessions.size)
        assertSame(session, requireNotNull(sessionFactory).sessions.single())
        assertEquals("the same meeting run remains admitted", TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
        assertFalse("Dictation remains unavailable after resume", coordinator.changeMode(TranscriptionMode.DICTATION))
        assertEquals("the first recorder was stopped exactly once", 1, requireNotNull(microphoneFactory).microphones.first().stopCalls.get())
        assertEquals("the resumed capture uses one new microphone", 1, requireNotNull(microphoneFactory).microphones.last().startCalls.get())
        assertEquals("resume still owns one native session", 0, session.cancelCalls.get())
    }

    @Test
    fun `service destruction retains the lease until reader and native close both finish`() {
        startServiceWithFakes(deferMicrophoneStop = true, closeNativeOnCancel = false)
        openMeetingPanel()
        val currentService = requireNotNull(service)
        val controller = requireNotNull(ownedController)
        sendCommand(MeetingPanelSessionCommand.START)
        awaitMainCondition {
            onMain {
                controller.state.phase == MeetingRecordingPhase.LISTENING &&
                    requireNotNull(microphoneFactory).microphones.size == 1
            }
        }

        val session = requireNotNull(sessionFactory).sessions.single()
        val microphone = requireNotNull(microphoneFactory).microphones.single()
        val sessionId = onMain { controller.state.document.sessionId }
        val destroyFuture = onMain {
            requireNotNull(serviceController).destroy()
            controller.destroy()
        }

        assertEquals("onDestroy requests a reader stop", 1, microphone.stopCalls.get())
        assertFalse("the simulated reader has not exited yet", microphone.stopCompletion.isDone)
        assertEquals("native cancellation waits for the reader's safe stop", 0, session.cancelCalls.get())
        assertEquals("the meeting lease remains held while the reader is active", TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
        assertFalse(coordinator.changeMode(TranscriptionMode.DICTATION))
        assertMeetingUiWasRemoved(currentService)

        session.emitLateHypothesis()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertMeetingUiWasRemoved(currentService)
        assertFalse("destruction cannot complete while either resource is open", destroyFuture.isDone)

        microphone.stopCompletion.complete(Unit)
        awaitMainCondition { session.cancelCalls.get() == 1 }
        assertTrue("the microphone barrier is complete", microphone.stopCompletion.isDone)
        assertFalse("the native close barrier is still pending", session.closed.isDone)
        assertFalse("the lease stays held after reader stop until native close", destroyFuture.isDone)
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().activeRunMode)
        assertFalse(coordinator.changeMode(TranscriptionMode.DICTATION))

        session.emitLateHypothesis()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertMeetingUiWasRemoved(currentService)

        session.closed.complete(Unit)
        destroyFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        awaitMainCondition { coordinator.snapshot().activeRunMode == null }

        assertFalse("safe closure does not poison the process", coordinator.snapshot().poisoned)
        assertTrue("Dictation becomes selectable only after both closures", coordinator.changeMode(TranscriptionMode.DICTATION))
        session.emitLateHypothesis()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertMeetingUiWasRemoved(currentService)
        assertFalse(
            "late native output cannot create a durable note",
            context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE).contains(sessionId),
        )
    }

    private fun startServiceWithFakes(deferMicrophoneStop: Boolean, closeNativeOnCancel: Boolean) {
        assertTrue(coordinator.changeMode(TranscriptionMode.MEETING))
        val sessionPorts = ControlledSessionFactory(closeNativeOnCancel)
        val microphonePorts = ControlledMicrophoneFactory(
            stopCompletion = if (deferMicrophoneStop) CompletableFuture() else CompletableFuture.completedFuture(Unit),
        )
        sessionFactory = sessionPorts
        microphoneFactory = microphonePorts
        val directory = File(context.cacheDir, "meeting-lifecycle-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Could not create isolated meeting lifecycle draft directory" }
        }
        draftDirectory = directory
        val admission = MeetingNativeAdmission(
            coordinator = coordinator,
            closeResidentDictation = { CompletableFuture.completedFuture(Unit) },
        )
        val overrides = OverlayService.MeetingTestOverrides(
            draftOwnership = MeetingDraftOwnership(executor = MeetingDraftOwnershipExecutor { task -> task() }),
            draftFile = File(directory, "draft.json"),
            modelStore = MeetingModelStore.shared(context),
            modelAvailability = MeetingModelAvailabilityPort { MeetingModelAvailability.READY },
            reservation = admission,
            sessionFactory = sessionPorts,
            microphoneFactory = microphonePorts,
        )
        assertTrue(OverlayService.setMeetingTestOverridesFactoryForTest { overrides })
        val created = Robolectric.buildService(OverlayService::class.java).create()
        serviceController = created
        service = created.get()
        OverlayService.clearMeetingTestOverridesFactoryForTest()
        onMain { invokeNoArgs(requireNotNull(service), "showButton") }
    }

    private fun openMeetingPanel() {
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        val currentService = requireNotNull(service)
        onMain {
            currentService.javaClass.getDeclaredMethod(
                "showFormatPicker",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(currentService, false, true)
            Unit
        }
        awaitMainCondition {
            onMain { findText(field<View?>(currentService, "floatingMenu"), "Ouvrir la réunion") != null }
        }
        val openMeeting = onMain {
            requireNotNull(findText(field<View?>(currentService, "floatingMenu"), "Ouvrir la réunion"))
        }
        onMain { openMeeting.performClick() }
        awaitMainCondition {
            onMain {
                field<MeetingRecordingController?>(currentService, "meetingRecordingController").also {
                    ownedController = it
                } != null
            }
        }
    }

    private fun sendCommand(command: MeetingPanelSessionCommand) {
        val currentService = requireNotNull(service)
        onMain {
            currentService.javaClass.getDeclaredMethod(
                "handleMeetingPanelCommand",
                MeetingPanelSessionCommand::class.java,
            ).apply { isAccessible = true }.invoke(currentService, command)
            Unit
        }
    }

    private fun assertMeetingUiWasRemoved(target: OverlayService) {
        onMain {
            assertNull(field<Any?>(target, "meetingPanelController"))
            assertNull(field<Any?>(target, "meetingRecordingController"))
            assertEquals(false, field<Boolean>(target, "meetingSurfaceOpen"))
            assertNull(field<Any?>(target, "pill"))
            assertNull(field<Any?>(target, "container"))
        }
    }

    private fun awaitMainCondition(timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadlineNanos) {
            looper.idle()
            if (condition()) return
            Thread.sleep(5L)
        }
        looper.idle()
        assertTrue("condition should complete within ${timeoutMs} ms", condition())
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>()
        val completed = AtomicInteger()
        Handler(Looper.getMainLooper()).post {
            try {
                result.set(action())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                completed.set(1)
            }
        }
        awaitMainCondition { completed.get() == 1 }
        failure.get()?.let { throw AssertionError("main-thread fixture action failed", it) }
        return requireNotNull(result.get())
    }

    private fun invokeNoArgs(target: Any, methodName: String): Unit {
        target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(target)
    }

    private inline fun <reified T> field(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.get(target) as T
    }

    private fun findText(root: View?, expected: String): TextView? {
        if (root == null) return null
        if (root is TextView && root.text.toString() == expected) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) findText(root.getChildAt(index), expected)?.let { return it }
        }
        return null
    }

    private class ControlledSessionFactory(
        private val closeNativeOnCancel: Boolean,
    ) : MeetingSessionFactoryPort {
        val sessions = CopyOnWriteArrayList<ControlledMeetingSession>()

        override fun start(
            runId: String,
            language: String,
            onReady: () -> Unit,
            onUpdate: (MeetingHypothesis) -> Unit,
            onFailure: (String) -> Unit,
        ): MeetingSession {
            val session = ControlledMeetingSession(runId, closeNativeOnCancel, onUpdate)
            sessions += session
            onReady()
            return session
        }
    }

    private class ControlledMeetingSession(
        val runId: String,
        private val closeNativeOnCancel: Boolean,
        private val onUpdate: (MeetingHypothesis) -> Unit,
    ) : MeetingSession {
        override val closed = CompletableFuture<Unit>()
        override val queuedAudioMs: Long = 0L
        val cancelCalls = AtomicInteger()

        override fun acceptPcm16(buffer: ByteArray, length: Int): Boolean = true

        override fun checkpoint(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

        override fun finish(): Unit {
            if (closeNativeOnCancel) closed.complete(Unit)
        }

        override fun cancel(): Unit {
            cancelCalls.incrementAndGet()
            if (closeNativeOnCancel) closed.complete(Unit)
        }

        override fun close(): Unit = cancel()

        fun emitLateHypothesis(): Unit = onUpdate(
            MeetingHypothesis(
                runId = runId,
                utteranceId = 1L,
                revision = 1L,
                words = emptyList(),
                transcript = "late callback",
                isFinal = true,
                stableSpeakerThroughMs = 0L,
                audioProcessedMs = 0L,
            ),
        )
    }

    private class ControlledMicrophoneFactory(
        private val stopCompletion: CompletableFuture<Unit>,
    ) : MeetingMicrophoneFactoryPort {
        val microphones = CopyOnWriteArrayList<ControlledMicrophone>()

        override fun create(): MeetingMicrophonePort = ControlledMicrophone(stopCompletion).also(microphones::add)
    }

    private class ControlledMicrophone(
        val stopCompletion: CompletableFuture<Unit>,
    ) : MeetingMicrophonePort {
        val startCalls = AtomicInteger()
        val stopCalls = AtomicInteger()

        override fun start(
            onPcm16: (ByteArray, Int) -> Boolean,
            onFailure: (String) -> Unit,
        ): Boolean {
            startCalls.incrementAndGet()
            return true
        }

        override fun stopAndJoin(): CompletableFuture<Unit> {
            stopCalls.incrementAndGet()
            return stopCompletion
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val TIMEOUT_MS = TIMEOUT_SECONDS * 1000L
    }
}
