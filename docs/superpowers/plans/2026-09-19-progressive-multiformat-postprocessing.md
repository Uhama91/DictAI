# Post-traitement progressif multiformat — plan d'implémentation

**Objectif :** un modèle de post-traitement pour texte corrigé, liste et e-mail, actif pendant les dictées longues et compatible avec les corrections inline.

**Architecture :** Nemotron reste indépendant. Un document versionné conserve source ASR, segments générés et modifications humaines. Un ordonnanceur LLM travaille par passage et publie uniquement des réponses validées encore applicables.

**Stack :** Kotlin/JVM et Android, backend ASR transcribe.cpp/sherpa existant, Python/Transformers/PEFT pour l'entraînement ; choix du runtime compact après mesure. Référence : `../specs/2026-09-19-progressive-multiformat-postprocessing.md`.

**Exécution :** politique canonique Sol–Luna du projet, Luna Max pour toute implémentation et tous les tests. Aucun commit ni push prévu. Le plan complet n'est pas exécuté dans cette recherche : le kit de préparation des données est livré et une sonde des reprises orales est ajoutée après le retour d'essai utilisateur. Pas de remplacement silencieux du modèle actuellement configuré.

## 0. Reprises orales : diagnostic préalable — moyen

- [x] Identifier le dernier APK Actions cité : run `35458263066`, révision `e8249e5`, Gemma 270M V3 Q8_0. Le chemin pilote contourne le filtre conservateur de la branche normale. L'installation sur l'appareil n'est pas inspectée.
- [ ] Comparer sortie brute du modèle, résultat après filtre et texte affiché sur l'appareil.
- [x] Créer `experiments/disfluency_probe_v1/cases.jsonl` et `scripts/probe_disfluency.py` dans le projet de fine-tuning : seize cas synthétiques, comprenant réparations, répétitions utiles, citations et fins inachevées.
- [x] Tester la séparation prompt/cible, les métriques et le refus d'écrasement ; quatre configurations exécutées, sans entraînement ni accès aux dictées privées et holdouts. Suite Python : 178 tests et 4 sous-tests réussis ; preuves dans `reports/disfluency_probe_v1/verification.md`.
- [x] Comparer 270M V3/prompt original, même checkpoint/prompt dédié, Qwen3-0.6B/prompt dédié et Gemma 3 1B IT/prompt dédié : quatre inférences complètes sur MPS, 4/10, 3/10, 2/10 et 4/10 réparations lexicalement exactes. Aucune configuration retenue ; revue et limites dans `reports/disfluency_probe_v1/review.md`.
- [ ] Comparer ensuite les candidats mobiles récents : Gemma 4 E2B LiteRT-LM, Qwen3.5-0.8B puis 2B si nécessaire, témoin Llama 3.2 1B. Vérifier le support runtime avant toute conversion ; ne pas confondre l'empreinte des poids Gemma annoncée à 0,84 Go avec la taille du fichier Android d'environ 2,58 Go.
- [ ] Mesurer ensuite les candidats acceptables dans le runtime Android avec Nemotron actif. La sonde Mac ne décide pas de la latence mobile.
- [ ] Enrichir les épisodes avec réparations implicites, déterminants remplacés, groupes abandonnés et conjonctions utiles à conserver, avant le fine-tuning multiformat.

## 1. Kit de données — moyen, première livraison

Fichiers dans `/Users/ulliemaillot/dev_project/asr-postclean-fr` :

- Créer `src/asr_postclean/multiformat.py` : validation des exemples, groupes de scénarios/épisodes, splits reproductibles, messages prompt/completion.
- Créer `scripts/prepare_multiformat.py` : export dans un dossier neuf et manifeste de provenance/hachages/comptes.
- Créer `experiments/multiformat_v1/seed.jsonl`, `recipe.json`, `README.md` : fixtures synthétiques partielles/finales et recette non lancée.
- Créer `tests/test_multiformat.py` et preuves sous `reports/multiformat_v1/`.

- [x] Tests de contrat : validation des champs, termes protégés et séparation des épisodes ; garde-fous de destination et groupes transitifs.
- [x] Export déterministe, variantes multiformats permises dans leur groupe, doublons entre splits contrôlés, refus d'écrasement.
- [x] 173 tests et 4 sous-tests réussis sous Python 3.12 ; 54 exemples exportés en 42/6/6, trois modes et deux phases présents. Statut `fixture_only_not_training_ready`.
- [x] Cibles synthétiques relues et corrigées ; elles demeurent des fixtures de contrat, sans validation humaine du corpus ni preuve de qualité modèle.

## 2. Corpus et entraînement — moyen

- [ ] Enrichir des épisodes indépendants, avec variantes réellement différentes et revue des cibles. Auditer familles de gabarits, quasi-doublons et séparation des anciens holdouts avant entraînement.
- [ ] Ajouter un entraîneur multiformat distinct ou étendre explicitement `prompting.py`, `tokenization.py`, `training.py`, `evaluation.py` et les CLIs. Le `train_lora.py` actuel n'accepte pas encore ce contrat ; ne pas lui passer directement le nouvel export.
- [ ] RED : modes/phase/contexte présents dans train ET évaluation, loss masquée sur le prompt, refus de troncature, test de chat template exact, aucun accès au test pendant sélection.
- [ ] GREEN : deux comparaisons à budget identique, modèle de base + corpus mélangé, puis adaptateur antérieur + même corpus. Ne pas choisir un adaptateur sur sa loss seule.
- [ ] Sélection sur validation, lecture humaine des déformations et mesure par mode/phase. Le holdout final gelé n'est exécuté qu'après sélection ; une nouvelle itération nécessite un autre holdout.

## 3. Document éditable versionné — complexe

Créer `ProgressiveTranscriptDocument.kt` et son test ; adapter `EditableTranscript.kt` seulement avec des tests de non-régression. Conserver `OverlayService.kt` comme point de raccordement, sans y ajouter toute la logique.

- [ ] RED : une réponse ancienne après frappe, suppression complète, collage, IME et undo ne doit jamais remplacer la correction humaine.
- [ ] Modèle de segments avec versions source/contexte/édition ; ancrages remappables et tombstones pour suppressions. Source brute récupérable ; propositions modèles séparées des éditions.
- [ ] Tests d'insertion/suppression ASR avant un verrou, vocabulaire passant de plusieurs mots à un seul, caractères accentués/emoji et offsets UTF-8/UTF-16, marqueurs d'image, brouillon restauré.
- [ ] Deux reviews sur le même état de code : contrat fonctionnel/sécurité, puis scénarios adverses/maintenabilité/régressions.

## 4. Instantanés ASR et découpage — complexe

Fichiers : `LiveStreamingTranscriber.kt`, `DictationAsrEngine.kt`, `TranscribeCppNative.kt`, éventuellement `transcribe_jni.cpp`, nouveaux `StableTranscriptSegmenter.kt` et tests.

- [ ] RED : aperçu committed différent de full, sherpa sans committed, hypothèse raccourcie, coupure au milieu d'un nombre, débit continu, phrase très longue, finalisation qui change un passage déjà traité.
- [ ] Transporter full/committed/tentative/révision dans un événement cohérent ; conserver la référence à la session.
- [ ] Découper seulement les fenêtres utiles ; borne de taille/âge, suffixe provisoire et contexte borné. Un dépassement sans frontière sûre conserve le texte brut plutôt que d'inventer une fin.
- [ ] Réconcilier le final ASR avec les seules zones non verrouillées ; invalidation des requêtes dépendantes.

## 5. Ordonnanceur et backend compact — complexe

Créer `ProgressiveFormattingCoordinator.kt`. Adapter `LocalFormatting.kt`, `LocalFormatEngine.kt`, `GemmaModelStore.kt` et le contrat de prompt dans des tâches séparées après choix du runtime. Réutiliser les garanties d'annulation déjà présentes.

- [ ] RED : une active/une attente au maximum, aucune famine par debounce, aucune sortie après annulation, contexte révisé, changement de mode et dictée suivante ; arrêt court sans offre anticipée.
- [ ] Une réponse porte toutes les versions ; publication atomique après vérification. Les nouveaux mots ne forcent pas l'abandon d'une requête encore applicable.
- [ ] Adapter explicitement le contrat de `GemmaConservativeEditing`/`GemmaFaithfulLayout` aux réparations autorisées. RED sur nombre changé sans instruction, réparation « 15, non 16 », négation supprimée, occurrence protégée déplacée et correction contextuelle rejetée à tort. Rapporter acceptation brute et après filtre ; aucune prétention de preuve sémantique par regex.
- [ ] Budget temps/mémoire, priorité ASR, politique de suspension progressive et conservation du texte en cas de refus/timeout. Pas de texte privé dans les diagnostics.
- [ ] Test de parité prompt/tokenizer/terminateurs après fusion, quantification et conversion. Vérifier l'installation atomique et le SHA du nouvel artefact avant utilisation.

## 6. Overlay et fin de dictée — complexe

Fichiers : `OverlayService.kt`, `OverlayTranscriptEditor.kt`, `DictationDraftStore.kt` et tests d'intégration/Robolectric.

- [ ] RED : les modifications clavier continuent pendant la génération ; un résultat apparaît avant l'arrêt pour une longue dictée ; seul le reliquat est traité si le final ASR ne change rien.
- [ ] Remplacer le gel global lié à `hasUserEdits()` par des zones protégées. Préserver point de lecture, sélection, clavier, pauses, annulation, images, notes et export.
- [ ] Feedback sobre : aperçu en cours puis segment traité ; aucune dépendance exclusive à la couleur. Confirmer visuellement sur rendus gauche/droite et avec clavier ouvert.
- [ ] Régression sur texte sans LLM, formats personnalisés/cloud existants et ASR batch. Un format personnalisé non couvert garde le traitement final actuel.

## 7. Vérification et livraison

- [ ] Tests Python ciblés puis suite du pilote selon fichiers réellement modifiés.
- [ ] Sous JDK 21 Android Studio : `./gradlew :app:testDebugUnitTest :app:assembleDebug` puis `git diff --check`. Ne pas prétendre valider le téléphone avec Robolectric.
- [ ] Mesurer sur Poco F7 et Pad 7 les mêmes dictées en ASR seul, traitement final et traitement progressif, avec répétitions froid/chaud. Aucun gain temporel annoncé avant cette comparaison.
- [ ] Appliquer les critères sémantiques, de structure, de protection et de latence de la spécification. Publier taux bruts, taux après validation/repli et exemples de défaillance anonymisés.
- [ ] Livrer une version expérimentale seulement après passage des critères ; documenter exactement ce qui reste à mesurer. Pas d'envoi réseau ni de collecte de corrections pour l'entraînement par défaut.
