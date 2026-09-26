package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipExecutor
import com.kafkasl.phonewhisper.meeting.MeetingModelCatalog
import com.kafkasl.phonewhisper.meeting.MeetingModelArtifact
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationPort
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationRequest
import com.kafkasl.phonewhisper.meeting.MeetingNativeRuntimeLease
import com.kafkasl.phonewhisper.meeting.MeetingPanelController
import com.kafkasl.phonewhisper.meeting.MeetingPanelStatus
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPhase
import com.kafkasl.phonewhisper.meeting.MeetingSession
import com.kafkasl.phonewhisper.meeting.MeetingSessionFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingHypothesis
import com.kafkasl.phonewhisper.meeting.MeetingMicrophoneFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingMicrophonePort
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowSettings

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceMeetingReadinessRobolectricTest {
    private lateinit var context: Context
    private lateinit var store: MeetingModelStore
    private lateinit var storeWorker: ExecutorService
    private lateinit var modelRoot: File
    private lateinit var draftFile: File
    private lateinit var coordinator: TranscriptionModeCoordinator
    private lateinit var serviceController: ServiceController<OverlayService>
    private lateinit var service: OverlayService
    private lateinit var previousMode: TranscriptionMode
    private var blocker: (MeetingModelStoreState) -> Unit = {}
    private var blockerRegistered = false
    private var modeChanged = false
    private val releaseChecking = CountDownLatch(1)
    private val checkingEntered = CountDownLatch(1)
    private val initialReadyDelivered = CountDownLatch(1)
    private val blockNextChecking = AtomicBoolean(true)
    private val reservationCalls = AtomicInteger()
    private val sessionCalls = AtomicInteger()
    private val microphoneCreateCalls = AtomicInteger()
    private val microphoneStartCalls = AtomicInteger()
    private val sessions = mutableListOf<ReadinessSession>()
    private val microphones = mutableListOf<ReadinessMicrophone>()
    private var ownedController: MeetingRecordingController? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        previousMode = PersistencePrefs(context).transcriptionMode
        TranscriptionModeCoordinator.clearProcessForTest()
        PersistencePrefs(context).transcriptionMode = TranscriptionMode.MEETING
        modeChanged = true
        coordinator = TranscriptionModeCoordinator.process(context)
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        ShadowSettings.setCanDrawOverlays(true)

        modelRoot = File(context.cacheDir, "meeting-readiness-models-${UUID.randomUUID()}")
        modelRoot.mkdirs()
        storeWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "meeting-readiness-store-fixture").apply { isDaemon = true }
        }
        store = createReadyStore(modelRoot, storeWorker)
        store.refresh()
        assertTrue("the real store verifies the tiny package before the overlay opens", awaitStoreState {
            it is MeetingModelStoreState.Ready
        } is MeetingModelStoreState.Ready)

        draftFile = File(context.cacheDir, "meeting-readiness-draft-${UUID.randomUUID()}.json")
        val overrides = OverlayService.MeetingTestOverrides(
            draftOwnership = MeetingDraftOwnership(executor = MeetingDraftOwnershipExecutor { task -> task() }),
            draftFile = draftFile,
            modelStore = store,
            reservation = MeetingNativeReservationPort {
                reservationCalls.incrementAndGet()
                ReadinessReservationRequest()
            },
            sessionFactory = object : MeetingSessionFactoryPort {
                override fun start(
                    runId: String,
                    language: String,
                    onReady: () -> Unit,
                    onUpdate: (MeetingHypothesis) -> Unit,
                    onFailure: (String) -> Unit,
                ): MeetingSession {
                    sessionCalls.incrementAndGet()
                    return ReadinessSession(runId, onFailure).also {
                        synchronized(sessions) { sessions += it }
                        onReady()
                    }
                }
            },
            microphoneFactory = MeetingMicrophoneFactoryPort {
                microphoneCreateCalls.incrementAndGet()
                ReadinessMicrophone(microphoneStartCalls).also { synchronized(microphones) { microphones += it } }
            },
        )
        assertTrue("native-only seams are installed before Service onCreate",
            OverlayService.setMeetingTestOverridesFactoryForTest { overrides })
        val created = Robolectric.buildService(OverlayService::class.java).create()
        serviceController = created
        service = created.get()
        OverlayService.clearMeetingTestOverridesFactoryForTest()
        onMain { invokeNoArgs(service, "showButton") }

        // The Service listener must be first: its callback posts the real panel projection before
        // this observer pauses the single model-store worker at Checking.
        onMain { invoke(service, "attachMeetingModelListener", store) }
        blocker = { next ->
            if (next is MeetingModelStoreState.Ready) initialReadyDelivered.countDown()
            if (next == MeetingModelStoreState.Checking && blockNextChecking.compareAndSet(true, false)) {
                checkingEntered.countDown()
                check(releaseChecking.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "readiness listener release timed out"
                }
            }
        }
        store.addListener(blocker)
        blockerRegistered = true
        assertTrue("the test listener receives the store's initial verified snapshot",
            initialReadyDelivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        releaseChecking.countDown()
        if (::store.isInitialized && blockerRegistered) runCatching { store.removeListener(blocker) }
        var destruction: CompletableFuture<Unit>? = null
        runCatching {
            if (::serviceController.isInitialized && ::service.isInitialized) {
                onMain {
                    ownedController = ownedController ?: field<MeetingRecordingController?>(service, "meetingRecordingController")
                    serviceController.destroy()
                    destruction = ownedController?.destroy()
                }
            }
        }
        runCatching { destruction?.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        if (::store.isInitialized) runCatching { store.shutdownForTests() }
        if (::storeWorker.isInitialized) {
            storeWorker.shutdownNow()
            storeWorker.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        OverlayService.clearMeetingTestOverridesFactoryForTest()
        if (::context.isInitialized && modeChanged && ::previousMode.isInitialized) {
            PersistencePrefs(context).transcriptionMode = previousMode
        }
        TranscriptionModeCoordinator.clearProcessForTest()
        ShadowSettings.reset()
        if (::draftFile.isInitialized) draftFile.takeIf(File::exists)?.delete()
        if (::modelRoot.isInitialized) modelRoot.takeIf(File::exists)?.deleteRecursively()
    }

    @Test
    fun `ready package recheck can refuse once then pill admits start only on a second tap`() {
        assertTrue(store.currentState is MeetingModelStoreState.Ready)

        tapPill()
        assertTrue("opening from the ready pill reaches the real store Checking event",
            checkingEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        awaitMainCondition {
            val controller = field<MeetingRecordingController?>(service, "meetingRecordingController")
            controller != null && controller.state.phase == MeetingRecordingPhase.MODEL_UNAVAILABLE
        }
        ownedController = field(service, "meetingRecordingController")
        assertEquals(MeetingModelStoreState.Checking, store.currentState)
        assertEquals("the visible panel reports current verification, not the failed attempt",
            "Chargement du modèle", panelStatusText())
        assertEquals("the pill remains in preparation while files are being checked",
            MeetingPillInteraction.Phase.PREPARING, pillPhase())
        assertEquals(0, reservationCalls.get())
        assertEquals(0, sessionCalls.get())
        assertEquals(0, microphoneCreateCalls.get())
        assertEquals(0, microphoneStartCalls.get())

        releaseChecking.countDown()
        awaitMainCondition {
            store.currentState is MeetingModelStoreState.Ready &&
                panelStatus() == MeetingPanelStatus.Phase.READY &&
                pillPhase() == MeetingPillInteraction.Phase.READY
        }
        assertEquals("the same real-store listener publishes a ready panel action",
            "Démarrer la réunion", panelStartActionLabel())
        assertEquals("readiness never retries the refused start automatically", 0, reservationCalls.get())
        assertEquals(0, sessionCalls.get())
        assertEquals(0, microphoneCreateCalls.get())
        assertEquals(0, microphoneStartCalls.get())

        tapPill()
        assertEquals(
            "the explicit retry is admitted without an automatic microphone start",
            MeetingRecordingPhase.PREPARING,
            ownedController?.state?.phase,
        )
        assertEquals("one fresh explicit tap admits one runtime", 1, reservationCalls.get())
    }

    private fun tapPill() {
        onMain {
            val pill = field<FrameLayout>(service, "pill")
            val now = android.os.SystemClock.uptimeMillis()
            pill.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 20f, 0))
            pill.dispatchTouchEvent(MotionEvent.obtain(now, now + 1L, MotionEvent.ACTION_UP, 20f, 20f, 0))
        }
    }

    private fun panelStatus(): MeetingPanelStatus.Phase? {
        val panel = field<MeetingPanelController?>(service, "meetingPanelController") ?: return null
        val status = field<MeetingPanelStatus>(panel.view, "status")
        return status.phase
    }

    private fun panelStatusText(): String {
        val panel = requireNotNull(field<MeetingPanelController?>(service, "meetingPanelController"))
        val statusView = field<android.widget.TextView>(panel.view, "statusView")
        return statusView.text.toString()
    }

    private fun panelStartActionLabel(): String {
        val panel = requireNotNull(field<MeetingPanelController?>(service, "meetingPanelController"))
        val actionsButton = field<android.widget.Button>(panel.view, "actionsButton")
        actionsButton.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16L, TimeUnit.MILLISECONDS)
        val dialog = requireNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("Actions de la réunion", Shadows.shadowOf(dialog).getTitle().toString())
        val list = requireNotNull(dialog.listView)
        val label = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
            .firstOrNull { it == "Démarrer la réunion" }
        dialog.dismiss()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16L, TimeUnit.MILLISECONDS)
        return requireNotNull(label) { "Ready actions must include Démarrer la réunion" }
    }

    private fun pillPhase(): MeetingPillInteraction.Phase =
        invokeNoArgs(service, "meetingPillPhase") as MeetingPillInteraction.Phase

    private fun createReadyStore(root: File, worker: ExecutorService): MeetingModelStore {
        val asr = "tiny valid asr model".encodeToByteArray()
        val diarization = "tiny valid diarization model".encodeToByteArray()
        val catalog = MeetingModelCatalog(
            packageName = "readiness-fixture",
            version = "v1",
            asr = artifact("asr", "asr.gguf", asr),
            diarization = artifact("diar", "diar.gguf", diarization),
        )
        val packageDirectory = File(root, catalog.packageDirectoryName("f1c7e"))
        check(packageDirectory.mkdirs())
        File(packageDirectory, catalog.asr.relativePath).writeBytes(asr)
        File(packageDirectory, catalog.diarization.relativePath).writeBytes(diarization)
        return MeetingModelStore(
            filesDirectory = root,
            catalog = catalog,
            availableBytes = { Long.MAX_VALUE },
            spaceMarginBytes = 0L,
            worker = worker,
        )
    }

    private fun artifact(id: String, filename: String, bytes: ByteArray): MeetingModelArtifact = MeetingModelArtifact(
        id = id,
        relativePath = filename,
        url = "https://models.invalid/$filename",
        sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun awaitStoreState(predicate: (MeetingModelStoreState) -> Boolean): MeetingModelStoreState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val current = store.currentState
            if (predicate(current)) return current
            Thread.sleep(5L)
        }
        throw AssertionError("store did not reach its expected state; current=${store.currentState}")
    }

    private fun awaitMainCondition(condition: () -> Boolean) {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            looper.idleFor(16L, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(5L)
        }
        looper.idleFor(16L, TimeUnit.MILLISECONDS)
        assertTrue("main-thread state did not settle within $TIMEOUT_SECONDS seconds", condition())
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val result = CompletableFuture<T>()
        Handler(Looper.getMainLooper()).post {
            try {
                result.complete(action())
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun invoke(target: Any, methodName: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { candidate ->
            candidate.name == methodName && candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.zip(args).all { (type, argument) ->
                    argument == null || type.isAssignableFrom(argument.javaClass) ||
                        (type.isPrimitive && argument is Boolean && type == Boolean::class.javaPrimitiveType)
                }
        }.apply { isAccessible = true }
        return method.invoke(target, *args)
    }

    private fun invokeNoArgs(target: Any, methodName: String): Any? =
        target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(target)

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private class ReadinessReservationRequest : MeetingNativeReservationRequest {
        override val lease = CompletableFuture.completedFuture<MeetingNativeRuntimeLease>(ReadinessLease())
        override fun cancel(): Unit = Unit
    }

    private class ReadinessLease : MeetingNativeRuntimeLease {
        override fun isCurrentAndUsable(): Boolean = true
        override fun release(): Unit = Unit
        override fun poison(): Unit = Unit
    }

    private class ReadinessSession(
        private val runId: String,
        private val onFailure: (String) -> Unit,
    ) : MeetingSession {
        override val closed = CompletableFuture<Unit>()
        override val queuedAudioMs: Long = 0L
        override fun acceptPcm16(buffer: ByteArray, length: Int): Boolean = true
        override fun checkpoint(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        override fun finish(): Unit { closed.complete(Unit) }
        override fun cancel(): Unit { closed.complete(Unit) }
        override fun close(): Unit = cancel()
        fun fail(message: String): Unit {
            onFailure(message)
            closed.complete(Unit)
        }
    }

    private class ReadinessMicrophone(private val starts: AtomicInteger) : MeetingMicrophonePort {
        override fun start(onPcm16: (ByteArray, Int) -> Boolean, onFailure: (String) -> Unit): Boolean {
            starts.incrementAndGet()
            return true
        }
        override fun stopAndJoin(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
