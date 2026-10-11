# DictAI — mises à jour depuis l’application

Le parcours approuvé est implémenté sur Android et Mac : DictAI vérifie les versions publiées, affiche un bandeau lorsqu’une version compatible est plus récente et ouvre son installateur dans le navigateur au clic. Les préférences proposent aussi une vérification manuelle ; le menu rapide du Mac reprend cette action.

Le téléphone et la tablette utilisent le même APK Android. Le paquet Mac est universel ARM/Intel. Les builds locaux sont Android **0.9.17-dictai / code 46** et Mac **0.4.7 / build 17**.

## Comportement vérifié

- Vérification automatique au lancement ou au retour dans l’application : une fois par jour après succès, nouvel essai possible après une heure en cas d’échec. Pendant une session ouverte, le contrôle périodique suit la même cadence.
- Cache local pour conserver une proposition hors ligne. Une version déjà installée ne déclenche plus le bandeau.
- Comparaison indépendante des codes Android et Mac, contrôle du système minimal, des identifiants de l’application et des liens HTTPS vers le dépôt DictAI.
- Aucun contenu de dictée, de note ou de compte dans la requête de mise à jour. Les échanges réseau ne bloquent pas la dictée.
- Un serveur indisponible affiche un échec de vérification. La réponse reçue pendant que l’écran Android est fermé apparaît au retour. Le contrôle des versions ne reconstruit pas les commandes de téléchargement de modèles.

## Validation locale

| Contrôle | Résultat |
|---|---|
| Suite Android et construction APK | 759 tests, aucun échec ; build réussi |
| Suite Mac ARM | 159 tests dans 26 suites, tous réussis |
| Suite Mac Intel sous Rosetta | 159 tests dans 26 suites, tous réussis |
| Générateur de publication | 4 tests réussis |
| APK | Signature et alignement ZIP 16 Ko vérifiés ; bibliothèques natives alignées sur 16 Ko |
| Paquet Mac | Deux architectures, signature ad hoc valide, métadonnées et payload contrôlés |
| Ancien paquet Mac renommé avec le nouveau numéro | Refusé : contenu différent de l’application annoncée |
| APK de test présenté pour une publication publique | Refusé ; aucun manifeste public produit |

Les tests de régression ont d’abord reproduit les défauts de reconstruction d’écran et de retour depuis l’arrière-plan, puis sont passés après correction. La suite existante de rendu Android conserve son contrôle du premier élément visible de l’accueil.

Commandes principales :

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
python3 -m unittest discover -s scripts -p test_prepare_update_manifest.py
cd desktop/macos
./scripts/test.sh
arch -x86_64 /bin/bash scripts/test.sh --arch x86_64
./scripts/build-installer.sh
```

## Aperçus natifs audités

Ces images proviennent des vrais composants Android et SwiftUI avec une **offre de version future simulée**. Elles permettent de contrôler l’interface ; elles ne prouvent pas qu’une version publique est actuellement disponible. Les versions illustrées, 0.9.18 et 0.4.8, servent uniquement aux tests.

### Écran Android avec le bandeau intégré

Le bandeau apparaît au-dessus des commandes existantes. Les titres, l’action et les cartes restent lisibles ; la suite de la page défile normalement.

![Bandeau intégré à l’écran Dictée Android](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/android-dictation-update.png)

### Téléphone, clair et sombre

![Bandeau Android clair, largeur 390](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/android-390-light.png)
![Bandeau Android sombre, largeur 390](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/android-390-dark.png)

### Petit téléphone avec police agrandie à 130 %

Le titre et le bouton passent sur plusieurs lignes sans chevauchement ni texte tronqué.

![Bandeau Android clair, largeur 320 et police agrandie](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/android-320-light.png)
![Bandeau Android sombre, largeur 320 et police agrandie](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/android-320-dark.png)

### Tablette

![Bandeau Android clair, largeur 800](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/android-800-light.png)
![Bandeau Android sombre, largeur 800](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/android-800-dark.png)

### Mac, bandeau et réglages

![Bandeau et réglages Mac clairs](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/macos-update-light.png)
![Bandeau et réglages Mac sombres](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/renders/macos-update-dark.png)

## Fichiers préparés et diffusion restante

- [APK Android de test](/Users/ulliemaillot/dev_project/phone-whisper/app/build/outputs/apk/debug/app-debug.apk).
- [Installateur Mac universel de test](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/dist/DictAI-0.4.7-macOS-test.pkg).
- [Manifeste local de test](/Users/ulliemaillot/dev_project/phone-whisper/dist/app-update-preview/updates-preview.json).
- [Procédure de publication](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/app-updates/publication.md).

Les versions installées sur les appareils n’ont pas été remplacées dans ce chantier. Aucun fichier de mise à jour n’a été publié sur le canal stable. Les signatures de diffusion et la compatibilité avec les installations Android actuelles restent à préparer, puis deux versions successives doivent être testées sur les appareils avec leurs données sauvegardées.

Le générateur lit les vrais installateurs, compare le payload Mac à l’application fournie et refuse les signatures de test pour une préparation publique. Les aperçus produisent `updates-preview.json`, marqué `testOnly` et rejeté par les clients du canal public. Les dossiers de préparation publique et de test ne peuvent pas partager leurs manifestes.

## Site et domaine à prévoir

Le [plan](/Users/ulliemaillot/dev_project/phone-whisper/docs/superpowers/plans/2026-10-11-in-app-update-notice.md) comprend maintenant un site distinct : présentation de DictAI, téléchargements Android et Mac, nouveautés, aide à l’installation et confidentialité. Sa réalisation est différée. Le site et l’application utiliseront les mêmes fichiers vérifiés ; le domaine peut être choisi sans modifier la source des versions dans l’application.

Le choix économique proposé est **dictai.fr**. Le registre ne renvoie aucun enregistrement au contrôle du 11 octobre 2026 ; la disponibilité exacte doit être confirmée au panier. OVHcloud affiche **5,99 € TTC la première année**, puis **9,35 € TTC/an**. [Tarifs OVHcloud .fr](https://www.ovhcloud.com/fr/domains/tld/fr/), [contrôle du registre](https://rdap.nic.fr/domain/dictai.fr).

Pour une adresse internationale, **getdictai.com** semble également disponible. Porkbun affiche **11,08 USD/an** à la création et au renouvellement d’un `.com`, taxes éventuelles à contrôler. [Tarifs Porkbun](https://porkbun.com/products/domains), [contrôle du registre](https://rdap.verisign.com/com/v1/domain/getdictai.com).

Aucun domaine n’a été acheté. Un hébergement statique gratuit est prévu dans le plan ; les limites du fournisseur seront vérifiées au moment du déploiement.
