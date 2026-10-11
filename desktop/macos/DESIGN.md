# DictAI Mac — adaptation du carnet mobile

Mode : Operate. Source visuelle : `ThemeTokens.kt`, `ic_launcher_foreground.xml`, `caveat.ttf` et captures natives Android de `docs/design/2026-09-12-carnet/qa-renders/`.

Le Mac reprend le logo à trois boucles, le lettrage Caveat et les palettes ivoire/encre/sauge et charbon chaud du mobile. La barre latérale et les vues en colonnes restent adaptées au clavier, au pointeur et à la largeur d’une fenêtre Mac. Les textes édités et les réglages emploient la police système pour rester lisibles ; Caveat distingue la marque et les titres.

Le chemin principal est : Option → petite pastille d’écoute → Option → fin de transcription et correction éventuelle → insertion dans le champ capturé → fermeture du panneau. La pastille garde le focus de l’application cible. Les mots sont visibles dans la pastille ; cliquer dessus ouvre l’éditeur rapide. Pause suspend l’écoute sans livraison ; Reprendre continue le même brouillon. Annuler arrête sans envoyer. En cas d’erreur, le panneau s’ouvre avec le texte conservé et une action de récupération. Échap ferme et conserve le brouillon.

Les gestes mobiles se traduisent en commandes nommées : pastille = Option, édition du texte = clic sur la transcription, formats = menu, classement = Classer. Un mode Note explicite crée une note locale ; aucune note ne part automatiquement dans un champ externe.

Les thèmes suivent `theme_mode` synchronisé, avec choix Système/Clair/Sombre. Icône Dock, barre des menus et marque interne utilisent le même tracé, sans pictogramme de microphone générique pour représenter DictAI.

Vérification : tests du cycle asynchrone, du second appui, des retouches, de l’annulation et des erreurs ; captures natives des thèmes et des panneaux dans une bibliothèque temporaire. Autorisations personnelles et synchronisation physique restent des essais séparés.

## Overlay à trois niveaux — 7 octobre 2026

La pastille mesure 440 × 124 points. Les mots récents restent sur la ligne du bas ; deux lignes sont lisibles et la troisième disparaît progressivement vers le haut. Cliquer sur ces mots ouvre un éditeur rapide de 560 × 330 points. Le bouton Ouvrir dans une fenêtre ouvre directement une vraie fenêtre macOS, redimensionnable, minimisable et compatible plein écran. Les trois présentations partagent un seul brouillon, sa persistance et le champ externe capturé au début ; changer de présentation ne relance ni la reconnaissance ni la capture du champ.

Le mouvement central est celui des boucles cursives : défilement continu et ouverture des boucles selon le niveau micro, avec la géométrie du mobile. L’animation reste limitée à ce petit canevas, à 30 images/s, uniquement pendant l’écoute et lorsque la surface est visible. Réduire les animations garde un tracé fixe et l’indicateur d’écoute. Les transitions du panneau restent courtes et désactivées avec cette préférence. Les actions de pause, de reprise, d’annulation, d’insertion et de classement gardent leur sens dans chaque présentation.

Une pause vocale, une pause explicite ou la fermeture du panneau conserve le document. Reprendre ajoute la suite. Une retouche protège le texte corrigé, tout en permettant l’ajout de nouveaux mots. Le second appui sur Option pendant l’écoute termine et livre le même texte dans les trois présentations. Le panneau se masque avant de restituer le focus au champ externe. Un retour positif de l’API d’accessibilité ne suffit plus à afficher « Texte inséré » : le contenu du champ est relu quand il est accessible.

Après annulation ou fermeture, Option démarre une nouvelle dictée et recapture le champ actif. L’ancien texte reste récupérable dans les notes ; reprendre un brouillon antérieur est une action explicite. Les fenêtres présentent Annuler dans leur en-tête et Pause/Reprendre à côté de Terminer ; la pastille garde son bouton croix avec le libellé d’accessibilité Annuler la dictée.
