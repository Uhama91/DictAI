# DictAI : post-traitement multiformat pendant la dictée

Ce dossier documente un modèle de post-traitement commun aux formats texte corrigé, liste et e-mail. La spécification prévoit que Nemotron continue de transcrire pendant que ce modèle traite des passages suffisamment stables. Les corrections manuelles sont prioritaires et le texte final de l'ASR doit être vérifié à l'arrêt.

État au 20 septembre 2026 : le corpus V4 est assemblé (604 exemples d'entraînement, 124 de validation et 24 de test de développement, dont 240 ajouts issus de 40 épisodes synthétiques). Le pilote Gemma 4 E2B a été entraîné et évalué : le score lexical progresse de 64/124 à 76/124, mais des pertes d'information et des erreurs de format empêchent de le retenir pour DictAI. Le PDF décrit la spécification et la préparation ; le bilan d'exécution est publié séparément ci-dessous. Le traitement progressif n'est pas encore activé dans l'application.

- [Dossier de fine-tuning et résultats séparés](/Users/ulliemaillot/dev_project/asr-postclean-fr)
- [Spécification et sources](/Users/ulliemaillot/dev_project/phone-whisper/docs/superpowers/specs/2026-09-19-progressive-multiformat-postprocessing.md)
- [Plan d'implémentation initial](/Users/ulliemaillot/dev_project/phone-whisper/docs/superpowers/plans/2026-09-19-progressive-multiformat-postprocessing.md)
- [Plan V4 de réutilisation du corpus mixte](/Users/ulliemaillot/dev_project/phone-whisper/docs/superpowers/plans/2026-09-19-v4-reuse-mixed-format-corpus.md)
- [Manifeste des données V4](/Users/ulliemaillot/dev_project/asr-postclean-fr/data_v4/manifest.json)
- [Revue du corpus V4](/Users/ulliemaillot/dev_project/asr-postclean-fr/reports/v4/corpus-review.md)
- [Exécution et bilan du pilote Gemma 4 E2B](/Users/ulliemaillot/dev_project/asr-postclean-fr/reports/gemma4_v4/review.md)
- [Ajouts du corpus V4, JSONL gelé](/Users/ulliemaillot/dev_project/asr-postclean-fr/experiments/multiformat_v2/additions.jsonl)
- [README du corpus d'ajouts](/Users/ulliemaillot/dev_project/asr-postclean-fr/experiments/multiformat_v2/README.md)
- [Manifeste séparé de préparation du pilote Gemma 4 E2B](/Users/ulliemaillot/dev_project/asr-postclean-fr/runs/gemma4-e2b-v4-pilot-20260919/manifest.json)
- [Diagnostic historique des reprises orales](/Users/ulliemaillot/dev_project/asr-postclean-fr/reports/disfluency_probe_v1/review.md) (diagnostic de 16 phrases synthétiques ; aucune mesure mobile)
- [Builder PDF](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/progressive-multiformat/build_pdf.py)
- [Version PDF](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/progressive-multiformat/progressive-multiformat-postprocessing.pdf)

Le PDF reprend toute la spécification et ajoute le schéma du traitement en première page. Vérification : six pages A4, six rendus PNG à 150 dpi inspectés page par page, texte complet contrôlé, sources S1 à S16 présentes et liens de référence cliquables vérifiés. Le diagnostic détaillé reste séparé du PDF ; il porte sur 16 phrases synthétiques et ne fournit aucune mesure mobile.

## Page 1 - Décision, schéma et retour d'essai

![Page 1 : schéma du traitement, choix Gemma 4 E2B et diagnostic du retour d'essai](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/progressive-multiformat/renders/page-1.png)

## Page 2 - Candidats locaux et état du code actuel

![Page 2 : comparaison des candidats locaux et début des constats sur le code actuel](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/progressive-multiformat/renders/page-2.png)

## Page 3 - Fonctionnement attendu et contrat d'édition

![Page 3 : fonctionnement progressif, étapes d'édition et protections de version](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/progressive-multiformat/renders/page-3.png)

## Page 4 - Contexte global, sécurité d'édition et corpus

![Page 4 : contexte exact, protections, paramètres et début de la préparation du corpus](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/progressive-multiformat/renders/page-4.png)

## Page 5 - Matrice et évaluation sur téléphone

![Page 5 : matrice des cas à couvrir et début des critères d'évaluation](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/progressive-multiformat/renders/page-5.png)

## Page 6 - Critères, conversion et sources S1 à S16

![Page 6 : critères de passage, conversion prévue et sources primaires S1 à S16](/Users/ulliemaillot/dev_project/phone-whisper/docs/research/progressive-multiformat/renders/page-6.png)
