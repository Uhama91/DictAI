package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.util.concurrent.CompletableFuture

interface MeetingSession : AutoCloseable {
    fun acceptPcm16(buffer: ByteArray, length: Int): Boolean
    fun checkpoint(): CompletableFuture<Unit>
    fun finish()
    fun cancel()
    override fun close() = cancel()

    val closed: CompletableFuture<Unit>
    val queuedAudioMs: Long
}

/** Client callbacks run on the session worker; keep them nonblocking. */
class MeetingEngine internal constructor(
    private val asrPath: String,
    private val diarPath: String,
    private val native: MeetingNativeBridge,
    private val queueCapacityBytes: Int,
    private val spoolRoot: File? = null,
    private val spoolCapacityBytes: Long = 0L,
    private val spoolSegmentTargetBytes: Int = MeetingAudioQueue.DEFAULT_SEGMENT_BYTES,
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
        native: MeetingNativeBridge = JniMeetingNative(),
    ) : this(asrPath, diarPath, native, MeetingAudioQueue.DEFAULT_CAPACITY_BYTES)

    /** Production constructor: audio backlog is private to the app and lives only in cacheDir. */
    constructor(
        asrPath: String,
        diarPath: String,
        cacheDir: File,
        native: MeetingNativeBridge = JniMeetingNative(),
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
        private val queue = MeetingAudioQueue(
            capacityBytes = queueCapacityBytes,
            spoolRoot = spoolRoot,
            spoolCapacityBytes = spoolCapacityBytes,
            segmentTargetBytes = spoolSegmentTargetBytes,
        )
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
        private val pendingCheckpoints = mutableListOf<CheckpointBarrier>()

        override val queuedAudioMs: Long
            get() = queue.queuedBytes.toLong() / BYTES_PER_MILLISECOND

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
                val admitted = synchronized(stateLock) {
                    if (!ready || finishRequested || cancelRequested || closing || failed || closed.isDone) {
                        false
                    } else {
                        pendingOffers += 1
                        true
                    }
                }
                if (!admitted) return@synchronized false

                val result = queue.offer(buffer, length)
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
                        accepted = true
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
            val finishInput = synchronized(stateLock) {
                if (finishRequested || cancelRequested || closing || failed || closed.isDone) return
                finishRequested = true
                pendingOffers == 0
            }
            if (finishInput) queue.finishInput()
        }

        override fun cancel() {
            synchronized(stateLock) {
                if (closed.isDone) return
                cancelRequested = true
                ready = false
                queue.cancel()
                pendingCheckpoints.map { it.future }.also { pendingCheckpoints.clear() }
            }.also(::failCheckpoints)
        }

        override fun close() = cancel()

        private fun runWorker() {
            var handle: Long? = null
            var closeFailed = false
            try {
                val openedHandle = native.open(asrPath, diarPath, language)
                if (openedHandle <= 0L) throw IllegalStateException("Invalid native meeting handle")
                handle = openedHandle
                queue.prepareSpool()
                announceReady()

                while (true) {
                    if (isStopping()) break
                    val pcm = queue.take() ?: break
                    if (!beginNativeOperation()) break
                    publish(native.acceptPcm16(openedHandle, pcm, pcm.size))
                    markBlockProcessed()
                }

                if (beginNativeFinish()) {
                    publish(native.finish(openedHandle))
                }
            } catch (failure: MeetingAudioSpoolException) {
                reportFailure(AUDIO_SPOOL_IO_ERROR)
            } catch (_: Throwable) {
                reportFailure()
            } finally {
                beginClosing()
                val handleToClose = handle
                if (handleToClose != null) {
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
            for (update in updates) {
                val admitted = synchronized(stateLock) {
                    !cancelRequested && !failed && !closing && !closed.isDone
                }
                if (admitted) {
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
                }
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
            synchronized(stateLock) {
                closing = true
                ready = false
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
