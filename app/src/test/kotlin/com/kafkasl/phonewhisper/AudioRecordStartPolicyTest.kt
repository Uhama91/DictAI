package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecordStartPolicyTest {
    @Test
    fun `positive buffer initialized recorder and recording state may start`() {
        assertEquals(
            AudioRecordStartPolicy.Decision.START,
            AudioRecordStartPolicy.decide(
                bufferSize = 1,
                isInitialized = true,
                isRecording = true,
            ),
        )
    }

    @Test
    fun `zero or negative buffer is rejected`() {
        listOf(0, -1).forEach { bufferSize ->
            assertEquals(
                AudioRecordStartPolicy.Decision.INVALID_BUFFER,
                AudioRecordStartPolicy.decide(
                    bufferSize = bufferSize,
                    isInitialized = true,
                    isRecording = true,
                ),
            )
        }
    }

    @Test
    fun `uninitialized recorder is rejected`() {
        assertEquals(
            AudioRecordStartPolicy.Decision.UNINITIALIZED,
            AudioRecordStartPolicy.decide(
                bufferSize = 1,
                isInitialized = false,
                isRecording = true,
            ),
        )
    }

    @Test
    fun `recorder not recording after start is rejected`() {
        assertEquals(
            AudioRecordStartPolicy.Decision.NOT_RECORDING,
            AudioRecordStartPolicy.decide(
                bufferSize = 1,
                isInitialized = true,
                isRecording = false,
            ),
        )
    }
}
