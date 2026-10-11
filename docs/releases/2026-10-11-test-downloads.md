# DictAI — version de test du 11 octobre 2026

Cette préversion permet d’essayer DictAI avant le lancement du site. Les fichiers sont téléchargeables directement, sans compte GitHub.

## Télécharger

- **Téléphone ou tablette Android 11 et versions ultérieures, processeur ARM64 :** [DictAI 0.9.17 — APK Android](https://github.com/Uhama91/DictAI/releases/download/dictai-20261011-preview/DictAI-android.apk).
- **Mac sous macOS 14 et versions ultérieures, Apple Silicon ou Intel :** [DictAI 0.4.7 — installateur Mac](https://github.com/Uhama91/DictAI/releases/download/dictai-20261011-preview/DictAI-macOS.pkg).
- [Empreintes SHA-256 des deux fichiers](https://github.com/Uhama91/DictAI/releases/download/dictai-20261011-preview/SHA256SUMS).

Les liens « Source code » proposés automatiquement par GitHub contiennent les sources du projet. Pour installer DictAI, choisissez l’APK ou le paquet Mac ci-dessus.

## Installer sur Android

1. Téléchargez l’APK sur le téléphone ou la tablette, puis ouvrez-le.
2. Si Android le demande, autorisez votre navigateur ou votre gestionnaire de fichiers à installer cette application.
3. Ouvrez DictAI et suivez l’assistant : microphone, modèle vocal et bouton flottant. Activez l’accessibilité pour insérer le texte dans les autres applications.
4. Sur Xiaomi/HyperOS, suivez aussi les étapes indiquées par l’assistant pour les paramètres restreints et la batterie.

Le modèle vocal se télécharge dans l’application : prévoyez une connexion Internet pour cette première configuration. Le compte Google et la correction cloud sont facultatifs. Le cloud utilise la clé OpenRouter personnelle du testeur ; aucune clé personnelle du développeur n’est fournie.

Si DictAI est déjà installé avec la signature de test actuelle, installez l’APK par-dessus. En cas de refus pour incompatibilité de signature, contactez le mainteneur avant toute désinstallation afin de protéger les données.

## Installer sur Mac

1. Téléchargez le fichier `.pkg`, ouvrez-le et suivez l’installateur.
2. Ouvrez DictAI depuis le dossier Applications.
3. Suivez l’assistant pour le microphone, la reconnaissance vocale et l’accessibilité. Confirmez à nouveau les autorisations si macOS le demande après une mise à jour.

Ce paquet de test ne dispose pas encore de signature Developer ID ni de notarisation Apple. Si macOS bloque son ouverture pour cette raison, le testeur peut autoriser spécifiquement ce fichier dans **Réglages Système → Confidentialité et sécurité → Ouvrir quand même**, après avoir tenté de l’ouvrir. [Procédure Apple](https://support.apple.com/fr-fr/102445). La reconnaissance vocale sur un Mac Intel physique reste à valider.

## Ce que contient cette version

Dictée locale, notes, historique des dix dernières dictées, copie du texte, correction OpenRouter facultative et synchronisation facultative avec Google. Cette version contient aussi le bandeau de mise à jour et la vérification manuelle.

Cette publication est une **préversion de test**, distincte du canal stable. Les notifications automatiques de nouvelles versions seront activées lorsque les installateurs de diffusion et le manifeste stable seront publiés. Aucun manifeste `updates.json` n’est fourni dans cette préversion.

Les fichiers ont été contrôlés : signature APK, alignement 16 Ko, paquet Mac universel et absence de données personnelles dans le payload Mac. Les suites locales comptent 759 tests Android et 159 tests Mac réussis. Ces vérifications ne remplacent pas les essais de dictée sur les appareils des testeurs.
