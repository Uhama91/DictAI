package com.kafkasl.phonewhisper.meeting

import com.kafkasl.phonewhisper.TranscribeCppNative
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class HandyMeetingNativeTest {
    @Test
    fun keeps_utf8_transcript_authoritative_and_returns_the_requested_raw_token_window() {
        val bindings = FakeTranscribeBindings()
        val session = TranscribeCppNative.forTesting(handle = 23L, bindings = bindings)
        val native = HandyMeetingNative(session)
        val pcm = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F)

        native.acceptPcm16(pcm, lengthBytes = 2)
        val snapshot = native.snapshot(firstTokenIndex = 2, maxTokens = 2)

        assertSame(pcm, bindings.acceptedPcm)
        assertEquals(2, bindings.acceptedLengthBytes)
        assertEquals(2, bindings.snapshotFirstToken)
        assertEquals(2, bindings.snapshotMaxTokens)
        assertArrayEquals("Café d'accord.".toByteArray(Charsets.UTF_8), snapshot.fullTextUtf8)
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0xA9.toByte(), 0x20), snapshot.tokenBytes)
        assertArrayEquals(intArrayOf(1, 3), snapshot.tokenByteEnds)
        assertArrayEquals(longArrayOf(80, 120), snapshot.tokenStartsMs)
        assertArrayEquals(longArrayOf(120, 160), snapshot.tokenEndsMs)
        assertEquals(5, snapshot.totalTokenCount)
        assertEquals(3, snapshot.committedTokenCount)
    }

    @Test
    fun rejects_invalid_pcm_lengths_and_token_windows_before_native_dispatch() {
        val bindings = FakeTranscribeBindings()
        val native = HandyMeetingNative(
            TranscribeCppNative.forTesting(handle = 24L, bindings = bindings),
        )

        assertThrows(IllegalArgumentException::class.java) {
            native.acceptPcm16(byteArrayOf(1, 2, 3), lengthBytes = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            native.acceptPcm16(byteArrayOf(1, 2), lengthBytes = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            native.snapshot(firstTokenIndex = -1, maxTokens = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            native.snapshot(firstTokenIndex = 0, maxTokens = 8193)
        }

        assertEquals(0, bindings.acceptCalls)
        assertEquals(0, bindings.snapshotCalls)
    }

    @Test
    fun finish_returns_the_final_snapshot_and_close_releases_the_shared_session_once() {
        val bindings = FakeTranscribeBindings()
        val native = HandyMeetingNative(
            TranscribeCppNative.forTesting(handle = 25L, bindings = bindings),
        )

        val finished = native.finish()
        native.close()
        native.close()

        assertArrayEquals("Café d'accord.".toByteArray(Charsets.UTF_8), finished.fullTextUtf8)
        assertEquals(1, bindings.finishCalls)
        assertEquals(listOf(25L), bindings.freedHandles)
        assertThrows(IllegalStateException::class.java) {
            native.acceptPcm16(byteArrayOf(0, 0), lengthBytes = 2)
        }
    }

    @Test
    fun open_exposes_both_begin_and_cleanup_failures_when_native_release_is_uncertain() {
        val beginFailure = IllegalStateException("begin failed")
        val cleanupFailure = IllegalStateException("close failed")
        val bindings = FakeTranscribeBindings(
            beginFailure = beginFailure,
            freeFailure = cleanupFailure,
        )

        val thrown = assertThrows(MeetingNativeCleanupUncertainException::class.java) {
            HandyMeetingNative.openUsingBindings("model.gguf", "fr-FR", bindings)
        }

        assertSame(beginFailure, thrown.cause)
        assertSame(cleanupFailure, thrown.suppressed.single())
        assertEquals(listOf(25L), bindings.freedHandles)
    }

    @Test
    fun open_rethrows_begin_failure_unchanged_when_native_release_succeeds() {
        val beginFailure = IllegalStateException("begin failed")
        val bindings = FakeTranscribeBindings(beginFailure = beginFailure)

        val thrown = assertThrows(IllegalStateException::class.java) {
            HandyMeetingNative.openUsingBindings("model.gguf", "fr-FR", bindings)
        }

        assertSame(beginFailure, thrown)
        assertEquals(listOf(25L), bindings.freedHandles)
    }

    private class FakeTranscribeBindings(
        private val beginFailure: Throwable? = null,
        private val freeFailure: Throwable? = null,
    ) : TranscribeCppNative.Bindings {
        val text = "Café d'accord.".toByteArray(Charsets.UTF_8)
        val finalWindow = HandyTokenWindow(
            fullTextUtf8 = text,
            firstTokenIndex = 0,
            totalTokenCount = 5,
            committedTokenCount = 5,
            tokenBytes = byteArrayOf('C'.code.toByte()),
            tokenByteEnds = intArrayOf(1),
            tokenStartsMs = longArrayOf(0),
            tokenEndsMs = longArrayOf(80),
        )
        var acceptedPcm: ByteArray? = null
        var acceptedLengthBytes = 0
        var acceptCalls = 0
        var snapshotFirstToken = -1
        var snapshotMaxTokens = -1
        var snapshotCalls = 0
        var finishCalls = 0
        val freedHandles = mutableListOf<Long>()

        override fun open(modelPath: String): Long = 25L
        override fun begin(handle: Long, language: String) {
            beginFailure?.let { throw it }
        }
        override fun feed(handle: Long, samples: FloatArray): Array<String> = arrayOf("", "", "")
        override fun getText(handle: Long): Array<String> = arrayOf("", "", "")
        override fun finish(handle: Long): Array<String> = arrayOf("", "", "")
        override fun reset(handle: Long) = Unit
        override fun free(handle: Long) {
            freedHandles += handle
            freeFailure?.let { throw it }
        }

        override fun acceptPcm16(handle: Long, buffer: ByteArray, lengthBytes: Int) {
            acceptedPcm = buffer
            acceptedLengthBytes = lengthBytes
            acceptCalls++
        }

        override fun tokenSnapshot(
            handle: Long,
            firstTokenIndex: Int,
            maxTokens: Int,
        ): HandyTokenWindow {
            snapshotFirstToken = firstTokenIndex
            snapshotMaxTokens = maxTokens
            snapshotCalls++
            return HandyTokenWindow(
                fullTextUtf8 = text,
                firstTokenIndex = firstTokenIndex,
                totalTokenCount = 5,
                committedTokenCount = 3,
                tokenBytes = byteArrayOf(0xC3.toByte(), 0xA9.toByte(), 0x20),
                tokenByteEnds = intArrayOf(1, 3),
                tokenStartsMs = longArrayOf(80, 120),
                tokenEndsMs = longArrayOf(120, 160),
            )
        }

        override fun finishTokenSnapshot(
            handle: Long,
            firstTokenIndex: Int,
            maxTokens: Int,
        ): HandyTokenWindow {
            finishCalls++
            return finalWindow
        }
    }
}
