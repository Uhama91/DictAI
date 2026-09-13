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

Pour le mouvement, les références de comparaison restent
`selected/motion-reference-A-souple-organique.png`,
`selected/motion-reference-B-vif-calligraphique.png` et
`selected/motion-reference-C-ample-respirant.png`. L’utilisateur a choisi B
pour sa vélocité et a demandé une ouverture basse franche en opposition au
sommet ; la référence ciblée est
`selected/motion-reference-B-creux-opposes.png`. Les vidéos
`qa-renders/wave-animation.mp4` et `qa-renders/wave-variants-abc.mp4` à ligne
basse fixe ou quasi fixe restent explicitement des essais rejetés et ne
représentent pas la cible actuelle.

Le nouveau rendu `qa-renders/wave-vivid-corrected.mp4` (164 × 176 pixels,
360 frames à 60 fps, 6 secondes) montre B dans la pastille réelle `74 × 44`
puis la même vue agrandie 2×. `qa-renders/wave-vivid-before-after.mp4`
(328 × 176 pixels) juxtapose à gauche l’ancien B extrait de l’archive et à
droite le rendu corrigé, avec le même signal RMS et les mêmes instants. Le
rendu natif montre environ 6 px de déplacement vers le haut et 7 px vers le
bas entre repos et voix forte ; la validation visuelle utilisateur de ce nouvel
aperçu reste attendue.

Le rendu Canvas natif hors appareil valide visuellement la composition de
l’accueil clair/sombre, le logo entier, l’espacement des cartes et le B corrigé
de l’onde compacte au repos et sous niveaux vocaux simulés. Le comparatif sert à
confirmer le choix B ; il ne remplace pas une validation Android réelle. Le
rendu natif de la pointe `qa-renders/bubble-pointer.png` montre les
quatre bords et des pixels hors du corps, sans remplacer la vérification de la
fenêtre overlay. La validation matérielle complète reste bloquée. Pour les
autres surfaces — réglages, overlay, notes, photo et export — la typographie,
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
  `fontScale` 1,3), `wave-compact.png` (148 × 32, repos puis voix),
  `wave-variants-abc.mp4` (archive A/B/C rejetée, 360 frames natives à 60 fps,
  six secondes), `wave-vivid-corrected.mp4` et
  `wave-vivid-before-after.mp4` (B corrigé, 360 frames à 60 fps, six secondes)
  et `bubble-pointer.png`. Le B corrigé utilise la même taille de pastille
  `74 × 44` et une transformation 2× pour la rangée agrandie ; ses frames
  avancent explicitement le temps. Cela vérifie la continuité, l’amplitude,
  les raccords et les bords fondus, pas le timing matériel de
  `Choreographer`.

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

## Correction mouvement B — validation utilisateur en attente

Les six tests ciblés de `CursiveWaveMotionTest` et le test
`OverlayWaveLevelTest` passent avec le JDK 21 isolé ; le rendu natif
`CompactMotionVariantsNativeRenderTest` B passe également. Ils vérifient
l’excursion opposée, l’enveloppe locale partagée, les formes cursives, le
défilement/wrap, l’équivalence 60/120 Hz, le plafonnement des retards et le
mapping audio visuel. Les 360 frames sont avancées explicitement de `1/60 s`
et l’encodage est borné à un thread ; elles ne prétendent pas mesurer le rythme
d’un `Choreographer` ou d’un GPU réel. B est le choix comportemental de
l’utilisateur, mais la validation de la nouvelle vidéo reste à recueillir
avant toute publication.

Le raccord de la pointe est couvert par un rendu Canvas hors appareil, mais la
relation réelle avec la pastille, le clavier, la rotation, les déplacements et
le `WindowManager` reste non validée sur matériel. Le résultat global demeure
`final result: blocked` jusqu’à une capture Android stable.

## Mise à jour B — repos de l’installation restauré

Le choix utilisateur B — Vif est conservé, mais l’aperçu aplati précédent a
été remplacé par `qa-renders/wave-vivid-restored-height.mp4` : 164 × 176 px,
360 frames à 60 fps, 6 secondes, pastille réelle 74 × 44 puis zoom fidèle 2×.
Le repos retrouve la silhouette de `631dbeb13f7b8cc912b8791a3eb75164293316a7`
(top logique 12, creux 28) et le rendu natif observé passe d’environ 14 px
d’encre au repos à 21 px en voix faible, 25 px en voix ordinaire et 26 px en
voix forte, sans coupe. Les sept tests de `CursiveWaveMotionTest`, le test
`OverlayWaveLevelTest` et le test de rendu natif B passent, soit neuf contrôles
ciblés ; l’audio est simulé et aucune validation matérielle ne
doit être déduite de ces contrôles. La référence ImageGen correspondante est
`selected/motion-reference-B-recentered-installed.png`. La revue visuelle
utilisateur est validée explicitement (« Excellent c’est ça que je veux »). La
validation matériel/runtime reste bloquée pour les interactions WindowManager,
IME, rotation, Camera2 et les autres surfaces indiquées ci-dessus.
Une grille de test pur couvre en plus les positions intermédiaires de déplacement
en portrait/paysage, le panneau compact/agrandi, le viewport réduit par l’IME,
l’enveloppe de pointe et son écart à la pastille ; elle sera exécutée par la CI,
sans constituer une validation `WindowManager` sur matériel.
Les autres essais ImageGen et vidéos historiques restent des archives locales
non suivies ; les preuves retenues pour la branche sont la référence B finale,
la référence de pointe, `qa-renders/bubble-pointer.png` et
`qa-renders/wave-vivid-restored-height.mp4`.
