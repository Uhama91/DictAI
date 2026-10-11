# DictAI Mac 0.4.1 — Insertion et ponctuation

La version 0.4.0 installée dans `/Applications/DictAI.app` affichait « Accessibilité à autoriser », tandis que le microphone et la reconnaissance vocale étaient autorisés. Sans cette permission, DictAI peut transcrire, mais ne peut pas insérer le texte dans une autre application ni recevoir le raccourci Option global. La correction automatique était désactivée.

La version 0.4.1 affiche cette situation dès la dictée, y compris dans la pastille. Le bouton d’insertion reste utilisable pour demander un nouvel essai et obtenir un message explicite. Le brouillon reste disponible en cas d’échec.

Au moment d’insérer, DictAI retrouve le champ actuellement sélectionné. Si l’éditeur DictAI possède le focus, il utilise la dernière application externe activée pendant cette dictée. Le champ est relu à ce moment, ce qui permet de retrouver un éditeur recréé et de conserver les modifications déjà faites dans le champ. Les champs sécurisés sont exclus ; une cible indisponible conserve le brouillon. Les éditeurs web reçoivent un collage natif sans restauration forcée d’une sélection AX incompatible.

La transcription Mac utilise le moteur local Apple, différent du moteur Android. La [ponctuation automatique Apple](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/addspunctuation) était déjà demandée. La nouvelle version corrige certains articles capitalisés après une préposition, ainsi que les raccords d’articles après un redémarrage de reconnaissance. Quand une dictée française est terminée, elle reçoit une majuscule initiale et un point final s’ils manquent. Une pause ne crée pas de point. Les retouches humaines, les noms à capitalisation particulière, les identifiants techniques et la ponctuation existante restent protégés. Ces règles locales ne constituent pas une correction grammaticale complète et ne garantissent pas la même qualité que sur Android.

Les 123 tests passent sur Apple Silicon et les mêmes 123 tests passent sous Rosetta en x86_64. Les nouveaux tests vérifient l’avertissement, la recherche tardive du champ, le changement d’application, la récupération du brouillon, la ponctuation et la protection des retouches. Les vues natives ci-dessous utilisent une bibliothèque temporaire et des services contrôlés, sans données de démonstration dans la bibliothèque réelle.

Le contrôle automatique interdit l’accès à Codex. Après renouvellement des autorisations macOS, l’utilisateur a effectué l’essai réel demandé et confirmé : « Le texte s’insère correctement ». L’insertion dans Codex est donc validée par cet essai utilisateur. La qualité de ponctuation sur une dictée longue reste à apprécier à l’usage.

## Aperçus vérifiés

![Pastille : avertissement lisible lorsque l’accessibilité manque](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/fix-0.4.1/fix-0.4.1-permission-compact.png)

![Éditeur : raison du blocage et brouillon conservé](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/fix-0.4.1/fix-0.4.1-permission-editor.png)

## Installation et essai sur le Mac

L’[installateur 0.4.1](/Users/ulliemaillot/Downloads/DictAI-0.4.1-macOS-test.pkg) est compilé et vérifié pour ARM et Intel. Il contient uniquement l’application, sans données personnelles. L’utilisateur a validé l’authentification administrateur et l’installation a réussi. La copie `/Applications/DictAI.app` est maintenant en version 0.4.1, build 11, avec une signature locale vérifiée.

Une sauvegarde de l’[application précédente](/Users/ulliemaillot/Downloads/DictAI-avant-correctif-0.4.1-application.zip) et des [données](/Users/ulliemaillot/Downloads/DictAI-avant-correctif-0.4.1-donnees.zip) a été créée. Les 12 fichiers originaux sont toujours présents ; le contenu de la bibliothèque JSON et les fichiers binaires sont inchangés. Le changement d’ordre des clés JSON lors de la fermeture de l’app a été distingué d’un changement de contenu.

L’ancienne entrée d’accessibilité a été remplacée par la copie actuelle de DictAI située dans `/Applications`. Les réglages de DictAI confirment désormais les trois autorisations : microphone, reconnaissance vocale et accessibilité. Le modèle vocal local français est disponible. L’assistant de configuration est fermé et le raccourci a été réactivé. L’utilisateur a également confirmé les deux autorisations audio et la réussite de l’essai Option → dictée → Option dans Codex.

[Tests Apple Silicon](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/fix-0.4.1-tests.log)

[Tests Intel sous Rosetta](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/fix-0.4.1-rosetta-tests.log) · [Vérification de l’installateur](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/fix-0.4.1-installer.log) · [État de l’installation et conservation des données](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/fix-0.4.1-install.json)
