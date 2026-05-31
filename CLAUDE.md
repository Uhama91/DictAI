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

## Session Log
| Date | Action | Fichiers/Config |
|------|--------|-----------------|
| 2026-05-31 | Brainstorm→spec(3x Codex)→plan(Codex)→fork Uhama91 + CI. Tasks 1-3 faites (rebrand WhisperPin, CI Actions, spike micro). Spike en attente de test device. | spec+plan docs, `OverlayService.kt` (spike), manifest, `build.gradle.kts`, `.github/workflows/build.yml` |
