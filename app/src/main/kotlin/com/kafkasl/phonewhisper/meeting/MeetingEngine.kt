package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock

interface MeetingSession : AutoCloseable {
    fun acceptPcm16(buffer: ByteArray, length: Int): Boolean
    fun checkpoint(): CompletableFuture<Unit>
    fun finish()
    fun cancel()
    override fun close() = cancel()

    val closed: CompletableFuture<Unit>
    val queuedAudioMs: Long
    val progress: MeetingProgressSnapshot
        get() = MeetingProgressSnapshot.EMPTY
    val voiceProgress: MeetingVoiceProgress
        get() = MeetingVoiceProgress.EMPTY
}

/** Client callbacks run on the session worker or the native listener's thread; keep them nonblocking. */
class MeetingEngine internal constructor(
    private val asrPath: String,
    private val diarPath: String,
    private val native: MeetingNativeBridge,
    private val queueCapacityBytes: Int,
    private val spoolRoot: File? = null,
    private val spoolCapacityBytes: Long = 0L,
    private val spoolSegmentTargetBytes: Int = MeetingAudioQueue.DEFAULT_SEGMENT_BYTES,
    private val monotonicClockNanos: () -> Long = System::nanoTime,
    /** Test observation seam; production callers leave this null. */
    private val onSessionClosingForTest: (() -> Unit)? = null,
) {
    private val engineLock = Any()
    private var activeSession: SessionImpl? = null
    private var poisonedByCloseFailure = false

    init {
        require(queueCapacityBytes >= BYTES_PER_PCM_SAMPLE && queueCapacityBytes % BYTES_PER_PCM_SAMPLE == 0) {
            "queueCapacityBytes must contain complete PCM16 samples"
        }
    }

    constructor(
        asrPath: String,
        diarPath: String,
        native: MeetingNativeBridge = HandyMeetingNativeBridge(),
    ) : this(asrPath, diarPath, native, MeetingAudioQueue.DEFAULT_CAPACITY_BYTES)

    /** Production constructor: audio backlog is private to the app and lives only in cacheDir. */
    constructor(
        asrPath: String,
        diarPath: String,
        cacheDir: File,
        native: MeetingNativeBridge = HandyMeetingNativeBridge(
            diarizationSpoolRoot = File(cacheDir, AUDIO_SPOOL_DIRECTORY),
        ),
    ) : this(
        asrPath,
        diarPath,
        native,
        MeetingAudioQueue.DEFAULT_MEMORY_CAPACITY_BYTES,
        File(cacheDir, AUDIO_SPOOL_DIRECTORY),
        MeetingAudioQueue.MAX_SPOOL_BYTES,
    )

    fun start(
        runId: String,
        language: String,
        onReady: () -> Unit,
        onUpdate: (MeetingHypothesis) -> Unit,
        onFailure: (String) -> Unit,
    ): MeetingSession {
        val session = synchronized(engineLock) {
            check(!poisonedByCloseFailure) { "La session précédente n’a pas pu être libérée." }
            check(activeSession == null) { "Une session de réunion est déjà active." }
            SessionImpl(runId, language, onReady, onUpdate, onFailure).also { activeSession = it }
        }
        session.launch()
        return session
    }

    private fun sessionClosed(session: SessionImpl, closeFailed: Boolean) {
        synchronized(engineLock) {
            if (closeFailed) {
                poisonedByCloseFailure = true
            } else if (activeSession === session) {
                activeSession = null
            }
        }
    }

    private data class CheckpointBarrier(
        val targetBlockCount: Long,
        val future: CompletableFuture<Unit>,
    )

    private enum class CheckpointResult {
        COMPLETE,
        FAILED,
        PENDING,
    }

    private inner class SessionImpl(
        private val runId: String,
        private val language: String,
        private val onReady: () -> Unit,
        private val onUpdate: (MeetingHypothesis) -> Unit,
        private val onFailure: (String) -> Unit,
    ) : MeetingSession {
        private val stateLock = Any()
        private val offerOrderLock = Any()
        private val nativeControlLock = Any()
        private val publicationLock = ReentrantLock()
        private val publicationsDrained = publicationLock.newCondition()
        private val queue = MeetingAudioQueue(
            capacityBytes = queueCapacityBytes,
            spoolRoot = spoolRoot,
            spoolCapacityBytes = spoolCapacityBytes,
            segmentTargetBytes = spoolSegmentTargetBytes,
        )
        private val progressTracker = MeetingProgressTracker(monotonicClockNanos)
        private val worker = Thread({ runWorker() }, WORKER_NAME).apply { isDaemon = true }

        override val closed = CompletableFuture<Unit>()

        private var ready = false
        private var finishRequested = false
        private var cancelRequested = false
        private var closing = false
        private var failed = false
        private var acceptedPcm = false
        private var failureDelivered = false
        private var pendingOffers = 0
        private var acceptedBlockCount = 0L
        private var processedBlockCount = 0L
        private var activeNativeHandle: Long? = null
        private var nativeCancelRequested = false
        private var activePublications = 0
        private val pendingCheckpoints = mutableListOf<CheckpointBarrier>()
        @Volatile
        private var lastVoiceProgress = MeetingVoiceProgress.EMPTY

        override val queuedAudioMs: Long
            get() = progressTracker.snapshot().queuedAudioMs

        override val progress: MeetingProgressSnapshot
            get() = progressTracker.snapshot()

        override val voiceProgress: MeetingVoiceProgress
            get() {
                val handle = synchronized(stateLock) {
                    activeNativeHandle.takeUnless { closing || closed.isDone }
                }
                if (handle != null) {
                    try {
                        // Bridge contract: this reads its own volatile/cache snapshot; it must never enter JNI.
                        lastVoiceProgress = native.voiceProgress(handle)
                    } catch (_: Throwable) {
                        // Keep the most recent valid snapshot if an optional progress source fails.
                    }
                }
                return lastVoiceProgress
            }

        fun launch() {
            try {
                worker.start()
            } catch (_: Throwable) {
                synchronized(stateLock) {
                    failed = true
                    closing = true
                    queue.cancel()
                    queue.close()
                }
                sessionClosed(this, closeFailed = false)
                closed.complete(Unit)
                throw IllegalStateException(FAILURE_MESSAGE)
            }
        }

        override fun acceptPcm16(buffer: ByteArray, length: Int): Boolean {
            if (length <= 0 || length > buffer.size || length > MAX_PCM_BYTES || length % BYTES_PER_PCM_SAMPLE != 0) {
                return false
            }
            return synchronized(offerOrderLock) {
                val nativeHandle = synchronized(stateLock) {
                    val handle = activeNativeHandle
                    if (!ready || finishRequested || cancelRequested || closing || failed || closed.isDone || handle == null) {
                        null
                    } else {
                        pendingOffers += 1
                        handle
                    }
                } ?: return@synchronized false

                var captureHookFailure: Throwable? = null
                val result = queue.offer(buffer, length) {
                    progressTracker.accepted(length)
                    try {
                        native.onPcmCaptured(nativeHandle, buffer, length)
                    } catch (failure: Throwable) {
                        // The PCM queue has committed this block. Let offer finish and wake the worker,
                        // then fail the session before another producer can advance the voice timeline.
                        captureHookFailure = failure
                    }
                }
                var accepted = false
                var queueError: String? = null
                var shouldFinishInput = false
                var impossibleCheckpoints: List<CompletableFuture<Unit>> = emptyList()
                synchronized(stateLock) {
                    pendingOffers -= 1
                    if (result == MeetingAudioQueue.OfferResult.ACCEPTED &&
                        !cancelRequested && !failed && !closing && !closed.isDone
                    ) {
                        acceptedPcm = true
                        acceptedBlockCount += 1
                        accepted = captureHookFailure == null
                    }
                    if (!cancelRequested && !failed && !closing && !closed.isDone) {
                        when (result) {
                            MeetingAudioQueue.OfferResult.LIMIT_REACHED -> queueError = AUDIO_SPOOL_LIMIT_ERROR
                            MeetingAudioQueue.OfferResult.IO_ERROR -> queueError = AUDIO_SPOOL_IO_ERROR
                            MeetingAudioQueue.OfferResult.ACCEPTED,
                            MeetingAudioQueue.OfferResult.CLOSED,
                            MeetingAudioQueue.OfferResult.FULL -> Unit
                        }
                    }
                    val achievableBlockCount = acceptedBlockCount + pendingOffers
                    impossibleCheckpoints = pendingCheckpoints
                        .filter { it.targetBlockCount > achievableBlockCount }
                        .map { it.future }
                    pendingCheckpoints.removeAll { it.targetBlockCount > achievableBlockCount }
                    shouldFinishInput = finishRequested && pendingOffers == 0 && queueError == null &&
                        !cancelRequested && !failed && !closing && !closed.isDone
                }
                failCheckpoints(impossibleCheckpoints)
                if (queueError != null) {
                    reportFailure(queueError!!)
                } else if (captureHookFailure != null) {
                    reportFailure()
                } else if (shouldFinishInput) {
                    queue.finishInput()
                }
                accepted
            }
        }

        override fun checkpoint(): CompletableFuture<Unit> {
            val future = CompletableFuture<Unit>()
            val result = synchronized(stateLock) {
                when {
                    cancelRequested || failed -> CheckpointResult.FAILED
                    processedBlockCount >= acceptedBlockCount + pendingOffers -> CheckpointResult.COMPLETE
                    else -> {
                        pendingCheckpoints += CheckpointBarrier(acceptedBlockCount + pendingOffers, future)
                        CheckpointResult.PENDING
                    }
                }
            }
            when (result) {
                CheckpointResult.COMPLETE -> future.complete(Unit)
                CheckpointResult.FAILED -> future.completeExceptionally(checkpointFailure())
                CheckpointResult.PENDING -> Unit
            }
            return future
        }

        override fun finish() {
            synchronized(offerOrderLock) {
                val finishInput = synchronized(stateLock) {
                    if (finishRequested || cancelRequested || closing || failed || closed.isDone) {
                        false
                    } else {
                        finishRequested = true
                        pendingOffers == 0
                    }
                }
                if (finishInput) queue.finishInput()
            }
        }

        override fun cancel() {
            val (checkpoints, nativeHandleToCancel) = synchronized(stateLock) {
                if (closed.isDone) return
                cancelRequested = true
                ready = false
                queue.cancel()
                val handleToCancel = activeNativeHandle?.takeIf {
                    !closing && !nativeCancelRequested
                }?.also { nativeCancelRequested = true }
                val checkpoints = pendingCheckpoints.map { it.future }
                pendingCheckpoints.clear()
                checkpoints to handleToCancel
            }
            progressTracker.abandonWaiting()
            nativeHandleToCancel?.let { handle ->
                signalNativeCancel(handle)
                refreshVoiceProgress(handle)
            }
            failCheckpoints(checkpoints)
        }

        override fun close() = cancel()

        private fun runWorker() {
            var handle: Long? = null
            var closeFailed = false
            try {
                val openedHandle = native.open(asrPath, diarPath, language)
                if (openedHandle <= 0L) throw IllegalStateException("Invalid native meeting handle")
                handle = openedHandle
                refreshVoiceProgress(openedHandle)
                val cancelAfterOpen = synchronized(stateLock) {
                    activeNativeHandle = openedHandle
                    if (cancelRequested && !nativeCancelRequested) {
                        nativeCancelRequested = true
                        openedHandle
                    } else {
                        null
                    }
                }
                cancelAfterOpen?.let { cancelledHandle ->
                    signalNativeCancel(cancelledHandle)
                    refreshVoiceProgress(cancelledHandle)
                }
                native.setUpdateListener(openedHandle) { update -> publish(update) }
                native.awaitCaptureReady(openedHandle)
                queue.prepareSpool()
                announceReady()

                while (true) {
                    if (isStopping()) break
                    val pcm = queue.take() ?: break
                    if (!beginNativeOperation()) break
                    if (!progressTracker.beginNative(pcm.size)) break
                    val updates = try {
                        native.acceptPcm16(openedHandle, pcm, pcm.size)
                    } catch (failure: Throwable) {
                        refreshVoiceProgress(openedHandle)
                        progressTracker.completeNative(pcm.size, succeeded = false)
                        throw failure
                    }
                    refreshVoiceProgress(openedHandle)
                    progressTracker.completeNative(pcm.size, succeeded = true)
                    publish(updates)
                    markBlockProcessed()
                }

                if (beginNativeFinish()) {
                    publish(native.finish(openedHandle))
                    refreshVoiceProgress(openedHandle)
                }
            } catch (failure: MeetingAudioSpoolException) {
                reportFailure(AUDIO_SPOOL_IO_ERROR)
            } catch (failure: MeetingNativeCleanupUncertainException) {
                closeFailed = true
                reportFailure()
            } catch (_: Throwable) {
                reportFailure()
            } finally {
                handle?.let(::refreshVoiceProgress)
                beginClosing()
                val handleToClose = handle
                if (handleToClose != null) {
                    try {
                        native.setUpdateListener(handleToClose, null)
                    } catch (_: Throwable) {
                        // A failed detach must not prevent the native lease from being closed.
                    }
                    try {
                        native.close(handleToClose)
                    } catch (_: Throwable) {
                        closeFailed = true
                        reportFailure()
                    }
                }
                queue.close()
                sessionClosed(this, closeFailed)
                if (!closeFailed) {
                    closed.complete(Unit)
                } else {
                    closed.completeExceptionally(IllegalStateException(CLOSE_FAILURE_MESSAGE))
                }
            }
        }

        private fun announceReady() {
            val admitted = synchronized(stateLock) {
                if (cancelRequested || finishRequested || failed || closing || closed.isDone) {
                    false
                } else {
                    ready = true
                    true
                }
            }
            // The reservation is atomic with cancellation; callback code never runs under stateLock.
            if (admitted) runCallback(onReady)
        }

        private fun publish(updates: List<MeetingNativeUpdate>) {
            updates.forEach(::publish)
        }

        private fun publish(update: MeetingNativeUpdate) {
            publicationLock.lock()
            val admitted = try {
                val canPublish = synchronized(stateLock) {
                    ready && !cancelRequested && !failed && !closing && !closed.isDone
                }
                if (canPublish) activePublications += 1
                canPublish
            } finally {
                publicationLock.unlock()
            }
            if (!admitted) return

            try {
                val hypothesis = MeetingHypothesis(
                    runId = runId,
                    utteranceId = update.utteranceId,
                    revision = update.revision,
                    words = update.words.toList(),
                    transcript = update.transcript,
                    isFinal = update.isFinal,
                    stableSpeakerThroughMs = update.stableSpeakerThroughMs,
                    audioProcessedMs = update.audioProcessedMs,
                )
                runCallback { onUpdate(hypothesis) }
            } finally {
                publicationLock.lock()
                try {
                    activePublications -= 1
                    if (activePublications == 0) publicationsDrained.signalAll()
                } finally {
                    publicationLock.unlock()
                }
            }
        }

        private fun signalNativeCancel(handle: Long) {
            synchronized(nativeControlLock) {
                val shouldSignal = synchronized(stateLock) {
                    activeNativeHandle == handle && cancelRequested && !closing && !closed.isDone
                }
                if (shouldSignal) {
                    try {
                        native.requestCancel(handle)
                    } catch (_: Throwable) {
                        // Cancellation must stay available even if a bridge cannot signal its worker.
                    }
                }
            }
        }

        private fun refreshVoiceProgress(handle: Long) {
            try {
                lastVoiceProgress = native.voiceProgress(handle)
            } catch (_: Throwable) {
                // Optional progress never affects ASR or native-resource ownership.
            }
        }

        private fun beginNativeOperation(): Boolean = synchronized(stateLock) {
            !cancelRequested && !failed && !closing && !closed.isDone
        }

        private fun beginNativeFinish(): Boolean = synchronized(stateLock) {
            finishRequested && acceptedPcm && !cancelRequested && !failed && !closing && !closed.isDone
        }

        private fun isStopping(): Boolean = synchronized(stateLock) {
            cancelRequested || failed || closing || closed.isDone
        }

        private fun reportFailure(message: String = FAILURE_MESSAGE) {
            val (admitted, barriers) = synchronized(stateLock) {
                failed = true
                ready = false
                queue.cancel()
                val pending = pendingCheckpoints.map { it.future }
                pendingCheckpoints.clear()
                val shouldDeliver = if (failureDelivered || cancelRequested || closed.isDone) {
                    false
                } else {
                    failureDelivered = true
                    true
                }
                shouldDeliver to pending
            }
            progressTracker.abandonWaiting()
            failCheckpoints(barriers)
            if (admitted) runCallback { onFailure(message) }
        }

        private fun markBlockProcessed() {
            val completed = synchronized(stateLock) {
                processedBlockCount += 1
                val ready = pendingCheckpoints.filter { it.targetBlockCount <= processedBlockCount }
                pendingCheckpoints.removeAll(ready.toSet())
                ready.map { it.future }
            }
            completed.forEach { it.complete(Unit) }
        }

        private fun failCheckpoints(barriers: List<CompletableFuture<Unit>>) {
            barriers.forEach { it.completeExceptionally(checkpointFailure()) }
        }

        private fun checkpointFailure() = IllegalStateException(FAILURE_MESSAGE)

        private fun beginClosing() {
            synchronized(nativeControlLock) {
                synchronized(stateLock) {
                    closing = true
                    ready = false
                }
            }
            try {
                onSessionClosingForTest?.invoke()
            } catch (_: Throwable) {
                // A test observer must never short-circuit native lease release.
            }
            publicationLock.lock()
            try {
                while (activePublications > 0) {
                    publicationsDrained.awaitUninterruptibly()
                }
            } finally {
                publicationLock.unlock()
            }
        }

        private fun runCallback(callback: () -> Unit) {
            try {
                callback()
            } catch (_: Throwable) {
                // Caller callbacks must not break native ownership or the session worker.
            }
        }

    }

    companion object {
        internal const val AUDIO_SPOOL_LIMIT_ERROR = "La file audio de la réunion a atteint sa limite."
        internal const val AUDIO_SPOOL_IO_ERROR = "Le tampon audio de la réunion ne peut plus être enregistré."
        const val BYTES_PER_PCM_SAMPLE = 2
        const val BYTES_PER_MILLISECOND = 32L
        const val MAX_PCM_BYTES = MeetingAudioQueue.DEFAULT_CAPACITY_BYTES
        const val WORKER_NAME = "dictai-meeting-worker"
        const val FAILURE_MESSAGE = "La session de réunion n’a pas pu être traitée."
        const val CLOSE_FAILURE_MESSAGE = "La session de réunion n’a pas pu être libérée."
        const val AUDIO_SPOOL_DIRECTORY = "meeting-audio-spool"
    }
}
