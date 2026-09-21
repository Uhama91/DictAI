package com.kafkasl.phonewhisper

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowSettings
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
class OverlayMeasurementAdmissionTest {
    private lateinit var context: Context
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        ShadowAudioRecord.clearSource()
        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        runCatching { LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)?.close() }
        ShadowAudioRecord.clearSource()
        ShadowSettings.reset()
    }

    @Test
    fun `benchmark lease blocks overlay start before opening an ASR session`() {
        val engine = FakeEngine()
        installEngine(engine)
        val benchmark = requireNotNull(
            LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK),
        )

        try {
            invoke(service, "startRec")

            assertEquals(0, engine.startCalls)
            val dictation = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.DICTATION)
            try {
                assertNull(dictation)
            } finally {
                dictation?.close()
            }
        } finally {
            benchmark.close()
        }
    }

    @Test
    fun `startup failure after admission releases the dictation lease`() {
        val engine = FakeEngine(startFailure = IllegalStateException("synthetic startup failure"))
        installEngine(engine)

        invoke(service, "startRec")

        assertEquals(1, engine.startCalls)
        val benchmark = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)
        assertNotNull("a failed startup must release the dictation lease", benchmark)
        benchmark!!.close()
    }

    @Test
    fun `destroy keeps the lease until the asynchronous ASR session exits`() {
        val session = BlockingSession()
        val lease = requireNotNull(
            LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.DICTATION),
        )
        val run = newActiveRun(session)
        setField(run, "measurementLease", lease)
        setField(service, "activeRun", run)

        try {
            service.onDestroy()

            assertTrue("destruction should wait for the native session", session.entered.await(1, TimeUnit.SECONDS))
            val earlyBenchmark = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)
            try {
                assertNull(earlyBenchmark)
            } finally {
                earlyBenchmark?.close()
            }

            session.release.countDown()
            assertTrue("the release worker should finish", session.finished.await(1, TimeUnit.SECONDS))
            assertTrue("the session must not leave a timed-out wait", !session.releaseTimedOut.get())
            val benchmark = awaitBenchmarkLease(1_000L)
            assertNotNull("the lease is released after the session exits", benchmark)
            benchmark!!.close()
        } finally {
            session.release.countDown()
            session.finished.await(1, TimeUnit.SECONDS)
            lease.close()
        }
    }

    private fun awaitBenchmarkLease(timeoutMs: Long): LocalMeasurementAdmission.Lease? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)?.let { return it }
            Thread.sleep(10L)
        }
        return null
    }

    private fun installEngine(engine: FakeEngine) {
        val selectedModel = TranscriptionEngine.selectedModelName(context)
        setField(service, "loadedModelName", selectedModel)
        setField(service, "asrEngine", engine)
        (field(service, "localLoading") as AtomicBoolean).set(false)
    }

    private fun newActiveRun(session: DictationAsrSession): Any {
        val optionsClass = Class.forName("com.kafkasl.phonewhisper.OverlayService\$RecordingOptions")
        val optionsConstructor = optionsClass.declaredConstructors.first { it.parameterTypes.size == 9 }
        optionsConstructor.isAccessible = true
        val options = optionsConstructor.newInstance(
            DictationLanguage.FRENCH,
            DictationAsrMode.BATCH,
            false,
            false,
            CloudModelCatalog.default,
            PostProcessingFormats.builtins.first(),
            false,
            NumberStyle.DIGITS,
            true,
        )

        val runClass = Class.forName("com.kafkasl.phonewhisper.OverlayService\$ActiveDictationRun")
        val constructor = runClass.declaredConstructors.first { it.parameterTypes.size == 4 }
        constructor.isAccessible = true
        return constructor.newInstance(
            session,
            options,
            DictationPurpose.MESSAGE,
            DictationCancellationCoordinator(),
        )
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }?.let { method ->
                method.isAccessible = true
                method.invoke(target, *args)
                return
            }
            type = type.superclass
        }
        error("No method $name on ${target.javaClass.name}")
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { field ->
                field.isAccessible = true
                field.set(target, value)
                return
            }
            type = type.superclass
        }
        error("No field $name on ${target.javaClass.name}")
    }

    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { field ->
                field.isAccessible = true
                return field.get(target)
            }
            type = type.superclass
        }
        error("No field $name on ${target.javaClass.name}")
    }

    private class FakeEngine(
        private val startFailure: Throwable? = null,
    ) : DictationAsrEngine {
        override val modelName: String = "synthetic"
        override val mode: DictationAsrMode = DictationAsrMode.BATCH
        var startCalls = 0

        override fun start(
            language: DictationLanguage,
            onPreview: (committed: String, tentative: String) -> Unit,
        ): DictationAsrSession {
            startCalls++
            startFailure?.let { throw it }
            return ImmediateSession()
        }

        override fun close() = Unit
    }

    private class ImmediateSession : DictationAsrSession {
        override fun acceptPcm16(buffer: ByteArray, length: Int) = Unit
        override fun finish(fullPcm: ByteArray): TranscriptionEngine.Result = TranscriptionEngine.Result(null)
        override fun cancel() = Unit
        override fun cancelAndAwait(): Boolean = true
    }

    private class BlockingSession : DictationAsrSession {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val releaseTimedOut = AtomicBoolean(false)

        override fun acceptPcm16(buffer: ByteArray, length: Int) = Unit
        override fun finish(fullPcm: ByteArray): TranscriptionEngine.Result = TranscriptionEngine.Result(null)
        override fun cancel() = Unit

        override fun cancelAndAwait(): Boolean {
            entered.countDown()
            if (!release.await(2, TimeUnit.SECONDS)) {
                releaseTimedOut.set(true)
                return false
            }
            finished.countDown()
            return true
        }
    }
}
