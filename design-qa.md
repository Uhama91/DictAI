# Design QA DictAI — 12 septembre 2026

## Statut

**BLOQUÉ — aucune validation visuelle runtime passée.**

`final result: blocked`

Sources de vérité visuelles :
`docs/design/2026-09-12-carnet/concepts/ivoire-encre-sauge-v2.png` et les
références approuvées sous
`docs/design/2026-09-12-carnet/selected/` (`main-clair-revision-v4.png`,
`main-sombre-revision-v3.png`, `main-sombre.png`,
`preferences-clair.png`, `dictee-clair.png`, `mise-en-forme-clair.png`,
`overlay-clair-enregistrement.png`, `overlay-sombre-pause.png`,
`notes-clair.png`, `photo-clair.png`, `export-clair.png`). Le screenshot de
l’implémentation est indisponible ; la comparaison pleine vue et par régions
reste impossible sans runtime.

Le rendu Canvas natif hors appareil valide visuellement la composition de
l’accueil clair/sombre, le logo entier, l’espacement des cartes et l’onde
compacte au repos puis sous niveau vocal simulé ; ces rendus sont archivés
ci-dessous. La validation matérielle complète reste bloquée. Pour les autres
surfaces — réglages, overlay, notes, photo et export — la typographie,
l’espacement, les couleurs, les images, la copie et les interactions restent
**NON VALIDÉS** sur Android réel. La revue statique et les tests attestent le
chemin de code, pas la fidélité runtime de ces surfaces.

La compilation, les tests unitaires et la revue statique du diff sont passés.
Aucune capture de l’application modifiée sur Android n’est disponible. Le seul essai
de runtime a utilisé l’AVD jetable `dictai-api30` avec Android 30 Google APIs,
390 × 844 px, densité 160 et accélération logicielle `-accel off`. La machine
n’a pas KVM ; le premier boot a déclenché le watchdog Android dans
`system_server`, puis ADB n’a pas fourni un framework stable pour installer et
lancer DictAI. L’AVD et ses processus ont été arrêtés ensuite. Aucun téléphone
réel n’a été utilisé.

## Contrôles réalisés

- Commande finale avec JDK 21 : `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest` : 472 tests, 0 échec, 0 erreur, 0 ignoré ;
  les deux APK sont assemblés.
- APK debug : `app/build/outputs/apk/debug/app-debug.apk`, 90 427 739 octets,
  SHA-256 `60a8513b15f90ff75006e58565fc4e2fe7bd3bf30ee31b7c67e6abb586ed36ef`.
- APK de tests : `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
  994 578 octets, SHA-256
  `a327b706eb901943799a50e4e60307762bd8998ebd7d6e865b999614978925e6`.
- `git diff --check` : propre.
- Revue statique des chemins accueil/réglages, thèmes, overlay, notes, caméra,
  contrôleur/panneau d’export et pont sélecteur.
- Les références ImageGen sélectionnées ont été observées avant
  implémentation ; les PNG font 853 × 1844 pixels pour une cible de contenu
  390 × 844 dp.
- Les tests unitaires `ExportGenerationGate` et `OverlayBridgeReturnGate`
  vérifient l’invalidation des résultats tardifs, la reprise après arrière-plan,
  les deux ordres reprise/focus et la consommation unique du retour.
- Les tests Robolectric natifs `NoteCameraActivityRobolectricTest` vérifient les
  constructions Light/Dark corrigées avec le wrapper `FrameLayout`, le teardown
  effectif de l’`ActivityController` et reproduisent l’exception
  `UnsupportedOperationException` de l’ancien fond posé directement sur
  `TextureView`.
- Les rendus Canvas natifs hors appareil sont archivés dans
  `docs/design/2026-09-12-carnet/qa-renders/` : `main-light.png` et
  `main-dark.png` (390 × 844), `main-small-large-font.png` (320 × 640,
  `fontScale` 1,3), `wave-compact.png` (148 × 32, repos puis voix). Le test
  compact avance explicitement 12 ticks ; cela vérifie l’amplitude et les
  bords fondus, pas le timing `Choreographer` d’un appareil.

## Contrôles encore impossibles

Le rendu réel et les interactions suivantes restent à vérifier sur un runtime
Android stable :

- accueil, sous-pages de réglages et choix Système/Clair/Sombre après recréation ;
- overlay compact/agrandi, clavier, sélection, défilement, animation de taille,
  indicateurs RECORDING/PAUSED/PROCESSING et respect des animations désactivées ;
- persistance réelle des notes, menus ⋮, vignettes et retour depuis une note ;
- viseur Camera2, permissions, rotation, capture et recoloration en cours de
  session ;
- génération et rendu `PdfRenderer`/`WebView`, mémoire, pages, retry, fermeture
  et retour au même brouillon ;
- sélecteurs Android Enregistrer/Partager, retour focus/reprise et rejet d’un
  ancien jeton de bridge ;
- TalkBack, tailles de police, contraste calculé par ressource, zones tactiles
  48 dp et comportement sans micro réel.

Les tests natifs hors appareil ne couvrent ni une session Camera2 réelle, ni
`WindowManager`, `PdfRenderer`, `WebView`, sélecteur système ou service
d’accessibilité ; ils ne constituent donc pas une preuve de design QA runtime.
Une prochaine passe pourra débloquer ce document avec un appareil ou un
émulateur accéléré stable, puis produire les captures main, réglages, overlay,
notes, photo et export dans `docs/design/2026-09-12-carnet/current/`.
