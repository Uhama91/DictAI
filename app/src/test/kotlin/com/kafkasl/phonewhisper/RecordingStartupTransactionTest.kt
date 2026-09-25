package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecordingStartupTransactionTest {
    @Test
    fun `construction failure does not create a session and confirms no recorder release was needed`() {
        var sessionAttempts = 0
        val result = RecordingStartupTransaction(
            bufferSize = 1,
            createRecorder = { throw IllegalStateException("construction") },
            openSession = {
                sessionAttempts++
                FakeSession()
            },
        ).start()

        assertFailed(result, RecordingStartupTransaction.Failure.CONSTRUCTION_FAILED)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(0, sessionAttempts)
    }

    @Test
    fun `invalid buffer confirms no recorder release was needed`() {
        var recorderAttempts = 0
        val result = RecordingStartupTransaction(
            bufferSize = 0,
            createRecorder = {
                recorderAttempts++
                FakeRecorder()
            },
            openSession = { FakeSession() },
        ).start()

        assertFailed(result, RecordingStartupTransaction.Failure.INVALID_BUFFER)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(0, recorderAttempts)
    }

    @Test
    fun `uninitialized recorder is released without creating a session`() {
        val recorder = FakeRecorder(initialized = false)
        var sessionAttempts = 0

        val result = transaction(
            recorder = recorder,
            openSession = {
                sessionAttempts++
                FakeSession()
            },
        ).start()

        assertFailed(result, RecordingStartupTransaction.Failure.UNINITIALIZED)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(0, recorder.startCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(0, sessionAttempts)
    }

    @Test
    fun `initialized getter failure rolls back the constructed recorder`() {
        val recorder = FakeRecorder(initializedFailure = IllegalStateException("initialized"))
        var sessionAttempts = 0

        val result = transaction(
            recorder = recorder,
            openSession = {
                sessionAttempts++
                FakeSession()
            },
        ).start()

        assertFailed(result, RecordingStartupTransaction.Failure.UNINITIALIZED)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(0, recorder.startCalls)
        assertEquals(1, recorder.stopCalls)
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

        assertFailed(result, RecordingStartupTransaction.Failure.START_FAILED)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(0, sessionAttempts)
    }

    @Test
    fun `start failure still reports unconfirmed release when release throws`() {
        val recorder = FakeRecorder(
            startFailure = IllegalStateException("start"),
            releaseFailure = IllegalStateException("release"),
        )

        val result = transaction(recorder = recorder, openSession = { FakeSession() }).start()

        assertFailed(result, RecordingStartupTransaction.Failure.START_FAILED)
        assertReleaseConfirmed(result, expected = false)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
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

        assertFailed(result, RecordingStartupTransaction.Failure.NOT_RECORDING)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(0, sessionAttempts)
    }

    @Test
    fun `stop failure still attempts release and confirms successful release`() {
        val recorder = FakeRecorder(
            recordingAfterStart = false,
            stopFailure = IllegalStateException("stop"),
        )

        val result = transaction(recorder = recorder, openSession = { FakeSession() }).start()

        assertFailed(result, RecordingStartupTransaction.Failure.NOT_RECORDING)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
    }

    @Test
    fun `recording getter failure rolls back instead of escaping startup`() {
        val recorder = FakeRecorder(recordingFailure = IllegalStateException("recording"))
        var sessionAttempts = 0

        val result = transaction(
            recorder = recorder,
            openSession = {
                sessionAttempts++
                FakeSession()
            },
        ).start()

        assertFailed(result, RecordingStartupTransaction.Failure.NOT_RECORDING)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(1, recorder.startCalls)
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

        assertFailed(result, RecordingStartupTransaction.Failure.SESSION_FAILED)
        assertReleaseConfirmed(result, expected = true)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
    }

    @Test
    fun `session failure still reports unconfirmed release when release throws`() {
        val recorder = FakeRecorder(releaseFailure = IllegalStateException("release"))

        val result = transaction(
            recorder = recorder,
            openSession = { throw IllegalStateException("session") },
        ).start()

        assertFailed(result, RecordingStartupTransaction.Failure.SESSION_FAILED)
        assertReleaseConfirmed(result, expected = false)
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

    private fun assertFailed(
        result: RecordingStartupTransaction.Result,
        expectedReason: RecordingStartupTransaction.Failure,
    ) {
        assertEquals(expectedReason, (result as RecordingStartupTransaction.Result.Failed).reason)
    }

    private fun assertReleaseConfirmed(
        result: RecordingStartupTransaction.Result,
        expected: Boolean,
    ) {
        val failed = result as RecordingStartupTransaction.Result.Failed
        val getter = failed.javaClass.methods.firstOrNull {
            it.name == "getRecorderReleaseConfirmed" && it.parameterCount == 0
        }
        assertNotNull("Failed result must expose recorderReleaseConfirmed", getter)
        assertEquals(expected, getter!!.invoke(failed))
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
        private val initialized: Boolean = true,
        private val initializedFailure: Throwable? = null,
        private val recordingAfterStart: Boolean = true,
        private val recordingFailure: Throwable? = null,
        private val startFailure: Throwable? = null,
        private val stopFailure: Throwable? = null,
        private val releaseFailure: Throwable? = null,
    ) : RecordingRecorder {
        var startCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        private var recording = false

        override val isInitialized: Boolean
            get() {
                initializedFailure?.let { throw it }
                return initialized
            }

        override val isRecording: Boolean
            get() {
                recordingFailure?.let { throw it }
                return recording
            }

        override fun startRecording() {
            startCalls++
            startFailure?.let { throw it }
            recording = recordingAfterStart
        }

        override fun stop() {
            stopCalls++
            recording = false
            stopFailure?.let { throw it }
        }

        override fun release() {
            releaseCalls++
            releaseFailure?.let { throw it }
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
