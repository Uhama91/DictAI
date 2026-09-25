package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal const val TRANSCRIPTION_MODE_UNAVAILABLE_MESSAGE = "Le moteur de transcription est indisponible."
internal const val TRANSCRIPTION_ENGINE_UNAVAILABLE_MESSAGE = "Le moteur de transcription est indisponible."
internal const val TRANSCRIPTION_RUN_CANCELLED_MESSAGE = "Le démarrage de l’enregistrement a été annulé."

internal enum class TranscriptionMode(val preferenceValue: String) {
    DICTATION("dictation"),
    MEETING("meeting");

    companion object {
        fun fromPreference(value: String?): TranscriptionMode =
            entries.firstOrNull { it.preferenceValue == value } ?: DICTATION
    }
}

internal data class TranscriptionModeSnapshot(
    val mode: TranscriptionMode,
    val generation: Long,
    val activeRunMode: TranscriptionMode?,
    val pendingDictationLoads: Int,
    val residentDictationEngines: Int,
    val poisoned: Boolean,
)

/** Process-wide admission and lifetime boundary. Listener callbacks run outside its state lock. */
internal class TranscriptionModeCoordinator internal constructor(
    initialMode: TranscriptionMode = TranscriptionMode.DICTATION,
    private val persistMode: (TranscriptionMode) -> Unit = {},
    private val listenerExecutor: Executor = Executor { it.run() },
) {
    private val lock = Any()
    private val ownerToken = Any()
    private var selectedMode = initialMode
    private var generation = 0L
    private var nextId = 0L
    private var activeRun: RunState? = null
    private val loads = linkedMapOf<Long, LoadState>()
    private val residents = linkedSetOf<Long>()
    private val listeners = linkedMapOf<Long, (TranscriptionModeSnapshot) -> Unit>()
    private var poisoned = false

    fun snapshot(): TranscriptionModeSnapshot = synchronized(lock) { snapshotLocked() }

    /** Listener work is dispatched on the configured executor, never from inside the state lock. */
    fun subscribe(listener: (TranscriptionModeSnapshot) -> Unit): AutoCloseable {
        val id: Long
        synchronized(lock) {
            id = ++nextId
            listeners[id] = listener
        }
        dispatchListener(id)
        return object : AutoCloseable {
            private val closed = AtomicBoolean()
            override fun close() {
                if (closed.compareAndSet(false, true)) synchronized(lock) { listeners.remove(id) }
            }
        }
    }

    /** Mode and generation advance together; an active run or a process poison rejects the change. */
    fun changeMode(mode: TranscriptionMode): Boolean {
        val effects: Effects
        synchronized(lock) {
            if (poisoned || activeRun != null) return false
            if (selectedMode == mode) return true
            try {
                persistMode(mode)
            } catch (_: Throwable) {
                return false
            }
            selectedMode = mode
            generation += 1
            effects = effectsLocked()
        }
        dispatch(effects)
        return true
    }

    /** A lease is held until its caller has stopped the reader and safely closed native state. */
    fun reserveRun(mode: TranscriptionMode): TranscriptionRunLease? {
        val run: RunState
        val effects: Effects
        synchronized(lock) {
            if (poisoned || selectedMode != mode || activeRun != null) return null
            run = RunState(id = ++nextId, mode = mode, generation = generation)
            activeRun = run
            effects = effectsLocked()
        }
        dispatch(effects)
        return TranscriptionRunLease(mode, run.generation, run.ready, run.id, ownerToken) { releaseRun(run.id) }
    }

    /** Validate a Dictation or Meeting lease against the current, unpoisoned process state. */
    internal fun isCurrentRun(lease: TranscriptionRunLease): Boolean = synchronized(lock) {
        if (poisoned || lease.ownerToken !== ownerToken || lease.generation != generation) return@synchronized false
        val run = activeRun ?: return@synchronized false
        run.id == lease.id && run.mode == lease.mode && run.generation == lease.generation
    }

    /**
     * A Dictation preload is distinct from a recording run. Its ticket remains a barrier until
     * either publication succeeds or the caller confirms native cleanup has returned safely.
     */
    fun beginDictationLoad(runLease: TranscriptionRunLease? = null): DictationLoadTicket? {
        val state: LoadState
        val effects: Effects
        synchronized(lock) {
            if (poisoned || selectedMode != TranscriptionMode.DICTATION || loads.isNotEmpty()) return null
            val currentRun = activeRun
            when {
                currentRun == null && runLease == null -> Unit
                currentRun != null && currentRun.mode == TranscriptionMode.DICTATION &&
                    runLease != null && runLease.ownerToken === ownerToken && runLease.id == currentRun.id -> Unit
                else -> return null
            }
            state = LoadState(id = ++nextId, generation = generation, runId = runLease?.id)
            loads[state.id] = state
            effects = effectsLocked()
        }
        dispatch(effects)
        return DictationLoadTicket(
            generation = state.generation,
            publishLoadedEngine = { publishLoad(state.id, it) },
            completeWithoutPublishing = { completeLoadWithoutPublish(state.id) },
        )
    }

    /** Sticky across owners/services for this process. Native causes and messages never escape. */
    fun reportUncertainClose() {
        val effects: Effects
        synchronized(lock) {
            if (poisoned) return
            poisoned = true
            generation += 1
            effects = effectsLocked()
        }
        dispatch(effects)
    }

    internal fun canUseDictationEngine(runLease: TranscriptionRunLease?): Boolean = synchronized(lock) {
        if (poisoned || selectedMode != TranscriptionMode.DICTATION) return@synchronized false
        val currentRun = activeRun
        when {
            currentRun == null -> runLease == null
            else -> currentRun.mode == TranscriptionMode.DICTATION &&
                runLease != null && runLease.ownerToken === ownerToken && runLease.id == currentRun.id
        }
    }

    private fun publishLoad(loadId: Long, onPublish: () -> Unit): DictationResidentRegistration? {
        val registrationId: Long
        val effects: Effects
        synchronized(lock) {
            val load = loads[loadId] ?: return null
            val currentRun = activeRun
            val runStillCompatible = when {
                load.runId != null -> currentRun?.id == load.runId
                currentRun == null -> true
                else -> currentRun.mode == TranscriptionMode.DICTATION
            }
            if (poisoned || selectedMode != TranscriptionMode.DICTATION ||
                generation != load.generation || !runStillCompatible
            ) return null

            // The publication callback only assigns the resident reference under its short state lock.
            // It must not invoke clients or native code while the coordinator lock is held.
            onPublish()
            loads.remove(loadId)
            registrationId = ++nextId
            residents += registrationId
            effects = effectsLocked()
        }
        dispatch(effects)
        return DictationResidentRegistration { releaseResident(registrationId) }
    }

    private fun completeLoadWithoutPublish(loadId: Long) {
        val effects: Effects
        synchronized(lock) {
            if (loads.remove(loadId) == null) return
            effects = effectsLocked()
        }
        dispatch(effects)
    }

    private fun releaseResident(registrationId: Long) {
        val effects: Effects
        synchronized(lock) {
            if (!residents.remove(registrationId)) return
            effects = effectsLocked()
        }
        dispatch(effects)
    }

    private fun releaseRun(runId: Long) {
        val effects: Effects
        synchronized(lock) {
            val run = activeRun?.takeIf { it.id == runId } ?: return
            activeRun = null
            if (run.readyOutcome == null) run.readyOutcome = RunReadyOutcome.Cancelled
            effects = effectsLocked(run)
        }
        dispatch(effects)
    }

    /** Reserve each run's terminal readiness outcome under the same lock as state changes. */
    private fun effectsLocked(completionCandidate: RunState? = activeRun): Effects {
        val candidate = completionCandidate
        if (candidate != null && candidate === activeRun && candidate.readyOutcome == null) {
            candidate.readyOutcome = when {
                poisoned -> RunReadyOutcome.Failed
                isReadyLocked(candidate) -> RunReadyOutcome.Ready
                else -> null
            }
        }
        val completion = candidate?.readyOutcome?.let { RunCompletion(candidate.ready, it) }
            ?.takeUnless { it.future.isDone }
        return Effects(
            listenerIds = listeners.keys.toList(),
            runCompletion = completion,
        )
    }

    private fun isReadyLocked(run: RunState): Boolean =
        !poisoned && activeRun === run && loads.isEmpty() &&
            (run.mode != TranscriptionMode.MEETING || residents.isEmpty())

    private fun dispatch(effects: Effects) {
        when (val completion = effects.runCompletion) {
            null -> Unit
            else -> when (completion.outcome) {
                RunReadyOutcome.Ready -> completion.future.complete(Unit)
                RunReadyOutcome.Cancelled -> completion.future.completeExceptionally(
                    CancellationException(TRANSCRIPTION_RUN_CANCELLED_MESSAGE),
                )
                RunReadyOutcome.Failed -> completion.future.completeExceptionally(
                    IllegalStateException(TRANSCRIPTION_MODE_UNAVAILABLE_MESSAGE),
                )
            }
        }
        effects.listenerIds.forEach(::dispatchListener)
    }

    private fun dispatchListener(id: Long) {
        try {
            listenerExecutor.execute {
                // Multiple state changes may enqueue notifications in a different order than they
                // released the lock. Deliver the current snapshot, never stale captured state.
                val delivery = synchronized(lock) {
                    listeners[id]?.let { listener -> listener to snapshotLocked() }
                } ?: return@execute
                try { delivery.first(delivery.second) } catch (_: Throwable) {
                    /* A listener cannot affect admission. */
                }
            }
        } catch (_: Throwable) {
            // A rejected UI dispatcher must not roll back process state.
        }
    }

    private fun snapshotLocked() = TranscriptionModeSnapshot(
        mode = selectedMode,
        generation = generation,
        activeRunMode = activeRun?.mode,
        pendingDictationLoads = loads.size,
        residentDictationEngines = residents.size,
        poisoned = poisoned,
    )

    private data class RunState(
        val id: Long,
        val mode: TranscriptionMode,
        val generation: Long,
        val ready: CompletableFuture<Unit> = CompletableFuture(),
        var readyOutcome: RunReadyOutcome? = null,
    )

    private data class LoadState(val id: Long, val generation: Long, val runId: Long?)

    private data class Effects(
        val listenerIds: List<Long>,
        val runCompletion: RunCompletion?,
    )

    private data class RunCompletion(
        val future: CompletableFuture<Unit>,
        val outcome: RunReadyOutcome,
    )

    internal companion object {
        private val processLock = Any()
        @Volatile private var processCoordinator: TranscriptionModeCoordinator? = null

        fun process(context: Context): TranscriptionModeCoordinator = synchronized(processLock) {
            processCoordinator ?: run {
                val appContext = context.applicationContext ?: context
                val prefs = PersistencePrefs(appContext)
                val mainHandler = Handler(Looper.getMainLooper())
                TranscriptionModeCoordinator(
                    initialMode = prefs.transcriptionMode,
                    persistMode = { prefs.transcriptionMode = it },
                    listenerExecutor = Executor { runnable -> mainHandler.post(runnable) },
                )
                    .also { processCoordinator = it }
            }
        }

        internal fun clearProcessForTest() = synchronized(processLock) { processCoordinator = null }
    }
}

internal class TranscriptionRunLease internal constructor(
    val mode: TranscriptionMode,
    val generation: Long,
    val ready: CompletableFuture<Unit>,
    internal val id: Long,
    internal val ownerToken: Any,
    private val release: () -> Unit,
) : AutoCloseable {
    override fun close() = release()
}

private enum class RunReadyOutcome {
    Ready,
    Cancelled,
    Failed,
}

internal class DictationLoadTicket internal constructor(
    val generation: Long,
    private val publishLoadedEngine: (() -> Unit) -> DictationResidentRegistration?,
    private val completeWithoutPublishing: () -> Unit,
) : AutoCloseable {
    fun publish(onPublish: () -> Unit): DictationResidentRegistration? = publishLoadedEngine(onPublish)
    fun completeWithoutPublish() = completeWithoutPublishing()
    override fun close() = completeWithoutPublish()
}

/** Call close only after the corresponding native engine close returned successfully. */
internal class DictationResidentRegistration internal constructor(
    private val releaseAfterNativeClose: () -> Unit,
) : AutoCloseable {
    override fun close() = releaseAfterNativeClose()
}
