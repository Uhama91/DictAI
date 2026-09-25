package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MeetingNativeTest {
    @Test
    fun publicFacadeConstructionDoesNotLoadNativeLibrary() {
        JniMeetingNative()
    }

    @Test
    fun jniFacadeDefersNativeLoadingUntilFirstOperation() {
        val native = FakeMeetingNativeCalls()
        val bridge = JniMeetingNative(native)

        assertEquals(0, native.operationCount)
        assertEquals(41L, bridge.open("asr.gguf", "diar.gguf", "fr"))
        assertEquals(1, native.operationCount)
    }

    @Test
    fun acceptsPcm16AndReturnsNativeUpdates() {
        val expected = listOf(
            MeetingNativeUpdate(
                utteranceId = 1,
                revision = 2,
                words = listOf(MeetingWord("Bonjour", 0, 420, 1)),
                transcript = "Bonjour.",
                isFinal = true,
                stableSpeakerThroughMs = 500,
                audioProcessedMs = 640,
            ),
        )
        val native = FakeMeetingNativeCalls().apply { acceptedUpdates = expected }
        val bridge = JniMeetingNative(native)
        val pcm = byteArrayOf(0, 1, 2, 3)

        assertEquals(expected, bridge.acceptPcm16(41, pcm, pcm.size))
        assertEquals(pcm.toList(), native.lastPcm?.toList())
        assertEquals(2, bridge.finish(41).first().revision)
        bridge.close(41)
        assertEquals(3, native.operationCount)
    }

    @Test
    fun rejectsInvalidHandlesAndPcmBoundsBeforeCallingNative() {
        val native = FakeMeetingNativeCalls()
        val bridge = JniMeetingNative(native)

        assertThrows(IllegalArgumentException::class.java) {
            bridge.acceptPcm16(0, byteArrayOf(0, 0), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.acceptPcm16(41, byteArrayOf(0, 0, 0), 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.acceptPcm16(41, byteArrayOf(0, 0), 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.acceptPcm16(41, ByteArray(320_002), 320_002)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.open("", "diar.gguf", "fr")
        }
        assertEquals(0, native.operationCount)
    }

    @Test
    fun closeWithNonPositiveHandleIsIdempotentAndDoesNotLoadNativeLibrary() {
        val native = FakeMeetingNativeCalls()
        val bridge = JniMeetingNative(native)

        bridge.close(0)
        bridge.close(-1)

        assertEquals(0, native.operationCount)
    }

    private class FakeMeetingNativeCalls : MeetingNativeCalls {
        var operationCount = 0
        var lastPcm: ByteArray? = null
        var acceptedUpdates: List<MeetingNativeUpdate> = emptyList()

        override fun open(asrPath: String, diarPath: String, language: String): Long {
            operationCount++
            return 41
        }

        override fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int) =
            acceptedUpdates.also {
                operationCount++
                lastPcm = buffer.copyOf(length)
            }

        override fun finish(handle: Long) = acceptedUpdates.also { operationCount++ }

        override fun close(handle: Long) {
            operationCount++
        }
    }
}
