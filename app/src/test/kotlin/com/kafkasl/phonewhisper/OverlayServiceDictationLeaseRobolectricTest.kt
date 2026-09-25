package com.kafkasl.phonewhisper

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowSettings

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceDictationLeaseRobolectricTest {
    private lateinit var context: Context
    private lateinit var app: Application
    private lateinit var coordinator: TranscriptionModeCoordinator
    private lateinit var service: OverlayService
    private var serviceController: ServiceController<OverlayService>? = null
    private val audioSources = CopyOnWriteArrayList<BlockingAudioSource>()
    private val audioSourcesByRecorder = IdentityHashMap<AudioRecord, BlockingAudioSource>()
    private val engines = CopyOnWriteArrayList<FakeDictationAsrEngine>()
    private val closeGates = CopyOnWriteArrayList<Barrier>()
    private val sessionGates = CopyOnWriteArrayList<Barrier>()
    private val recorderReleaseGates = CopyOnWriteArrayList<Barrier>()
    private var whisperPinBefore: Map<String, Any?> = emptyMap()
    private var formatPrefsBefore: Map<String, Any?> = emptyMap()
    private var dictationDraftBefore: Map<String, Any?> = emptyMap()
    private var previousRecordPermissionGranted = false
    private var previousAnimatorScale = 1f

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        context = app
        val prefs = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        whisperPinBefore = prefs.all.toMap()
        val formats = context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
        formatPrefsBefore = formats.all.toMap()
        val draft = context.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE)
        dictationDraftBefore = draft.all.toMap()
        previousRecordPermissionGranted =
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        previousAnimatorScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )

        check(Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f))
        ShadowSettings.setCanDrawOverlays(true)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSourceProvider { record ->
            synchronized(audioSourcesByRecorder) {
                audioSourcesByRecorder[record] ?: BlockingAudioSource(record).also { source ->
                    audioSourcesByRecorder[record] = source
                    audioSources += source
                }
            }
        }

        TranscriptionModeCoordinator.clearProcessForTest()
        PersistencePrefs(context).transcriptionMode = TranscriptionMode.MEETING
        PersistencePrefs(context).formattingEngine = "off"
        PostProcessingFormats(context).select(PostProcessingFormats.builtins.first { it.id == "cleanup" })
        coordinator = TranscriptionModeCoordinator.process(context)
    }

    @After
    fun tearDown() {
        var cleanupFailure: Throwable? = null
        fun clean(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure
                else cleanupFailure?.addSuppressed(failure)
            }
        }

        try {
            audioSources.forEach(BlockingAudioSource::releaseRead)
            closeGates.forEach(Barrier::release)
            sessionGates.forEach(Barrier::release)
            recorderReleaseGates.forEach(Barrier::release)

            val activeController = serviceController
            if (activeController != null) {
                onMain { activeController.destroy() }
                serviceController = null
            }
            if (::coordinator.isInitialized) {
                try {
                    awaitCondition {
                        coordinator.snapshot().activeRunMode == null &&
                            coordinator.snapshot().residentDictationEngines == 0
                    }
                } catch (failure: Throwable) {
                    throw AssertionError("Coordinator did not release Dictation ownership: ${fixtureDiagnostic()}", failure)
                }
            }
        } catch (failure: Throwable) {
            cleanupFailure = failure
        } finally {
            clean { ShadowAudioRecord.clearSource() }
            clean { synchronized(audioSourcesByRecorder) { audioSourcesByRecorder.clear() } }
            clean {
                if (!previousRecordPermissionGranted) {
                    Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
                }
            }
            clean { ShadowSettings.reset() }
            clean {
                check(Settings.Global.putFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    previousAnimatorScale,
                ))
            }
            clean { restorePreferences("dictai_formats", formatPrefsBefore) }
            clean { restorePreferences("dictation_draft", dictationDraftBefore) }
            clean { restorePreferences("whisperpin", whisperPinBefore) }
            clean { if (::coordinator.isInitialized) TranscriptionModeCoordinator.clearProcessForTest() }
        }
        cleanupFailure?.let { throw AssertionError("Dictation lease fixture cleanup was incomplete", it) }
    }

    @Test
    fun `real Dictation start keeps one lease and one ASR session through pause and resume`() {
        val engine = startDictation()
        val session = engine.session

        assertEquals("the ASR starts only after the service owns the Dictation run", listOf(TranscriptionMode.DICTATION), engine.startRunModes)
        assertEquals("a native start cannot switch the active run to Meeting", listOf(false), engine.meetingSwitchDuringStart)
        assertEquals(1, engine.starts.get())
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().activeRunMode)
        assertMeetingSwitchRejectedWithoutChangingMode()

        val firstReader = audioSources.single()
        assertTrue("the real startRec reader reached the fake AudioRecord source",
            firstReader.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        onMain { invokeNoArgs(service, "pauseRec") }
        assertEquals("PAUSING", field<Any?>(service, "state").toString())
        firstReader.releaseRead()
        awaitMainCondition { field<Any?>(service, "state").toString() == "PAUSED" }

        assertEquals("pause retains the run lease", TranscriptionMode.DICTATION, coordinator.snapshot().activeRunMode)
        assertSame("pause does not reopen ASR", session, field<Any?>(service, "asrSession"))
        assertEquals(1, engine.starts.get())
        assertMeetingSwitchRejectedWithoutChangingMode()

        onMain { invokeNoArgs(service, "resumeRec") }
        assertEquals("RECORDING", field<Any?>(service, "state").toString())
        awaitCondition { audioSources.size == 2 }
        assertTrue(audioSources[1].readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().activeRunMode)
        assertSame(session, field<Any?>(service, "asrSession"))
        assertEquals("resume does not create another native ASR session", 1, engine.starts.get())
        assertMeetingSwitchRejectedWithoutChangingMode()
    }

    @Test
    fun `closed resident is not restarted through a stale service engine field`() {
        val engine = startDictation()
        val reader = audioSources.single()
        assertTrue(reader.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        onMain { invoke(service, "cancelRec", false) }
        reader.releaseRead()
        awaitMainCondition {
            field<Any?>(service, "state").toString() == "IDLE" &&
                coordinator.snapshot().activeRunMode == null
        }
        assertEquals(1, engine.starts.get())

        val resident = field<Lazy<ResidentEngine<DictationAsrEngine>>>(service, "residentAsrEngine\$delegate").value
        assertSame("the service still has its published field before resident close", engine,
            field<DictationAsrEngine?>(service, "asrEngine"))
        resident.close()
        assertSame("closing the resident does not implicitly clear the legacy service field", engine,
            field<DictationAsrEngine?>(service, "asrEngine"))

        assertTrue("the closed fake rejects any stale restart", engine.closed)
        onMain { invokeNoArgs(service, "startRec") }

        assertEquals("a closed ASR instance is never started again", 1, engine.starts.get())
        assertEquals("no replacement microphone is opened", 1, audioSources.size)
        assertNull(field<DictationAsrEngine?>(service, "asrEngine"))
        assertNull(field<String?>(service, "loadedModelName"))
        assertNull(field<Any?>(service, "activeRun"))
        assertNull(coordinator.snapshot().activeRunMode)
    }

    @Test
    fun `poison during pause prevents reopening the Dictation microphone`() {
        val engine = startDictation()
        val reader = audioSources.single()
        assertTrue(reader.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        onMain { invokeNoArgs(service, "pauseRec") }
        reader.releaseRead()
        awaitMainCondition { field<Any?>(service, "state").toString() == "PAUSED" }
        val runBeforeResume = requireNotNull(field<Any?>(service, "activeRun"))
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().activeRunMode)

        coordinator.reportUncertainClose()
        assertTrue(coordinator.snapshot().poisoned)
        onMain { invokeNoArgs(service, "resumeRec") }

        assertSame("the paused run remains the sole owner until safe shutdown", runBeforeResume,
            field<Any?>(service, "activeRun"))
        assertEquals("resume never creates a second ASR session", 1, engine.starts.get())
        assertEquals("resume never creates a second AudioRecord reader", 1, audioSources.size)
        assertNull("a poisoned process must not publish a resumed recorder", field<AudioRecord?>(service, "audioRecord"))
        assertEquals("the existing Dictation lease remains held", TranscriptionMode.DICTATION,
            coordinator.snapshot().activeRunMode)
    }

    @Test
    fun `Dictation lease remains held until cancellation exits and AudioRecord is released`() {
        val cancellation = Barrier()
        sessionGates += cancellation
        val engine = startDictation(sessionBarrier = cancellation)
        val reader = audioSources.single()
        assertTrue(reader.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        onMain { invoke(service, "cancelRec", false) }
        assertEquals("CANCELLING", field<Any?>(service, "state").toString())
        assertMeetingSwitchRejectedWithoutChangingMode()

        reader.releaseRead()
        assertTrue("the real cancel worker reaches the held ASR exit barrier",
            cancellation.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        awaitMainCondition { field<AudioRecord?>(service, "audioRecord") == null }
        assertTrue("AudioRecord release completes before native cancellation is released", reader.readExited.count == 0L)
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().activeRunMode)
        assertFalse("the ASR cancellation barrier is still held", cancellation.returned.count == 0L)
        assertMeetingSwitchRejectedWithoutChangingMode()

        cancellation.release()
        assertTrue("Meeting is admitted after actual reader, recorder, and ASR exit",
            switchToMeetingUntilAccepted())
        assertTrue(cancellation.returned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, engine.starts.get())
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        assertNull(field<Any?>(service, "activeRun"))
    }

    @Test
    fun `service destruction holds the run lease through delayed resident ASR close without main callbacks`() {
        val closing = Barrier()
        closeGates += closing
        val engine = startDictation(closeBarrier = closing)
        val reader = audioSources.single()
        assertTrue(reader.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val controller = requireNotNull(serviceController)
        onMain { controller.destroy() }
        serviceController = null
        assertNull("destroy detached the active run from the dead Service", field<Any?>(service, "activeRun"))
        reader.releaseRead()
        assertTrue("the independent native-close worker reaches the delayed close",
            closing.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertEquals("the run lease remains registered while native close is unresolved",
            TranscriptionMode.DICTATION, coordinator.snapshot().activeRunMode)
        assertMeetingSwitchRejectedWithoutChangingMode()
        assertEquals(1, coordinator.snapshot().residentDictationEngines)

        closing.release()
        assertTrue("lease release does not depend on a queued Service/main callback",
            switchToMeetingUntilAccepted(idleMainLooper = false))
        assertTrue(engine.closeReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(TranscriptionMode.MEETING, coordinator.snapshot().mode)
        assertEquals(0, coordinator.snapshot().residentDictationEngines)
    }

    @Test
    fun `destroy waits for failed resume recorder release before closing resident ASR`() {
        val closing = Barrier()
        closeGates += closing
        val engine = startDictation(closeBarrier = closing)
        val initialReader = audioSources.single()
        assertTrue(initialReader.readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        onMain { invokeNoArgs(service, "pauseRec") }
        initialReader.releaseRead()
        awaitMainCondition { field<Any?>(service, "state").toString() == "PAUSED" }

        val release = Barrier()
        recorderReleaseGates += release
        val failedResumeRecorders = CopyOnWriteArrayList<FailingResumeAudioRecord>()
        setField(service, "resumeAudioRecordFactory", { bufferSize: Int ->
            FailingResumeAudioRecord(bufferSize, release).also(failedResumeRecorders::add)
        })
        onMain { invokeNoArgs(service, "resumeRec") }
        val failedResumeRecorder = failedResumeRecorders.single()

        assertTrue("the failed resume enters cleanup on its worker",
            failedResumeRecorder.startEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue("cleanup reaches recorder release and waits there",
            failedResumeRecorder.releaseEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("resume failure is still owned by Dictation", TranscriptionMode.DICTATION,
            coordinator.snapshot().activeRunMode)
        assertEquals("resident ASR has not closed before recorder release returns", 0,
            engine.closeCalls.get())

        val controller = requireNotNull(serviceController)
        onMain { controller.destroy() }
        serviceController = null
        assertFalse("destroy must not start native close while failed-resume release is blocked",
            closing.entered.await(250, TimeUnit.MILLISECONDS))
        assertEquals("destroy cannot release the Dictation lease while cleanup owns the recorder",
            TranscriptionMode.DICTATION, coordinator.snapshot().activeRunMode)
        assertEquals("destroy waits before closing resident ASR", 0, engine.closeCalls.get())
        assertFalse("the controlled release remains blocked", failedResumeRecorder.releaseReturned.count == 0L)

        release.release()
        assertTrue("the worker completes release after its barrier is opened",
            failedResumeRecorder.releaseReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue("native close begins only after recorder release returns",
            closing.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        closing.release()
        assertTrue("the run lease is freed after recorder and resident cleanup are confirmed",
            switchToMeetingUntilAccepted(idleMainLooper = false))
        assertTrue(engine.closeReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(0, coordinator.snapshot().residentDictationEngines)
    }

    private fun startDictation(
        sessionBarrier: Barrier? = null,
        closeBarrier: Barrier? = null,
    ): FakeDictationAsrEngine {
        val created = Robolectric.buildService(OverlayService::class.java).create()
        serviceController = created
        service = created.get()
        onMain { invokeNoArgs(service, "showButton") }

        assertTrue("the fixture switches to Dictation only after Service creation", coordinator.changeMode(TranscriptionMode.DICTATION))
        awaitMainCondition { field<View?>(service, "pill")?.contentDescription == "Pastille Dictée" }

        val model = TranscriptionEngine.selectedModelName(context)
        val engine = FakeDictationAsrEngine(model, coordinator, sessionBarrier, closeBarrier)
        engines += engine
        val resident = field<Lazy<ResidentEngine<DictationAsrEngine>>>(service, "residentAsrEngine\$delegate").value
        assertSame("the test engine is installed through the real resident manager", engine, resident.replace(model) { engine })
        setField(service, "asrEngine", engine)
        setField(service, "loadedModelName", model)

        onMain {
            service.onStartCommand(
                Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_ARM_MIC),
                0,
                1,
            )
        }
        assertTrue("the visible test Service arms the microphone through its normal action", OverlayService.micArmed)
        onMain { invokeNoArgs(service, "startRec") }
        assertEquals("RECORDING", field<Any?>(service, "state").toString())
        return engine
    }

    private fun assertMeetingSwitchRejectedWithoutChangingMode() {
        assertEquals(TranscriptionMode.DICTATION, coordinator.snapshot().mode)
        val accepted = coordinator.changeMode(TranscriptionMode.MEETING)
        if (accepted) assertTrue("restore fixture mode after exposing a missing run lease",
            coordinator.changeMode(TranscriptionMode.DICTATION))
        assertFalse("Meeting must not be selected while this Dictation run owns audio/ASR", accepted)
    }

    private fun switchToMeetingUntilAccepted(idleMainLooper: Boolean = true): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        while (System.nanoTime() < deadline) {
            if (idleMainLooper) mainLooper.idle()
            if (coordinator.changeMode(TranscriptionMode.MEETING)) return true
            Thread.yield()
        }
        if (idleMainLooper) mainLooper.idle()
        return false
    }

    private fun awaitMainCondition(condition: () -> Boolean) {
        awaitCondition {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.yield()
        }
        assertTrue("condition completes within the bounded fixture timeout", condition())
    }

    private fun fixtureDiagnostic(): String {
        val snapshot = coordinator.snapshot()
        val workerSummary = Thread.getAllStackTraces().entries
            .filter { (thread, _) -> thread.name.startsWith("dictai-") && thread.isAlive }
            .sortedBy { it.key.name }
            .joinToString(prefix = "[", postfix = "]") { (thread, stack) ->
                val frames = stack.take(5).joinToString("/") { frame ->
                    "${frame.className.substringAfterLast('.')}.${frame.methodName}"
                }
                "${thread.name}:${thread.state}:$frames"
            }
        val sourceSummary = audioSources.mapIndexed { index, source ->
            "$index:readIn=${source.readEntered.count == 0L},readOut=${source.readExited.count == 0L}"
        }
        val engineSummary = engines.mapIndexed { index, engine ->
            "$index:starts=${engine.starts.get()},closes=${engine.closeCalls.get()},sessionExits=${engine.session.cancelAndAwaitCalls.get()},closed=${engine.closed}"
        }
        return "snapshot=$snapshot; workers=$workerSummary; sources=$sourceSummary; engines=$engineSummary"
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val value = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        val complete = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                value.set(action())
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                complete.countDown()
            }
        }
        awaitMainCondition { complete.count == 0L }
        failure.get()?.let { throw AssertionError("main-thread action failed", it) }
        @Suppress("UNCHECKED_CAST")
        return value.get() as T
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { candidate ->
            candidate.name == name && candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.zip(args).all { (type, value) ->
                    value == null || type.isAssignableFrom(value.javaClass) ||
                        (type.isPrimitive && value is Boolean && type == Boolean::class.javaPrimitiveType)
                }
        }.apply { isAccessible = true }
        return method.invoke(target, *args)
    }

    private fun invokeNoArgs(target: Any, name: String): Unit {
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    }

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(target) as T
        }

    private fun setField(target: Any, name: String, value: Any?): Unit {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun restorePreferences(name: String, snapshot: Map<String, Any?>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        snapshot.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "Could not restore preferences $name" }
    }

    private class Barrier {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)

        fun await(): Boolean = try {
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            returned.countDown()
        }

        fun release(): Unit = release.countDown()
    }

    private class BlockingAudioSource(private val recorder: AudioRecord) : ShadowAudioRecord.AudioRecordSource {
        val readEntered = CountDownLatch(1)
        val readExited = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, isBlocking: Boolean): Int {
            readEntered.countDown()
            try {
                if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return -3
                return if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) 0 else -3
            } finally {
                readExited.countDown()
            }
        }

        fun releaseRead(): Unit = release.countDown()
    }

    private class FailingResumeAudioRecord(
        bufferSize: Int,
        private val releaseBarrier: Barrier,
    ) : AudioRecord(
        MediaRecorder.AudioSource.MIC,
        TEST_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize,
    ) {
        val startEntered = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)

        override fun startRecording(): Unit {
            startEntered.countDown()
            throw IllegalStateException("controlled resume start failure")
        }

        override fun release(): Unit {
            releaseEntered.countDown()
            check(releaseBarrier.await()) { "test did not release the failed-resume recorder" }
            super.release()
            releaseReturned.countDown()
        }
    }

    private class FakeDictationAsrEngine(
        override val modelName: String,
        private val coordinator: TranscriptionModeCoordinator,
        sessionBarrier: Barrier?,
        private val closeBarrier: Barrier?,
    ) : DictationAsrEngine {
        override val mode = DictationAsrMode.BATCH
        val starts = AtomicInteger()
        val startRunModes = CopyOnWriteArrayList<TranscriptionMode?>()
        val meetingSwitchDuringStart = CopyOnWriteArrayList<Boolean>()
        val closeCalls = AtomicInteger()
        val closeReturned = CountDownLatch(1)
        val session = FakeDictationAsrSession(sessionBarrier)
        @Volatile var closed = false

        override fun start(
            language: DictationLanguage,
            onPreview: (committed: String, tentative: String) -> Unit,
        ): DictationAsrSession {
            starts.incrementAndGet()
            check(!closed) { "The test ASR engine is already closed" }
            startRunModes += coordinator.snapshot().activeRunMode
            val meetingAccepted = coordinator.changeMode(TranscriptionMode.MEETING)
            meetingSwitchDuringStart += meetingAccepted
            if (meetingAccepted) coordinator.changeMode(TranscriptionMode.DICTATION)
            return session
        }

        override fun close(): Unit {
            closeCalls.incrementAndGet()
            closeBarrier?.let { barrier ->
                barrier.entered.countDown()
                check(barrier.await()) { "Test did not release the blocked ASR close" }
            }
            closed = true
            closeReturned.countDown()
        }
    }

    private class FakeDictationAsrSession(private val cancellationBarrier: Barrier?) : DictationAsrSession {
        val cancelAndAwaitCalls = AtomicInteger()

        override fun acceptPcm16(buffer: ByteArray, length: Int): Unit = Unit
        override fun finish(fullPcm: ByteArray): TranscriptionEngine.Result = TranscriptionEngine.Result(null)
        override fun cancel(): Unit = Unit

        override fun cancelAndAwait(): Boolean {
            cancelAndAwaitCalls.incrementAndGet()
            if (cancellationBarrier == null) return true
            cancellationBarrier.entered.countDown()
            return cancellationBarrier.await()
        }
    }

    private companion object {
        const val TEST_SAMPLE_RATE = 16_000
        const val TIMEOUT_SECONDS = 5L
    }
}
