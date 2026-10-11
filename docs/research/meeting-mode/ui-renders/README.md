# Panneau Réunion — captures Android

Captures inspectées par Astra le 24 septembre 2026. Il s’agit du panneau Android réel, hébergé dans une fixture utilisant des données simulées. Le micro et les modèles ne sont pas utilisés dans ces scènes. La pastille en spirale et son raccordement aux gestes ne figurent pas encore dans ces images.

Le second passage de la fixture est terminé avec succès : un test instrumenté, 11,285 secondes. Les 13 tests JVM du panneau et 20 tests médias ciblés passent également. Les prénoms longs, homonymes, pièces jointes conservées après filtre et thèmes sont lisibles. Le panneau compact conserve deux lignes à taille normale ; avec une police agrandie, le défilement et l’agrandissement permettent de poursuivre la lecture. La capture du clavier montre son ouverture, sans valider à elle seule une correction pendant une vraie transcription.

La première série était masquée par une alerte System UI de l’émulateur ; elle n’est pas utilisée pour cet audit. Les détails et empreintes figurent dans le [rapport de validation](../validation.md). Le [plan](../../../superpowers/plans/2026-09-24-meeting-mode.md) décrit les essais applicatifs qui restent à effectuer.

## Trois profils et retour d’un intervenant

![Trois profils et retour d’un intervenant](01-three-voices-return.png)

## Deux personnes portant le prénom Sophie restent distinctes dans le sélecteur

![Deux personnes portant le prénom Sophie restent distinctes dans le sélecteur](02-homonyms-native-dialog.png)

## Saisie d’un nom long

![Saisie d’un nom long](03-long-rename-native-dialog.png)

## Nom actualisé sur les passages concernés

![Nom actualisé sur les passages concernés](04-renamed-homonyms.png)

## Paroles masquées et pièce jointe toujours accessible

![Paroles masquées et pièce jointe toujours accessible](05-ignored-voice-image-action.png)

## Ouverture du clavier sur un passage éditable

![Ouverture du clavier sur un passage éditable](06-focused-edit-field.png)

## Écoute affichée et erreur de sauvegarde distincte

![Écoute affichée et erreur de sauvegarde distincte](07-live-save-error.png)

## Progression et annulation du téléchargement

![Progression et annulation du téléchargement](08-model-download-progress.png)

## Thème clair

![Thème clair](09-manual-light-theme.png)

## Thème sombre

![Thème sombre](10-manual-dark-theme.png)

## Panneau de 240 × 112 dp, taille de texte normale

![Panneau de 240 × 112 dp, taille de texte normale](11-landscape-compact-240x112-normal-font.png)

## Même panneau avec taille de texte agrandie

![Même panneau avec taille de texte agrandie](12-landscape-compact-240x112-large-font.png)

## Couronne Réunion

Vue Android réelle : pastille 74 × 44 dp en haut, aperçu agrandi en dessous. La [série complète et sa vidéo](pill/README.md) ont maintenant été inspectées par Astra : repos, rotation, pause et retour à l’onde Dictée. L’écoute est simulée, sans audio ni modèle. Les six boucles sont décoratives et ne représentent pas le nombre d’intervenants.

![Couronne Réunion à la taille de la pastille et en aperçu agrandi](pill/01-meeting-paused-static.png)

## Accueil et configuration

Les [huit captures des vraies Activities](activities/README.md), inspectées le 25 septembre, montrent le choix Dictée/Réunion, les réglages et l’assistant dans les deux thèmes, avec deux vues à texte agrandi. Cette série utilise des modèles absents et ne démarre pas d’enregistrement.

![Choix du mode Réunion dans l’accueil Android](activities/01-main-home-light-100.png)
