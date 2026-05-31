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
| 2026-05-31 | Brainstorm→spec(3x Codex)→plan(Codex)→fork Uhama91 + CI. Tasks 1-7+11 faites. Spike micro VERT (maxAmp 21791). Core fonctionnel testé sur Poco F7 : bouton overlay + record + transcription locale + injection accessibilité OK après levée des « paramètres restreints ». Reste : pack survie (boot/watchdog/assistant OEM), sécurité finale, matrice. | tous les `.kt` du package, `jniLibs/arm64-v8a/*.so`, manifest, CI |
| 2026-05-31 | Projet distribution publique : spec écrite (`docs/.../2026-05-31-dictai-distribution-onboarding-design.md`). **Brique 4 onboarding in-app** livrée : `OnboardingActivity` checklist auto-vérifiée (micro/modèle/overlay/accessibilité/batterie/notifs + étapes Xiaomi masquées hors MIUI), téléchargement du modèle FR intégré. **Fixes critiques** : modèle recommandé = Parakeet 0.6B (FR) au lieu du 110M (EN) ; erreur « Set API key » trompeuse → « Modèle local absent » ; rechargement auto du modèle sur ARM_MIC. Review Codex xhigh : race `loadLocal` (AtomicBoolean), extraction atomique (staging+rename), garde Activity/`isDestroyed`, `onb_complete` sur succès, `configChanges`. Validé device Pad 7 (dictée FR offline OK). | `OnboardingActivity.kt`, `ModelDownloader.kt`, `TranscriptionEngine.kt`, `OverlayService.kt`, `MainActivity.kt`, manifest |
| 2026-05-31 | Rebrand **DictAI** (repo `Uhama91/DictAI`) + thème sombre « carnet » + LLM post-traitement local (Qwen3-0.6B no-think) + vocabulaire + espace auto fin de dictée. **Le bouton EST désormais la pastille d'ondulation permanente** (plus de micro) : collée au bord, défilement gauche→droite + amplitude voix, bordure verte lumineuse de chargement (transcription/LLM), reste allumée tant qu'actif. Icône = boucle cursive verte sur crème. Review Codex xhigh appliquée (cue état pastille, loader GONE par défaut, collapse gardé, floats bornés). Validé device Poco F7. | `OverlayService.kt`, `CursiveWaveView.kt`, `LoadingBorderView.kt`, `PersistencePrefs.kt`, `MainActivity.kt`, `res/{drawable,mipmap-anydpi-v26,values}/ic_launcher*` |
