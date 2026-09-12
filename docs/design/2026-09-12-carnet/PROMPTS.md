# Trace ImageGen — 12 septembre 2026

L’outil ImageGen intégré a été utilisé sans paramètre de modèle sélectionnable,
sans clé et sans référence de capture Android. Les prompts ciblaient un canevas
de contenu applicatif portrait `390 × 844`, sans bezel ni barres système, puis
les sorties ont été observées et copiées dans `concepts/`.

## Références finales

- `ivoire-encre-sauge-v2.png` — itération de `ivoire-encre-sauge.png` : page
  ivoire et sauge, action « Activer le bouton flottant », « Mes notes », puis
  trois groupes réels (« Dictée », « Mise en forme », « Préférences »), avec
  les aperçus « Français · Texte sans LLM » et « Texte · Liste · Mail ». Le
  prompt de correction supprimait slogans, feuilles, plume et faux écran
  « Formats / format d’export ».
- `carnet-nocturne.png` — proposition indépendante sombre fondée sur les
  `ThemeTokens` (`#201F1C`, surfaces charbon, encre crème, sauge et bleu
  discret), avec la même copie fonctionnelle et une boucle cursive souple.
- `feuille-claire-structuree.png` — itération de
  `feuille-claire-structuree-v1.png` : feuille claire modulaire et pragmatique,
  mêmes destinations et mêmes choix factuels ; le slogan a été retiré lors de
  la correction.

Les fichiers `*-v1.png` et `ivoire-encre-sauge.png` sont conservés comme
brouillons de traçabilité et ne sont pas des choix finaux. Les prompts ont
explicitement exclu comptes, cloud sync, analytics, abonnements, chat,
calendrier, modèle présenté comme installé, enregistrement simulé, néon,
glassmorphism et tout contenu marketing inventé.

## Sélection et variantes

- `selected/main-sombre.png` — dérivée image-vers-image de la référence
  sélectionnée `ivoire-encre-sauge-v2.png`, avec composition, proportions,
  hiérarchie, libellés et espacements conservés. Seule la palette est adaptée
  aux tokens sombres DictAI (`#201F1C`, surfaces charbon, encre crème, sauge et
  bleu discret). La composition de `carnet-nocturne.png` n’a pas été réutilisée.
- `selected/preferences-clair.png` — dérivée image-vers-image de la même
  référence sélectionnée, comme sous-écran clair Préférences avec retour
  contextuel. Le contrôle requis affiche `Système / Clair / Sombre`, avec
  `Clair` sélectionné, puis les accès existants Dictée, Modèles locaux et
  Diagnostic. Aucun écran Android réel n’était disponible comme référence.
- `selected/dictee-clair.png` — sous-écran clair dédié aux réglages réels de
  dictée : langue, modèle vocal du catalogue, nombres, affichage du texte,
  nettoyage léger, espace final et vocabulaire. Le modèle vocal est présenté
  comme sélection/téléchargement possible, jamais comme installé.
- `selected/mise-en-forme-clair.png` — sous-écran clair dédié aux réglages
  réels de post-traitement : `Texte sans LLM` avec les choix `Texte · Liste à
  puces · Mail`, moteur, modèle cloud du catalogue (`Mistral Small 3.2`), clé
  OpenRouter et dernier diagnostic. Une première sortie introduisait un
  modèle absent du catalogue ; elle n’a pas été conservée et la version
  corrigée est celle indiquée ici.

Les variantes ont été générées séparément par l’outil ImageGen intégré, sans
paramètre de modèle sélectionnable. Cible de prompt : contenu applicatif
portrait `390 × 844 dp`, sans bezel ni barres système ; les PNG ImageGen
conservent le ratio mais peuvent utiliser une résolution pixel supérieure.

Après l’essai réel de l’APK, l’accueil a reçu deux révisions ImageGen
image-first, toujours dérivées de la référence ivoire sélectionnée :
`selected/main-clair-revision-v4.png` et
`selected/main-sombre-revision-v3.png`. Le prompt conservait les destinations
existantes, supprimait le CTA et la carte d’état permanents, gardait le logo
DictAI entier avec marge, dessinait une onde cursive haute et ample, puis
répartissait Mes notes et Dictée/Mise en forme/Préférences sur la hauteur utile.
Ces révisions n’introduisent aucune fonction ni donnée d’exemple.

## Déclinaisons phase 2 — même direction sélectionnée

Chaque interface ci-dessous a été générée avant son implémentation, avec les
références locales `concepts/ivoire-encre-sauge-v2.png` et/ou
`selected/main-sombre.png` attachées à l’outil ImageGen. Les fichiers font
`853 × 1844` pixels, soit le ratio de la cible `390 × 844 dp`; aucune capture
Android de runtime n’était disponible.

- `selected/overlay-clair-enregistrement.png` — panneau overlay clair d’environ
  312dp, titre exact `Texte sans LLM`, témoin rouge lumineux, transcript
  réaliste, trois actions capture/photo/partage et un seul contrôle de taille
  dans l’en-tête à côté du masquage. Une sortie précédente avec un contrôle de
  taille dupliqué est conservée comme `overlay-clair-enregistrement-v1.png`.
- `selected/overlay-sombre-pause.png` — dérivée de la claire corrigée avec la
  même composition, palette sombre et signe pause cursif à deux traits, sans
  texte d’état. La sortie intermédiaire est conservée comme
  `overlay-sombre-pause-v1.png`.
- `selected/notes-clair.png` — liste `Mes notes`, `＋ Nouvelle note`, dates
  fictives ancrées au `12 sept. 2026` et menu existant
  `Renommer` / `Partager / exporter · texte et images` / `Supprimer` /
  `Retour aux notes`.
- `selected/photo-clair.png` — viseur compact de `NoteCameraActivity` avec les
  labels exacts `Photo 1 · cadrer puis photographier`, `Prendre la photo` et
  `Annuler`; aucune fonction caméra non présente n’est montrée.
- `selected/export-clair.png` — panneau overlay agrandi avec retour
  `Retour à la note`, choix `PDF` / `HTML` / `Texte et images`, aperçu local,
  navigation de page et actions `Enregistrer` / `Partager` dans le même
  contexte.

Les deux sorties overlay en `-v1` sont uniquement des brouillons de traçabilité;
les fichiers sans suffixe sont les références corrigées à revoir pour la phase
d’implémentation. Les arrière-plans d’application visibles dans les maquettes
servent de contexte visuel : le code overlay doit conserver la fenêtre de
l’application réelle et ne pas recopier de contenu fictif.

## Trace de mise en œuvre et validation

Après la sélection de la direction ivoire/encre/sauge, les références ci-dessus
ont été utilisées comme ancrage pour les écrans correspondants. La phase 2 a
conservé les données et composants existants : l’overlay reste la fenêtre de
travail, les exports sont préparés et prévisualisés dans son panneau agrandi,
et `NoteExportActivity` ne sert qu’aux sélecteurs Android explicitement
actionnés. Aucun exemple de note, titre ou image provenant des maquettes n’a
été injecté dans le stockage.

La validation finale du 12 septembre 2026 a exécuté, avec le SDK/JDK isolés et
le JDK 21 requis par la dépendance Gemma,
`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
Elle a obtenu 472 tests sans échec, erreur ni test ignoré et a assemblé les deux
APK. Le contrôle visuel design QA complet reste bloqué faute de runtime Android
stable ; les contrôles natifs hors appareil sont archivés dans
`qa-renders/` : `main-light.png` et `main-dark.png` (390 × 844),
`main-small-large-font.png` (320 × 640, fontScale 1,3) et `wave-compact.png`
(148 × 32, repos puis voix). Le contrôle compact avance explicitement 12 ticks
pour vérifier amplitude et bords fondus ; il ne prétend pas mesurer le timing
`Choreographer` matériel. Voir [`design-qa.md`](../../../design-qa.md) pour les
limites runtime restantes.

Le test Robolectric natif de caméra reproduit l’exception
`UnsupportedOperationException` de l’ancien fond appliqué directement à
`TextureView`, tandis que les constructions Light/Dark corrigées passent avec
le wrapper `FrameLayout` et un teardown `ActivityController` détruit.
L’APK debug courant est `app/build/outputs/apk/debug/app-debug.apk`
(90 427 739 octets, SHA-256
`60a8513b15f90ff75006e58565fc4e2fe7bd3bf30ee31b7c67e6abb586ed36ef`) et l’APK
de tests `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
(994 578 octets, SHA-256
`a327b706eb901943799a50e4e60307762bd8998ebd7d6e865b999614978925e6`).

## Référence mouvement à revoir

`motion-reference.png` est une planche ImageGen dédiée à la pastille, générée
avec `qa-renders/wave-compact.png` comme référence du trait existant et
`selected/overlay-clair-enregistrement.png` comme contexte de la pastille. Le
prompt bref demandait trois états `REPOS`, `VOIX FAIBLE`, `VOIX FORTE`, une
translation gauche→droite continue, une base inférieure strictement fixe et
une amplitude supérieure nettement plus vive avec attaque rapide et relâchement
doux. Il excluait l’interface, les contrôles et les graphes audio.

Observation à confirmer avant implémentation : les trois états de la planche
gardent la même ligne basse, tandis que les sommets supérieurs montent
progressivement et que la rangée fantôme indique le défilement sans rupture.

## Révision du mouvement — comparaison A/B/C du 12 septembre 2026

Le premier essai `qa-renders/wave-animation.mp4`, fondé sur une ligne basse
strictement fixe, a été rejeté par l’utilisateur comme peu naturel. Il reste
archivé comme essai rejeté et n’est pas une référence de comportement.

Trois références ImageGen indépendantes ont ensuite été produites, avec
`motion-reference.png` et `qa-renders/wave-compact.png` attachés comme
références du trait existant :

- `selected/motion-reference-A-souple-organique.png` — creux légèrement
  mobiles, élasticité asymétrique et attaque douce mais lisible ;
- `selected/motion-reference-B-vif-calligraphique.png` — attaque rapide,
  sommets plus francs et inclinaison manuscrite plus nerveuse ;
- `selected/motion-reference-C-ample-respirant.png` — boucles plus ouvertes,
  respiration large et creux plus présents.

Chaque référence montre `Repos`, `Voix faible` et `Voix forte` dans la vraie
pastille arrondie `74 × 44 dp` et un agrandissement. Le trait reste une seule
écriture cursive qui avance de gauche à droite ; les creux partagés descendent
moins que les sommets lorsqu’une syllabe monte, avec tangentes continues et
raccord spatial sans coupe. Les annotations ImageGen et les fonds de planche
ne sont pas des éléments à recopier dans l’application.

L’essai natif comparable est `qa-renders/wave-variants-abc.mp4` : 484 × 176
pixels, 360 images à 60 fps, six secondes, signal RMS synthétique identique
pour A/B/C. La rangée supérieure utilise la pastille `74 × 44` et une onde
`74 × 32`; la rangée inférieure agrandit cette même vue par transformation 2×
pour conserver l’épaisseur relative. Les images témoins sont
`qa-renders/motion-variants-rest.png` et
`qa-renders/motion-variants-voice-forte.png`. Aucun preset n’est encore choisi
pour la version finale et aucune publication ne doit partir avant ce choix.

## Pointe de bulle — portée limitée

`overlay-bubble-reference.png` reste la référence de la seule pointe courbe
reliant le panneau existant à la pastille ; la pastille `74 × 44`, son contenu,
sa palette et ses contrôles ne viennent pas de la planche. Le rendu natif
`qa-renders/bubble-pointer.png` vérifie les quatre bords et des pixels de la
pointe hors du corps. Le corps arrondi est dessiné avant la pointe, qui remplit
un très court recouvrement et garde un contour ouvert afin d’éviter une ligne
droite à l’attache ; la validation complète du placement WindowManager reste
à faire sur appareil.

## Révision mouvement B — creux opposés — 12 septembre 2026

L’utilisateur a retenu la direction B (`selected/motion-reference-B-vif-calligraphique.png`)
pour sa vélocité, puis a rejeté le comportement à ligne basse fixe montré dans
`qa-renders/wave-animation.mp4` et `qa-renders/wave-variants-abc.mp4`. La
référence ciblée `selected/motion-reference-B-creux-opposes.png` a été générée
avec la référence B et un rendu natif de la pastille. Elle demande trois états
où le sommet monte et le creux descend de façon visible et corrélée, autour
d’un axe médian de planche qui ne doit pas être recopié dans l’application.

Le moteur VIVID conserve les boucles cursives croisées, la translation vers la
droite, l’attaque et le relâchement rapides, mais utilise la même enveloppe
locale pour les deux excursions opposées. Le creux reste raccordé à ses voisins
par une tangente continue et n’est plus limité à une vibration de 1 à 2 px.

Le nouveau rendu natif `qa-renders/wave-vivid-corrected.mp4` montre le B corrigé
dans une pastille opaque réelle `74 × 44` puis la même vue agrandie 2× ; il fait
164 × 176 px, 360 frames à 60 fps et 6 secondes. Le comparatif
`qa-renders/wave-vivid-before-after.mp4` fait 328 × 176 px : la moitié gauche
est extraite de l’archive B précédente et la moitié droite est le nouveau rendu,
avec le même signal RMS et les mêmes instants. Aux témoins repos/voix forte,
l’encre du rendu corrigé se déplace d’environ 6 px vers le haut et 7 px vers le
bas. Le B est choisi sous réserve de validation visuelle de ce nouvel aperçu par
l’utilisateur ; aucune publication finale ne doit encore en être déduite.

Les six tests de `CursiveWaveMotionTest` couvrent l’excursion opposée,
l’enveloppe partagée, les formes, le défilement/wrap, l’équivalence 60/120 Hz et
le plafonnement des retards ; `OverlayWaveLevelTest` couvre le mapping audio
visuel. Le rendu natif B passe également. Les 360 ticks sont avancés
explicitement à `1/60 s` et l’encodage utilise FFmpeg à un thread sous un scope
CPU/mémoire borné : cela vérifie le Canvas et la géométrie, pas le timing d’un
`Choreographer` ou d’un GPU réel.

## Révision B — repos de l’installation restauré — 12 septembre 2026

Le retour utilisateur a demandé de conserver la silhouette repos réellement
installée tout en gardant B — Vif et ses creux opposés. La référence unique
`selected/motion-reference-B-recentered-installed.png` reprend donc le repos
`y=12`/`y=28` de `631dbeb13f7b8cc912b8791a3eb75164293316a7` et montre l’ouverture
vers `y=3`/`y=37`. Elle sert de planche d’inspiration, sans guide ni annotation
dans l’application.

Le nouveau rendu natif `qa-renders/wave-vivid-restored-height.mp4` est le seul
aperçu de cette itération : pastille opaque `74 × 44`, onde `74 × 32`, zoom
fidèle 2×, `164 × 176` pixels, 360 frames à 60 fps et 6 secondes. Il remplace
pour la revue l’aperçu B aplati précédent ; `wave-compact.png` reste la
référence intacte de l’installation. La validation visuelle utilisateur de ce
nouvel aperçu est encore attendue avant publication.

L’utilisateur a ensuite validé explicitement cet aperçu (« Excellent c’est ça
que je veux »). La direction B est donc la référence comportementale retenue
pour la branche CI ; cette validation visuelle hors appareil ne couvre pas les
interactions WindowManager, IME, rotation ou Camera2 sur matériel réel.

Les variantes et essais historiques mentionnés plus haut restent des archives
locales non suivies. Le manifest prévu retient la référence finale
`selected/motion-reference-B-recentered-installed.png`,
`overlay-bubble-reference.png`, le rendu de pointe
`qa-renders/bubble-pointer.png` et le seul aperçu retenu
`qa-renders/wave-vivid-restored-height.mp4`, avec le code, les tests et les
documents de suivi.
