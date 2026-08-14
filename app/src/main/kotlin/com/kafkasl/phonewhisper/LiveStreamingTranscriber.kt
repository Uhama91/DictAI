package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable
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
    private val afterTimeoutCancellationRequested: (() -> Unit)? = null,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val activeSession = AtomicReference<Session?>(null)

    fun start(
        language: DictationLanguage = DictationLanguage.FRENCH,
        onText: (committed: String, tentative: String) -> Unit,
    ): Session {
        check(!closed.get()) { "Streaming recognizer is closed" }
        val session = Session(
            recognizer,
            language.nemotronLanguage,
            language.transcribeCppLanguage,
            onText,
            { activeSession.compareAndSet(it, null) },
            afterTimeoutCancellationRequested,
        )
        check(activeSession.compareAndSet(null, session)) { "A streaming session is already active" }
        if (closed.get()) {
            session.cancel()
            activeSession.compareAndSet(session, null)
            error("Streaming recognizer is closed")
        }
        session.start()
        return session
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeSession.getAndSet(null)?.cancelAndAwait()
        recognizer.close()
    }

    class Session internal constructor(
        private val recognizer: StreamingRecognizer,
        private val nemotronLanguage: String,
        private val transcribeCppLanguage: String,
        private val onText: (committed: String, tentative: String) -> Unit,
        private val onClosed: (Session) -> Unit,
        private val afterTimeoutCancellationRequested: (() -> Unit)?,
    ) {
        private val queue = LinkedBlockingQueue<Command>()
        private val done = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        private val cancellationRequested = AtomicBoolean(false)
        private val finalization = AtomicReference<Finalization?>(null)
        private val acceptedSamples = AtomicLong(0)
        private val decodeCalls = AtomicLong(0)
        private var lastEmitted: Pair<String, String>? = null

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
                val timedOut = publishFinalization(Finalization.Timeout, awaitStartedAt)
                requestCancellation(afterTimeoutCancellationRequested)
                return timedOut
            }
            return finalization.get() ?: Finalization.Failure("finalizer_stopped")
        }

        fun cancel() {
            requestCancellation()
        }

        internal fun cancelAndAwait(timeoutMs: Long = DEFAULT_FINALIZE_TIMEOUT_MS): Boolean {
            requestCancellation()
            return done.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        private fun requestCancellation(afterRequested: (() -> Unit)? = null) {
            cancellationRequested.set(true)
            closed.set(true)
            queue.offer(Command.Cancel)
            afterRequested?.invoke()
        }

        private fun runWorker() {
            var stream: StreamingStream? = null
            try {
                stream = recognizer.createStream(transcribeCppLanguage)
                try {
                    stream.setLanguage(nemotronLanguage)
                } catch (t: Throwable) {
                    LiveStreamingTranscriber.logWarning(
                        "event=stream_language outcome=failure type=${t.javaClass.simpleName}",
                    )
                }

                var running = true
                while (running) {
                    when (val cmd = queue.take()) {
                        is Command.Samples -> {
                            if (cancellationRequested.get()) {
                                publishFinalization(Finalization.Cancelled, System.currentTimeMillis())
                                running = false
                            } else {
                                handleSamples(stream, cmd.samples)
                            }
                        }
                        Command.Finish -> {
                            if (cancellationRequested.get()) {
                                publishFinalization(Finalization.Cancelled, System.currentTimeMillis())
                            } else {
                                finalizeStream(stream)
                            }
                            running = false
                        }
                        Command.Cancel -> {
                            publishFinalization(Finalization.Cancelled, System.currentTimeMillis())
                            running = false
                        }
                    }
                }
            } catch (t: Throwable) {
                publishFinalization(
                    if (cancellationRequested.get()) {
                        Finalization.Cancelled
                    } else {
                        Finalization.Failure("native_${t.javaClass.simpleName}")
                    },
                    System.currentTimeMillis(),
                )
                LiveStreamingTranscriber.logWarning(
                    "event=stream_worker outcome=failure type=${t.javaClass.simpleName}",
                )
            } finally {
                try { stream?.release() } catch (_: Throwable) {}
                done.countDown()
                onClosed(this)
            }
        }

        private fun handleSamples(stream: StreamingStream, samples: FloatArray) {
            if (cancellationRequested.get()) return
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            acceptedSamples.addAndGet(samples.size.toLong())
            if (cancellationRequested.get()) return
            val decoded = drain(stream)
            if (!cancellationRequested.get() && (decoded > 0 || stream.emitsSnapshotOnAccept)) {
                emitLivePreview(stream.snapshot())
            }
        }

        private fun finalizeStream(stream: StreamingStream) {
            val startedAt = System.currentTimeMillis()
            if (cancellationRequested.get()) {
                publishFinalization(Finalization.Cancelled, startedAt)
                return
            }
            if (stream.finalSilenceSamples > 0) {
                val trailingSilence = FloatArray(stream.finalSilenceSamples)
                stream.acceptWaveform(trailingSilence, SAMPLE_RATE_HZ)
                acceptedSamples.addAndGet(trailingSilence.size.toLong())
            }
            if (cancellationRequested.get()) {
                publishFinalization(Finalization.Cancelled, startedAt)
                return
            }
            stream.inputFinished()
            if (cancellationRequested.get()) {
                publishFinalization(Finalization.Cancelled, startedAt)
                return
            }
            drain(stream)
            if (cancellationRequested.get()) {
                publishFinalization(Finalization.Cancelled, startedAt)
                return
            }
            val text = stream.snapshot().full.trim()
            if (cancellationRequested.get()) {
                publishFinalization(Finalization.Cancelled, startedAt)
                return
            }
            val outcome = if (text.isBlank()) {
                Finalization.Empty
            } else {
                Finalization.Success(text)
            }
            publishFinalization(outcome, startedAt)
        }

        /** Atomically returns the terminal outcome that won, including at the timeout boundary. */
        private fun publishFinalization(candidate: Finalization, startedAt: Long): Finalization {
            val publication = if (cancellationRequested.get() && candidate != Finalization.Timeout) {
                Finalization.Cancelled
            } else {
                candidate
            }
            finalization.compareAndSet(null, publication)
            val actual = finalization.get() ?: candidate
            logMetric("finalize", actual.metricName(), startedAt)
            return actual
        }

        private fun drain(stream: StreamingStream): Int {
            var decoded = 0
            while (!cancellationRequested.get() && stream.isReady()) {
                if (cancellationRequested.get()) break
                stream.decode()
                decoded++
                decodeCalls.incrementAndGet()
            }
            return decoded
        }

        private fun emitLivePreview(snapshot: TextSnapshot) {
            if (cancellationRequested.get()) return
            val preview = snapshot.committed to snapshot.tentative
            if (preview == lastEmitted) return
            lastEmitted = preview
            onText(preview.first, preview.second)
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
        data object Cancelled : Finalization()
        data class Failure(val reason: String) : Finalization()
        data object Timeout : Finalization()

        internal fun metricName(): String = when (this) {
            is Success -> "success"
            Empty -> "empty"
            Cancelled -> "cancelled"
            is Failure -> "failure"
            Timeout -> "timeout"
        }
    }

    data class TextSnapshot(
        val full: String,
        val committed: String,
        val tentative: String,
    )

    /** Minimal seam around the native API so finalization behavior remains unit-testable. */
    internal interface StreamingRecognizer : Closeable {
        fun createStream(transcribeCppLanguage: String): StreamingStream
    }

    internal interface StreamingStream {
        /** Sherpa needs the documented end-of-utterance tail; transcribe.cpp does not. */
        val finalSilenceSamples: Int get() = 0
        /** transcribe.cpp returns a usable native snapshot directly from feed(). */
        val emitsSnapshotOnAccept: Boolean get() = false
        fun setLanguage(language: String)
        fun acceptWaveform(samples: FloatArray, sampleRate: Int)
        fun isReady(): Boolean
        fun decode()
        fun snapshot(): TextSnapshot
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
            MODEL_CATALOG.any { it.archive == modelName && it.runtimeType == RuntimeModelType.GGUF } ||
                modelName.contains("sherpa-onnx-nemotron-3.5-asr-streaming", ignoreCase = true)

        fun create(ctx: Context, modelName: String): LiveStreamingTranscriber? {
            if (!supports(modelName)) return null
            val dir = File(ctx.filesDir, "models/$modelName")
            val model = MODEL_CATALOG.firstOrNull { it.archive == modelName }
            if (model == null || !ModelDownloader.isInstalled(ctx, model)) return null
            val layout = ModelStorage.inspectModelDirectory(dir) ?: return null

            return try {
                when (layout.type) {
                    RuntimeModelType.GGUF -> LiveStreamingTranscriber(
                        TranscribeCppStreamingRecognizer(TranscribeCppNative.open(layout.model!!.absolutePath)),
                    )
                    RuntimeModelType.TRANSDUCER -> {
                        val config = detectConfig(layout) ?: return null
                        val recognizer = OnlineRecognizer(assetManager = null, config = config)
                        LiveStreamingTranscriber(SherpaStreamingRecognizer(recognizer))
                    }
                    else -> null
                }?.also { Log.i(TAG, "Loaded streaming model: $modelName") }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load streaming model: ${t.javaClass.simpleName}")
                null
            }
        }

        internal fun forTesting(recognizer: StreamingRecognizer): LiveStreamingTranscriber =
            LiveStreamingTranscriber(recognizer)

        internal fun forTesting(
            recognizer: StreamingRecognizer,
            afterTimeoutCancellationRequested: () -> Unit,
        ): LiveStreamingTranscriber = LiveStreamingTranscriber(
            recognizer,
            afterTimeoutCancellationRequested,
        )

        internal fun forTesting(native: TranscribeCppNative): LiveStreamingTranscriber =
            LiveStreamingTranscriber(TranscribeCppStreamingRecognizer(native))

        private fun detectConfig(layout: ValidatedModelLayout): OnlineRecognizerConfig? {
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
    override fun createStream(transcribeCppLanguage: String): LiveStreamingTranscriber.StreamingStream =
        SherpaStreamingStream(recognizer, recognizer.createStream())

    override fun close() {
        recognizer.release()
    }
}

private class SherpaStreamingStream(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
) : LiveStreamingTranscriber.StreamingStream {
    override val finalSilenceSamples: Int = LiveStreamingTranscriber.FINAL_SILENCE_SAMPLES

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

    override fun snapshot(): LiveStreamingTranscriber.TextSnapshot {
        val text = recognizer.getResult(stream).text
        return LiveStreamingTranscriber.TextSnapshot(full = text, committed = "", tentative = text)
    }

    override fun inputFinished() {
        stream.inputFinished()
    }

    override fun release() {
        stream.release()
    }
}

/** One preloaded transcribe.cpp session. Its native stream is reset after every utterance. */
private class TranscribeCppStreamingRecognizer(
    private val native: TranscribeCppNative,
) : LiveStreamingTranscriber.StreamingRecognizer {
    private val streamActive = AtomicBoolean(false)

    override fun createStream(transcribeCppLanguage: String): LiveStreamingTranscriber.StreamingStream {
        check(streamActive.compareAndSet(false, true)) { "A transcribe.cpp stream is already active" }
        return try {
            native.begin(transcribeCppLanguage)
            TranscribeCppStreamingStream(native) { streamActive.set(false) }
        } catch (t: Throwable) {
            try { native.reset() } catch (_: Throwable) {}
            streamActive.set(false)
            throw t
        }
    }

    override fun close() {
        native.close()
    }
}

private class TranscribeCppStreamingStream(
    private val native: TranscribeCppNative,
    private val onRelease: () -> Unit,
) : LiveStreamingTranscriber.StreamingStream {
    override val emitsSnapshotOnAccept: Boolean = true
    private var latest = native.getText().asStreamingSnapshot()
    private var released = false

    override fun setLanguage(language: String) = Unit // begin() configures transcribe.cpp per stream.

    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        latest = native.feed(samples).asStreamingSnapshot()
    }

    override fun isReady(): Boolean = false

    override fun decode() = Unit

    override fun snapshot(): LiveStreamingTranscriber.TextSnapshot = latest

    override fun inputFinished() {
        latest = native.finish().asStreamingSnapshot()
    }

    override fun release() {
        if (released) return
        released = true
        try {
            native.reset()
        } finally {
            onRelease()
        }
    }
}

private fun TranscribeCppNative.Text.asStreamingSnapshot() = LiveStreamingTranscriber.TextSnapshot(
    full = full,
    committed = committed,
    tentative = tentative,
)
