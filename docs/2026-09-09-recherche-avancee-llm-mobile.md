# LLM mobiles pour le post-traitement de DictAI

## Décision de recherche

**Des solutions récentes justifient de poursuivre le local. La sélection doit porter sur un modèle et son moteur Android ensemble.** Le nombre de paramètres et la taille du fichier ne permettent pas, seuls, de prévoir le temps nécessaire pour produire une liste ou un mail correct.

**Gemma 4 E2B dans LiteRT-LM sur GPU** ressort comme candidat principal pour un prototype accéléré, grâce à ses preuves de performances mobiles. **Luth-2-0.8B**, spécialisé en français sans réflexion, et **Bonsai-4B**, compressé à 572 Mo, sont des découvertes pertinentes ; des sondes supplémentaires montrent toutefois une mise en forme encore insuffisante dans les configurations essayées. **Qwen3.5-2B dans MNN Q4** reste un comparateur utile pour isoler l’effet d’un moteur différent. Aucun de ces couples n’est encore validé dans DictAI sur le téléphone cible.[^1][^2][^3]

La recherche couvre les publications et artefacts disponibles au 9 septembre 2026. Elle distingue les faits des éditeurs, les essais locaux exploratoires et les recommandations d’ingénierie. Les résultats sur ordinateur déjà obtenus ne constituent pas une mesure de latence Android. Une annonce « fonctionne sur téléphone » ne démontre pas une réponse finale presque instantanée.

## Appareil et situation de départ

La référence **Xiaomi 25053PC47G correspond au POCO F7 standard**, avec Snapdragon 8s Gen 4, GPU Adreno 825 et 12 Go de mémoire LPDDR5X. La fiche Xiaomi donne le nom et la référence dans le même document. Il faut distinguer ce téléphone du F7 Ultra et des Snapdragon 8 Elite souvent utilisés pour les démonstrations NPU. Android 16 est la version rapportée par le test DictAI.[^4]

Dans DictAI, le LFM2.5-350M fonctionne actuellement avec llama.cpp sur **deux threads CPU**, sans couches déportées au GPU. La construction désactive Vulkan et KleidiAI. Le moteur reste résident et certaines générations sont préparées pendant la dictée, mais ce chemin n’exploite donc pas les accélérateurs étudiés ici. La grammaire de copie impose de conserver les mots et laisse au modèle choisir les séparations. Elle évite certaines altérations tout en pouvant modifier ses décisions de mise en forme.[^5]

Le test reçu sur téléphone mesure, à chaud, environ **700 ms jusqu’au premier fragment et 1 723 ms jusqu’au résultat complet** pour sa liste française ; **613 ms et 1 304 ms** pour son mail anglais. Le chargement initial était de 682 ms. Ces durées concernent le moteur isolé ; elles excluent arrêt ASR, affichage et insertion dans l’application destinataire. Le texte est bien validé dans ce banc. Son fonctionnement n’établit pas que chaque dictée réelle a reçu une réponse du LLM.[^6]

La version 0.7.3 publiée ajoute précisément le diagnostic du dernier traitement réellement appliqué. Elle conserve le 350M et ne prétend pas résoudre son découpage. Cette base permet de comparer les futurs candidats sans confondre résultat direct, réponse du modèle, cache et repli vers le texte source.[^7]

## Ce que doit mesurer la vélocité

Le temps perçu comporte plusieurs étapes : chargement éventuel, lecture du prompt, génération, validation puis insertion. Le **TTFT**, temps jusqu’au premier token, ne couvre que le début de la réponse. Le débit en tokens par seconde décrit la génération une fois lancée ; un token n’équivaut pas nécessairement à un mot français.

Pour une exécution séquentielle, une approximation utile est : **durée finale = chargement + traitement du prompt + génération + validation/insertion**. Avec un moteur déjà préparé, le premier terme peut disparaître du geste courant. Avec du calcul anticipé pendant la dictée, une partie des autres termes peut être effectuée avant l’arrêt ; une modification finale de l’ASR ou un changement de format peut toutefois invalider ce travail.

Illustration arithmétique, sans prédiction pour le POCO : à 50 tokens/s, générer 60 tokens prend environ 1,2 seconde ; à 90 tokens/s, environ 0,67 seconde. Il faut encore ajouter ce qui précède et suit la génération. Un premier token à 300 ms ne signifie donc pas qu’un mail entier est insérable à 300 ms.

Le critère principal recommandé est **le délai après l’arrêt jusqu’au texte correct, validé et insérable**, avec son p95, soit le délai respecté par 95 % des essais. Le premier fragment visible reste utile, mais une réponse rapide ensuite rejetée ne remplit pas l’objectif. Le mode Normal et les acquiescements courts doivent conserver un chemin direct lorsque le LLM n’apporte rien.

Des budgets initiaux peuvent guider le développement : premier résultat utile chaud autour de 300 ms, supplément final après arrêt de 500 à 800 ms sur les messages courts. Ce sont des **objectifs proposés**, pas des capacités établies ni des seuils universels de perception. Le ressenti déjà jugé satisfaisant avec le 350M constitue aussi un témoin à conserver.

## Candidats et ordre de priorité

Les tailles ci-dessous sont des tailles de fichiers ou de poids identifiés, en unités décimales. Elles n’incluent pas systématiquement le moteur, les caches et la transcription résidente. Les écarts entre variantes d’un même modèle sont substantiels.

| Candidat | Empreinte identifiée | Français / anglais | Place dans l’évaluation |
|---|---|---|---|
| Gemma 4 E2B mobile | Paquet GPU consulté 2,01 Go ; paquet standard 2,59 Go | Multilingue, dont FR/EN | Priorité pour le couple capacité et moteur mobile |
| Luth-2-0.8B Q4_K_M | 529 Mo | FR spécialisé ; EN non validé dans Luth-2 | Réserve : sondes de mise en forme insuffisantes |
| Luth-2-2B Q4_K_M | 1,27 Go | Même réserve sur l’anglais | Étape suivante si le 0.8B manque de capacité |
| Qwen3.5-2B MNN Q4 | 1,18 Go de poids texte, fichiers auxiliaires en plus | Multilingue FR/EN | Comparateur de runtime ; modèle déjà essayé autrement |
| LFM2.5-1.2B-Instruct QAD Q4_0 | 696 Mo | FR/EN annoncés | Comparateur compact non-thinking, déjà partiellement étudié |
| Bonsai-4B Q1_0 | 572 Mo | Base Qwen3 ; FR spécifique non démontré | Compression intéressante, sondes de structure insuffisantes |
| Ternary-Bonsai-4B | 1,14 Go en Q2_0_g64 actuel | Même réserve linguistique | Réserve après le binaire, avec fichier/runtime assortis |
| Gemma 4 E4B mobile | Mémoire de chargement texte estimée 2,2 Go, hors caches et autres composants | Multilingue | Si E2B échoue en qualité ; débit inférieur |

Sources des tailles, langues et formats : Gemma,[^8][^9] Luth,[^2][^10] MNN/Qwen,[^3][^11] Liquid,[^12][^13] Bonsai.[^14][^15]

### Gemma 4 E2B : la piste mobile la mieux étayée

Gemma 4, annoncé en avril 2026, apporte des variantes E2B/E4B conçues pour les appareils mobiles, des instructions système et un mode de réflexion configurable. La famille prend en charge plus de 140 langues. Sa licence Apache-2.0 facilite l’intégration des poids dans une application sous réserve de conserver les notices.[^16]

La distribution mobile combine plusieurs précisions de poids et des embeddings accessibles par mémoire mappée. Google donne une estimation de mémoire de chargement d’E2B d’environ **0,84 Go en texte seul**, mais ce chiffre n’est ni le téléchargement ni toute la RAM du processus. Le paquet standard consulté fait 2 588 147 712 octets ; la variante GPU consultée 2 008 432 640. Les buffers, le contexte et les autres composants s’ajoutent. Le modèle « E2B » a davantage de paramètres stockés que ses deux milliards effectifs ne le suggèrent.[^8][^9]

Les versions QAT publiées en juin adaptent l’entraînement à la compression. Le format mobile utilise une combinaison de poids 2/4/8 bits ; un GGUF Q4 ordinaire n’est pas équivalent. Il faut donc comparer des artefacts précisément nommés, et mesurer le paquet mobile avec LiteRT-LM.[^17]

L’autre progrès est le **MTP**, prédiction de plusieurs tokens avec un petit modèle auxiliaire dont les propositions sont vérifiées. Il peut accélérer la sortie sans remplacer le modèle principal par le petit proposant. Son coût et son gain varient avec la tâche et le backend. Un réglage qui accélère la réécriture sur GPU peut apporter peu sur CPU, voire ralentir certains cas ; un test avec et sans MTP reste nécessaire.[^18]

Une sonde supplémentaire a chargé le paquet officiel vérifié dans **LiteRT-LM 0.17 sur CPU Linux**, sans thinking ni grammaire de copie. Avec les mêmes quatre sources et instructions naturelles que les sondes libres Bonsai/Luth, Gemma fournit quatre réponses complètes : les deux listes et le mail anglais sont satisfaisants. Le mail français invente un objet, ajoute une formulation, déplace « Madame Martin » de la signature vers le destinataire et produit un nom fictif de remplacement. C’est un progrès de capacité ciblé, avec un défaut de fidélité qui interdit de considérer ce réglage comme prêt à livrer. Les temps ne sont pas comparables à ceux du JNI : runtime et quantification diffèrent, et un avertissement de cache XNNPACK a été observé.[^49]

Un second essai a figé une seule consigne plus conservative : tous les mots, leur ordre et leur langue à préserver, seuls ponctuation/casse/retours autorisés, aucun objet ajouté. Les quatre cas précédents et trois nouveaux exemples ont été testés sans ajuster ensuite le prompt. Sept réponses sont complètes, cinq sont satisfaisantes. Le mail français initial est réparé ; deux défauts subsistent : les courses redeviennent quatre puces au lieu de six, et un nouveau mail anglais est traduit en français. Le système de cette seconde sonde était en français pour tous les cas, différence documentée qui peut contribuer à ce dernier défaut. Ce petit corpus exploratoire confirme un potentiel, avec une configuration encore à fiabiliser ; il ne représente pas un taux de réussite en production.[^54]

### Luth-2 : spécialisation française plutôt qu’augmentation de taille

KuraKura AI a publié Luth-2 en août 2026 en tailles 0.8B, 2B et 4B, à partir de Qwen3.5. La spécialisation utilise de la distillation orientée français. Les modèles sont explicitement sans raisonnement et sous Apache-2.0. Leurs auteurs évaluent le suivi des consignes françaises avec le thinking désactivé, sur dix passages.[^2]

Dans ce protocole, **Luth-2-0.8B passe de 44,47 à 71,23 sur IFEval français**, et de 32,72 à 61,52 sur Multi-IF français, par rapport à son modèle source. Le 2B passe de 61,91 à 75,06 et de 45,38 à 69,67. Ces résultats des auteurs portent sur leurs évaluations, sans démontrer la même progression après quantification ou dans les dictées DictAI.[^2][^10]

Le template Luth-2 ferme déjà le bloc de réflexion avant la génération de la réponse. L’architecture reste celle de Qwen3.5 : une conversion MNN est donc une possibilité technique, mais aucun export Luth MNN validé n’a été confirmé. **Aucune mesure de latence mobile ni évaluation anglaise propre à Luth-2 n’a été trouvée dans les sources retenues.** Les résultats anglais de Luth-1 ne doivent pas lui être attribués.[^19]

Le même contrôle exploratoire que pour Bonsai a été réalisé sur le GGUF Luth 0.8B : huit réponses complètes, une seule correctement structurée, la liste avec compléments en sortie libre. La liste de courses libre comporte cinq puces en fusionnant lait et oranges ; le mail français transforme une interdiction en constat et ajoute une formule absente ; le mail anglais invente un objet. Les quatre sorties contraintes conservent les mots mais restent monoblocs. Le template embarqué correspond au template officiel sans thinking. Les paramètres étaient ceux de DictAI, différents des recommandations éditeur : ce contrôle justifie de ne pas livrer ce poids immédiatement, sans constituer une évaluation générale optimale du modèle.[^47]

### Qwen et Liquid : revoir le moteur peut changer le verdict de vitesse

Le Qwen3.5-2B déjà essayé dans DictAI ne doit pas être éliminé de tous les usages Android sur la base du seul CPU hôte. MNN fournit un export Q4 de l’équipe du moteur, avec poids vision séparés. Pour le texte seul, une configuration cohérente sans branche vision évite cette charge. Il reste à vérifier si l’amélioration de capacité obtenue sur certains mails survit au nouveau moteur et reste assez fiable sur les listes.[^3]

LFM2.5-1.2B-Instruct est distinct de sa version Thinking et annonce FR/EN. Liquid publie pour Instruct des mesures mobiles avec 1 K tokens d’entrée puis 100 de sortie : 4 391 tokens/s de préfill et 82 de décodage sur le NPU d’un ROG Phone 9 Pro ; 335 et 70 sur CPU d’un Galaxy S25 Ultra. **Ce ne sont pas le même téléphone et le même runtime** : ce tableau ne mesure donc pas isolément un gain NPU universel.[^12]

Les versions **QAD Q4_0**, publiées en août, sont réentraînées après compression pour réduire la dégradation. Elles peuvent être plus pertinentes qu’une quantification plus agressive choisie uniquement pour sa taille. Le 1.2B QAD a déjà été essayé dans un cadre plus contraint ; un nouvel essai doit tester une hypothèse différente, comme le moteur accéléré ou la validation séparée de la génération. Le 350M QAD sert plutôt de contrôle à taille comparable que de promesse d’intelligence nettement supérieure.[^13]

### Bonsai : compression réelle, preuve de qualité encore insuffisante

PrismML publie des modèles binaires et ternaires adaptés à la très basse précision. Bonsai-1.7B tient dans environ 248 Mo et Bonsai-4B dans 572 Mo. Le 4B conserve une architecture de quatre milliards de paramètres ; le Q1_0 stocke environ 1,125 bit par poids avec ses échelles. Cela illustre précisément la possibilité de conserver davantage de paramètres dans un petit fichier. Ce n’est toutefois pas une garantie de conservation de toutes les capacités du modèle initial.[^14][^20]

La publication technique décrit des évaluations sans thinking. Les démonstrations mobiles de débit les plus visibles portent sur iPhone, avec environ 44 tokens/s pour le 8B. Elles ne donnent pas un TTFT complet sur le POCO. Les kernels Q1_0 sont désormais présents dans llama.cpp sur plusieurs backends ; les formats ternaires récents doivent être distingués des anciens fichiers Q2_0 au nom identique.[^21][^22]

Un contrôle exploratoire supplémentaire a été exécuté avec **le vrai JNI DictAI sur ordinateur**, poids 4B Q1_0 vérifiés, template officiel avec bloc think fermé, deux threads et les paramètres actuels de DictAI. Quatre sources ont été soumises en génération libre puis en copie contrainte : courses, compléments, mail FR et mail EN. Sur huit sorties, sept sont complètes ; une seule est correctement structurée, la liste avec compléments en mode libre. Les sept sorties complètes conservent les mots et leur ordre ; les autres défauts sont des regroupements monoblocs. Le mail FR libre atteint le délai de 20 secondes et ne reçoit pas de score de qualité.[^23]

Ce contrôle ne prédit pas la vitesse ARM/GPU. Il suffit néanmoins à **ne pas retenir Bonsai comme remplacement immédiat validé**. Une meilleure compression n’a pas corrigé spontanément le format dans cette configuration. Une variante ternaire ou un autre entraînement reste possible, sans justification pour livrer automatiquement un poids supplémentaire.

## Preuves de performances Android

Voici des résultats éditeurs avec leur portée. Ils servent à choisir les expériences, pas à annoncer les performances du POCO F7.

| Modèle / appareil | Backend | Prompt, tokens/s | Sortie, tokens/s | Premier token |
|---|---|---:|---:|---:|
| Gemma 4 E2B / S26 Ultra | CPU | 557 | 46,9 | 1,8 s |
| Gemma 4 E2B / S26 Ultra | GPU | 3 808 | 52,1 | 0,3 s |
| Gemma 4 E2B / S26 Ultra | GPU + MTP, réécriture | Non publié | 87,4 | Non publié |
| Qwen3-0.6B / Snapdragon 8 Elite | MNN CPU | 336 | 94,3 | Non mesuré |
| Qwen3-0.6B / Snapdragon 8 Elite | MNN OpenCL | 1 491 | 71,4 | Non mesuré |
| Qwen3-0.6B / Snapdragon 8 Elite | MNN Hexagon | 2 667 | 68,5 | Non mesuré |

Pour E2B sans MTP : entrée de 1 024 tokens, sortie de 256, contexte de 2 048, CPU quatre threads, caches déjà initialisés, chargement exclu. La ligne MTP provient d’autres prompts et longueurs ; sa baseline GPU propre est 51,5 tokens/s. La mémoire CPU annoncée en mode GPU ne représente pas toute la mémoire de l’accélérateur.[^1]

Pour MNN 3.6.1 : poids W4, cinq passages, médiane, tests pp512 et tg128 séparés. La génération tg128 part avec peu de contexte ; une mesure combinée prompt puis génération est préférable pour DictAI. Ces chiffres montrent aussi qu’un NPU peut accélérer fortement le prompt sans produire les tokens plus vite que le CPU.[^24]

Une preuve différente concerne **FunctionGemma Mobile Actions 270M** : la carte LiteRT annonce sur S25 Ultra CPU 0,24 seconde au premier token et 154,2 tokens/s, avec 512 tokens d’entrée, 256 de sortie et caches préparés. Cette vitesse est mesurée sur un modèle spécialisé dans des commandes mobiles. Elle établit l’intérêt des petits spécialistes, sans prouver qu’il saurait formater une dictée ; il faudrait l’entraîner et l’évaluer sur cette tâche.[^48]

### Lire les nouveaux classements avec leur protocole

Pipette, publié par Liquid en août 2026, rassemble des comparaisons récentes. Sa couverture Android publiée utilise llama.cpp sur CPU de Galaxy S26 Ultra ; elle ne classe pas LiteRT-LM ou MNN sur GPU. Aucun résultat POCO F7 pertinent n’a été établi dans les sources retenues.[^50]

Son « TTFT » mesure actuellement le **préfill seul**, contrairement au premier fragment reçu par DictAI. Les téléphones du laboratoire sont branchés et refroidis activement ; chargement et échauffement sont exclus. Ces conditions rendent le classement reproductible, mais différent d’une dictée sur batterie. La qualité est évaluée séparément sur H100. Ces limites expliquent pourquoi un palmarès général ne suffit pas à trancher le cas de l’application.[^51]

## Moteurs et fournisseurs réellement utilisables

**LiteRT-LM** fournit une API Android embarquée, GPU OpenCL, cache et génération asynchrone. Le modèle est exécuté dans l’application. Son initialisation peut prendre plusieurs secondes ; elle doit être sortie du geste de finalisation. Le mode sans réflexion est explicite, avec `ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)`. Le MTP est une option distincte : accélérer la génération et désactiver le raisonnement répondent à deux coûts différents.[^25]

**Alibaba MNN** est une seconde voie pour les architectures Qwen3.5 et LFM. Il propose CPU, OpenCL, réutilisation de cache et un benchmark combiné entrée/sortie. L’OpenCL effectue un tuning initial qu’il faut conserver. Le nombre de threads d’une configuration GPU peut coder des options de backend ; il ne doit pas être interprété comme un nombre de cœurs CPU. Les exports et le runtime doivent être assortis, notamment pour l’attention linéaire.[^26]

**Qualcomm GenieX**, issu de Nexa, propose GGUF et paquets QAIRT compilés par puce. Sa documentation Android NPU consultée cible SM8750 et SM8850, soit les familles 8 Elite documentées, pas le 8s Gen 4 du F7. Le SDK actuel est BSD-3-Clause ; les anciens paquets Nexa peuvent garder des conditions différentes. Par exemple, le package LFM2.5-1.2B-npu consulté conserve CC-BY-NC-4.0 et une activation par jeton. Une licence de SDK ne remplace pas celle des poids.[^27][^28]

**RunAnywhere / QHexRT** annonce des modèles Bonsai sur NPU et publie des bundles. Le bundle 4B consulté ne contient qu’une variante v81 et environ 1,42 Go de fichiers, dont des embeddings FP16 ; il n’a donc pas la taille du GGUF de 572 Mo. Ni sa page ni les démonstrations iPhone ne certifient son fonctionnement sur le F7. La voie mérite une veille technique, après confirmation du chipset et des conditions de distribution.[^29]

**LEAP** simplifie les bundles Liquid et le streaming, mais sa documentation mentionne llama.cpp : sa présence ne prouve pas une accélération NPU supplémentaire. **ZETIC Melange** propose LFM1.2B et des modes vitesse/précision ; les grands facteurs d’accélération visibles dans ses benchmarks généraux portent notamment sur la vision, pas sur une mesure DictAI FR/EN. Ces offres sont des moteurs à évaluer, pas des preuves d’un nouveau modèle plus intelligent.[^30][^31]

**llama.cpp accéléré** pourrait aussi préserver une partie de l’intégration existante. Son support OpenCL cible des Adreno, avec une liste de GPU vérifiés qui ne mentionne pas encore explicitement l’Adreno 825. **ExecuTorch et MLC** constituent d’autres chaînes Android, mais nécessitent les bons exports et bibliothèques ; ils ne suppriment pas la nécessité de mesurer le modèle choisi. Le backend MediaTek ne concerne pas le NPU Qualcomm du F7.[^32][^33]

### Gemini Nano / AICore et la contrainte de l’overlay

Les API locales Android peuvent sembler idéales pour la correction et la réécriture. Elles présentent cependant un obstacle précis : **ML Kit GenAI interdit l’inférence lorsque l’application n’est pas l’application principale au premier plan**, même avec un foreground service. DictAI fonctionne justement au-dessus d’une autre application. De plus, la liste consultée nomme le F7 Ultra, sans le F7 standard. AICore n’est donc pas la fondation à retenir pour garantir ce parcours.[^34]

## Autres modèles examinés

| Famille | Intérêt | Motif de priorité inférieure |
|---|---|---|
| Gemma 3n E2B/E4B | Conception mobile, FR/EN | Gemma 4 mobile fournit une piste plus récente à comparer en premier |
| Gemma 3 270M / FunctionGemma | Très petits, spécialisation possible | Function calling ou adaptation de tâche ; pas une réécriture générale déjà prouvée |
| SmolLM3-3B / Phi-4-mini / Ministral 3 3B | Instruction et multilingue selon modèle | Alternatives crédibles, preuves de latence sur F7 absentes ; pas nécessairement plus petites |
| MiniCPM5-1B / 2B | Releases et quantifications très récentes | Français non documenté dans les preuves retenues ; export mobile annoncé à vérifier |
| Falcon-H1-Tiny multilingue 100M | Français, très petite taille | Les auteurs reconnaissent une capacité absolue modeste ; pas une montée en qualité démontrée |
| LFM2.5-2.6B | Modèle récent plus capable | Raisonnement systématique, sans mode non-thinking officiellement établi |
| LFM2.5-8B-A1B | Faible calcul actif par token | Poids totaux et fichier de plusieurs Go ; raisonnement ; RAM active ASR à ajouter |
| Nemotron 3 Nano 4B | Compression, mode thinking désactivable | Anglais officiellement supporté ; pas de preuve Android FR/EN pour ce besoin |
| BitNet b1.58 2B4T | Entraînement ternaire natif | Français limité ; 0,4 Go annoncé exclut des composants, GGUF réel environ 1,19 Go |
| Qwen3.8 / Bonsai 27B / DiffusionGemma | Architectures ou compression nouvelles | Paramètres actifs et petits fichiers ne garantissent pas une finalisation mobile rapide |

Sources des familles Google, HF/Microsoft/Mistral et MiniCPM,[^35][^36][^37][^38][^39] Falcon/Liquid/NVIDIA/BitNet,[^40][^41][^42][^43] et familles plus grandes.[^44][^45]

Les modèles d’embeddings et de classement ne génèrent pas eux-mêmes un mail. Les solutions Apple MLX/Mirai ne s’intègrent pas directement à Android. Les débits de cartes NVIDIA de bureau, le nombre de TOPS et les records sur texte très long ne sont pas des critères suffisants pour retenir un modèle mobile.

## Fourniture du modèle avec l’application

Accepter une application plus lourde ouvre une voie utile, sans imposer plusieurs téléchargements manuels. L’installation peut fournir le poids avec le paquet ou organiser son téléchargement initial de façon explicite, vérifiée et reprenable. Le premier téléchargement et la première préparation du GPU doivent être distingués de la réactivité des dictées suivantes.

Le paquet standard E2B de 2,59 Go dépasse la limite de 2 Gio par fichier d’une release GitHub. La variante GPU de 2,01 Go laisse moins de marge pour un APK unique et doit être validée avec son moteur ; son nom ne suffit pas à établir ses caractéristiques exactes. Il faut donc prévoir le conditionnement avant de promettre le même APK monolithique qu’avec le 350M. La copie extraite des poids, les fichiers temporaires et les caches peuvent aussi augmenter l’espace installé.[^9][^52]

Les poids Gemma4, Qwen, Luth et Bonsai examinés annoncent Apache-2.0 ; les LFMs consultés ont leur propre LFM Open License. Les bibliothèques et bundles accélérés peuvent ajouter des conditions distinctes. Choisir un paquet public exécutable hors ligne ne nécessite pas de transformer le post-traitement local en appel cloud.[^11][^12][^14][^16][^19][^53]

## Spécialiser la tâche pour conserver une faible latence

La quantification réduit le coût d’un modèle ; la spécialisation peut augmenter la qualité à taille constante. **LFM2-Extract 350M et 1.2B** sont des modèles FR/EN existants pour extraction structurée. Ils pourraient produire des items ou les zones d’un mail, puis laisser l’application appliquer la présentation. Leur compétence en extraction JSON/XML/YAML ne démontre toutefois pas le découpage d’une dictée sans séparateurs.[^46]

Demander au modèle moins de texte, par exemple des frontières, peut réduire le décodage. Cela exige un modèle capable de fournir des positions fiables, une validation stricte et un comportement défini pour les ambiguïtés. Les anciens essais START/JOIN de DictAI n’ont pas satisfait ce besoin. Ils ne doivent pas être réintroduits comme une solution déjà acquise.[^5]

La correction des nombres, les correspondances explicites du vocabulaire et les messages très courts peuvent continuer à utiliser des règles locales partagées avec le mode cloud. Pour une correction contextuelle ambiguë, le LLM doit préserver les noms et les intentions, sans transformer automatiquement une transcription incertaine en affirmation différente.

Le cloud avec la clé choisie reste une voie fonctionnelle parallèle. Un délai dépassé localement ne doit pas provoquer un envoi cloud silencieux. Dans une comparaison, conserver la même source et évaluer la qualité du format séparément des éventuelles réécritures permet d’attribuer correctement les gains.

## Protocole proposé pour le prochain prototype

**1. Geler un corpus indépendant.** Réserver au moins 120 dictées courtes FR/EN, réparties entre listes, mails et contrôles Normal. Couvrir les courses sans virgules, les compléments, les noms, les nombres, les négations, les citations et les signatures. Prévoir plusieurs formats légitimes pour les cas ambigus. Les exemples servant à modifier le prompt ne doivent pas servir de validation finale.

**2. Séparer trois scores.** Mesurer la fidélité du contenu, le bon regroupement et le délai jusqu’à l’insertion. Conserver une catégorie pour les réponses incomplètes et les replis ; exclure les échecs du bilan de latence donnerait un résultat artificiellement favorable. Une ponctuation légitimement ajoutée n’est pas une erreur ; une négation supprimée, un nom changé ou une quantité altérée l’est. Un nombre de puces correct sans bons éléments ne suffit pas.

**3. Comparer des configurations bornées.** Tester Gemma4 E2B mobile GPU sans thinking, puis avec et sans MTP. Utiliser Qwen3.5-2B MNN CPU/OpenCL et le 350M actuel comme comparateurs. Monter à E4B seulement si un échec de capacité précis le justifie. Luth2B ou une adaptation de Luth0.8B ne passent à l’essai Android qu’après correction reproductible des défauts de qualité observés.

**4. Mesurer sur le F7 réel.** Relever nouveau processus, moteur chaud, retour après inactivité, première installation et première compilation GPU séparément. Conserver Nemotron résident et tester l’overlay au-dessus d’une application de texte. Répéter les exemples à froid puis en séries, téléphone débranché, en notant médiane, p95, mémoire, état thermique et erreurs. Les caches système non vidés doivent être signalés.

**5. Vérifier l’absence de thinking au calcul.** Archiver le template rendu, les options du moteur et les tokens réellement générés. Masquer une zone de pensée dans l’interface n’est pas une désactivation. Respecter les marqueurs de fin et fixer un budget de sortie proportionné au texte, sans couper silencieusement une signature.

**6. Réduire le coût du geste.** Charger le moteur en amont, conserver son cache de compilation, réutiliser les instructions stables et anticiper sur du texte stabilisé. Annuler les réponses périmées lorsque la dictée change. Ne publier que la sortie validée du bon texte et du bon format ; garder le résultat direct pour les petites réponses qui n’ont besoin d’aucune mise en forme.

**7. Décider sur les résultats conjoints.** Un candidat doit améliorer nettement les listes et mails sans altération critique et sans régression perceptible de réactivité. Un débit élevé avec erreurs de format n’est pas une réussite ; un bon format avec attente systématique ne l’est pas davantage. Les mesures locales exploratoires et les scores publics servent au tri initial, pas à cette décision produit.

## Limites et conclusion technique

La recherche confirme l’existence de modèles récents, compressés et embarquables. Elle identifie aussi des accélérations Android mesurées qui n’étaient pas couvertes par le chemin CPU actuel. **Gemma 4 E2B mobile sur GPU est le premier couple à tester pour la vitesse.** Luth-2 et Bonsai démontrent des voies différentes de spécialisation et de compression, mais leurs sondes ne justifient pas un remplacement immédiat. La décision produit reste à établir sur appareil, avec l’anglais et la fidélité.

Aucune source retenue ne démontre encore la chaîne complète POCO F7, Android 16, ASR résident, dictée française spontanée, format validé et insertion presque immédiate. Les performances thermiques, la mémoire totale et la latence après inactivité restent inconnues. Le travail suivant doit produire cette mesure, avec une comparaison transparente au 350M, plutôt qu’une nouvelle sélection fondée uniquement sur la taille des poids.

## Sources

Toutes les sources web ont été consultées le 9 septembre 2026. Les révisions et empreintes des poids réellement exécutés figurent dans les rapports JSON.

[^1]: Google AI Edge / litert-community. [Gemma 4 E2B : performances et protocole LiteRT-LM](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm), consulté le 9 septembre 2026.

[^2]: KuraKura AI / Max LSB. [Luth-2 : méthode et évaluations françaises](https://huggingface.co/blog/MaxLSB/luth-2), 11 août 2026.

[^3]: Alibaba / équipe MNN. [Qwen3.5-2B-MNN : export Q4 et fichiers](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/tree/35781816d7b6a9dcb273a6765ac9563401951c3c), révision 35781816d7b6a9dcb273a6765ac9563401951c3c.

[^4]: Xiaomi. [POCO F7 : spécifications, référence 25053PC47G](https://www.mi.com/tw/product/poco-f7/specs/), consulté le 9 septembre 2026.

[^5]: DictAI. [JNI et configuration de construction du moteur local](https://github.com/Uhama91/DictAI/tree/52e0663c8629471efd472e76b5905083f6d76a35/app/src/main/cpp/llm), source 52e0663. ; DictAI. [Essais précédents de formats locaux et limites](https://github.com/Uhama91/DictAI/blob/52e0663c8629471efd472e76b5905083f6d76a35/docs/2026-09-09-retour-essai-local.md), 9 septembre 2026.

[^6]: DictAI. [Rapport de test reçu sur Xiaomi : LFM350](https://github.com/Uhama91/DictAI/blob/52e0663c8629471efd472e76b5905083f6d76a35/docs/benchmarks/local-format/phone-lfm350-2026-09-09.json), 9 septembre 2026.

[^7]: DictAI / GitHub Actions. [Version 0.7.3 : diagnostic du post-traitement local](https://github.com/Uhama91/DictAI/releases/tag/local-layout-test-34296957796), 9 septembre 2026.

[^8]: Google. [Gemma 4 : mémoire, paramètres effectifs et quantification](https://ai.google.dev/gemma/docs/core), mise à jour du 8 juillet 2026.

[^9]: Google AI Edge / litert-community. [Fichiers Gemma 4 E2B LiteRT-LM](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1), révision b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1.

[^10]: KuraKura AI. [Luth-2-0.8B et GGUF officiels](https://huggingface.co/kurakurai/Luth-2-0.8B), révision GGUF 669c903a2b2bbf47afe9afce350f4b2b399b69bc. ; KuraKura AI. [Luth-2-2B et GGUF officiels](https://huggingface.co/kurakurai/Luth-2-2B-GGUF/tree/main), août 2026.

[^11]: Qwen. [Qwen3.5-2B : carte du modèle](https://huggingface.co/Qwen/Qwen3.5-2B), mars 2026.

[^12]: Liquid AI. [LFM2.5-1.2B-Instruct : langues, réglages et mesures mobiles](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct), consulté le 9 septembre 2026.

[^13]: Liquid AI. [Quantization-Aware Distillation](https://www.liquid.ai/blog/qad), 19 août 2026. ; Liquid AI. [LFM2.5-1.2B-Instruct : fichiers GGUF](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/tree/main), consulté le 9 septembre 2026.

[^14]: PrismML. [Bonsai-4B Q1_0 : format, paramètres et licence](https://huggingface.co/prism-ml/Bonsai-4B-gguf), révision 78f2c2bacd0904ffaba24b4873ed975e5818354a.

[^15]: PrismML. [Ternary-Bonsai-4B : fichiers](https://huggingface.co/prism-ml/Ternary-Bonsai-4B-gguf/tree/main), consulté le 9 septembre 2026.

[^16]: Google. [Gemma 4 : annonce et licence](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/), 2 avril 2026. ; Google. [Gemma 4 E2B QAT mobile : carte](https://huggingface.co/google/gemma-4-E2B-it-qat-mobile-transformers), consulté le 9 septembre 2026.

[^17]: Google. [Quantization-aware training for Gemma 4](https://blog.google/innovation-and-ai/technology/developers-tools/quantization-aware-training-gemma-4/), 5 juin 2026.

[^18]: Google AI Edge. [Blazing fast on-device GenAI with LiteRT-LM](https://developers.googleblog.com/en/blazing-fast-on-device-genai-with-litert-lm/), 19 mai 2026.

[^19]: KuraKura AI. [Luth-2-0.8B : template de conversation](https://huggingface.co/kurakurai/Luth-2-0.8B/blob/cd628ce69a99aefd973854848d11a1224b5a7ad5/chat_template.jinja), révision cd628ce69a99aefd973854848d11a1224b5a7ad5. ; KuraKura AI. [Luth-2 : dépôt d’évaluation](https://github.com/kurakurai/Luth-2), consulté le 9 septembre 2026.

[^20]: PrismML. [Bonsai-1.7B Q1_0 : modèle et fichiers](https://huggingface.co/prism-ml/Bonsai-1.7B-gguf), consulté le 9 septembre 2026.

[^21]: PrismML. [1-bit Bonsai 8B : livre blanc, configuration B.3 et mesures mobiles](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/1-bit-bonsai-8b-whitepaper.pdf), 31 mars 2026.

[^22]: PrismML. [Bonsai Demo : état des formats Q1_0 et Q2_0](https://github.com/PrismML-Eng/Bonsai-demo#upstream-status-for-binary), consulté le 9 septembre 2026.

[^23]: DictAI. [Bonsai4 : poids, prompts, sorties et mesures exploratoires CPU hôte](https://github.com/Uhama91/DictAI/blob/codex/intentional-format-swipe/docs/benchmarks/local-format/bonsai4-research-probe-2026-09-09.json), 9 septembre 2026.

[^24]: Alibaba MNN. [Version 3.6.1 : benchmarks CPU, OpenCL et Hexagon](https://github.com/alibaba/MNN/releases/tag/3.6.1), 23 juillet 2026.

[^25]: Google AI Edge. [LiteRT-LM Android : initialisation, thinking et GPU](https://developers.google.com/edge/litert-lm/android), consulté le 9 septembre 2026.

[^26]: Alibaba MNN. [MNN LLM : configurations, cache, benchmark combiné](https://github.com/alibaba/MNN/blob/master/docs/transformers/llm.md), consulté le 9 septembre 2026.

[^27]: Qualcomm. [GenieX : installation Android et plateformes](https://geniex.aihub.qualcomm.com/en/run/android/install), consulté le 9 septembre 2026. ; Qualcomm. [GenieX : code et licence BSD-3-Clause](https://github.com/qualcomm/GenieX), consulté le 9 septembre 2026.

[^28]: Nexa AI. [LFM2.5-1.2B-npu : licence et activation du package historique](https://huggingface.co/NexaAI/LFM2.5-1.2B-npu), consulté le 9 septembre 2026.

[^29]: RunAnywhere. [Bonsai-4B : artefacts Hexagon v81](https://huggingface.co/runanywhere/bonsai_4b_1bit_HNPU/tree/main/v81), consulté le 9 septembre 2026. ; RunAnywhere. [Bonsai sur iPhone, Android et Mac](https://www.runanywhere.ai/blog/bonsai-27b-1-bit-models-on-phone), 19 juillet 2026.

[^30]: Liquid AI. [LEAP SDK : chargement des modèles](https://docs.liquid.ai/deployment/on-device/sdk/model-loading), consulté le 9 septembre 2026. ; Liquid AI. [Guide SDK et paramètres llama.cpp](https://docs.liquid.ai/deployment/on-device/sdk/ai-agent-usage-guide), consulté le 9 septembre 2026.

[^31]: ZETIC. [Modèles LLM pris en charge et modes d’inférence](https://docs.zetic.ai/llm-inference/supported-models), consulté le 9 septembre 2026. ; ZETIC. [Benchmarks publiés](https://docs.zetic.ai/performance/benchmarks), consulté le 9 septembre 2026.

[^32]: ggml-org. [llama.cpp : backend OpenCL](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md), consulté le 9 septembre 2026.

[^33]: PyTorch. [ExecuTorch : backend Qualcomm](https://docs.pytorch.org/executorch/stable/backends-qualcomm.html), consulté le 9 septembre 2026. ; MLC AI. [MLC LLM : SDK Android](https://github.com/mlc-ai/mlc-llm/blob/main/docs/deploy/android.rst), consulté le 9 septembre 2026. ; PyTorch. [ExecuTorch : backend MediaTek](https://docs.pytorch.org/executorch/stable/backends-mediatek.html), consulté le 9 septembre 2026.

[^34]: Google. [ML Kit GenAI : appareils, quotas et restriction d’arrière-plan](https://developers.google.com/ml-kit/genai), consulté le 9 septembre 2026.

[^35]: Google. [Gemma 3n : guide de lancement](https://developers.googleblog.com/introducing-gemma-3n-developer-guide/), juin 2025. ; Google. [Gemma 3 270M : annonce](https://developers.googleblog.com/introducing-gemma-3-270m/), 14 août 2025. ; Google. [FunctionGemma 270M : carte](https://huggingface.co/google/functiongemma-270m-it), décembre 2025.

[^36]: Hugging Face. [SmolLM3-3B : carte et non-thinking](https://huggingface.co/HuggingFaceTB/SmolLM3-3B), 8 juillet 2025.

[^37]: Microsoft. [Phi-4-mini-instruct : carte et langues](https://huggingface.co/microsoft/Phi-4-mini-instruct), consulté le 9 septembre 2026.

[^38]: Mistral AI. [Ministral 3 3B Instruct 2512](https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512), 2 décembre 2025.

[^39]: OpenBMB. [MiniCPM5-1B : carte et déploiement](https://huggingface.co/openbmb/MiniCPM5-1B), mai 2026. ; OpenBMB. [MiniCPM5-2B : carte](https://huggingface.co/openbmb/MiniCPM5-2B), septembre 2026.

[^40]: TII. [Falcon-H1-Tiny : chapitre multilingue du rapport](https://huggingface.co/spaces/tiiuae/tiny-h1-blogpost/blob/main/app/src/content/chapters/demo/tiny-h1-multilingual.mdx), consulté le 9 septembre 2026.

[^41]: Liquid AI. [LFM2.5-2.6B : raisonnement systématique](https://huggingface.co/LiquidAI/LFM2.5-2.6B), août 2026. ; Liquid AI. [LFM2.5-8B-A1B : paramètres totaux et actifs](https://huggingface.co/LiquidAI/LFM2.5-8B-A1B), consulté le 9 septembre 2026.

[^42]: NVIDIA. [Nemotron 3 Nano 4B : carte et langues](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-4B-BF16), 16 mars 2026.

[^43]: Microsoft. [BitNet b1.58 2B4T : limites et mémoire](https://huggingface.co/microsoft/bitnet-b1.58-2B-4T), consulté le 9 septembre 2026. ; Microsoft. [BitNet : GGUF officiel](https://huggingface.co/microsoft/bitnet-b1.58-2B-4T-gguf/tree/main), consulté le 9 septembre 2026.

[^44]: Qwen. [Qwen3.8 : modèles publiés](https://github.com/QwenLM/Qwen3.8), août 2026. ; PrismML. [Bonsai 27B : annonce](https://prismml.com/news/bonsai-27b), juillet 2026.

[^45]: Google. [DiffusionGemma : génération parallèle](https://blog.google/innovation-and-ai/technology/developers-tools/diffusion-gemma-faster-text-generation/), 10 juin 2026.

[^46]: Liquid AI. [LFM2-1.2B-Extract : tâches, langues et schéma](https://huggingface.co/LiquidAI/LFM2-1.2B-Extract), consulté le 9 septembre 2026. ; Liquid AI. [Liquid Nanos : modèles spécialisés et évaluation](https://www.liquid.ai/blog/introducing-liquid-nanos-frontier-grade-performance-on-everyday-devices), consulté le 9 septembre 2026.

[^47]: DictAI. [Luth0.8 : poids, template, prompts et sondes de qualité CPU hôte](https://github.com/Uhama91/DictAI/blob/codex/intentional-format-swipe/docs/benchmarks/local-format/luth08-research-probe-2026-09-09.json), 9 septembre 2026.

[^48]: Google AI Edge / litert-community. [FunctionGemma Mobile Actions : protocole et vitesse](https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions), consulté le 9 septembre 2026.

[^49]: DictAI. [Sonde Gemma4 E2B : poids, configuration LiteRT, messages et résultats](https://github.com/Uhama91/DictAI/blob/codex/intentional-format-swipe/docs/benchmarks/local-format/gemma4-research-probe-2026-09-09.json), 9 septembre 2026.

[^50]: Liquid AI. [Introducing Pipette](https://www.liquid.ai/blog/pipette-on-device-ai-benchmarking-by-liquid-ai), 24 août 2026.

[^51]: Liquid AI. [Méthode de mesure des performances Pipette](https://pipette.liquid.ai/docs/methodology/performance-methodology) et [conditions des appareils](https://pipette.liquid.ai/docs/methodology/device-conditions), consultés le 9 septembre 2026.

[^52]: GitHub. [About releases : limites des fichiers](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), consulté le 9 septembre 2026.

[^53]: Liquid AI. [LFM Open License 1.0](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct/blob/main/LICENSE), consultée le 9 septembre 2026.


[^54]: DictAI. [Gemma4 E2B : consigne conservative figée, quatre anciens cas et trois nouveaux](https://github.com/Uhama91/DictAI/blob/codex/intentional-format-swipe/docs/benchmarks/local-format/gemma4-conservative-research-probe-2026-09-09.json), 9 septembre 2026.
