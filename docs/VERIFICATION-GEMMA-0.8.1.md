# Vérification DictAI 0.8.1 — diagnostic des formats Gemma

Vérification locale du 9 septembre 2026, version `0.8.1-wp-gemma-test`, code 24.

- 331 tests JVM réussis dans 49 suites, zéro échec. Les nouvelles régressions couvrent la conservation d’un diagnostic Mail après plusieurs dictées Texte et recréation des préférences, les anciennes préférences et le statut attendu du mode Texte.
- Construction de l’APK ARM64 réussie ; signature, alignement ELF/ZIP 16 Ko et contrat de contenu vérifiés.
- 10 bibliothèques natives conformes, LiteRT-LM présent, anciens poids/moteurs de formatage absents.
- APK locale : 78 551 445 octets ; SHA-256 `a17a1e783ce075f7f3ca98b6ac940f643b94bfa42be6e1615f2be1967a131126`.
- `git diff --check` réussi.

Le format est capturé au démarrage dans les options immuables de la dictée, puis utilisé par l’overlay, le calcul anticipé et la finalisation ; la copie nullable et les replis par défaut vers Texte ont été supprimés. Le dernier rapport d’un format demandé est conservé séparément du rapport de la dernière dictée.

Le modèle, le téléchargement déjà installé, le prompt et les limites d’attente ne changent pas. La cause des mails longs rapportés reste à confirmer avec le diagnostic correspondant au format Mail. Aucun geste ou traitement GPU Android n’a été exécuté par Codex pour cette mise à jour.
