# WhisperPin — Post-traitement LLM local : Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Rendre utilisable le post-traitement LLM 100 % local (spike VERT) : écran de réglages + gestion de prompts + branchement dans la dictée, avec Qwen3-0.6B via kmp-ai (llama.cpp).

**Architecture:** `LlmPostProcessor` (singleton thread-safe : charge/décharge Qwen3-0.6B via kmp-ai, génère, strippe `<think>`, timeout+fallback) ; `PostProcessPrompts` (CRUD prompts + substitution `${output}` en prefs) ; `OverlayService.stopRec` appelle le LLM après les corrections si activé+prêt ; `MainActivity` expose l'écran « Post-traitement ».

**Tech Stack:** Kotlin, kmp-ai (`io.github.fadizg.kmpai:llm:0.2.8` — déjà intégré au spike), kotlinx-coroutines, Kotlin 2.2.20. Modèle `unsloth/Qwen3-0.6B-GGUF` / `Qwen3-0.6B-Q4_K_M.gguf`.

**Spec:** `docs/superpowers/specs/2026-05-31-whisperpin-local-llm-postprocessing.md` (v2 + spike §11).

**Acquis du spike (à réutiliser):** API kmp-ai `LlmEnvironment(ctx).load(ModelSource.HuggingFace(repo,file,rev,null)).use { engine -> ChatSession(engine, ChatTemplate.ChatML, system).send(text, SamplingParams(maxTokens,temp,topP,topK,repeatPenalty,seed,stop)).collect { it.text } }`. `<think>` à stripper.

---

## Task 1: `LlmPostProcessor` (moteur + lifecycle + strip think)

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/LlmPostProcessor.kt`
- Create test: `app/src/test/kotlin/com/kafkasl/phonewhisper/LlmStripThinkTest.kt`

- [ ] **Step 1: test du strip `<think>` (pur, sans device)**

Create `LlmStripThinkTest.kt`:
```kotlin
package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmStripThinkTest {
    @Test fun stripsThinkBlock() {
        assertEquals("Salut, ça va ?",
            LlmPostProcessor.stripThink("<think>\nL'utilisateur dit bonjour\n</think>Salut, ça va ?"))
    }
    @Test fun keepsPlainText() {
        assertEquals("Bonjour", LlmPostProcessor.stripThink("Bonjour"))
    }
    @Test fun stripsUnclosedThink() {
        assertEquals("", LlmPostProcessor.stripThink("<think> en cours"))
    }
}
```

- [ ] **Step 2: run le test (échec attendu), implémenter, run (succès)**

Create `LlmPostProcessor.kt`:
```kotlin
package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import io.github.fadizg.kmpai.llm.ChatSession
import io.github.fadizg.kmpai.llm.ChatTemplate
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
    @Volatile var ready: Boolean = false
        private set

    /** Retire un éventuel bloc de raisonnement Qwen3 `<think>...</think>`. */
    fun stripThink(raw: String): String {
        val s = raw.trim()
        if (!s.contains("<think>")) return s
        val close = s.indexOf("</think>")
        return if (close >= 0) s.substring(close + "</think>".length).trim() else ""
    }

    /** Charge le modèle (télécharge au 1er appel). Bloquant — hors thread principal. */
    fun ensureLoaded(ctx: Context): Boolean = runBlocking {
        mutex.withLock {
            if (engine != null) { ready = true; return@withLock true }
            try {
                val env = LlmEnvironment(ctx.applicationContext)
                val src = ModelSource.HuggingFace(REPO, FILE, "main", null)
                engine = env.load(src)
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

    /** Réécrit `userPrompt` (déjà rempli avec le transcript). Bloquant. Fallback = null si échec. */
    fun rewrite(ctx: Context, userPrompt: String): String? = runBlocking {
        val e = engine ?: return@runBlocking null
        try {
            mutex.withLock {
                withTimeout(GEN_TIMEOUT_MS) {
                    val chat = ChatSession(e, ChatTemplate.ChatML,
                        "Tu es un assistant qui reformule du texte en français. Réponds uniquement avec le texte final.")
                    val sb = StringBuilder()
                    chat.send(userPrompt, SamplingParams(512, 0.7f, 0.8f, 20, 1.5f, null, emptyList()))
                        .collect { sb.append(it.text) }
                    stripThink(sb.toString()).ifBlank { null }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "LLM rewrite échec: ${t.javaClass.simpleName}")
            null
        }
    }

    fun unload() {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null; ready = false
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*LlmStripThinkTest*"` → PASS.

- [ ] **Step 3: commit** — `feat: LlmPostProcessor (local Qwen3 via kmp-ai, strip think, timeout, thread-safe)`

---

## Task 2: `PostProcessPrompts` (CRUD + substitution)

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/PostProcessPrompts.kt`
- Create test: `app/src/test/kotlin/com/kafkasl/phonewhisper/PostProcessPromptsTest.kt`

- [ ] **Step 1: test substitution (pur)**

Create `PostProcessPromptsTest.kt`:
```kotlin
package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class PostProcessPromptsTest {
    @Test fun substitutesOutput() {
        assertEquals("Corrige : bonjour",
            PostProcessPrompts.fillTemplate("Corrige : \${output}", "bonjour"))
    }
    @Test fun appendsWhenNoPlaceholder() {
        assertEquals("Résume.\n\nbonjour",
            PostProcessPrompts.fillTemplate("Résume.", "bonjour"))
    }
}
```

- [ ] **Step 2: run (échec), implémenter, run (succès)**

Create `PostProcessPrompts.kt`:
```kotlin
package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Prompts de post-traitement (libellé + template avec ${output}), stockés en prefs. */
object PostProcessPrompts {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)

    data class Prompt(val label: String, val template: String)

    private const val DEFAULT_TEMPLATE =
        "Corrige l'orthographe, la grammaire et la ponctuation du texte suivant sans en changer le sens ni la langue. Réponds uniquement avec le texte corrigé.\n\n\${output}"

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("llm_enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("llm_enabled", v).apply()

    fun all(ctx: Context): List<Prompt> {
        val raw = prefs(ctx).getString("llm_prompts", null)
            ?: return listOf(Prompt("Correction", DEFAULT_TEMPLATE))
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Prompt(o.getString("label"), o.getString("template"))
        }.ifEmpty { listOf(Prompt("Correction", DEFAULT_TEMPLATE)) }
    }

    fun save(ctx: Context, prompts: List<Prompt>) {
        val arr = JSONArray()
        prompts.forEach { arr.put(JSONObject().put("label", it.label).put("template", it.template)) }
        prefs(ctx).edit().putString("llm_prompts", arr.toString()).apply()
    }

    fun selectedIndex(ctx: Context) =
        prefs(ctx).getInt("llm_selected", 0).coerceIn(0, (all(ctx).size - 1).coerceAtLeast(0))
    fun setSelectedIndex(ctx: Context, i: Int) = prefs(ctx).edit().putInt("llm_selected", i).apply()

    fun selected(ctx: Context): Prompt = all(ctx).getOrElse(selectedIndex(ctx)) { all(ctx).first() }

    /** Remplit le template avec le transcript (substitue ${output}, sinon l'ajoute en fin). */
    fun fillTemplate(template: String, transcript: String): String =
        if (template.contains("\${output}")) template.replace("\${output}", transcript)
        else "$template\n\n$transcript"

    fun fill(ctx: Context, transcript: String) = fillTemplate(selected(ctx).template, transcript)
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*PostProcessPromptsTest*"` → PASS.

- [ ] **Step 3: commit** — `feat: PostProcessPrompts CRUD + ${output} substitution`

---

## Task 3: Brancher le post-traitement dans la dictée (OverlayService)

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt`

- [ ] **Step 1: nouvel état visuel LLM + appel après corrections**

Dans `OverlayService` :
- Ajouter `LLM_PROCESSING` à l'enum `State`, et une couleur dans `setState` (ex: `0xDD3B6B8A.toInt()` bleu).
- Dans `stopRec`, après `val text = r.text?.let { Vocabulary.applyCorrections(this@OverlayService, it) }` et AVANT l'injection, insérer :
```kotlin
                var finalText = text
                if (!finalText.isNullOrBlank() &&
                    PostProcessPrompts.isEnabled(this@OverlayService) && LlmPostProcessor.ready) {
                    setState(State.LLM_PROCESSING)
                    val pp = LlmPostProcessor.rewrite(
                        this@OverlayService, PostProcessPrompts.fill(this@OverlayService, finalText))
                    if (!pp.isNullOrBlank()) finalText = pp  // sinon fallback = texte corrigé
                }
```
puis remplacer l'usage de `text` par `finalText` dans la suite (copyToClipboard/inject/toast).
> Note : `rewrite` est bloquant mais on est déjà dans un `thread {}` (pas le thread UI). `setState` poste sur le main thread. OK.

- [ ] **Step 2: build + commit** — `feat: wire local LLM post-processing into dictation flow (after corrections, before injection)`

---

## Task 4: Écran « Post-traitement » (MainActivity) + retrait du bouton spike

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt`
- Delete: `app/src/main/kotlin/com/kafkasl/phonewhisper/LlmSpike.kt`

- [ ] **Step 1: retirer le spike**

Supprimer `LlmSpike.kt` et le bouton `llmSpikeBtn` dans `MainActivity.onCreate`.

- [ ] **Step 2: section Post-traitement**

Ajouter dans `MainActivity` (réutiliser `settingsRow` + dialogs), section « Post-traitement (local) » :
1. **Toggle « Activer le post-traitement »** (`MaterialSwitch`) → `PostProcessPrompts.setEnabled`. À l'activation, lancer en background `thread { LlmPostProcessor.ensureLoaded(this) }` (télécharge le modèle au 1er coup) + toast « Préparation du modèle (~378 Mo en WiFi)… ».
2. **Ligne « Modèle Qwen3-0.6B »** : sous-titre `LlmPostProcessor.ready ? "Prêt" : "À préparer"` ; onClick → `thread { ensureLoaded }`.
3. **Ligne « Prompt sélectionné »** : ouvre un dialog listant `PostProcessPrompts.all`, choix → `setSelectedIndex`.
4. **Ligne « Modifier les prompts »** : dialog avec EditText (libellé + template), aide « Utilise `${output}` pour insérer le texte dicté », boutons Créer/Mettre à jour/Supprimer → `PostProcessPrompts.save`.

Code indicatif (toggle) :
```kotlin
        val ppSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = PostProcessPrompts.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                PostProcessPrompts.setEnabled(this@MainActivity, on)
                if (on) {
                    android.widget.Toast.makeText(this@MainActivity,
                        "Préparation du modèle (~378 Mo en WiFi)…", android.widget.Toast.LENGTH_LONG).show()
                    kotlin.concurrent.thread { LlmPostProcessor.ensureLoaded(this@MainActivity) }
                }
            }
        }
        root.addView(settingsRow("Post-traitement (local)", "Reformule la dictée avec un LLM hors-ligne", ppSwitch))
```
(Adapter à la vraie signature de `settingsRow(title, subtitle, widget?, onClick?)`.)

- [ ] **Step 3: build + commit** — `feat: Post-processing settings screen (toggle, model prep, prompt manager); remove spike`

---

## Task 5: Vérification device + finitions

- [ ] **Step 1: build APK, test sur Poco F7**
Activer le toggle → attendre « Prêt » → dicter une phrase mal orthographiée dans Notes → vérifier que le texte **inséré est reformulé proprement** (pas de `<think>`), bouton passe en bleu pendant le LLM. Désactiver → revient au texte brut+corrections. Mesurer latence ressentie.

- [ ] **Step 2: garde-fous**
Vérifier : si toggle ON mais modèle pas prêt → injection du texte brut (pas de blocage). Si génération échoue/timeout → fallback texte corrigé. Pas de crash mémoire après plusieurs dictées (RAM : modèle reste chargé tant que toggle ON ; `unload()` si toggle OFF — l'ajouter au `setOnCheckedChangeListener` côté OFF).

- [ ] **Step 3: doc + commit final**
Mettre à jour `CLAUDE.md` (session log + feature LLM). Consigner latence réelle.

---

## Self-Review (couverture spec)
- §4.1 LlmEngine/PostProcessPrompts/OverlayService/MainActivity → Tasks 1-4. ✓
- §4.2 flux (corrections → LLM si activé → injection) → Task 3. ✓
- §4.3 lifecycle (load on enable, unload on disable, fallback) → Tasks 1,4,5. ✓
- §10.4 strip `<think>` → Task 1. ✓
- §10.8 validation/fallback → Tasks 1,3. ✓
- §11 binding kmp-ai + modèle unsloth → Task 1. ✓
- UI prompts façon Handy (`${output}`) → Tasks 2,4. ✓

Pas de placeholder ; signatures cohérentes (`LlmPostProcessor.ready/ensureLoaded/rewrite/unload`, `PostProcessPrompts.isEnabled/fill/selected`).

---

## Corrections OBLIGATOIRES (review Codex) — à appliquer dans les tasks

### C1 (BLOQUANT) — `unload()` sous le même mutex ; `engine` lu DANS le lock (Task 1)
Remplacer `rewrite` (lecture `engine` dans le lock) et `unload` :
```kotlin
    fun rewrite(ctx: Context, userPrompt: String): String? = runBlocking {
        mutex.withLock {
            val e = engine ?: return@withLock null
            try {
                withTimeout(GEN_TIMEOUT_MS) {
                    val chat = ChatSession(e, ChatTemplate.ChatML,
                        "Tu es un assistant qui reformule du texte en français. Réponds uniquement avec le texte final.")
                    val sb = StringBuilder()
                    chat.send(userPrompt, SamplingParams(512, 0.7f, 0.8f, 20, 1.5f, null, emptyList()))
                        .collect { sb.append(it.text) }
                    stripThink(sb.toString()).ifBlank { null }
                }
            } catch (t: Throwable) { Log.e(TAG, "rewrite échec: ${t.javaClass.simpleName}"); null }
        }
    }

    fun unload() = runBlocking {
        mutex.withLock {
            try { engine?.close() } catch (_: Throwable) {}
            engine = null; ready = false
        }
    }
```
Aussi : stocker la réf `LlmEnvironment` (champ `@Volatile private var env`) au cas où elle détient des ressources (la fermer dans `unload` si elle expose `close()` ; sinon la garder en vie tant que `engine` vit).

### C2 (BLOQUANT) — Task 3 : l'appel LLM reste sur le thread BACKGROUND, pas `main.post`
Restructurer le bloc résultat de `stopRec` ainsi (corrections + LLM calculés sur le thread de transcription, puis SEULEMENT injection/UI sur main) :
```kotlin
        thread {
            val r = TranscriptionEngine.transcribe(this, data, local)
            // --- background : corrections + post-traitement LLM ---
            var finalText = r.text?.let { Vocabulary.applyCorrections(this, it) }
            if (!finalText.isNullOrBlank() &&
                PostProcessPrompts.isEnabled(this) && LlmPostProcessor.ready) {
                setState(State.LLM_PROCESSING)   // setState poste l'UI sur main, OK depuis un bg thread
                val pp = LlmPostProcessor.rewrite(this, PostProcessPrompts.fill(this, finalText))
                if (!pp.isNullOrBlank()) finalText = pp
            }
            val outText = finalText
            // --- main : clipboard + injection + UI seulement ---
            main.post {
                if (!outText.isNullOrBlank()) {
                    copyToClipboard(outText)
                    val injected = WhisperAccessibilityService.controller?.inject(outText) ?: false
                    toast(if (injected) "Insere" else "Copie (presse-papier)")
                } else toast("Erreur: ${r.error ?: "vide"}")
                setState(State.IDLE)
            }
        }
```

### C3 (IMPORTANT) — `onTap` ignore les taps pendant `LLM_PROCESSING` (anti file d'attente)
Dans `onTap`, traiter `State.LLM_PROCESSING` comme `State.TRANSCRIBING` (no-op).

### C4 (IMPORTANT) — preload non-bloquant si activé mais pas prêt (process redémarré)
Dans `OverlayService.onCreate`, après le chargement Parakeet :
```kotlin
        if (PostProcessPrompts.isEnabled(this)) thread { LlmPostProcessor.ensureLoaded(this) }
```
→ après reboot/restart, le modèle (déjà téléchargé) se recharge tout seul. Tant qu'il n'est pas prêt, la dictée injecte le texte corrigé (pas de blocage).

### C5 (IMPORTANT) — toggle OFF appelle `unload()` ; sémantique file documentée
Dans le `setOnCheckedChangeListener` (Task 4), branche OFF : `kotlin.concurrent.thread { LlmPostProcessor.unload() }`. Une seule génération à la fois (mutex) ; les taps pendant génération sont ignorés (C3).

### C6 (MINEUR) — `PostProcessPrompts` : parsing JSON gardé
Wrapper `all()` dans `try/catch` → en cas de prefs corrompus, retourner `listOf(Prompt("Correction", DEFAULT_TEMPLATE))`.

### C7 (MINEUR) — test « think non fermé / timeout → null → texte corrigé conservé »
Déjà couvert par `stripsUnclosedThink` (retourne "") + fallback `if (!pp.isNullOrBlank())`. Ajouter un test reliant les deux si simple.

### C8 (test device OBLIGATOIRE) — cancellation du timeout
kmp-ai/llama.cpp n'est peut-être pas cancellation-cooperative : forcer un timeout court pendant une génération et vérifier que `rewrite` rend la main, libère le mutex, et qu'une 2ᵉ dictée repart. Si ça reste bloqué → ajouter `unload()` on timeout.
