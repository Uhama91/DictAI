package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecordingStartupTransactionTest {
    @Test
    fun `construction failure does not create a session`() {
        var sessionAttempts = 0
        val result = RecordingStartupTransaction(
            bufferSize = 1,
            createRecorder = { throw IllegalStateException("construction") },
            openSession = {
                sessionAttempts++
                FakeSession()
            },
        ).start()

        assertEquals(
            RecordingStartupTransaction.Failure.CONSTRUCTION_FAILED,
            (result as RecordingStartupTransaction.Result.Failed).reason,
        )
        assertEquals(0, sessionAttempts)
    }

    @Test
    fun `uninitialized recorder is released without creating a session`() {
        val recorder = FakeRecorder(isInitialized = false)
        var sessionAttempts = 0

        val result = transaction(
            recorder = recorder,
            openSession = {
                sessionAttempts++
                FakeSession()
            },
        ).start()

        assertEquals(
            RecordingStartupTransaction.Failure.UNINITIALIZED,
            (result as RecordingStartupTransaction.Result.Failed).reason,
        )
        assertEquals(0, recorder.startCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(0, sessionAttempts)
    }

    @Test
    fun `start exception releases recorder without creating a session`() {
        val recorder = FakeRecorder(startFailure = IllegalStateException("start"))
        var sessionAttempts = 0

        val result = transaction(
            recorder = recorder,
            openSession = {
                sessionAttempts++
                FakeSession()
            },
        ).start()

        assertEquals(
            RecordingStartupTransaction.Failure.START_FAILED,
            (result as RecordingStartupTransaction.Result.Failed).reason,
        )
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(0, sessionAttempts)
    }

    @Test
    fun `non-recording recorder is stopped and released`() {
        val recorder = FakeRecorder(recordingAfterStart = false)
        var sessionAttempts = 0

        val result = transaction(
            recorder = recorder,
            openSession = {
                sessionAttempts++
                FakeSession()
            },
        ).start()

        assertEquals(
            RecordingStartupTransaction.Failure.NOT_RECORDING,
            (result as RecordingStartupTransaction.Result.Failed).reason,
        )
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(0, sessionAttempts)
    }

    @Test
    fun `session creation failure stops and releases recorder`() {
        val recorder = FakeRecorder()

        val result = transaction(
            recorder = recorder,
            openSession = { throw IllegalStateException("session") },
        ).start()

        assertEquals(
            RecordingStartupTransaction.Failure.SESSION_FAILED,
            (result as RecordingStartupTransaction.Result.Failed).reason,
        )
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
    }

    @Test
    fun `successful transaction returns recorder and session without cleanup`() {
        val recorder = FakeRecorder()
        val session = FakeSession()

        val result = transaction(recorder = recorder, openSession = { session }).start()

        val started = result as RecordingStartupTransaction.Result.Started
        assertSame(recorder, started.recorder)
        assertSame(session, started.session)
        assertEquals(0, recorder.stopCalls)
        assertEquals(0, recorder.releaseCalls)
    }

    private fun transaction(
        recorder: FakeRecorder,
        openSession: () -> DictationAsrSession,
    ) = RecordingStartupTransaction(
        bufferSize = 1,
        createRecorder = { recorder },
        openSession = openSession,
    )

    private class FakeRecorder(
        override val isInitialized: Boolean = true,
        private val recordingAfterStart: Boolean = true,
        private val startFailure: Throwable? = null,
    ) : RecordingRecorder {
        var startCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        override var isRecording: Boolean = false

        override fun startRecording() {
            startCalls++
            startFailure?.let { throw it }
            isRecording = recordingAfterStart
        }

        override fun stop() {
            stopCalls++
            isRecording = false
        }

        override fun release() {
            releaseCalls++
        }
    }

    private class FakeSession : DictationAsrSession {
        override fun acceptPcm16(buffer: ByteArray, length: Int) = Unit
        override fun finish(fullPcm: ByteArray): TranscriptionEngine.Result =
            TranscriptionEngine.Result(null)
        override fun cancel() = Unit
        override fun cancelAndAwait(): Boolean = true
    }
}
