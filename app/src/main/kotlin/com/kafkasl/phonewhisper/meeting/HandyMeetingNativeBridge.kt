package com.kafkasl.phonewhisper.meeting

import android.os.Process
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.floor
import kotlin.math.roundToLong

/** Narrow injectable boundary for the independent voice-attribution worker. */
internal interface DiarizationSessionPort : Closeable {
    fun acceptPcm16(buffer: ByteArray, lengthBytes: Int)
    fun snapshot(firstFrameIndex: Long, maxFrames: Int = MAX_FRAMES): DiarizationFrameWindow
    fun finish()

    companion object {
        const val MAX_FRAMES = 16_384
    }
}

/**
 * Hybrid meeting bridge: Handy owns the synchronous, priority ASR path while diarization runs
 * independently and may revise already-published utterances through the update listener.
 * Both public MeetingEngine constructors select this bridge by default for the experimental integration;
 * model catalog and store selection remain separate.
 */
internal class HandyMeetingNativeBridge(
    private val handyFactory: (modelPath: String, language: String) -> HandyAsrPort = { path, language ->
        HandyMeetingNative.open(path, language)
    },
    private val diarizationFactory: (modelPath: String) -> DiarizationSessionPort = { path ->
        NativeDiarizationSession(DiarizationNative.open(path))
    },
    private val maxDiarizationQueueBytes: Int = MAX_DIARIZATION_BACKLOG_BYTES,
    /** Test seam; production lowers priority before loading or calling the diarization runtime. */
    private val setDiarWorkerBackgroundPriority: () -> Unit = {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    },
    private val workerFactory: (Runnable, String) -> Thread = { runnable, name ->
        Thread(runnable, name).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    },
) : MeetingNativeBridge {
    private val nextHandle = AtomicLong(1L)
    private val sessions = ConcurrentHashMap<Long, HybridSession>()

    init {
        require(maxDiarizationQueueBytes > 0 && maxDiarizationQueueBytes % PCM_SAMPLE_BYTES == 0) {
            "maxDiarizationQueueBytes must allow complete PCM16 samples"
        }
    }

    override fun open(asrPath: String, diarPath: String, language: String): Long {
        val canonicalLanguage = normalizeLanguage(language)
        val handle = nextHandle.getAndIncrement().also { require(it > 0L) { "Native handle space exhausted" } }
        var handy: HandyAsrPort? = null
        var session: HybridSession? = null
        try {
            val openedHandy = handyFactory(asrPath, canonicalLanguage)
            handy = openedHandy
            val openedSession = HybridSession(handle, diarPath, openedHandy)
            session = openedSession
            check(sessions.putIfAbsent(handle, openedSession) == null) { "Native handle was already registered" }
            openedSession.startDiarizationWorker()
            return handle
        } catch (failure: Throwable) {
            try {
                val openedSession = session
                if (openedSession != null) {
                    sessions.remove(handle, openedSession)
                    openedSession.close()
                } else {
                    handy?.close()
                }
            } catch (cleanupFailure: Throwable) {
                throw MeetingNativeCleanupUncertainException(failure).also {
                    it.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    override fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate> =
        requireSession(handle).acceptPcm16(buffer, length)

    override fun awaitCaptureReady(handle: Long) = requireSession(handle).awaitCaptureReady()

    override fun onPcmCaptured(handle: Long, buffer: ByteArray, length: Int) {
        requireSession(handle).onPcmCaptured(buffer, length)
    }

    override fun finish(handle: Long): List<MeetingNativeUpdate> = requireSession(handle).finish()

    override fun setUpdateListener(handle: Long, listener: ((MeetingNativeUpdate) -> Unit)?) {
        sessions[handle]?.setUpdateListener(listener)
    }

    override fun requestCancel(handle: Long) {
        sessions[handle]?.requestCancel()
    }

    override fun voiceProgress(handle: Long): MeetingVoiceProgress =
        sessions[handle]?.voiceProgressSnapshot ?: MeetingVoiceProgress.EMPTY

    override fun close(handle: Long) {
        val session = sessions.remove(handle) ?: return
        session.close()
    }

    private fun requireSession(handle: Long): HybridSession =
        sessions[handle] ?: throw IllegalStateException("Meeting handle is not active")

    private inner class HybridSession(
        private val handle: Long,
        private val diarPath: String,
        private val handy: HandyAsrPort,
    ) {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val asrLock = Any()
        private val callbackDepth = ThreadLocal.withInitial { 0 }
        private val diarQueue = ArrayDeque<ByteArray>()
        private val assembler = MeetingHandyTranscriptAssembler(
            maxRetainedAudioMs = MAX_REVISED_AUDIO_MS,
            maxRetainedChunks = MAX_REVISED_CHUNKS,
        )

        @Volatile
        var voiceProgressSnapshot = MeetingVoiceProgress(MeetingVoiceState.PREPARING, 0L)
            private set

        private var listener: ((MeetingNativeUpdate) -> Unit)? = null
        private var activeCallbacks = 0
        @Volatile
        private var acceptedAsrBytes = 0L
        private var admittedDiarBytes = 0L
        private var processedDiarBytes = 0L
        private var stableSpeakerThroughMs = 0L
        private var queuedDiarBytes = 0
        private var inFlightDiarBytes = 0
        private var diarState = MeetingVoiceState.PREPARING
        private var unavailableReason: MeetingVoiceUnavailableReason? = null
        private var cancelRequested = false
        private var finishRequested = false
        private var closing = false
        private var bridgeClosed = false
        private var workerDone = false
        private var handyFinished = false
        private var lastDiarSnapshotAudioMs = 0L
        private var diarCloseFailure: Throwable? = null
        private var diarWorker: Thread? = null

        fun startDiarizationWorker() {
            val worker = workerFactory(Runnable { runDiarizationWorker() }, "meeting-diar-$handle")
            lock.withLock {
                check(!bridgeClosed) { "Meeting bridge is closed" }
                diarWorker = worker
            }
            worker.start()
        }

        fun setUpdateListener(value: ((MeetingNativeUpdate) -> Unit)?) {
            lock.withLock {
                if (!closing && !bridgeClosed) listener = value
            }
        }

        fun acceptPcm16(buffer: ByteArray, length: Int): List<MeetingNativeUpdate> {
            require(length in PCM_SAMPLE_BYTES..buffer.size && length % PCM_SAMPLE_BYTES == 0) {
                "length must select complete PCM16 samples"
            }
            val asrUpdates = synchronized(asrLock) {
                if (handyFinished || !beginHandyCall()) return emptyList()
                handy.acceptPcm16(buffer, length)
                acceptedAsrBytes += length.toLong()
                val audioProcessedMs = bytesToAudioMs(acceptedAsrBytes)
                val window = handy.snapshot(assembler.nextSnapshotFirstTokenIndex, MAX_HANDY_TOKEN_WINDOW)
                assembler.update(window, audioProcessedMs, isFinal = false)
            }
            publish(asrUpdates)
            // All asynchronous transcript and voice revisions use the listener to avoid duplicates.
            return emptyList()
        }

        fun awaitCaptureReady() {
            var interrupted = false
            lock.lock()
            try {
                while (diarState == MeetingVoiceState.PREPARING && unavailableReason == null &&
                    !cancelRequested && !closing && !bridgeClosed
                ) {
                    try {
                        changed.await()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (unavailableReason != null && !cancelRequested && !closing && !bridgeClosed) {
                    throw IllegalStateException("Meeting voice model is unavailable")
                }
            } finally {
                lock.unlock()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        fun onPcmCaptured(buffer: ByteArray, length: Int) {
            require(length in PCM_SAMPLE_BYTES..buffer.size && length % PCM_SAMPLE_BYTES == 0) {
                "length must select complete PCM16 samples"
            }
            lock.withLock {
                if (cancelRequested || closing || bridgeClosed || unavailableReason != null) return
                if (diarState != MeetingVoiceState.ACTIVE) {
                    setUnavailableLocked(MeetingVoiceUnavailableReason.PROCESSING_FAILED)
                    changed.signalAll()
                    return
                }
                val backlogBytes = queuedDiarBytes.toLong() + inFlightDiarBytes
                if (backlogBytes + length > maxDiarizationQueueBytes) {
                    setUnavailableLocked(MeetingVoiceUnavailableReason.BACKLOG_LIMIT)
                    changed.signalAll()
                    return
                }
                val copy = try {
                    buffer.copyOf(length)
                } catch (_: Throwable) {
                    setUnavailableLocked(MeetingVoiceUnavailableReason.PROCESSING_FAILED)
                    changed.signalAll()
                    return
                }
                try {
                    diarQueue.addLast(copy)
                } catch (_: Throwable) {
                    setUnavailableLocked(MeetingVoiceUnavailableReason.PROCESSING_FAILED)
                    changed.signalAll()
                    return
                }
                queuedDiarBytes += length
                admittedDiarBytes += length.toLong()
                refreshVoiceProgressLocked()
                changed.signalAll()
            }
        }

        fun finish(): List<MeetingNativeUpdate> {
            val finalUpdates = synchronized(asrLock) {
                if (handyFinished || !beginHandyCall()) return emptyList()
                val window = handy.finish(assembler.nextSnapshotFirstTokenIndex, MAX_HANDY_TOKEN_WINDOW)
                handyFinished = true
                assembler.update(window, bytesToAudioMs(acceptedAsrBytes), isFinal = true)
            }
            // Publish the authoritative final ASR text before waiting for the slower voice worker.
            publish(finalUpdates)
            lock.withLock {
                if (!cancelRequested && !closing && !bridgeClosed && unavailableReason == null &&
                    admittedDiarBytes != acceptedAsrBytes
                ) {
                    setUnavailableLocked(MeetingVoiceUnavailableReason.PROCESSING_FAILED)
                    changed.signalAll()
                } else if (!cancelRequested && !closing && !bridgeClosed && unavailableReason == null) {
                    finishRequested = true
                    changed.signalAll()
                }
            }
            awaitDiarizationFinish()
            return emptyList()
        }

        fun requestCancel() {
            lock.withLock {
                if (cancelRequested || closing || bridgeClosed) return
                cancelRequested = true
                diarQueue.clear()
                queuedDiarBytes = 0
                setUnavailableLocked(MeetingVoiceUnavailableReason.CANCELLED)
                changed.signalAll()
            }
        }

        fun close() {
            val worker = lock.withLock {
                if (bridgeClosed) return
                bridgeClosed = true
                closing = true
                listener = null
                if (!workerDone) {
                    cancelRequested = true
                    diarQueue.clear()
                    queuedDiarBytes = 0
                    if (unavailableReason == null) {
                        setUnavailableLocked(MeetingVoiceUnavailableReason.CANCELLED)
                    }
                }
                changed.signalAll()
                diarWorker
            }
            worker?.joinUninterruptibly()
            awaitCallbacksDrained()

            var closeFailure: Throwable? = null
            try {
                synchronized(asrLock) { handy.close() }
            } catch (failure: Throwable) {
                closeFailure = failure
            }
            diarCloseFailure?.let { failure ->
                val existing = closeFailure
                if (existing == null) closeFailure = failure else existing.addSuppressed(failure)
            }
            if (closeFailure != null) {
                throw MeetingNativeCleanupUncertainException(requireNotNull(closeFailure))
            }
        }

        private fun runDiarizationWorker() {
            var diarization: DiarizationSessionPort? = null
            try {
                if (shouldStopWorker()) return
                try {
                    setDiarWorkerBackgroundPriority()
                } catch (_: Throwable) {
                    // Priority is best-effort; a platform limitation must not disable ASR or diarization.
                }
                diarization = diarizationFactory(diarPath)
                val admitted = lock.withLock {
                    if (cancelRequested || closing || bridgeClosed || unavailableReason != null) {
                        false
                    } else {
                        diarState = MeetingVoiceState.ACTIVE
                        refreshVoiceProgressLocked()
                        changed.signalAll()
                        true
                    }
                }
                if (!admitted) return

                var done = false
                while (!done) {
                    var block: ByteArray? = null
                    var finalize = false
                    val exit = lock.withLock {
                        while (diarQueue.isEmpty() && !finishRequested && !cancelRequested && !closing &&
                            unavailableReason == null
                        ) {
                            changed.await()
                        }
                        when {
                            cancelRequested || closing || bridgeClosed || unavailableReason != null -> true
                            diarQueue.isNotEmpty() -> {
                                block = diarQueue.removeFirst()
                                queuedDiarBytes -= requireNotNull(block).size
                                inFlightDiarBytes = requireNotNull(block).size
                                refreshVoiceProgressLocked()
                                false
                            }
                            finishRequested -> {
                                finalize = true
                                true
                            }
                            else -> true
                        }
                    }
                    if (block != null) {
                        val pcm = requireNotNull(block)
                        diarization.acceptPcm16(pcm, pcm.size)
                        val processedAudioMs = lock.withLock {
                            inFlightDiarBytes = 0
                            processedDiarBytes += pcm.size.toLong()
                            refreshVoiceProgressLocked()
                            bytesToAudioMs(processedDiarBytes)
                        }
                        if (shouldTakeDiarizationSnapshot(processedAudioMs)) {
                            reviseFromDiarization(diarization)
                        }
                        continue
                    }
                    if (exit && finalize) {
                        diarization.finish()
                        val processedAudioMs = lock.withLock { bytesToAudioMs(processedDiarBytes) }
                        reviseFromDiarization(diarization)
                        done = true
                    } else if (exit) {
                        done = true
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                lock.withLock {
                    if (unavailableReason == null) setUnavailableLocked(MeetingVoiceUnavailableReason.PROCESSING_FAILED)
                    changed.signalAll()
                }
            } catch (_: Throwable) {
                lock.withLock {
                    if (unavailableReason == null) {
                        setUnavailableLocked(
                            if (diarization == null) MeetingVoiceUnavailableReason.MODEL_LOAD_FAILED
                            else MeetingVoiceUnavailableReason.PROCESSING_FAILED,
                        )
                    }
                    changed.signalAll()
                }
            } finally {
                try {
                    diarization?.close()
                } catch (failure: Throwable) {
                    diarCloseFailure = failure
                }
                lock.withLock {
                    inFlightDiarBytes = 0
                    if (diarCloseFailure != null && unavailableReason == null) {
                        setUnavailableLocked(MeetingVoiceUnavailableReason.PROCESSING_FAILED)
                    }
                    workerDone = true
                    refreshVoiceProgressLocked()
                    changed.signalAll()
                }
            }
        }

        private fun shouldStopWorker(): Boolean = lock.withLock {
            if (cancelRequested || closing || bridgeClosed) {
                if (unavailableReason == null) setUnavailableLocked(MeetingVoiceUnavailableReason.CANCELLED)
                true
            } else {
                false
            }
        }

        /** Linearizes admission with cancellation without holding the state lock through JNI. */
        private fun beginHandyCall(): Boolean = lock.withLock {
            !cancelRequested && !closing && !bridgeClosed
        }

        private fun shouldTakeDiarizationSnapshot(processedAudioMs: Long): Boolean = lock.withLock {
            if (cancelRequested || closing || bridgeClosed || unavailableReason != null ||
                processedAudioMs - lastDiarSnapshotAudioMs < LIVE_SNAPSHOT_INTERVAL_MS
            ) {
                false
            } else {
                lastDiarSnapshotAudioMs = processedAudioMs
                true
            }
        }

        private fun reviseFromDiarization(diarization: DiarizationSessionPort) {
            val (acceptedAudioMs, capturedAudioMs) = lock.withLock {
                bytesToAudioMs(acceptedAsrBytes) to bytesToAudioMs(admittedDiarBytes)
            }
            val snapshot = latestDiarizationWindow(diarization).capStableThrough(capturedAudioMs)
            val stableThroughMs = (snapshot.stableFrameCount.toDouble() *
                snapshot.secondsPerFrame * MILLIS_PER_SECOND)
                .takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToLong()
                ?: 0L
            lock.withLock {
                if (cancelRequested || closing || bridgeClosed || unavailableReason != null) return
                this.stableSpeakerThroughMs = maxOf(this.stableSpeakerThroughMs, stableThroughMs)
                refreshVoiceProgressLocked()
            }
            publish(assembler.reviseDiarization(snapshot, acceptedAudioMs))
        }

        private fun DiarizationFrameWindow.capStableThrough(audioProcessedMs: Long): DiarizationFrameWindow {
            val frameDurationMs = secondsPerFrame * MILLIS_PER_SECOND
            val acceptedFrameCount = floor(audioProcessedMs / frameDurationMs)
                .toLong()
                .coerceIn(0L, totalFrameCount)
            val cappedStableFrameCount = minOf(stableFrameCount, acceptedFrameCount)
            return if (cappedStableFrameCount == stableFrameCount) this
            else copy(stableFrameCount = cappedStableFrameCount)
        }

        /**
         * Fetches the metadata first, then only the newest retained 120 seconds. A zero-frame
         * native snapshot reports the rolling retained base and absolute frame totals without
         * copying probability rows.
         */
        private fun latestDiarizationWindow(diarization: DiarizationSessionPort): DiarizationFrameWindow {
            val metadata = diarization.snapshot(firstFrameIndex = 0L, maxFrames = 0)
            val frameDurationMs = metadata.secondsPerFrame * MILLIS_PER_SECOND
            val calculatedLimit = MAX_REVISED_AUDIO_MS.toDouble() / frameDurationMs
            val frameLimit = if (calculatedLimit.isFinite()) {
                floor(calculatedLimit).toLong().coerceIn(1L, DiarizationSessionPort.MAX_FRAMES.toLong())
            } else {
                DiarizationSessionPort.MAX_FRAMES.toLong()
            }
            val firstRetainedFrame = maxOf(
                metadata.firstFrameIndex,
                (metadata.totalFrameCount - frameLimit).coerceAtLeast(0L),
            )
            val availableFrames = (metadata.totalFrameCount - firstRetainedFrame).coerceAtLeast(0L)
            val requestedFrames = minOf(frameLimit, availableFrames).toInt()
            return diarization.snapshot(firstRetainedFrame, requestedFrames)
        }

        private fun awaitDiarizationFinish() {
            var interrupted = false
            lock.lock()
            try {
                while (!workerDone && !cancelRequested && !closing && !bridgeClosed && unavailableReason == null) {
                    try {
                        changed.await()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                lock.unlock()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun publish(updates: List<MeetingNativeUpdate>) {
            updates.forEach(::publish)
        }

        private fun publish(update: MeetingNativeUpdate) {
            val callback = lock.withLock {
                if (cancelRequested || closing || bridgeClosed) null
                else listener?.also { activeCallbacks++ }
            } ?: return
            try {
                callbackDepth.set((callbackDepth.get() ?: 0) + 1)
                callback(update)
            } catch (_: Throwable) {
                // Consumer failures must not terminate either native processing path.
            } finally {
                callbackDepth.set(((callbackDepth.get() ?: 1) - 1).coerceAtLeast(0))
                lock.withLock {
                    activeCallbacks--
                    changed.signalAll()
                }
            }
        }

        private fun awaitCallbacksDrained() {
            val callbacksOnThisThread = callbackDepth.get() ?: 0
            var interrupted = false
            lock.lock()
            try {
                while (activeCallbacks > callbacksOnThisThread) {
                    try {
                        changed.await()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                lock.unlock()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun setUnavailableLocked(reason: MeetingVoiceUnavailableReason) {
            if (unavailableReason == null) unavailableReason = reason
            diarState = MeetingVoiceState.UNAVAILABLE
            diarQueue.clear()
            queuedDiarBytes = 0
            refreshVoiceProgressLocked()
        }

        private fun refreshVoiceProgressLocked() {
            val reason = unavailableReason
            val pending = if (reason != null) {
                0L
            } else {
                (bytesToAudioMs(admittedDiarBytes) - stableSpeakerThroughMs)
                    .coerceIn(0L, bytesToAudioMs(admittedDiarBytes))
            }
            voiceProgressSnapshot = MeetingVoiceProgress(
                state = if (reason != null) MeetingVoiceState.UNAVAILABLE else diarState,
                pendingAudioMs = pending,
                unavailableReason = reason,
            )
        }

        private fun Thread.joinUninterruptibly() {
            if (this === Thread.currentThread() || state == Thread.State.NEW) return
            var interrupted = false
            while (isAlive) {
                try {
                    join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private class NativeDiarizationSession(private val native: DiarizationNative) : DiarizationSessionPort {
        override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) = native.acceptPcm16(buffer, lengthBytes)
        override fun snapshot(firstFrameIndex: Long, maxFrames: Int) = native.snapshot(firstFrameIndex, maxFrames)
        override fun finish() = native.finish()
        override fun close() = native.close()
    }

    private companion object {
        const val PCM_SAMPLE_BYTES = 2
        const val PCM_BYTES_PER_MILLISECOND = 32L
        const val MILLIS_PER_SECOND = 1_000.0
        const val LIVE_SNAPSHOT_INTERVAL_MS = 500L
        const val MAX_HANDY_TOKEN_WINDOW = 8_192
        const val MAX_DIARIZATION_BACKLOG_BYTES = 120 * 32_000
        const val MAX_REVISED_AUDIO_MS = 120_000L
        const val MAX_REVISED_CHUNKS = 256

        fun normalizeLanguage(language: String): String = when (language.trim()) {
            "fr", "fr-FR" -> "fr-FR"
            "en", "en-US" -> "en-US"
            else -> throw IllegalArgumentException("Unsupported Handy meeting language: $language")
        }

        fun bytesToAudioMs(bytes: Long): Long = bytes / PCM_BYTES_PER_MILLISECOND
    }
}
