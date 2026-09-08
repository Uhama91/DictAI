# Vérification du post-traitement DictAI

Exécution UTC : 2026-09-08T22:49:57.467917+00:00

- Tests JVM : 251, zéro échec, dans 39 suites.
- Tests Python du contrôleur APK : 18, zéro échec.
- Compilation native ARM64 : réussie ; alignement 16 Ko ; seul export JNI_OnLoad ; aucun GGML/llama externe.
- APK normale : construction, signature, alignement ELF/ZIP 16 Ko vérifiés.
- Absence du poids GGUF expérimental de l’APK normale : vérifiée dans l’archive finale.
- Taille normale : 60702615 octets.
- SHA-256 normale : `321b379f7e198172bbfe0cd8fbefd4f714125e259f47e76451675f95b97ac5c9`.
- Fichier normal conservé : `app/build/outputs/apk/verified/app-normal.apk`.

Les tests couvrent notamment la fidélité intégrale des mots et de chaque préfixe affiché, les interruptions et le retour direct des messages courts. Vocabulaire et nombres disposent aussi de tests automatisés. Les interactions de l’overlay et la latence restent à vérifier sur téléphone. La version normale propose cloud/désactivé ; la mise en page locale reste un essai séparé. Voir `docs/SUIVI-DEVELOPPEMENT.md`.

## Prototype de mise en page locale — 0.7.1-wp-local-test

- APK construite et signature, alignement ELF/ZIP 16 Ko vérifiés.
- Un seul GGUF inclus, LFM2.5-350M Q4_K_M : taille, SHA-256, stockage sans compression, licence et notice vérifiés dans l’APK finale.
- Deux variantes natives et deux prompts partagés présents.
- Taille prototype : 290019789 octets.
- SHA-256 prototype : `793a6d94a99f2f91a6d658aed845d49f1ddbff3a578692bdf7f8063bfe0d6288`.
- Fichier : `app/build/outputs/apk/verified/app-local-layout-test.apk`.

Le prototype conserve le texte et choisit uniquement puces/paragraphes. Il n’est pas validé comme remplaçant général du cloud. Mesure sur téléphone nécessaire ; aucune installation effectuée par Codex.
