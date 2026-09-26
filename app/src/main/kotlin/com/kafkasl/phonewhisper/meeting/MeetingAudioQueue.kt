package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** FIFO for PCM blocks with a bounded heap prefix and an optional bounded private disk backlog. */
class MeetingAudioQueue(
    private val capacityBytes: Int = DEFAULT_CAPACITY_BYTES,
    private val spoolRoot: File? = null,
    private val spoolCapacityBytes: Long = if (spoolRoot == null) 0L else MAX_SPOOL_BYTES,
    private val segmentTargetBytes: Int = DEFAULT_SEGMENT_BYTES,
) : AutoCloseable {
    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
        require(segmentTargetBytes > 0) { "segmentTargetBytes must be positive" }
        require(spoolRoot == null || spoolCapacityBytes > 0) { "spoolCapacityBytes must be positive with a spool root" }
    }

    enum class OfferResult {
        ACCEPTED,
        CLOSED,
        FULL,
        LIMIT_REACHED,
        IO_ERROR,
    }

    private class DiskSegment(
        val file: File,
        var writer: RandomAccessFile?,
        var writeOffset: Long = 0,
        var readOffset: Long = 0,
        var sealed: Boolean = false,
    )

    private val lock = ReentrantLock()
    private val wakeConsumer = Semaphore(0)
    private val wakePending = AtomicBoolean(false)
    private val memoryBlocks = ArrayDeque<ByteArray>()
    private val diskSegments = ArrayDeque<DiskSegment>()
    private val cancellationRequested = AtomicBoolean(false)
    private val finishRequested = AtomicBoolean(false)
    private var inputFinished = false
    private var cancelled = false
    private var closed = false
    private var spoolFailed = false
    private var spoolPreparing = false
    private var spoolPreparationFailure: Throwable? = null
    private var spooling = false
    private var bytesQueued = 0
    private var memoryBytes = 0
    private var diskBytesQueued = 0L
    private var activeSegment: DiskSegment? = null
    private var spool: MeetingAudioSpool? = null

    val queuedBytes: Int
        get() = if (cancellationRequested.get()) 0 else lock.withLock { bytesQueued }

    val spooledBytesQueued: Long
        get() = if (cancellationRequested.get()) 0L else lock.withLock { diskBytesQueued }

    val isCancelled: Boolean
        get() = cancellationRequested.get()

    /** Creates the private session lock on the engine worker before audio callbacks are admitted. */
    fun prepareSpool() {
        val root = lock.withLock {
            if (spoolRoot == null || spool != null || spoolPreparing || cancellationRequested.get() || closed) return
            spoolPreparing = true
            spoolRoot
        }
        var preparationFailure: Throwable? = null
        val prepared = try {
            MeetingAudioSpool(root)
        } catch (failure: Throwable) {
            preparationFailure = failure
            null
        }
        val shouldDiscard = lock.withLock {
            spoolPreparing = false
            if (cancellationRequested.get() || closed) {
                true
            } else {
                spool = prepared
                spoolPreparationFailure = preparationFailure
                signalConsumer()
                false
            }
        }
        if (shouldDiscard) {
            try {
                prepared?.close()
            } catch (_: Throwable) {
                // A cancelled session must not wait for best-effort spool cleanup.
            }
        }
    }

    fun offer(buffer: ByteArray, length: Int): OfferResult {
        require(length >= 0) { "length must not be negative" }
        require(length <= buffer.size) { "length must not exceed the buffer size" }
        require(length % 2 == 0) { "PCM16 data length must be even" }

        return lock.withLock {
            if (finishRequested.get() || inputFinished || cancellationRequested.get() || cancelled || closed) {
                return@withLock OfferResult.CLOSED
            }
            if (spoolFailed) return@withLock OfferResult.IO_ERROR
            if (spoolPreparing) return@withLock OfferResult.IO_ERROR
            if (length == 0) return@withLock OfferResult.ACCEPTED

            if (!spooling && length <= capacityBytes - memoryBytes) {
                val copy = buffer.copyOf(length)
                memoryBlocks.addLast(copy)
                memoryBytes += copy.size
                bytesQueued += copy.size
                if (cancellationRequested.get()) {
                    markCancelledLocked()
                    return@withLock OfferResult.CLOSED
                }
                signalConsumer()
                return@withLock OfferResult.ACCEPTED
            }

            if (spoolRoot == null) {
                if (length > capacityBytes - bytesQueued) return@withLock OfferResult.FULL
                val copy = buffer.copyOf(length)
                memoryBlocks.addLast(copy)
                memoryBytes += copy.size
                bytesQueued += copy.size
                if (cancellationRequested.get()) {
                    markCancelledLocked()
                    return@withLock OfferResult.CLOSED
                }
                signalConsumer()
                return@withLock OfferResult.ACCEPTED
            }

            val bytesToSpool = length.toLong() + if (spooling) 0L else memoryBytes.toLong()
            if (diskBytesQueued + bytesToSpool > spoolCapacityBytes) {
                return@withLock OfferResult.LIMIT_REACHED
            }

            try {
                if (!spooling) {
                    spooling = true
                    while (memoryBlocks.isNotEmpty()) {
                        val pending = memoryBlocks.first
                        appendToDiskLocked(pending, pending.size, newAudio = false)
                        memoryBlocks.removeFirst()
                        memoryBytes -= pending.size
                    }
                }
                appendToDiskLocked(buffer, length, newAudio = true)
                if (cancellationRequested.get()) {
                    markCancelledLocked()
                    OfferResult.CLOSED
                } else {
                    signalConsumer()
                    OfferResult.ACCEPTED
                }
            } catch (_: Throwable) {
                spoolFailed = true
                signalConsumer()
                OfferResult.IO_ERROR
            }
        }
    }

    fun take(): ByteArray? {
        while (true) {
            if (cancellationRequested.get()) return null
            var block: ByteArray? = null
            var shouldWait = false
            var ended = false
            lock.withLock {
                if (cancellationRequested.get() || cancelled || closed) {
                    markCancelledLocked()
                    ended = true
                    return@withLock
                }
                if (spoolFailed) throw MeetingAudioSpoolException(IOException("spool storage unavailable"))

                if (memoryBlocks.isNotEmpty()) {
                    block = memoryBlocks.removeFirst()
                    memoryBytes -= requireNotNull(block).size
                    bytesQueued -= requireNotNull(block).size
                    return@withLock
                }

                val segment = diskSegments.firstOrNull()
                if (segment != null && segment.readOffset < segment.writeOffset) {
                    try {
                        block = readRecordLocked(segment)
                        diskBytesQueued -= requireNotNull(block).size
                        bytesQueued -= requireNotNull(block).size
                        deleteExhaustedSegmentsLocked()
                        if (bytesQueued == 0 && diskSegments.isEmpty() && activeSegment != null) {
                            sealActiveSegmentLocked()
                            deleteExhaustedSegmentsLocked()
                        }
                    } catch (failure: Throwable) {
                        spoolFailed = true
                        throw MeetingAudioSpoolException(failure)
                    }
                    if (cancellationRequested.get()) {
                        markCancelledLocked()
                        block = null
                        ended = true
                    }
                    return@withLock
                }

                try {
                    deleteExhaustedSegmentsLocked()
                    if (finishRequested.get()) inputFinished = true
                    if (inputFinished) {
                        sealActiveSegmentLocked()
                        deleteExhaustedSegmentsLocked()
                        if (diskSegments.isEmpty()) {
                            cleanupSpoolLocked()
                            closed = true
                            ended = true
                            return@withLock
                        }
                        return@withLock
                    }
                } catch (failure: Throwable) {
                    spoolFailed = true
                    throw MeetingAudioSpoolException(failure)
                }

                shouldWait = true
            }

            if (block != null) return block
            if (ended) return null
            if (shouldWait) {
                try {
                    wakeConsumer.acquire()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
                wakePending.set(false)
            }
        }
    }

    fun finishInput() {
        if (cancellationRequested.get()) return
        finishRequested.set(true)
        signalConsumer()
    }

    /** Signals the consumer and drops logical backlog; disk deletion is deferred to close(). */
    fun cancel() {
        cancellationRequested.set(true)
        signalConsumer()
        if (!lock.tryLock()) return
        try {
            markCancelledLocked()
        } finally {
            lock.unlock()
        }
    }

    /** Releases segment files and the session lock. Engine sessions call this from their worker's finally block. */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            cancelled = true
            inputFinished = true
            memoryBlocks.clear()
            memoryBytes = 0
            bytesQueued = 0
            diskBytesQueued = 0
            cleanupSpoolLocked()
            signalConsumer()
        }
    }

    private fun appendToDiskLocked(buffer: ByteArray, length: Int, newAudio: Boolean) {
        var segment = activeSegment
        val recordBytes = RECORD_HEADER_BYTES + length.toLong()
        if (segment != null && segment.writeOffset > 0 && segment.writeOffset + recordBytes > segmentTargetBytes) {
            sealActiveSegmentLocked()
            segment = null
        }
        if (segment == null) {
            val activeSpool = spool ?: run {
                spoolPreparationFailure?.let { throw MeetingAudioSpoolException(it) }
                MeetingAudioSpool(requireNotNull(spoolRoot)).also { spool = it }
            }
            val file = activeSpool.createSegment()
            segment = DiskSegment(file, RandomAccessFile(file, "rw"))
            diskSegments.addLast(segment)
            activeSegment = segment
        }

        val writer = requireNotNull(segment.writer)
        writer.seek(segment.writeOffset)
        writer.writeInt(length)
        writer.write(buffer, 0, length)
        segment.writeOffset += recordBytes
        diskBytesQueued += length
        if (newAudio) bytesQueued += length
        if (segment.writeOffset >= segmentTargetBytes) sealActiveSegmentLocked()
    }

    private fun readRecordLocked(segment: DiskSegment): ByteArray {
        val recordStart = segment.readOffset
        val recordLength = RandomAccessFile(segment.file, "r").use { input ->
            input.seek(recordStart)
            val length = input.readInt()
            if (length <= 0 || length % 2 != 0 || recordStart + RECORD_HEADER_BYTES + length > segment.writeOffset) {
                throw IOException("Invalid meeting audio spool record")
            }
            val block = ByteArray(length)
            input.readFully(block)
            segment.readOffset += RECORD_HEADER_BYTES + length
            block
        }
        return recordLength
    }

    private fun sealActiveSegmentLocked() {
        val segment = activeSegment ?: return
        activeSegment = null
        segment.sealed = true
        val writer = segment.writer
        segment.writer = null
        writer?.close()
        deleteExhaustedSegmentsLocked()
    }

    private fun deleteExhaustedSegmentsLocked() {
        while (true) {
            val first = diskSegments.firstOrNull() ?: break
            if (!first.sealed || first.readOffset < first.writeOffset) break
            spool?.deleteSegment(first.file)
            diskSegments.removeFirst()
        }
        if (diskSegments.isEmpty() && activeSegment == null) spooling = false
    }

    private fun markCancelledLocked() {
        cancelled = true
        inputFinished = true
        memoryBlocks.clear()
        memoryBytes = 0
        bytesQueued = 0
        diskBytesQueued = 0
    }

    /** Coalesces state changes into one wake token; the consumer always rechecks the queue. */
    private fun signalConsumer() {
        if (wakePending.compareAndSet(false, true)) wakeConsumer.release()
    }

    private fun cleanupSpoolLocked() {
        diskSegments.forEach { segment ->
            try {
                segment.writer?.close()
            } catch (_: Throwable) {
                // Spool cleanup is best-effort; an orphan is removed on a later session startup.
            }
            segment.writer = null
        }
        diskSegments.clear()
        activeSegment = null
        try {
            spool?.close()
        } catch (_: Throwable) {
            // Never turn a completed native close into a lease failure for cache cleanup alone.
        }
        spool = null
        diskBytesQueued = 0
    }

    companion object {
        const val RECORD_HEADER_BYTES = 4L
        const val DEFAULT_SEGMENT_BYTES = 256 * 1024
        const val DEFAULT_MEMORY_CAPACITY_BYTES = 64 * 1024
        const val DEFAULT_CAPACITY_BYTES = 320_000
        const val MAX_SPOOL_BYTES = 128L * 1024L * 1024L
    }
}
