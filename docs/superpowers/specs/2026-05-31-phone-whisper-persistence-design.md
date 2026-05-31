# WhisperPin — Bouton de dictation overlay indestructible (Approche C)

- **Date** : 2026-05-31
- **Auteur** : Ullie (Uhama) + Claude
- **Produit** : **WhisperPin** — app **séparée** (nouvel `applicationId` `com.uhama.whisperpin`, label « WhisperPin »), fork de `kafkasl/phone-whisper`. S'installe **à côté** de l'app phone-whisper existante, sans conflit ni désinstallation.
- **Repo cible** : fork `Uhama91/phone-whisper` (origin re-pointé sur le fork).
- **Périmètre** : Bloc isolé n°1 « Persistance ». Fonctionnalités type Wispr Flow / Handy et audit sécurité complet = blocs ultérieurs.
- **Appareils cibles** : Xiaomi Poco F7 + Xiaomi Pad 7 (HyperOS / ex-MIUI, Android 15).
- **Révision** : v2.1 — intègre deux passes de review Codex (xhigh) du 2026-05-31 (architecture micro FGS + règles `startForeground`).

---

## 1. Problème

Le bouton micro flottant est dessiné en `TYPE_ACCESSIBILITY_OVERLAY` et créé dans
`WhisperAccessibilityService.onServiceConnected()`. Son existence est **liée au processus du service
d'accessibilité**. L'app n'a **aucun service au premier plan** ; le manifest ne déclare que
`RECORD_AUDIO` + `INTERNET`.

Sur HyperOS (gestionnaire mémoire très agressif) :
- Le bouton « nettoyer la RAM » 🧹 tue le processus → le service d'accessibilité meurt → le bouton
  disparaît.
- Sur le Pad 7 : le bouton meurt **dès** que l'app quitte le premier plan ; re-basculer
  l'accessibilité l'affiche **brièvement** puis il est retué.
- « Forcer l'arrêt » désactive le service d'accessibilité au niveau Android (règle OS).

**Une seule cause racine, un seul correctif pour les deux appareils.**

## 2. Critères de succès

1. On configure **accessibilité + permissions + réglages OEM UNE seule fois**.
2. Le bouton **persiste en permanence** sur toutes les apps et survit à : fermer l'app, balayer les
   récents, le bouton 🧹 RAM de HyperOS, écran éteint prolongé, et **redémarrage**.
3. Comportement **identique** sur Poco F7 et Pad 7, **prouvé par une matrice de test sur les deux
   appareils réels** (cf. §10) — la survie HyperOS n'est PAS garantie par AOSP, elle dépend des
   réglages OEM obligatoires.
4. **Les réglages OEM (autostart, sans restriction batterie, pop-up arrière-plan, verrouillage
   récents) sont des critères d'acceptation OBLIGATOIRES**, pas un simple onboarding optionnel.
5. Exceptions acceptées (limites Android, hors de notre contrôle) : « Forcer l'arrêt » manuel, et
   arrêt via le **gestionnaire de tâches de la notification FGS (Android 13+)**. Dans ces cas, au
   prochain lancement l'app lit `ApplicationExitInfo` et guide l'utilisateur vers la réactivation.

## 3. Architecture (Approche C — bouton + micro découplés vers le FGS)

### 3.1 Principe

Découpler **le bouton ET la capture micro** du service d'accessibilité. Les deux passent dans un
**service au premier plan** (`OverlayService`) — le composant le plus dur à tuer. Le service
d'accessibilité ne sert plus **qu'à injecter le texte**.

> **Changement clé vs v1 (suite à Codex)** : la capture micro NE reste PAS dans le service
> d'accessibilité. Raison : après avoir retiré la fenêtre overlay visible du service
> d'accessibilité, plus rien ne garantit l'accès micro en arrière-plan (restrictions while-in-use
> `RECORD_AUDIO`, API 28+). On utilise à la place le **chemin documenté et correct** : un FGS de type
> `microphone`.

### 3.2 Composants

| Composant | Statut | Rôle |
|---|---|---|
| `OverlayService` | 🆕 | Service premier plan `specialUse` **+** `microphone`. Héberge le bouton (`TYPE_APPLICATION_OVERLAY`), gère drag/tap/visuels/feedback/états, **et capture l'audio**. Notif `IMPORTANCE_MIN`. `START_STICKY`, `stopWithTask=false`. Expose un **binder local** (`OverlayBinder`). |
| `WhisperAccessibilityService` | ✏️ | **Ne garde que l'injection de texte** (champ focalisé + repli presse-papier). Perd l'hébergement du bouton ET la capture audio. Expose un **binder local** (`InjectionBinder`) consommé par `OverlayService`. |
| `TranscriptionEngine` | 🆕 (extrait) | Logique transcription (sherpa-onnx / Whisper) + post-traitement, extraite en composant partagé appelé par `OverlayService`. |
| `BootReceiver` | 🆕 | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `QUICKBOOT_POWERON` → démarre `OverlayService` (`specialUse` autorisé au boot). **`LOCKED_BOOT_COMPLETED` retiré** (nécessiterait direct-boot aware + device-protected storage). |
| `WatchdogWorker` | 🆕 | WorkManager périodique (~15 min). **Best-effort/diagnostic uniquement** : ne peut PAS démarrer un FGS mort depuis l'arrière-plan (Android 12+). Sert à détecter l'état et à poser une notification de récupération. La vraie auto-récupération = `START_STICKY` + boot receiver + réglages OEM. |
| Assistant de config | 🆕 (`MainActivity`) | Checklist détectée + intents directs vers les écrans HyperOS. |

### 3.3 Chemin micro (résolu)

- `OverlayService` déclare `android:foregroundServiceType="specialUse|microphone"` et les permissions
  `FOREGROUND_SERVICE_SPECIAL_USE` + `FOREGROUND_SERVICE_MICROPHONE` (+ `RECORD_AUDIO` runtime).
- Le service est **démarré pendant que l'app est au premier plan** (onboarding / activation) → le
  type `microphone` est établi validement (pas un démarrage en arrière-plan). Il reste ensuite
  vivant (`START_STICKY` + notif). **Tant que le FGS microphone vit, la capture marche sur tap à tout
  moment, même app en arrière-plan** — c'est précisément la raison d'être du type `microphone`.
- **Règles `startForeground` (précisées par Codex v2 — critiques)** :
  - **Armement** : la promotion au type `microphone` (`startForeground(specialUse|microphone)`) ne se
    fait QUE pendant que `MainActivity` est visible (ou sous une autre exemption while-in-use). AOSP
    re-vérifie l'éligibilité à **chaque** appel `startForeground()` ; un service jamais mort n'a PAS
    d'exemption permanente.
  - **Persistance du micro** : une fois armé, **ne JAMAIS rappeler `startForeground()`**. Le type
    `microphone` reste dans le masque tant que le FGS vit → la capture marche sur tap en arrière-plan.
  - **Mises à jour de notif** (états idle/recording) : via `NotificationManager.notify()` **jamais**
    `startForeground(specialUse)` seul — sinon le masque est écrasé et le type `microphone` est
    **supprimé**.
  - **Erreurs** : catcher `SecurityException` **et** `ForegroundServiceStartNotAllowedException`.
- **Dégradation après kill+restart en arrière-plan ou reboot** : `START_STICKY` / `BootReceiver`
  relancent le service en **`specialUse` seul** (démarrage micro interdit depuis l'arrière-plan/boot).
  **Le bouton persiste**, mais le micro entre en état « à réarmer ». Un tap affiche « ouvre WhisperPin
  une fois pour réactiver le micro » ; dès que `MainActivity` est visible, le micro est re-promu
  automatiquement. Le pack survie OEM rend les kills rares ; le coût reboot = un seul tap.
  **Dégradation explicitement acceptée et documentée pour l'utilisateur.**

### 3.4 Pont entre services (anti-race)

- Communication via **binders locaux explicites** (`OverlayBinder` / `InjectionBinder`), **pas** via
  un `companion.instance` statique (fragile, sans contrat de cycle de vie — relevé par Codex).
- `OverlayService` peut démarrer (boot/watchdog) **avant** la connexion du service d'accessibilité.
  Tout tap doit gérer l'état « injection indisponible » → le texte transcrit part **dans le
  presse-papier** + le bouton affiche « accessibilité inactive, tape pour réactiver » ouvrant les
  réglages.
- Création/suppression de l'overlay **idempotentes** : garde anti double-`addView`, nettoyage des
  callbacks `Handler`, `removeView` tolérant aux vues déjà détachées.

## 4. Flux de données (tap → texte)

1. Tap (OverlayService) → si type `microphone` actif : démarre `AudioRecord` directement dans
   l'OverlayService. Sinon → état « micro à réarmer ».
2. Bouton rouge + pulse + **vibration courte**. 2ᵉ tap → stop + transcription.
3. Transcription locale (sherpa-onnx) **ou** API Whisper (inchangé) → post-traitement optionnel.
4. `OverlayService` appelle `InjectionBinder.inject(text)` du service d'accessibilité → injection
   dans le champ focalisé ; **repli presse-papier** si injection échoue ou accessibilité absente.
5. Visuels idle/busy + **vibration de fin**.

## 5. Stratégie de persistance (couches)

1. **FGS `specialUse`+`microphone`** + notif `IMPORTANCE_MIN` + `START_STICKY` + `stopWithTask=false`
   → priorité processus élevée, survit au nettoyage RAM normal. **`START_STICKY` = auto-récupération
   primaire** (redémarrage initié par le système, exempté des restrictions de démarrage FGS).
2. **Receiver boot/update** → revient après reboot / mise à jour.
3. **Watchdog WorkManager** (diagnostic) → détecte l'état, pose une notif de récupération ; ne tente
   un (re)démarrage FGS que sous exemption réelle, sinon `catch ForegroundServiceStartNotAllowedException`.
4. **Réglages OEM (obligatoires, une fois)** : autostart MIUI, sans restriction batterie, pop-up
   arrière-plan, verrouillage récents, exemption optimisation batterie. **C'est la vraie défense
   anti-HyperOS.**

## 6. Permissions & manifest (ajouts)

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SPECIAL_USE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `SYSTEM_ALERT_WINDOW`
- `RECEIVE_BOOT_COMPLETED`
- `POST_NOTIFICATIONS` (Android 13+)
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- (conservé) `RECORD_AUDIO`, `INTERNET`

Déclarations :
- `<service android:name=".OverlayService" android:foregroundServiceType="specialUse|microphone"
  android:stopWithTask="false" android:exported="false">` + `<property
  android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
  android:value="persistent floating dictation button overlay"/>`.
- `<receiver android:name=".BootReceiver" android:exported="true">` (BOOT_COMPLETED /
  MY_PACKAGE_REPLACED / QUICKBOOT_POWERON). **Pas** de `LOCKED_BOOT_COMPLETED`.
  - ⚠️ Dans tout chemin boot/restart, `startForeground()` doit passer **explicitement**
    `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` — **jamais** le type par défaut du manifest (qui inclut
    `microphone`, interdit depuis `BOOT_COMPLETED` pour targetSdk 34+).
- Renommage produit : `android:label="WhisperPin"`, nouvel `applicationId`.

## 7. Assistant de configuration (« config une seule fois »)

Écran checklist dans `MainActivity`. Détecte Xiaomi/HyperOS via `Build.MANUFACTURER`/`Build.BRAND`
(POCO/Redmi/Xiaomi) → étapes MIUI affichées seulement là. Chaque ligne : libellé + état ✓/✗ (si
détectable) + bouton « Configurer ».

Lignes :
1. Permission micro (existant)
2. Service accessibilité (existant)
3. **Afficher par-dessus les autres apps** (`Settings.canDrawOverlays`, `ACTION_MANAGE_OVERLAY_PERMISSION`)
4. **Notifications** (`POST_NOTIFICATIONS`) — si refusé, **le FGS démarre quand même** mais la notif
   permanente est masquée du tiroir → la ligne le signale et propose d'accorder.
5. **Exemption optimisation batterie** (`isIgnoringBatteryOptimizations`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
6. **Autostart MIUI** (intent `com.miui.securitycenter` → `AutoStartManagementActivity`, repli détails app)
7. **Permission pop-up arrière-plan MIUI** (intent autres-permissions MIUI, repli détails app)
8. **Verrouiller dans les récents** (instruction visuelle — non automatisable)
9. **(Si présent sur HyperOS) « Keep running after screen off » / « Boost speed » / prompt
   énergivore** — connus sur MIUI, présence à confirmer sur HyperOS/Android 15 ; marqués « si
   présent », non automatisables, à vérifier sur les 2 appareils lors des tests.

Tout intent OEM est protégé `try/catch` → repli `ACTION_APPLICATION_DETAILS_SETTINGS` (les composants
MIUI varient selon les versions HyperOS). L'assistant affiche un état global « prêt / incomplet » et
**bloque la promesse de persistance tant que les étapes obligatoires ne sont pas faites**.

## 8. Améliorations du bouton (low-risk, dans ce bloc)

1. **Position mémorisée** : `x/y` sauvés dans SharedPreferences au relâcher ; restaurés au démarrage
   de l'OverlayService ; clamp aux bornes écran (résolution variable).
2. **Vibration au tap** : retour haptique court début/fin d'enregistrement (`VibratorManager` API 31+,
   repli `Vibrator`).
3. **Appui long = ouvrir l'app** : long-press → lance `MainActivity`. Distinct du tap (start/stop) et
   du drag (déplacement).
4. **Repli sur le bord au repos** : après un délai d'inactivité, le bouton se rétracte / devient
   translucide contre le bord le plus proche ; revient opaque au toucher.

## 9. Sécurité (passe ciblée — le reste reporté)

- 🔴 **Corrigé dans ce bloc** : `logNode()` logge `text`, `contentDescription` **et** les labels
  d'actions en INFO → fuite de contenu sensible (mots de passe, champs) dans logcat. On **supprime /
  rédige tout contenu de champ** dans les logs, et on **gate tous les logs structurels derrière
  `BuildConfig.DEBUG`**.
- 🟠 **Reporté (bloc sécurité)** : clé API OpenAI en clair dans SharedPreferences →
  `EncryptedSharedPreferences` / Keystore.
- 🟡 **Documenté (bloc sécurité)** : presse-papier conserve la dictée ; portée accessibilité large ;
  combo FGS+boot+overlay peut alerter Play Protect (OK pour sideload perso).

## 10. Tests & vérification

- Tests unitaires existants (`TranscriberClient`, `ModelDownloader`, `WavWriter`, `PostProcessor`)
  conservés et verts ; ajustés si la transcription est extraite en `TranscriptionEngine`.
- **🥇 PREMIÈRE étape d'implémentation = spike micro sur appareil réel** : prouver que la capture
  audio via `OverlayService` (FGS `microphone`) fonctionne en arrière-plan sur le Poco F7 **avant**
  de construire le reste. Vérifier aussi le comportement de re-promotion du type micro après un
  restart en arrière-plan (informe l'UX de dégradation §3.3).
- **Écran auto-test persistance** (debug) : FGS actif ? type micro actif ? accessibilité connectée ?
  overlay visible ? batterie exemptée ? autostart probable (lib `MIUI-autostart`) ? + dernier
  `ApplicationExitInfo` (raison du dernier kill).
- **Matrice manuelle OBLIGATOIRE (Poco F7 + Pad 7)** : activer une fois → fermer l'app → balayer
  récents → 🧹 RAM → écran éteint 10 min → **reboot** → arrêt via gestionnaire de tâches notif →
  vérifier à chaque étape : bouton présent + dictée OK (ou dégradation attendue documentée).

## 11. Build & distribution

- **GitHub Actions** : JDK 17 + Android SDK → `./gradlew assembleDebug testDebugUnitTest` → APK
  `whisperpin-debug.apk` en artefact à chaque push.
- **Keystore debug commité** (signature stable) → nos MAJ s'installent **par-dessus** sans
  désinstaller. Nouvel `applicationId` → **aucun conflit** avec l'app phone-whisper existante :
  WhisperPin s'installe à côté, pas de désinstallation requise.
- **Fork** `Uhama91/phone-whisper`, origin re-pointé sur le fork.

## 12. Hors périmètre (blocs ultérieurs)

- Fonctionnalités Wispr Flow / Handy (streaming, vocabulaire perso, commandes IA, historique,
  multi-langue, push-to-talk vs toggle…).
- Audit sécurité complet (chiffrement clé API, durcissement).
- Redesign visuel profond.
- Publication Play Store (signature release, justification politique FGS `specialUse`).

## 13. Risques & mitigations

| Risque | Mitigation |
|---|---|
| **Capture micro en arrière-plan invalide après découplage** | FGS type `microphone` (chemin documenté) ; **spike on-device en 1ʳᵉ étape** ; dégradation « réarmer » explicite |
| Re-promotion micro impossible après restart background | Bouton reste visible en `specialUse` ; état « ouvre l'app une fois » ; OEM pack rend les kills rares |
| `startForeground(specialUse)` seul efface le type `microphone` du masque | Mises à jour de notif via `NotificationManager.notify()` uniquement ; ne jamais rappeler `startForeground` une fois le micro armé |
| Promotion micro tentée en arrière-plan → `SecurityException` | Promotion uniquement quand `MainActivity` visible ; catch `SecurityException` + `ForegroundServiceStartNotAllowedException` |
| Survie HyperOS non garantie par AOSP | Réglages OEM = **critères d'acceptation obligatoires** + preuve matrice 2 appareils |
| Watchdog ne peut ressusciter un FGS mort | Rétrogradé en diagnostic ; `START_STICKY` + boot receiver = vraie récupération ; `catch` exception |
| Intents MIUI variables selon HyperOS | `try/catch` + repli détails app systématique |
| Arrêt via gestionnaire de tâches notif (A13+) / Force Stop | Acceptés ; `ApplicationExitInfo` au prochain lancement → guidage réactivation |
| Race OverlayService vs accessibilité au boot | Binders explicites + repli presse-papier + état « accessibilité inactive » |
| Fuites de vues WindowManager (multi-start) | Création/suppression overlay idempotentes |
| Re-signature CI casse l'install par-dessus | Keystore debug commité / `signingConfig` stable |
| Notif FGS masquée si `POST_NOTIFICATIONS` refusé | FGS démarre quand même ; ligne assistant dédiée |

---

## Résultat spike micro (Task 3) — GATE VERT 🟢

- **Date** : 2026-05-31
- **Appareil** : Xiaomi (modèle 25053PC47G, `onyx`, HyperOS Android 15)
- **Méthode** : OverlayService FGS `specialUse|microphone` démarré depuis l'app, app mise en arrière-plan, tap du bouton flottant.
- **Résultat** : capture audio en arrière-plan **fonctionnelle**. `maxAmp` mesuré jusqu'à **21791** (samples jusqu'à 357760 ≈ 22 s). 5 cycles d'enregistrement réussis.
- **Verdict** : **GATE VERT** — le chemin micro via FGS `microphone` est validé sur HyperOS. Approche C confirmée. On poursuit Phases C→F.
- **Observation UX** : les `Toast` d'une app en arrière-plan sont masqués par HyperOS → le retour utilisateur doit reposer sur le **changement d'apparence du bouton + vibration** (déjà prévu Task 7/11), pas sur des toasts.
