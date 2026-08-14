# CLAUDE.md — WhisperPin (fork de phone-whisper)

## Description
App Android Kotlin : bouton micro flottant pour dicter du texte (transcription locale sherpa-onnx
ou Whisper API) par-dessus toutes les apps via service d'accessibilité. **Fork rebrandé "WhisperPin"**
(`applicationId com.uhama.whisperpin`, namespace inchangé `com.kafkasl.phonewhisper`). Objectif du
chantier en cours : rendre le bouton **indestructible** sur Xiaomi HyperOS (Poco F7 + Pad 7).

## Stack
- Kotlin, Android (compileSdk 34, minSdk 30, targetSdk 34), AGP/Gradle, okhttp, sherpa-onnx (JNI, .so NON bundlé → modèle local KO au runtime, chemin Whisper cloud OK).
- Build : `./gradlew :app:assembleDebug` (NDK non requis, APK ~16 Mo). Tests : `./gradlew :app:testDebugUnitTest`.
- SDK local : `~/Library/Android/sdk` (via `local.properties`, gitignored).
- CI : GitHub Actions (`.github/workflows/build.yml`) → artefact `whisperpin-debug-apk`.
- Repo : fork `Uhama91/phone-whisper`, branche de travail `whisperpin-persistence`.

## Architecture cible (Approche C — voir spec)
- `OverlayService` (FGS `specialUse|microphone`) héberge le bouton (`TYPE_APPLICATION_OVERLAY`) + capture audio.
- `WhisperAccessibilityService` = injection de texte seule.
- Binders locaux entre les deux. Pack survie : START_STICKY + BootReceiver + watchdog WorkManager (diagnostic) + assistant config MIUI.
- Règles micro FGS critiques (Codex) : armer `microphone` seulement app visible ; updates notif via `NotificationManager.notify()` (jamais `startForeground` seul) ; boot = `specialUse` explicite.

## Docs
- Spec : `docs/superpowers/specs/2026-05-31-phone-whisper-persistence-design.md` (v2.1, 3 passes Codex).
- Plan : `docs/superpowers/plans/2026-05-31-whisperpin-persistence.md` (14 tasks, 1 passe Codex).

## TODO / état
- ⏳ **GATE spike micro (Task 3)** : valider sur Poco F7 que l'OverlayService FGS `microphone` capte
  un audio non silencieux en arrière-plan (mesure `maxAmp`). APK : `~/Downloads/whisperpin-spike.apk`.
  Si VERT → Phases C-F (manifest, refactor, OverlayService complet, boot, watchdog, assistant, bouton, sécurité, matrice).
  Si ROUGE → repli (capture dans le service d'accessibilité).

## Étapes OEM réelles découvertes sur le Poco F7 (à encoder dans l'assistant Task 10)
1. **Overlay** : Afficher par-dessus les autres apps → autoriser.
2. **Paramètres restreints** (sideload + accessibilité Android 13+/HyperOS) : Réglages → Apps → WhisperPin → **bas de page** : interrupteur **« Autoriser les paramètres restreints »** = ON. (PAS dans le menu ⋮.) Sans ça, l'accessibilité reste grisée.
3. **Accessibilité** : Réglages → Accessibilité → Applications téléchargées → WhisperPin → activer.
4. **« Interrompre l'activité de l'application si elle n'est pas utilisée »** (en haut de la fiche app) → **désactiver** (sinon HyperOS retire les perms / tue l'app).
5. Batterie sans restriction, Autostart, pop-up arrière-plan, verrouiller dans les récents (cf. spec §7).

## Bugs réels écrasés (build de notre fork vs APK officiel)
- **Libs natives sherpa-onnx** (`libonnxruntime.so`, `libsherpa-onnx-{c,cxx,jni}.so`) : `jniLibs/` est gitignoré upstream → absentes de notre clone → `UnsatisfiedLinkError` crash au chargement modèle local. **Fix** : extraites de l'APK officiel v0.3.0, commitées (force-add) dans `app/src/main/jniLibs/arm64-v8a/`.
- **Permission `VIBRATE` manquante** → `SecurityException` crash au tap. **Fix** : déclarée + `vibrate()` en try/catch.
- `loadLocal`/`transcribe` doivent `catch(Throwable)` (UnsatisfiedLinkError = Error, pas Exception).

## Session Log
| Date | Action | Fichiers/Config |
|------|--------|-----------------|
| 2026-08-14 | Correctif Keep : sélection d’une cible d’injection unique, priorité au corps focalisé et conservation de cette même cible pour l’insertion directe comme pour le repli vers le presse-papiers ; version 0.5.6-wp, 133 tests et APK vérifiés. | `InjectionController.kt`, `WhisperAccessibilityService.kt`, tests, APK `~/Downloads/dictai-keep-injection-fix-0.5.6-wp.apk` |
| 2026-08-14 | Durcissement de l’exécution : interface ASR unifiée, démarrage transactionnel d’AudioRecord, registre d’injection sûr sur tout le cycle de vie et retour de l’overlay au démarrage sans armement du micro ; version 0.5.5-wp, 130 tests et build de débogage vérifiés. | `DictationAsrEngine.kt`, `RecordingStartupTransaction.kt`, `InjectionGateway.kt`, `BootReceiver.kt`, APK `~/Downloads/dictai-runtime-hardening-2026-08-14.apk` |
| 2026-08-06 | Correctif des régressions d’accessibilité et de presse-papiers : dictées de repli lisibles sans marqueur sensible, focus automatique restauré et cibles revalidées après rafraîchissement. | Injection, presse-papiers, tests, version 0.5.4-wp |
| 2026-08-05 | Catalogue ASR recentré sur quatre modèles avec Handy Q8 par défaut ; injection directe prioritaire, fallback presse-papiers unique et feedback de succès silencieux. | Catalogue, onboarding, accessibilité, overlay, tests, version 0.5.3-wp |
| 2026-08-05 | Nettoyage cloud OpenRouter unique : correctif AndroidKeyStore AES-GCM, migration du modèle historique, credential unique vérifié par commit/relecture, suppression anti-résurrection et conservation du ciphertext corrompu, catalogue de cinq modèles et tests ciblés. | `CloudCleanup.kt`, `PersistencePrefs.kt`, `MainActivity.kt`, `OverlayService.kt`, tests, version 0.5.2-wp |
| 2026-08-05 | Langue globale FR/EN instantanée par enregistrement (Nemotron `fr`/`en`, GGUF `fr-FR`/`en-US`), nettoyage cloud Keystore AES-GCM avec catalogue curaté de 2 modèles/fournisseur, budget dynamique plafonné à 5 120 tokens et timeout cloud de 45 s, DeepSeek sans thinking, exclusion des champs sensibles et repli fail-open ; UI fournisseur/modèle/clé sans préremplissage. | `CloudCleanup.kt`, `PersistencePrefs.kt`, `OverlayService.kt`, `MainActivity.kt`, `OnboardingActivity.kt`, JNI, tests, APK arm64 |
| 2026-08-05 | Ajout de Nemotron 3.5 GGUF, réglage Handy Q8_0 (751 Mo, SHA épinglé), maintien de Q6_K « compact » et affichage explicite du runtime transcribe.cpp/GGUF ou sherpa-onnx/ONNX dans les cartes et le statut prêt. | `ModelDownloader.kt`, `MainActivity.kt`, tests JVM |
| 2026-08-05 | Intégration expérimentale de `transcribe.cpp` (commit `553f109…`) avec Nemotron GGUF Q6_K (621 Mo) : téléchargement direct vérifié par SHA, publication atomique et progression plafonnée à 101 ; overlay en direct et ponctuation finale native. Test réel Android arm64 OK ; benchmark émulateur (audio 8,497 s) : chargement 1,157 s, inférence 19,004 s, RTF 2,237, pic PSS 1 093 867 Ko. Validation sur Poco F7 encore requise. | `CMakeLists.txt`, `transcribe_jni.cpp`, `TranscribeCppNative.kt`, `LiveStreamingTranscriber.kt`, `ModelDownloader.kt`, `OverlayService.kt` |
| 2026-08-05 | Installation ASR durcie : workspace `filesDir`, archive `.part`, publication atomique ; l’UI ignore les modèles incomplets. | `ModelDownloader.kt`, `ModelStorage.kt`, `LocalTranscriber.kt`, UI |
| 2026-08-05 | Nemotron : aperçu détaché sur trois lignes, non tactile ; pastille ancrée aux quatre bords avec migration et rotation. | `OverlayService.kt`, `LiveTranscriptBuffer.kt`, `LiveStreamingTranscriber.kt`, `OverlayPlacement.kt`, `PersistencePrefs.kt` |
| 2026-08-05 | DictAI passe à la transcription 100 % locale : retrait complet des flux cloud/LLM, conservation de Parakeet batch et de Nemotron streaming, garde-fous APK 16 Ko validés. | UI/transcription, contrôleur natif, CI |
| 2026-05-31 | Brainstorm→spec(3x Codex)→plan(Codex)→fork Uhama91 + CI. Tasks 1-7+11 faites. Spike micro VERT (maxAmp 21791). Core fonctionnel testé sur Poco F7 : bouton overlay + record + transcription locale + injection accessibilité OK après levée des « paramètres restreints ». Reste : pack survie (boot/watchdog/assistant OEM), sécurité finale, matrice. | tous les `.kt` du package, `jniLibs/arm64-v8a/*.so`, manifest, CI |
| 2026-06-03 | **Nettoyage cloud** (post-traitement) via **OpenRouter** (gateway compatible OpenAI, 1 clé/1 endpoint, tous modèles). `CloudCleanup.kt` + sélecteur Off/Local/Cloud (`PostProcessPrompts.engine`) + choix modèle (GPT-5 nano défaut, Gemini 2.0/2.5 Flash-Lite, GPT-4o mini, GPT-5.4 nano — slugs OpenRouter vérifiés). Découverte : nettoyage cloud existait (`PostProcessor`) mais **jamais branché** → seul le local Qwen3 tournait. Review Codex xhigh : timeout 12s, gate état `LLM_PROCESSING` sur dispo réelle, demande clé si Cloud sans clé, pas de log du corps de réponse (vie privée), feedback « nettoyage indispo ». Clé OpenRouter `phonewhisper/openrouter_key`, séparée de la clé OpenAI (transcription). Validé Poco F7 + Pad 7. | `CloudCleanup.kt`, `PostProcessPrompts.kt`, `OverlayService.kt`, `MainActivity.kt` |
| 2026-05-31 | Projet distribution publique : spec écrite (`docs/.../2026-05-31-dictai-distribution-onboarding-design.md`). **Brique 4 onboarding in-app** livrée : `OnboardingActivity` checklist auto-vérifiée (micro/modèle/overlay/accessibilité/batterie/notifs + étapes Xiaomi masquées hors MIUI), téléchargement du modèle FR intégré. **Fixes critiques** : modèle recommandé = Parakeet 0.6B (FR) au lieu du 110M (EN) ; erreur « Set API key » trompeuse → « Modèle local absent » ; rechargement auto du modèle sur ARM_MIC. Review Codex xhigh : race `loadLocal` (AtomicBoolean), extraction atomique (staging+rename), garde Activity/`isDestroyed`, `onb_complete` sur succès, `configChanges`. Validé device Pad 7 (dictée FR offline OK). | `OnboardingActivity.kt`, `ModelDownloader.kt`, `TranscriptionEngine.kt`, `OverlayService.kt`, `MainActivity.kt`, manifest |
| 2026-05-31 | Rebrand **DictAI** (repo `Uhama91/DictAI`) + thème sombre « carnet » + LLM post-traitement local (Qwen3-0.6B no-think) + vocabulaire + espace auto fin de dictée. **Le bouton EST désormais la pastille d'ondulation permanente** (plus de micro) : collée au bord, défilement gauche→droite + amplitude voix, bordure verte lumineuse de chargement (transcription/LLM), reste allumée tant qu'actif. Icône = boucle cursive verte sur crème. Review Codex xhigh appliquée (cue état pastille, loader GONE par défaut, collapse gardé, floats bornés). Validé device Poco F7. | `OverlayService.kt`, `CursiveWaveView.kt`, `LoadingBorderView.kt`, `PersistencePrefs.kt`, `MainActivity.kt`, `res/{drawable,mipmap-anydpi-v26,values}/ic_launcher*` |
