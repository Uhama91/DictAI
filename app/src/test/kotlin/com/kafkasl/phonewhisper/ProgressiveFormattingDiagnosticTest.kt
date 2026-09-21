package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveFormattingDiagnosticTest {
    @Test
    fun `successful partial records schedule launch timings and only a fragment flag`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        val dispatcher = ManualDispatcher()
        val backend = RecordingBackend(clock, output = "segment corrige.", emitChunk = true)
        val coordinator = coordinator(clock, diagnostic, dispatcher, backend)

        coordinator.update(longText(), stableWordCount = 65, totalDictationWordCount = 65)
        assertEquals(1, diagnostic.snapshot().scheduledPartial)
        assertEquals(0, diagnostic.snapshot().launchedPartial)

        clock.advance(4)
        dispatcher.runNext()

        val snapshot = diagnostic.snapshot()
        assertEquals(1, snapshot.scheduledPartial)
        assertEquals(1, snapshot.launchedPartial)
        assertEquals(1, snapshot.startedDuringDictation)
        assertEquals(1, snapshot.resultsApplied)
        assertEquals(0, snapshot.resultsUnchanged)
        assertEquals(4L, snapshot.calls.single().dispatcherWaitMs)
        assertEquals(7L, snapshot.calls.single().backendWaitMs)
        // The native duration starts at onNativeStart: 3 ms to the first chunk plus
        // 11 ms until return.
        assertEquals(14L, snapshot.calls.single().generationMs)
        assertEquals(3L, snapshot.calls.single().firstFragmentMs)
        assertTrue(snapshot.calls.single().firstFragmentSeen)
        assertFalse(snapshot.summary().contains("segment corrige"))
        coordinator.close()
    }

    @Test
    fun `null return is absent and does not become a timeout`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        val dispatcher = ImmediateDispatcher()
        val backend = RecordingBackend(clock, output = null)
        val coordinator = coordinator(clock, diagnostic, dispatcher, backend)

        coordinator.finish("un texte court", 3, 3)

        val snapshot = diagnostic.snapshot()
        assertEquals(1, snapshot.scheduledFinal)
        assertEquals(1, snapshot.resultsAbsent)
        assertEquals(0, snapshot.finalDeadlineExceeded)
        assertTrue(snapshot.summary().contains("absents=1"))
        coordinator.close()
    }

    @Test
    fun `rejected output is counted separately from an absent return`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        val backend = RecordingBackend(clock, output = "<think>private reasoning</think>")
        val coordinator = coordinator(clock, diagnostic, ImmediateDispatcher(), backend)

        coordinator.update(longText(), 65, 65)

        val snapshot = diagnostic.snapshot()
        assertEquals(1, snapshot.resultsRejected)
        assertEquals(0, snapshot.resultsAbsent)
        assertEquals(0, snapshot.finalDeadlineExceeded)
        coordinator.close()
    }

    @Test
    fun `final deadline is distinct from a late queued callback`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        val dispatcher = ManualDispatcher()
        val backend = RecordingBackend(clock, output = "final corrige")
        val coordinator = coordinator(clock, diagnostic, dispatcher, backend, finalWaitMs = 5)

        coordinator.finish(longText(), 65, 65)
        var snapshot = diagnostic.snapshot()
        assertEquals(1, snapshot.finalDeadlineExceeded)
        assertEquals(0, snapshot.resultsAbsent)

        // A caller-owned queue may still execute the stale task after the timeout.
        dispatcher.runNext()
        snapshot = diagnostic.snapshot()
        assertEquals(1, snapshot.finalDeadlineExceeded)
        assertEquals(0, snapshot.resultsApplied)
        assertEquals(0, snapshot.resultsNotReturned)
        coordinator.close()
    }

    @Test
    fun `queued work cancelled by human edit never reaches the backend`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        val dispatcher = ManualDispatcher()
        val backend = RecordingBackend(clock, output = "ne doit pas partir")
        val coordinator = coordinator(clock, diagnostic, dispatcher, backend)

        coordinator.update(longText(), 65, 65)
        coordinator.resetForHumanEdit("préfixe saisi")
        dispatcher.runNext()

        val snapshot = diagnostic.snapshot()
        assertEquals(0, snapshot.launchedPartial)
        assertEquals(1, snapshot.resultsNotReturned)
        assertEquals(1, snapshot.cancellations[ProgressiveCancellationReason.HUMAN_EDIT])
        coordinator.close()
    }

    @Test
    fun `close rejects a queued callback and records close cancellation`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        val dispatcher = ManualDispatcher()
        val coordinator = coordinator(
            clock,
            diagnostic,
            dispatcher,
            RecordingBackend(clock, output = "late"),
        )

        coordinator.update(longText(), 65, 65)
        coordinator.close()
        dispatcher.runNext()

        val snapshot = diagnostic.snapshot()
        assertEquals(0, snapshot.launchedPartial)
        assertEquals(1, snapshot.resultsNotReturned)
        assertEquals(1, snapshot.cancellations[ProgressiveCancellationReason.CLOSE])
    }

    @Test
    fun `an output equal to its source is counted as unchanged`() {
        val diagnostic = ProgressiveFormattingDiagnostic(FakeClock())
        val request = ProgressiveFormatRequest(
            id = 1,
            humanEpoch = 0,
            sourceStart = 0,
            sourceEndExclusive = 5,
            source = "même source",
            contextBefore = "",
            mode = LocalLayoutKind.TEXT,
            phase = GemmaFineTunedPrompt.Phase.FINAL,
        )
        diagnostic.schedule(request)
        diagnostic.markWorkerStarted(request.id)
        diagnostic.markBackendEntered(request.id)
        diagnostic.markNativeStarted(request.id)
        diagnostic.markGenerationReturned(request.id)
        diagnostic.markValidationStarted(request.id)
        diagnostic.markSettled(request, request.source, request.source, current = true)

        val snapshot = diagnostic.snapshot()
        assertEquals(0, snapshot.resultsApplied)
        assertEquals(1, snapshot.resultsUnchanged)
        assertEquals(1, snapshot.stillDelivered)
    }

    @Test
    fun `calls launched after capture end are not marked as during dictation`() {
        val diagnostic = ProgressiveFormattingDiagnostic(FakeClock())
        diagnostic.markDictationEnded()
        diagnostic.schedule(1, GemmaFineTunedPrompt.Phase.PARTIAL)
        diagnostic.markWorkerStarted(1)

        val snapshot = diagnostic.snapshot()
        assertEquals(1, snapshot.launchedPartial)
        assertEquals(0, snapshot.startedDuringDictation)
    }

    @Test
    fun `backend return after an in-flight revision is invalidated and finished`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        lateinit var coordinator: ProgressiveFormattingCoordinator
        val backend = RecordingBackend(
            clock = clock,
            output = "retour tardif",
            onGenerate = { coordinator.resetForHumanEdit("édition humaine") },
        )
        coordinator = coordinator(clock, diagnostic, ImmediateDispatcher(), backend)

        coordinator.update(longText(), 65, 65)

        val snapshot = diagnostic.snapshot()
        assertEquals(1, snapshot.resultsNotReturned)
        assertEquals(0, snapshot.resultsApplied)
        assertEquals(0, snapshot.stillDelivered)
        assertTrue(snapshot.calls.single().outcome == ProgressiveCallOutcome.INVALIDATED)
        coordinator.close()
    }

    @Test
    fun `revision invalidates accepted segment and records cancellation reason`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        val dispatcher = ManualDispatcher()
        val backend = RecordingBackend(clock, output = "segment corrige.")
        val coordinator = coordinator(clock, diagnostic, dispatcher, backend)

        coordinator.update(longText(), 65, 65)
        dispatcher.runNext()
        coordinator.update(longText().replace("mot7", "révisé7"), 65, 65)

        val snapshot = diagnostic.snapshot()
        assertEquals(1, snapshot.acceptedThenInvalidated)
        assertEquals(0, snapshot.stillDelivered)
        assertEquals(1, snapshot.cancellations[ProgressiveCancellationReason.ASR_REVISION])
        coordinator.close()
    }

    @Test
    fun `summary is bounded and has no source or exception text`() {
        val diagnostic = ProgressiveFormattingDiagnostic(FakeClock())
        repeat(64) { id ->
            diagnostic.schedule(id.toLong() + 1, GemmaFineTunedPrompt.Phase.PARTIAL)
            diagnostic.markNotReturned(id.toLong() + 1)
        }

        val summary = diagnostic.snapshot().summary()
        assertTrue(summary.length <= ProgressiveFormattingDiagnostic.MAX_SUMMARY_CHARS)
        assertFalse(summary.contains("private source"))
        assertFalse(summary.contains("exception"))
    }

    @Test
    fun `aggregate invalidation survives eviction of old call details`() {
        val diagnostic = ProgressiveFormattingDiagnostic(FakeClock())
        repeat(40) { index ->
            val id = index.toLong() + 1
            val request = ProgressiveFormatRequest(
                id = id,
                humanEpoch = 0,
                sourceStart = 0,
                sourceEndExclusive = 10,
                source = "source-$id",
                contextBefore = "",
                mode = LocalLayoutKind.TEXT,
                phase = GemmaFineTunedPrompt.Phase.PARTIAL,
            )
            diagnostic.schedule(request)
            diagnostic.markWorkerStarted(id)
            diagnostic.markBackendEntered(id)
            diagnostic.markNativeStarted(id)
            diagnostic.markGenerationReturned(id)
            diagnostic.markValidationStarted(id)
            diagnostic.markSettled(request, "sortie-$id", "sortie-$id", current = true)
        }

        diagnostic.invalidateAccepted(1, ProgressiveCancellationReason.HUMAN_EDIT)
        val snapshot = diagnostic.snapshot()
        assertEquals(40, snapshot.resultsApplied)
        assertEquals(1, snapshot.acceptedThenInvalidated)
        assertEquals(39, snapshot.stillDelivered)
        assertEquals(1, snapshot.cancellations[ProgressiveCancellationReason.HUMAN_EDIT])
    }

    @Test
    fun `queued cancellations stay terminal and late callbacks do not grow the trace`() {
        val clock = FakeClock()
        val diagnostic = ProgressiveFormattingDiagnostic(clock)
        val dispatcher = ManualDispatcher()
        val coordinator = coordinator(
            clock,
            diagnostic,
            dispatcher,
            RecordingBackend(clock, output = "ne doit jamais partir"),
        )

        repeat(40) { index ->
            coordinator.update(longText() + " cycle$index", 65, 65)
            coordinator.resetForHumanEdit("édition $index")
        }

        val beforeLateCallbacks = diagnostic.snapshot()
        assertEquals(40, beforeLateCallbacks.resultsNotReturned)
        assertTrue(beforeLateCallbacks.calls.size <= 32)

        repeat(40) { dispatcher.runNext() }

        val afterLateCallbacks = diagnostic.snapshot()
        assertEquals(beforeLateCallbacks.resultsNotReturned, afterLateCallbacks.resultsNotReturned)
        assertEquals(beforeLateCallbacks.cancellations, afterLateCallbacks.cancellations)
        assertTrue(afterLateCallbacks.calls.size <= 32)
        coordinator.close()
    }

    @Test
    fun `bounded summary keeps the newest final detail visible`() {
        val diagnostic = ProgressiveFormattingDiagnostic(FakeClock())
        repeat(39) { id ->
            diagnostic.schedule(id.toLong() + 1, GemmaFineTunedPrompt.Phase.PARTIAL)
            diagnostic.markNotReturned(id.toLong() + 1)
        }
        diagnostic.schedule(40, GemmaFineTunedPrompt.Phase.FINAL)
        diagnostic.markNotReturned(40)

        val summary = diagnostic.snapshot().summary()
        assertTrue(summary.length <= ProgressiveFormattingDiagnostic.MAX_SUMMARY_CHARS)
        assertTrue(summary.contains("appel=40 phase=final"))
    }

    private fun coordinator(
        clock: FakeClock,
        diagnostic: ProgressiveFormattingDiagnostic,
        dispatcher: ProgressiveTaskDispatcher,
        backend: RecordingBackend,
        finalWaitMs: Long = 5_000,
    ) = ProgressiveFormattingCoordinator(
        mode = LocalLayoutKind.TEXT,
        backend = backend,
        workDispatcher = dispatcher,
        mainDispatcher = ProgressiveTaskDispatcher { it() },
        diagnostic = diagnostic,
        clock = clock,
        finalWaitMs = finalWaitMs,
        requestFactory = { segment, source ->
            LocalFormatRequest(
                text = source,
                instructions = "format",
                language = "French",
                layoutKind = null,
                validation = LocalFormatValidation.GEMMA_PROJECTION,
                phase = segment.phase,
                contextBefore = segment.contextBefore,
            )
        },
    )

    private fun longText(): String =
        (1..25).joinToString(" ") { "mot$it" } + ". " +
            (26..65).joinToString(" ") { "suite$it" }

    private class FakeClock : ProgressiveMonotonicClock {
        private var now = 0L
        override fun nowNanos(): Long = now * 1_000_000L
        fun advance(milliseconds: Long) { now += milliseconds }
    }

    private class ManualDispatcher : ProgressiveTaskDispatcher {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun dispatch(task: () -> Unit) { tasks.addLast(task) }
        fun runNext() { tasks.removeFirst().invoke() }
    }

    private class ImmediateDispatcher : ProgressiveTaskDispatcher {
        override fun dispatch(task: () -> Unit) = task()
    }

    private class RecordingBackend(
        private val clock: FakeClock,
        private val output: String?,
        private val emitChunk: Boolean = false,
        private val onGenerate: (() -> Unit)? = null,
    ) : LocalFormatBackend {
        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? =
            generate(request, onChunk, {})

        override fun generate(
            request: LocalFormatRequest,
            onChunk: (String) -> Unit,
            onNativeStart: () -> Unit,
        ): String? {
            clock.advance(7)
            onNativeStart()
            if (emitChunk) {
                clock.advance(3)
                onChunk("private chunk")
            }
            onGenerate?.invoke()
            clock.advance(11)
            return output
        }

        override fun cancel() = Unit
    }
}
