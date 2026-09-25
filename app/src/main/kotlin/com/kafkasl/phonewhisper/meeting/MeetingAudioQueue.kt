package com.kafkasl.phonewhisper.meeting

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MeetingAudioQueue(private val capacityBytes: Int = DEFAULT_CAPACITY_BYTES) {
    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
    }

    enum class OfferResult {
        ACCEPTED,
        CLOSED,
        FULL,
    }

    private val lock = ReentrantLock()
    private val hasData = lock.newCondition()
    private val blocks = ArrayDeque<ByteArray>()
    private var inputFinished = false
    private var cancelled = false
    private var bytesQueued = 0

    val queuedBytes: Int
        get() = lock.withLock { bytesQueued }

    val isCancelled: Boolean
        get() = lock.withLock { cancelled }

    fun offer(buffer: ByteArray, length: Int): OfferResult {
        require(length >= 0) { "length must not be negative" }
        require(length <= buffer.size) { "length must not exceed the buffer size" }
        require(length % 2 == 0) { "PCM16 data length must be even" }

        return lock.withLock {
            if (inputFinished || cancelled) return@withLock OfferResult.CLOSED
            if (length == 0) return@withLock OfferResult.ACCEPTED
            if (length > capacityBytes - bytesQueued) return@withLock OfferResult.FULL

            blocks.addLast(buffer.copyOf(length))
            bytesQueued += length
            hasData.signal()
            OfferResult.ACCEPTED
        }
    }

    fun take(): ByteArray? = lock.withLock {
        while (blocks.isEmpty() && !inputFinished && !cancelled) {
            try {
                hasData.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }

        if (cancelled || blocks.isEmpty()) return@withLock null
        val block = blocks.removeFirst()
        bytesQueued -= block.size
        block
    }

    fun finishInput() {
        lock.withLock {
            inputFinished = true
            hasData.signalAll()
        }
    }

    fun cancel() {
        lock.withLock {
            cancelled = true
            inputFinished = true
            blocks.clear()
            bytesQueued = 0
            hasData.signalAll()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY_BYTES = 320_000
    }
}
