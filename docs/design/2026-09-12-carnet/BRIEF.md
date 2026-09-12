# Préparation visuelle DictAI — 12 septembre 2026

## Objectif de cette étape

Préparer trois propositions ImageGen indépendantes pour l’écran principal mobile
DictAI, avant toute modification de code. L’image sélectionnée deviendra la
référence visuelle de l’implémentation fidèle. Chaque surface future suivra le
même principe : image générée d’abord, choix visuel, puis réalisation.

## Demande produit

- Repenser l’application principale et ses réglages, puis l’overlay.
- Prévoir le parcours notes, ajout de photo, partage et export PDF/HTML.
- Donner une direction carnet/bloc-notes/cahier d’écriture, douce et épurée,
  aux formes arrondies attrayantes, avec une touche technologique discrète et
  pragmatique.
- Faire vivre l’identité de boucles manuscrites cursives synchronisées avec la
  voix.
- Dans l’overlay futur, retirer « Modifier » car agrandir/réduire couvre déjà
  ce besoin.
- Retirer la rangée « Message / Écoute en cours / En pause ».
- Afficher le format choisi (par exemple « Texte sans LLM ») en haut, à côté
  d’un témoin rouge lumineux animé d’enregistrement et d’un signe de pause
  calligraphique distinct.
- Ouvrir la préparation et l’aperçu HTML/PDF par agrandissement/transition dans
  l’overlay, avec retour au contexte de note, plutôt qu’une fenêtre séparée.

## Références observées

- `ThemeTokens.kt` : thème actuel « carnet sombre », fond charbon chaud
  `#201F1C`, surfaces `#2B2925`/`#35322D`, encre crème `#F5EEE4`, sauge
  `#AFD0AD`, rouge `#E89B89`.
- `NotebookBackgroundDrawable.kt` : lignes horizontales réglées tous les
  34dp et filet de marge vert à environ 30dp.
- `MainActivity.kt` : écran très long en défilement, en-tête DictAI en Caveat,
  gros bouton d’activation, statut, installation, modèles locaux, réglages,
  notes, diagnostic et formats ; les rangées actuelles sont des surfaces
  arrondies séparées.
- `CursiveWaveView.kt` : huit boucles cursives défilantes, amplitude liée au
  niveau micro, calmes au repos.
- `OverlayService.kt` : pastille 74×44dp avec onde cursive, panneau séparé,
  gestes haut/bas/gauche, édition du texte, captures et partage/export.
- `docs/logo.png` a été visualisé. C’est l’ancien repère bleu/blanc de
  Phone Whisper, pas une capture de l’interface actuelle ; il ne doit servir
  qu’à rappeler le principe du microphone si nécessaire, sans imposer son bleu.

## Captures et environnement

Aucune capture actuelle fiable n’a pu être produite. Un essai borné a installé
dans le cache isolé `/home/ullie/.cache/dictai-build-tools/sdk` les
`platform-tools` et l’émulateur officiels, puis a lancé l’AVD jetable
`dictai-api30` sur le port 5556 avec Android 30 Google APIs, 390×844 px, densité
160, `-accel off`, SwiftShader et sans audio. L’image expose bien
`x86_64,x86,arm64-v8a,...` et `libndk_translation.so`, donc l’APK arm64 actuel
était compatible sur le papier.

Le premier démarrage logiciel a toutefois déclenché à répétition le watchdog
Android : `system_server` restait bloqué pendant l’initialisation/migration des
permissions puis dans `AudioService.readPersistedSettings` sous TCG sans KVM.
Le framework a fini par écrire naturellement
`runtime-permissions.xml` en version 8 avec le fingerprint et
`?pc_version=300900706` attendus ; ce fichier a été sauvegardé avant l’arrêt et
aucune donnée de l’application ni source n’a été modifiée. Un arrêt/redémarrage
du framework et plusieurs relances bornées ont été essayés, sans rendre le
runtime stable. L’AVD et les processus QEMU/emulator ont ensuite été arrêtés et
vérifiés absents ; aucune capture ne doit être présentée comme une vue de
l’application actuelle.

La tâche `:app:assembleDebug` avait produit l’APK essayé, identifié comme
`0.9.5-wp`, code 34, applicationId `com.uhama.whisperpin`, avec la date du
fichier au 12 septembre à 08:52. Le SHA-256 calculé sur ce fichier est
`183e8b91ae13d0e960e4d022ea036571dfcfa7201733277fb3c9ecbbaf9c7fa7`. La
soumission à `adb install` n’a jamais abouti : le premier essai a rencontré
`package` indisponible, puis `StorageManager.getVolumes()` nul avant que le
framework ne soit stable. MainActivity et l’overlay DictAI n’ont donc pas été
lancés et aucun test de rendu runtime n’a eu lieu. Les captures à obtenir
lorsqu’un appareil ou un runtime stable sera accessible sont :

1. écran principal — haut et sections de réglages ;
2. overlay compact en enregistrement, en pause et agrandi ;
3. liste/édition de notes avec vignettes ;
4. appareil photo intégré ;
5. préparation, aperçu et partage/export PDF/HTML.

Les références ImageGen sont donc ancrées dans le code et ses tokens, pas dans
une capture de runtime. Le SDK Android cache fournit plateforme 34, build-tools,
NDK/CMake, JDK 17 et le wrapper Gradle ; la compilation existante est
disponible. Le rendu visuel réel sur appareil reste à valider plus tard.

## Phase 1 — accueil, réglages et thèmes

La référence sélectionnée `concepts/ivoire-encre-sauge-v2.png` a été réalisée
avant cette implémentation, puis déclinée en
`selected/main-sombre.png`, `selected/preferences-clair.png`,
`selected/dictee-clair.png` et `selected/mise-en-forme-clair.png`. L’accueil
réorganise l’écran long en quatre destinations conservant les fonctions
existantes : accueil, Dictée (langue, modèles vocaux, nombres, transcription,
nettoyage léger, espace final, vocabulaire), Mise en forme (formats, moteur,
modèle cloud, clé et diagnostics) et Préférences (apparence, installation,
diagnostics). Les labels décrivent les comportements actuels ; aucune capacité
Gemma n’est annoncée hors build prototype.

`ThemeMode` persiste `Système`, `Clair` ou `Sombre` dans les préférences. La
résolution de palette respecte un choix explicite même si la configuration
système est opposée ; l’overlay reçoit une action de rafraîchissement et
retinte ses vues, menus et dialogues en place pour ne pas interrompre une
session, un texte, une note ou une sélection. Le service conserve son état lors
d’un changement jour/nuit ; les dimensions et le contexte de dictée restent
gérés par son chemin de configuration existant.

Le checkpoint de la phase 1 avait réussi avec 457 tests ; son APK et son hash
sont historiques et ne décrivent plus l’artefact courant. La validation finale
après la phase 2 est reportée ci-dessous. Aucun APK de cette série n’a été
installé sur un appareil : l’installation et le lancement DictAI n’ont pas été
validés, et il n’y a donc aucun résultat de rendu runtime à présenter.

## Directions générées

Les trois propositions finales observées sont conservées dans
`docs/design/2026-09-12-carnet/concepts/` :

- `ivoire-encre-sauge-v2.png` : page ivoire réglurée très légère, action du
  bouton flottant, Mes notes mise en avant et trois regroupements fonctionnels.
- `carnet-nocturne.png` : thème charbon des `ThemeTokens`, encre crème, sauge et
  bleu discret, avec une boucle cursive souple et la même hiérarchie factuelle.
- `feuille-claire-structuree.png` : feuille claire modulaire, plus pragmatique,
  avec notes et export PDF/HTML uniquement dans le parcours Mes notes.

Les prompts ciblaient un canevas de contenu applicatif `390 × 844` (la densité
160 de l’AVD correspondait à cette cible en dp). L’outil ImageGen a livré ces
PNG en `853 × 1844` pixels, au même ratio portrait ; cette taille réelle est à
prendre en compte lors de la préparation d’une référence d’implémentation.

Les fichiers `ivoire-encre-sauge.png`, `carnet-nocturne-v1.png` et
`feuille-claire-structuree-v1.png` sont des brouillons ImageGen conservés pour
traçabilité ; ils ne sont pas des choix finaux. Les versions finales évitent le
faux libellé « Formats / format d’export », les slogans et l’inventaire de six
cartes. Elles montrent « Français · Texte sans LLM » comme aperçu de réglage,
et « Texte · Liste · Mail » comme choix de mise en forme. Aucune capture Android
ou référence du vieux logo n’a été jointe à ImageGen.

Après le retour sur l’APK réel, deux révisions ImageGen de l’accueil ont été
produites avant les corrections de composition :
`selected/main-clair-revision-v4.png` et
`selected/main-sombre-revision-v3.png`. Elles retirent le CTA et la carte d’état
permanents, donnent de l’air au logo DictAI entier et à une onde cursive ample,
et répartissent Mes notes et les trois accès Réglages sur la hauteur utile.
Elles prolongent la même direction ivoire/sauge et charbon, sans nouvelle
destination ni contenu fictif ; elles servent de références image-first de la
version finale de l’accueil.

## Déclinaisons générées pour la suite

Après le choix de la direction ivoire/encre/sauge, cinq interfaces ont suivi le
même workflow image-first et sont conservées dans
`docs/design/2026-09-12-carnet/selected/` :

- `overlay-clair-enregistrement.png` : panneau live overlay clair, format
  `Texte sans LLM`, témoin rouge, transcript et trois actions capture/photo/
  partage ; un seul contrôle de taille dans l’en-tête.
- `overlay-sombre-pause.png` : même panneau en charbon, signal pause cursif à
  deux traits et aucun texte d’état.
- `notes-clair.png` : liste Mes notes, nouvelle note, dates `12 sept. 2026` et
  actions existantes de renommage, partage/export et suppression.
- `photo-clair.png` : viseur compact avec `Photo 1 · cadrer puis photographier`,
  `Prendre la photo` et `Annuler`.
- `export-clair.png` : préparation et aperçu dans le panneau overlay agrandi,
  retour à la note, choix PDF/HTML/Texte et images, puis Enregistrer/Partager.

Les fichiers overlay suffixés `-v1` gardent les premières sorties qui avaient
un contrôle de taille dupliqué ; ils ne servent pas de référence finale. Toutes
les maquettes font `853 × 1844` pixels au ratio de la cible `390 × 844 dp`.
Les contenus de notes et les images affichés sont des exemples de composition ;
l’implémentation doit lire les notes réellement stockées et conserver l’état
réel.

## Phase 2 — overlay, notes, photo et export

La direction sélectionnée a été implémentée dans les surfaces existantes, sans
injecter les exemples des maquettes dans les données. `OverlayService.kt`
conserve la session de dictée, le texte éditable, la sélection, les vignettes et
les actions Insérer/Terminer des notes. Son en-tête affiche le format choisi,
un indicateur visuel/enregistrement accessible et une seule commande
agrandir/réduire avec Masquer ; l’ancienne rangée de statuts et Modifier ont
disparu. La transition compact/agrandi interpole brièvement les bornes de la
fenêtre, respecte les animations système et s’annule sur repositionnement,
configuration ou destruction.

`OverlayExportController.kt` et `OverlayExportPanel.kt` préparent PDF, HTML et
Texte et images hors du thread UI, présentent l’aperçu dans `livePanel`,
bornent la rasterisation PDF à une page, détruisent les WebView/bitmaps à la
fermeture et invalident les résultats tardifs. Le texte d’aperçu signale une
troncature locale éventuelle tandis que les fichiers exportés restent complets.
`NoteExportActivity.kt` ne sert plus qu’au pont transparent vers les sélecteurs
Android Enregistrer/Partager ; chaque retour porte un jeton d’export afin qu’un
ancien picker ne puisse pas restaurer un nouveau contexte de note. Notes et
photo gardent leur stockage, Camera2, permissions, orientation et actions
existants, avec la palette claire/sombre/système appliquée en place.

## Validation finale et limite runtime

Commande exécutée avec le SDK/JDK isolés et le JDK 21 requis par la dépendance
Gemma :
`./gradlew --no-daemon --max-workers=2 -Djavax.net.ssl.trustStore=/home/ullie/.cache/dictai-build-tools/cacerts :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
Résultat : 472 tests unitaires, 0 échec, 0 erreur, 0 test ignoré ; les APK
debug et debugAndroidTest ont été assemblés avec succès, et
`git diff --check` reste propre. L’APK courant est
`app/build/outputs/apk/debug/app-debug.apk`, 90 427 739 octets, SHA-256
`60a8513b15f90ff75006e58565fc4e2fe7bd3bf30ee31b7c67e6abb586ed36ef`.
L’APK de tests est `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
994 578 octets, SHA-256
`a327b706eb901943799a50e4e60307762bd8998ebd7d6e865b999614978925e6`.

Le correctif photo est couvert par trois tests Robolectric natifs : Light et
Dark construisent `NoteCameraActivity` avec le wrapper arrondi sans fond sur la
`TextureView`, et un test de framework reproduit précisément le
`UnsupportedOperationException` déclenché par l’ancien `TextureView.background`.
Le teardown détruit réellement les `ActivityController`. Les rendus Canvas
natifs hors appareil sont archivés dans
`docs/design/2026-09-12-carnet/qa-renders/` : `main-light.png` et
`main-dark.png` font 390 × 844 pixels, `main-small-large-font.png` fait 320 ×
640 pixels avec `fontScale` 1,3, et `wave-compact.png` fait 148 × 32 pixels
(repos puis voix). Le contrôle compact avance explicitement 12 ticks afin de
vérifier l’amplitude, les bords fondus et l’absence de fin prématurée ; il ne
mesure pas le timing `Choreographer` d’un appareil.

Ces rendus natifs et tests ne remplacent pas un test Android réel de
`PdfRenderer`, `WebView`, `WindowManager`, Camera2, sélecteur système,
TalkBack, clavier ou rotation. Le contrôle design QA reste donc bloqué :
l’émulateur logiciel API 30 sans KVM a redémarré le framework sous watchdog et
aucun appareil USB/wifi stable n’était disponible. Aucun lancement DictAI,
screenshot de runtime ou interaction matérielle n’est présenté comme réussi.
Le détail est consigné dans [`design-qa.md`](../../../design-qa.md).

## État et garde-fous

- La préparation visuelle initiale n’a modifié aucun code ; la phase 1 ci-dessus
  a ensuite modifié uniquement les fichiers Kotlin/ressources nécessaires à
  l’accueil, aux réglages et aux thèmes, en conservant les documents
  préexistants.
- Les références PNG, leurs prompts et cette note restent dans
  `docs/design/2026-09-12-carnet/`.
- Les modifications préexistantes `CLAUDE.md` et
  `docs/2026-09-07-voix-personnelle-et-gestes.md` sont à préserver.
- La publication de la branche est autorisée après revue finale ; aucune release,
  fusion vers `main`, installation sur téléphone réel ou envoi de messages n’est
  prévu.
- Les trois images doivent être indépendantes, en contenu applicatif mobile
  `390 × 844`, sans bezel, barre système, horloge ou chrome de navigateur.
- La génération utilise l’outil ImageGen intégré ; le nom d’un modèle ImageGen
  récent peut documenter le contexte, mais le modèle exact de l’outil intégré
  n’est pas sélectionnable ici et ne doit pas être affirmé.
