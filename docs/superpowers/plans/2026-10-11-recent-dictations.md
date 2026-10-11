# Historique des dictées récentes

**Objectif :** récupérer les dix dernières dictées sur Android et macOS, puis publier les sources sur GitHub.

**Contrat :** tampon local, dix entrées au maximum, ordre de fin de dictée décroissant, texte intégral après correction, copie et suppression. La onzième dictée remplace la plus ancienne. Le tampon survit à la fermeture de l’application, reste hors synchronisation et ne conserve aucun audio. Les notes existantes restent intactes ; seules les notes enregistrées volontairement sont permanentes.

## Mise en œuvre native

- [x] Écrire puis exécuter les tests de rétention, redémarrage, doublons et suppression sur les deux plateformes.
- [x] Ajouter `DictationHistory.kt` et `DictationHistoryActivity.kt` ; raccorder la publication finale active d’`OverlayService` et l’accueil de `MainActivity`.
- [x] Ajouter `DictationHistoryStore.swift` et `HistoryView.swift` ; raccorder `LibraryStore`, `AppModel` et `LibraryView`. Remplacer l’archivage automatique des dictées vers un champ par ce tampon.
- [x] Vérifier les insertions échouées, les retouches, les pauses, les annulations, les textes saisis sans voix, les comptes et la conservation des notes.
- [x] Exécuter les suites complètes, construire l’APK et l’installateur universel et inspecter les rendus natifs clairs et sombres.

Les sources vérifiées et les documents locaux seront publiés sur `main` après les commits de cette livraison.

## Vérifications prioritaires

La récupération doit rester possible après une insertion échouée. Une nouvelle tentative d’une même dictée ne doit pas prendre deux places. Les retouches doivent être conservées. Les hypothèses vocales intermédiaires, les dictées annulées et les notes tapées ne doivent pas remplir le tampon. Un effacement du tampon doit laisser notes, vocabulaire, dossiers et images inchangés.

**Commandes :** `./gradlew :app:testDebugUnitTest :app:assembleDebug` avec JDK 21 ; `desktop/macos/scripts/test.sh` ; `desktop/macos/scripts/build-installer.sh`. Les rendus seront consignés dans `docs/2026-10-11-recent-dictations.md`. Aucun test synthétique ne prouve la dictée physique sur Poco F7 ou Pad 7.
