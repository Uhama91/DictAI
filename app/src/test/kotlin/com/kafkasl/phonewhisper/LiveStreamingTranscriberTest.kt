package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamingTranscriberTest {
    @Test
    fun supports_only_nemotron_streaming_models() {
        assertTrue(LiveStreamingTranscriber.supports("sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-int8"))
        assertFalse(LiveStreamingTranscriber.supports("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"))
    }

    @Test
    fun nemotron_uses_the_documented_80_feature_dimensions_at_16khz() {
        assertTrue(LiveStreamingTranscriber.NEMOTRON_FEATURE_DIM == 80)
        assertTrue(LiveStreamingTranscriber.SAMPLE_RATE_HZ == 16000)
    }

    @Test
    fun finish_adds_exactly_400ms_of_silence_then_returns_the_complete_single_stream_result() {
        val native = FakeStreamingRecognizer(resultsAfterDecode = listOf("bonjour", "bonjour monde"))
        val previews = mutableListOf<Pair<String, String>>()
        val session = LiveStreamingTranscriber.forTesting(native).start { committed, tentative ->
            previews += committed to tentative
        }

        session.acceptPcm16(byteArrayOf(1, 0, 2, 0), 4)
        val result = session.finish(timeoutMs = 1_000)

        assertEquals(LiveStreamingTranscriber.Finalization.Success("bonjour monde"), result)
        assertEquals(listOf("" to "bonjour"), previews)
        assertEquals(2, native.stream.accepted.size)
        assertEquals(6_400, native.stream.accepted.last().size)
        assertTrue(native.stream.accepted.last().all { it == 0f })
        assertTrue(native.stream.inputFinished)
        assertEquals(2, native.stream.decodeCalls)
    }

    @Test
    fun finalization_failure_never_converts_a_live_preview_into_a_success() {
        val previews = mutableListOf<String>()
        val native = FakeStreamingRecognizer(
            resultsAfterDecode = listOf("aperçu partiel"),
            failOnInputFinished = true,
        )
        val session = LiveStreamingTranscriber.forTesting(native).start { _, tentative ->
            previews += tentative
        }

        session.acceptPcm16(byteArrayOf(1, 0), 2)
        val result = session.finish(timeoutMs = 1_000)

        assertTrue(result is LiveStreamingTranscriber.Finalization.Failure)
        assertFalse(result is LiveStreamingTranscriber.Finalization.Success)
        assertEquals(listOf("aperçu partiel"), previews)
    }

    @Test
    fun finalization_with_a_valid_blank_result_is_empty_not_failure() {
        val native = FakeStreamingRecognizer(resultsAfterDecode = listOf(""))
        val session = LiveStreamingTranscriber.forTesting(native).start { _, _ -> }

        val result = session.finish(timeoutMs = 1_000)

        assertEquals(LiveStreamingTranscriber.Finalization.Empty, result)
    }

    @Test
    fun finalization_timeout_is_structured_and_does_not_return_text() {
        val decoded = CountDownLatch(1)
        val native = FakeStreamingRecognizer(
            resultsAfterDecode = listOf("final tardif"),
            decodeDelayMs = 100,
            decoded = decoded,
        )
        val session = LiveStreamingTranscriber.forTesting(native).start { _, _ -> }

        val result = session.finish(timeoutMs = 1)

        assertEquals(LiveStreamingTranscriber.Finalization.Timeout, result)
        assertTrue(decoded.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun default_finalization_timeout_is_30_seconds() {
        assertEquals(30_000L, LiveStreamingTranscriber.DEFAULT_FINALIZE_TIMEOUT_MS)
    }

    private class FakeStreamingRecognizer(
        private val resultsAfterDecode: List<String>,
        private val failOnInputFinished: Boolean = false,
        private val decodeDelayMs: Long = 0,
        private val decoded: CountDownLatch? = null,
    ) : LiveStreamingTranscriber.StreamingRecognizer {
        val stream = FakeStreamingStream(resultsAfterDecode, failOnInputFinished, decodeDelayMs, decoded)

        override fun createStream(): LiveStreamingTranscriber.StreamingStream = stream
    }

    private class FakeStreamingStream(
        private val resultsAfterDecode: List<String>,
        private val failOnInputFinished: Boolean,
        private val decodeDelayMs: Long,
        private val decoded: CountDownLatch?,
    ) : LiveStreamingTranscriber.StreamingStream {
        val accepted = mutableListOf<FloatArray>()
        var inputFinished = false
        var decodeCalls = 0
        private var readyChecks = 0

        override fun setLanguage(language: String) = Unit

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            accepted += samples
        }

        override fun isReady(): Boolean {
            val ready = readyChecks % 2 == 0 && decodeCalls < resultsAfterDecode.size
            readyChecks++
            return ready
        }

        override fun decode() {
            if (decodeDelayMs > 0) Thread.sleep(decodeDelayMs)
            decodeCalls++
            decoded?.countDown()
        }

        override fun resultText(): String = resultsAfterDecode.getOrElse(decodeCalls - 1) { "" }

        override fun inputFinished() {
            if (failOnInputFinished) error("native failure")
            inputFinished = true
        }

        override fun release() = Unit
    }
}
