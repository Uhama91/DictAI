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

Ces premières vérifications utilisent des bibliothèques isolées et des dictées synthétiques. Les essais physiques réalisés ensuite sur le [Mac](2026-10-11-macos-quick-access.md), le [Poco F7 et la Xiaomi Pad 7](2026-10-11-android-installation.md) confirment la récupération et la copie des vraies dictées de l’utilisateur, ainsi que leur conservation après réouverture de l’historique. Les trois installations préservent les données antérieures. Le paquet Mac conserve le statut de version de test, sans validation Developer ID ni notarisation.

## Publication GitHub

Les sources sont publiées sur `main` au commit [`e0a6fda`](https://github.com/Uhama91/DictAI/commit/e0a6fda84d92e0ed3b9d41cd70228559abdfcb3a). Le commit précédent conserve les documents et rendus locaux historiques. Les deux builds GitHub de ces sources réussissent : [Android](https://github.com/Uhama91/DictAI/actions/runs/38135603758) et [macOS](https://github.com/Uhama91/DictAI/actions/runs/38135603762).

- [APK Android 0.9.16 — artefact GitHub](https://github.com/Uhama91/DictAI/actions/runs/38135603758/artifacts/11700863347) : SHA-256 `fc53f27201eee7902a9515d351c38fb8e7f2e360dba5a39e44027a5df77403a0`.
- [Installateur Mac 0.4.4 — artefact GitHub](https://github.com/Uhama91/DictAI/actions/runs/38135603762/artifacts/11700957844) : SHA-256 du paquet `87d5038e5d54eb309077b62e0d7adfb1ac0694d697ba6b9de5ff3ecaebf3faf2`.

Les artefacts téléchargés ont été contrôlés : empreintes conformes, identité et signature de l’APK, alignement ELF/ZIP 16 Ko, signature de l’application Mac et présence des deux architectures. Depuis cette livraison, le [Mac 0.4.6](2026-10-11-macos-quick-access.md) et [le Poco et la tablette 0.9.16](2026-10-11-android-installation.md) sont mis à jour avec conservation des données. L’historique est vérifié sur de vraies dictées sur les trois appareils, avec copie exacte et conservation après réouverture.

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
