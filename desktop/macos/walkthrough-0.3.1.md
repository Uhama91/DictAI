# DictAI Mac 0.3.1 — un brouillon continu

La version 0.3.1 est installée dans [Applications](/Users/ulliemaillot/Applications/DictAI.app). Les six notes existantes et le dernier brouillon ont été retrouvés après la mise à jour. L’ancienne application est archivée et récupérable.

[Archive de la version 0.3.1](/Users/ulliemaillot/Downloads/DictAI-macOS-0.3.1.zip) · [Code et guide](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/README.md)

## Texte récent en bas

La pastille passe de 560 à 440 points de large. La dernière ligne reste en bas, la précédente remonte et la troisième s’efface progressivement en haut. Cliquer sur le texte ouvre l’édition rapide ; les flèches ouvrent la fenêtre complète. Le logo et les boucles animées sont conservés.

Captures de l’application native dans une bibliothèque temporaire, avec reconnaissance simulée :

![Pastille sombre, mots récents en bas et anciennes lignes en fondu](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3.1-ribbon-dark.png)

![Pastille claire en pause, texte conservé](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3.1-ribbon-light.png)

## Continuité entre les trois vues

Les défauts reproduits dans les tests concernaient le remplacement par un résultat vocal vide, la remise à zéro lors d’une reprise et l’effacement après enregistrement. Le brouillon est maintenant indépendant des séquences de reconnaissance. Les phrases précédentes restent présentes quand le moteur commence une nouvelle séquence après un silence.

Le parcours vérifié dans l’interface est : transcription → pause → édition rapide → correction de « réunion » en « rencontre » → reprise → grande fenêtre → enregistrement → réouverture. Les 40 mots restent présents, avec la correction et la suite dictée. Le simulateur répète volontairement sa phrase à chaque reprise ; cette répétition dans la capture vient du scénario de test.

![Texte conservé après correction, reprise et passage en grande fenêtre](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3.1-continuity-window.png)

Pendant l’écoute, Option termine puis insère dans le champ d’origine, ou enregistre en mode Note. Après une pause explicite, Option reprend l’écoute. Fermer conserve le brouillon. Les trois présentations partagent le même document et le même champ de destination.

## Insertion et vérifications

Le panneau se masque avant de rendre le focus à l’application d’origine. L’insertion attend que le champ retrouve le focus et sa sélection. Quand le champ expose son texte, DictAI vérifie le résultat réel ; une simple réponse positive de l’API d’accessibilité ne suffit plus. Le collage sert de repli si le champ reste inchangé. Un résultat incertain conserve le brouillon et affiche une explication.

- **61 tests réussis dans 10 suites**, dont les reprises après silence, les résultats vides, les retouches, les trois présentations, l’insertion unique et les erreurs récupérables. Les nouveaux tests de non-régression ont d’abord échoué sur le comportement précédent.
- Compilation release Apple Silicon réussie ; signature locale et identité du binaire installé vérifiées.
- Parcours natifs clair et sombre inspectés visuellement ; les données de test restent séparées de la bibliothèque réelle.
- **Le parcours réel Option → voix → Option → champ externe reste à valider sur cette version.** Les tests automatisés utilisent des services contrôlés et ne prouvent pas ce parcours matériel.

macOS demande de renouveler les autorisations après le changement de signature locale. L’accès d’accessibilité est renouvelé et confirmé par l’application. Les autorisations Microphone et Reconnaissance vocale ont été réinitialisées uniquement pour DictAI et seront redemandées au prochain démarrage de la dictée. Un document TextEdit distinct est prêt pour le test. La validation des fenêtres audio et l’appui sur Option doivent être effectués par l’utilisateur ; l’outil UI ne sait pas les simuler.

[Journal des tests](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3.1-tests.log) · [Compilation](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/overlay-0.3.1-build.log)
