# DictAI — retour téléphone et décision du 9 septembre 2026

## Résultat et décision

Le 350M n’est pas validé pour un découpage fiable des listes et mails. Ullie juge désormais la latence acceptable, mais les erreurs de regroupement sont reproduites avec le véritable JNI de l’application compilé sur ordinateur. Ce constat ne prouve pas une impossibilité générale du modèle : il établit une qualité insuffisante avec les configurations effectivement essayées.

Le poids utilisé est bien le [LFM2.5-350M entraîné à suivre des instructions](https://huggingface.co/LiquidAI/LFM2.5-350M), avec le format de conversation documenté ; ce n’est pas le checkpoint Base. Les [données de 84 générations](benchmarks/local-format/feedback-model-comparison-2026-09-09.json) distinguent configuration, sortie et durée. Le nom de signature du retour utilisateur est masqué dans le rapport public.

| Modèle, même consigne avec exemples et copie contrainte | Liste sans virgules | Liste avec virgules partielles | Mails | Médiane des 7 générations sur ordinateur |
|---|---|---|---|---|
| LFM2.5-350M Q4_K_M | Farine et pain regroupés | Plusieurs regroupements erronés | Fermetures attachées au corps | 1,76 s |
| LFM2.5-1.2B Q4_K_M | Six éléments | Regroupements erronés | Résultats irréguliers | 5,16 s |
| Qwen2.5-1.5B Q4_K_M | Six éléments | Trois groupes au lieu de six | Meilleures fermetures ; paragraphes supplémentaires | 6,84 s |
| Qwen3.5-0.8B Q4_0 | Six éléments | Six éléments | Fermeture avec signature encore attachée dans un cas | 4,60 s |

Les modèles étaient déjà chargés ; les durées incluent la lecture du prompt et la génération, pas le chargement ni la chaîne audio/affichage/insertion. Ce sont des mesures sur ordinateur, pas des prédictions téléphone. Les essais ultérieurs de consigne courte ou de ponctuation/casse autorisées pendant le décodage ne résolvent pas la qualité et produisent d’autres mauvais regroupements (« du lait de la ferme », « thé à la menthe »). Ces variantes ne sont pas intégrées à l’APK.

Décision : ne pas présenter un simple changement de prompt comme une correction, ni remplacer automatiquement le 350M par un modèle nettement plus lourd encore imparfait. Livrer séparément les corrections vérifiables et conserver la sélection actuelle local/cloud/désactivé. Aucune dictée n’a été envoyée au cloud pendant ces comparaisons. La mise en forme locale reste un chantier ouvert.

## Correctifs de la version 0.7.2

- Overlay : retrait des boutons Ranger / Notes et Coller ; format sélectionné et moteur prévu visibles ; phase de traitement et confirmation du résultat local, en distinguant une réponse directe sans appel au LLM. Le modèle local est nommé dans les réglages.
- Démarrage : fenêtre de texte préparée masquée avant le premier appui ; états rendus immédiatement sur main ; recherche de champ sensible uniquement si le cloud est demandé. Capacité audio d’une seconde pour absorber la préparation, avec lectures toujours de 20 ms. Le gain réel et les premières syllabes restent à vérifier sur appareil.
- Pause : glissement vers le haut pour insérer/copier, y compris après saisie manuelle. Une demande pendant la mise en pause attend son achèvement. Un export de brouillon réserve l’état pour éviter une reprise concurrente ; un échec conserve le texte. Aucun envoi de message dans l’application destinataire.
- Nombres : « test numéro deux » devient « test numéro 2 » selon la préférence ; « un, 2, trois » avec séparateurs espacés devient « 1, 2, 3 ». Articles, noms propres, identifiants, téléphones et vocabulaire protégé restent prioritaires. Les chaînes ambiguës sans espaces, par exemple `un,2,5`, restent conservées.
- Points finaux : passe locale après le résultat complet, sur la prose normale et le corps des mails ; conservation des listes, formats personnels, signatures reconnues, URL, emojis et ponctuation existante. Aucune modification des retouches manuelles. Les phrases internes ou les signatures ambiguës ne sont pas réécrites.

Un test ASR préexistant attendait qu’un décodeur ait tourné alors qu’un délai de 1 ms pouvait annuler son démarrage. Le test attend maintenant une entrée effective dans le décodeur puis provoque le délai, sans attente arbitraire ; aucun comportement ASR de production n’est modifié par cette correction du test.

## Reprise

1. Sur téléphone : confirmer le moteur réellement appliqué et mesurer le premier démarrage, le geste d’envoi en pause et les corrections de nombres/points.
2. Pour le LLM : comparer sur un corpus tenu à l’écart des consignes, comprenant listes sans virgules, compléments ambigus, signatures et citations. Évaluer qualité avant d’augmenter la taille livrée. Étudier une spécialisation au découpage ou un autre modèle si le gain est reproductible ; ne pas forcer toutes les frontières `du/de la/des` par règle.
3. Garder les demandes vocabulaire et catalogue ASR dans le suivi canonique du dépôt. Les utilisateurs qui choisissent le cloud conservent leur clé et leur choix ; aucune bascule automatique.
