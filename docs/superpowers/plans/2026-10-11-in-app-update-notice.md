# DictAI — notification des mises à jour depuis l’application

> Méthode : implémentation native dans cette session, tests ciblés puis suites Android et macOS.

**Objectif :** distribuer DictAI depuis un site, avertir dans l’application lorsqu’une nouvelle version compatible est publiée et ouvrir son téléchargement au clic. Téléphone et tablette utilisent le même APK Android ; le Mac reçoit son installateur universel. Le système conserve la confirmation d’installation.

**Périmètre :** vérification automatique discrète, bandeau, vérification manuelle, liens HTTPS vers les fichiers de la version publiée. La distribution publique et les signatures définitives forment une étape séparée, après contrôle des fichiers. Aucun besoin de Play Store ou de Sparkle pour ce parcours.

## État contrôlé le 11 octobre 2026

- Android : `com.uhama.whisperpin`, version `0.9.16-dictai`, code 45. La CI construit un APK de test ; `app/debug.keystore` est présent dans le dépôt public. Cette clé sert à la compatibilité des installations actuelles et ne doit pas devenir la clé de diffusion publique.
- Mac installé : `com.uhama.dictai.desktop`, version 0.4.6, build 16, application universelle ARM/Intel. Signature ad hoc, aucune identité de signature valide détectée dans le Trousseau interrogé. Developer ID et notarisation ne sont pas encore validés.
- GitHub : dépôt public `Uhama91/DictAI`. Des versions stables anciennes et des préversions expérimentales sont visibles. La dernière version stable, `v0.5.6-wp`, contient uniquement `dictai-0.5.6-wp.apk`, sans `updates.json` ; aucune source stable de mise à jour n’est actuellement configurée dans les applications.
- Site : le domaine `dictai.uhama.fr` ne résout actuellement pas lors du contrôle local. Aucun canal de mise à jour n’est prêt.
- La spécification du 31 mai prévoyait une diffusion directe et un bandeau, mais son changement de package ne convient pas aux données réelles accumulées depuis. Conserver les identifiants actuels ; préparer et tester une migration de signature distincte avant diffusion publique.

## Contrat de publication

Une version GitHub stable porte un fichier `updates.json` et les téléchargements associés. Le site pointe vers les mêmes fichiers. Les préversions expérimentales ne déclenchent pas les notifications du canal stable.

```json
{
  "schemaVersion": 1,
  "android": {
    "applicationId": "com.uhama.whisperpin",
    "versionCode": 46,
    "versionName": "0.9.17-dictai",
    "minSdk": 30,
    "downloadUrl": "https://github.com/Uhama91/DictAI/releases/download/dictai-example/DictAI-android.apk"
  },
  "macos": {
    "bundleIdentifier": "com.uhama.dictai.desktop",
    "buildNumber": 17,
    "versionName": "0.4.7",
    "minSystemVersion": "14.0",
    "downloadUrl": "https://github.com/Uhama91/DictAI/releases/download/dictai-example/DictAI-macOS.pkg"
  }
}
```

Cet exemple est un contrat de test, pas une version à publier. Le manifeste réel doit utiliser les métadonnées extraites des fichiers vérifiés et un tag réel. La source configurée sera `https://github.com/Uhama91/DictAI/releases/latest/download/updates.json`.

## Tâche 1 — contrat et comparaison

Créer `AppUpdateManifest.kt` et `DictAICore/AppUpdate.swift` : lecture bornée à 64 Ko, schéma 1, comparaison sur code/build numérique, identifiant attendu, version minimale du système, texte de version de longueur bornée. Accepter uniquement un téléchargement HTTPS sous `github.com/Uhama91/DictAI/releases/download/`, sans identifiants, fragment, query, traversée de chemin ou port inhabituel, avec l’extension du système.

Écrire d’abord `AppUpdateManifestTest.kt` et `AppUpdateTests.swift`. Les tests partagent les cas : version plus récente, égale, ancienne, installation plus récente que le serveur, autre système, système trop ancien, manifeste incomplet, schéma inconnu, URL externe, URL HTTP et URL trompeuse. Une plateforme absente ne doit pas rendre inutilisable la plateforme présente.

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest --tests '*AppUpdateManifestTest' --console=plain
cd desktop/macos
./scripts/test.sh --filter AppUpdateTests
```

## Tâche 2 — vérification et interface

Créer un service Android `AppUpdateChecker.kt` et un service Mac `Services/AppUpdateChecker.swift`. Conserver le résultat compatible en cache local, indépendant de la synchronisation du compte. Vérifier au retour dans l’application si le dernier essai est assez ancien : 24 heures après succès, une heure après échec. Ajouter une vérification manuelle forcée, sans requêtes concurrentes. Sur Mac, ajouter aussi une vérification périodique pendant que l’application reste ouverte. Le réseau ne doit pas bloquer la dictée et un échec automatique ne doit pas afficher d’alerte.

Dans `MainActivity.kt`, placer un bandeau au-dessus du contenu et une ligne de vérification dans les préférences. Dans `WorkspaceView`, les réglages Mac et le menu rapide, afficher le même résultat. Le bandeau indique « DictAI [version] est disponible » et propose « Télécharger la mise à jour ». Le clic ouvre le navigateur vers le fichier de la bonne plateforme. En vérification manuelle : afficher clairement la progression, une version déjà actuelle ou une vérification temporairement indisponible. Ne pas annoncer « à jour » lorsque le serveur n’a pas répondu.

Tester la cadence, le cache après relancement, l’effacement du bandeau après mise à jour, la reprise après erreur et l’URL réellement ouverte. Produire des rendus Android et Mac en clair/sombre, avec petit écran et police agrandie Android. Inspecter les images et les joindre au compte rendu.

## Tâche 3 — fichiers et publication

Préparer un générateur de manifeste depuis les métadonnées Android/macOS, avec validation de la structure et des URL. Documenter les étapes de publication : construire et vérifier les installateurs, téléverser les fichiers dans une version préparée, vérifier leur disponibilité, puis rendre la version stable visible. Publier le manifeste uniquement lorsque les deux fichiers annoncés existent. Les plateformes peuvent avancer indépendamment ; recopier la version inchangée avec ses métadonnées et son fichier vérifiés.

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
cd desktop/macos
./scripts/test.sh
./scripts/build-installer.sh
```

Avant diffusion publique : clé Android privée et sauvegardée, compatibilité des mises à jour établie ; signature Developer ID Application et Installer, notarisation Mac. Tester deux vraies versions successives sur Poco F7, Pad 7 et Mac avec les données existantes sauvegardées, sans désinstallation. Contrôler notes, dossiers, images, vocabulaire, modèles, historique et permissions après installation. La présence du bandeau seule ne valide pas la conservation des données.

## Points de contrôle

- Serveur absent ou hors ligne : dictée utilisable, aucun faux état « à jour ».
- Versions Android/Mac différentes : aucun mélange entre code Android et build Mac.
- Préversions de recherche : aucune notification sur le canal stable.
- Mise à jour déjà installée : cache filtré avec le code/build actuellement exécuté.
- Signature Android publique différente : migration testée séparément ; aucune désinstallation automatique.

## Étape ultérieure — site et domaine indépendant

Demande complémentaire de l’utilisateur : prévoir un site propre à DictAI, même si sa réalisation vient plus tard, et rechercher un autre domaine peu cher. Le site comportera une présentation courte, les téléchargements Android et Mac depuis les fichiers vérifiés, le numéro de version, les nouveautés, l’aide à l’installation et les informations de confidentialité. Un téléphone ou une tablette recevra l’APK ; un Mac recevra le paquet universel. Les liens du site et le manifeste doivent toujours annoncer les mêmes versions.

Le site peut rester statique. Le domaine du site n’a pas besoin d’être figé dans l’application : la source de versions GitHub reste indépendante, ce qui permet de créer ou déplacer le site sans obliger les utilisateurs à réinstaller DictAI. Prévoir un hébergement statique gratuit ; vérifier les limites du fournisseur au moment du déploiement. Les fichiers lourds peuvent rester sur GitHub Releases.

Recherche du 11 octobre 2026 : `dictai.com` est enregistré ; `dictai.fr`, `getdictai.com` et `dictaiapp.com` ne renvoient pas d’enregistrement dans les services RDAP des registres interrogés. Ils semblent donc disponibles, sous réserve de la vérification finale au panier du registrar et des éventuelles réservations ou tarifications spécifiques. Aucun domaine acheté.

| Option | Prix de création affiché | Renouvellement affiché | Usage proposé |
|---|---|---|---|
| `dictai.fr` chez OVHcloud | 5,99 € TTC la première année, offre à contrôler au panier | 9,35 € TTC/an | Nom court, budget réduit, premiers utilisateurs francophones |
| `getdictai.com` chez OVHcloud | 9,59 € TTC la première année | 16,19 € TTC/an | Adresse internationale |
| `getdictai.com` chez Porkbun | 11,08 USD/an, taxes éventuelles à contrôler | 11,08 USD/an affichés | Alternative internationale à coût annuel réduit |

Le `.fr` est le choix économique prioritaire. Le `.com` est une alternative si la diffusion internationale devient prioritaire. Comparer le renouvellement, pas seulement la promotion initiale. Sources : [tarifs OVHcloud .fr](https://www.ovhcloud.com/fr/domains/tld/fr/), [tarifs OVHcloud .com](https://www.ovhcloud.com/fr/domains/tld/com/), [tarifs Porkbun](https://porkbun.com/products/domains), [RDAP Afnic](https://rdap.nic.fr/domain/dictai.fr), [RDAP Verisign](https://rdap.verisign.com/com/v1/domain/getdictai.com).

## Sources officielles

- [Distribution Android](https://developer.android.com/studio/publish/preparing).
- [Signature et compatibilité des mises à jour Android](https://developer.android.com/studio/publish/app-signing).
- [Distribution Mac hors App Store](https://developer.apple.com/developer-id/).
- [Notarisation Mac](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution).
- [Sparkle](https://sparkle-project.org/documentation/) : pertinent pour une installation gérée dans l’application, au-delà du téléchargement demandé ici.

## Suivi d’exécution — 11 octobre 2026

Travail réalisé dans le checkout existant, propre au départ, sur la branche dédiée `codex/in-app-update-notice`, base `01f449a`. Le parcours a été explicitement approuvé par l’utilisateur ; le site et le domaine sont ajoutés pour une étape ultérieure.

- Tâche 1 terminée : parseurs natifs Android et Mac, comparaison numérique, limites, identifiants, système minimal et URL testés. Les manifestes marqués `testOnly` sont refusés sur le canal public.
- Tâche 2 terminée : services, cache, cadence, bandeaux et vérification manuelle implémentés. Les rendus natifs sont enregistrés et audités dans le compte rendu. La réponse arrivée hors écran apparaît au retour ; les contrôles de modèles restent attachés à leurs vues.
- Tâche 3 terminée pour la préparation locale : générateur fondé sur les vrais fichiers, contrôles des signatures et du payload, procédure de publication et builds produits. La publication publique et les essais sur appareils restent une étape distincte.
- Vérification finale : `:app:testDebugUnitTest :app:assembleDebug` → 759 tests, zéro échec, build réussi ; `./scripts/test.sh` → 159 tests Mac ARM ; Rosetta avec `--arch x86_64` → 159 tests ; tests Python du générateur → 4 réussis.
- Revue finale par un agent distinct : reconstruction Android, retour depuis l’arrière-plan et mélange d’un dossier public/test corrigés. Tests de régression en échec avant correction, puis réussis après correction ; suite Android complète réussie. Aucun défaut important restant signalé.
- Générateur contrôlé sur des fichiers réels : paquet Mac ancien renommé refusé, APK de test refusé pour le canal public ; aperçu local généré uniquement dans `updates-preview.json`.
- Décisions : conserver les identifiants actuels et préparer les signatures publiques séparément, afin de préserver la compatibilité et les données ; utiliser GitHub Releases comme source indépendante du futur domaine. L’accès public reste à valider après publication.

[Compte rendu et aperçus](/Users/ulliemaillot/dev_project/phone-whisper/docs/2026-10-11-app-updates.md).
