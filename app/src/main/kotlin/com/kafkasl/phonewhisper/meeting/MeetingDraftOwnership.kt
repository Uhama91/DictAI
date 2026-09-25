package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Keeps only a detachable listener; a registry does not retain an Activity or Service. */
internal class MeetingDraftErrorRelay(initialError: String? = null) {
    private val lock = Any()
    private var listener: ((String?) -> Unit)? = null

    @Volatile
    private var error: String? = initialError

    val lastError: String?
        get() = error

    fun attach(listener: ((String?) -> Unit)?): Unit {
        val currentError = synchronized(lock) {
            this.listener = listener
            error
        }
        if (currentError != null && listener != null) notifyListener(listener, currentError)
    }

    fun detach(): Unit {
        synchronized(lock) { listener = null }
    }

    fun publish(message: String?): Unit {
        val currentListener = synchronized(lock) {
            error = message
            listener
        }
        if (currentListener != null) notifyListener(currentListener, message)
    }

    private fun notifyListener(listener: (String?) -> Unit, message: String?) {
        try {
            listener(message)
        } catch (_: Throwable) {
            // UI callback failures must not interfere with draft ownership or writes.
        }
    }
}

internal fun interface MeetingDraftOwnershipExecutor {
    fun execute(task: () -> Unit): Unit
}

internal fun interface MeetingDraftOwnershipWriterFactory {
    fun create(path: File, errorRelay: MeetingDraftErrorRelay): MeetingDraftWriter
}

internal fun interface MeetingDraftOwnershipReader {
    fun read(path: File): MeetingDocumentRead
}

internal fun interface MeetingDraftOwnershipClearer {
    fun clear(path: File): Unit
}

/** A path claim carries only the writer, immutable data, and registry callbacks. */
internal class MeetingDraftOwnershipClaim internal constructor(
    val path: File,
    val writer: MeetingDraftWriter,
    val errorRelay: MeetingDraftErrorRelay,
    val recoveredDocument: MeetingDocumentRead,
    val snapshot: MeetingDocument?,
    val saveError: String?,
    private val rememberAction: (MeetingDocument) -> Unit,
    private val relinquishAction: (MeetingDocument?) -> CompletableFuture<Unit>,
    private val clearSaveErrorAction: () -> Unit = {},
) {
    private val lock = Any()
    private var latestSnapshot = snapshot
    private var relinquishFuture: CompletableFuture<Unit>? = null

    fun rememberSnapshot(document: MeetingDocument): Unit {
        synchronized(lock) {
            check(relinquishFuture == null) { "Meeting draft claim is being relinquished" }
            rememberAction(document)
            latestSnapshot = document
        }
    }

    fun relinquish(snapshot: MeetingDocument): CompletableFuture<Unit> = relinquishInternal(snapshot)

    internal fun clearSaveError(): Unit {
        errorRelay.publish(null)
        clearSaveErrorAction()
    }

    internal fun relinquishLatest(): CompletableFuture<Unit> = relinquishInternal(
        synchronized(lock) { latestSnapshot },
    )

    private fun relinquishInternal(snapshot: MeetingDocument?): CompletableFuture<Unit> {
        val completion: CompletableFuture<Unit>
        synchronized(lock) {
            relinquishFuture?.let { return it }
            if (snapshot != null) {
                rememberAction(snapshot)
                latestSnapshot = snapshot
            }
            completion = CompletableFuture()
            relinquishFuture = completion
        }
        try {
            relinquishAction(snapshot).whenComplete { _, failure ->
                if (failure == null) completion.complete(Unit)
                else completion.completeExceptionally(failure)
            }
        } catch (_: Throwable) {
            completion.completeExceptionally(IllegalStateException("Impossible de transférer le brouillon de réunion."))
        }
        return completion
    }
}

internal class MeetingDraftOwnershipRequest internal constructor(
    val future: CompletableFuture<MeetingDraftOwnershipClaim>,
    private val cancelAction: () -> Unit,
) {
    fun cancel(): Unit = cancelAction()
}

/** The process-wide registry serializes reads, writer ownership, and transfers by private path. */
internal class MeetingDraftOwnership(
    private val executor: MeetingDraftOwnershipExecutor = DefaultMeetingDraftOwnershipExecutor,
    private val writerFactory: MeetingDraftOwnershipWriterFactory = MeetingDraftOwnershipWriterFactory { path, relay ->
        MeetingDraftWriter(MeetingDraftStore(path), onErrorChanged = relay::publish)
    },
    private val reader: MeetingDraftOwnershipReader = MeetingDraftOwnershipReader { path ->
        MeetingDraftStore(path).load()
    },
    private val clearer: MeetingDraftOwnershipClearer = MeetingDraftOwnershipClearer { path ->
        MeetingDraftStore(path).clear()
    },
) {
    companion object {
        val processWide: MeetingDraftOwnership by lazy { MeetingDraftOwnership() }

        private const val CANCELLED_MESSAGE = "Demande de brouillon annulée."
        private const val READ_ERROR_MESSAGE = "Impossible de lire le brouillon de réunion."
        private const val WRITER_ERROR_MESSAGE = "Impossible d’ouvrir le brouillon de réunion."
        private const val OWNERSHIP_ERROR_MESSAGE = "Impossible de transférer le brouillon de réunion."
        private const val SAVE_ERROR_MESSAGE = "Sauvegarde impossible"
        private const val CLEAR_REFUSED_MESSAGE = "Le brouillon de réunion ne correspond plus à la session attendue."
        private const val CLEAR_ERROR_MESSAGE = "Impossible d’effacer le brouillon de réunion."
    }

    private val lock = Any()
    private val paths = mutableMapOf<String, MeetingDraftPathState>()

    fun claim(path: File): MeetingDraftOwnershipRequest {
        val normalizedPath = File(path.absoluteFile.path)
        val state = synchronized(lock) {
            paths.getOrPut(normalizedPath.path) { MeetingDraftPathState(normalizedPath) }
        }
        val pending = MeetingDraftPendingRequest(state)
        val request = MeetingDraftOwnershipRequest(pending.future) { cancel(pending) }
        pending.request = request
        synchronized(lock) {
            state.waiting.addLast(pending)
        }
        schedulePump(state)
        return request
    }

    fun clear(path: File, expectedSessionId: String): CompletableFuture<Unit> =
        File(path.absoluteFile.path).let { normalizedPath ->
            val state = synchronized(lock) {
                paths.getOrPut(normalizedPath.path) { MeetingDraftPathState(normalizedPath) }
            }
            val pending = MeetingDraftPendingClear(state, expectedSessionId)
            synchronized(lock) {
                state.waiting.addLast(pending)
            }
            schedulePump(state)
            pending.future
        }

    fun rememberSnapshot(claim: MeetingDraftOwnershipClaim, snapshot: MeetingDocument): Unit {
        synchronized(lock) {
            val state = paths[claim.path.path]
            check(state?.owner === claim) { "Meeting draft claim is no longer current" }
            state.latestSnapshot = snapshot
            state.saveError = claim.errorRelay.lastError
        }
    }

    private fun refreshSaveError(claim: MeetingDraftOwnershipClaim): Unit {
        synchronized(lock) {
            val state = paths[claim.path.path]
            check(state?.owner === claim) { "Meeting draft claim is no longer current" }
            state.saveError = claim.errorRelay.lastError
        }
    }

    fun relinquish(
        claim: MeetingDraftOwnershipClaim,
        snapshot: MeetingDocument,
    ): CompletableFuture<Unit> = relinquishClaim(claim, snapshot)

    private fun cancel(pending: MeetingDraftPendingRequest) {
        val deliveredClaim: MeetingDraftOwnershipClaim?
        var completeCancelled = false
        synchronized(lock) {
            if (pending.cancelled) return
            pending.cancelled = true
            pending.state.waiting.remove(pending)
            deliveredClaim = pending.claim
            if (deliveredClaim == null && !pending.future.isDone) {
                completeCancelled = true
            }
        }
        if (completeCancelled) {
            pending.future.completeExceptionally(CancellationException(CANCELLED_MESSAGE))
        }
        if (deliveredClaim != null) {
            deliveredClaim.relinquishLatest()
        } else {
            schedulePump(pending.state)
        }
    }

    private fun schedulePump(state: MeetingDraftPathState) {
        val schedule = synchronized(lock) {
            if (state.processing || state.pumpScheduled || state.owner != null) {
                false
            } else {
                state.pumpScheduled = true
                true
            }
        }
        if (!schedule) return
        try {
            executor.execute {
                synchronized(lock) { state.pumpScheduled = false }
                pump(state)
            }
        } catch (_: Throwable) {
            synchronized(lock) { state.pumpScheduled = false }
            failNextPending(state, IllegalStateException(OWNERSHIP_ERROR_MESSAGE))
        }
    }

    private fun pump(state: MeetingDraftPathState) {
        val pending = synchronized(lock) {
            while ((state.waiting.firstOrNull() as? MeetingDraftPendingRequest)?.cancelled == true) {
                state.waiting.removeFirst()
            }
            if (state.owner != null || state.processing) return
            state.waiting.firstOrNull()?.also { state.processing = true }
        } ?: return

        if (!state.readComplete) {
            readForPending(state, pending)
        } else {
            when (pending) {
                is MeetingDraftPendingRequest -> createClaimForPending(state, pending)
                is MeetingDraftPendingClear -> clearForPending(state, pending)
            }
        }
    }

    private fun readForPending(state: MeetingDraftPathState, pending: MeetingDraftPendingOperation) {
        val result = try {
            reader.read(state.path)
        } catch (_: Throwable) {
            null
        }
        var readFailure = false
        synchronized(lock) {
            state.processing = false
            if (result == null) {
                state.waiting.remove(pending)
                readFailure = true
            } else {
                state.readComplete = true
                state.recoveredDocument = result
                if (state.latestSnapshot == null && result is MeetingDocumentRead.Ready) {
                    state.latestSnapshot = result.document
                }
            }
        }
        if (readFailure) {
            completeExceptionally(pending, IllegalStateException(READ_ERROR_MESSAGE))
        }
        schedulePump(state)
    }

    private fun clearForPending(state: MeetingDraftPathState, pending: MeetingDraftPendingClear) {
        val shouldClear = synchronized(lock) {
            val recovered = state.recoveredDocument
            if (recovered is MeetingDocumentRead.Unsupported || recovered is MeetingDocumentRead.Invalid) {
                false
            } else {
                val knownDocument = state.latestSnapshot ?: (recovered as? MeetingDocumentRead.Ready)?.document
                when {
                    knownDocument == null && recovered == MeetingDocumentRead.Absent -> null
                    knownDocument == null -> false
                    knownDocument.sessionId != pending.expectedSessionId -> false
                    else -> true
                }
            }
        }

        when (shouldClear) {
            false -> finishClear(
                state = state,
                pending = pending,
                failure = IllegalStateException(CLEAR_REFUSED_MESSAGE),
            )
            null -> finishClear(state = state, pending = pending, failure = null)
            true -> {
                val clearFailure = try {
                    clearer.clear(state.path)
                    null
                } catch (_: Throwable) {
                    IllegalStateException(CLEAR_ERROR_MESSAGE)
                }
                finishClear(state = state, pending = pending, failure = clearFailure)
            }
        }
    }

    private fun finishClear(
        state: MeetingDraftPathState,
        pending: MeetingDraftPendingClear,
        failure: Throwable?,
    ) {
        synchronized(lock) {
            state.waiting.remove(pending)
            state.processing = false
            if (failure == null) {
                state.latestSnapshot = null
                state.recoveredDocument = MeetingDocumentRead.Absent
                state.saveError = null
                state.readComplete = true
            } else if (failure.message == CLEAR_ERROR_MESSAGE) {
                state.saveError = CLEAR_ERROR_MESSAGE
            }
        }
        if (failure == null) pending.future.complete(Unit)
        else pending.future.completeExceptionally(failure)
        schedulePump(state)
    }

    private fun createClaimForPending(state: MeetingDraftPathState, pending: MeetingDraftPendingRequest) {
        val relay: MeetingDraftErrorRelay
        val initialError: String?
        val initialSnapshot: MeetingDocument?
        val recovered: MeetingDocumentRead
        val alreadyCancelled = synchronized(lock) {
            if (pending.cancelled) {
                state.processing = false
                state.waiting.remove(pending)
                true
            } else {
                false
            }
        }
        if (alreadyCancelled) {
            pending.future.completeExceptionally(CancellationException(CANCELLED_MESSAGE))
            schedulePump(state)
            return
        }
        synchronized(lock) {
            relay = MeetingDraftErrorRelay(state.saveError)
            initialError = state.saveError
            initialSnapshot = state.latestSnapshot
            recovered = state.recoveredDocument
        }

        val writer = try {
            writerFactory.create(state.path, relay)
        } catch (_: Throwable) {
            synchronized(lock) {
                state.processing = false
                state.waiting.remove(pending)
            }
            pending.future.completeExceptionally(IllegalStateException(WRITER_ERROR_MESSAGE))
            schedulePump(state)
            return
        }

        lateinit var claim: MeetingDraftOwnershipClaim
        claim = MeetingDraftOwnershipClaim(
            path = state.path,
            writer = writer,
            errorRelay = relay,
            recoveredDocument = recovered,
            snapshot = initialSnapshot,
            saveError = initialError,
            rememberAction = { document -> rememberSnapshot(claim, document) },
            relinquishAction = { document -> relinquishClaim(claim, document) },
            clearSaveErrorAction = { refreshSaveError(claim) },
        )

        val abandoned = synchronized(lock) {
            state.waiting.remove(pending)
            if (pending.cancelled) {
                true
            } else {
                state.processing = false
                pending.claim = claim
                state.owner = claim
                state.ownerRequest = pending
                false
            }
        }
        if (abandoned) {
            pending.future.completeExceptionally(CancellationException(CANCELLED_MESSAGE))
            writer.close().whenComplete { _, closeFailure ->
                var safeToContinue = closeFailure == null
                if (closeFailure != null) {
                    safeToContinue = try {
                        writer.retireAfterCloseFailure()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                if (safeToContinue) {
                    synchronized(lock) { state.processing = false }
                    schedulePump(state)
                }
            }
        } else {
            if (!pending.future.complete(claim)) cancel(pending)
        }
    }

    private fun relinquishClaim(
        claim: MeetingDraftOwnershipClaim,
        snapshot: MeetingDocument?,
    ): CompletableFuture<Unit> {
        val state = synchronized(lock) {
            val ownerState = paths[claim.path.path]
            check(ownerState?.owner === claim) { "Meeting draft claim is no longer current" }
            if (snapshot != null) {
                ownerState.latestSnapshot = snapshot
                ownerState.saveError = claim.errorRelay.lastError
            }
            ownerState
        }
        claim.errorRelay.detach()

        val result = CompletableFuture<Unit>()
        val closeFuture = try {
            claim.writer.close()
        } catch (_: Throwable) {
            CompletableFuture<Unit>().also { it.completeExceptionally(IllegalStateException(SAVE_ERROR_MESSAGE)) }
        }
        closeFuture.whenComplete { _, closeFailure ->
            var retirementFailure: Throwable? = null
            if (closeFailure != null) {
                try {
                    claim.writer.retireAfterCloseFailure()
                } catch (failure: Throwable) {
                    retirementFailure = failure
                }
            }
            try {
                executor.execute {
                    val canRelease = synchronized(lock) {
                        if (state.owner !== claim) {
                            false
                        } else if (retirementFailure != null) {
                            state.saveError = SAVE_ERROR_MESSAGE
                            false
                        } else {
                            state.saveError = if (closeFailure == null) {
                                claim.errorRelay.lastError
                            } else {
                                claim.errorRelay.lastError ?: SAVE_ERROR_MESSAGE
                            }
                            state.owner = null
                            state.ownerRequest = null
                            true
                        }
                    }
                    if (retirementFailure != null) {
                        result.completeExceptionally(IllegalStateException(OWNERSHIP_ERROR_MESSAGE))
                    } else if (closeFailure != null) {
                        result.completeExceptionally(IllegalStateException(SAVE_ERROR_MESSAGE))
                    } else {
                        result.complete(Unit)
                    }
                    if (canRelease) schedulePump(state)
                }
            } catch (_: Throwable) {
                result.completeExceptionally(IllegalStateException(OWNERSHIP_ERROR_MESSAGE))
            }
        }
        return result
    }

    private fun failNextPending(state: MeetingDraftPathState, failure: Throwable) {
        val pending = synchronized(lock) { state.waiting.pollFirst() } ?: return
        completeExceptionally(pending, failure)
        schedulePump(state)
    }

    private fun completeExceptionally(pending: MeetingDraftPendingOperation, failure: Throwable): Unit {
        when (pending) {
            is MeetingDraftPendingRequest -> pending.future.completeExceptionally(failure)
            is MeetingDraftPendingClear -> pending.future.completeExceptionally(failure)
        }
    }

}

private class MeetingDraftPathState(val path: File) {
    var readComplete = false
    var processing = false
    var pumpScheduled = false
    var recoveredDocument: MeetingDocumentRead = MeetingDocumentRead.Absent
    var latestSnapshot: MeetingDocument? = null
    var saveError: String? = null
    var owner: MeetingDraftOwnershipClaim? = null
    var ownerRequest: MeetingDraftPendingRequest? = null
    val waiting = ArrayDeque<MeetingDraftPendingOperation>()
}

private sealed interface MeetingDraftPendingOperation {
    val state: MeetingDraftPathState
}

private class MeetingDraftPendingRequest(override val state: MeetingDraftPathState) : MeetingDraftPendingOperation {
    val future = CompletableFuture<MeetingDraftOwnershipClaim>()
    lateinit var request: MeetingDraftOwnershipRequest
    var claim: MeetingDraftOwnershipClaim? = null
    var cancelled = false
}

private class MeetingDraftPendingClear(
    override val state: MeetingDraftPathState,
    val expectedSessionId: String,
) : MeetingDraftPendingOperation {
    val future = CompletableFuture<Unit>()
}

private object DefaultMeetingDraftOwnershipExecutor : MeetingDraftOwnershipExecutor {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "meeting-draft-ownership").apply { isDaemon = true }
    }

    override fun execute(task: () -> Unit): Unit {
        executor.execute(task)
    }
}
