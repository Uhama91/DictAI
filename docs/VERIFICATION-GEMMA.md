# Vérification du prototype Gemma — 0.8.0-wp-gemma-test

Exécution UTC : 2026-09-09T06:18:22.659774+00:00

- Tests JVM : 328, zéro échec, dans 48 suites.
- Tests Python du contrôleur APK : 18, zéro échec.
- APK ARM64 : construction, signature, alignement ELF/ZIP 16 Ko vérifiés.
- LiteRT-LM JNI présent ; absence des anciens moteurs llama et des poids 350M vérifiée dans l'APK.
- Gemma est installé séparément dans l'application ; taille et SHA-256 sont vérifiés avant publication du modèle.
- Taille APK : 79311099 octets.
- SHA-256 APK : `b995db0e2cb8673164df91c6a6d3cd1d049d5f01f10efc4979e41963281d4ed4`.
- Fichier local : `app/build/outputs/apk/verified/app-gemma-test.apk`.

Les tests couvrent reprise du téléchargement, vérification d'intégrité, projection fidèle du texte, rejet d'ajouts/traductions, annulation et suppression des sorties tardives. Les tests de transport utilisent des callbacks simulés, pas une exécution GPU Android.

La latence GPU, la qualité des dictées réelles et les gestes restent à mesurer sur téléphone. Aucun appareil Android n'était connecté à cette vérification. Le premier chargement ne constitue pas une dictée instantanée ; le moteur est préchauffé et reste partagé avec l'overlay.

La construction standard 0.8.0-wp (cloud/désactivé) a également été construite, signée et vérifiée : 86710819 octets, SHA-256 `2fbc4b02c4387c8e5dc8bf88e9054a64025f7b1344f4dd94a7615add95e5bd96`, 12 bibliothèques natives conformes. Aucun poids expérimental inclus.
