package com.kafkasl.phonewhisper

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
}
