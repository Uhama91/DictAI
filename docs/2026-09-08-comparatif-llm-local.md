# Comparatif local du 8 septembre 2026

## Premier lot : réécriture libre

La priorité confirmée par Ullie est le LLM local et sa faible latence. **Aucun des quatre candidats de ce lot n’est retenu comme moteur quotidien.** Ils ont été exécutés réellement sur ordinateur avec 12 dictées synthétiques françaises/anglaises (listes, mails, format personnel). Plusieurs sorties suppriment des informations, changent le destinataire ou ajoutent une signature. Cela ne démontre pas qu’aucun petit modèle ne puisse convenir ; ces poids et ces configurations ne sont pas validés.

Aucun nouveau poids n’est ajouté à l’APK normale, ni aucun envoi cloud déclenché par ces essais. Cette conclusion concerne la réécriture libre. Le nouvel essai de copie contrainte décrit ci-dessous est une configuration distincte, réservée à `-PlocalFormatPrototype=true` ; il sert à évaluer la mise en page et la latence, sans validation quotidienne à ce stade.

| Candidat et poids vérifiés | Taille du fichier | Exemples d’échecs réels |
|---|---:|---|
| Liquid AI LFM2.5-1.2B-Instruct QAD-Q4_0 | 695 755 488 octets | Mail Julie : demande d’apporter les documents supprimée ; liste anglaise : Monday supprimé ; réponse inventée à « OK, ça marche ». |
| Liquid AI LFM2.5-1.2B-Instruct Q4_K_M | 730 895 168 octets | Monday supprimé ; signature inventée ; « ne pas envoyer » devient « je n’enverrai pas ». |
| Qwen3.5-0.8B Q4_0, sans raisonnement | 563 036 064 octets | Karim devient signature d’un mail adressé à Maëlys ; long mail fictif créé à partir de « OK, that works ». |
| Qwen2.5-1.5B-Instruct Q4_K_M | 1 117 320 736 octets | Lait devient « Laï » ; instruction de ne pas envoyer le dossier à Karim supprimée du format Actions/Deadline. |

Les entrées, sorties, paramètres, révisions et empreintes SHA-256 sont conservés dans [les rapports JSON](benchmarks/local-format/). Ce sont des textes synthétiques, aucune dictée personnelle. Les vérifications automatiques par sous-chaîne sont des repères incomplets : elles peuvent accepter `2` contenu dans `23`, ou une négation dont le sujet a changé. Le rejet des candidats repose aussi sur la lecture des sorties ; ne pas transformer les repères en pourcentage de fidélité.

## Protocole et correction du banc

Commande reproductible : `python3 scripts/compare_local_format_models.py --model lfm12-qad` (autres clés : lfm12-q4km, qwen08, qwen15). Le script télécharge une révision figée, vérifie taille/SHA-256 et exécute uniquement le CLI local. llama.cpp : `1511ce3bc3f087376c8526b4ad07100bfabb277f`.

Contexte 4096, lot logique 4096, micro-lot 128, 2 threads, température 0,1, top-k 50, pénalité de répétition 1,05, seed 1234. LFM : BOS explicite unique ; Qwen2.5 : ChatML sans BOS LFM ; Qwen3.5 : préfixe assistant avec bloc de réflexion vide. Le JNI a été aligné sur l’historique de répétition du CLI, incluant les tokens du prompt.

Le premier banc utilisait un lot logique de 128. Dans llama-completion, un jeton de fin placé au bord d’un lot du prompt pouvait arrêter le programme avant inférence. Ces sorties vides étaient invalides comme jugement sur le modèle. Les anciens rapports sont archivés dans le cache avec le suffixe `invalid-cli-batch128`, puis les 48 cas comparés ont été relancés avec lot logique 4096. Le JNI Android n’avait pas ce défaut. La comparaison QAD micro-lot 128 contre 512 donne la même suppression des documents dans le mail Julie : elle ne résout pas cet échec.

La durée du processus et son premier stdout incluent le chargement du modèle sur ordinateur. Certaines exécutions ont eu lieu pendant une compilation. **Aucun chiffre de ce lot ne constitue une mesure de latence sur Poco F7 ou tablette, ni un classement fiable de vitesse des candidats.**

## Pistes de consigne et de sortie courte essayées

Une consigne de copie/mise en page plus simple et trois exemples few-shot ont été essayés sur LFM Q4_K_M, avec mail Julie, liste Monday et message court. La liste conserve Monday avec exemples, mais le modèle change encore le tutoiement et copie parfois du contenu de l’exemple dans la réponse courte. Ces prompts ne sont pas retenus.

Un contrat extractif a aussi été testé : le LLM choisit des indices de début de groupes, puis un renderer pourrait recopier chaque mot d’origine une fois, dans l’ordre. QAD : 0/3 plans utiles sans vrais échanges d’exemple ; avec deux échanges few-shot, 3/3 JSON valides mais seulement le message court est correctement groupé. Le mail est découpé mot par mot et la liste sépare des nombres de leurs objets. Le préremplissage s’allonge. **Ce renderer n’a pas été intégré dans l’application.** Une sortie JSON valide ne suffit pas à obtenir un bon format.

## Changements effectifs du moteur

- Deux bibliothèques ARM64 statiques isolées : baseline et DOTPROD/FP16. Une sonde native vérifie les capacités du processeur avant de charger la seconde ; repli baseline si elle ne peut être chargée. Les handles restent attachés à leur bibliothèque.
- Premier fragment UTF-8 complet émis immédiatement, puis limitation des suivants à 60 ms.
- Même dictée et mêmes options : les callbacks ASR identiques ne repoussent plus la préparation d’une seconde. `warm()` est dédupliqué.
- Extraction du poids protégée par un verrou partagé entre moteur du service et moteur de test.
- Mesures distinctes du premier fragment affiché et de la fin d’insertion dans le champ destinataire.
- Dialogue de benchmark ajouté au prototype : 3 exemples FR/EN × 2 passages, chargement, premier fragment reçu et résultat complet ; rapport copiable, arrêt non bloquant et gestion du cycle de vie. Ce dialogue reste masqué dans la construction normale. Il ne mesure pas toute la chaîne ASR/insertion.

Compilation, tests et empreinte de l’APK normale : [rapport de vérification](VERIFICATION-POST-TRAITEMENT.md). Aucune installation ni mesure sur téléphone réalisée. Les modèles restent dans le cache de recherche.

## Travail restant

1. Améliorer la qualité du découpage de la copie contrainte décrite ci-dessous, en gardant un corpus de validation distinct. La réécriture libre et les formats personnels locaux restent non résolus.
2. Mesurer le prototype de copie contrainte sur téléphone : chargement, premier affichage et fin d’insertion, avec et sans Nemotron résident. Comparer 2 et 4 threads ainsi que CPU baseline/optimisé. Aucun appareil de test n’est piloté dans ce lot.
3. Examiner un cache de préfixe mesuré. Pour les modèles hybrides LFM/Qwen3.5, ne pas supposer que supprimer un suffixe restaure l’état récurrent : utiliser une snapshot complète et vérifiée ou un mécanisme adapté.
4. Conserver les autres demandes dans [le suivi](SUIVI-DEVELOPPEMENT.md) : vocabulaire/nombres, certitude du démarrage, envoi depuis la pause, catalogue ASR. Elles ne sont pas effacées par la recherche LLM.

## Sources des poids et configurations

- [Liquid AI : GGUF officiels](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF), [publication QAD](https://huggingface.co/blog/LiquidAI/qad). Les gains publiés sur d’autres appareils ne prouvent pas ceux du Poco.
- [Qwen3.5-0.8B : modèle source](https://huggingface.co/Qwen/Qwen3.5-0.8B), [conversion ggml-org](https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF).
- [Qwen2.5-1.5B : GGUF officiels](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF).

## Deuxième lot : 350M, copie contrainte et exemples partagés

Configuration du prototype **0.7.1-wp-local-test** : LFM2.5-350M Q4_K_M, 229 312 224 octets, révision `9969000761ce34de907bf20017cbfc3d52d6eaf9`. Deux échanges d’exemple par format, stockés dans les assets `layout-list.prompt` et `layout-email.prompt` ; l’application et le banc lisent exactement ces mêmes fichiers. Aucune réécriture libre : la grammaire GBNF impose tous les mots source dans leur ordre, avec leur casse et leur ponctuation. Le modèle choisit seulement espaces, puces et paragraphes. Cette configuration ne transforme donc pas « écris à Julie pour lui dire… » en mail rédigé et ne supprime pas les hésitations.

Le premier sondage de six textes a conservé tous les mots et obtenu cinq découpages utiles. Le corpus élargi de douze textes est plus difficile : dix générations et deux messages courts renvoyés directement. **Les dix générations conservent exactement toutes les unités du texte ; aucun échec d’exécution ou sortie tronquée dans ce lot.** Cela ne valide pas la qualité de tous les découpages :

- Les deux listes avec en-tête gardent trois items et les groupes « lait et miel » / « peanut butter and jelly » ; l’en-tête reste sur la première puce, alors que la référence le place hors liste. Ce style de titre n’est pas disponible dans cette grammaire.
- Liste française avec date, négation et montants : les trois groupes attendus sont présents.
- Budget anglais : une puce supplémentaire sépare le total du prix unitaire.
- Deux listes contenant des consignes citées : tous les mots sont conservés, mais tout reste sur une seule puce.
- Les quatre mails séparent la salutation du corps. Le placement des dernières formules et des signatures reste irrégulier ; les paragraphes ne correspondent pas toujours à la référence.
- Les deux acquiescements courts passent sans appel LLM. Ils ne comptent pas comme des réussites du modèle.

Rapport complet : [LFM350 contraint, deux threads](benchmarks/local-format/lfm350-faithful-t2.json). Références figées : [corpus v1](benchmarks/local-format/layout-cases-v1.json). Les positions de coupure, les rôles des lignes et la conservation des mots sont enregistrés séparément. Les références n’ont pas été réécrites pour améliorer le score. Le sous-corpus reprend trois sondages déjà vus ; ce n’est donc pas un jeu entièrement inédit. Les temps sont ceux de processus sur ordinateur, avec chargement et compilation Android concurrente : ils ne valident pas la latence téléphone.

Reproduction : `python3 scripts/compare_local_format_models.py --model lfm350 --style faithful --threads 2`. Le banc réutilise le poids présent après vérification taille/SHA ; il enregistre également les empreintes du binaire, des prompts et du corpus, les prompts complets, les grammaires et les sorties stdout/stderr.

### Intégration et contrôles de cette configuration

- `FaithfulLayout` vérifie la totalité des unités source avant publication, puis reconstruit le résultat depuis ces unités. Les contrôles de vocabulaire s’appliquent aussi. La conversion des nombres précède cette étape.
- Chaque aperçu progressif conserve la source complète, y compris sa fin encore non générée. Seuls les choix de mise en page déjà vérifiés sont ajoutés.
- Les mails de cinq mots au plus sans salutation reconnue et les listes d’un mot disposent d’un résultat direct. La finalisation court-circuite la file de travail : un ancien chargement local ne retarde pas « OK, ça marche ».
- Moteur résident, préparation sur pauses naturelles, réutilisation seulement si texte/options identiques. Attente additionnelle de mise en forme plafonnée à cinq secondes après finalisation ASR ; sinon le texte source est conservé avec un message. Ce plafond ne constitue pas une promesse d’instantanéité ni un plafond de la chaîne ASR complète.
- Le JNI rejette une grammaire vide ou invalide ; le masque de grammaire précède top-k. L’historique du prompt va uniquement au sampler de pénalités. Le succès exige une fin de génération complète ; annulation, limite de jetons et délai rendent un échec récupérable.
- Test réel du JNI compilé depuis le même source sur ordinateur : grammaire invalide/vide, Unicode/emoji, guillemets, négation/nombres, flux cumulatif, fin complète, annulation/reprise, troncature et délai de préremplissage passés. Reproduction : `bash scripts/verify_local_postprocessing.sh --host-jni`.

Construction normale et prototype séparés : `bash scripts/verify_local_postprocessing.sh --prototype`. [Rapport APK et tests](VERIFICATION-POST-TRAITEMENT.md). [Mode d’essai sur téléphone](ESSAI-LLM-LOCAL.md). Le cloud reste sélectionnable avec la clé API ; aucune bascule cloud automatique n’est déclenchée par un échec local.

### Contre-vérification avec le JNI exact

Le CLI filtre les choix invalides après un premier tirage ; le JNI filtre systématiquement avant top-k. Les graines identiques ne garantissent donc pas les mêmes coupures. Le corpus a été exécuté une seconde fois avec le **source natif exact de l’application, compilé pour l’ordinateur**, avec modèle résident et deux threads : [rapport JNI](benchmarks/local-format/lfm350-faithful-jni-host.json). Les dix générations se terminent et conservent tous les mots ; les deux réponses directes sont comptées à part.

Différences observées : la liste budgétaire anglaise garde cette fois ses trois groupes correctement ; le mail Maëlys reste presque entièrement sur un paragraphe après la salutation. Les deux listes contenant des consignes citées restent monoblocs. Les clôtures Zoë/Noah restent collées au corps ; Inès est correctement réparti avec signature sur la ligne de clôture. Cette contre-vérification confirme la fidélité des unités et les limites de mise en page, sans établir une parité de sortie entre architectures CPU ni mesurer un téléphone.
