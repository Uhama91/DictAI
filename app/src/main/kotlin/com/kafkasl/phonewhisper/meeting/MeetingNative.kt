package com.kafkasl.phonewhisper.meeting

/** Native transcript revision without the run id owned by the Kotlin worker. */
data class MeetingNativeUpdate(
    val utteranceId: Long,
    val revision: Long,
    val words: List<MeetingWord>,
    val transcript: String,
    val isFinal: Boolean,
    val stableSpeakerThroughMs: Long,
    val audioProcessedMs: Long,
)

/** Synchronous boundary used by the single meeting worker. */
interface MeetingNativeBridge {
    /** Loads both models and warms the stream; call from the meeting worker, never the UI thread. */
    fun open(asrPath: String, diarPath: String, language: String): Long

    /** Accepts mono 16 kHz PCM16 little-endian bytes; [length] is the number of valid bytes. */
    fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate>

    fun finish(handle: Long): List<MeetingNativeUpdate>

    fun close(handle: Long)
}

internal interface MeetingNativeCalls {
    fun open(asrPath: String, diarPath: String, language: String): Long
    fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate>
    fun finish(handle: Long): List<MeetingNativeUpdate>
    fun close(handle: Long)
}

/** Loads the dedicated meeting binary on first use, never on class import. */
class JniMeetingNative internal constructor(
    private val native: MeetingNativeCalls,
) : MeetingNativeBridge {
    constructor() : this(LoadedMeetingNativeCalls())

    override fun open(asrPath: String, diarPath: String, language: String): Long {
        require(asrPath.isNotBlank()) { "ASR model path is required" }
        require(diarPath.isNotBlank()) { "Diarization model path is required" }
        require(language.isNotBlank()) { "Meeting language is required" }
        return native.open(asrPath, diarPath, language)
    }

    override fun acceptPcm16(
        handle: Long,
        buffer: ByteArray,
        length: Int,
    ): List<MeetingNativeUpdate> {
        require(handle > 0) { "Meeting handle is invalid" }
        require(length in 0..minOf(buffer.size, MAX_PCM_BYTES)) {
            "PCM length is outside the accepted bounds"
        }
        require(length % 2 == 0) { "PCM16 byte length must be even" }
        return native.acceptPcm16(handle, buffer, length)
    }

    override fun finish(handle: Long): List<MeetingNativeUpdate> {
        require(handle > 0) { "Meeting handle is invalid" }
        return native.finish(handle)
    }

    override fun close(handle: Long) {
        if (handle > 0) native.close(handle)
    }

    private companion object {
        const val MAX_PCM_BYTES = 320_000
    }
}

/** JNI methods are registered from JNI_OnLoad; this class itself is inert until called. */
internal class LoadedMeetingNativeCalls : MeetingNativeCalls {
    @Volatile
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("dictai_meeting")
            loaded = true
        }
    }

    override fun open(asrPath: String, diarPath: String, language: String): Long {
        ensureLoaded()
        return nativeOpen(asrPath, diarPath, language)
    }

    override fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate> {
        ensureLoaded()
        return nativeAcceptPcm16(handle, buffer, length)
    }

    override fun finish(handle: Long): List<MeetingNativeUpdate> {
        ensureLoaded()
        return nativeFinish(handle)
    }

    override fun close(handle: Long) {
        ensureLoaded()
        nativeClose(handle)
    }

    private external fun nativeOpen(asrPath: String, diarPath: String, language: String): Long
    private external fun nativeAcceptPcm16(
        handle: Long,
        buffer: ByteArray,
        length: Int,
    ): List<MeetingNativeUpdate>
    private external fun nativeFinish(handle: Long): List<MeetingNativeUpdate>
    private external fun nativeClose(handle: Long)
}
