# DictAI — les dix dernières dictées

Android **0.9.16** (code 45) et macOS **0.4.4** (build 14) ajoutent un historique local pour retrouver et copier une transcription, y compris lorsque son insertion a échoué.

## Utilisation

- Android : ouvrir **Historique des dictées** depuis l’accueil ou le menu flottant des notes, toucher une dictée, puis **Copier le texte**.
- Mac : ouvrir **Historique des dictées** dans la bibliothèque ou le menu de la barre des menus, sélectionner une dictée, puis **Copier le texte**.
- **Vider l’historique** efface uniquement ce tampon, après confirmation dans l’interface.

## Conservation

Le tampon conserve au maximum dix dictées, de la plus récente à la plus ancienne. La onzième remplace la plus ancienne ; il ne s’agit pas d’une archive permanente. Le texte intégral après correction et retouches reste disponible après fermeture et réouverture de l’application. Aucun audio ni aucune image n’est ajouté au tampon, et son contenu n’est pas synchronisé.

Les notes tapées sans reconnaissance vocale, les captures seules et les dictées annulées ne produisent pas d’entrée. Une nouvelle tentative d’insertion met à jour la même entrée. Les dictées enregistrées volontairement comme notes restent aussi accessibles dans l’historique ; ces notes restent permanentes. Sur Mac, envoyer une dictée dans une autre application ne crée plus automatiquement une note permanente. Les notes, dossiers, images et éléments de vocabulaire déjà présents sont conservés.

## Vérifications

Les tests couvrent la onzième dictée, le redémarrage, les retouches et nouvelles tentatives, l’absence de parole, l’effacement du tampon, la préservation des notes et du vocabulaire, les erreurs d’écriture et les fichiers illisibles. Sur Mac, l’identité du brouillon survit au redémarrage et les comptes utilisent des tampons locaux séparés. Android vérifie aussi l’ouverture d’une longue transcription et sa copie intégrale dans le presse-papiers.

Les suites complètes réussissent : **748 tests Android**, **149 tests Mac sur ARM et sur Intel via Rosetta**. Les tests Mac suivent le lanceur du projet, qui sérialise les fenêtres AppKit et les raccourcis partageant la boucle principale. Les rendus natifs ont été inspectés en apparence claire et sombre : titres et commandes lisibles, liste défilante, transcription intégrale dans une zone dédiée. L’APK conserve les neuf bibliothèques de reconnaissance autorisées, avec alignement ELF et ZIP 16 Ko ; sa signature est vérifiée. L’installateur Mac universel contient les binaires ARM et Intel et aucune donnée personnelle.

Ces vérifications utilisent des bibliothèques isolées et des dictées synthétiques. Aucun appareil Android physique n’était connecté pendant cette livraison. Le parcours vocal réel sur Poco F7, Pad 7 et dans un champ externe du Mac reste à essayer après installation de ces versions. Le paquet Mac conserve le statut de version de test, sans validation Developer ID ni notarisation.

## Aperçus natifs

### Mac — clair et sombre

![Historique Mac clair](https://raw.githubusercontent.com/Uhama91/DictAI/main/docs/research/recent-dictations/macos-light.png)

![Historique Mac sombre](https://raw.githubusercontent.com/Uhama91/DictAI/main/docs/research/recent-dictations/macos-dark.png)

### Mac — historique vide

![Historique Mac vide clair](https://raw.githubusercontent.com/Uhama91/DictAI/main/docs/research/recent-dictations/macos-empty-light.png)

![Historique Mac vide sombre](https://raw.githubusercontent.com/Uhama91/DictAI/main/docs/research/recent-dictations/macos-empty-dark.png)

### Android — clair et sombre

![Historique Android clair](https://raw.githubusercontent.com/Uhama91/DictAI/main/docs/research/recent-dictations/android-light.png)

![Historique Android sombre](https://raw.githubusercontent.com/Uhama91/DictAI/main/docs/research/recent-dictations/android-dark.png)
