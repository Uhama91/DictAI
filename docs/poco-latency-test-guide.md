# Mesurer l'attente de DictAI sur Poco

Cette version de diagnostic 0.9.9 conserve Gemma 4 V6 et ses réglages. Elle sert à expliquer les 10–15 secondes signalées ; elle n'annonce pas encore une réduction de ce délai. Les poids Gemma 4 V6 déjà téléchargés sont réutilisés.

## Essai principal : une dictée habituelle

1. Installer l'APK en mise à jour de DictAI, sans désinstaller l'application.
2. Garder le moteur local et le format **Texte corrigé**, puis faire une dictée représentative du problème.
3. Ouvrir **Mise en forme → Diagnostics → Dernier post-traitement**, puis copier le rapport.

Le rapport contient les durées, les appels progressifs et leur issue. Il ne contient ni la dictée, ni le vocabulaire personnel, ni les clés API. Il permet de distinguer le temps de fin de transcription du temps ajouté par la correction. Un retour au texte brut après rejet du modèle ne sera pas compté comme une correction réussie.

## Essai complémentaire : six textes fixes

Dans **Mise en forme → Diagnostics**, choisir **Mesurer la latence — 6 textes**. Le test utilise six textes français fictifs, courts, moyens et longs, avec trois passages par texte. Prévoir quelques minutes et laisser l'application ouverte. Ne pas dicter pendant le test. À la fin, copier le rapport.

Ce rapport contient les textes fictifs et les réponses du modèle pour permettre d'examiner les corrections, en plus des délais. Ces six cas ne mesurent pas la qualité générale du modèle. Le test n'utilise pas les dictées personnelles. Le délai du premier fragment produit et celui de la réponse complète sont distingués. Un test annulé ou incomplet ne permet pas de comparer les configurations.

Pour interpréter les résultats, préciser simplement si le téléphone était chaud, en charge, ou venait de lancer l'application. Un moteur déjà chargé ne représente pas un démarrage à froid.

## Suite du travail

Les six textes serviront à comparer le même parcours avec Gemma 3. Ce premier APK mesure uniquement la configuration Gemma 4 actuelle. Le choix du modèle, une éventuelle optimisation et le prochain entraînement dépendront du délai complet et des corrections réellement utilisables sur le Poco.
