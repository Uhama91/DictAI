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

## Prototype de mise en page locale — 0.7.2-wp-local-test

- APK construite et signature, alignement ELF/ZIP 16 Ko vérifiés.
- Un seul GGUF inclus, LFM2.5-350M Q4_K_M : taille, SHA-256, stockage sans compression, licence et notice vérifiés dans l’APK finale.
- Deux variantes natives et deux prompts partagés présents.
- Taille prototype : 290019789 octets.
- SHA-256 prototype : `793a6d94a99f2f91a6d658aed845d49f1ddbff3a578692bdf7f8063bfe0d6288`.
- Fichier : `app/build/outputs/apk/verified/app-local-layout-test.apk`.

Le prototype conserve le texte et choisit uniquement puces/paragraphes. Il n’est pas validé comme remplaçant général du cloud. Mesure sur téléphone nécessaire ; aucune installation effectuée par Codex.

## Publication GitHub de la version 0.7.2

- [Actions](https://github.com/Uhama91/DictAI/actions/runs/34290412481) : construction et publication réussies pour le commit `0f157a708b4bc4016eaef17e49962f210b8d43e1`.
- [APK directement téléchargeable](https://github.com/Uhama91/DictAI/releases/download/local-layout-test-34290412481/dictai-local-layout-test.apk), asset public chargé ; prerelease non brouillon.
- Taille CI : 290036185 octets. SHA-256 : `97d9846ec1b2509084f9c2d8d4b81652dd5e4b375c660064a1f252cfe49e249d`.
- Empreinte annoncée par GitHub identique à celle vérifiée dans le journal du contrôleur APK.
- Le digest local diffère de la compilation CI ; les deux fichiers sont explicitement distingués ci-dessus.
- Validation sur téléphone du nouveau panneau, des gestes et du démarrage encore requise. Qualité de mise en page du 350M toujours expérimentale.
