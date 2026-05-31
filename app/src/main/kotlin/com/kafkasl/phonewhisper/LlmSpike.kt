package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import io.github.fadizg.kmpai.llm.ChatSession
import io.github.fadizg.kmpai.llm.ChatTemplate
import io.github.fadizg.kmpai.llm.LlmEnvironment
import io.github.fadizg.kmpai.llm.ModelSource
import io.github.fadizg.kmpai.llm.SamplingParams
import kotlinx.coroutines.runBlocking

/**
 * SPIKE (gate) : prouver qu'un LLM local (Qwen3-0.6B GGUF via llama.cpp/kmp-ai) charge et génère
 * une correction FR hors-ligne, en cohabitant avec sherpa-onnx. À retirer une fois le gate validé.
 */
object LlmSpike {
    private const val TAG = "WhisperPin"

    fun run(ctx: Context, log: (String) -> Unit) {
        val rt = Runtime.getRuntime()
        fun memMb() = (rt.totalMemory() - rt.freeMemory()) / 1048576
        try {
            val t0 = System.currentTimeMillis()
            log("LLM spike: démarrage…")
            val env = LlmEnvironment(ctx.applicationContext)
            val source = ModelSource.HuggingFace(
                /* repo */ "unsloth/Qwen3-0.6B-GGUF",
                /* file */ "Qwen3-0.6B-Q4_K_M.gguf",
                /* revision */ "main",
                /* auth */ null
            )
            log("Chargement (1er run = téléchargement ~400 Mo, patiente sur WiFi)…")
            runBlocking {
                env.load(source).use { engine ->
                    val loadMs = System.currentTimeMillis() - t0
                    Log.i(TAG, "SPIKE-LLM loaded in ${loadMs}ms, heap=${memMb()}MB")
                    log("Modèle chargé (${loadMs}ms, heap=${memMb()}MB). Génération…")
                    val chat = ChatSession(
                        engine,
                        ChatTemplate.ChatML,
                        "Tu es un correcteur. Corrige l'orthographe, la grammaire et la ponctuation. " +
                            "Réponds UNIQUEMENT avec le texte corrigé, en français."
                    )
                    val sb = StringBuilder()
                    val tGen = System.currentTimeMillis()
                    chat.send(
                        "salut sa va jvoudré te voir demin a la gare /no_think",
                        SamplingParams(256, 0.7f, 0.8f, 20, 1.5f, null, emptyList())
                    ).collect { token -> sb.append(token.text) }
                    val genMs = System.currentTimeMillis() - tGen
                    val out = sb.toString().trim()
                    val hasThink = out.contains("<think>")
                    Log.i(TAG, "SPIKE-LLM result (${genMs}ms, think=$hasThink, heap=${memMb()}MB): $out")
                    log("OK ${genMs}ms think=$hasThink heap=${memMb()}MB → ${out.take(140)}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SPIKE-LLM échec", t)
            log("ÉCHEC: ${t.javaClass.simpleName} — ${t.message}")
        }
    }
}
