# Publication des mises à jour de DictAI

Le parcours est une distribution directe : le site et le bandeau de l’application ouvrent les mêmes fichiers de téléchargement. L’installation se termine dans l’installateur Android ou macOS. Aucun compte DictAI n’est nécessaire pour vérifier une nouvelle version.

## Fonctionnement

Android et macOS consultent `https://github.com/Uhama91/DictAI/releases/latest/download/updates.json`. Le manifeste indique une version indépendante pour chaque plateforme. Seul un code/build plus élevé, pour la bonne application et un système compatible, déclenche le bandeau. Un nom de version lisible ne sert jamais à décider si une mise à jour est plus récente.

La vérification automatique intervient au lancement ou au retour dans l’application, au maximum une fois par jour après succès. Après échec, un nouvel essai automatique est possible une heure plus tard. Pendant une session ouverte, une vérification périodique reprend cette règle. Le bouton manuel permet de réessayer immédiatement. Le dernier résultat compatible reste visible hors ligne ; il disparaît après installation de cette version ou d’une version plus récente.

Le réseau ne bloque pas la dictée. Le client lit au maximum 64 Ko de métadonnées et ne journalise aucun contenu de dictée. La vérification des mises à jour et son cache restent locaux, indépendants du compte et des préférences synchronisées.

## Avant la première version publique

- Android utilise actuellement le package `com.uhama.whisperpin` et une signature de test publique. Préparer une clé privée de diffusion, sauvegardée hors du dépôt et de la CI. Étudier et tester une transition avec filiation de signatures Android avant de remplacer la signature actuelle. Ne pas changer le package ni désinstaller pour contourner une incompatibilité sur les appareils existants.
- macOS utilise `com.uhama.dictai.desktop`. Configurer Developer ID Application, Developer ID Installer et la notarisation. Les scripts de construction acceptent déjà `DICTAI_SIGNING_IDENTITY`, `DICTAI_INSTALLER_IDENTITY` et `DICTAI_NOTARY_PROFILE`.
- Valider une installation depuis le navigateur, puis une seconde version proposée par le bandeau, avec sauvegarde et contrôle des données sur Poco F7, Pad 7 et Mac.

Les builds préparés dans ce chantier restent des versions de test. Le générateur public refuse un APK de débogage ou signé avec la clé de test publique du dépôt, un certificat Android différent du certificat approuvé, une application Mac sans Developer ID et un paquet sans signature Developer ID Installer ou ticket de notarisation accepté.

## Préparer les fichiers

Construire les deux installateurs, puis lancer le générateur sur les fichiers effectivement livrés. Le paquet Mac doit contenir exactement les métadonnées et le binaire de l’application fournie. Le programme contrôle également la signature APK et l’alignement ZIP 16 Ko.

```bash
python3 scripts/prepare_update_manifest.py \
  --android-apk /chemin/vers/DictAI-release.apk \
  --macos-app /chemin/vers/DictAI.app \
  --macos-installer /chemin/vers/DictAI-0.4.7-macOS.pkg \
  --expected-android-cert "$DICTAI_PUBLIC_CERT_SHA256" \
  --tag dictai-20261011 \
  --output-directory dist/public-update
```

Les chemins sont à remplacer par ceux des artefacts contrôlés et le tag par celui de la version réellement préparée. Le dossier produit contient `DictAI-android.apk`, `DictAI-macOS.pkg` et `updates.json`, avec les métadonnées et les empreintes SHA-256 des fichiers. Les empreintes sont utiles pour les contrôles de publication ; le clic ouvre un navigateur et ne vérifie pas lui-même le fichier téléchargé. La signature est contrôlée par l’installateur du système.

Pour une vérification locale des builds de test, utiliser `--allow-test-artifacts`. Cette option produit uniquement `updates-preview.json`, marqué `testOnly`. Les applications refusent ce marqueur sur le canal public. Utiliser un dossier distinct pour les préparations publiques et les aperçus : le générateur refuse de mélanger leurs manifestes. Ne pas renommer ce fichier en `updates.json` pour le publier.

## Rendre une version visible

Préparer une release GitHub en brouillon, téléverser les trois fichiers, vérifier qu’ils proviennent de la même préparation, puis publier la release stable en la marquant explicitement comme dernière version. Les expériences de modèles doivent rester des préversions : elles ne doivent pas remplacer la source publique.

Si une seule plateforme évolue, le manifeste doit conserver les informations exactes de l’autre plateforme et annoncer son fichier vérifié. Ne pas lui inventer un nouveau numéro. Le générateur peut recevoir son installateur inchangé et recopier les deux fichiers dans la nouvelle release.

Une fois la version publiée, contrôler le téléchargement public du manifeste, de l’APK et du paquet Mac, puis le bandeau depuis une installation antérieure. Un fichier absent doit produire « Vérification indisponible », jamais un faux message indiquant que l’application est à jour.

## Distribuer aux premiers testeurs sans site

La [préversion du 11 octobre 2026](https://github.com/Uhama91/DictAI/releases/tag/dictai-20261011-preview) fournit l’APK Android 0.9.17, le paquet Mac 0.4.7 et leurs empreintes. Les fichiers sont téléchargeables sans compte GitHub ; les notes de version expliquent leur installation. Les trois téléchargements publics ont été vérifiés et comparés aux fichiers locaux.

Pour une prochaine préversion, préparer et contrôler les installateurs, créer une release en brouillon marquée comme préversion, joindre les fichiers et les instructions, puis publier avec `--latest=false`. Partager le lien exact de la release. Conserver les téléchargements d’une version publiée pour les testeurs qui l’utilisent encore.

Ce canal de test ne fournit pas de manifeste `updates.json` et ne remplace pas la dernière release stable. Les notifications automatiques restent liées à la préparation d’une diffusion stable signée.

## Site et domaine, à réaliser ultérieurement

Prévoir une page de présentation, deux boutons de téléchargement clairement identifiés, les nouveautés, l’aide à l’installation et la confidentialité. Le site partagera les versions du manifeste, avec des fichiers hébergés sur GitHub Releases. Un hébergement statique gratuit suffit pour démarrer ; le domaine sera choisi séparément.

`dictai.fr` semble disponible d’après le registre interrogé le 11 octobre 2026. OVHcloud affiche 5,99 € TTC la première année, puis 9,35 € TTC/an. `getdictai.com` semble également disponible ; OVHcloud affiche 9,59 € TTC la première année, puis 16,19 € TTC/an. Porkbun affiche 11,08 USD pour la création et le renouvellement d’un `.com`, avec taxes éventuelles à contrôler. Vérifier la disponibilité et le total au panier avant achat. Aucun domaine n’a été acheté. [Tarifs .fr](https://www.ovhcloud.com/fr/domains/tld/fr/), [tarifs .com OVHcloud](https://www.ovhcloud.com/fr/domains/tld/com/), [tarifs Porkbun](https://porkbun.com/products/domains).

Le `.fr` est le choix proposé pour réduire les dépenses annuelles. Le `.com` reste une option pour une adresse internationale. Le domaine du site ne conditionne pas la source des mises à jour dans l’application.
