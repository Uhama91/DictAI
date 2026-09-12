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

La validation finale du 12 septembre 2026 a exécuté
`:app:testDebugUnitTest :app:assembleDebug` avec le SDK/JDK isolés et obtenu
462 tests sans échec, erreur ni test ignoré. Le contrôle visuel design QA reste
bloqué faute de runtime Android stable ; aucune capture d’émulateur ou de
téléphone n’a été jointe rétroactivement aux prompts. Voir
[`design-qa.md`](../../../design-qa.md) pour les contrôles passés et les vérifications
runtime restantes. Après le clipping final des coins du panneau, l’APK debug
courant est `app/build/outputs/apk/debug/app-debug.apk` (90 419 811 octets,
SHA-256 `04e40304745e4bb8fd6546c5adfa5e8b896dddf6bd9a5a0aafb49969c4234e97`).
