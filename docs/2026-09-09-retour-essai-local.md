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


## Mesures téléphone et essais complémentaires

Ullie a fourni le rapport du menu sur Xiaomi 25053PC47G, Android 16, version 0.7.2. Chargement du 350M : 682 ms. À chaud : premier fragment 700 ms / fin 1723 ms pour la liste FR ; premier fragment 613 ms / fin 1304 ms pour le mail EN. Le message « OK, ça marche. » ne lance pas de génération. [Mesures reçues](benchmarks/local-format/phone-lfm350-2026-09-09.json).

Ce rapport confirme le fonctionnement du JNI dans le test isolé. Il ne prouve pas que chaque dictée réelle a reçu un résultat LLM : la finalisation peut conserver la source après délai, sortie rejetée ou indisponibilité. Les cinq repères affichés vérifiaient le contenu, pas la qualité du regroupement. Les dictées spontanées de liste et mail restent insuffisantes.

Ullie accepte explicitement un modèle/application plus lourd si la compréhension progresse en conservant la réactivité. Il demande de vérifier le mode thinking et la latence. Les essais Qwen3.5 utilisent bien le suffixe officiel de mode sans réflexion (`assistant` puis bloc think vide fermé) ; le Qwen3-4B Instruct 2507 utilise son template sans bloc think. Les deux modèles source sont Apache-2.0 ; les GGUF testés sont des conversions Unsloth, pas des conversions publiées par Qwen.

Sources : [template Qwen3.5-2B](https://huggingface.co/Qwen/Qwen3.5-2B/blob/main/chat_template.jinja), [modèle Qwen3-4B Instruct](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), [GGUF 2B](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/blob/main/Qwen3.5-2B-Q4_K_M.gguf), [GGUF 4B Q3_K_S](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/blob/main/Qwen3-4B-Instruct-2507-Q3_K_S.gguf).

### Résultats sur ordinateur, modèle chargé, deux threads, sans thinking

Les 17 sources comprennent les cas reçus, des compléments à conserver et des mails FR/EN. Une égalité exacte au découpage de référence est un indicateur ciblé, pas un score général de compréhension. Les durées comprennent le prompt et la génération, pas le chargement ni la chaîne ASR/affichage/insertion. [Sorties, prompts, grammaires et mesures](benchmarks/local-format/larger-models-2026-09-09.json).

| Configuration | Découpages de référence | Premier fragment médian | Fin médiane |
|---|---:|---:|---:|
| 350M, consigne avec exemples de cette comparaison | 6/17 | 966 ms | 1481 ms |
| Qwen3.5-2B Q4_K_M, mêmes exemples | 9/17 | 5902 ms | 8442 ms |
| Qwen3.5-2B, consigne brève | 3/17 | 1955 ms | 4517 ms |

Le 2B avec exemples structure les deux mails français mais laisse encore des regroupements erronés en liste. La version brève perd notamment les paragraphes du mail. Sans la grammaire de copie, cette même consigne brève produit six éléments pour les listes simples, mais conserve des mails monoblocs et coupe encore « thé à la menthe » / « lait de la ferme » ; certaines réponses omettent ou répètent du contenu. Une projection lexicale pourrait récupérer des puces/majuscules/ponctuations différentes, mais ne résout pas ces erreurs de compréhension. Seize générations sur dix-sept sont complètes ; la génération incomplète ne reçoit aucune note de regroupement. Cette variante n'est pas intégrée.

Qwen3-4B Instruct Q3_K_S (1 886 997 600 octets) : le lot avec exemples a dépassé 20 secondes avant le premier fragment sur sept cas observés, puis a été interrompu. Le lot bref borné à quatre cas produit quatre réponses complètes/fidèles mais un seul regroupement conforme : la liste utilisateur a trois puces, le mail français reste monobloc, lait de la ferme/pain reste une seule puce ; le mail anglais est structuré. Premier fragment 6,64–10,10 s, fin 9,47–19,03 s. Arrêt de ce candidat sans lancer les treize autres cas ni multiplier les réglages. Les sorties absentes du lot interrompu ne reçoivent aucune note de qualité.

Les lots 350M/2B et l'ancien lot 4B exécutaient les tests natifs avant les cas ; le 4B bref utilise un pilote de cas seuls, avec fragments partiels conservés. Le code natif et les réglages de génération restent identiques.

Une autre formulation, classification START/JOIN sur fragments candidats fournis manuellement, a également échoué : 350M 4/17, variante d'exemples 0/17, Qwen0.8B bref 5/17, LFM1.2B bref 4/17. L'extraction automatique des candidats n'était pas testée. [Rapport séparé](benchmarks/local-format/boundary-decisions-2026-09-09.json).

Décision : aucun changement de modèle livré sur la seule promesse « plus gros = meilleur ». Ces constats concernent les poids quantifiés, consignes, runtime CPU et contraintes effectivement testés ; ils ne prouvent pas l'impossibilité du local. La qualité et la réactivité du remplacement restent à résoudre.

## Correctifs 0.7.3

- Diagnostic du dernier traitement effectivement publié : moteur demandé/appliqué, appel natif ayant produit le résultat, cache/calcul/direct, résultat validé ou motif de repli, délai final et arrêt vers insertion/copie, nombres de lignes/puces. Aucun texte dicté, vocabulaire, nom de format personnel ou clé API stocké dans ce diagnostic. Une génération tardive ne remplace pas le diagnostic du délai. Réglages : « Dernier post-traitement », avec copie.
- Banc du menu : six sources FR/EN, deux passages ; conservation du texte et regroupement évalués séparément. Les listes sans virgules, compléments et mails sans ponctuation font partie des cas, avec dix générations et deux réponses directes. Le banc reste isolé de l'ASR et de l'insertion.
- Nombres : « de un jusqu'à dix » devient « de 1 jusqu'à 10 » ; refus des bornes partielles comme « dix-huitième ». Correction d'une recherche arrière d'énumération trop coûteuse sur les longues proses. Les variantes usuelles de 256 sont couvertes et fonctionnent déjà ; le cas exact du retour utilisateur reste inconnu. Majuscules internes et tirets longs ambiguës restent à étudier séparément.
- Le modèle de l'APK demeure le 350M. Cette version améliore les corrections et le diagnostic, elle ne prétend pas résoudre la qualité des formats locaux.

### Suite conservée

Avant toute nouvelle sélection, définir une évaluation de regroupement et de fidélité sur un corpus tenu à l'écart des consignes. Étudier une spécialisation au découpage et/ou un runtime accéléré sur appareil. Un cache des préfixes immuables via `llama_state_seq_get_data/set_data` est techniquement possible pour Qwen35, mais son gain n'a pas été mesuré et il ne corrige pas la qualité. Ne pas augmenter la taille de l'APK tant qu'un candidat ne montre pas un gain utile. Ne pas basculer automatiquement le cloud : ce choix et la clé restent ceux de l'utilisateur.

## Recherche approfondie complémentaire du 9 septembre

À la demande d'Ullie, la recherche a été élargie aux publications récentes, quantifications entraînées et moteurs Android CPU/GPU/NPU. Le [rapport consolidé](2026-09-09-recherche-avancee-llm-mobile.md) distingue les preuves éditeur des mesures réelles et propose Gemma 4 E2B mobile avec LiteRT-LM GPU comme prochaine expérience. Les mesures CPU précédentes ne suffisent pas à éliminer une voie accélérée. Le téléphone identifié est le POCO F7 standard, Snapdragon 8s Gen 4 ; aucune latence de cette nouvelle configuration n'y a été mesurée.

Trois candidats supplémentaires ont été exécutés sur ordinateur, sans cloud ni thinking : Bonsai-4B, Luth-2-0.8B et Gemma 4 E2B dans le runtime officiel LiteRT-LM. Les 27 générations sont des sondes exploratoires, dont 26 complètes, pas un benchmark de production. Gemma réussit davantage de cas simples, mais un prompt plus strict corrige un mail au prix d'autres défauts : courses regroupées et traduction indésirable d'un nouveau cas anglais. Les sorties exactes, configurations et limites figurent dans le rapport. Aucun remplacement n'est livré sur cette seule base.

La version 0.7.3 de diagnostic est [téléchargeable directement](https://github.com/Uhama91/DictAI/releases/download/local-layout-test-34296957796/dictai-local-layout-test.apk), avec le modèle 350M. Cette recherche n'a pas produit de nouvelle APK. Le suivi des travaux restants demeure dans [SUIVI-DEVELOPPEMENT.md](SUIVI-DEVELOPPEMENT.md).
