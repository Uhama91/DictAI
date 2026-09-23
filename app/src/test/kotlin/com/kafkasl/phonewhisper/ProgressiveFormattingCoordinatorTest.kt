package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProgressiveFormattingCoordinatorTest {
    @Test fun `short dictation waits for final and uses no partial call`() {
        val backend = FakeBackend(outputs = ArrayDeque(listOf("Bonjour.")))
        val states = mutableListOf<ProgressiveBufferState>()
        val coordinator = coordinator(backend, states, ImmediateDispatcher, ImmediateDispatcher)
        val raw = "Bonjour Marie"

        coordinator.update(raw, stableWordCount = raw.words(), totalDictationWordCount = raw.words())
        assertTrue(backend.requests.isEmpty())
        val final = coordinator.finish(raw, raw.words(), raw.words())

        assertEquals(GemmaFineTunedPrompt.Phase.FINAL, backend.requests.single().phase)
        assertEquals(raw, backend.requests.single().text)
        assertEquals("Bonjour.", final.renderedText)
        assertTrue(states.last().acceptedSegments.isNotEmpty())
        coordinator.close()
    }

    @Test fun `stable partial is accepted and appended ASR does not cancel it`() {
        val dispatcher = ManualDispatcher()
        val backend = FakeBackend(outputs = ArrayDeque(listOf("Segment corrigé.")))
        val states = mutableListOf<ProgressiveBufferState>()
        val coordinator = coordinator(backend, states, dispatcher, ImmediateDispatcher)
        val raw = longText()

        coordinator.update(raw, stableWordCount = 65, totalDictationWordCount = 65)
        assertEquals(1, dispatcher.size)
        coordinator.update("$raw ajout", stableWordCount = 65, totalDictationWordCount = 66)
        assertFalse(backend.cancelled)
        dispatcher.runNext()

        assertEquals("Segment corrigé.", states.last().acceptedSegments.single().output)
        assertTrue(states.last().renderedText.contains("ajout"))
        coordinator.close()
    }

    @Test fun `old result after ASR revision is ignored and a new request can run`() {
        val dispatcher = ManualDispatcher()
        val backend = FakeBackend(outputs = ArrayDeque(listOf("nouveau")))
        val states = mutableListOf<ProgressiveBufferState>()
        val coordinator = coordinator(backend, states, dispatcher, ImmediateDispatcher)
        val raw = longText()

        coordinator.update(raw, stableWordCount = 65, totalDictationWordCount = 65)
        val old = coordinator.state().inFlight
        coordinator.update(raw.replace("mot7", "révisé7"), stableWordCount = 65, totalDictationWordCount = 65)
        assertNotNull(old)
        assertTrue(backend.cancelled)
        dispatcher.runNext()
        dispatcher.runNext()

        assertEquals(1, states.last().acceptedSegments.size)
        assertEquals("nouveau", states.last().acceptedSegments.single().output)
        assertTrue(backend.requests.last().text.contains("révisé7"))
        coordinator.close()
    }

    @Test fun `human reset opens an epoch and rejects an old completion`() {
        val dispatcher = FirstManualThenImmediateDispatcher()
        val backend = FakeBackend(outputs = ArrayDeque(listOf("suite corrigée")))
        val states = mutableListOf<ProgressiveBufferState>()
        val coordinator = coordinator(backend, states, dispatcher, ImmediateDispatcher)
        val raw = longText()

        coordinator.update(raw, stableWordCount = 65, totalDictationWordCount = 65)
        coordinator.resetForHumanEdit("Texte tapé")
        dispatcher.runFirst()
        coordinator.update("suite euh", stableWordCount = 2, totalDictationWordCount = 2)
        coordinator.finish("suite euh", 2, 2)

        assertEquals("suite corrigée", states.last().renderedText)
        assertTrue(states.last().humanEpoch > 0)
        coordinator.close()
    }

    @Test fun `normalization is applied before request but chunks never reach publication`() {
        val dispatcher = ManualDispatcher()
        val backend = FakeBackend(outputs = ArrayDeque(listOf("Sortie validée.")), emitChunk = true)
        val states = mutableListOf<ProgressiveBufferState>()
        val coordinator = coordinator(
            backend = backend,
            states = states,
            work = dispatcher,
            main = ImmediateDispatcher,
            normalizer = { it.replace("euh ", "") },
        )
        val raw = longText().replaceFirst("mot1", "euh mot1")

        coordinator.update(raw, stableWordCount = 65, totalDictationWordCount = 65)
        dispatcher.runNext()

        assertTrue(backend.requests.single().text.startsWith("mot1"))
        assertEquals("Sortie validée.", states.last().acceptedSegments.single().output)
        assertFalse(states.last().renderedText.contains("chunk-brut"))
        coordinator.close()
    }

    @Test fun `rejected partial advances instead of repeating the same source`() {
        val dispatcher = ManualDispatcher()
        val backend = FakeBackend(outputs = ArrayDeque(listOf(null, "Deuxième segment.")))
        val states = mutableListOf<ProgressiveBufferState>()
        val coordinator = coordinator(backend, states, dispatcher, ImmediateDispatcher)
        val raw = longText() + " Une seconde phrase commence ici et se termine maintenant. suite " +
            (1..20).joinToString(" ") { "fin$it" }

        coordinator.update(raw, stableWordCount = raw.words(), totalDictationWordCount = raw.words())
        dispatcher.runNext()
        assertEquals(1, dispatcher.size)
        val second = coordinator.state().inFlight
        assertNotNull(second)
        assertTrue(second!!.sourceStart > 0)
        dispatcher.runNext()

        assertEquals(2, backend.requests.size)
        assertTrue(states.last().acceptedSegments.single().output.contains("Deuxième"))
        coordinator.close()
    }

    @Test fun `no committed stability means no anticipatory partial request`() {
        val dispatcher = ManualDispatcher()
        val backend = FakeBackend(outputs = ArrayDeque())
        val coordinator = coordinator(backend, mutableListOf(), dispatcher, ImmediateDispatcher)

        coordinator.update(longText(), stableWordCount = 0, totalDictationWordCount = 65)

        assertEquals(0, dispatcher.size)
        assertTrue(backend.requests.isEmpty())
        coordinator.close()
    }

    @Test fun `queued obsolete request is dropped before backend generation`() {
        val dispatcher = ManualDispatcher()
        val backend = FakeBackend(outputs = ArrayDeque(listOf("ne doit pas sortir")))
        val coordinator = coordinator(backend, mutableListOf(), dispatcher, ImmediateDispatcher)

        coordinator.update(longText(), stableWordCount = 65, totalDictationWordCount = 65)
        coordinator.resetForHumanEdit("Texte corrigé")
        dispatcher.runNext()

        assertTrue(backend.requests.isEmpty())
        coordinator.close()
    }

    @Test fun `final deadline preserves raw remainder and cancels backend`() {
        val backend = BlockingBackend()
        val states = mutableListOf<ProgressiveBufferState>()
        val coordinator = ProgressiveFormattingCoordinator(
            mode = LocalLayoutKind.TEXT,
            backend = backend,
            mainDispatcher = ImmediateDispatcher,
            finalWaitMs = 30L,
            requestFactory = { _, source ->
                LocalFormatRequest(
                    text = source,
                    instructions = "format",
                    language = "French",
                    layoutKind = null,
                )
            },
            onSnapshot = states::add,
        )
        val raw = "un texte court"

        val started = System.nanoTime()
        val final = coordinator.finish(raw, stableWordCount = raw.words(), totalDictationWordCount = raw.words())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(raw, final.renderedText)
        assertTrue(backend.cancelled)
        assertTrue(elapsedMs < 1_000L)
        coordinator.close()
    }

    @Test fun `final-only coordinator tracks revisions but emits one final request`() {
        val backend = FakeBackend(outputs = ArrayDeque(listOf("texte final relu")))
        val submitted = mutableListOf<LocalFormatRequest>()
        val diagnostic = ProgressiveFormattingDiagnostic(ProgressiveMonotonicClock { 1L })
        val coordinator = ProgressiveFormattingCoordinator(
            mode = LocalLayoutKind.TEXT,
            backend = backend,
            mainDispatcher = ImmediateDispatcher,
            finalWaitMs = 3_000L,
            allowPartialRequests = false,
            diagnostic = diagnostic,
            requestFactory = { segment, source ->
                LocalFormatRequest(
                    text = source,
                    instructions = "",
                    language = "français",
                    layoutKind = LocalLayoutKind.TEXT,
                    validation = LocalFormatValidation.EXACT_LAYOUT,
                    phase = segment.phase,
                    contextBefore = segment.contextBefore,
                ).also(submitted::add)
            },
        )
        val firstSource = longText()
        val revisedSource = firstSource.replace("mot1", "source2")

        coordinator.update(firstSource, stableWordCount = 25, totalDictationWordCount = 65)
        coordinator.update(revisedSource, stableWordCount = 30, totalDictationWordCount = 65)
        assertTrue("final-only updates must not launch partial work", backend.requests.isEmpty())

        coordinator.finish(revisedSource, stableWordCount = 65, totalDictationWordCount = 65)

        assertEquals(1, backend.requests.size)
        assertEquals(1, submitted.size)
        assertEquals(GemmaFineTunedPrompt.Phase.FINAL, submitted.single().phase)
        assertEquals("français", submitted.single().language)
        assertEquals(revisedSource, submitted.single().text)
        val snapshot = coordinator.diagnosticSnapshot()!!
        assertEquals(0, snapshot.scheduledPartial)
        assertEquals(1, snapshot.scheduledFinal)
        assertTrue(snapshot.summary().contains("limite_attente_configuree=3000ms"))
        coordinator.close()
    }

    @Test fun `close races a completed request without accepting its result`() {
        val dispatcher = ManualDispatcher()
        val backend = FakeBackend(outputs = ArrayDeque(listOf("ancien résultat")))
        val coordinator = coordinator(backend, mutableListOf(), dispatcher, ImmediateDispatcher)

        coordinator.update(longText(), stableWordCount = 65, totalDictationWordCount = 65)
        coordinator.close()
        dispatcher.runNext()

        assertTrue(coordinator.state().acceptedSegments.isEmpty())
        assertFalse(coordinator.state().renderedText.contains("ancien résultat"))
    }

    private fun coordinator(
        backend: FakeBackend,
        states: MutableList<ProgressiveBufferState>,
        work: ProgressiveTaskDispatcher,
        main: ProgressiveTaskDispatcher,
        normalizer: (String) -> String = { it },
    ) = ProgressiveFormattingCoordinator(
        mode = LocalLayoutKind.TEXT,
        backend = backend,
        normalizer = normalizer,
        workDispatcher = work,
        mainDispatcher = main,
        requestFactory = { segment, source ->
            LocalFormatRequest(
                text = source,
                instructions = "format",
                language = "French",
                // The coordinator tests exercise scheduling/epochs. Layout lexical validation
                // is covered by GemmaFaithfulLayout tests; a null kind lets these fixtures use
                // compact sentinel outputs while still asserting the segment mode below.
                layoutKind = null,
                validation = LocalFormatValidation.GEMMA_PROJECTION,
                simpleEmailLayout = false,
                phase = segment.phase,
                contextBefore = segment.contextBefore,
            )
        },
        onSnapshot = states::add,
    )

    private fun longText(): String =
        (1..25).joinToString(" ") { "mot$it" } + ". " +
            (26..65).joinToString(" ") { "suite$it" }

    private fun String.words(): Int = trim().split(Regex("\\s+")).filter(String::isNotEmpty).size

    private object ImmediateDispatcher : ProgressiveTaskDispatcher {
        override fun dispatch(task: () -> Unit) = task()
    }

    private class ManualDispatcher : ProgressiveTaskDispatcher {
        private val tasks = ArrayDeque<() -> Unit>()
        val size: Int get() = tasks.size
        override fun dispatch(task: () -> Unit) { tasks.addLast(task) }
        fun runNext() { tasks.removeFirst().invoke() }
    }

    private class FirstManualThenImmediateDispatcher : ProgressiveTaskDispatcher {
        private var first = true
        private val held = ArrayDeque<() -> Unit>()
        override fun dispatch(task: () -> Unit) {
            if (first) {
                first = false
                held.addLast(task)
            } else task()
        }
        fun runFirst() { held.removeFirst().invoke() }
    }

    private class FakeBackend(
        private val outputs: ArrayDeque<String?>,
        private val emitChunk: Boolean = false,
    ) : LocalFormatBackend {
        val requests = mutableListOf<LocalFormatRequest>()
        var cancelled = false
        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? =
            generate(request, onChunk, {})

        override fun generate(
            request: LocalFormatRequest,
            onChunk: (String) -> Unit,
            onNativeStart: () -> Unit,
        ): String? {
            requests += request
            onNativeStart()
            if (emitChunk) onChunk("chunk-brut")
            return if (outputs.isEmpty()) null else outputs.removeFirst()
        }

        override fun cancel() { cancelled = true }
    }

    private class BlockingBackend : LocalFormatBackend {
        private val release = CountDownLatch(1)
        var cancelled = false

        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? =
            generate(request, onChunk, {})

        override fun generate(
            request: LocalFormatRequest,
            onChunk: (String) -> Unit,
            onNativeStart: () -> Unit,
        ): String? {
            onNativeStart()
            release.await(2, TimeUnit.SECONDS)
            return null
        }

        override fun cancel() {
            cancelled = true
            release.countDown()
        }
    }
}
