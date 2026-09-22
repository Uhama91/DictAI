# Mesurer l'attente de DictAI sur Poco et Pad 7

[Télécharger l'APK 0.9.10](https://github.com/Uhama91/DictAI/releases/download/gemma4-v6-test-35766071521/dictai-gemma4-v6-test.apk) · [Construction GitHub Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/35766071521).

Cette version de diagnostic 0.9.10 corrige le rapport des notes et l'affichage des anciens rapports. Elle conserve Gemma 4 V6 et ses réglages ; elle n'annonce pas encore une réduction du délai. Les poids Gemma 4 V6 déjà téléchargés sont réutilisés.

Le fichier public a été téléchargé et vérifié : code de version 39, même certificat que le pilote précédent, 87 580 033 octets. Il est identique à l'artefact CI et préparé pour une mise à jour sans désinstallation ; l'installation de cette version sur Poco et Pad 7 reste à essayer. SHA-256 : `f2ccd0378d8e359d35cd4fa8566fafcc6ef4bc0741e0c16b24049141de42f3d1`.

## Essai principal : une dictée habituelle

1. Installer l'APK comme mise à jour de DictAI, sans désinstaller l'application.
2. Garder le moteur local et le format **Texte corrigé**, puis faire une dictée représentative du problème.
3. Ouvrir **Mise en forme → Diagnostics → Dernier post-traitement**, puis copier le rapport.

En mode de prise de notes, le bouton micro met la capture en pause. Le bouton **Terminer** achève la note et renouvelle son diagnostic avec la mention **Publication : note enregistrée**. Un essai court suffit pour vérifier ce parcours.

L'écran ouvre le rapport le plus récent. Il affiche la version installée et signale un rapport conservé d'une autre version, sans en modifier la date ni les mesures. Le dernier format distinct reste accessible par un bouton. La version inscrite dans un ancien rapport ne prouve pas que l'APK installé est ancien.

Le rapport contient les durées, les appels progressifs et leur issue. Il ne contient ni la dictée, ni le vocabulaire personnel, ni les clés API. Il permet de distinguer le temps de fin de transcription du temps ajouté par la correction. Un retour au texte brut après rejet du modèle ne sera pas compté comme une correction réussie.

## Essai complémentaire : six textes fixes

Dans **Mise en forme → Diagnostics**, choisir **Mesurer la latence — 6 textes**. Le test utilise six textes français fictifs, courts, moyens et longs, avec trois passages par texte. Prévoir quelques minutes et laisser l'application ouverte. Ne pas dicter pendant le test. À la fin, copier le rapport.

Ce rapport contient les textes fictifs et les réponses du modèle pour permettre d'examiner les corrections, en plus des délais. Ces six cas ne mesurent pas la qualité générale du modèle. Le test n'utilise pas les dictées personnelles. Le délai du premier fragment produit et celui de la réponse complète sont distingués. Un test annulé ou incomplet ne permet pas de comparer les configurations.

La [grille de lecture des six cas](poco-latency-case-review.md) fixe les critères de fidélité et de retouche nécessaires pour la comparaison future.

Pour interpréter les résultats, préciser simplement si le téléphone était chaud, en charge, ou venait de lancer l'application. Un moteur déjà chargé ne représente pas un démarrage à froid.

## Suite du travail

Le deuxième essai utilisateur sur Pad 7 avec 0.9.9 a atteint la limite de 20 secondes sans premier fragment du correcteur, avec une récupération finale ASR de 218 ms. Il n'est pas nécessaire de répéter une longue dictée pour établir ce défaut de réactivité. Le temps de chargement historique du moteur, affiché séparément, ne s'ajoute pas automatiquement à l'attente de cette dictée.

Les six textes serviront à comparer le même parcours avec Gemma 3. Cet APK mesure uniquement la configuration Gemma 4 actuelle. Le choix du modèle, une éventuelle optimisation et le prochain entraînement dépendront du délai complet et des corrections réellement utilisables sur l'appareil. Les résultats Pad 7 et Poco seront présentés séparément.
