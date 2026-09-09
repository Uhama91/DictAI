# Vérification du post-traitement DictAI

Exécution UTC : 2026-09-09T00:51:27.998869+00:00

- Tests JVM : 285, zéro échec, dans 43 suites.
- Tests Python du contrôleur APK : 18, zéro échec.
- Compilation native ARM64 : réussie ; alignement 16 Ko ; seul export JNI_OnLoad ; aucun GGML/llama externe.
- APK normale : construction, signature, alignement ELF/ZIP 16 Ko vérifiés.
- Absence du poids GGUF expérimental de l’APK normale : vérifiée dans l’archive finale.
- Taille normale : 60735383 octets.
- SHA-256 normale : `fa565a1c7f224bcaeb33ef0f113f81d2683877729ed494cc78a0bdf1784bddff`.
- Fichier normal conservé : `app/build/outputs/apk/verified/app-normal.apk`.

Les tests couvrent notamment la fidélité intégrale des mots et de chaque préfixe affiché, les interruptions et le retour direct des messages courts. Vocabulaire et nombres disposent aussi de tests automatisés. Les interactions de l’overlay et la latence restent à vérifier sur téléphone. La version normale propose cloud/désactivé ; la mise en page locale reste un essai séparé. Voir `docs/SUIVI-DEVELOPPEMENT.md`.

## Prototype de mise en page locale — 0.7.3-wp-local-test

- APK construite et signature, alignement ELF/ZIP 16 Ko vérifiés.
- Un seul GGUF inclus, LFM2.5-350M Q4_K_M : taille, SHA-256, stockage sans compression, licence et notice vérifiés dans l’APK finale.
- Deux variantes natives et deux prompts partagés présents.
- Taille prototype : 290052557 octets.
- SHA-256 prototype : `4c59e86a7d71690ca67da14549df2d148b26ebc767788d47bd0771bf254e0fbc`.
- Fichier : `app/build/outputs/apk/verified/app-local-layout-test.apk`.

Le prototype conserve le texte et choisit uniquement puces/paragraphes. Il n’est pas validé comme remplaçant général du cloud. Mesure sur téléphone nécessaire ; aucune installation effectuée par Codex.
