package com.kafkasl.phonewhisper

import android.content.Context
import java.io.Closeable

internal enum class DictationAsrMode {
    BATCH,
    STREAMING,
}

internal interface DictationAsrEngine : Closeable {
    val modelName: String
    val mode: DictationAsrMode

    fun start(
        language: DictationLanguage,
        onPreview: (committed: String, tentative: String) -> Unit,
    ): DictationAsrSession
}

internal interface DictationAsrSession {
    fun acceptPcm16(buffer: ByteArray, length: Int)
    fun finish(fullPcm: ByteArray): TranscriptionEngine.Result
    fun cancel()
    fun cancelAndAwait(): Boolean
}

internal object DictationAsrEngineFactory {
    fun create(
        modelName: String,
        batchLoader: (String) -> DictationAsrEngine?,
        streamingLoader: (String) -> DictationAsrEngine?,
    ): DictationAsrEngine? = if (LiveStreamingTranscriber.supports(modelName)) {
        streamingLoader(modelName)
    } else {
        batchLoader(modelName)
    }

    fun create(
        context: Context,
        modelName: String = TranscriptionEngine.selectedModelName(context),
    ): DictationAsrEngine? = create(
        modelName = modelName,
        batchLoader = { selectedModel ->
            TranscriptionEngine.loadLocal(context, selectedModel)?.let { local ->
                BatchAsrEngine(
                    modelName = selectedModel,
                    port = object : BatchAsrPort {
                        override fun transcribe(fullPcm: ByteArray): TranscriptionEngine.Result =
                            TranscriptionEngine.transcribe(context, fullPcm, local)

                        override fun close() = local.close()
                    },
                )
            }
        },
        streamingLoader = { selectedModel ->
            TranscriptionEngine.loadStreamingLocal(context, selectedModel)?.let { streaming ->
                StreamingAsrEngine(selectedModel, streaming)
            }
        },
    )
}

internal interface BatchAsrPort : Closeable {
    fun transcribe(fullPcm: ByteArray): TranscriptionEngine.Result
}

internal class BatchAsrEngine internal constructor(
    override val modelName: String,
    private val port: BatchAsrPort,
) : DictationAsrEngine {
    override val mode: DictationAsrMode = DictationAsrMode.BATCH

    override fun start(
        language: DictationLanguage,
        onPreview: (String, String) -> Unit,
    ): DictationAsrSession = BatchAsrSession(port)

    override fun close() = port.close()

    companion object {
        internal fun forTesting(
            modelName: String,
            transcribe: (ByteArray) -> TranscriptionEngine.Result,
        ): BatchAsrEngine = BatchAsrEngine(
            modelName = modelName,
            port = object : BatchAsrPort {
                override fun transcribe(fullPcm: ByteArray): TranscriptionEngine.Result =
                    transcribe(fullPcm)

                override fun close() = Unit
            },
        )
    }
}

private class BatchAsrSession(
    private val port: BatchAsrPort,
) : DictationAsrSession {
    override fun acceptPcm16(buffer: ByteArray, length: Int) = Unit

    override fun finish(fullPcm: ByteArray): TranscriptionEngine.Result = port.transcribe(fullPcm)

    override fun cancel() = Unit

    override fun cancelAndAwait(): Boolean = true
}

internal class StreamingAsrEngine internal constructor(
    override val modelName: String,
    private val transcriber: LiveStreamingTranscriber,
    private val sessionTimeoutMs: Long = LiveStreamingTranscriber.DEFAULT_FINALIZE_TIMEOUT_MS,
) : DictationAsrEngine {
    override val mode: DictationAsrMode = DictationAsrMode.STREAMING

    override fun start(
        language: DictationLanguage,
        onPreview: (String, String) -> Unit,
    ): DictationAsrSession = StreamingAsrSession(
        nativeSession = transcriber.start(language, onPreview),
        timeoutMs = sessionTimeoutMs,
    )

    override fun close() = transcriber.close()

    companion object {
        internal fun forTesting(
            modelName: String,
            transcriber: LiveStreamingTranscriber,
            timeoutMs: Long = LiveStreamingTranscriber.DEFAULT_FINALIZE_TIMEOUT_MS,
        ): StreamingAsrEngine = StreamingAsrEngine(modelName, transcriber, timeoutMs)
    }
}

private class StreamingAsrSession(
    private val nativeSession: LiveStreamingTranscriber.Session,
    private val timeoutMs: Long,
) : DictationAsrSession {
    override fun acceptPcm16(buffer: ByteArray, length: Int) {
        nativeSession.acceptPcm16(buffer, length)
    }

    override fun finish(fullPcm: ByteArray): TranscriptionEngine.Result =
        mapStreamingFinalization(nativeSession.finish(timeoutMs))

    override fun cancel() {
        nativeSession.cancel()
    }

    override fun cancelAndAwait(): Boolean = nativeSession.cancelAndAwait(timeoutMs)
}

internal fun mapStreamingFinalization(
    finalization: LiveStreamingTranscriber.Finalization,
): TranscriptionEngine.Result = when (finalization) {
    is LiveStreamingTranscriber.Finalization.Success ->
        TranscriptionEngine.Result(finalization.text)
    LiveStreamingTranscriber.Finalization.Empty ->
        TranscriptionEngine.Result(null)
    LiveStreamingTranscriber.Finalization.Timeout ->
        TranscriptionEngine.Result(null, "Transcription locale expirée.")
    is LiveStreamingTranscriber.Finalization.Failure ->
        TranscriptionEngine.Result(null, "Transcription locale indisponible.")
}
