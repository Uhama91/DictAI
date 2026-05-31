# WhisperPin — Persistance indestructible : Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre le bouton de dictation flottant indestructible sur Xiaomi HyperOS (Poco F7 + Pad 7) en découplant le bouton et la capture micro du service d'accessibilité vers un foreground service `specialUse|microphone`, avec pack survie (START_STICKY, BootReceiver, watchdog diagnostic, assistant OEM).

**Architecture:** `OverlayService` (FGS) héberge le bouton (`TYPE_APPLICATION_OVERLAY`) et capture l'audio ; `WhisperAccessibilityService` ne fait plus que l'injection de texte ; communication par binders locaux ; `TranscriptionEngine` extrait la logique sherpa-onnx/Whisper ; `BootReceiver` + `WatchdogWorker` + assistant de config MIUI assurent la survie.

**Tech Stack:** Kotlin, Android SDK (compileSdk 34, minSdk 30), AndroidX (core-ktx, appcompat, material, **work-runtime-ktx** nouveau), okhttp, sherpa-onnx (JNI déjà présent), JUnit + Robolectric (pour tests unitaires sur resources). Build via GitHub Actions.

**Spec source:** `docs/superpowers/specs/2026-05-31-phone-whisper-persistence-design.md` (v2.1).

**Référence package:** le `namespace`/package Kotlin reste `com.kafkasl.phonewhisper` (on ne déplace pas les fichiers) ; seul l'`applicationId` devient `com.uhama.whisperpin` (séparation d'app). Tous les nouveaux fichiers vont dans `app/src/main/kotlin/com/kafkasl/phonewhisper/`.

---

## ⚠️ Gate de décision (Task 3)

La **Task 3 est un spike on-device** : elle prouve que la capture micro via `OverlayService` FGS `microphone` fonctionne en arrière-plan sur le Poco F7. **Si elle échoue** (audio silencieux en arrière-plan), STOP : ne pas continuer les tasks suivantes telles quelles — revenir vers l'utilisateur avec le résultat et l'option de repli (garder la capture dans le service d'accessibilité + maintenir une fenêtre overlay accessibilité invisible). Toutes les tasks ≥4 supposent le spike vert.

## Gate Codex (rappel)

Review Codex (xhigh) **avant chaque commit/push** de fin de phase (Phases A à F ci-dessous). Le plan lui-même doit être reviewé par Codex avant de démarrer la Task 1.

---

## Phases

- **Phase A — Fondations** : Task 1 (renommage/applicationId + keystore debug), Task 2 (CI GitHub Actions).
- **Phase B — Spike micro** : Task 3 (OverlayService minimal + mic, vérif on-device). **GATE.**
- **Phase C — Cœur persistance** : Task 4 (manifest complet), Task 5 (binders + refactor accessibilité injection-only), Task 6 (TranscriptionEngine), Task 7 (OverlayService complet : états + transcription + injection + dégradation mic).
- **Phase D — Survie** : Task 8 (BootReceiver), Task 9 (WatchdogWorker diagnostic).
- **Phase E — UX & assistant** : Task 10 (assistant config + détection HyperOS), Task 11 (améliorations bouton), Task 12 (écran auto-test persistance).
- **Phase F — Sécurité & finition** : Task 13 (redaction logcat), Task 14 (matrice de test 2 appareils + doc utilisateur).

---

## File Structure

| Fichier | Action | Responsabilité |
|---|---|---|
| `app/build.gradle.kts` | Modifier | applicationId, signingConfig debug, dépendance WorkManager |
| `app/debug.keystore` | Créer | Keystore debug commité (signature stable) |
| `.github/workflows/build.yml` | Créer | CI : build APK + tests + artefact |
| `app/src/main/AndroidManifest.xml` | Modifier | Permissions FGS/overlay/boot, déclaration OverlayService + BootReceiver, label |
| `.../OverlayService.kt` | Créer | FGS `specialUse|microphone` : bouton overlay, capture audio, états, dégradation mic, binder |
| `.../OverlayController.kt` | Créer | Interface/binder exposé par OverlayService (callbacks d'état) |
| `.../InjectionController.kt` | Créer | Interface/binder exposé par le service d'accessibilité (injecter texte) |
| `.../WhisperAccessibilityService.kt` | Modifier | Réduit à l'injection de texte + binder ; perd overlay + audio |
| `.../TranscriptionEngine.kt` | Créer | Transcription locale/API + post-traitement (extrait du service) |
| `.../BootReceiver.kt` | Créer | Relance OverlayService au boot/MAJ (specialUse explicite) |
| `.../WatchdogWorker.kt` | Créer | WorkManager diagnostic périodique |
| `.../OemSetup.kt` | Créer | Détection HyperOS + intents réglages OEM + états permissions |
| `.../MainActivity.kt` | Modifier | Assistant de config (checklist), arme le micro quand visible, écran auto-test |
| `.../PersistencePrefs.kt` | Créer | Helper SharedPreferences (position bouton, état armement) |
| `app/src/test/.../*` | Créer/Modifier | Tests unitaires (TranscriptionEngine, OemSetup détection, redaction, clamp position) |

---

# Phase A — Fondations

## Task 1: Renommage en WhisperPin + keystore debug commité

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/debug.keystore`
- Modify: `app/src/main/res/values/strings.xml` (app_name)

- [ ] **Step 1: Générer un keystore debug stable**

Run:
```bash
cd /Users/ulliemaillot/dev_project/phone-whisper
keytool -genkey -v -keystore app/debug.keystore -storepass android \
  -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=WhisperPin Debug,O=Uhama,C=FR"
```
Expected: `app/debug.keystore` créé (~2 KB).

- [ ] **Step 2: Modifier `app/build.gradle.kts` — applicationId + signingConfig**

Dans `android { defaultConfig { ... } }`, changer :
```kotlin
        applicationId = "com.uhama.whisperpin"
        targetSdk = 34
        versionCode = 3
        versionName = "0.4.0-wp"
```
Ajouter après `defaultConfig { ... }` (dans `android { }`) :
```kotlin
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
```

- [ ] **Step 3: Modifier le nom de l'app**

Dans `app/src/main/res/values/strings.xml`, changer la valeur de `app_name` en `WhisperPin`.

- [ ] **Step 4: Vérifier que le build local n'est pas requis — valider la config via lecture**

Run:
```bash
grep -n "applicationId\|versionName\|debug.keystore" app/build.gradle.kts
```
Expected: voir `com.uhama.whisperpin`, `0.4.0-wp`, `debug.keystore`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/debug.keystore app/src/main/res/values/strings.xml
git commit -m "chore: rebrand to WhisperPin, separate applicationId + stable debug keystore"
```

---

## Task 2: CI GitHub Actions (build APK + tests + artefact)

**Files:**
- Create: `.github/workflows/build.yml`

- [ ] **Step 1: Créer le workflow**

Create `.github/workflows/build.yml`:
```yaml
name: Build WhisperPin APK

on:
  push:
    branches: [ "**" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant execute permission for gradlew
        run: chmod +x ./gradlew

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest --stacktrace

      - name: Build debug APK
        run: ./gradlew assembleDebug --stacktrace

      - name: Rename APK
        run: cp app/build/outputs/apk/debug/app-debug.apk whisperpin-debug.apk

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: whisperpin-debug-apk
          path: whisperpin-debug.apk
          if-no-files-found: error
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: build debug APK + run unit tests on push, upload artifact"
```

- [ ] **Step 3: (après fork) déclencher et vérifier le 1er build vert**

Après le fork+push (Task 14 setup ou maintenant si on pousse), aller dans l'onglet Actions du repo GitHub, vérifier que le job `build` est vert et que l'artefact `whisperpin-debug-apk` est téléchargeable. Si rouge, lire les logs et corriger avant de continuer.

---

# Phase B — Spike micro (GATE)

## Task 3: OverlayService minimal + capture micro vérifiée on-device

But : prouver empiriquement que `OverlayService` (FGS `specialUse|microphone`, démarré au premier plan) capture un audio **non silencieux** quand on tape le bouton flottant **alors que l'app est en arrière-plan**, sur le Poco F7. Ce squelette devient la fondation du `OverlayService` complet (Task 7).

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt` (version spike)
- Modify: `app/src/main/AndroidManifest.xml` (permissions + service minimal)
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt` (bouton « Démarrer overlay (spike) »)

- [ ] **Step 1: Manifest — permissions minimales du spike**

Dans `AndroidManifest.xml`, ajouter sous les `uses-permission` existants :
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
Dans `<application>`, ajouter :
```xml
        <service
            android:name=".OverlayService"
            android:exported="false"
            android:foregroundServiceType="specialUse|microphone"
            android:stopWithTask="false">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="persistent floating dictation button overlay" />
        </service>
```

- [ ] **Step 2: Écrire `OverlayService.kt` (version spike)**

Create `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt`:
```kotlin
package com.kafkasl.phonewhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.sqrt

class OverlayService : Service() {

    companion object {
        private const val TAG = "WhisperPin"
        private const val CHANNEL_ID = "whisperpin_overlay"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        const val ACTION_START = "com.uhama.whisperpin.START"
    }

    private var button: ImageView? = null
    private var recording = false
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Spike: on démarre directement avec specialUse|microphone (app au premier plan attendue)
        startAsForeground(withMic = true)
        showButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "WhisperPin", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WhisperPin actif")
            .setContentText("Appuie sur le bouton pour dicter")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()

    /** Règle Codex: armer le micro seulement quand éligible (app visible). try/catch. */
    private fun startAsForeground(withMic: Boolean) {
        val type = if (withMic)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
            Log.i(TAG, "startForeground OK withMic=$withMic")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground withMic=$withMic failed: ${e.javaClass.simpleName} ${e.message}")
            if (withMic) {
                // repli sans micro pour ne pas crasher (dégradation)
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        }
    }

    private fun showButton() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xDD1C1C1E.toInt())
            }
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { onTap() }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (8 * dp).toInt()
            y = resources.displayMetrics.heightPixels / 2
        }
        wm.addView(img, params)
        button = img
    }

    private fun onTap() {
        if (!recording) startRec() else stopRec()
    }

    private fun startRec() {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: SecurityException) {
            toast("Mic refusé: ${e.message}"); return
        }
        recording = true
        toast("REC…")
        audioRecord!!.startRecording()
        thread {
            val buf = ShortArray(bufSize)
            var maxAmp = 0.0
            var samples = 0L
            while (recording) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                for (i in 0 until n) { maxAmp = maxOf(maxAmp, kotlin.math.abs(buf[i].toDouble())); samples++ }
            }
            // SPIKE ASSERTION: amplitude doit être non nulle si le micro marche en arrière-plan
            Log.i(TAG, "SPIKE result: samples=$samples maxAmp=$maxAmp (>0 => mic OK en background)")
            android.os.Handler(mainLooper).post {
                toast("maxAmp=${maxAmp.toInt()} samples=$samples")
            }
        }
    }

    private fun stopRec() {
        recording = false
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopRec()
        button?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        button = null
        super.onDestroy()
    }
}
```

- [ ] **Step 3: MainActivity — bouton de lancement du spike**

Dans `MainActivity.onCreate`, après la construction du `root`, ajouter un bouton qui démarre le service (l'app est au premier plan → armement micro éligible) :
```kotlin
        val spikeBtn = android.widget.Button(this).apply {
            text = "Démarrer overlay (spike)"
            setOnClickListener {
                if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                    return@setOnClickListener
                }
                startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
            }
        }
        root.addView(spikeBtn)
```
(Garder l'écran existant ; on ne fait qu'ajouter ce bouton temporaire.)

- [ ] **Step 4: Build APK via CI**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt app/src/main/AndroidManifest.xml app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt
git commit -m "spike: OverlayService FGS specialUse|microphone minimal pour valider mic background"
git push
```
Récupérer `whisperpin-debug-apk` depuis l'onglet Actions.

- [ ] **Step 5: Test on-device (Poco F7) — LE GATE**

Procédure manuelle :
1. Désinstaller toute version WhisperPin précédente, installer le nouvel APK.
2. Ouvrir l'app → accorder micro + « afficher par-dessus les autres apps ».
3. Taper « Démarrer overlay (spike) » → le bouton flottant apparaît, notif « WhisperPin actif ».
4. **Appuyer sur Accueil** (app en arrière-plan), ouvrir une autre app (ex: Notes).
5. Taper le bouton flottant → parler 3 s → re-taper.
6. Lire le toast / `adb logcat -s WhisperPin` (si dispo) : **`maxAmp` doit être nettement > 0** (typiquement plusieurs centaines/milliers). `maxAmp ≈ 0` = micro silencieux en arrière-plan.

Expected (succès) : `maxAmp` largement > 0 → **mic background via FGS OK → GATE VERT → continuer Task 4.**
Expected (échec) : `maxAmp ≈ 0` → **STOP.** Remonter le résultat à l'utilisateur ; envisager le repli (capture dans le service d'accessibilité + fenêtre overlay accessibilité). Ne pas continuer.

- [ ] **Step 6: Consigner le résultat du spike**

Ajouter une ligne en bas de la spec (`## Résultat spike micro`) : date, appareil, `maxAmp` observé, verdict VERT/ROUGE. Commit.

```bash
git add docs/superpowers/specs/2026-05-31-phone-whisper-persistence-design.md
git commit -m "docs: consigner le résultat du spike micro on-device"
```

---

# Phase C — Cœur persistance

> **Pré-requis : Task 3 GATE VERT.**

## Task 4: Manifest complet

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Ajouter les permissions restantes**

Sous les `uses-permission` existants, ajouter :
```xml
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```
(Les autres — FOREGROUND_SERVICE*, SYSTEM_ALERT_WINDOW, POST_NOTIFICATIONS — ont été ajoutées en Task 3.)

- [ ] **Step 2: Déclarer le BootReceiver**

Dans `<application>`, ajouter :
```xml
        <receiver
            android:name=".BootReceiver"
            android:exported="true"
            android:directBootAware="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 3: Label app**

Vérifier que `<application android:label="@string/app_name" ...>` (déjà le cas). `app_name = WhisperPin` (Task 1).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: manifest complet (boot, battery, receiver)"
```

---

## Task 5: Binders locaux + refactor accessibilité injection-only

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/InjectionController.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt`

- [ ] **Step 1: Définir l'interface d'injection**

Create `InjectionController.kt`:
```kotlin
package com.kafkasl.phonewhisper

/** Contrat exposé par le service d'accessibilité au OverlayService. */
interface InjectionController {
    /** Injecte le texte dans le champ focalisé. Retourne true si une action d'injection a réussi. */
    fun inject(text: String): Boolean
}
```

- [ ] **Step 2: Refactor `WhisperAccessibilityService` — retirer overlay + audio, garder injection + binder**

Dans `WhisperAccessibilityService.kt` :
- Supprimer tout le code d'overlay (`showOverlay`, `removeOverlay`, `circle`, `pill`, `setAppearance`, `setBusy`, `positionFeedback`, `showFeedback`, `startPulse`, `stopPulse`, vues, layoutParams, hideFeedback).
- Supprimer la capture audio (`startRecording`, `stopAndTranscribe`, `audioRecord`, `pcmStream`, `onTap`, machine d'états).
- Supprimer la transcription locale/API (déplacée en `TranscriptionEngine`, Task 6) ainsi que `transcribeLocal`/`transcribeApi`/`handleTranscriptionResult`/`reset`/`initLocalModel`/`reloadModel`/`localTranscriber`.
- **Conserver** : `findInjectionCandidates`, `collectInjectionCandidates`, `collectPotentialTargets`, `isPotentialInjectionTarget`, `candidateScore`, `tryInjectIntoNode`, `findCustomPasteAction`, `prefs`.
- Implémenter `InjectionController` via une classe binder locale et l'exposer.

Nouveau squelette du fichier :
```kotlin
class WhisperAccessibilityService : AccessibilityService(), InjectionController {

    companion object {
        @Volatile var controller: InjectionController? = null
        private const val TAG = "WhisperPin"
    }

    override fun onServiceConnected() {
        controller = this
        // Tenter de démarrer l'overlay dès que l'accessibilité est active.
        // GARDÉ (Codex) : onServiceConnected peut être un contexte background →
        // startForegroundService peut lever ForegroundServiceStartNotAllowedException.
        // En cas d'échec, dégradation silencieuse : MainActivity.onResume le démarrera
        // au premier plan (chemin fiable).
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "overlay start depuis accessibilité refusé: ${e.javaClass.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        controller = null
        super.onDestroy()
    }

    override fun inject(text: String): Boolean {
        // copie presse-papier (repli) + tentative d'injection dans le champ focalisé
        val clip = ClipData.newPlainText("whisperpin", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        val candidates = findInjectionCandidates()
        var injected = false
        try {
            for (c in candidates) { if (tryInjectIntoNode(c, text)) { injected = true; break } }
        } finally { candidates.forEach { it.recycle() } }
        return injected
    }

    // ... (conserver findInjectionCandidates / tryInjectIntoNode / etc. tels quels) ...
}
```
> Note : `OverlayService` lira `WhisperAccessibilityService.controller` (peut être `null` si l'accessibilité n'est pas connectée → repli presse-papier seul, géré en Task 7).

- [ ] **Step 3: Build via CI**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/InjectionController.kt app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt
git commit -m "refactor: accessibilité = injection seule + binder InjectionController"
git push
```
Vérifier que le build CI passe (compile). Les tests existants ne couvrent pas ce service ; vérifier juste la compilation.

---

## Task 6: Extraire `TranscriptionEngine`

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/TranscriptionEngine.kt`
- Modify: `app/src/test/kotlin/com/kafkasl/phonewhisper/PostProcessorTest.kt` (inchangé si PostProcessor inchangé)
- Create: `app/src/test/kotlin/com/kafkasl/phonewhisper/TranscriptionEngineTest.kt`

- [ ] **Step 1: Écrire le test (conversion PCM→float)**

Le point testable sans device : la conversion 16-bit PCM → FloatArray (extraite du service). Create `TranscriptionEngineTest.kt`:
```kotlin
package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionEngineTest {
    @Test
    fun pcm16ToFloat_convertsKnownSamples() {
        // 0x0000 -> 0.0 ; 0x00FF? little-endian: lo, hi
        // sample 32767 (0x7FFF) -> ~0.999 ; sample -32768 (0x8000) -> -1.0
        val pcm = byteArrayOf(
            0x00, 0x00,             // 0
            0xFF.toByte(), 0x7F,    // 32767
            0x00, 0x80.toByte()     // -32768
        )
        val f = TranscriptionEngine.pcm16ToFloat(pcm)
        assertEquals(3, f.size)
        assertEquals(0f, f[0], 1e-6f)
        assertEquals(32767f / 32768f, f[1], 1e-4f)
        assertEquals(-1f, f[2], 1e-6f)
    }
}
```

- [ ] **Step 2: Run le test (échec attendu)**

Run: `./gradlew testDebugUnitTest --tests "*TranscriptionEngineTest*"`
Expected: FAIL (TranscriptionEngine inexistant).

- [ ] **Step 3: Écrire `TranscriptionEngine.kt`**

Create `TranscriptionEngine.kt` (déplacer la logique depuis l'ancien service) :
```kotlin
package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

object TranscriptionEngine {

    private const val SAMPLE_RATE = 16000

    fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        return out
    }

    /** Résultat de transcription. */
    data class Result(val text: String?, val error: String? = null)

    /**
     * Transcrit un buffer PCM 16-bit. Bloquant — appeler hors thread principal.
     * Choisit local (sherpa) ou API selon prefs. (Logique reprise du service v1.)
     */
    fun transcribe(ctx: Context, pcm: ByteArray, local: LocalTranscriber?): Result {
        val prefs = prefs(ctx)
        val useLocal = prefs.getBoolean("use_local", true)
        return if (useLocal && local != null) {
            val samples = pcm16ToFloat(pcm)
            val text = local.transcribe(samples, SAMPLE_RATE)
            Result(text)
        } else {
            val apiKey = prefs.getString("api_key", "") ?: ""
            if (apiKey.isBlank()) return Result(null, "Set API key")
            val wav = WavWriter.encode(pcm)
            var result: Result = Result(null, "timeout")
            val latch = java.util.concurrent.CountDownLatch(1)
            TranscriberClient.transcribe(wav, apiKey) { r ->
                result = Result(r.text, r.error); latch.countDown()
            }
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            result
        }
    }

    fun loadLocal(ctx: Context): LocalTranscriber? {
        val modelName = prefs(ctx).getString("model_name", "") ?: ""
        return if (modelName.isBlank()) {
            val models = LocalTranscriber.availableModels(ctx)
            if (models.isNotEmpty()) LocalTranscriber.create(ctx, models.first()) else null
        } else LocalTranscriber.create(ctx, modelName)
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
}
```
> Note : on garde le nom de prefs `phonewhisper` pour ne pas casser les réglages existants (clé API, modèle).

- [ ] **Step 4: Run le test (succès attendu)**

Run: `./gradlew testDebugUnitTest --tests "*TranscriptionEngineTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/TranscriptionEngine.kt app/src/test/kotlin/com/kafkasl/phonewhisper/TranscriptionEngineTest.kt
git commit -m "feat: extract TranscriptionEngine + test conversion PCM"
```

---

## Task 7: OverlayService complet (états, transcription, injection, dégradation mic)

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt`
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/PersistencePrefs.kt`

- [ ] **Step 1: Helper de préférences (position + état armement)**

Create `PersistencePrefs.kt`:
```kotlin
package com.kafkasl.phonewhisper

import android.content.Context

class PersistencePrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)

    var buttonX: Int
        get() = p.getInt("btn_x", -1)
        set(v) { p.edit().putInt("btn_x", v).apply() }
    var buttonY: Int
        get() = p.getInt("btn_y", -1)
        set(v) { p.edit().putInt("btn_y", v).apply() }

    /** Dernière erreur de démarrage FGS (diagnostic auto-test). */
    var lastError: String?
        get() = p.getString("last_error", null)
        set(v) { p.edit().putString("last_error", v).apply() }

    /** Clamp aux bornes écran (résolution peut changer). */
    fun clampX(x: Int, w: Int, screenW: Int) = x.coerceIn(0, (screenW - w).coerceAtLeast(0))
    fun clampY(y: Int, h: Int, screenH: Int) = y.coerceIn(0, (screenH - h).coerceAtLeast(0))
}
```

- [ ] **Step 2: Test du clamp (unitaire pur)**

Create `app/src/test/kotlin/com/kafkasl/phonewhisper/PersistencePrefsTest.kt`:
```kotlin
package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
class PersistencePrefsTest {
    private val prefs = PersistencePrefs(ApplicationProvider.getApplicationContext())
    @Test fun clamp_keepsInBounds() {
        assertEquals(0, prefs.clampX(-50, 100, 1080))
        assertEquals(980, prefs.clampX(5000, 100, 1080))
        assertEquals(300, prefs.clampX(300, 100, 1080))
    }
}
```
(Robolectric est déjà permis par `isIncludeAndroidResources = true` ; si non présent, ajouter `testImplementation("org.robolectric:robolectric:4.13")` + `testImplementation("androidx.test:core:1.6.1")` dans `app/build.gradle.kts`.)

- [ ] **Step 3: Run (échec attendu), implémenter, run (succès)**

Run: `./gradlew testDebugUnitTest --tests "*PersistencePrefsTest*"` → FAIL puis PASS après création du fichier.

- [ ] **Step 4: Étendre `OverlayService` — états, armement micro conditionnel, transcription, injection**

Remplacer la version spike par la version complète. Points clés (règles Codex) :
- `startForeground(specialUse)` **toujours en premier** dans `onStartCommand`/`onCreate`.
- Armement micro (`promoteMic()`) appelé **uniquement** quand `MainActivity` signale qu'elle est visible (via une action d'intent `ACTION_ARM_MIC`).
- **Jamais** rappeler `startForeground` pour les updates de notif → utiliser `NotificationManager.notify()`.
- Si non armé, un tap affiche « ouvre WhisperPin pour activer le micro » et lance MainActivity.

Code (remplace le corps spike) :
```kotlin
class OverlayService : Service() {

    companion object {
        private const val TAG = "WhisperPin"
        private const val CHANNEL_ID = "whisperpin_overlay"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        const val ACTION_ARM_MIC = "com.uhama.whisperpin.ARM_MIC"
        @Volatile var micArmed = false
            private set
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING, MIC_UNARMED }

    private val prefs by lazy { PersistencePrefs(this) }
    private var state = State.MIC_UNARMED
    private var button: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcm: java.io.ByteArrayOutputStream? = null
    private var local: LocalTranscriber? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSpecialUse()                 // toujours OK
        showButton()
        kotlin.concurrent.thread { local = TranscriptionEngine.loadLocal(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ARM_MIC) promoteMic()
        return START_STICKY
    }

    private fun startForegroundSpecialUse() {
        try {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            // GARDÉ (Codex) : ne JAMAIS rester vivant sans startForeground confirmé,
            // sinon ForegroundServiceDidNotStartInTimeException crashe l'app.
            Log.e(TAG, "startForeground specialUse échec: ${e.javaClass.simpleName} → stopSelf")
            PersistencePrefs(this).lastError = e.javaClass.simpleName
            stopSelf()
        }
    }

    /** Promotion micro — APPELÉE UNIQUEMENT quand l'app est visible (ACTION_ARM_MIC). */
    private fun promoteMic() {
        if (micArmed) return
        try {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            micArmed = true
            setState(State.IDLE)
            Log.i(TAG, "Mic armé")
        } catch (e: Exception) {
            // SecurityException / ForegroundServiceStartNotAllowedException
            Log.e(TAG, "promoteMic échec: ${e.javaClass.simpleName}")
            micArmed = false
            setState(State.MIC_UNARMED)
        }
    }

    private fun updateNotif() {
        // JAMAIS startForeground ici → sinon le type microphone est effacé
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun onTap() {
        when (state) {
            State.MIC_UNARMED -> openAppToArm()
            State.IDLE -> startRec()
            State.RECORDING -> stopRec()
            State.TRANSCRIBING -> {}
        }
    }

    private fun openAppToArm() {
        toast("Ouvre WhisperPin pour activer le micro")
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startRec() {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: SecurityException) { toast("Mic refusé"); return }
        pcm = java.io.ByteArrayOutputStream()
        audioRecord!!.startRecording()
        setState(State.RECORDING)
        vibrate(20)
        kotlin.concurrent.thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) pcm?.write(buf, 0, n)
            }
        }
    }

    private fun stopRec() {
        setState(State.TRANSCRIBING)
        vibrate(20)
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        val data = pcm?.toByteArray() ?: ByteArray(0); pcm = null
        if (data.isEmpty()) { setState(State.IDLE); return }
        kotlin.concurrent.thread {
            val r = TranscriptionEngine.transcribe(this, data, local)
            main.post {
                val text = r.text
                if (!text.isNullOrBlank()) {
                    val injected = WhisperAccessibilityService.controller?.inject(text) ?: false
                    toast(if (injected) "Inséré" else "Copié (presse-papier)")
                } else toast("Erreur: ${r.error ?: "vide"}")
                setState(State.IDLE)
            }
        }
    }

    private fun setState(s: State) {
        state = s
        main.post {
            val color = when (s) {
                State.IDLE -> 0xDD1C1C1E.toInt()
                State.RECORDING -> 0xDDEF4444.toInt()
                State.TRANSCRIBING -> 0xDD6B6B6B.toInt()
                State.MIC_UNARMED -> 0xDD8A6D3B.toInt()
            }
            (button?.background as? GradientDrawable)?.setColor(color)
        }
    }

    private fun vibrate(ms: Long) {
        val v = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VibratorManager::class.java)).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        v?.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // showButton(): comme spike mais avec position mémorisée + drag + long-press (Task 11)
    // ... (voir Task 11 pour drag/edge-collapse/long-press) ...
}
```
(Imports à ajouter : `android.app.NotificationManager`, `android.content.pm.ServiceInfo`, `android.os.Build`, `android.os.Vibrator`, `android.os.VibratorManager`, `android.graphics.drawable.GradientDrawable`.)

- [ ] **Step 5: MainActivity arme le micro quand visible**

Dans `MainActivity.onResume()`, envoyer l'intent d'armement (app au premier plan = éligible) :
```kotlin
    override fun onResume() {
        super.onResume()
        if (android.provider.Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_ARM_MIC))
        }
    }
```
Retirer le bouton spike temporaire de la Task 3 (Step 3).

- [ ] **Step 6: Build + test on-device**

```bash
git add -A && git commit -m "feat: OverlayService complet (états, armement micro, transcription, injection, dégradation)"
git push
```
Vérifier on-device : ouvrir l'app (arme le micro) → background → dicter dans une autre app → texte inséré ou copié. Puis tester la dégradation : « Forcer l'arrêt » puis rouvrir → bouton revient, micro réarmé à l'ouverture.

---

# Phase D — Survie

## Task 8: BootReceiver

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/BootReceiver.kt`

- [ ] **Step 1: Écrire le receiver (type specialUse explicite)**

Create `BootReceiver.kt`:
```kotlin
package com.kafkasl.phonewhisper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("WhisperPin", "BootReceiver: ${intent?.action}")
        // Démarre l'overlay SANS micro (armement interdit depuis boot/background).
        // OverlayService.onCreate appelle startForeground(SPECIAL_USE) — jamais microphone ici.
        context.startForegroundService(Intent(context, OverlayService::class.java))
    }
}
```
> Le `OverlayService` ne promeut le micro que sur `ACTION_ARM_MIC` (app visible) → aucun risque de démarrer un FGS microphone au boot.

- [ ] **Step 2: Commit + test reboot**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/BootReceiver.kt
git commit -m "feat: BootReceiver relance overlay (specialUse) au boot/MAJ"
git push
```
Test on-device : redémarrer le téléphone → après déverrouillage, le bouton flottant doit réapparaître (en état « micro à réarmer », couleur ambre). Ouvrir l'app une fois → micro réarmé.

---

## Task 9: WatchdogWorker (diagnostic)

**Files:**
- Modify: `app/build.gradle.kts` (dépendance WorkManager)
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/WatchdogWorker.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt` (planifier le worker)

- [ ] **Step 1: Ajouter la dépendance WorkManager**

Dans `app/build.gradle.kts`, `dependencies { }` :
```kotlin
    implementation("androidx.work:work-runtime-ktx:2.10.5")
```

- [ ] **Step 2: Écrire le worker**

Create `WatchdogWorker.kt`:
```kotlin
package com.kafkasl.phonewhisper

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Diagnostic best-effort. Ne PEUT PAS ressusciter un FGS mort depuis l'arrière-plan (Android 12+).
 * Tente un démarrage gardé ; en cas d'interdiction, log seulement. La vraie récup = START_STICKY + boot.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            applicationContext.startForegroundService(
                Intent(applicationContext, OverlayService::class.java))
            Log.i("WhisperPin", "Watchdog: start tenté")
            Result.success()
        } catch (e: Exception) {
            Log.w("WhisperPin", "Watchdog: start interdit (${e.javaClass.simpleName})")
            Result.success() // ne pas réessayer en boucle
        }
    }
}
```

- [ ] **Step 3: Planifier le worker (unicité sans piège KEEP/REPLACE)**

Dans `MainActivity.onCreate`, après setup :
```kotlin
        val wm = androidx.work.WorkManager.getInstance(this)
        val infos = wm.getWorkInfosForUniqueWork("whisperpin-watchdog").get()
        val active = infos.any { !it.state.isFinished }
        if (!active) {
            val req = androidx.work.PeriodicWorkRequestBuilder<WatchdogWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(
                "whisperpin-watchdog",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        }
```

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/kafkasl/phonewhisper/WatchdogWorker.kt app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt
git commit -m "feat: WatchdogWorker diagnostic (WorkManager, unicité gardée)"
git push
```

---

# Phase E — UX & assistant

## Task 10: Assistant de config + détection HyperOS

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/OemSetup.kt`
- Create: `app/src/test/kotlin/com/kafkasl/phonewhisper/OemSetupTest.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt` (checklist UI)

- [ ] **Step 1: Test détection Xiaomi (pur)**

Create `OemSetupTest.kt`:
```kotlin
package com.kafkasl.phonewhisper

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class OemSetupTest {
    @Test fun detectsXiaomi() {
        assertTrue(OemSetup.isXiaomiBrand("Xiaomi", "POCO"))
        assertTrue(OemSetup.isXiaomiBrand("Xiaomi", "Redmi"))
        assertFalse(OemSetup.isXiaomiBrand("samsung", "samsung"))
    }
}
```

- [ ] **Step 2: Run (FAIL), implémenter `OemSetup.kt`, run (PASS)**

Create `OemSetup.kt`:
```kotlin
package com.kafkasl.phonewhisper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object OemSetup {

    fun isXiaomiBrand(manufacturer: String, brand: String): Boolean {
        val m = (manufacturer + brand).lowercase()
        return listOf("xiaomi", "poco", "redmi").any { m.contains(it) }
    }

    fun isXiaomi(): Boolean =
        isXiaomiBrand(android.os.Build.MANUFACTURER ?: "", android.os.Build.BRAND ?: "")

    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun isBatteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun openOverlaySettings(ctx: Context) = safe(ctx) {
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    }

    fun openBatterySettings(ctx: Context) = safe(ctx) {
        @android.annotation.SuppressLint("BatteryLife")
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}"))
        i
    }

    fun openAutostart(ctx: Context) = safe(ctx) {
        Intent().setClassName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")
    }

    fun openOtherPermissions(ctx: Context) = safe(ctx) {
        Intent().setClassName("com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", ctx.packageName)
    }

    fun openAppDetails(ctx: Context) = safe(ctx) {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"))
    }

    /** Lance l'intent ; en cas d'échec (composant MIUI absent) → repli détails app. */
    private inline fun safe(ctx: Context, build: () -> Intent) {
        try {
            ctx.startActivity(build().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }
}
```
Run: `./gradlew testDebugUnitTest --tests "*OemSetupTest*"` → PASS.

- [ ] **Step 3: Checklist UI dans MainActivity**

Ajouter une section « Persistance » avec une ligne par étape (réutiliser le helper `settingsRow` existant). Chaque ligne : libellé + sous-titre d'état (✓/✗) + onClick → `OemSetup.openXxx(this)`. Lignes :
1. Afficher par-dessus les autres apps → `OemSetup.openOverlaySettings` ; état `OemSetup.canDrawOverlays`.
2. Notifications (API 33+) → `requestPermissions(POST_NOTIFICATIONS)` ; état via `checkSelfPermission`.
3. Exemption batterie → `OemSetup.openBatterySettings` ; état `OemSetup.isBatteryUnrestricted`.
4. (si `OemSetup.isXiaomi()`) Autostart MIUI → `OemSetup.openAutostart` ; état non détectable (afficher « à activer »).
5. (si Xiaomi) Pop-up arrière-plan / autres permissions → `OemSetup.openOtherPermissions`.
6. (si Xiaomi) Verrouiller dans les récents → texte d'instruction (pas d'intent).
7. (si Xiaomi, « si présent ») « Boost speed / Lock apps » → texte d'instruction (non automatisable, à confirmer sur device).
8. (si Xiaomi, « si présent ») « Keep running after screen off » / prompt énergivore → texte d'instruction (à confirmer sur device).
Mettre à jour les états dans `onResume()` (refresh ✓/✗).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/OemSetup.kt app/src/test/kotlin/com/kafkasl/phonewhisper/OemSetupTest.kt app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt
git commit -m "feat: assistant config OEM + détection HyperOS"
git push
```

---

## Task 11: Améliorations bouton (position, drag, vibration, long-press, repli bord)

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt`

- [ ] **Step 1: `showButton()` complet — drag + tap + long-press + position mémorisée**

Remplacer `showButton()` par une version avec `OnTouchListener` (distingue tap / drag / long-press) et restauration de position via `PersistencePrefs` :
```kotlin
    private fun showButton() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val margin = (8 * dp).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDD8A6D3B.toInt()) }
            setPadding((12*dp).toInt(),(12*dp).toInt(),(12*dp).toInt(),(12*dp).toInt())
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.buttonX >= 0) prefs.clampX(prefs.buttonX, size, screenW) else screenW - size - margin
            y = if (prefs.buttonY >= 0) prefs.clampY(prefs.buttonY, size, screenH) else screenH / 2
        }

        var downX = 0; var downY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        var longPressed = false
        val longPress = Runnable { longPressed = true; vibrate(30); openApp() }

        img.setOnTouchListener { v, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = lp.x; downY = lp.y; touchX = ev.rawX; touchY = ev.rawY
                    moved = false; longPressed = false
                    main.postDelayed(longPress, 500); true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 10 * dp) {
                        moved = true; main.removeCallbacks(longPress)
                        lp.x = prefs.clampX((downX + dx).toInt(), size, screenW)
                        lp.y = prefs.clampY((downY + dy).toInt(), size, screenH)
                        wm.updateViewLayout(v, lp)
                    }; true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!moved && !longPressed) onTap()
                    else if (moved) {
                        // snap au bord + mémoriser
                        lp.x = if (lp.x + size/2 > screenW/2) screenW - size - margin else margin
                        wm.updateViewLayout(v, lp)
                        prefs.buttonX = lp.x; prefs.buttonY = lp.y
                    }; true
                }
                else -> false
            }
        }
        wm.addView(img, lp)
        button = img; params = lp
    }

    private fun openApp() = startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
```

- [ ] **Step 2: Repli sur le bord au repos (translucidité après inactivité)**

Ajouter un runnable d'inactivité qui réduit l'alpha à ~0.4 après 3 s sans interaction, restauré à 1.0 au `ACTION_DOWN` :
```kotlin
    private val collapse = Runnable { button?.animate()?.alpha(0.4f)?.setDuration(200)?.start() }
    private fun scheduleCollapse() { main.removeCallbacks(collapse); main.postDelayed(collapse, 3000) }
    private fun wake() { button?.animate()?.alpha(1f)?.setDuration(120)?.start(); scheduleCollapse() }
```
Appeler `wake()` dans `ACTION_DOWN` et `scheduleCollapse()` après `addView` et après chaque `setState`.

- [ ] **Step 3: Idempotence (anti double-add)**

En tête de `showButton()`, garder :
```kotlin
        if (button != null) return
```
Et rendre `onDestroy` tolérant :
```kotlin
    override fun onDestroy() {
        stopRec(); main.removeCallbacksAndMessages(null)
        try { button?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        button = null; super.onDestroy()
    }
```

- [ ] **Step 4: Commit + test on-device**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt
git commit -m "feat: bouton — drag, position mémorisée, vibration, long-press ouvre app, repli bord"
git push
```
Vérifier : déplacer le bouton (snap + mémorisé après reboot), appui long ouvre l'app, repli translucide après 3 s.

---

## Task 12: Écran auto-test persistance (debug)

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt`

- [ ] **Step 1: Section diagnostic**

Ajouter (visible si `BuildConfig.DEBUG`) une section affichant :
- FGS overlay actif ? (heuristique : `OemSetup.canDrawOverlays` + service lancé)
- Micro armé ? `OverlayService.micArmed`
- Accessibilité connectée ? `WhisperAccessibilityService.controller != null`
- Batterie exemptée ? `OemSetup.isBatteryUnrestricted(this)`
- Dernier kill : lire `ApplicationExitInfo` :
```kotlin
        val am = getSystemService(android.app.ActivityManager::class.java)
        val exits = am.getHistoricalProcessExitReasons(packageName, 0, 3)
        val lastExit = exits.firstOrNull()?.let { "reason=${it.reason} desc=${it.description}" } ?: "n/a"
```
Afficher ces valeurs dans des `TextView`, rafraîchies à `onResume`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt
git commit -m "feat: écran auto-test persistance (debug) + ApplicationExitInfo"
git push
```

---

# Phase F — Sécurité & finition

## Task 13: Redaction logcat (fuite de contenu de champ)

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt`

- [ ] **Step 1: Supprimer/rédiger les logs de contenu + gate BuildConfig.DEBUG**

Dans `logNode()` (et tout log d'injection), retirer `text=`, `desc=`/`contentDescription`, et les labels d'actions. Gater le reste derrière `BuildConfig.DEBUG`. Exemple :
```kotlin
    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "$prefix package=${node.packageName} class=${node.className} " +
            "focused=${node.isFocused} editable=${node.isEditable}")  // PAS de text/desc/labels
    }
```
Vérifier qu'aucun autre `Log.*` n'émet `node.text`, `result.text`, ou le texte transcrit. Le texte transcrit ne doit jamais être loggé.

- [ ] **Step 2: Grep de contrôle**

Run:
```bash
grep -rn "node.text\|contentDescription\|\.text}\|result.text" app/src/main/kotlin | grep -i "Log\."
```
Expected: aucune ligne de log n'émet du contenu de champ.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt
git commit -m "security: stop leaking field content (text/desc/labels) in logcat, gate logs behind BuildConfig.DEBUG"
git push
```

---

## Task 14: Fork, matrice de test 2 appareils, doc utilisateur

**Files:**
- Create: `README-WHISPERPIN.md` (guide install + config HyperOS)
- Modify: `docs/superpowers/specs/2026-05-31-phone-whisper-persistence-design.md` (résultats matrice)

- [ ] **Step 1: Fork + push (si pas déjà fait)**

```bash
cd /Users/ulliemaillot/dev_project/phone-whisper
gh repo fork kafkasl/phone-whisper --org Uhama91 --clone=false 2>/dev/null || gh repo create Uhama91/phone-whisper --private --source=. --remote=fork
git remote set-url origin "$(gh repo view Uhama91/phone-whisper --json url -q .url 2>/dev/null || echo git@github.com:Uhama91/phone-whisper.git)"
git push -u origin HEAD
```
Vérifier le build Actions vert + artefact APK.

- [ ] **Step 2: Guide utilisateur**

Create `README-WHISPERPIN.md` : install APK (depuis Actions), étapes config HyperOS dans l'ordre (overlay → accessibilité → batterie → autostart → pop-up arrière-plan → verrouiller dans les récents), et la note « après reboot, ouvrir l'app une fois pour réarmer le micro ».

- [ ] **Step 3: Matrice de test sur Poco F7 ET Pad 7**

Pour CHAQUE appareil, après config complète, exécuter et consigner (✓/✗) :
| Étape | Bouton présent | Dictée OK |
|---|---|---|
| Après activation initiale | | |
| App fermée (Accueil) | | |
| Balayage depuis les récents (app verrouillée) | | |
| Bouton 🧹 nettoyer RAM | | |
| Écran éteint 10 min | | |
| Reboot (bouton présent ; micro réarmé à l'ouverture) | | |
| Ouvrir l'app puis Accueil immédiat (reste MIC_UNARMED, pas de crash) | | |
| Arrêt via gestionnaire de tâches notif (échec attendu, guidage au relancement) | | |

Critère d'acceptation : toutes les lignes sauf la dernière = bouton présent + dictée OK (après réarmement micro pour reboot), **sur les deux appareils**.

- [ ] **Step 4: Consigner + commit final**

```bash
git add README-WHISPERPIN.md docs/superpowers/specs/2026-05-31-phone-whisper-persistence-design.md
git commit -m "docs: guide WhisperPin + résultats matrice de test 2 appareils"
git push
```

- [ ] **Step 5: Finir la branche**

Utiliser `superpowers:finishing-a-development-branch` pour décider merge/PR/cleanup.

---

## Self-Review (couverture spec)

- §3 Architecture (OverlayService FGS, accessibilité injection-only, binders) → Tasks 3,5,7. ✓
- §3.3 Règles micro (startForeground, notify, catch, dégradation) → Task 7 Steps 4-5. ✓
- §4 Flux données → Task 7. ✓
- §5 Persistance (START_STICKY, boot, watchdog, OEM) → Tasks 7,8,9,10. ✓
- §6 Manifest/permissions → Tasks 3,4. ✓
- §7 Assistant config (toutes lignes + repli) → Task 10. ✓
- §8 Améliorations bouton (position, vibration, long-press, repli) → Task 11. ✓
- §9 Sécurité logcat → Task 13. ✓
- §10 Tests (spike, auto-test, matrice) → Tasks 3,12,14. ✓
- §11 Build/distribution (CI, keystore, fork) → Tasks 1,2,14. ✓
- §13 Risques → couverts (dégradation Task 7, idempotence Task 11, repli intents Task 10). ✓

Aucun placeholder ; signatures cohérentes (`micArmed`, `controller`, `inject`, `ACTION_ARM_MIC`, `PersistencePrefs`).
