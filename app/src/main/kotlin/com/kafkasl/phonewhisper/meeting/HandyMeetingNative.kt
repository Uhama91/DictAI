package com.kafkasl.phonewhisper.meeting

import com.kafkasl.phonewhisper.DictationLanguage
import com.kafkasl.phonewhisper.TranscribeCppNative
import java.io.Closeable

/** A byte-preserving window into the native Handy stream. */
internal data class HandyTokenWindow(
    val fullTextUtf8: ByteArray,
    val firstTokenIndex: Int,
    val totalTokenCount: Int,
    val committedTokenCount: Int,
    val tokenBytes: ByteArray,
    val tokenByteEnds: IntArray,
    val tokenStartsMs: LongArray,
    val tokenEndsMs: LongArray,
) {
    init {
        require(firstTokenIndex >= 0) { "firstTokenIndex must not be negative" }
        require(totalTokenCount >= 0) { "totalTokenCount must not be negative" }
        require(committedTokenCount in 0..totalTokenCount) {
            "committedTokenCount must be within the current token count"
        }
        require(firstTokenIndex <= totalTokenCount) { "firstTokenIndex exceeds totalTokenCount" }
        require(tokenByteEnds.size == tokenStartsMs.size && tokenStartsMs.size == tokenEndsMs.size) {
            "Token byte boundaries and timestamps must have matching lengths"
        }
        require(tokenStartsMs.size <= MAX_TOKEN_WINDOW) { "Token window exceeds $MAX_TOKEN_WINDOW entries" }
        require(firstTokenIndex.toLong() + tokenStartsMs.size <= totalTokenCount) {
            "Token window extends past totalTokenCount"
        }
        var previousByteEnd = 0
        for (byteEnd in tokenByteEnds) {
            require(byteEnd in previousByteEnd..tokenBytes.size) {
                "Token byte boundaries must be ordered and within tokenBytes"
            }
            previousByteEnd = byteEnd
        }
    }

    private companion object {
        const val MAX_TOKEN_WINDOW = 8_192
    }
}

internal interface HandyAsrPort : Closeable {
    fun acceptPcm16(buffer: ByteArray, lengthBytes: Int)

    fun snapshot(firstTokenIndex: Int, maxTokens: Int = 8_192): HandyTokenWindow

    fun finish(firstTokenIndex: Int = 0, maxTokens: Int = 8_192): HandyTokenWindow
}

/** Meeting adapter over the same transcribe.cpp session registry used by Dictée. */
internal class HandyMeetingNative internal constructor(
    private val session: TranscribeCppNative,
) : HandyAsrPort {
    private var finished = false
    private var closed = false

    @Synchronized
    override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) {
        check(!closed) { "Handy meeting session is closed" }
        check(!finished) { "Handy meeting session is already finished" }
        session.acceptPcm16(buffer, lengthBytes)
    }

    @Synchronized
    override fun snapshot(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow {
        check(!closed) { "Handy meeting session is closed" }
        return session.tokenSnapshot(firstTokenIndex, maxTokens)
    }

    @Synchronized
    override fun finish(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow {
        check(!closed) { "Handy meeting session is closed" }
        if (finished) return session.tokenSnapshot(firstTokenIndex, maxTokens)
        val finalWindow = session.finishTokenSnapshot(firstTokenIndex, maxTokens)
        finished = true
        return finalWindow
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    companion object {
        fun open(
            modelPath: String,
            language: String = DictationLanguage.FRENCH.transcribeCppLanguage,
        ): HandyMeetingNative = openWithSession(modelPath, language) {
            TranscribeCppNative.open(modelPath)
        }

        internal fun openUsingBindings(
            modelPath: String,
            language: String,
            bindings: TranscribeCppNative.Bindings,
        ): HandyMeetingNative = openWithSession(modelPath, language) {
            TranscribeCppNative.openUsingBindings(modelPath, bindings)
        }

        private inline fun openWithSession(
            modelPath: String,
            language: String,
            openSession: () -> TranscribeCppNative,
        ): HandyMeetingNative {
            require(modelPath.isNotBlank()) { "modelPath must not be blank" }
            val session = openSession()
            try {
                session.begin(language)
                return HandyMeetingNative(session)
            } catch (beginFailure: Throwable) {
                try {
                    session.close()
                } catch (cleanupFailure: Throwable) {
                    throw MeetingNativeCleanupUncertainException(beginFailure).also {
                        it.addSuppressed(cleanupFailure)
                    }
                }
                throw beginFailure
            }
        }
    }
}
