# WhisperPin — Post-traitement LLM 100 % local (offline)

- **Date** : 2026-05-31
- **Auteur** : Ullie (Uhama) + Claude
- **Périmètre** : Feature « Post-traitement » façon Handy, mais **100 % on-device** (zéro cloud).
- **Pré-requis** : core WhisperPin fonctionnel (overlay + Parakeet v3 + injection + vocabulaire) — déjà livré.
- **Appareils cibles** : Poco F7 (SD 8s Gen 4, 12 Go) + Pad 7. arm64-v8a.

---

## 1. But

Après la transcription (et l'application des corrections vocabulaire), passer **optionnellement** le texte
dans un **LLM exécuté localement** avec un **prompt système défini par l'utilisateur** (placeholder
`${output}`), puis injecter le résultat. Cas d'usage : reformater un email, corriger le style, changer
le ton, résumer — **sans réseau, sans clé API, gratuit, privé**.

> Rappel : Parakeet v3 met déjà ponctuation + majuscules et garde la langue parlée. Le LLM n'apporte
> que pour du **reformatage intelligent**. La feature est **désactivée par défaut**.

## 2. Décisions (prises au brainstorming)

| Sujet | Décision | Raison |
|---|---|---|
| Moteur | **llama.cpp** via binding Kotlin (GGUF) | Modèles en téléchargement **libre** (pas de login HF), flexible, large support appareils |
| Modèle par défaut | **Qwen3-0.6B-Instruct, Q4_K_M GGUF** (~0,5 Go) | Léger (~1 Go RAM), français correct (meilleur que Llama 1B per étude Luth EACL 2026), Apache 2.0, GGUF officiel |
| Mode | `enable_thinking = false` | Sortie directe pour le nettoyage (pas de `<think>`) |
| Activation | **Toggle OFF par défaut** | Feature avancée optionnelle ; n'alourdit pas l'usage de base |
| Livraison modèle | **Téléchargement in-app** (comme Parakeet) | Trop gros pour l'APK ; cache dans `filesDir` |

Écartés : Gemma 4 E2B (2,58 Go, multimodal, format LiteRT-LM ≠ GGUF) ; Llama 3.2 1B (mauvais en
français) ; MediaPipe+Gemma (téléchargement gated login HF).

## 3. ⚠️ Spike d'abord (GATE)

**Première étape d'implémentation = spike on-device** : prouver qu'un binding llama.cpp Kotlin charge
**Qwen3-0.6B Q4 GGUF** et **réécrit un texte court en français hors-ligne** sur le Poco F7, avec une
latence et une RAM acceptables. **Si le spike échoue** (binding non consommable, .so arm64 absents,
OOM, latence rédhibitoire) → STOP, on réévalue le moteur (binding officiel llama.cpp vs `llama-bro`
vs build NDK, ou repli MediaPipe). Tout ce qui suit suppose le spike vert.

Candidats binding à évaluer au spike :
- **binding officiel `ggml-org/llama.cpp`** (Android, façade `AiChat`/`InferenceEngine`),
- **`whyisitworking/llama-bro`** (SDK Kotlin Coroutines, GGUF, templates Gemma/Llama/ChatML/Qwen),
- repli : build llama.cpp via NDK + JNI maison (lourd).

Critère d'acceptation spike : génère une réécriture correcte d'un transcript FR de ~30 mots en **< ~6 s**,
RAM pic raisonnable (l'app ne crashe pas en OOM avec Parakeet aussi chargé).

## 4. Architecture

### 4.1 Composants

| Composant | Statut | Rôle |
|---|---|---|
| `LlmEngine` | 🆕 | Encapsule le binding llama.cpp : `load(modelPath)`, `generate(prompt): String`, `unload()`. Singleton, chargé à la demande. |
| `LlmModelDownloader` | 🆕 | Télécharge + cache le GGUF (réutilise le pattern `ModelDownloader` Parakeet). |
| `PostProcessPrompts` | 🆕 | CRUD des prompts utilisateur (libellé + instructions avec `${output}`), stockés en prefs. Un prompt par défaut fourni. |
| `OverlayService` | ✏️ | Dans `stopRec`, après corrections : si post-traitement activé + modèle chargé → `LlmEngine.generate(promptRempli)` → injecter le résultat. Nouvel état visuel « LLM ». |
| `MainActivity` | ✏️ | Écran « Post-traitement » : toggle activer, téléchargement/sélection modèle, gestion des prompts (créer/éditer/sélectionner/supprimer). |

### 4.2 Flux (tap → texte)

1. Tap → record → stop → transcription Parakeet → **corrections vocabulaire**.
2. Si **post-traitement activé** ET modèle chargé :
   - bouton passe en état « LLM » (couleur dédiée),
   - `prompt = promptSélectionné.replace("\${output}", transcript)`,
   - `result = LlmEngine.generate(prompt)` (sur thread, hors UI),
   - injecter `result` (sinon repli sur le transcript brut si échec/timeout).
3. Sinon : injecter le transcript (comportement actuel).

### 4.3 Gestion mémoire / cycle de vie

- **Chargement à la demande** : le modèle LLM n'est chargé qu'au 1ᵉʳ usage (ou quand le toggle est
  activé), pas au démarrage du service. Param « durée de vie du modèle » (comme Handy) : décharger
  après N minutes d'inactivité pour libérer la RAM. v1 : décharger quand le post-traitement est
  désactivé ; garder chargé tant qu'activé.
- **Pas de concurrence ASR+LLM simultanée** : la transcription Parakeet finit avant le LLM → pas de
  pic mémoire doublé en théorie ; à valider au spike (les deux modèles peuvent rester en RAM).
- Timeout de génération (ex : 20 s) → repli transcript brut.

## 5. UI « Post-traitement » (façon Handy)

- **Activer le post-traitement** (toggle, OFF par défaut).
- **Modèle** : ligne d'état (téléchargé ? chargé ?) + bouton télécharger/supprimer (Qwen3-0.6B).
- **Prompts** :
  - liste déroulante « Prompt sélectionné »,
  - éditeur (libellé + instructions, avec aide « Utilise `${output}` pour insérer le transcript »),
  - boutons Créer / Mettre à jour / Supprimer.
  - **Prompt par défaut** fourni (FR) : « Corrige l'orthographe, la grammaire et la ponctuation du
    texte suivant sans en changer le sens ni la langue. Réponds uniquement avec le texte corrigé.
    Texte : ${output} ».
- Stockage : prefs `whisperpin` (clé `llm_prompts`, `llm_selected`, `llm_enabled`).

## 6. Permissions & build

- `INTERNET` (déjà) pour le téléchargement du modèle.
- Binding llama.cpp : ajoute des **libs natives arm64-v8a** (.so) — soit via dépendance Gradle/AAR
  (idéal), soit committées dans `jniLibs/arm64-v8a/` (comme sherpa). Impact taille APK à mesurer.
- Aucune permission stockage (modèle dans `filesDir`).

## 7. Tests & vérification

- **Spike on-device (GATE)** : §3.
- Tests unitaires : `PostProcessPrompts` (CRUD + substitution `${output}`), parsing/format prompt.
- Matrice manuelle (Poco F7) : activer → télécharger modèle → dicter une phrase → vérifier réécriture
  cohérente en français → mesurer latence + absence de crash mémoire ; désactiver → revient au
  comportement brut.

## 8. Risques

| Risque | Mitigation |
|---|---|
| Binding llama.cpp non consommable / .so arm64 absents | **Spike en 1ʳᵉ étape** ; 3 candidats + repli NDK |
| OOM (Parakeet + LLM en RAM) | Chargement à la demande, déchargement, mesure au spike, modèle 0.6B léger |
| Latence trop élevée | Modèle 0.6B, `enable_thinking=false`, timeout + repli transcript brut |
| Qwen3 sort des `<think>` ou se répète | `enable_thinking=false`, sampling recommandé (temp 0.7, presence_penalty 1.5), template Qwen/ChatML correct |
| Taille APK / modèle | Modèle téléchargé (pas dans l'APK) ; libs natives arm64 seulement |
| Qualité FR insuffisante en 0.6B | Option future : proposer Qwen3-1.7B / Luth-1.7B en téléchargement alternatif |

## 9. Hors périmètre

- Streaming token-par-token dans le champ (on injecte le résultat final).
- Multimodal (image/audio).
- Modèles >2B, NPU/GPU tuning avancé (CPU d'abord ; GPU si le binding le permet sans surcoût).
- Choix multi-modèles dans le catalogue (v1 = Qwen3-0.6B ; alternatives en évolution ultérieure).

---

## 10. Révision v2 — durcissements suite à review Codex (xhigh)

**Verdict Codex** : faisable, mais **2–3 sprints** (pas 1). Le coût d'intégration **native** est sous-estimé : aucun chemin « importer un AAR + déposer un GGUF = ça tourne » n'est prouvé propre pour llama.cpp sur Android en 2026. **Le spike est l'unique préalable.**

### 10.1 Le spike doit produire un *document de décision binding* (livrable)
Choix tranché avec : dépendance/commit exact, ABIs fournies, licence, **preuve de build CI**, et un **build WhisperPin minimal qui coexiste avec les libs sherpa déjà présentes**. Candidats (aucun « drop-in » garanti) :
- `ggml-org/llama.cpp` (`examples/llama.android`) → **build NDK source**, pas un AAR Maven stable.
- `whyisitworking/llama-bro` (JitPack) → templates listent **Qwen 2.5, pas Qwen3** → maturité à vérifier.
- repli : build llama.cpp NDK + JNI maison.

### 10.2 Gate spike DURCI (tous bloquants)
Build Gradle propre **+** cold-load chronométré **+** génération warm < ~6 s sur ~30 mots **+** plafond **PSS mesuré** (`dumpsys meminfo`) **+** **20 générations** consécutives sans croissance RSS (pas de fuite native) **+** `unload()` libère bien la RAM **+** **aucun bloc `<think>` en sortie** **+** qualité FR correcte **+** fallback timeout OK **+** **zéro régression overlay/FGS**.

### 10.3 RAM (P1) — `NativeModelManager`
Mesurer PSS à chaque étape (après load ASR, après load LLM, pendant génération, après unload, après 20 cycles). Composant capable de **décharger le LLM** (voire l'ASR) sous pression mémoire. Pic estimé Parakeet + LLM 0.6B : **> 1,5 Go RSS** → possible sur 12 Go mais HyperOS kill agressif sur FGS → à prouver.

### 10.4 Qwen3 (P1) — précisions
- **Quant** : les `Q4_K_M` sont des quants **communautaires** (bartowski…) ; le GGUF *officiel* Qwen est Q8_0 (~639 Mo). **Épingler repo + fichier + SHA-256 exacts.**
- `enable_thinking=false` se règle **au niveau du chat template**, pas via un simple system prompt.
- Prévoir : stop tokens, max output tokens, **strippeur défensif `<think>...</think>`**, sampling recommandé (temp 0.7, presence_penalty 1.5).

### 10.5 Packaging natif (P2)
Inventaire `.so` dans l'APK en **CI** (`unzip -l apk | grep .so`), **une seule ABI**, **pas de `libc++_shared.so` en double**, symboles cachés / bridge JNI à nom unique pour éviter collisions ggml↔sherpa/onnxruntime, suivi taille APK.

### 10.6 Cloud vs Local (P2)
WhisperPin a déjà un post-processing **cloud** (`PostProcessor` OpenAI, `use_post_processing`). La feature locale doit être **distincte et non ambiguë** : soit deux entrées séparées (« Nettoyage cloud » / « Nettoyage local »), soit retrait du chemin cloud dans ce fork. Ne pas réutiliser `use_post_processing`.

### 10.7 Download intégrité (P2)
SHA-256, reprise/nettoyage des téléchargements partiels, vérification d'espace libre, avertissement données mobiles, épinglage de version, delete/retry.

### 10.8 Validation prompt (P3)
Exactement un `${output}`, plafonds de longueur prompt/transcription, délimitation du transcript, **fallback systématique sur le transcript brut** si prompt malformé / génération vide / timeout.
