# Gemma 3 : laisser passer les corrections utiles

Demande autorisée : améliorer la correction française avec Gemma 3 en conservant la réactivité, puis fournir un APK de test via Actions si le changement est qualifié. Astra cadre et réalise deux revues du changement figé ; Luna 6 Max implémente et teste. Risque classé complexe : une validation trop permissive pourrait perdre des informations.

## Contrat du premier lot

Le modèle V3, son prompt natif, ses poids, ses deux threads et sa limite finale de trois secondes restent les témoins. Ce lot améliore la validation des propositions existantes ; il ne constitue pas un nouveau fine-tuning. Une validation dédiée à Gemma 3 permet de ne pas modifier les modes Gemma 4, listes et mails. Aucun texte personnel du retour utilisateur ne va dans les tests ou corpus.

Le nouveau validateur examine les différences source/candidat. Il autorise des réparations locales reconnues, puis réutilise les protections lexicales et techniques existantes sur une source ajustée. Chaque autorisation doit avoir une contre-épreuve : forme correcte à conserver, citation ou terme protégé, négation et information voisine. Aucun seuil de similarité seul ne vaut validation sémantique. Les modifications non reconnues conservent le comportement prudent existant.

Familles bornées de cette itération :

- Répétition adjacente d'un groupe complet contenant un pronom ; conserver les répétitions emphatiques, les constructions réflexives et les répétitions séparées par une limite de phrase. Le doublon « on on » est distinct du pronom réfléchi « nous nous ».
- Départ abandonné de préposition ou de conjonction, avec indice explicite d'hésitation ; conserver les coordinations grammaticales et ne pas restaurer une moitié d'une réparation (« et mais »).
- Autocorrection explicite de jour/date par « non » ou « pardon », uniquement si la valeur finale est conservée avec les informations autour. Raffinement après revue, avant comparaison : « lundi, non mardi » peut signifier lundi et pas mardi ; « non » doit donc être isolé par deux virgules pour ce lot (« lundi, non, mardi »). « Pardon » reste un indice explicite sans cette ponctuation. Les emplois narratifs de « enfin » restent ambigus et ne justifient pas une règle générale. Les signes techniques et autres contenus des intervalles ne peuvent pas disparaître avec la valeur abandonnée.
- Quelques contextes grammaticaux non ambigus : subjonctif après « il faut que », conjugaison locale avec sujet explicite, ajout d'« être » dans « dois être au courant », correction phonétique dans l'expression « de cet acabit ». Ces protections ne prétendent pas couvrir toute la grammaire française.

## Implémentation et preuve

1. Luna runtime : ajouter `Gemma3ContextualEditing.kt` et son test, une valeur de validation distincte dans `LocalFormatting.kt`, brancher seulement `gemma3FinalLocalFormatRequest` et ajuster les tests de routage nécessaires. Commencer par RED sur les sorties utiles bloquées, puis GREEN et contre-exemples. Aucun changement de prompt ou de génération.
2. Luna évaluation, en parallèle et dans ses fichiers propres : préparer un comparateur reproductible des 108 sorties parent déjà produites et des candidats rejetés V1/V2, des 108 cibles, et un petit lot synthétique contrastif indépendant. Utiliser les mêmes sources/candidats pour ancien et nouveau validateur. Vérifier identifiants, SHA, paramètres, termes protégés ; conserver tous les résultats et relire les sorties dont la décision change. Ne pas appeler le modèle ni toucher aux holdouts verrouillés.
3. Astra : deux revues du même ensemble de sources figé, fonctionnelle puis adversariale. Faire corriger par Luna les observations, puis renouveler les revues. Seuil de livraison : amélioration utile sur des sorties parent réelles préexistantes, aucun nouveau résultat dangereux accepté dans les régressions connues, contrastes et suite Android verts. Les cibles directes mesurent la compatibilité, pas la qualité du modèle.
4. Après qualification : Luna prépare une version APK supérieure au code 40, intègre le correctif de libellé de diagnostic déjà testé et vérifie les contrats de publication. Exécuter une suite Android et un build ; publier par le workflow existant autorisé, vérifier signature, version, artefact et empreinte téléchargée. Ne jamais annoncer la latence du téléphone à partir de tests JVM/CI.

## Suite du fine-tuning

Les sorties livrées après validation servent de mesure pour la prochaine expérience ; les ensembles de développement restent hors entraînement. Ne pas répéter la continuation V1/V2 rejetée : ses gains exacts masquaient des pertes de dates et de relations. Le changement de consigne et les corrections plus générales feront l'objet d'un essai séparé avec conservation d'exemples déjà corrects. Ce lot ne doit pas attendre un nouvel entraînement pour rendre un gain déjà mesurable disponible.

## Qualification du premier lot

L'implémentation retenue reconnaît une seule différence lexicale locale. Les patrons sont plus étroits que les familles envisagées : groupe répété commençant par un pronom sujet, « et euh mais », jour seul ou date numérique, « il faut qu'il ait accès », « je me permets de », « tu dois peut-être être au courant » et « de cet acabit » à partir de « akaby ». Les constructions plus générales et les réparations multiples conservent le garde existant.

La comparaison figée r2 comporte 463 cas source/candidat, chacun soumis aux deux validateurs (926 appels JVM). Sur les 108 prédictions V3 préexistantes, 67 textes finaux correspondaient exactement à la cible, contre 72 après changement ; les cinq nouvelles corrections ont été relues et conservent le sens. Les candidats dangereux connus restent refusés. Aucun poids n'a été chargé pour cette comparaison et ce score exact ne mesure pas une réussite générale en dictée.

Le lot synthétique conserve huit attentes non satisfaites, dont quatre étiquetées `supported_contract` : une date composée avec « non » non isolé, l'ajout d'« être » avec « je dois », « il faut qu'il ait la copie » et « tu permets ». Les données et attentes restent inchangées après mesure. Ces refus inchangés montrent une couverture inférieure à l'objectif initial ; aucune réussite complète des contrastes n'est revendiquée. Les cas dangereux du lot restent tous rejetés. Astra retient le pilote pour le gain borné sur les sorties V3, en documentant ces limites, sans assimiler cette étape au fine-tuning grammatical attendu.

La suite Android complète passe : 742 tests, aucun échec, erreur ou test ignoré. L'assemblage local 0.9.12/code 41 réussit sous JDK 21. La livraison reste conditionnée aux contrôles d'identité et à la vérification du fichier publié par Actions.
