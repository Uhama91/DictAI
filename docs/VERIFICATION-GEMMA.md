# Vérification du prototype Gemma — 0.9.5-wp-gemma-test

Exécution UTC : 2026-09-09T20:57:30.944632+00:00

- Tests JVM : 440, zéro échec, dans 63 suites.
- Tests Python du contrôleur APK : 18, zéro échec.
- Tests instrumentés Android : compilation réussie, exécution non réalisée faute d'appareil.
- APK ARM64 : construction, signature, alignement ELF/ZIP 16 Ko vérifiés.
- LiteRT-LM JNI présent ; absence des anciens moteurs llama et des poids 350M vérifiée dans l'APK.
- Gemma est installé séparément dans l'application ; taille et SHA-256 sont vérifiés avant publication du modèle.
- Taille APK : 78865904 octets.
- SHA-256 APK : `ea2e8ee4e751a86329ec3ca7601f7ae3ef74862ac1a3a9d103289a859a89e8a8`.
- Fichier local : `app/build/outputs/apk/verified/app-gemma-test.apk`.

Les tests couvrent séparation message/note, confirmation ponctuelle, reprise sûre du brouillon, coordination du clavier, maintien du point de lecture, reprise du téléchargement, vérification d'intégrité, projection fidèle du texte, rejet d'ajouts/traductions, annulation et suppression des sorties tardives. Les tests de transport utilisent des callbacks simulés, pas une exécution GPU Android.

La latence GPU, la qualité des dictées réelles et les gestes restent à mesurer sur téléphone. Aucun appareil Android n'était connecté à cette vérification. Le premier chargement ne constitue pas une dictée instantanée ; le moteur est préchauffé et reste partagé avec l'overlay.
