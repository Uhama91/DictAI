# Vérification DictAI 0.8.2 — omissions dans les mails Gemma

Vérification locale du 9 septembre 2026, version `0.8.2-wp-gemma-test`, code 25.

- 343 tests JVM réussis dans 50 suites, zéro échec. Les nouveaux cas couvrent le mail reproduit, une omission dans un mail anglais, la conservation des négations, nombres et noms, les ajouts/substitutions, les séquences ambiguës, troncatures, limites d’omission et coupures incertaines.
- Une vérification par suppression successive de chaque mot vérifie que toute sortie acceptée reconstitue exactement les mots sources et leur ordre.
- Finalisation testée : le mot normalement est rétabli sans second appel au modèle et le diagnostic compte un mot restauré sans en enregistrer le contenu.
- Construction APK ARM64, signature, contrat de contenu et alignement ELF/ZIP 16 Ko vérifiés ; 10 bibliothèques natives conformes.
- APK locale : 78 567 829 octets ; SHA-256 `624f4a623df77d8d6d9544f8dde7563b549ec7d85b8959d1668f85fcf51abc5a`.
- `git diff --check` réussi.

Deux générations CPU sur ordinateur reproduisent le rejet du mail long (normalement supprimé) et l’acceptation du mail court. La nouvelle projection rétablit normalement et conserve les paragraphes du résultat long. Le brut CPU peut différer du brut GPU Android. [Protocole et sorties](benchmarks/local-format/gemma4-long-mail-rejection-2026-09-09.json).

28 sorties précédemment enregistrées ont également été repassées dans la projection : 18 acceptées, toutes avec les unités lexicales exactes de la source. Ce contrôle n’est pas une nouvelle mesure du modèle ni un nouveau taux de réussite représentatif.

Le modèle, le prompt et les délais sont inchangés. La restauration est limitée aux petites omissions certaines dans les mails ; les substitutions, ajouts et formats ambigus restent rejetés. Aucune mesure de latence GPU sur téléphone n’a été effectuée par Codex. Le rapport utilisateur sur 0.8.0 reste la mesure réelle : 4 957 ms d’attente finale et 5 590 ms jusqu’à insertion sur le mail en échec.

## Publication vérifiée

- Source : `38ddfff38a045301908d484883b3f09ed17253fc`.
- [GitHub Actions 34325226264](https://github.com/Uhama91/DictAI/actions/runs/34325226264) : construction, tests JVM, 18 tests Python, signature, alignement et publication réussis.
- Artefact Actions `dictai-local-layout-test`, identifiant `10093571492`.
- [APK directe](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34325226264/dictai-local-layout-test.apk), [prerelease publique](https://github.com/Uhama91/DictAI/releases/tag/gemma-test-34325226264).
- APK publiée : 78 567 829 octets ; SHA-256 `624f4a623df77d8d6d9544f8dde7563b549ec7d85b8959d1668f85fcf51abc5a`, identique à l’APK locale vérifiée.
- Téléchargement HTTP 200 complet vérifié : taille et SHA identiques à `SHA256SUMS`, au journal Actions et à l’empreinte de l’asset GitHub.
