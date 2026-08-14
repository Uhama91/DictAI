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

        data class Failed(val reason: Failure) : Result()
    }

    fun start(): Result {
        if (bufferSize <= 0) return Result.Failed(Failure.INVALID_BUFFER)

        val recorder = try {
            createRecorder()
        } catch (_: Throwable) {
            return Result.Failed(Failure.CONSTRUCTION_FAILED)
        }

        if (!recorder.isInitialized) {
            cleanup(recorder)
            return Result.Failed(Failure.UNINITIALIZED)
        }

        try {
            recorder.startRecording()
        } catch (_: Throwable) {
            cleanup(recorder)
            return Result.Failed(Failure.START_FAILED)
        }

        when (AudioRecordStartPolicy.decide(bufferSize, recorder.isInitialized, recorder.isRecording)) {
            AudioRecordStartPolicy.Decision.START -> Unit
            AudioRecordStartPolicy.Decision.INVALID_BUFFER -> {
                cleanup(recorder)
                return Result.Failed(Failure.INVALID_BUFFER)
            }
            AudioRecordStartPolicy.Decision.UNINITIALIZED -> {
                cleanup(recorder)
                return Result.Failed(Failure.UNINITIALIZED)
            }
            AudioRecordStartPolicy.Decision.NOT_RECORDING -> {
                cleanup(recorder)
                return Result.Failed(Failure.NOT_RECORDING)
            }
        }

        val session = try {
            openSession()
        } catch (_: Throwable) {
            cleanup(recorder)
            return Result.Failed(Failure.SESSION_FAILED)
        }
        return Result.Started(recorder, session)
    }

    private fun cleanup(recorder: RecordingRecorder) {
        try { recorder.stop() } catch (_: Throwable) {}
        try { recorder.release() } catch (_: Throwable) {}
    }
}
