package com.kafkasl.phonewhisper.meeting

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal const val MEETING_AUDIO_UNAVAILABLE_MESSAGE = "La capture audio de la réunion est indisponible."

internal fun interface MeetingAudioRecorderFactory {
    fun create(sampleRateHz: Int, blockBytes: Int): MeetingAudioRecorder
}

/** Narrow seam around AudioRecord so JVM tests never access Android's microphone service. */
internal interface MeetingAudioRecorder {
    val initialized: Boolean
    val recording: Boolean
    fun startRecording(): Unit
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stop(): Unit
    fun release(): Unit
}

/** Android microphone adapter; all recorder access is owned by its capture session. */
internal class MeetingAudioRecord(
    private val readinessCheck: () -> Boolean,
    private val recorderFactory: MeetingAudioRecorderFactory = MeetingAudioRecorderFactory(::createAndroidRecorder),
    private val blockBytes: Int = DEFAULT_BLOCK_BYTES,
) : MeetingMicrophoneFactoryPort {
    init {
        require(blockBytes in 2..MAX_BLOCK_BYTES && blockBytes % 2 == 0)
    }

    override fun create(): MeetingMicrophonePort = CaptureSession()

    private inner class CaptureSession : MeetingMicrophonePort {
        private val lock = Any()
        private val stopCompletion = CompletableFuture<Unit>()
        private val failureDelivered = AtomicBoolean()

        private var state = State.NEW
        private var stopRequested = false
        private var stopWorkerStarted = false
        private var recorder: MeetingAudioRecorder? = null
        private var reader: Thread? = null
        private var readerExited = true
        private var releaseStarted = false
        private var releaseFinished = false
        private var releaseFailed = false
        private var terminal = false
        private var onPcm16: ((ByteArray, Int) -> Boolean)? = null
        private var onFailure: ((String) -> Unit)? = null

        override fun start(
            onPcm16: (ByteArray, Int) -> Boolean,
            onFailure: (String) -> Unit,
        ): Boolean {
            val accepted = synchronized(lock) {
                if (state != State.NEW) false
                else {
                    state = State.STARTING
                    this.onPcm16 = onPcm16
                    this.onFailure = onFailure
                    true
                }
            }
            if (!accepted) return false

            if (startWasStopped()) {
                finishStartWithoutRecorder()
                return false
            }
            if (!isReady()) {
                finishStartWithoutRecorder()
                return false
            }
            if (startWasStopped()) {
                finishStartWithoutRecorder()
                return false
            }

            val created = try {
                recorderFactory.create(SAMPLE_RATE_HZ, blockBytes)
            } catch (_: Throwable) {
                finishStartWithoutRecorder()
                return false
            }
            val stoppedDuringCreate = synchronized(lock) {
                recorder = created
                state != State.STARTING || stopRequested
            }
            if (stoppedDuringCreate) {
                rollbackStart(created, stopIfRecording = false)
                return false
            }

            val initialized = try {
                created.initialized
            } catch (_: Throwable) {
                false
            }
            if (!initialized) {
                rollbackStart(created, stopIfRecording = false)
                return false
            }

            if (!isReady() || startWasStopped()) {
                rollbackStart(created, stopIfRecording = false)
                return false
            }

            try {
                created.startRecording()
            } catch (_: Throwable) {
                rollbackStart(created, stopIfRecording = true)
                return false
            }
            val recording = try {
                created.recording
            } catch (_: Throwable) {
                false
            }
            if (!recording) {
                rollbackStart(created, stopIfRecording = false)
                return false
            }

            val readThread = try {
                Thread({ readLoop(created) }, "MeetingAudioRecord-reader").apply { isDaemon = true }
            } catch (_: Throwable) {
                rollbackStart(created, stopIfRecording = true)
                return false
            }
            val launched = synchronized(lock) {
                if (state != State.STARTING || stopRequested) {
                    false
                } else {
                    reader = readThread
                    readerExited = false
                    state = State.RUNNING
                    try {
                        readThread.start()
                        true
                    } catch (_: Throwable) {
                        reader = null
                        readerExited = true
                        state = State.STOPPING
                        false
                    }
                }
            }
            if (!launched) {
                rollbackStart(created, stopIfRecording = true)
                return false
            }
            return true
        }

        override fun stopAndJoin(): CompletableFuture<Unit> {
            var stopTarget: Pair<MeetingAudioRecorder, Thread>? = null
            val shouldFinishWithoutRecorder = synchronized(lock) {
                when (state) {
                    State.TERMINATED -> return stopCompletion
                    State.NEW -> {
                        stopRequested = true
                        state = State.STOPPING
                        releaseFinished = true
                        true
                    }
                    State.STARTING -> {
                        stopRequested = true
                        state = State.STOPPING
                        false
                    }
                    State.RUNNING -> {
                        stopRequested = true
                        state = State.STOPPING
                        val activeRecorder = recorder
                        val activeReader = reader
                        if (!stopWorkerStarted && activeRecorder != null && activeReader != null) {
                            stopWorkerStarted = true
                            stopTarget = activeRecorder to activeReader
                        }
                        false
                    }
                    State.STOPPING -> false
                }
            }

            if (shouldFinishWithoutRecorder) {
                finishTerminalIfClean()
            } else {
                stopTarget?.let { (activeRecorder, activeReader) -> startStopWorker(activeRecorder, activeReader) }
            }
            return stopCompletion
        }

        private fun readLoop(activeRecorder: MeetingAudioRecorder) {
            val readBuffer = ByteArray(blockBytes)
            var failure: String? = null
            try {
                while (!isStopRequested()) {
                    val count = activeRecorder.read(readBuffer, 0, readBuffer.size)
                    if (isStopRequested()) break
                    when {
                        count == 0 -> Thread.yield()
                        count < 0 || count > readBuffer.size || count % 2 != 0 -> {
                            failure = MEETING_AUDIO_UNAVAILABLE_MESSAGE
                            break
                        }
                        else -> {
                            val callback = synchronized(lock) { onPcm16 }
                            val accepted = try {
                                callback?.invoke(readBuffer.copyOf(count), count) == true
                            } catch (_: Throwable) {
                                false
                            }
                            if (!accepted) {
                                failure = MEETING_AUDIO_UNAVAILABLE_MESSAGE
                                break
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                if (!isStopRequested()) failure = MEETING_AUDIO_UNAVAILABLE_MESSAGE
            } finally {
                val readerOwnsCleanup = synchronized(lock) {
                    if (!stopRequested) {
                        stopRequested = true
                        state = State.STOPPING
                    }
                    if (stopWorkerStarted) false else {
                        stopWorkerStarted = true
                        true
                    }
                }
                val stopFailed = readerOwnsCleanup && stopRecorder(activeRecorder)
                if (failure != null || stopFailed) deliverFailure(MEETING_AUDIO_UNAVAILABLE_MESSAGE)
                if (readerOwnsCleanup) releaseRecorder(activeRecorder)
                synchronized(lock) { readerExited = true }
                finishTerminalIfClean()
            }
        }

        private fun startStopWorker(activeRecorder: MeetingAudioRecorder, activeReader: Thread) {
            val cleanup = Runnable {
                val stopFailed = stopRecorder(activeRecorder)
                if (stopFailed) deliverFailure(MEETING_AUDIO_UNAVAILABLE_MESSAGE)
                joinReader(activeReader)
                releaseRecorder(activeRecorder)
                finishTerminalIfClean()
            }
            try {
                Thread(cleanup, "MeetingAudioRecord-stop").apply { isDaemon = true }.start()
            } catch (_: Throwable) {
                try {
                    Thread({
                        val stopFailed = stopRecorder(activeRecorder)
                        if (stopFailed) deliverFailure(MEETING_AUDIO_UNAVAILABLE_MESSAGE)
                        joinReader(activeReader)
                        releaseRecorder(activeRecorder)
                        finishTerminalIfClean()
                    }, "MeetingAudioRecord-stop-fallback").apply { isDaemon = true }.start()
                } catch (_: Throwable) {
                    // No caller-thread native cleanup: the owner reports the unconfirmed close.
                    stopCompletion.completeExceptionally(IllegalStateException(MEETING_AUDIO_UNAVAILABLE_MESSAGE))
                }
            }
        }

        private fun joinReader(activeReader: Thread) {
            if (Thread.currentThread() === activeReader) return
            var interrupted = false
            while (activeReader.isAlive) {
                try {
                    activeReader.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        private fun rollbackStart(activeRecorder: MeetingAudioRecorder, stopIfRecording: Boolean) {
            synchronized(lock) {
                readerExited = true
                state = State.STOPPING
                stopRequested = true
            }
            if (stopIfRecording) stopRecorder(activeRecorder)
            releaseRecorder(activeRecorder)
            finishTerminalIfClean()
        }

        private fun finishStartWithoutRecorder() {
            synchronized(lock) {
                stopRequested = true
                state = State.STOPPING
                readerExited = true
                releaseFinished = true
            }
            finishTerminalIfClean()
        }

        private fun stopRecorder(activeRecorder: MeetingAudioRecorder): Boolean = try {
            if (activeRecorder.recording) activeRecorder.stop()
            false
        } catch (_: Throwable) {
            true
        }

        private fun releaseRecorder(activeRecorder: MeetingAudioRecorder) {
            val shouldRelease = synchronized(lock) {
                if (releaseStarted) false else {
                    releaseStarted = true
                    true
                }
            }
            if (!shouldRelease) return

            val failed = try {
                activeRecorder.release()
                false
            } catch (_: Throwable) {
                true
            }
            synchronized(lock) {
                releaseFailed = releaseFailed || failed
                releaseFinished = true
            }
            finishTerminalIfClean()
        }

        private fun finishTerminalIfClean() {
            val outcome = synchronized(lock) {
                if (terminal || !readerExited || !releaseFinished) return
                terminal = true
                state = State.TERMINATED
                onPcm16 = null
                onFailure = null
                releaseFailed
            }
            if (outcome) stopCompletion.completeExceptionally(IllegalStateException(MEETING_AUDIO_UNAVAILABLE_MESSAGE))
            else stopCompletion.complete(Unit)
        }

        private fun isReady(): Boolean = try {
            readinessCheck()
        } catch (_: Throwable) {
            false
        }

        private fun startWasStopped(): Boolean = synchronized(lock) {
            state != State.STARTING || stopRequested
        }

        private fun isStopRequested(): Boolean = synchronized(lock) { stopRequested }

        private fun deliverFailure(message: String) {
            if (!failureDelivered.compareAndSet(false, true)) return
            val callback = synchronized(lock) { onFailure }
            try {
                callback?.invoke(message)
            } catch (_: Throwable) {
                // The capture failure is already terminal; listener failures cannot change it.
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_BLOCK_BYTES = 3_200
        const val MAX_BLOCK_BYTES = 8_192

        fun createAndroidRecorder(sampleRateHz: Int, blockBytes: Int): MeetingAudioRecorder {
            val minBufferBytes = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBufferBytes > 0) { MEETING_AUDIO_UNAVAILABLE_MESSAGE }
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferBytes, blockBytes * 2),
            )
            return object : MeetingAudioRecorder {
                override val initialized: Boolean
                    get() = record.state == AudioRecord.STATE_INITIALIZED
                override val recording: Boolean
                    get() = record.recordingState == AudioRecord.RECORDSTATE_RECORDING

                override fun startRecording() = record.startRecording()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    record.read(buffer, offset, length, AudioRecord.READ_BLOCKING)
                override fun stop() = record.stop()
                override fun release() = record.release()
            }
        }
    }

    private enum class State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        TERMINATED,
    }
}
