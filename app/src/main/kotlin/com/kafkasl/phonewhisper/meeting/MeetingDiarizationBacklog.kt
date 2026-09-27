package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bounded, non-blocking capture admission with a worker that transfers audio into the private spool. */
internal class MeetingDiarizationBacklog(
    private val spoolRoot: File?,
    private val spoolCapacityBytes: Long,
    private val memoryCapacityBytes: Int,
    private val ingressCapacityBytes: Int,
    private val segmentTargetBytes: Int = MeetingAudioQueue.DEFAULT_SEGMENT_BYTES,
    private val onBlockTransferredForTest: (() -> Unit)? = null,
    private val beforeQueueOfferForTest: (() -> Unit)? = null,
    private val onFailure: ((MeetingDiarizationBacklogException) -> Unit)? = null,
) : AutoCloseable {
    enum class OfferResult { ACCEPTED, BACKLOG_LIMIT, CLOSED }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val ingress = ArrayDeque<ByteArray>()
    private var ingressBytes = 0
    private var prepared = false
    private var finishRequested = false
    private var cancelled = false
    private var closed = false
    private var failure: MeetingDiarizationBacklogException? = null
    private var audioQueue: MeetingAudioQueue? = null
    private var transferWorker: Thread? = null

    val isFinished: Boolean
        get() = lock.withLock { finishRequested }

    val failureKind: MeetingDiarizationBacklogException.Kind?
        get() = lock.withLock { failure?.kind }

    init {
        require(memoryCapacityBytes > 0) { "memoryCapacityBytes must be positive" }
        require(ingressCapacityBytes > 0) { "ingressCapacityBytes must be positive" }
        require(segmentTargetBytes > 0) { "segmentTargetBytes must be positive" }
        require((spoolRoot == null && spoolCapacityBytes == 0L) ||
            (spoolRoot != null && spoolCapacityBytes > 0L)) {
            "spool capacity and root must be configured together"
        }
    }

    /** Prepares the private spool and its transfer worker before capture is announced as ready. */
    fun prepare() {
        val queue = MeetingAudioQueue(
            capacityBytes = memoryCapacityBytes,
            spoolRoot = spoolRoot,
            spoolCapacityBytes = spoolCapacityBytes,
            segmentTargetBytes = segmentTargetBytes,
        )
        lock.withLock {
            check(!prepared && !closed) { "Meeting diarization backlog is already prepared or closed" }
            audioQueue = queue
        }
        queue.prepareSpool()
        val preparationFailure = queue.spoolPreparationFailure
        if (preparationFailure != null) {
            queue.close()
            val error = MeetingDiarizationBacklogException(
                MeetingDiarizationBacklogException.Kind.STORAGE_ERROR,
                preparationFailure,
            )
            lock.withLock {
                audioQueue = null
                failure = error
                prepared = true
            }
            onFailure?.invoke(error)
            throw error
        }

        val worker = Thread({ transferLoop(queue) }, "meeting-diarization-spool").apply { isDaemon = true }
        lock.withLock {
            check(!closed) { "Meeting diarization backlog is closed" }
            prepared = true
            transferWorker = worker
        }
        worker.start()
    }

    /** Copies a complete PCM block into bounded memory and returns without doing disk I/O. */
    fun offer(buffer: ByteArray, length: Int): OfferResult {
        require(length in 0..buffer.size && length % 2 == 0) { "length must contain complete PCM16 samples" }
        if (length == 0) return lock.withLock {
            if (!prepared || finishRequested || cancelled || closed || failure != null) OfferResult.CLOSED
            else OfferResult.ACCEPTED
        }
        return lock.withLock {
            if (!prepared || finishRequested || cancelled || closed || failure != null) return@withLock OfferResult.CLOSED
            if (length > ingressCapacityBytes - ingressBytes) return@withLock OfferResult.BACKLOG_LIMIT
            val copy = try {
                buffer.copyOf(length)
            } catch (_: Throwable) {
                return@withLock OfferResult.BACKLOG_LIMIT
            }
            ingress.addLast(copy)
            ingressBytes += copy.size
            changed.signalAll()
            OfferResult.ACCEPTED
        }
    }

    /** Drains admitted blocks in order, then closes the downstream reader. */
    fun finishInput() {
        lock.withLock {
            if (cancelled || closed || finishRequested) return
            finishRequested = true
            changed.signalAll()
        }
    }

    /** Discards pending input and wakes both workers without waiting for disk cleanup. */
    fun cancel() {
        val queue = lock.withLock {
            if (cancelled || closed) return
            cancelled = true
            ingress.clear()
            ingressBytes = 0
            changed.signalAll()
            audioQueue
        }
        queue?.cancel()
    }

    fun take(): ByteArray? {
        val queue = lock.withLock {
            check(prepared) { "Meeting diarization backlog is not prepared" }
            if (failure != null || cancelled || closed) return null
            audioQueue
        } ?: return null
        return try {
            queue.take()
        } catch (error: MeetingAudioSpoolException) {
            val wrapped = MeetingDiarizationBacklogException(
                MeetingDiarizationBacklogException.Kind.STORAGE_ERROR,
                error,
            )
            reportFailure(wrapped)
            throw wrapped
        }
    }

    override fun close() {
        val state = lock.withLock {
            if (closed) return
            closed = true
            if (!finishRequested) {
                cancelled = true
                ingress.clear()
                ingressBytes = 0
            }
            changed.signalAll()
            transferWorker to audioQueue
        }
        if (!isFinished) state.second?.cancel()
        state.first?.joinUninterruptibly()
        state.second?.close()
    }

    private fun transferLoop(queue: MeetingAudioQueue) {
        var shouldFinish = false
        try {
            while (true) {
                val block = lock.withLock {
                    while (ingress.isEmpty() && !finishRequested && !cancelled && !closed && failure == null) {
                        changed.await()
                    }
                    when {
                        cancelled || closed || failure != null -> null
                        ingress.isNotEmpty() -> ingress.removeFirst()
                        finishRequested -> {
                            shouldFinish = true
                            null
                        }
                        else -> null
                    }
                }
                if (block == null) break
                beforeQueueOfferForTest?.invoke()
                when (queue.offer(block, block.size)) {
                    MeetingAudioQueue.OfferResult.ACCEPTED -> {
                        releaseIngressBytes(block.size)
                        onBlockTransferredForTest?.invoke()
                    }
                    MeetingAudioQueue.OfferResult.LIMIT_REACHED,
                    MeetingAudioQueue.OfferResult.FULL -> throw MeetingDiarizationBacklogException(
                        MeetingDiarizationBacklogException.Kind.BACKLOG_LIMIT,
                    )
                    MeetingAudioQueue.OfferResult.IO_ERROR -> throw MeetingDiarizationBacklogException(
                        MeetingDiarizationBacklogException.Kind.STORAGE_ERROR,
                        queue.spoolPreparationFailure,
                    )
                    MeetingAudioQueue.OfferResult.CLOSED -> {
                        releaseIngressBytes(block.size)
                        if (!isCancelledOrClosed()) {
                            throw MeetingDiarizationBacklogException(MeetingDiarizationBacklogException.Kind.STORAGE_ERROR)
                        }
                        return
                    }
                }
            }
            if (shouldFinish && !isCancelledOrClosed()) queue.finishInput()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!isCancelledOrClosed()) reportFailure(
                MeetingDiarizationBacklogException(MeetingDiarizationBacklogException.Kind.STORAGE_ERROR, interrupted),
            )
        } catch (error: MeetingDiarizationBacklogException) {
            reportFailure(error)
        } catch (error: Throwable) {
            if (!isCancelledOrClosed()) reportFailure(
                MeetingDiarizationBacklogException(MeetingDiarizationBacklogException.Kind.STORAGE_ERROR, error),
            )
        }
    }

    private fun reportFailure(error: MeetingDiarizationBacklogException) {
        val shouldNotify = lock.withLock {
            if (failure != null || cancelled || closed) false else {
                failure = error
                ingress.clear()
                ingressBytes = 0
                changed.signalAll()
                true
            }
        }
        if (shouldNotify) {
            audioQueue?.cancel()
            onFailure?.invoke(error)
        }
    }

    private fun releaseIngressBytes(bytes: Int) {
        lock.withLock {
            ingressBytes = (ingressBytes - bytes).coerceAtLeast(0)
            changed.signalAll()
        }
    }

    private fun isCancelledOrClosed(): Boolean = lock.withLock { cancelled || closed }

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

internal class MeetingDiarizationBacklogException(
    val kind: Kind,
    cause: Throwable? = null,
) : IOException("Meeting diarization backlog " + kind.name.lowercase(), cause) {
    enum class Kind { STORAGE_ERROR, BACKLOG_LIMIT }
}
