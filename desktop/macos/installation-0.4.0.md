# Installer et configurer DictAI 0.4.0

[Ouvrir l’installateur de test](/Users/ulliemaillot/Downloads/DictAI-0.4.0-macOS-test.pkg)

Ce paquet installe l’application dans `/Applications/DictAI.app`. Il contient uniquement l’application, ses polices, son icône et ses licences. Il ne contient aucun compte, aucune note ni aucune clé de développement.

## Les Mac visés

La priorité est une dictée locale confortable. La configuration recommandée est un **Mac avec puce Apple M1 ou plus récente, sous macOS 14 Sonoma ou plus récent**. Le Mac de référence vérifié ici est un **M1 avec 16 Go**. Ce choix ne constitue pas une mesure de performances sur tous les modèles, et aucun minimum de mémoire prétendument garanti n’a été inventé.

Le paquet contient également un exécutable **Intel x86_64**, pour les essais. La reconnaissance locale et sa réactivité sur un véritable Mac Intel ne sont pas validées. Il ne faut donc pas annoncer une compatibilité Intel complète à ce stade. L’assistant vérifie la disponibilité du modèle vocal sur le Mac concerné ; il ne bascule jamais vers une transcription en ligne.

Apple distingue bien une [application universelle contenant les deux architectures](https://developer.apple.com/documentation/apple-silicon/building-a-universal-macos-binary) de la [disponibilité effective de la reconnaissance locale](https://developer.apple.com/documentation/speech/sfspeechrecognizer/supportsondevicerecognition).

## Faire l’installation soi-même

1. Quitter DictAI depuis son menu. L’installateur demande sa fermeture et refuse de remplacer une application encore en cours d’exécution.
2. Ouvrir `DictAI-0.4.0-macOS-test.pkg` dans Téléchargements.
3. Suivre l’assistant macOS et valider l’installation avec l’authentification habituelle du Mac.
4. Ouvrir **DictAI dans le dossier Applications à la racine du Mac**. L’ancienne copie de développement se trouve encore dans `/Users/ulliemaillot/Applications` ; elle n’a pas été remplacée pendant la préparation.
5. Suivre les quatre étapes de configuration. Pour synchroniser, utiliser le même compte Google que sur le téléphone et la tablette.
6. Faire l’essai proposé dans un document TextEdit vide : Option, une phrase, Option. Contrôler la qualité du texte, la réactivité, l’insertion unique et la fermeture de la pastille.

Le paquet est fourni pour un **test local**. Il n’a pas de signature Developer ID ni de notarisation Apple : aucune identité de signature valide n’est disponible sur ce Mac. Il ne reproduit donc pas encore la confiance accordée à un téléchargement public signé. La [signature et la notarisation](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution) restent nécessaires avant une diffusion publique normale.

## Recommencer comme un nouvel utilisateur

Pour reproduire exactement le premier démarrage, utiliser une **nouvelle session utilisateur macOS**. Cette session possède ses propres autorisations, son Trousseau et sa bibliothèque locale. Le compte Google se configure alors dans l’assistant, sans modifier les données de la session actuelle.

Dans la session habituelle, **Réglages → Reprendre la configuration** rejoue le parcours et permet de changer de compte. Cette action conserve les notes et les autorisations existantes. Une réinstallation n’est pas une remise à zéro des données ; les autorisations peuvent néanmoins devoir être renouvelées après un changement de signature de développement.

## Vérifications effectuées

- Paquet reconstruit et décompressé : application de production, architectures `arm64` et `x86_64`, minimum macOS 14 pour les deux exécutables, absence de données personnelles, destination fixe `/Applications`.
- Signature technique ad hoc de l’application vérifiée. Paquet non signé et non notarié, explicitement nommé `macOS-test`.
- Assistant macOS parcouru jusqu’au récapitulatif, puis ramené à l’accueil. **Aucune installation lancée à ta place.**
- 110 tests réussis sur Apple Silicon ; 110 tests réussis pour le code Intel sous Rosetta. Le lanceur SwiftPM natif ne pouvait pas charger le module Intel ; les tests Intel ont ensuite été exécutés avec le lanceur x86_64 correspondant.
- Régression vérifiée : rejouer la configuration conserve la bibliothèque ; une sauvegarde impossible ne marque pas la configuration comme terminée ; les autorisations seules ne suffisent pas à déclarer la dictée prête.
- Rendus natifs des quatre étapes inspectés à la taille minimale de fenêtre. Espacements ajustés pour garder les commandes visibles.
- Application actuellement installée conservée en version 0.3.6. Aucun compte déconnecté et aucune autorisation réinitialisée par cette préparation.

Les tests sous Rosetta ne remplacent pas un essai vocal sur un Mac Intel. Un essai complet sur une nouvelle session et la notarisation restent à faire.

## Aperçu de l’assistant

Les captures utilisent une bibliothèque de test, sans données personnelles. Les états d’autorisation correspondent au processus de test.

![Accueil de la configuration](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/setup-0.4.0-1.png)

![Vérification du Mac et de ses autorisations](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/setup-0.4.0-2.png)

![Connexion Google facultative](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/setup-0.4.0-3.png)

![Essai de dictée](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/setup-0.4.0-4.png)

## Reproduction technique

Depuis `desktop/macos` :

```sh
./scripts/test.sh
./scripts/build-installer.sh
./scripts/verify-installer.sh dist/DictAI-0.4.0-macOS-test.pkg
```

Le script de construction accepte `DICTAI_SIGNING_IDENTITY` (Developer ID Application), `DICTAI_INSTALLER_IDENTITY` (Developer ID Installer) et `DICTAI_NOTARY_PROFILE` (profil du Trousseau). Le nom de paquet sans suffixe `test` n’est produit qu’après réussite de la notarisation, de l’agrafage du ticket et du contrôle Gatekeeper. Aucun secret n’est intégré au paquet.
