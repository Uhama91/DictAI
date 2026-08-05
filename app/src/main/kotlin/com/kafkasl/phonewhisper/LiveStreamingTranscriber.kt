package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Live local ASR path for streaming transducer models such as Nemotron 3.5.
 * The AudioRecord thread only enqueues frames; decoding runs on a worker.
 */
class LiveStreamingTranscriber private constructor(
    private val recognizer: StreamingRecognizer,
    private val language: String,
) {
    fun start(onText: (committed: String, tentative: String) -> Unit): Session =
        Session(recognizer, language, onText).also { it.start() }

    class Session internal constructor(
        private val recognizer: StreamingRecognizer,
        private val language: String,
        private val onText: (committed: String, tentative: String) -> Unit,
    ) {
        private val queue = LinkedBlockingQueue<Command>()
        private val done = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        private val finalization = AtomicReference<Finalization?>(null)
        private val acceptedSamples = AtomicLong(0)
        private val decodeCalls = AtomicLong(0)
        private var lastEmitted = ""

        fun start() {
            thread(name = "dictai-live-asr") { runWorker() }
        }

        fun acceptPcm16(buf: ByteArray, n: Int) {
            if (closed.get() || n <= 1) return
            val copy = buf.copyOf(n)
            queue.offer(Command.Samples(TranscriptionEngine.pcm16ToFloat(copy)))
        }

        /** Finishes the manually-delimited utterance without ever returning a live preview as final text. */
        fun finish(timeoutMs: Long = DEFAULT_FINALIZE_TIMEOUT_MS): Finalization {
            if (!closed.compareAndSet(false, true)) {
                return finalization.get() ?: Finalization.Failure("session_already_closed")
            }
            queue.offer(Command.Finish)
            val awaitStartedAt = System.currentTimeMillis()
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                queue.offer(Command.Cancel)
                return publishFinalization(Finalization.Timeout, awaitStartedAt)
            }
            return finalization.get() ?: Finalization.Failure("finalizer_stopped")
        }

        fun cancel() {
            if (closed.compareAndSet(false, true)) {
                queue.offer(Command.Cancel)
            }
        }

        private fun runWorker() {
            var stream: StreamingStream? = null
            try {
                stream = recognizer.createStream()
                try {
                    stream.setLanguage(language)
                } catch (t: Throwable) {
                    LiveStreamingTranscriber.logWarning(
                        "event=stream_language outcome=failure type=${t.javaClass.simpleName}",
                    )
                }

                var running = true
                while (running) {
                    when (val cmd = queue.take()) {
                        is Command.Samples -> handleSamples(stream, cmd.samples)
                        Command.Finish -> {
                            finalizeStream(stream)
                            running = false
                        }
                        Command.Cancel -> running = false
                    }
                }
            } catch (t: Throwable) {
                publishFinalization(
                    Finalization.Failure("native_${t.javaClass.simpleName}"),
                    System.currentTimeMillis(),
                )
                LiveStreamingTranscriber.logWarning(
                    "event=stream_worker outcome=failure type=${t.javaClass.simpleName}",
                )
            } finally {
                try { stream?.release() } catch (_: Throwable) {}
                done.countDown()
            }
        }

        private fun handleSamples(stream: StreamingStream, samples: FloatArray) {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            acceptedSamples.addAndGet(samples.size.toLong())
            if (drain(stream) > 0) emitLivePreview(stream.resultText().trim())
        }

        private fun finalizeStream(stream: StreamingStream) {
            val startedAt = System.currentTimeMillis()
            val trailingSilence = FloatArray(FINAL_SILENCE_SAMPLES)
            stream.acceptWaveform(trailingSilence, SAMPLE_RATE_HZ)
            acceptedSamples.addAndGet(trailingSilence.size.toLong())
            stream.inputFinished()
            drain(stream)
            val text = stream.resultText().trim()
            val outcome = if (text.isBlank()) {
                Finalization.Empty
            } else {
                Finalization.Success(text)
            }
            publishFinalization(outcome, startedAt)
        }

        /** Atomically returns the terminal outcome that won, including at the timeout boundary. */
        private fun publishFinalization(candidate: Finalization, startedAt: Long): Finalization {
            finalization.compareAndSet(null, candidate)
            val actual = finalization.get() ?: candidate
            logMetric("finalize", actual.metricName(), startedAt)
            return actual
        }

        private fun drain(stream: StreamingStream): Int {
            var decoded = 0
            while (stream.isReady()) {
                stream.decode()
                decoded++
                decodeCalls.incrementAndGet()
            }
            return decoded
        }

        private fun emitLivePreview(text: String) {
            if (text == lastEmitted) return
            lastEmitted = text
            // Nemotron revises its own live output. There is no committed prefix until finalization.
            onText("", text)
        }

        private fun logMetric(event: String, outcome: String, startedAt: Long) {
            LiveStreamingTranscriber.logMetric(
                "event=stream_$event outcome=$outcome acceptedSamples=${acceptedSamples.get()} " +
                    "decodeCalls=${decodeCalls.get()} elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
        }
    }

    sealed class Finalization {
        data class Success(val text: String) : Finalization()
        data object Empty : Finalization()
        data class Failure(val reason: String) : Finalization()
        data object Timeout : Finalization()

        internal fun metricName(): String = when (this) {
            is Success -> "success"
            Empty -> "empty"
            is Failure -> "failure"
            Timeout -> "timeout"
        }
    }

    /** Minimal seam around the native API so finalization behavior remains unit-testable. */
    internal interface StreamingRecognizer {
        fun createStream(): StreamingStream
    }

    internal interface StreamingStream {
        fun setLanguage(language: String)
        fun acceptWaveform(samples: FloatArray, sampleRate: Int)
        fun isReady(): Boolean
        fun decode()
        fun resultText(): String
        fun inputFinished()
        fun release()
    }

    private sealed class Command {
        data class Samples(val samples: FloatArray) : Command()
        object Finish : Command()
        object Cancel : Command()
    }

    companion object {
        private const val TAG = "LiveStreamingTranscriber"
        const val SAMPLE_RATE_HZ = 16000
        const val NEMOTRON_FEATURE_DIM = 80
        const val FINAL_SILENCE_MS = 400
        const val FINAL_SILENCE_SAMPLES = SAMPLE_RATE_HZ * FINAL_SILENCE_MS / 1000
        const val DEFAULT_FINALIZE_TIMEOUT_MS = 30_000L

        private fun logMetric(message: String) {
            try { Log.i(TAG, message) } catch (_: Throwable) {}
        }

        private fun logWarning(message: String) {
            try { Log.w(TAG, message) } catch (_: Throwable) {}
        }

        fun supports(modelName: String): Boolean =
            modelName.contains("nemotron-3.5-asr-streaming", ignoreCase = true)

        fun create(ctx: Context, modelName: String): LiveStreamingTranscriber? {
            if (!supports(modelName)) return null
            val dir = File(ctx.filesDir, "models/$modelName")
            val model = MODEL_CATALOG.firstOrNull { it.archive == modelName }
            if (model == null || !ModelDownloader.isInstalled(ctx, model)) return null
            val config = detectConfig(dir) ?: return null

            return try {
                val recognizer = OnlineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded streaming model: $modelName")
                LiveStreamingTranscriber(SherpaStreamingRecognizer(recognizer), language = "fr")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load streaming model: ${t.javaClass.simpleName}")
                null
            }
        }

        internal fun forTesting(recognizer: StreamingRecognizer): LiveStreamingTranscriber =
            LiveStreamingTranscriber(recognizer, language = "fr")

        private fun detectConfig(dir: File): OnlineRecognizerConfig? {
            val layout = ModelStorage.inspectModelDirectory(dir) ?: return null
            if (layout.type != RuntimeModelType.TRANSDUCER) return null

            return OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = NEMOTRON_FEATURE_DIM),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = layout.encoder!!.absolutePath,
                        decoder = layout.decoder!!.absolutePath,
                        joiner = layout.joiner!!.absolutePath,
                    ),
                    tokens = layout.tokens.absolutePath,
                    numThreads = 2,
                ),
                enableEndpoint = false,
                decodingMethod = "greedy_search",
            )
        }

    }
}

private class SherpaStreamingRecognizer(
    private val recognizer: OnlineRecognizer,
) : LiveStreamingTranscriber.StreamingRecognizer {
    override fun createStream(): LiveStreamingTranscriber.StreamingStream =
        SherpaStreamingStream(recognizer, recognizer.createStream())
}

private class SherpaStreamingStream(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
) : LiveStreamingTranscriber.StreamingStream {
    override fun setLanguage(language: String) {
        stream.setOption("language", language)
    }

    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        stream.acceptWaveform(samples, sampleRate)
    }

    override fun isReady(): Boolean = recognizer.isReady(stream)

    override fun decode() {
        recognizer.decode(stream)
    }

    override fun resultText(): String = recognizer.getResult(stream).text

    override fun inputFinished() {
        stream.inputFinished()
    }

    override fun release() {
        stream.release()
    }
}
