# Retour téléphone — banc Gemma 0.9.4 du 9 septembre 2026

Rapport complet transmis par Ullie : **14 exemples synthétiques, deux passages, 28 essais**. Les deux passages donnent les mêmes textes et les mêmes statuts ; les durées varient. Il s’agit de 14 contenus distincts. Les mesures proviennent du téléphone d’Ullie ; Codex a analysé le rapport et le code, sans exécuter ces générations Android.

**Précision d’Ullie après réception du bilan : la priorité est un nettoyage utile par le modèle local.** Retirer les hésitations/onomatopées parasites et répétitions involontaires, corriger les maladresses et améliorer la cohérence des phrases tout en conservant l’intention. Une transcription parfaite comparable à un puissant modèle cloud n’est pas attendue ; un résultat globalement propre et agréable à utiliser suffit. Les défauts de découpage ou de paragraphes restent des améliorations secondaires, pas des motifs pour classer tout le post-traitement comme un échec.

Les corrections de forme réussissent sur plusieurs textes. Le banc relève aussi des limites structurelles : **10 sorties de listes acceptées par les contrôles lexicaux, aucune avec toutes les coupures attendues**. Les six sorties rejetées sont des réponses complètes avec des modifications de contenu visibles, pas des dépassements du délai du banc. Ces constats doivent être pondérés selon la priorité de nettoyage précisée par Ullie.

Données détaillées : [corpus, sorties et 28 mesures JSON](benchmarks/local-format/gemma4-poco-f7-094-user-2026-09-09.json). Les entrées sont recoupées avec le corpus de la source `5ee4c5a959694fe4846307a52a2115ea35c1d17d`. Les sorties identiques sont conservées une fois par cas ; chaque passage possède ses propres durées. Les bruts absents du rapport ne sont pas reconstitués.

## Configuration et portée

- Application **0.9.4-wp-gemma-test**, Xiaomi **25053PC47G**, Android **16**.
- `gemma-4-E2B-it.litertlm`, LiteRT-LM **0.17.0**, GPU, MTP activé, thinking désactivé avec budget 0 ; aucun cloud.
- Moteur partagé déjà chargé. Dernière initialisation : **6 203 ms**, valeur historique du moteur ; aucun démarrage à froid mesuré dans ces 28 essais. Caches système/GPU non vidés.
- Limite **20 000 ms** après préparation initiale, file comprise. Les seuils 5/8/10 s servent à comparer les durées et ne coupent pas ce banc.
- Les durées ci-dessous comprennent l’appel backend et la validation, avec la file et la création de conversation, hors préparation initiale. Le premier essai a attendu 1 ms de préparation. Arrêt ASR, affichage et insertion dans une autre application exclus.

Le banc de `LocalFormatBenchmarkDialog` appelle `backend.generate(example.request)` puis `example.request.acceptOutput(raw)`. **Il ne traverse pas `CorrectedTextPreparation`, appelé depuis `OverlayService` en Texte corrigé.** Il ne valide donc pas à lui seul le nouveau retrait déterministe des hésitations, le repli après rejet/délai, ni leur comportement après retouche manuelle. Le moteur est partagé avec l’overlay, mais la préparation de la dictée n’est pas exécutée ici.

## Résultats par exemple

Les durées sont en millisecondes, appel + validation. Le résultat indiqué vaut pour les deux passages.

| Exemple | Passage 1 | Passage 2 | Contrôle des modifications | Structure ou défaut visible |
| --- | ---: | ---: | --- | --- |
| FR, liste avec noms et nombres | 2 240 | 2 209 | Accepté | Une seule puce ; deux coupures manquantes |
| EN, mail avec interdiction | 2 167 | 2 173 | Rejeté | Ajout de Karim comme signature |
| FR, mail très court | 5 | 5 | Accepté, direct sans LLM | Texte inchangé, critère réussi |
| FR, courses sans virgules | 1 810 | 1 796 | Accepté | Trois puces au lieu de six ; trois coupures manquantes |
| FR, complément à conserver | 1 311 | 1 292 | Accepté | Lait de la ferme et pain dans la même puce |
| FR, mail sans ponctuation | 2 458 | 2 453 | Accepté | Cordialement et signature restent dans le corps |
| EN, mail sans ponctuation avec signature | 2 209 | 2 169 | Rejeté | Eli apparaît deux fois |
| FR, courses avec compléments | 1 676 | 1 725 | Accepté | Une seule puce pour les quatre articles |
| EN, liste avec compléments | 1 351 | 1 320 | Accepté | Une seule puce pour les trois articles |
| FR, ancien mail long | 5 536 | 5 555 | Rejeté | Ajout de « et la », omission de « normalement » |
| FR, mail le plus long | 8 946 | 9 135 | Accepté après rétablissement | « aussi » rétabli ; un paragraphe de corps ; coupures non évaluées |
| FR, texte long avec répétitions et deux sujets | 4 562 | 4 529 | Accepté | Corrections et retrait de euh réussis ; 1 paragraphe sur 2 ciblés |
| EN, texte long avec répétitions et deux sujets | 4 534 | 4 538 | Accepté | Corrections et critère de 2 paragraphes réussis |
| FR, mail long avec trois sujets | 5 166 | 5 153 | Accepté | 3 paragraphes de corps, salutation et signature séparées |

Sur les **26 appels Gemma**, **20 sont acceptés et 6 rejetés**. Les deux réponses directes sont acceptées en 5 ms chacune et sont exclues des statistiques LLM. Toutes les sorties acceptées conservent leurs repères de contenu attendus, mais ces repères sont un contrôle partiel.

Les dix sorties de listes échouent sur le regroupement, tout en conservant les mots. Les coupures manquantes sont identiques d’un passage à l’autre. Le cas « Demain, appeler Maëlys…, imprimer… et apporter… » donne désormais un exemple exact d’actions formulées en phrase que le modèle ne sépare pas. Il n’est pas nécessaire de demander à nouveau un exemple pour commencer à travailler ce défaut.

Le texte français corrige « Je je », « la le », « prêt » et retire « euh ». Il garde toutefois les deux sujets dans un seul paragraphe. Le texte anglais atteint ses deux paragraphes, et le mail français à trois sujets atteint ses trois paragraphes de corps. Cela représente **4 réussites sur les 6 essais ayant un minimum de paragraphes défini** ; ce score compte des paragraphes, sans évaluer automatiquement leur pertinence.

Les deux mails anglais rejetés ajoutent un nom en signature : Karim, qui était le destinataire, et Eli, déjà présent. Le premier garde bien l’interdiction « do not ». La comparaison des mots de l’ancien mail long montre l’ajout de « et la » avant « quatrième » et la suppression de « normalement ». Le rapport ne fournit pas de motif détaillé de chaque branche du validateur ; ces différences visibles ne justifient pas d’assouplir indistinctement ses protections.

Sur le mail le plus long, Gemma avait écrit « soit correct. Je ne sais pas… ». Le rétablissement de « aussi » produit « soit correct aussi je ne sais pas… », ce qui rattache les phrases et dégrade leur ponctuation. La salutation et la signature sont bien séparées, mais le corps reste un seul paragraphe. Le banc ne score pas les coupures car le nombre de mots a changé ; il ne faut pas convertir cette absence de score en réussite.

## Durées et mémoire

Pour les 26 appels Gemma avec validation : **minimum 1 292 ms, médiane 2 224,5 ms, maximum 9 135 ms**. Le premier fragment non blanc arrive entre **1 163 et 2 348 ms** depuis l’appel backend. Il ne constitue pas encore un texte final validé et insérable.

**6 appels sur 26 dépassent 5 s ; 2 dépassent 8 s ; aucun ne dépasse 10 s.** Les deux appels les plus longs terminent en 8 946 et 9 135 ms. Cela ne mesure pas le temps complet après le geste d’arrêt d’une dictée ; le banc laisse jusqu’à 20 s et omet l’ASR et l’insertion. Ces résultats seuls ne justifient pas de changer les plafonds de l’overlay.

| Mesure | Avant | Après | Écart |
| --- | ---: | ---: | ---: |
| PSS du processus | 1 992 Mio | 1 966 Mio | −26 Mio |
| RAM disponible | 2 837 Mio | 2 816 Mio | −21 Mio |
| État thermique Android | 0 | 0 | 0 |

La mémoire GPU partagée peut être exclue du PSS. Ces deux relevés ne donnent ni le pic mémoire ni la température intermédiaire, et ne suffisent pas pour conclure sur une fuite mémoire ou une longue session.

## Priorités retenues après la précision d’Ullie

1. Évaluer d’abord le nettoyage en dictée réelle : hésitations et onomatopées parasites, répétitions involontaires, maladresses et cohérence de la phrase. Les noms, nombres et l’intention doivent rester fidèles ; les citations et sons volontairement mentionnés ne sont pas des parasites à supprimer automatiquement.
2. Prendre les corrections du texte français comme un signal positif : euh, répétitions et accord corrigés aux deux passages. Le corpus contient trop peu de cas d’hésitations et de répétitions pour conclure sur leur couverture générale. Le rattachement maladroit des phrases après rétablissement de « aussi » est plus pertinent pour cette priorité que le seul nombre de paragraphes.
3. Garder distincts le test du modèle et l’essai de Texte corrigé dans l’overlay. Le rapport reçu ne remet pas en cause le choix de Texte corrigé déjà confirmé par Ullie, et ne nécessite pas de relancer tout le banc pour vérifier les hésitations.
4. Conserver les exemples de listes et de mails pour des améliorations secondaires. Ne pas lancer une recherche de mise en forme parfaite, imposer un score de paragraphes comme condition d’acceptation globale, ou augmenter la latence uniquement pour satisfaire ces critères.

Cette étape consigne et analyse les résultats. Aucun code applicatif, modèle, prompt ou délai n’est modifié ; aucune nouvelle APK n’est construite.
