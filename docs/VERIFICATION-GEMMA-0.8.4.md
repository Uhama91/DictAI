# Vérification DictAI 0.8.4 — mesurer Gemma au-delà de cinq secondes

Vérifications du 9 septembre 2026, version `0.8.4-wp-gemma-test`, code 27.

- 361 tests JVM dans 54 suites, aucun échec/erreur/test ignoré.
- Test avec backend simulé terminant après 5 150 ms : le vrai `LocalFormattingSession.finish` accepte le mail long avec le plafond Mail de 8 000 ms ; aucun contournement direct, un appel natif simulé enregistré. Le test ne mesure pas la vitesse de Gemma.
- Tests des routes courtes/longues, espaces Unicode au seuil de 60 mots, limites Mail/Liste et maintien des acquiescements directs.
- Mesures testées : temps avant natif, temps depuis natif, retour moteur, validation, dépassement de quelques millisecondes ; un délai dépassé ne devient pas un faux temps de fin.
- Construction de l’APK ARM64 réussie. Signature et alignement ELF/ZIP 16 Ko vérifiés, dix bibliothèques natives conformes. Contrat du paquet Gemma vérifié, aucun poids ajouté à l’APK.
- APK locale : 78 600 597 octets ; SHA-256 `01dcf03752f51c4efbe43a710fef6bb9395926c818780390aaaeb93a2bbd4894`.
- `git diff --check` réussi.

## Génération complète sur ordinateur

Une génération isolée de la dernière source utilisateur (144 mots, 841 caractères), sans compilation simultanée, termine en **24 923 ms** ; premier fragment à **6 457 ms**. Sortie complète et acceptée, aucun changement lexical. [Source, brut, sortie validée et paramètres](benchmarks/local-format/gemma4-latest-long-mail-host-duration-2026-09-09.json).

LiteRT-LM 0.17.0 CPU, deux threads, modèle et prompt inchangés, thinking false/budget zéro, MTP désactivé sur CPU, température 0,1, seed 1234, contexte 4096, plafond de génération 45 s non atteint. Le moteur est chargé avant la mesure ; les caches ne sont pas vidés. Il s’agit d’un essai hôte, **pas d’une estimation de durée téléphone**.

Les anciennes mesures hôte étaient 15 399 ms pour le premier mail long et 7 281 ms pour le court. Le téléphone GPU avait terminé le premier long en 4 978 / 5 136 ms. Le dernier mail plus long n’a encore aucune durée GPU complète connue.

## Essai sur téléphone livré

- Menu **Mesurer les mails longs avec Gemma** : deux sources, deux passages, exclusivement Gemma, jusqu’à 20 000 ms par appel après préparation initiale, file comprise. Aucun arrêt à 5 ou 8 s dans ce banc.
- Rapport copiable : préparation distincte, délai avant natif, premier fragment, retour moteur, validation et écarts aux seuils 5/8 s. Le retour brut et le verdict de fidélité restent séparés.
- En dictée, les mails de 60 mots ou plus reviennent à Gemma ; attente Mail de 8 000 ms maximum, résultat utilisé dès validation. Les listes restent à 5 000 ms. Le diagnostic expose le plafond effectif.
- La marge de 8 s est un essai de tolérance, pas une accélération du modèle ni une limite finale validée. Un mail peut encore dépasser le délai ou être rejeté pour fidélité.
- Aucun Android connecté à Codex. Le test GPU et le ressenti utilisateur restent à recevoir. Le test instrumenté de l’overlay ajouté en 0.8.3 n’est pas exécuté ici.

## Publication

Publication GitHub Actions en cours après validation locale. Installer en mise à jour conserve Gemma déjà téléchargé.
