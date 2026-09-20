package com.kafkasl.phonewhisper

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalFormatCpuEngineTest {
    @Test
    fun requestIdentityIncludesPhaseAndContext() {
        val final = request().copy(
            phase = GemmaFineTunedPrompt.Phase.FINAL,
            contextBefore = "Nom déjà introduit.",
        )
        val partial = final.copy(phase = GemmaFineTunedPrompt.Phase.PARTIAL)
        val otherContext = final.copy(contextBefore = "Autre nom déjà introduit.")

        assertNotEquals(final, partial)
        assertNotEquals(final, otherContext)
    }

    @Test
    fun nativeCallUsesExactEnvelopeGreedyProfileAndContext() {
        val bindings = RecordingBindings(output = "sortie")
        val fixture = Fixture(bindings)
        val engine = fixture.engine()

        val request = request(
            text = "Pour la réunion il faut vérifier le budget.",
            mode = LocalLayoutKind.TEXT,
        ).copy(
            phase = GemmaFineTunedPrompt.Phase.PARTIAL,
            contextBefore = "Le rendez-vous est vendredi.",
        )
        val result = engine.backend().generate(request, {})

        assertEquals("sortie", result)
        val userPrompt = GemmaFineTunedPrompt.build(
            request.text,
            GemmaFineTunedPrompt.Mode.TEXT,
            request.phase,
            request.contextBefore,
            request.protectedTerms,
        )
        assertEquals(
            GemmaFineTunedPrompt.buildNativeEnvelope(userPrompt),
            bindings.prompt,
        )
        assertEquals(LocalFormatDecodingProfile.GemmaFineTunedGreedy, bindings.profile)
        assertNull(bindings.grammar)
        engine.close()
    }

    @Test
    fun twoOwnersShareOneNativeAndOnlyLastCloseReleasesIt() {
        val bindings = RecordingBindings(output = "ok")
        val fixture = Fixture(bindings)
        val first = fixture.engine()
        val second = fixture.engine()

        assertEquals("ok", first.backend().generate(request(), {}))
        assertEquals("ok", second.backend().generate(request("deux"), {}))
        assertEquals(1, fixture.opens.get())

        first.close()
        assertEquals(0, bindings.closeCalls.get())
        assertEquals("ok", second.backend().generate(request("trois"), {}))
        second.close()

        await(bindings.closed, "shared native close")
        assertEquals(1, bindings.closeCalls.get())
    }

    @Test
    fun cancellationBeforeNativeEntryDoesNotCallNative() {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val bindings = RecordingBindings(output = "should not run")
        val fixture = Fixture(bindings, openGate = { openEntered.countDown(); releaseOpen.await(2, TimeUnit.SECONDS) })
        val engine = fixture.engine()
        val backend = engine.backend()
        val result = arrayOfNulls<String>(1)
        val worker = thread(start = true) { result[0] = backend.generate(request(), {}) }

        await(openEntered, "model open")
        backend.cancel()
        releaseOpen.countDown()
        join(worker)

        assertNull(result[0])
        assertEquals(0, bindings.generateCalls.get())
        engine.close()
    }

    @Test
    fun cancellationDuringNativeSuppressesLateChunksAndResult() {
        val dispatcher = QueuedCancellationDispatcher()
        val bindings = RecordingBindings(
            output = "late result",
            waitAfterFirstChunk = true,
            firstChunk = "avant",
            secondChunk = "après",
        )
        val fixture = Fixture(bindings, cancelDispatcher = dispatcher)
        val engine = fixture.engine()
        val backend = engine.backend()
        val chunks = mutableListOf<String>()
        val result = arrayOfNulls<String>(1)
        val worker = thread(start = true) { result[0] = backend.generate(request(), chunks::add) }

        await(bindings.firstChunkSent, "first native chunk")
        backend.cancel()
        assertEquals(1, dispatcher.pendingCount())
        dispatcher.drainOne()
        assertEquals(1, bindings.cancelCalls.get())
        bindings.releaseGeneration.countDown()
        join(worker)

        assertNull(result[0])
        assertEquals(listOf("avant"), chunks)
        assertEquals(1, bindings.cancelCalls.get())
        engine.close()
    }

    @Test
    fun cancellationDoesNotWaitForBlockedChunkCallbackOrPublishResult() {
        val bindings = RecordingBindings(output = "cancelled")
        val fixture = Fixture(bindings)
        val engine = fixture.engine()
        val backend = engine.backend()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val cancellationReturned = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        val worker = thread(start = true) {
            result[0] = backend.generate(request("bloqué")) {
                callbackEntered.countDown()
                releaseCallback.await(2, TimeUnit.SECONDS)
            }
        }

        await(callbackEntered, "blocked chunk callback")
        val canceller = thread(start = true) {
            backend.cancel()
            cancellationReturned.countDown()
        }
        try {
            assertTrue("cancellation waited for the client callback", cancellationReturned.await(1, TimeUnit.SECONDS))
        } finally {
            releaseCallback.countDown()
        }
        join(canceller)
        join(worker)

        assertNull(result[0])
        assertEquals("cancelled", backend.generate(request("generation suivante"), {}))
        engine.close()
    }

    @Test
    fun nativePrefixesAreForwardedWithoutAppendingDeltas() {
        val bindings = RecordingBindings(
            output = "Bonjour",
            waitAfterFirstChunk = true,
            firstChunk = "Bon",
            secondChunk = "Bonjour",
        )
        val fixture = Fixture(bindings)
        val engine = fixture.engine()
        val chunks = mutableListOf<String>()
        val result = arrayOfNulls<String>(1)
        val worker = thread(start = true) {
            result[0] = engine.backend().generate(request(), chunks::add)
        }

        await(bindings.firstChunkSent, "prefix chunk")
        bindings.releaseGeneration.countDown()
        join(worker)

        assertEquals("Bonjour", result[0])
        assertEquals(listOf("Bon", "Bonjour"), chunks)
        assertEquals(2, chunks.size)
        engine.close()
    }

    @Test
    fun closingOneOwnerCancelsOnlyItsCallAndOtherOwnerContinues() {
        val bindings = RecordingBindings(
            output = "shared output",
            waitAfterFirstChunk = true,
        )
        val fixture = Fixture(bindings)
        val first = fixture.engine()
        val second = fixture.engine()
        val firstResult = arrayOfNulls<String>(1)
        val firstWorker = thread(start = true) {
            firstResult[0] = first.backend().generate(request("premier"), {})
        }
        await(bindings.firstChunkSent, "first owner native call")

        first.close()
        val secondResult = arrayOfNulls<String>(1)
        val secondWorker = thread(start = true) {
            secondResult[0] = second.backend().generate(request("second"), {})
        }
        bindings.releaseGeneration.countDown()
        await(bindings.secondGenerationStarted, "second owner native call")
        join(firstWorker)
        join(secondWorker)

        assertNull(firstResult[0])
        assertEquals("shared output", secondResult[0])
        assertEquals(0, bindings.closeCalls.get())
        second.close()
        await(bindings.closed, "last owner close")
    }

    @Test
    fun cancellingOneBackendDoesNotCancelAnotherBackendOfSameOwner() {
        val bindings = RecordingBindings(output = "shared output", waitAfterFirstChunk = true)
        val fixture = Fixture(bindings)
        val engine = fixture.engine()
        val firstBackend = engine.backend()
        val secondBackend = engine.backend()
        val firstResult = arrayOfNulls<String>(1)
        val firstWorker = thread(start = true) {
            firstResult[0] = firstBackend.generate(request("premier"), {})
        }
        await(bindings.firstChunkSent, "first backend native call")

        val secondResult = arrayOfNulls<String>(1)
        val secondWorker = thread(start = true) {
            secondResult[0] = secondBackend.generate(request("second"), {})
        }
        firstBackend.cancel()
        bindings.releaseGeneration.countDown()
        await(bindings.secondGenerationStarted, "second backend native call")
        join(firstWorker)
        join(secondWorker)

        assertNull(firstResult[0])
        assertEquals("shared output", secondResult[0])
        engine.close()
        await(bindings.closed, "backend owner close")
    }

    @Test
    fun delayedCancellationTargetsTheOriginalNativeGeneration() {
        val dispatcher = QueuedCancellationDispatcher()
        val bindings = RecordingBindings(
            output = "shared output",
            waitAfterFirstChunk = true,
            holdSecondGeneration = true,
        )
        val fixture = Fixture(bindings, cancelDispatcher = dispatcher)
        val engine = fixture.engine()
        val backend = engine.backend()
        val firstResult = arrayOfNulls<String>(1)
        val firstWorker = thread(start = true) {
            firstResult[0] = backend.generate(request("premier"), {})
        }
        await(bindings.firstChunkSent, "first generation")

        backend.cancel()
        bindings.releaseGeneration.countDown()
        join(firstWorker)
        assertNull(firstResult[0])
        assertEquals(1, dispatcher.pendingCount())

        val secondResult = arrayOfNulls<String>(1)
        val secondWorker = thread(start = true) {
            secondResult[0] = backend.generate(request("second"), {})
        }
        await(bindings.secondGenerationStarted, "second generation")
        dispatcher.drainOne()
        assertTrue(bindings.cancelledGenerations.isEmpty())
        bindings.releaseSecondGeneration.countDown()
        join(secondWorker)

        assertEquals("shared output", secondResult[0])
        engine.close()
        await(bindings.closed, "native close")
    }

    @Test
    fun generationFailureKeepsResidentNativeState() {
        val bindings = RecordingBindings(output = "ok")
        val fixture = Fixture(bindings)
        val engine = fixture.engine()

        assertEquals("ok", engine.backend().generate(request(), {}))
        bindings.throwOnGenerate.set(true)
        assertNull(engine.backend().generate(request("échec"), {}))

        assertTrue(engine.isLoaded())
        assertEquals("generation_error", engine.failureCode())
        assertEquals("fake-cpu", engine.runtimeName())
        engine.close()
        await(bindings.closed, "resident native close")
    }

    @Test
    fun finalOwnerCloseWaitsForNativeReturnAndClosesOnce() {
        val bindings = RecordingBindings(output = "result", waitAfterFirstChunk = true)
        val fixture = Fixture(bindings)
        val engine = fixture.engine()
        val result = arrayOfNulls<String>(1)
        val worker = thread(start = true) { result[0] = engine.backend().generate(request(), {}) }

        await(bindings.firstChunkSent, "native call")
        engine.close()
        assertEquals(0, bindings.closeCalls.get())
        bindings.releaseGeneration.countDown()
        join(worker)
        await(bindings.closed, "native close")

        assertNull(result[0])
        assertEquals(1, bindings.closeCalls.get())
    }

    @Test
    fun reacquisitionDuringDeferredCloseKeepsResidentState() {
        val bindings = RecordingBindings(output = "shared", waitAfterFirstChunk = true)
        val fixture = Fixture(bindings)
        val first = fixture.engine()
        val firstResult = arrayOfNulls<String>(1)
        val firstWorker = thread(start = true) {
            firstResult[0] = first.backend().generate(request("premier"), {})
        }
        await(bindings.firstChunkSent, "resident generation")
        first.close()

        val second = fixture.engine()
        assertTrue(second.isLoaded())
        bindings.releaseGeneration.countDown()
        join(firstWorker)

        assertNull(firstResult[0])
        assertEquals("shared", second.backend().generate(request("second"), {}))
        assertEquals(1, fixture.opens.get())
        assertTrue(second.isLoaded())
        assertEquals("fake-cpu", second.runtimeName())
        second.close()
        await(bindings.closed, "resident close")
    }

    @Test
    fun loadTimeIsDeductedFromGenerationDeadline() {
        val clock = MutableClock()
        val bindings = RecordingBindings(output = "ok")
        val fixture = Fixture(bindings, clock = clock, openGate = {
            clock.nowMs.set(7_500L)
        })
        val engine = fixture.engine()

        assertEquals("ok", engine.backend().generate(request(), {}))
        assertTrue(bindings.timeoutMs in 1L..12_500L)
        engine.close()
    }

    @Test
    fun reservedTokenizerDelimitersFailBeforeModelOpen() {
        val forbidden = listOf(
            request("texte <| interdit"),
            request("texte", contextBefore = "contexte <bos>"),
            request("texte", protectedTerms = listOf("terme |>") ),
        )
        forbidden.forEach { value ->
            val bindings = RecordingBindings(output = "should not run")
            val fixture = Fixture(bindings)
            val engine = fixture.engine()
            assertNull(engine.backend().generate(value, {}))
            assertEquals(0, fixture.opens.get())
            engine.close()
        }
    }

    @Test
    fun missingModelReportsFixedDiagnosticWithoutNativeCall() {
        val bindings = RecordingBindings(output = "should not run")
        val fixture = Fixture(bindings, model = null)
        val engine = fixture.engine()

        assertNull(engine.backend().generate(request(), {}))
        assertEquals("model_missing", engine.failureCode())
        assertEquals("model-missing", engine.runtimeName())
        assertEquals(0, fixture.opens.get())
        engine.close()
    }

    @Test
    fun prepareInfoSharesLoadedState() {
        val bindings = RecordingBindings(output = "ok")
        val fixture = Fixture(bindings)
        val engine = fixture.engine()

        val first = engine.prepareForBenchmarkInfo()
        val second = engine.prepareForBenchmarkInfo()

        assertFalse(first.wasAlreadyLoaded)
        assertTrue(second.wasAlreadyLoaded)
        assertEquals(1, fixture.opens.get())
        assertTrue(first.waitMs >= 0L)
        engine.close()
    }

    private fun request(
        text: String = "texte suffisamment long pour le moteur",
        mode: LocalLayoutKind = LocalLayoutKind.TEXT,
        contextBefore: String = "",
        protectedTerms: List<String> = emptyList(),
    ) = LocalFormatRequest(
        text = text,
        instructions = "",
        language = "fr",
        protectedTerms = protectedTerms,
        layoutKind = mode,
        validation = LocalFormatValidation.GEMMA_PROJECTION,
        phase = GemmaFineTunedPrompt.Phase.FINAL,
        contextBefore = contextBefore,
    )

    private fun await(latch: CountDownLatch, label: String) {
        assertTrue("timeout waiting for $label", latch.await(2, TimeUnit.SECONDS))
    }

    private fun join(worker: Thread) {
        worker.join(2_000L)
        assertFalse("worker did not finish", worker.isAlive)
    }

    private class Fixture(
        private val bindings: RecordingBindings,
        private val model: File? = File.createTempFile("gemma-cpu", ".gguf").apply { deleteOnExit() },
        private val clock: MutableClock = MutableClock(),
        private val openGate: (() -> Unit)? = null,
        private val cancelDispatcher: LocalFormatCpuCancellationDispatcher? = null,
    ) {
        val opens = AtomicInteger()

        fun engine(): LocalFormatCpuEngine = LocalFormatCpuEngine(
            modelProvider = LocalFormatCpuModelProvider { model },
            nativeFactory = LocalFormatCpuNativeFactory { _, _, _ ->
                opens.incrementAndGet()
                openGate?.invoke()
                LocalFormatNative.forTesting(bindings)
            },
            clock = clock,
            cancelDispatcher = cancelDispatcher,
            sharedKey = "test-${model?.absolutePath ?: "missing"}-${System.identityHashCode(bindings)}",
        )
    }

    private class MutableClock : LocalFormatCpuClock {
        val nowMs = AtomicLong(0L)
        override fun nowMs(): Long = nowMs.get()
    }

    private class RecordingBindings(
        private val output: String,
        private val waitAfterFirstChunk: Boolean = false,
        private val firstChunk: String = "chunk",
        private val secondChunk: String = "late",
        private val holdSecondGeneration: Boolean = false,
    ) : LocalFormatNativeApi {
        override val runtimeName: String = "fake-cpu"
        val generateCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val firstChunkSent = CountDownLatch(1)
        val secondGenerationStarted = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val releaseSecondGeneration = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelledGenerations = CopyOnWriteArrayList<Long>()
        val throwOnGenerate = AtomicBoolean(false)
        @Volatile var prompt: String? = null
        @Volatile var grammar: String? = null
        @Volatile var profile: LocalFormatDecodingProfile? = null
        @Volatile var timeoutMs: Long = 0L

        override fun open(path: ByteArray, contextSize: Int, threads: Int): Long = 1L

        override fun generate(
            handle: Long,
            generation: Long,
            prompt: ByteArray,
            grammar: ByteArray?,
            profile: LocalFormatDecodingProfile,
            maxTokens: Int,
            timeoutMs: Long,
            sink: LocalFormatChunkSink,
        ): ByteArray? {
            val call = generateCalls.incrementAndGet()
            if (throwOnGenerate.get()) throw IllegalStateException("fake generation failure")
            this.prompt = prompt.toString(Charsets.UTF_8)
            this.grammar = grammar?.toString(Charsets.UTF_8)
            this.profile = profile
            this.timeoutMs = timeoutMs
            if (call == 2) secondGenerationStarted.countDown()
            sink.onBytes(firstChunk.toByteArray(Charsets.UTF_8))
            firstChunkSent.countDown()
            if (waitAfterFirstChunk) {
                releaseGeneration.await(2, TimeUnit.SECONDS)
                sink.onBytes(secondChunk.toByteArray(Charsets.UTF_8))
            }
            if (holdSecondGeneration && call == 2) {
                releaseSecondGeneration.await(2, TimeUnit.SECONDS)
            }
            return output.toByteArray(Charsets.UTF_8)
        }

        override fun cancel(handle: Long, generation: Long) {
            cancelCalls.incrementAndGet()
            cancelledGenerations += generation
        }

        override fun close(handle: Long) {
            closeCalls.incrementAndGet()
            closed.countDown()
        }
    }

    private class QueuedCancellationDispatcher : LocalFormatCpuCancellationDispatcher {
        private val pending = ConcurrentLinkedQueue<() -> Unit>()

        override fun dispatch(task: () -> Unit) { pending += task }

        fun pendingCount(): Int = pending.size

        fun drainOne() { pending.poll()?.invoke() }
    }
}
