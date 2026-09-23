package com.kafkasl.phonewhisper

import java.util.EnumMap
import java.util.LinkedHashMap

/** Monotonic time seam used by the progressive-formatting trace and its deterministic tests. */
internal fun interface ProgressiveMonotonicClock {
    fun nowNanos(): Long
}

internal object SystemProgressiveMonotonicClock : ProgressiveMonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

internal enum class ProgressiveCancellationReason {
    END_OF_DICTATION,
    ASR_REVISION,
    HUMAN_EDIT,
    CLOSE,
    FINAL_DEADLINE,
}

internal enum class ProgressiveCallOutcome {
    PENDING,
    APPLIED_MODIFIED,
    APPLIED_UNCHANGED,
    REJECTED,
    ABSENT,
    NOT_RETURNED,
    FINAL_DEADLINE_EXCEEDED,
    INVALIDATED,
}

internal data class ProgressiveCallTiming(
    val id: Long,
    val phase: GemmaFineTunedPrompt.Phase,
    val startedDuringDictation: Boolean,
    val nativeStarted: Boolean,
    val nativeStartedDuringDictation: Boolean,
    val dispatcherWaitMs: Long?,
    val backendWaitMs: Long?,
    val generationMs: Long?,
    val firstFragmentMs: Long?,
    val firstFragmentSeen: Boolean,
    val validationMs: Long?,
    val outcome: ProgressiveCallOutcome,
    val cancellation: ProgressiveCancellationReason?,
    val acceptedThenInvalidated: Boolean,
)

/** Immutable, text-free snapshot suitable for the persisted copyable diagnostic. */
internal data class ProgressiveFormattingDiagnosticSnapshot(
    val scheduledPartial: Int,
    val scheduledFinal: Int,
    val launchedPartial: Int,
    val launchedFinal: Int,
    val startedDuringDictation: Int,
    val nativeStartedDuringDictation: Int,
    val resultsApplied: Int,
    val resultsUnchanged: Int,
    val resultsRejected: Int,
    val resultsAbsent: Int,
    val resultsNotReturned: Int,
    val acceptedThenInvalidated: Int,
    val stillDelivered: Int,
    val finalDeadlineExceeded: Int,
    val cancellations: Map<ProgressiveCancellationReason, Int>,
    val calls: List<ProgressiveCallTiming>,
    val configuredWaitLimitMs: Long? = null,
) {
    /** A bounded summary that deliberately contains no dictated text or exception details. */
    fun summary(): String {
        val cancellationSummary = ProgressiveCancellationReason.entries.joinToString(
            separator = ",",
            prefix = "annulations=",
        ) { reason ->
            "${reason.name.lowercase()}=${cancellations[reason] ?: 0}"
        }
        val header = buildString {
            append("Progressif: planifies_partial=$scheduledPartial")
            append("; planifies_final=$scheduledFinal")
            append("; lances_partial=$launchedPartial")
            append("; lances_final=$launchedFinal")
            append("; travailleurs_demarres_pendant_dictée=$startedDuringDictation")
            append("; natifs_demarres_pendant_dictée=$nativeStartedDuringDictation")
            append("; appliques=$resultsApplied")
            append("; inchanges=$resultsUnchanged")
            append("; rejetes=$resultsRejected")
            append("; absents=$resultsAbsent")
            append("; non_retournes=$resultsNotReturned")
            append("; acceptes_puis_invalides=$acceptedThenInvalidated")
            append("; encore_livres=$stillDelivered")
            append("; echeances_finales_depassees=$finalDeadlineExceeded")
            configuredWaitLimitMs?.let { append("; limite_attente_configuree=${it}ms") }
            append("; $cancellationSummary")
        }
        // Newest calls carry the useful end-of-dictation evidence. Keep them first so the
        // bounded copyable summary does not truncate the final request behind older partials.
        val detail = calls.asReversed().joinToString(separator = "\n") { call ->
            "appel=${call.id} phase=${call.phase.name.lowercase()} " +
                "attente_dispatch=${call.dispatcherWaitMs ?: -1}ms " +
                "attente_backend=${call.backendWaitMs ?: -1}ms " +
                "generation=${call.generationMs ?: -1}ms " +
                "premier_fragment=${call.firstFragmentMs ?: -1}ms " +
                "appel_natif=${if (call.nativeStarted) "oui" else "non"} " +
                "validation=${call.validationMs ?: -1}ms " +
                "fragment=${if (call.firstFragmentSeen) "oui" else "non"} " +
                "etat=${call.outcome.name.lowercase()}"
        }
        return (if (detail.isEmpty()) header else "$header\n$detail")
            .take(MAX_SUMMARY_CHARS)
    }

    companion object {
        const val MAX_SUMMARY_CHARS: Int = 4_000
    }
}

/**
 * Records scheduling and lifecycle facts for progressive calls without retaining prompts,
 * generated text, chunks, or exception messages.
 */
internal class ProgressiveFormattingDiagnostic(
    private val clock: ProgressiveMonotonicClock,
) {
    companion object {
        const val MAX_SUMMARY_CHARS: Int = ProgressiveFormattingDiagnosticSnapshot.MAX_SUMMARY_CHARS
        private const val MAX_CALL_DETAILS = 32
        private const val MAX_EVICTED_TOMBSTONES = 64
    }

    private data class EvictedCall(
        val wasAccepted: Boolean,
        var invalidated: Boolean = false,
    )

    private class MutableCall(
        val id: Long,
        val phase: GemmaFineTunedPrompt.Phase,
        val scheduledAtNanos: Long,
    ) {
        var workerStartedAtNanos: Long? = null
        var backendEnteredAtNanos: Long? = null
        var nativeStartedAtNanos: Long? = null
        var returnedAtNanos: Long? = null
        var validationStartedAtNanos: Long? = null
        var validationFinishedAtNanos: Long? = null
        var firstFragmentAtNanos: Long? = null
        var startedDuringDictation = false
        var nativeStartedDuringDictation = false
        var cancellation: ProgressiveCancellationReason? = null
        var outcome = ProgressiveCallOutcome.PENDING
        var acceptedKind: ProgressiveCallOutcome? = null
        var acceptedThenInvalidated = false
        var finished = false

        fun timing(): ProgressiveCallTiming {
            val worker = workerStartedAtNanos
            val backendEntered = backendEnteredAtNanos
            val native = nativeStartedAtNanos
            val returned = returnedAtNanos
            return ProgressiveCallTiming(
                id = id,
                phase = phase,
                startedDuringDictation = startedDuringDictation,
                nativeStarted = native != null,
                nativeStartedDuringDictation = nativeStartedDuringDictation,
                dispatcherWaitMs = worker?.let { elapsedMs(scheduledAtNanos, it) },
                backendWaitMs = if (backendEntered != null && native != null) elapsedMs(backendEntered, native) else null,
                generationMs = if (native != null && returned != null) elapsedMs(native, returned) else null,
                firstFragmentMs = if (native != null && firstFragmentAtNanos != null)
                    elapsedMs(native, firstFragmentAtNanos!!) else null,
                firstFragmentSeen = firstFragmentAtNanos != null,
                validationMs = if (returned != null && validationFinishedAtNanos != null)
                    elapsedMs(returned, validationFinishedAtNanos!!) else null,
                outcome = outcome,
                cancellation = cancellation,
                acceptedThenInvalidated = acceptedThenInvalidated,
            )
        }

        private fun elapsedMs(start: Long, end: Long): Long =
            ((end - start).coerceAtLeast(0L) / 1_000_000L)
    }

    private val lock = Any()
    private val calls = linkedMapOf<Long, MutableCall>()
    /** Bounded tombstones make late callbacks for evicted calls harmless. */
    private val evictedCalls = LinkedHashMap<Long, EvictedCall>()
    private val cancellationCounts = EnumMap<ProgressiveCancellationReason, Int>(
        ProgressiveCancellationReason::class.java,
    )
    private var dictationEnded = false
    private var scheduledPartial = 0
    private var scheduledFinal = 0
    private var launchedPartial = 0
    private var launchedFinal = 0
    private var startedDuringDictation = 0
    private var nativeStartedDuringDictation = 0
    private var resultsApplied = 0
    private var resultsUnchanged = 0
    private var resultsRejected = 0
    private var resultsAbsent = 0
    private var resultsNotReturned = 0
    private var acceptedThenInvalidated = 0
    private var stillDelivered = 0
    private var finalDeadlineExceeded = 0

    fun schedule(request: ProgressiveFormatRequest): Unit = synchronized(lock) {
        if (calls.containsKey(request.id) || evictedCalls.containsKey(request.id)) return@synchronized
        evictFinishedDetailsIfNeeded()
        calls[request.id] = MutableCall(request.id, request.phase, clock.nowNanos())
        when (request.phase) {
            GemmaFineTunedPrompt.Phase.PARTIAL -> scheduledPartial++
            GemmaFineTunedPrompt.Phase.FINAL -> scheduledFinal++
        }
    }

    internal fun schedule(id: Long, phase: GemmaFineTunedPrompt.Phase): Unit = synchronized(lock) {
        if (calls.containsKey(id) || evictedCalls.containsKey(id)) return@synchronized
        evictFinishedDetailsIfNeeded()
        calls[id] = MutableCall(id, phase, clock.nowNanos())
        when (phase) {
            GemmaFineTunedPrompt.Phase.PARTIAL -> scheduledPartial++
            GemmaFineTunedPrompt.Phase.FINAL -> scheduledFinal++
        }
    }

    fun markDictationEnded() = synchronized(lock) {
        dictationEnded = true
    }

    fun markWorkerStarted(id: Long) = synchronized(lock) {
        calls[id]?.let { call ->
            if (!call.finished && call.workerStartedAtNanos == null) {
                call.workerStartedAtNanos = clock.nowNanos()
                call.startedDuringDictation = !dictationEnded
                when (call.phase) {
                    GemmaFineTunedPrompt.Phase.PARTIAL -> launchedPartial++
                    GemmaFineTunedPrompt.Phase.FINAL -> launchedFinal++
                }
                if (call.startedDuringDictation) startedDuringDictation++
            }
        }
    }

    fun markNativeStarted(id: Long) = synchronized(lock) {
        calls[id]?.let { call ->
            if (!call.finished && call.nativeStartedAtNanos == null) {
                call.nativeStartedAtNanos = clock.nowNanos()
                call.nativeStartedDuringDictation = !dictationEnded
                if (call.nativeStartedDuringDictation) nativeStartedDuringDictation++
            }
        }
    }

    fun markBackendEntered(id: Long) = synchronized(lock) {
        calls[id]?.let { call ->
            if (call.backendEnteredAtNanos == null) call.backendEnteredAtNanos = clock.nowNanos()
        }
    }

    fun markFirstFragment(id: Long) = synchronized(lock) {
        calls[id]?.let { call ->
            if (call.firstFragmentAtNanos == null) call.firstFragmentAtNanos = clock.nowNanos()
        }
    }

    fun markGenerationReturned(id: Long) = synchronized(lock) {
        calls[id]?.let { call ->
            if (call.returnedAtNanos == null) call.returnedAtNanos = clock.nowNanos()
        }
    }

    fun markValidationStarted(id: Long) = synchronized(lock) {
        calls[id]?.validationStartedAtNanos = clock.nowNanos()
    }

    fun markNotReturned(id: Long) = synchronized(lock) {
        val call = calls[id]
        if (call != null && !call.finished) {
            call.outcome = ProgressiveCallOutcome.NOT_RETURNED
            call.finished = true
            resultsNotReturned++
        } else if (call == null && evictedCalls.containsKey(id)) {
            // The terminal aggregate was recorded before detail eviction. A late callback must
            // not manufacture a second result for that same request.
            return@synchronized
        }
    }

    fun markFinalDeadlineExceeded(id: Long) = synchronized(lock) {
        val call = calls[id]
        if (call == null || call.finished) return@synchronized
        incrementCancellation(call, ProgressiveCancellationReason.FINAL_DEADLINE)
        call.outcome = ProgressiveCallOutcome.FINAL_DEADLINE_EXCEEDED
        call.finished = true
        finalDeadlineExceeded++
    }

    fun markCancelled(id: Long, reason: ProgressiveCancellationReason) = synchronized(lock) {
        val call = calls[id] ?: return@synchronized
        if (call.finished) return@synchronized
        incrementCancellation(call, reason)
        call.outcome = ProgressiveCallOutcome.INVALIDATED
        call.finished = true
        resultsNotReturned++
    }

    fun markSettled(
        request: ProgressiveFormatRequest,
        generated: String?,
        validated: String?,
        current: Boolean,
    ) = synchronized(lock) {
        val call = calls[request.id] ?: return@synchronized
        if (call.finished) return@synchronized
        call.validationFinishedAtNanos = clock.nowNanos()
        if (!current) {
            call.outcome = ProgressiveCallOutcome.INVALIDATED
            call.finished = true
            resultsNotReturned++
            return@synchronized
        }
        call.outcome = when {
            generated == null -> ProgressiveCallOutcome.ABSENT
            validated == null -> ProgressiveCallOutcome.REJECTED
            validated == request.source -> ProgressiveCallOutcome.APPLIED_UNCHANGED
            else -> ProgressiveCallOutcome.APPLIED_MODIFIED
        }
        if (call.outcome == ProgressiveCallOutcome.APPLIED_MODIFIED ||
            call.outcome == ProgressiveCallOutcome.APPLIED_UNCHANGED
        ) {
            call.acceptedKind = call.outcome
            if (call.outcome == ProgressiveCallOutcome.APPLIED_MODIFIED) resultsApplied++
            else resultsUnchanged++
            stillDelivered++
        } else if (call.outcome == ProgressiveCallOutcome.REJECTED) {
            resultsRejected++
        } else if (call.outcome == ProgressiveCallOutcome.ABSENT) {
            resultsAbsent++
        }
        call.finished = true
    }

    fun invalidateAccepted(id: Long, reason: ProgressiveCancellationReason) = synchronized(lock) {
        val call = calls[id]
        if (call == null) {
            val evicted = evictedCalls[id]
            if (evicted == null || !evicted.wasAccepted || evicted.invalidated) return@synchronized
            evicted.invalidated = true
            incrementCancellation(null, reason)
            acceptedThenInvalidated++
            stillDelivered = (stillDelivered - 1).coerceAtLeast(0)
        } else if (call.acceptedKind != null && !call.acceptedThenInvalidated) {
            call.acceptedThenInvalidated = true
            call.outcome = ProgressiveCallOutcome.INVALIDATED
            incrementCancellation(call, reason)
            acceptedThenInvalidated++
            stillDelivered = (stillDelivered - 1).coerceAtLeast(0)
        }
    }

    fun snapshot(configuredWaitLimitMs: Long? = null): ProgressiveFormattingDiagnosticSnapshot = synchronized(lock) {
        val timings = calls.values.map { it.timing() }
        val cancellations = ProgressiveCancellationReason.entries.associateWith { reason ->
            cancellationCounts[reason] ?: 0
        }
        ProgressiveFormattingDiagnosticSnapshot(
            scheduledPartial = scheduledPartial,
            scheduledFinal = scheduledFinal,
            launchedPartial = launchedPartial,
            launchedFinal = launchedFinal,
            startedDuringDictation = startedDuringDictation,
            nativeStartedDuringDictation = nativeStartedDuringDictation,
            resultsApplied = resultsApplied,
            resultsUnchanged = resultsUnchanged,
            resultsRejected = resultsRejected,
            resultsAbsent = resultsAbsent,
            resultsNotReturned = resultsNotReturned,
            acceptedThenInvalidated = acceptedThenInvalidated,
            stillDelivered = stillDelivered,
            finalDeadlineExceeded = finalDeadlineExceeded,
            cancellations = cancellations,
            calls = timings,
            configuredWaitLimitMs = configuredWaitLimitMs,
        )
    }

    private fun incrementCancellation(call: MutableCall?, reason: ProgressiveCancellationReason) {
        if (call != null) {
            if (call.cancellation == reason) return
            if (call.cancellation != null) return
            call.cancellation = reason
        }
        cancellationCounts[reason] = (cancellationCounts[reason] ?: 0) + 1
    }

    private fun evictFinishedDetailsIfNeeded() {
        while (calls.size >= MAX_CALL_DETAILS) {
            val evict = calls.entries.firstOrNull { it.value.finished }?.key ?: return
            calls.remove(evict)?.let { call ->
                evictedCalls[evict] = EvictedCall(
                    wasAccepted = call.acceptedKind != null,
                    invalidated = call.acceptedThenInvalidated,
                )
                while (evictedCalls.size > MAX_EVICTED_TOMBSTONES) {
                    evictedCalls.entries.firstOrNull()?.let { evictedCalls.remove(it.key) }
                }
            }
        }
    }

}
