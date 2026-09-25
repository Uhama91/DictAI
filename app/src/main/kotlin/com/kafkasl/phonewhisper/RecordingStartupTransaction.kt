package com.kafkasl.phonewhisper

internal interface RecordingRecorder {
    val isInitialized: Boolean
    val isRecording: Boolean
    fun startRecording()
    fun stop()
    fun release()
}

/** Keeps the recorder and ASR session local until startup completes successfully. */
internal class RecordingStartupTransaction(
    private val bufferSize: Int,
    private val createRecorder: () -> RecordingRecorder,
    private val openSession: () -> DictationAsrSession,
) {
    enum class Failure {
        INVALID_BUFFER,
        CONSTRUCTION_FAILED,
        UNINITIALIZED,
        START_FAILED,
        NOT_RECORDING,
        SESSION_FAILED,
    }

    sealed class Result {
        data class Started(
            val recorder: RecordingRecorder,
            val session: DictationAsrSession,
        ) : Result()

        data class Failed(
            val reason: Failure,
            val recorderReleaseConfirmed: Boolean,
        ) : Result()
    }

    fun start(): Result {
        if (bufferSize <= 0) {
            return Result.Failed(Failure.INVALID_BUFFER, recorderReleaseConfirmed = true)
        }

        val recorder = try {
            createRecorder()
        } catch (_: Throwable) {
            return Result.Failed(Failure.CONSTRUCTION_FAILED, recorderReleaseConfirmed = true)
        }

        val initiallyInitialized = try {
            recorder.isInitialized
        } catch (_: Throwable) {
            return failedAfterCleanup(Failure.UNINITIALIZED, recorder)
        }
        if (!initiallyInitialized) return failedAfterCleanup(Failure.UNINITIALIZED, recorder)

        try {
            recorder.startRecording()
        } catch (_: Throwable) {
            return failedAfterCleanup(Failure.START_FAILED, recorder)
        }

        val initializedAfterStart = try {
            recorder.isInitialized
        } catch (_: Throwable) {
            return failedAfterCleanup(Failure.UNINITIALIZED, recorder)
        }
        if (!initializedAfterStart) return failedAfterCleanup(Failure.UNINITIALIZED, recorder)

        val recordingAfterStart = try {
            recorder.isRecording
        } catch (_: Throwable) {
            return failedAfterCleanup(Failure.NOT_RECORDING, recorder)
        }

        when (AudioRecordStartPolicy.decide(bufferSize, initializedAfterStart, recordingAfterStart)) {
            AudioRecordStartPolicy.Decision.START -> Unit
            AudioRecordStartPolicy.Decision.INVALID_BUFFER -> {
                return failedAfterCleanup(Failure.INVALID_BUFFER, recorder)
            }
            AudioRecordStartPolicy.Decision.UNINITIALIZED -> {
                return failedAfterCleanup(Failure.UNINITIALIZED, recorder)
            }
            AudioRecordStartPolicy.Decision.NOT_RECORDING -> {
                return failedAfterCleanup(Failure.NOT_RECORDING, recorder)
            }
        }

        val session = try {
            openSession()
        } catch (_: Throwable) {
            return failedAfterCleanup(Failure.SESSION_FAILED, recorder)
        }
        return Result.Started(recorder, session)
    }

    private fun failedAfterCleanup(
        reason: Failure,
        recorder: RecordingRecorder,
    ): Result.Failed = Result.Failed(reason, recorderReleaseConfirmed = cleanup(recorder))

    private fun cleanup(recorder: RecordingRecorder): Boolean {
        try { recorder.stop() } catch (_: Throwable) {}
        return try {
            recorder.release()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
