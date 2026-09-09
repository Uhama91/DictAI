# Vérification du prototype Gemma — 0.9.4-wp-gemma-test

Exécution UTC : 2026-09-09T18:22:19.770349+00:00

- Tests JVM : 429, zéro échec, dans 61 suites.
- Tests Python du contrôleur APK : 18, zéro échec.
- APK ARM64 : construction, signature, alignement ELF/ZIP 16 Ko vérifiés.
- LiteRT-LM JNI présent ; absence des anciens moteurs llama et des poids 350M vérifiée dans l'APK.
- Gemma est installé séparément dans l'application ; taille et SHA-256 sont vérifiés avant publication du modèle.
- Taille APK : 78833036 octets.
- SHA-256 APK : `7dad952b7d9f204bf256823126dddbce62855bc55d982b922da021fd63def42c`.
- Fichier local : `app/build/outputs/apk/verified/app-gemma-test.apk`.

Les tests couvrent reprise du téléchargement, vérification d'intégrité, projection fidèle du texte, rejet d'ajouts/traductions, annulation et suppression des sorties tardives. Les tests de transport utilisent des callbacks simulés, pas une exécution GPU Android.

La latence GPU, la qualité des dictées réelles et les gestes restent à mesurer sur téléphone. Aucun appareil Android n'était connecté à cette vérification. Le premier chargement ne constitue pas une dictée instantanée ; le moteur est préchauffé et reste partagé avec l'overlay.

## APK publique

Publication vérifiée le 9 septembre 2026 : [APK directe 0.9.4](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34388786570/dictai-local-layout-test.apk), [GitHub Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34388786570), source `5ee4c5a959694fe4846307a52a2115ea35c1d17d`. Téléchargement public complet HTTP 200, **78 833 036 octets**, SHA-256 `7dad952b7d9f204bf256823126dddbce62855bc55d982b922da021fd63def42c`. Empreinte identique à l’APK locale finale, à SHA256SUMS, au digest GitHub et au journal CI ; les **1 048 entrées ZIP** sont également identiques. Certificat de mise à jour, version code33 / 0.9.4-wp-gemma-test et alignement ZIP/natif 16 Ko vérifiés. **429 tests JVM / 61 suites**, 18 tests du contrôleur APK et contrat natif réussis. Aucune génération GPU Android ni essai terrain de ce correctif effectué par Codex.
