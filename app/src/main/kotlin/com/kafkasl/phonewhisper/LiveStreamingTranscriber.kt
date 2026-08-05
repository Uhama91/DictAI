package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Live local ASR path for streaming transducer models such as Nemotron 3.5.
 * The AudioRecord thread only enqueues frames; decoding runs on a worker.
 */
class LiveStreamingTranscriber private constructor(
    private val recognizer: OnlineRecognizer,
    private val language: String,
) {
    fun start(onText: (committed: String, tentative: String) -> Unit): Session =
        Session(recognizer, language, onText).also { it.start() }

    class Session(
        private val recognizer: OnlineRecognizer,
        private val language: String,
        private val onText: (committed: String, tentative: String) -> Unit,
    ) {
        private val queue = LinkedBlockingQueue<Command>()
        private val done = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        private val finalText = AtomicReference<String?>(null)
        private val lock = Any()
        private val committed = StringBuilder()
        private var tentative = ""
        private var lastEmitted = ""

        fun start() {
            thread(name = "dictai-live-asr") { runWorker() }
        }

        fun acceptPcm16(buf: ByteArray, n: Int) {
            if (closed.get() || n <= 1) return
            val copy = buf.copyOf(n)
            queue.offer(Command.Samples(TranscriptionEngine.pcm16ToFloat(copy)))
        }

        fun finish(timeoutMs: Long = 15_000): String? {
            if (!closed.compareAndSet(false, true)) return currentText().ifBlank { null }
            queue.offer(Command.Finish)
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Live stream finalize timeout")
                queue.offer(Command.Cancel)
                return currentText().ifBlank { null }
            }
            return finalText.get()?.trim()?.ifBlank { null }
        }

        fun cancel() {
            if (closed.compareAndSet(false, true)) {
                queue.offer(Command.Cancel)
            }
        }

        private fun runWorker() {
            var stream: OnlineStream? = null
            try {
                stream = recognizer.createStream()
                try {
                    stream.setOption("language", language)
                } catch (t: Throwable) {
                    Log.w(TAG, "Cannot set streaming language '$language': ${t.javaClass.simpleName}")
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
                Log.e(TAG, "Live streaming failed: ${t.javaClass.simpleName}: ${t.message}")
                finalText.set(currentText().ifBlank { null })
            } finally {
                try { stream?.release() } catch (_: Throwable) {}
                done.countDown()
            }
        }

        private fun handleSamples(stream: OnlineStream, samples: FloatArray) {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            var decoded = false
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                decoded = true
            }
            if (decoded) {
                val text = recognizer.getResult(stream).text.trim()
                updateTentative(text)
            }
            if (recognizer.isEndpoint(stream)) {
                val text = recognizer.getResult(stream).text.trim()
                appendCommitted(text)
                recognizer.reset(stream)
            }
        }

        private fun finalizeStream(stream: OnlineStream) {
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            appendCommitted(recognizer.getResult(stream).text.trim())
            finalText.set(currentText().ifBlank { null })
        }

        private fun updateTentative(text: String) {
            val snapshot = synchronized(lock) {
                tentative = text
                snapshotLocked()
            }
            emitIfChanged(snapshot)
        }

        private fun appendCommitted(text: String) {
            val snapshot = synchronized(lock) {
                if (text.isNotBlank()) {
                    if (committed.isNotEmpty() && !committed.endsWith(" ")) committed.append(' ')
                    committed.append(text)
                }
                tentative = ""
                snapshotLocked()
            }
            emitIfChanged(snapshot, force = true)
        }

        private fun currentText(): String = synchronized(lock) { snapshotLocked().first }

        private fun snapshotLocked(): Pair<String, String> {
            val committedText = committed.toString().trim()
            val full = listOf(committedText, tentative.trim())
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
            return full to tentative.trim()
        }

        private fun emitIfChanged(snapshot: Pair<String, String>, force: Boolean = false) {
            val full = snapshot.first
            if (!force && full == lastEmitted) return
            lastEmitted = full
            val stable = synchronized(lock) { committed.toString().trim() }
            onText(stable, snapshot.second)
        }
    }

    private sealed class Command {
        data class Samples(val samples: FloatArray) : Command()
        object Finish : Command()
        object Cancel : Command()
    }

    companion object {
        private const val TAG = "LiveStreamingTranscriber"
        private const val SAMPLE_RATE = 16000

        fun supports(modelName: String): Boolean =
            modelName.contains("nemotron-3.5-asr-streaming", ignoreCase = true)

        fun create(ctx: Context, modelName: String): LiveStreamingTranscriber? {
            if (!supports(modelName)) return null
            val dir = File(ctx.filesDir, "models/$modelName")
            if (!dir.exists()) return null
            val config = detectConfig(dir) ?: return null

            return try {
                val recognizer = OnlineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded streaming model: $modelName")
                LiveStreamingTranscriber(recognizer, language = "fr")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load streaming model: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }

        private fun detectConfig(dir: File): OnlineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = "$p/tokens.txt"
            val encoder = findFile(p, "encoder") ?: return null
            val decoder = findFile(p, "decoder") ?: return null
            val joiner = findFile(p, "joiner") ?: return null
            if (!File(tokens).exists()) return null

            return OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner,
                    ),
                    tokens = tokens,
                    numThreads = 2,
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.0f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 18.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
        }

        private fun findFile(dir: String, prefix: String): String? {
            val d = File(dir)
            d.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.contains("int8") }
                ?.let { return it.absolutePath }
            return d.listFiles()?.firstOrNull {
                it.name.startsWith(prefix) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.absolutePath
        }
    }
}
