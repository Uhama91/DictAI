# DictAI sur Mac — première version

> Une version plus récente est disponible : [DictAI Mac 0.3.5 — terminer vraiment la dictée](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/walkthrough-0.3.5.md).

Application native SwiftUI/AppKit, compilée pour ce Mac Apple Silicon, avec macOS 14 comme version minimale. Le raccourci retenu est **un appui sur Option pour démarrer, puis un second pour arrêter**. Les données visées sont celles de DictAI : notes, dossiers, images et préférences de vocabulaire.

[Ouvrir l’application installée](/Users/ulliemaillot/Applications/DictAI.app) · [Archive ZIP](/Users/ulliemaillot/Downloads/DictAI-macOS-0.1.0.zip) · [Code et guide](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/README.md)

## Panneau de dictée

Le panneau flottant reste modifiable. Il permet de copier, de choisir un dossier pour enregistrer une note, de demander une correction et d’insérer le texte dans le champ d’origine. Une retouche humaine ne peut pas être écrasée par une transcription ou une correction tardive. Une petite correction peut être mémorisée explicitement dans le vocabulaire.

L’image ci-dessous montre le véritable panneau compilé, avec un texte saisi pour contrôler l’interface. Elle ne constitue pas une preuve de reconnaissance au microphone. « Insérer » est désactivé car ce test n’a pas capturé de champ dans une autre application.

![Panneau éditable de DictAI](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay.png)

## Bibliothèque

Le texte du panneau a été enregistré depuis le menu « Enregistrer », puis retrouvé dans la bibliothèque. Les notes s’enregistrent automatiquement. Recherche, classement, export texte, images reçues d’Android et conservation des versions concurrentes sont implémentés.

![Note enregistrée depuis le panneau](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/library.png)

Les sous-dossiers sont représentés par des noms comme **École / Réunions** et affichés avec indentation sur Mac. Le protocole Android actuel ne contient pas de relation parent : Android affiche donc ce chemin comme nom de dossier. Une véritable arborescence commune nécessitera une évolution des deux applications.

## Vocabulaire et compte facultatif

Les termes et règles de remplacement se conservent sur le Mac et entrent dans le même document de synchronisation que sur Android. La règle de contrôle `dicta => DictAI` a été ajoutée et affichée dans une bibliothèque temporaire.

![Vocabulaire et dossier de contrôle](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/vocabulary.png)

La connexion Google utilise le projet Supabase existant. Chaque compte possède une bibliothèque séparée ; l’utilisation sans compte reste possible. Les clés et jetons sont stockés dans le Trousseau. Les données de contrôle ne sont pas placées dans la bibliothèque personnelle.

![Compte et raccourci](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/settings.png)

![Autorisations et correction facultative](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/settings-permissions.png)

## Vérifications et limites

- **30 tests réussis**, couvrant notamment la persistance, les suppressions, les conflits concurrents, l’isolation des comptes, les limites Android, les titres Unicode, Option avec une autre touche et la protection des retouches.
- **Compilation release réussie**, signature locale ad hoc vérifiée. Livraison Apple Silicon ; aucune validation sur Mac Intel ni sur une ancienne version de macOS.
- **Interface réelle inspectée** : création/édition de note, dossier, vocabulaire, réglages, panneau, enregistrement du panneau vers la bibliothèque. Captures prises en mode isolé `--preview`.
- **À valider avec autorisations** : écoute au microphone, reconnaissance locale disponible sur ce Mac, raccourci Option dans les autres applications et insertion effective. Les autorisations micro, reconnaissance vocale et accessibilité n’ont pas été accordées pendant ces essais.
- **À valider avec le compte** : retour de connexion Google et synchronisation bidirectionnelle réelle entre Mac et Android. Aucun compte Google n’a été connecté pendant ces essais. Les tests du protocole ne prouvent pas la convergence de deux appareils connectés.
- Le moteur vocal de cette version est **Apple Speech en mode local**. Les modèles sherpa-onnx, Nemotron et Gemma, ainsi que le mode réunion Android, ne sont pas portés. OpenRouter est disponible en option ; aucun appel payant n’a été testé.
- Le workflow GitHub Actions est ajouté au code ; il n’a pas été exécuté. L’application n’est pas notarisée pour une diffusion publique.

Les sources de [Handy](https://github.com/cjpais/Handy) et de [FluidVoice](https://github.com/altic-dev/FluidVoice) ont servi de références pour les interactions macOS. L’implémentation Swift est nouvelle ; les détails de provenance sont dans le guide.
