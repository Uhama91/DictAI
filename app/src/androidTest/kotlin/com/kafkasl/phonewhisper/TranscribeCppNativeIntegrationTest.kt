package com.kafkasl.phonewhisper

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscribeCppNativeIntegrationTest {
    @Test
    fun transcribes_preloaded_french_wav_through_the_real_native_runtime() {
        assumeTrue(
            "TranscribeCppNative integration test requires an arm64-v8a device",
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDirectory = context.getExternalFilesDir(TEST_DIRECTORY) ?: run {
            assumeTrue("External app storage is unavailable", false)
            return
        }

        val modelFile = File(testDirectory, MODEL_FILE_NAME)
        val wavFile = File(testDirectory, WAV_FILE_NAME)
        assumeTrue(
            "Skipping: preload $MODEL_FILE_NAME and $WAV_FILE_NAME in ${testDirectory.absolutePath}",
            modelFile.isFile && wavFile.isFile,
        )

        val samples = readPcm16Mono16KhzWav(wavFile)
        val audioDurationMs = samples.size * 1_000.0 / SAMPLE_RATE_HZ
        var loadMs: Long? = null
        var inferenceMs: Long? = null
        var memoryAfterOpen: MemorySnapshot? = null
        var peakMemoryBeforeClose: MemorySnapshot? = null
        var finalText: String? = null

        try {
            val loadStartedAt = SystemClock.elapsedRealtime()
            TranscribeCppNative.open(modelFile.absolutePath).use { session ->
                loadMs = SystemClock.elapsedRealtime() - loadStartedAt
                memoryAfterOpen = memorySnapshot()
                peakMemoryBeforeClose = memoryAfterOpen

                val inferenceStartedAt = SystemClock.elapsedRealtime()
                session.begin()
                samples.indices.step(CHUNK_SAMPLES).forEach { offset ->
                    val endExclusive = minOf(offset + CHUNK_SAMPLES, samples.size)
                    session.feed(samples.copyOfRange(offset, endExclusive))
                    peakMemoryBeforeClose = peakMemoryBeforeClose?.maxWith(memorySnapshot())
                }
                val text = session.finish().full.trim()
                peakMemoryBeforeClose = peakMemoryBeforeClose?.maxWith(memorySnapshot())
                inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt
                finalText = text

                assertTrue("Transcription must not be empty", text.isNotEmpty())
                val normalizedText = normalize(text)
                EXPECTED_PHRASES.forEach { expectedPhrase ->
                    assertTrue(
                        "Expected '$expectedPhrase' in transcription: $text",
                        normalizedText.contains(normalize(expectedPhrase)),
                    )
                }
                assertTrue(
                    "Transcription must end with terminal punctuation: $text",
                    text.matches(TERMINAL_PUNCTUATION),
                )
            }
        } finally {
            val memoryAfterClose = memorySnapshot()
            val rtf = inferenceMs?.let { durationMs -> durationMs / audioDurationMs }
            Log.i(
                TAG,
                "loadMs=$loadMs inferenceMs=$inferenceMs audioMs=${format(audioDurationMs)} " +
                    "rtf=${rtf?.let(::format)} pssKbAfterOpen=${memoryAfterOpen?.pssKb} " +
                    "nativeHeapBytesAfterOpen=${memoryAfterOpen?.nativeHeapBytes} " +
                    "peakPssKbBeforeClose=${peakMemoryBeforeClose?.pssKb} " +
                    "peakNativeHeapBytesBeforeClose=${peakMemoryBeforeClose?.nativeHeapBytes} " +
                    "pssKbAfterClose=${memoryAfterClose.pssKb} " +
                    "nativeHeapBytesAfterClose=${memoryAfterClose.nativeHeapBytes}",
            )
            Log.i(TAG, "finalText=${finalText.orEmpty()}")
        }
    }

    private fun readPcm16Mono16KhzWav(wavFile: File): FloatArray {
        val bytes = wavFile.readBytes()
        require(bytes.size >= RIFF_HEADER_SIZE) { "WAV is shorter than its RIFF header" }
        require(fourCc(bytes, RIFF_ID_OFFSET) == RIFF) { "WAV must start with RIFF" }
        require(fourCc(bytes, WAVE_ID_OFFSET) == WAVE) { "RIFF form type must be WAVE" }

        var offset = RIFF_HEADER_SIZE
        var format: WavFormat? = null
        var pcmData: ByteArray? = null
        while (offset < bytes.size) {
            require(offset + CHUNK_HEADER_SIZE <= bytes.size) { "Truncated WAV chunk header at $offset" }
            val chunkId = fourCc(bytes, offset)
            val chunkSize = unsignedIntLE(bytes, offset + CHUNK_ID_SIZE)
            val dataOffset = offset + CHUNK_HEADER_SIZE
            require(chunkSize <= Int.MAX_VALUE) { "WAV chunk $chunkId is too large" }
            val dataEnd = dataOffset.toLong() + chunkSize
            require(dataEnd <= bytes.size) { "Truncated WAV chunk $chunkId" }

            when (chunkId) {
                FMT -> format = parseFormat(bytes, dataOffset, chunkSize.toInt())
                DATA -> if (pcmData == null) {
                    pcmData = bytes.copyOfRange(dataOffset, dataEnd.toInt())
                }
            }

            val paddedChunkEnd = dataEnd + (chunkSize and 1L)
            require(paddedChunkEnd <= bytes.size) { "Truncated padding after WAV chunk $chunkId" }
            offset = paddedChunkEnd.toInt()
        }

        val wavFormat = requireNotNull(format) { "WAV has no fmt chunk" }
        require(wavFormat.audioFormat == PCM_FORMAT) { "WAV must use PCM format 1" }
        require(wavFormat.channelCount == MONO_CHANNEL_COUNT) { "WAV must be mono" }
        require(wavFormat.sampleRateHz == SAMPLE_RATE_HZ) { "WAV must use 16 kHz" }
        require(wavFormat.bitsPerSample == PCM_BITS_PER_SAMPLE) { "WAV must use 16-bit samples" }

        val data = requireNotNull(pcmData) { "WAV has no data chunk" }
        require(data.isNotEmpty()) { "WAV data chunk is empty" }
        require(data.size % PCM_SAMPLE_BYTES == 0) { "WAV PCM data must align to 16-bit samples" }

        return FloatArray(data.size / PCM_SAMPLE_BYTES) { sampleIndex ->
            val byteOffset = sampleIndex * PCM_SAMPLE_BYTES
            signedShortLE(data, byteOffset) / PCM_NORMALIZATION
        }
    }

    private fun parseFormat(bytes: ByteArray, offset: Int, size: Int): WavFormat {
        require(size >= PCM_FORMAT_CHUNK_SIZE) { "WAV fmt chunk is shorter than PCM format fields" }
        return WavFormat(
            audioFormat = unsignedShortLE(bytes, offset + FMT_AUDIO_FORMAT_OFFSET),
            channelCount = unsignedShortLE(bytes, offset + FMT_CHANNEL_COUNT_OFFSET),
            sampleRateHz = unsignedIntLE(bytes, offset + FMT_SAMPLE_RATE_OFFSET).toInt(),
            bitsPerSample = unsignedShortLE(bytes, offset + FMT_BITS_PER_SAMPLE_OFFSET),
        )
    }

    private fun memorySnapshot() = MemorySnapshot(
        pssKb = Debug.getPss(),
        nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
    )

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun fourCc(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, FOUR_CC_SIZE, StandardCharsets.US_ASCII)

    private fun unsignedIntLE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun unsignedShortLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun signedShortLE(bytes: ByteArray, offset: Int): Int =
        unsignedShortLE(bytes, offset).toShort().toInt()

    private data class WavFormat(
        val audioFormat: Int,
        val channelCount: Int,
        val sampleRateHz: Int,
        val bitsPerSample: Int,
    )

    private data class MemorySnapshot(
        val pssKb: Long,
        val nativeHeapBytes: Long,
    ) {
        fun maxWith(other: MemorySnapshot) = MemorySnapshot(
            pssKb = maxOf(pssKb, other.pssKb),
            nativeHeapBytes = maxOf(nativeHeapBytes, other.nativeHeapBytes),
        )
    }

    private companion object {
        const val TAG = "TranscribeCppNativeIT"
        const val TEST_DIRECTORY = "transcribe-test"
        const val MODEL_FILE_NAME = "nemotron-3.5-asr-streaming-0.6b-Q6_K.gguf"
        const val WAV_FILE_NAME = "dictai-fr-test.wav"
        const val SAMPLE_RATE_HZ = 16_000
        const val CHUNK_DURATION_MS = 1_120
        const val CHUNK_SAMPLES = SAMPLE_RATE_HZ * CHUNK_DURATION_MS / 1_000
        const val PCM_FORMAT = 1
        const val MONO_CHANNEL_COUNT = 1
        const val PCM_BITS_PER_SAMPLE = 16
        const val PCM_SAMPLE_BYTES = PCM_BITS_PER_SAMPLE / Byte.SIZE_BITS
        const val PCM_NORMALIZATION = 32_768f
        const val RIFF_HEADER_SIZE = 12
        const val CHUNK_HEADER_SIZE = 8
        const val FOUR_CC_SIZE = 4
        const val CHUNK_ID_SIZE = FOUR_CC_SIZE
        const val RIFF_ID_OFFSET = 0
        const val WAVE_ID_OFFSET = 8
        const val PCM_FORMAT_CHUNK_SIZE = 16
        const val FMT_AUDIO_FORMAT_OFFSET = 0
        const val FMT_CHANNEL_COUNT_OFFSET = 2
        const val FMT_SAMPLE_RATE_OFFSET = 4
        const val FMT_BITS_PER_SAMPLE_OFFSET = 14
        const val RIFF = "RIFF"
        const val WAVE = "WAVE"
        const val FMT = "fmt "
        const val DATA = "data"
        val EXPECTED_PHRASES = listOf(
            "tout fonctionne",
            "transcription est fluide",
            "point à la fin",
        )
        val TERMINAL_PUNCTUATION = Regex(".*[.!?]$")
        val COMBINING_MARKS = Regex("\\p{M}+")
    }
}
