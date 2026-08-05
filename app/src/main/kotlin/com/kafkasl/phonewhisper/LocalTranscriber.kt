package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.Closeable
import java.io.File

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's external files dir.
 */
class LocalTranscriber private constructor(private var recognizer: OfflineRecognizer?) : Closeable {

    /** Transcribe raw PCM float samples. Blocking — call from background thread. */
    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val openRecognizer = checkNotNull(recognizer) { "Local recognizer is closed" }
        val stream = openRecognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            openRecognizer.decode(stream)
            openRecognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun close() {
        val openRecognizer = recognizer ?: return
        recognizer = null
        openRecognizer.release()
    }

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return MODEL_CATALOG.filter { ModelDownloader.isInstalled(ctx, it) }.map { it.archive }
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            val model = MODEL_CATALOG.firstOrNull { it.archive == modelName }
            if (model == null || !ModelDownloader.isInstalled(ctx, model)) {
                Log.e(TAG, "Model dir is invalid: $modelDir")
                return null
            }
            val config = detectModelConfig(modelDir) ?: run {
                Log.e(TAG, "Could not detect model type in $modelDir")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}")
                null
            }
        }

        /** Auto-detect model type from files present in the directory. */
        private fun detectModelConfig(dir: File): OfflineRecognizerConfig? {
            val layout = ModelStorage.inspectModelDirectory(dir) ?: return null
            return when (layout.type) {
                RuntimeModelType.MOONSHINE -> OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = layout.preprocess!!.absolutePath,
                            encoder = layout.encoder!!.absolutePath,
                            uncachedDecoder = layout.uncachedDecoder!!.absolutePath,
                            cachedDecoder = layout.cachedDecoder!!.absolutePath,
                        ),
                        tokens = layout.tokens.absolutePath,
                        numThreads = 2,
                    )
                )
                RuntimeModelType.WHISPER -> OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = layout.encoder!!.absolutePath,
                            decoder = layout.decoder!!.absolutePath,
                        ),
                        tokens = layout.tokens.absolutePath,
                        numThreads = 2,
                        modelType = "whisper",
                    )
                )
                RuntimeModelType.TRANSDUCER -> OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = layout.encoder!!.absolutePath,
                            decoder = layout.decoder!!.absolutePath,
                            joiner = layout.joiner!!.absolutePath,
                        ),
                        tokens = layout.tokens.absolutePath,
                        numThreads = 2,
                        modelType = "nemo_transducer",
                    )
                )
                RuntimeModelType.CTC -> OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = layout.model!!.absolutePath),
                        tokens = layout.tokens.absolutePath,
                        numThreads = 2,
                    )
                )
                // GGUF is handled by the dedicated native runtime, not sherpa-onnx.
                RuntimeModelType.GGUF -> null
            }
        }
    }
}
