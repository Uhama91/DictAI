package com.kafkasl.phonewhisper

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationAsrEngineTest {
    @Test
    fun `streaming model invokes only streaming loader`() {
        var batchCalls = 0
        var streamingCalls = 0
        val streaming = FakeEngine("sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-int8", DictationAsrMode.STREAMING)

        val selected = DictationAsrEngineFactory.create(
            modelName = streaming.modelName,
            batchLoader = {
                batchCalls++
                FakeEngine(it, DictationAsrMode.BATCH)
            },
            streamingLoader = {
                streamingCalls++
                streaming
            },
        )

        assertSame(streaming, selected)
        assertEquals(0, batchCalls)
        assertEquals(1, streamingCalls)
    }

    @Test
    fun `batch model invokes only batch loader`() {
        var batchCalls = 0
        var streamingCalls = 0
        val batch = FakeEngine("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", DictationAsrMode.BATCH)

        val selected = DictationAsrEngineFactory.create(
            modelName = batch.modelName,
            batchLoader = {
                batchCalls++
                batch
            },
            streamingLoader = {
                streamingCalls++
                FakeEngine(it, DictationAsrMode.STREAMING)
            },
        )

        assertSame(batch, selected)
        assertEquals(1, batchCalls)
        assertEquals(0, streamingCalls)
    }

    @Test
    fun `missing selected backend returns null`() {
        var batchCalls = 0
        var streamingCalls = 0

        val selected = DictationAsrEngineFactory.create(
            modelName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            batchLoader = {
                batchCalls++
                null
            },
            streamingLoader = {
                streamingCalls++
                FakeEngine(it, DictationAsrMode.STREAMING)
            },
        )

        assertNull(selected)
        assertEquals(1, batchCalls)
        assertEquals(0, streamingCalls)
    }

    @Test
    fun `batch finish delegates the complete PCM once`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        var calls = 0
        var received: ByteArray? = null
        val engine = BatchAsrEngine.forTesting(
            modelName = "batch",
            transcribe = { fullPcm ->
                calls++
                received = fullPcm.copyOf()
                TranscriptionEngine.Result("final")
            },
        )
        val session = engine.start(DictationLanguage.FRENCH) { _, _ -> }

        session.acceptPcm16(byteArrayOf(9, 9), 2)
        assertEquals(TranscriptionEngine.Result("final"), session.finish(pcm))

        assertEquals(1, calls)
        assertArrayEquals(pcm, received)
        engine.close()
    }

    @Test
    fun `streaming success maps text`() {
        val engine = streamingEngine(text = "bonjour monde")
        val session = engine.start(DictationLanguage.FRENCH) { _, _ -> }

        assertEquals(
            TranscriptionEngine.Result("bonjour monde"),
            session.finish(ByteArray(0)),
        )
        engine.close()
    }

    @Test
    fun `streaming empty maps no text and no error`() {
        val engine = streamingEngine(text = "   ")
        val session = engine.start(DictationLanguage.FRENCH) { _, _ -> }

        assertEquals(TranscriptionEngine.Result(null), session.finish(ByteArray(0)))
        engine.close()
    }

    @Test
    fun `streaming timeout maps expiration error`() {
        val streamCreated = CountDownLatch(1)
        val unblockFinish = CountDownLatch(1)
        val engine = streamingEngine(
            text = "bonjour",
            timeoutMs = 10,
            streamCreated = streamCreated,
            blockFinish = unblockFinish,
        )
        val session = engine.start(DictationLanguage.FRENCH) { _, _ -> }
        assertTrue(streamCreated.await(1, TimeUnit.SECONDS))

        assertEquals(
            TranscriptionEngine.Result(null, "Transcription locale expirée."),
            session.finish(ByteArray(0)),
        )

        unblockFinish.countDown()
        assertTrue(session.cancelAndAwait())
        engine.close()
    }

    @Test
    fun `streaming failure maps unavailable error`() {
        val engine = streamingEngine(failure = IllegalStateException("native failure"))
        val session = engine.start(DictationLanguage.FRENCH) { _, _ -> }

        assertEquals(
            TranscriptionEngine.Result(null, "Transcription locale indisponible."),
            session.finish(ByteArray(0)),
        )
        engine.close()
    }

    @Test
    fun `streaming cancel is delegated`() {
        val released = CountDownLatch(1)
        val engine = streamingEngine(onReleased = { released.countDown() })
        val session = engine.start(DictationLanguage.FRENCH) { _, _ -> }

        session.cancel()

        assertTrue(released.await(1, TimeUnit.SECONDS))
        engine.close()
    }

    @Test
    fun `streaming cancel and await is delegated with its result`() {
        val engine = streamingEngine()
        val session = engine.start(DictationLanguage.FRENCH) { _, _ -> }

        assertTrue(session.cancelAndAwait())
        engine.close()
    }

    private fun streamingEngine(
        text: String = "",
        failure: Throwable? = null,
        timeoutMs: Long = 1_000,
        streamCreated: CountDownLatch? = null,
        blockFinish: CountDownLatch? = null,
        onReleased: () -> Unit = {},
    ): StreamingAsrEngine = StreamingAsrEngine.forTesting(
        modelName = "streaming",
        transcriber = LiveStreamingTranscriber.forTesting(
            ScriptedStreamingRecognizer(
                text = text,
                failure = failure,
                streamCreated = streamCreated,
                blockFinish = blockFinish,
                onReleased = onReleased,
            ),
        ),
        timeoutMs = timeoutMs,
    )

    private class ScriptedStreamingRecognizer(
        private val text: String,
        private val failure: Throwable?,
        private val streamCreated: CountDownLatch?,
        private val blockFinish: CountDownLatch?,
        private val onReleased: () -> Unit,
    ) : LiveStreamingTranscriber.StreamingRecognizer {
        override fun createStream(transcribeCppLanguage: String): LiveStreamingTranscriber.StreamingStream {
            failure?.let { throw it }
            streamCreated?.countDown()
            return object : LiveStreamingTranscriber.StreamingStream {
                override fun setLanguage(language: String) = Unit
                override fun acceptWaveform(samples: FloatArray, sampleRate: Int) = Unit
                override fun isReady(): Boolean = false
                override fun decode() = Unit
                override fun snapshot() = LiveStreamingTranscriber.TextSnapshot(text, text, "")
                override fun inputFinished() {
                    blockFinish?.await()
                }
                override fun release() = onReleased()
            }
        }

        override fun close() = Unit
    }

    private class FakeEngine(
        override val modelName: String,
        override val mode: DictationAsrMode,
    ) : DictationAsrEngine, Closeable {
        override fun start(
            language: DictationLanguage,
            onPreview: (String, String) -> Unit,
        ): DictationAsrSession = error("not needed for factory selection")

        override fun close() = Unit
    }
}
