# V4 : réutilisation du corpus et listes dans le texte corrigé

**Objectif :** reprendre le corpus V3 et l'enrichir pour Gemma 4 E2B, avec nettoyage des reprises, trois formats, états partiels et listes locales dans le texte corrigé.

**Architecture :** nouvelle version de données, construite à partir des splits V3 existants et d'épisodes synthétiques ajoutés. Les fichiers antérieurs et les évaluations réservées restent intacts. Le modèle mobile final dépendra d'une vérification de l'entraînement local, de la conversion et de la qualité.

**Exécution :** politique Sol–Luna ; tâches bornées de risque moyen, TDD et review primaire. Aucun commit ni push.

**État :** corpus V4 assemblé après revue des 40 nouveaux épisodes ; pilote Gemma 4 E2B terminé sur le Mac (604 micro-lots, 151 mises à jour). La comparaison avant/après sur les 124 cas de validation et la sonde distincte de 12 cas sont achevées. Le gain lexical global passe de 64/124 à 76/124, mais les pertes d'information et les formats encore insuffisants rendent le candidat impropre à l'application. Voir le [bilan détaillé](/Users/ulliemaillot/dev_project/asr-postclean-fr/reports/gemma4_v4/review.md).

## Contrat de mise en forme

« Texte corrigé » autorise une structure mixte. Garder les paragraphes narratifs ; convertir une énumération clairement structurée en puces, ou en numérotation si un ordre est explicitement dicté. Conserver l'introduction, la conclusion, l'ordre, les quantités, les conditions et les compléments dans leur rôle d'origine. Une coordination ordinaire (« du pain et de la confiture »), une citation ou une suite ambiguë ne suffit pas à déclencher une liste. N'ajouter aucun titre, item, objet de mail ou formule absent de la source. Les fragments partiels peuvent contenir des items incomplets ; ne pas les compléter avec la suite connue seulement plus tard.

## 1. Reconstruction et provenance

- [x] Importer les 436 exemples train et 76 exemples valid V3, déjà issus des précédentes versions, sans les importer une seconde fois depuis V1/V2.
- [x] Conserver les partitions, les textes et les métadonnées ; associer le mode corrigé et la phase finale aux exemples anciens.
- [x] Ajouter `src/asr_postclean/v4_dataset.py`, `scripts/build_v4_dataset.py` et leurs tests : destination neuve, manifeste, hashes, doublons, groupes et provenance.
- [x] Contrôler automatiquement les collisions avec les évaluations réservées sans exposer leurs contenus ni les utiliser pour écrire des exemples.
- [x] Mettre à jour le contrat multiformat pour autoriser les listes locales en mode corrigé ; préserver les anciens exports comme archives versionnées.

## 2. Nouveaux épisodes

- [x] Écrire 40 épisodes de développement : 28 train, 8 valid, 4 test de développement. Chaque épisode comprend les trois formats et les deux phases, soit 240 nouveaux exemples.
- [x] Couvrir les déterminants et groupes abandonnés, conjonctions utiles ou reprises, répétitions d'insistance, citations, nombres, négations, termes protégés et états partiels.
- [x] Inclure paragraphes avant/après une liste, listes numérotées, listes dans les mails, quantités avec compléments, fin d'item inachevée et contre-exemples à garder en prose.
- [x] Relire les cibles et publier une revue de couverture. Ces données synthétiques, relues par des agents, ne constituent pas une validation humaine ni un benchmark représentatif.

## 3. Gemma 4 E2B

- [x] Vérifier le backend de fine-tuning et l'empreinte mémoire compatibles avec les 16 Go du Mac ; choisir une voie documentée, en environnement séparé.
- [x] Établir le template natif, le masquage du prompt, l'absence de troncature et les arrêts ; les cibles ne sont jamais présentes dans une entrée d'inférence.
- [x] Si la voie locale est viable, lancer un pilote borné puis évaluer sur validation, sans utiliser les anciens holdouts pour choisir l'adaptateur.
- [x] Rapporter le résultat réel et les limites de conversion vers LiteRT-LM ; aucun remplacement Android avant mesure et validation.
