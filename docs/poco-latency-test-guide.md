# Mesurer l'attente de DictAI sur Poco

[Télécharger l'APK 0.9.9](https://github.com/Uhama91/DictAI/releases/download/gemma4-v6-test-35648476423/dictai-gemma4-v6-test.apk) · [Construction GitHub Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/35648476423).

Cette version de diagnostic 0.9.9 conserve Gemma 4 V6 et ses réglages. Elle sert à expliquer les 10–15 secondes signalées ; elle n'annonce pas encore une réduction de ce délai. Les poids Gemma 4 V6 déjà téléchargés sont réutilisés.

Le fichier public a été téléchargé et vérifié : code de version 38, même signature que le pilote précédent, 87 563 641 octets. Il est préparé pour une mise à jour sans désinstallation ; l'installation sur Poco reste à essayer. SHA-256 : `63e8d1945abc4571cd7ba57116b9d6539e4ff1113230421a7ff64218003e5d66`.

## Essai principal : une dictée habituelle

1. Installer l'APK en mise à jour de DictAI, sans désinstaller l'application.
2. Garder le moteur local et le format **Texte corrigé**, puis faire une dictée représentative du problème.
3. Ouvrir **Mise en forme → Diagnostics → Dernier post-traitement**, puis copier le rapport.

Le rapport contient les durées, les appels progressifs et leur issue. Il ne contient ni la dictée, ni le vocabulaire personnel, ni les clés API. Il permet de distinguer le temps de fin de transcription du temps ajouté par la correction. Un retour au texte brut après rejet du modèle ne sera pas compté comme une correction réussie.

## Essai complémentaire : six textes fixes

Dans **Mise en forme → Diagnostics**, choisir **Mesurer la latence — 6 textes**. Le test utilise six textes français fictifs, courts, moyens et longs, avec trois passages par texte. Prévoir quelques minutes et laisser l'application ouverte. Ne pas dicter pendant le test. À la fin, copier le rapport.

Ce rapport contient les textes fictifs et les réponses du modèle pour permettre d'examiner les corrections, en plus des délais. Ces six cas ne mesurent pas la qualité générale du modèle. Le test n'utilise pas les dictées personnelles. Le délai du premier fragment produit et celui de la réponse complète sont distingués. Un test annulé ou incomplet ne permet pas de comparer les configurations.

La [grille de lecture des six cas](poco-latency-case-review.md) fixe les critères de fidélité et de retouche nécessaires pour la comparaison future.

Pour interpréter les résultats, préciser simplement si le téléphone était chaud, en charge, ou venait de lancer l'application. Un moteur déjà chargé ne représente pas un démarrage à froid.

## Suite du travail

Les six textes serviront à comparer le même parcours avec Gemma 3. Ce premier APK mesure uniquement la configuration Gemma 4 actuelle. Le choix du modèle, une éventuelle optimisation et le prochain entraînement dépendront du délai complet et des corrections réellement utilisables sur le Poco.
