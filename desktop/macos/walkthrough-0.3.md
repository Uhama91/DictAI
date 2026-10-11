# DictAI Mac 0.3 — du texte dans la pastille à la fenêtre de travail

Version 0.3.0 installée sur ce Mac le 7 octobre 2026. La transcription apparaît directement dans la pastille. Un clic sur le texte ouvre un éditeur rapide ; le bouton d’agrandissement ouvre une vraie fenêtre macOS. Le même brouillon suit ces trois présentations, avec ses retouches et son enregistrement progressif.

[Application installée](/Users/ulliemaillot/Applications/DictAI.app) · [Archive 0.3](/Users/ulliemaillot/Downloads/DictAI-macOS-0.3.0.zip) · [Guide](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/README.md) · [Vérifications](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/verification-0.3.json)

## Trois niveaux pour le même texte

| Action | Résultat |
|---|---|
| Premier appui sur Option | Écoute et transcription dans la pastille, avec les boucles animées. |
| Clic sur les mots | Ouverture de l’éditeur rapide pour une retouche. |
| Clic sur les flèches d’agrandissement | Fenêtre native, déplaçable, redimensionnable, minimisable et compatible plein écran. |
| Relire pendant l’écoute | Fin de l’écoute sans insertion automatique, pour corriger tranquillement. |
| Second appui sur Option | Fin du traitement puis insertion dans le champ d’origine, ou enregistrement en mode Note. |
| Revenir à la pastille | Retour à l’aperçu compact avec le même texte. |
| Fermer | Arrêt de l’écoute et conservation du brouillon sur ce Mac. |

Les captures ci-dessous proviennent de l’application compilée, dans une bibliothèque temporaire séparée. La transcription et le niveau sonore y sont simulés : ce sont des preuves d’interface, pas un test de reconnaissance au microphone. Aucune note de démonstration n’a été ajoutée à la bibliothèque personnelle.

### Pastille avec texte

Les dernières lignes reconnues sont visibles et cliquables. Les commandes pour terminer, ouvrir la fenêtre et fermer restent distinctes.

![Pastille sombre avec transcription](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-compact-dark.png)

![Pastille claire avec transcription](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-compact-light.png)

### Retouche rapide

Le panneau s’ouvre légèrement. Les retouches manuelles restent protégées des résultats tardifs de reconnaissance. Une correction courte peut ensuite être mémorisée dans le vocabulaire.

![Éditeur rapide sombre](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-editor-dark.png)

![Éditeur rapide clair et sélection native du texte](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-editor-light.png)

### Fenêtre de travail

La fenêtre possède les commandes natives de macOS. Le passage depuis l’éditeur rapide conserve le texte et les corrections ; l’enregistrement de la note a été vérifié jusqu’à sa présence dans la bibliothèque de test.

![Fenêtre sombre et proposition de vocabulaire après une retouche](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-window-dark.png)

![Fenêtre claire](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-window-light.png)

Le plein écran conserve une colonne de lecture centrée. Le retour vers la pastille attend la fin de la transition native avant de masquer la fenêtre. La réouverture du brouillon puis sa fermeture ont été vérifiées après cette transition.

![Fenêtre en plein écran](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-fullscreen-dark.png)

## Boucles et véritable icône DictAI

Les boucles cursives défilent pendant l’écoute et leur amplitude suit le niveau du microphone. Leur tracé reprend la construction de l’animation Android. Elles s’arrêtent à la fin de l’écoute ou à la fermeture du panneau ; le réglage macOS Réduire les animations donne un tracé statique.

L’animation suivante utilise la même géométrie que l’application, avec un niveau sonore simulé. Les images ont été inspectées à plusieurs instants ; une version ralentie à 10 % est également fournie.

![Boucles animées](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/loops-0.3.gif)

[Animation au ralenti](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/loops-0.3-slow.gif)

L’icône reprend exactement les trois boucles du lanceur Android, en encre sur fond crème. Elle est embarquée en plusieurs résolutions dans le fichier ICNS du paquet macOS. Son affichage par le sélecteur natif d’applications a été vérifié sur la version installée.

![Icône DictAI](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/icon-0.3.png)

![Icône reconnue par macOS](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/icon-0.3-macos.png)

## Vérification

- **48 tests réussis dans 8 suites**, dont le partage du brouillon entre les trois présentations, la protection des retouches, l’insertion unique au second appui, les propriétés de la fenêtre native et la continuité géométrique des boucles.
- **Compilation release et signature locale vérifiées**. Archive Apple Silicon pour macOS 14 minimum. La version précédente est conservée dans Téléchargements.
- **Parcours natifs contrôlés** en clair et en sombre : clic sur le texte, agrandissement, relecture, plein écran, retour, fermeture, reprise et enregistrement dans la bibliothèque isolée.
- **Six notes personnelles toujours présentes** après installation ; les données de test utilisent une autre bibliothèque et un autre identifiant d’application.
- L’entrée Accessibilité de la nouvelle compilation a été réinscrite. Le renouvellement des autorisations audio est en cours de validation manuelle, les fenêtres système étant inaccessibles à l’outil de contrôle.

Le parcours physique Option → voix → Option → insertion dans une application externe reste à confirmer pour cette version. La convergence réelle Mac/Android avec un compte connecté, la compatibilité avec les Mac Intel et la notarisation publique ne sont pas validées. Cette livraison concerne le Mac ; elle ne remplace pas l’APK Android.

[Journal des tests](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-tests.log) · [Journal de compilation](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3-build.log)
