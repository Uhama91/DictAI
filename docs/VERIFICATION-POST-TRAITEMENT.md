# Vérification du post-traitement DictAI

Exécution UTC : 2026-09-08T20:56:00.882314+00:00

- Tests JVM : 243, zéro échec, dans 38 suites.
- Tests Python du contrôleur APK : 18, zéro échec.
- Compilation native ARM64 : réussie ; alignement 16 Ko ; seul export JNI_OnLoad ; aucun GGML/llama externe.
- APK normale : construction, signature, alignement ELF/ZIP 16 Ko vérifiés.
- Absence du poids GGUF expérimental de l’APK normale : vérifiée dans l’archive finale.
- Taille normale : 60690871 octets.
- SHA-256 normale : `06be31cd85593e37b57e2d68442440a07a7f430f54f3931bdd9cc69ee91f48bd`.
- Fichier normal conservé : `app/build/outputs/apk/verified/app-normal.apk`.

Les tests couvrent notamment la fidélité intégrale des mots et de chaque préfixe affiché, les interruptions et le retour direct des messages courts. Vocabulaire et nombres disposent aussi de tests automatisés. Les interactions de l’overlay et la latence restent à vérifier sur téléphone. La version normale propose cloud/désactivé ; la mise en page locale reste un essai séparé. Voir `docs/SUIVI-DEVELOPPEMENT.md`.

## Prototype de mise en page locale — 0.7.1-wp-local-test

- APK construite et signature, alignement ELF/ZIP 16 Ko vérifiés.
- Un seul GGUF inclus, LFM2.5-350M Q4_K_M : taille, SHA-256, stockage sans compression, licence et notice vérifiés dans l’APK finale.
- Deux variantes natives et deux prompts partagés présents.
- Taille prototype : 290003417 octets.
- SHA-256 prototype : `da602717f5db59478bcc14c8f13df0d73407fa79ea55e98591ff29cf992616b6`.
- Fichier : `app/build/outputs/apk/verified/app-local-layout-test.apk`.

Le prototype conserve le texte et choisit uniquement puces/paragraphes. Il n’est pas validé comme remplaçant général du cloud. Mesure sur téléphone nécessaire ; aucune installation effectuée par Codex.
