# DictAI — Durcissement du cœur de dictée inspiré de FluidVoice

- **Date :** 2026-08-14
- **Statut :** implémenté et vérifié automatiquement — validation sur appareils en attente
- **Périmètre :** refactor interne et fiabilité d’exécution, sans redesign ni changement du geste de dictée
- **Appareils cibles :** Poco F7 et Xiaomi Pad 7 sous HyperOS
- **Référence étudiée :** FluidVoice `main` au commit `ab7faa13e696423dded0119ea145b9023dfe1e63`

## 1. Contexte

DictAI dispose déjà de fondations robustes : transcription locale batch et streaming, moteurs sherpa-onnx et transcribe.cpp, installation atomique des modèles, validation SHA-256 des GGUF, nettoyage cloud optionnel protégé par Android Keystore et injection défensive dans les champs de texte.

La dette principale se concentre dans `OverlayService`, qui cumule neuf responsabilités : foreground service, notification, fenêtre overlay, gestes, capture audio, cycle de vie des moteurs natifs, transcription, post-traitement et injection. Cette concentration rend les changements de moteur, les erreurs audio et les transitions de cycle de vie difficiles à analyser et à tester.

L’étude de FluidVoice fait ressortir quatre mécanismes utiles et transférables :

1. un contrat unique devant tous les moteurs ASR ;
2. des barrières explicites avant de considérer une capture audio comme démarrée ;
3. un point de passage indépendant entre la transcription et l’injection ;
4. un cycle de vie vérifiable pour les services et les ressources à longue durée de vie.

FluidVoice contient également plusieurs fichiers de plusieurs milliers de lignes. Le but n’est donc pas de reproduire son architecture complète, mais d’adopter ses coutures techniques les plus utiles tout en réduisant la concentration de DictAI.

## 2. Objectifs

À la fin de ce lot :

1. `OverlayService` ne connaît plus les classes concrètes `LocalTranscriber` et `LiveStreamingTranscriber` ; il manipule un seul contrat `DictationAsrEngine` et une seule session `DictationAsrSession`.
2. Le choix batch/streaming et la traduction des résultats natifs sont confinés dans un adaptateur ASR testable.
3. Le service d’accessibilité s’enregistre dans un `InjectionGateway` indépendant. Une ancienne instance ne peut pas désenregistrer une instance plus récente.
4. Le démarrage d’`AudioRecord` est transactionnel : il valide la taille de tampon, l’état d’initialisation et l’état d’enregistrement avant d’ouvrir une session ASR ou de publier l’état `RECORDING`.
5. Après `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` ou le quick boot Xiaomi, DictAI relance uniquement le FGS `specialUse`. Le microphone reste désarmé jusqu’au prochain passage visible dans l’application.
6. Tous les comportements ci-dessus possèdent des tests JVM ; l’ensemble de la suite existante reste verte et l’APK debug est assemblé.

## 3. Non-objectifs

Ce lot n’ajoute pas :

- d’historique de dictées ou d’enregistrements audio ;
- de profils par application ;
- de nouveau moteur ou modèle ASR ;
- de nouvelle interface ou de nouveaux gestes ;
- de WorkManager pour les téléchargements ;
- de changement du nettoyage OpenRouter ;
- de publication, commit, push ou pull request ;
- de promesse de capture micro immédiatement après reboot, interdite par les règles Android relatives aux permissions « while in use ».

Ces sujets restent des lots séparés afin que l’APK issu de ce chantier soit comparable au comportement actuellement validé sur les deux appareils Xiaomi.

## 4. Architecture cible

```text
MainActivity / OnboardingActivity
            │ ACTION_ARM_MIC
            ▼
      OverlayService ───────────────► Overlay UI / notification
            │
            ├── RecordingStartupTransaction
            │
            ├── DictationAsrEngine ─► BatchAsrEngine (sherpa offline)
            │                      └► StreamingAsrEngine (sherpa/transcribe.cpp)
            │
            ├── vocabulaire + nettoyage cloud optionnel
            │
            └── InjectionGateway ───► WhisperAccessibilityService

BootReceiver ── specialUse only ───► OverlayService
```

### 4.1 Contrat ASR unifié

`DictationAsrEngine` possède une seule responsabilité : ouvrir une session de dictée pour le modèle déjà chargé.

```kotlin
internal interface DictationAsrEngine : Closeable {
    val modelName: String
    val mode: Mode

    fun start(
        language: DictationLanguage,
        onPreview: (committed: String, tentative: String) -> Unit,
    ): DictationAsrSession
}
```

`DictationAsrSession` reçoit les paquets PCM et produit exactement un résultat terminal :

```kotlin
internal interface DictationAsrSession {
    fun acceptPcm16(buffer: ByteArray, length: Int)
    fun finish(fullPcm: ByteArray): TranscriptionEngine.Result
    fun cancel()
    fun cancelAndAwait(): Boolean
}
```

Deux adaptateurs sont fournis :

- `BatchAsrEngine` : `acceptPcm16` est volontairement sans effet ; `finish` transmet le PCM complet à `TranscriptionEngine.transcribe`.
- `StreamingAsrEngine` : transmet les paquets à `LiveStreamingTranscriber.Session`, puis traduit `Success`, `Empty`, `Timeout` et `Failure` vers `TranscriptionEngine.Result`.

Une factory choisit l’adaptateur à partir du modèle. Cette factory possède des loaders injectables dans les tests afin de prouver qu’un modèle streaming ne charge jamais le moteur batch, et inversement.

### 4.2 Cycle de vie ASR

`ResidentEngine<DictationAsrEngine>` reste le propriétaire exclusif du moteur natif chargé. Lors d’un changement de modèle :

1. la session active est annulée et attendue ;
2. l’ancien moteur est fermé ;
3. le nouveau moteur est ouvert hors du thread principal ;
4. la publication est refusée si le service a été détruit entre-temps.

Le service ne conserve plus deux références concurrentes `local` et `streamingLocal`. Il conserve seulement :

- `asrEngine: DictationAsrEngine?` ;
- `asrSession: DictationAsrSession?` ;
- `loadedModelName: String?`.

`RecordingStartGate` est adapté au même vocabulaire : il ne reçoit plus deux indicateurs batch/streaming et ne connaît plus `LiveStreamingTranscriber`. Il décide seulement à partir de la présence du moteur unifié et des autres préconditions existantes. Le mode batch ou streaming utilisé pour les logs est capturé dans l’instantané immuable de chaque enregistrement.

### 4.3 Registre d’injection

Il n’est pas possible d’exposer un binder local personnalisé depuis `AccessibilityService`, car Android rend `AccessibilityService.onBind()` final et réserve cette liaison au système.

Le pont devient donc un registre applicatif minimal :

```kotlin
internal object InjectionGateway {
    fun register(controller: InjectionController)
    fun unregister(controller: InjectionController)
    fun current(): InjectionController?
}
```

Le désenregistrement utilise une comparaison d’identité : la destruction tardive d’une ancienne instance du service ne doit jamais effacer la nouvelle. `OverlayService`, `MainActivity` et `OnboardingActivity` ne référencent plus le `companion object` du service d’accessibilité.

Le comportement de sécurité ne change pas :

- cible sensible ou inconnue : aucun nettoyage cloud et aucun fallback presse-papiers automatisé ;
- accessibilité absente : copie finale dans le presse-papiers avec feedback existant ;
- injection directe réussie : aucune copie résiduelle.

### 4.4 Transaction de démarrage audio

Une couture JVM générique, `RecordingStartupTransaction`, garde les objets `AudioRecord` et `DictationAsrSession` locaux tant que le démarrage complet n’a pas réussi. Avant de passer à `RECORDING`, elle vérifie successivement :

1. `AudioRecord.getMinBufferSize(...) > 0` ;
2. la construction de `AudioRecord` ne lève aucune exception ;
3. `audioRecord.state == AudioRecord.STATE_INITIALIZED` ;
4. `startRecording()` réussit ;
5. `audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING` ;
6. l’ouverture de la session ASR réussit.

En cas d’échec de construction, de démarrage ou d’ouverture de session, la session éventuellement créée est annulée, l’objet audio est arrêté si nécessaire puis libéré, et l’état reste `IDLE`. Aucun objet n’est publié dans les champs du service et aucun thread lecteur ne démarre. La publication et le lancement du lecteur n’ont lieu qu’après un résultat transactionnel `Started` ; si ce dernier lancement échoue, le service annule la session, libère l’enregistreur et remet ses champs à zéro.

Le lot ne bloque pas le thread principal en attendant un premier paquet PCM. Une véritable barrière « premier PCM reçu » nécessitera l’extraction complète de la capture audio dans un lot ultérieur.

### 4.5 Retour après reboot et mise à jour

`BootReceiver` accepte uniquement :

- `Intent.ACTION_BOOT_COMPLETED` ;
- `Intent.ACTION_MY_PACKAGE_REPLACED` ;
- `android.intent.action.QUICKBOOT_POWERON`.

Une politique pure transforme l’action reçue en un descripteur de redémarrage dépourvu d’action de service. Le receiver Android construit ensuite l’intent explicite vers `OverlayService` et appelle `ContextCompat.startForegroundService`. Cette séparation permet de tester le contrat sans Robolectric. `OverlayService.onCreate()` appelle déjà `startForeground` avec le type explicite `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`. Le receiver n’envoie jamais `ACTION_ARM_MIC`.

Le receiver est déclaré avec `android:exported="false"` : les diffusions système restent recevables, tandis qu’une application ordinaire ne peut pas invoquer directement ce composant non exporté.

Cette séparation est obligatoire : Android 14+ interdit la création d’un FGS microphone depuis l’arrière-plan ou `BOOT_COMPLETED`, hors exemptions limitées. Après reboot, la pastille peut revenir, mais le micro reste dans l’état ambre « ouvrir l’application pour l’activer ».

Le démarrage est entouré d’un `try/catch` et ne fait pas planter le processus si HyperOS refuse le lancement.

## 5. Concurrence et invariants

Les invariants suivants doivent rester vrais :

1. une seule session ASR peut être active ;
2. un moteur remplacé est fermé avant l’ouverture du suivant ;
3. `AudioRecord` n’est jamais libéré pendant que le thread lecteur est encore vivant ;
4. un instantané du PCM n’est pris qu’après l’arrêt du lecteur ;
5. une session annulée ne peut pas publier un texte final ;
6. une ancienne instance d’accessibilité ne peut pas retirer la nouvelle du registre ;
7. le receiver de boot ne peut pas armer le microphone ;
8. l’overlay reste idempotent et son comportement visuel ne change pas.

## 6. Gestion des erreurs et confidentialité

- Les erreurs natives restent attrapées avec `Throwable` aux frontières JNI.
- Les logs n’incluent ni audio, ni texte dicté, ni clé, ni contenu de champ.
- Les erreurs exposées à l’utilisateur restent génériques et actionnables.
- Le nettoyage cloud reste fail-open : son échec conserve le texte local.
- Aucun contenu de dictée supplémentaire n’est persisté par ce lot.

## 7. Compatibilité et migration

- Aucun changement de `applicationId`, de signature, de version de modèle ou de format de préférences.
- L’artefact de test passe de `versionCode 8` / `versionName 0.5.4-wp` à `versionCode 9` / `versionName 0.5.5-wp` pour permettre une mise à jour clairement identifiable par-dessus l’installation actuelle.
- Aucun changement des répertoires de modèles existants.
- Aucun changement des gestes utilisateur : tap, double-tap, appui long ou glisser-déposer.
- Le receiver de boot est un ajout compatible avec une installation par-dessus la version actuelle.
- Les noms historiques de préférences `phonewhisper` et `whisperpin` ne sont pas unifiés dans ce lot afin d’éviter une migration non liée.

## 8. Stratégie de test

### 8.1 Tests JVM

- La factory choisit exclusivement le backend batch ou streaming attendu.
- La finalisation batch transmet exactement le PCM complet à son backend.
- Les quatre résultats de finalisation streaming sont traduits correctement.
- `cancel` et `cancelAndAwait` sont chacun transmis à la session native.
- `RecordingStartGate` ne dépend plus des classes ou modes ASR concrets.
- `InjectionGateway.unregister(old)` ne retire pas `new`.
- Le registre revient à `null` après le désenregistrement de l’instance courante.
- La transaction de démarrage audio couvre les exceptions de construction et de démarrage, la libération de l’enregistreur, l’absence de session sur échec audio et le nettoyage si l’ouverture ASR échoue.
- La politique de boot accepte exactement les trois actions prévues et produit un descripteur sans action d’armement du micro.
- Les tests existants de stop audio, de streaming, d’injection, de modèle et de sécurité restent verts.

### 8.2 Vérification de build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Le build doit produire `app/build/outputs/apk/debug/app-debug.apk` sans échec.

### 8.3 Acceptation manuelle après livraison

Cette matrice exige les appareils physiques et constitue l’acceptation post-livraison de l’APK, pas un prérequis que le build automatisé peut prétendre avoir satisfait. L’APK est livré avec la checklist suivante pour le test matinal.

Sur le Poco F7 puis le Pad 7 :

1. installer l’APK par-dessus la version existante ;
2. ouvrir DictAI une fois pour armer le micro ;
3. tester une dictée batch puis une dictée streaming ;
4. vérifier aperçu, transcription finale et injection ;
5. balayer l’application des récents et dicter depuis une autre application ;
6. redémarrer l’appareil ;
7. vérifier que la pastille revient en mode micro désarmé ;
8. ouvrir DictAI, puis vérifier qu’une dictée fonctionne à nouveau ;
9. changer de modèle et confirmer que le nouveau moteur se recharge sans crash.

## 9. Critères d’acceptation

Le livrable automatisé est prêt pour le test appareil si :

- tous les tests JVM sont verts ;
- l’APK debug est assemblé ;
- `OverlayService` ne référence plus directement `LocalTranscriber`, `LiveStreamingTranscriber` ni `WhisperAccessibilityService.controller` ;
- le service d’accessibilité utilise `InjectionGateway` ;
- le manifest déclare `BootReceiver` avec `android:exported="false"` et les trois actions prévues ;
- le receiver ne transmet jamais `ACTION_ARM_MIC` ;
- un démarrage audio invalide ne lance ni lecteur ni session ASR ;
- aucun changement observable des gestes et visuels existants n’est introduit ;
- aucune modification n’est commitée ou poussée sans autorisation explicite.

L’acceptation complète du comportement HyperOS sera acquise après l’exécution de la matrice §8.3 sur le Poco F7 et le Pad 7.

## 10. Risques et réponses

| Risque | Réponse |
|---|---|
| Régression native lors de l’unification batch/streaming | Adaptateurs minces, tests de sélection et traduction, conservation des moteurs existants |
| Session ancienne encore active pendant un changement de modèle | `cancelAndAwait` avant fermeture et remplacement du moteur résident |
| Course entre deux instances du service d’accessibilité | Désenregistrement par comparaison d’identité |
| Crash ou fuite `AudioRecord` pendant un démarrage partiel | Transaction testable, publication tardive et nettoyage symétrique de chaque ressource acquise |
| Démarrage microphone illégal au boot | `specialUse` uniquement ; armement réservé à l’activité visible |
| HyperOS refuse le FGS au boot | Exception absorbée et réarmement au prochain lancement visible |
| Copie de code GPLv3 depuis FluidVoice | Réimplémentation indépendante des concepts ; aucun code FluidVoice copié |
