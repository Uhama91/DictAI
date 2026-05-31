package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import io.github.fadizg.kmpai.llm.ChatSession
import io.github.fadizg.kmpai.llm.ChatTemplate
import io.github.fadizg.kmpai.llm.EngineConfig
import io.github.fadizg.kmpai.llm.LlmEngine
import io.github.fadizg.kmpai.llm.LlmEnvironment
import io.github.fadizg.kmpai.llm.ModelSource
import io.github.fadizg.kmpai.llm.SamplingParams
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Post-traitement LLM 100 % local (Qwen3-0.6B via kmp-ai / llama.cpp). Thread-safe. */
object LlmPostProcessor {
    private const val TAG = "WhisperPin"
    private const val REPO = "unsloth/Qwen3-0.6B-GGUF"
    private const val FILE = "Qwen3-0.6B-Q4_K_M.gguf"
    private const val GEN_TIMEOUT_MS = 25_000L

    private val mutex = Mutex()
    @Volatile private var engine: LlmEngine? = null
    @Volatile private var env: LlmEnvironment? = null
    @Volatile var ready: Boolean = false
        private set

    /**
     * Template ChatML Qwen3 avec le bloc `<think></think>` VIDE pré-rempli → désactive le mode
     * "thinking" (llama.cpp ignore `enable_thinking=false` ; c'est le workaround documenté).
     * Sans ça, Qwen3 génère des centaines de tokens de raisonnement avant la réponse (= très lent).
     */
    private val noThinkTemplate by lazy { ChatTemplate.Custom(
        { msgs ->
            val sb = StringBuilder()
            for (m in msgs) sb.append("<|im_start|>${m.role.name.lowercase()}\n${m.content}<|im_end|>\n")
            sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
            sb.toString()
        },
        listOf("<|im_end|>")
    ) }

    /** Retire un éventuel bloc de raisonnement Qwen3 `<think>...</think>`. */
    fun stripThink(raw: String): String {
        val s = raw.trim()
        if (!s.contains("<think>")) return s
        val close = s.indexOf("</think>")
        return if (close >= 0) s.substring(close + "</think>".length).trim() else ""
    }

    fun isDownloaded(ctx: Context): Boolean =
        ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).getBoolean("llm_downloaded", false)

    /** Charge le modèle (télécharge au 1er appel). Bloquant — hors thread principal. */
    fun ensureLoaded(ctx: Context): Boolean = runBlocking {
        mutex.withLock {
            if (engine != null) { ready = true; return@withLock true }
            try {
                val e = LlmEnvironment(ctx.applicationContext)
                // CPU only (kmp-ai ne bundle pas de backend GPU) ; 4 threads = optimal sur SoC
                // asymétriques (8 threads régressent), contexte court suffisant, mmap pour la RAM.
                engine = e.load(ModelSource.HuggingFace(REPO, FILE, "main", null),
                    EngineConfig(2048, 0, 4, true))
                env = e
                ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
                    .edit().putBoolean("llm_downloaded", true).apply()
                ready = true
                Log.i(TAG, "LLM prêt")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "LLM load échec: ${t.javaClass.simpleName} ${t.message}")
                ready = false
                false
            }
        }
    }

    /** Réécrit `userPrompt` (déjà rempli avec le transcript). Bloquant. null si échec/timeout. */
    fun rewrite(ctx: Context, userPrompt: String): String? = runBlocking {
        mutex.withLock {
            val e = engine ?: return@withLock null
            try {
                withTimeout(GEN_TIMEOUT_MS) {
                    val chat = ChatSession(
                        e, noThinkTemplate,
                        "Tu es un assistant qui reformule du texte en français. Réponds uniquement avec le texte final, sans explication."
                    )
                    val sb = StringBuilder()
                    // non-thinking: temp 0.7 / topP 0.8 / topK 20 (reco Qwen3) ; sortie courte.
                    chat.send(userPrompt, SamplingParams(256, 0.7f, 0.8f, 20, 1.1f, null, emptyList()))
                        .collect { sb.append(it.text) }
                    stripThink(sb.toString()).ifBlank { null }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "LLM rewrite échec: ${t.javaClass.simpleName}")
                null
            }
        }
    }

    fun unload() = runBlocking {
        mutex.withLock {
            try { engine?.close() } catch (_: Throwable) {}
            engine = null; env = null; ready = false
        }
    }
}
