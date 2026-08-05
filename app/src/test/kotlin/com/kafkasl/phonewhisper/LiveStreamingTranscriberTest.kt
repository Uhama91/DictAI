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
}
