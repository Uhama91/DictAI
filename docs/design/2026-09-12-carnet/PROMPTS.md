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
