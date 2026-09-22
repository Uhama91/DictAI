# DictAI — plan de mesure, de choix du modèle et de spécialisation

**Statut : exécution autorisée le 21 septembre 2026. APK de diagnostic livré ; un deuxième essai sur Pad 7 atteint la limite de 20 secondes sans fragment du correcteur. Comparaison Gemma 3 prioritaire ; mesures Poco encore attendues. Aucun nouvel entraînement.**

**Objectif :** retrouver une dictée réactive sur le Poco F7 et retenir le modèle qui produit une correction utile dans ce budget de temps.

**Architecture visée :** conserver la transcription locale et l'édition progressive ; traiter les portions stables pendant la parole lorsque cela apporte un gain mesuré. Le choix entre Gemma 3 270M et Gemma 4 E2B dépend des résultats sur téléphone, et non de la génération du modèle ou d'un score synthétique isolé.

**Stack :** Android/Kotlin, Nemotron, llama.cpp CPU pour les pilotes existants ; LiteRT-LM/GPU comme expérience distincte de faisabilité pour Gemma 4.

**Références :** demande de réactivité et retours utilisateur signalant des délais de 10 à 15 secondes ; `docs/gemma4-pilot-test-guide.md` ; `docs/superpowers/plans/2026-09-20-gemma4-finetuned-delivery.md`. Exécution selon la politique canonique Sol–Luna : tâches bornées à Luna Max, recherche et décisions au principal, revue avant publication.

## Contraintes et critères proposés

- Aucun entraînement long avant de choisir un moteur suffisamment rapide.
- Gemma 3 V3 et Gemma 4 V6 restent des références immuables. Ne pas modifier leurs poids, corpus historiques, consignes ou résultats pendant la comparaison initiale.
- La cible d'expérience est une attente habituelle d'environ une seconde après l'arrêt, sans pointes récurrentes à 10–15 secondes. Mesurer séparément le reste de transcription et l'attente ajoutée par la correction. La cible n'est pas une performance déjà obtenue.
- Pour décider sur le petit lot mobile : viser une médiane d'attente ajoutée par la correction au plus égale à 1 seconde, avec une attente ajoutée inférieure ou égale à 2 secondes dans au moins 90 % des essais chauds. Rapporter aussi le maximum et tous les échecs ; ce petit lot ne permet pas de garantir un percentile en production.
- Une réponse brute rendue immédiatement après un échec n'est pas une correction réussie. Toujours publier ensemble les délais, le nombre de corrections utiles, les rejets et les retours au texte brut.
- Préserver noms, nombres, dates, négations, conditions et retouches manuelles. Une amélioration de style ne compense pas une modification du sens.
- Le premier chargement est mesuré séparément, puis inclus dans un essai réel de première dictée. Ne pas le masquer en ne publiant que les essais déjà chauds.
- Les exemples transmis par l'utilisateur sont des résultats observés. Sans audio ou transcription ASR avant correction, ne pas les présenter comme des paires d'entraînement ou comme une reproduction exacte du défaut.
- Pas de nouveau calcul lourd local, nouvelle quantification ou publication pendant la phase de planification. Un essai ultérieur exige un budget explicite dans sa fiche d'exécution et les contrôles de ressources existants.

## 1. Identifier l'attente réellement subie

**Livrable :** une chronologie d'une dictée courte, d'une dictée moyenne et d'une dictée longue sur le Poco, avec le résultat réellement appliqué.

**Points d'entrée existants :** `PostprocessingDiagnostic.kt`, `LocalFormatBenchmarkDialog.kt`, `LocalFormatCpuEngine.kt`, `ProgressiveFormattingCoordinator.kt`, `OverlayService.kt` dans `app/src/main/kotlin/com/kafkasl/phonewhisper/`.

- [ ] Lire d'abord le diagnostic copiable de la version 0.9.8 : format, moteur, chargement, durée du post-traitement, arrêt vers insertion, résultat appliqué.
- [x] Ajouter des métadonnées par dictée : fin ASR, appels partiels/final, attente avant calcul, calcul, acceptation/rejet, annulation. Aucun texte dicté dans le diagnostic. Le détail progressif de la version précédente était insuffisant.
- [x] Fournir un APK de diagnostic préparé pour une mise à jour : code 38, même certificat que le code 37, fichier public vérifié. L'installation effective sur Poco reste à confirmer. Aucune commande de développement demandée sur le téléphone.
- [x] Vérifier avec des horloges et moteurs substituables que les compteurs distinguent un résultat accepté, un délai dépassé et un rejet de fidélité. Préserver les retouches et refuser les callbacks périmés.
- [ ] Mesurer les mêmes enregistrements avec post-traitement désactivé pour isoler le coût ASR. Mesurer aussi la correction seule sur un texte ASR fixé, puis l'ensemble en concurrence avec Nemotron. Aucun temps serveur ou Mac ne remplace cette mesure.

**Constats de code à vérifier dans la trace :** le chemin progressif peut attendre 20 secondes à la fin ; une requête partielle encore active est annulée avant la requête finale ; le cache de calcul du prompt est vidé à chaque appel natif ; les segments partiels exigent une ponctuation de fin de phrase et des mots reconnus comme stables. Le nettoyage léger des hésitations est contourné par ce parcours. Ces constats ne prouvent pas quelle cause explique les 15 secondes de l'essai utilisateur.

## 2. Comparer sans réentraîner

**Livrable :** un tableau qualité/latence de configurations identifiées, sur le même Poco et les mêmes entrées.

- [x] Figer six cas de correction : deux courts, deux moyens, deux longs. Inclure hésitations, reprises de déterminant/conjonction, reformulation explicite, énumération dans de la prose et informations à préserver. Conserver des exemples propres pour détecter les réécritures inutiles.
- [ ] Utiliser d'abord le format Texte corrigé, seul périmètre commun du pilote Gemma 3 V3 et du pilote Gemma 4 V6. Ne pas attribuer au pilote Gemma 3 une prise en charge des listes/mails qui n'est pas intégrée.
- [ ] Réaliser trois passages chauds par cas et par configuration, ainsi qu'un démarrage froid représentatif par configuration. Alterner l'ordre des modèles et noter les conditions de température et de charge du téléphone.
- [ ] Lire manuellement les six sorties distinctes de chaque configuration ; si les répétitions d'un cas diffèrent, lire ces différences également. Classer : utilisable, petites retouches, à refaire, erreur de sens. Garder le texte source et la réponse du modèle séparés du texte finalement appliqué.

**Références connues :** Gemma 3 270M V3 Q8, fichier de 291 545 280 octets, et Gemma 4 E2B V6 Q6/QO F16, fichier de 3 931 578 880 octets. Tous deux utilisent deux threads CPU dans les pilotes publiés. La taille des fichiers n'est pas un rapport de vitesse.

## 3. Donner une chance bornée à l'optimisation de Gemma 4

**Livrable :** un verdict de faisabilité, sans chantier ouvert de conversion ou d'optimisation.

- [ ] Choisir l'optimisation selon la trace : réutilisation sûre du préfixe de consigne si son calcul domine ; correction du déclenchement ou de la réutilisation du travail progressif si l'attente vient de l'ordonnancement. Une modification à la fois, comparée aux mêmes poids et aux mêmes entrées.
- [ ] Examiner séparément la voie officielle LiteRT-LM/GPU pour les poids fine-tunés. Faire un essai de transport et de sortie complète avant une conversion longue. Le précédent problème de sorties `<pad>` doit être explicitement testé ; activer le GPU ne suffit pas à valider cette voie.
- [ ] Vérifier l'identité des poids fine-tunés, la consigne et l'intégrité des sorties de toute variante exportée. Un modèle de base officiel ou une sonde minuscule peut démontrer la disponibilité du moteur, mais ne peut pas remplacer notre candidat dans la comparaison de qualité.
- [ ] Limiter cette phase à une optimisation de l'intégration CPU et à un essai de faisabilité GPU, avec un budget fixé avant chaque lancement. Pas de grille de modèles, de quantifications ou de relances automatiques.
- [ ] Si la voie optimisée reste trop lente, si la sortie est incorrecte, ou si l'intégration nécessite un chantier disproportionné, arrêter cette piste pour le prochain pilote. Documenter le résultat et passer au candidat léger.

La documentation Google décrit l'export de poids fine-tunés vers LiteRT-LM et les accélérations disponibles : https://developers.google.com/edge/litert-lm/models/gemma-4. Les résultats publiés sur d'autres téléphones ne garantissent ni le délai complet sur Poco ni la qualité de notre export.

## 4. Choisir, puis améliorer uniquement le candidat retenu

**Règle de décision :** Gemma 4 ne reste candidat que si son attente devient acceptable et que son avantage de correction est visible sur les mêmes dictées. Sinon, Gemma 3 devient la base prioritaire. Si aucun ne respecte les critères, ne qualifier aucun des deux : conserver un mode rapide explicite et revoir le périmètre de correction.

- [ ] Réutiliser les corpus existants avec la consigne et le tokenizer propres au modèle choisi. Séparer les familles d'exemples entre entraînement et évaluation ; ne pas mélanger les variantes d'un même épisode entre les deux.
- [ ] Commencer par les hésitations, répétitions accidentelles et reformulations explicites. Ajouter des contre-exemples qui conservent les insistances, citations, incertitudes et formulations ambiguës.
- [ ] Enrichir ensuite les énumérations au sein de la prose, les listes et les mails, avec conservation des introductions, conclusions et signatures. Les corrections de vocabulaire connues restent déterministes.
- [ ] Réaliser une seule expérience courte, avec le parent comme témoin, un checkpoint de sortie prévu et un budget fixé avant exécution. N'étendre l'entraînement que si des cas tenus à l'écart montrent un gain utile sans régression de fidélité ni de latence.

Gemma 3 270M est conçu pour la spécialisation de tâches ciblées ; cela justifie l'expérience, pas une promesse de réussite sur toutes les reformulations françaises : https://developers.googleblog.com/introducing-gemma-3-270m/.

## 5. Livrer une version que le téléphone permet de qualifier

- [ ] Retenir les corrections progressives terminées et validées ; protéger les retouches humaines. Ne pas prolonger systématiquement l'attente finale pour tenter de compenser un modèle trop lent.
- [ ] Rétablir le nettoyage léger sur le parcours concerné, avec tests des citations, termes protégés et portions modifiées manuellement. Ne pas faire de ce correctif une preuve de qualité du modèle.
- [ ] Vérifier les cas courts et longs, l'absence de ponctuation dans le flux ASR, l'annulation, les erreurs du moteur, les listes/mails et la reprise après une retouche pendant la dictée.
- [ ] Construire l'APK via GitHub Actions après les revues, avec un `versionCode` supérieur à 37 et à ceux des APK de diagnostic éventuellement publiés, ainsi que la même signature ; vérifier le fichier publié et fournir son lien direct.
- [ ] Faire confirmer la réactivité et le gain réel de correction sur le Poco. Les tests JVM et une CI verte valident le logiciel, pas l'expérience mobile.

## Revue du plan

Les risques couverts sont : premier chargement, dictée longue sans ponctuation stable, correction rejetée après calcul, course avec une retouche humaine, comparaison biaisée entre formats non pris en charge. Ils sont rattachés aux étapes 1, 2, 3 et 5. Le choix du modèle et les recettes d'entraînement restent conditionnés à des résultats mesurés. Le premier lot ci-dessous a été autorisé par l'utilisateur.

## Lot d'exécution initial — diagnostic et banc mobile

Le Poco n'est pas exposé par ADB lors de la vérification initiale. La première livraison sera donc un APK de diagnostic compatible avec la mise à jour de la version 0.9.8. Les poids, délais, consignes, garde-fous, découpage et moteur de dictée restent ceux du témoin afin que la mesure explique le comportement signalé.

- Lot A, **complexe**, confié à `gemma4_training` : instrumentation du coordinateur progressif, temps de fin ASR et rapport copiable. Les compteurs sont bornés et ne contiennent pas le texte. Une absence de réponse n'est pas automatiquement appelée un timeout. Les appels invalidés ne sont pas présentés comme des corrections livrées. Les instants sont monotones et substituables dans les tests. Deux revues du principal sur le même état de fichiers sont requises.
- Lot B, **moyen**, confié à `research_artifacts` : six textes synthétiques français fixes en Texte corrigé et trois passages par texte dans le moteur actuellement installé. Le dialogue distingue chargement, calcul, validation, résultat inchangé, modifié et rejeté. Seuls les textes synthétiques sont copiables dans ce banc. Le test isolé est refusé pendant une dictée ; l'inverse est également protégé pendant un test. Le banc ne revendique aucune comparaison avec Gemma 3 avant que cette configuration soit réellement intégrée et exécutée.
- Le principal examine en parallèle les conditions de conversion des poids fine-tunés vers LiteRT-LM, sans installer de nouveaux runtimes ni lancer de conversion ou d'inférence. Le premier lot ne consomme aucun des deux essais d'optimisation prévus.
- Un seul processus Gradle à la fois, JDK 21, deux workers et tas de 2 Go localement. Les tests ciblés précèdent une vérification intégrée unique ; les rendus du dialogue sont inspectés. Publication uniquement après les revues, la réussite CI et la vérification du véritable APK.

La mesure réelle sur Poco et le choix du modèle resteront ouverts après la livraison de ce premier lot jusqu'à réception des diagnostics de l'appareil.

### Vérification du premier lot

Le 21 septembre : 686 tests JVM réussis (aucun échec, erreur ou test ignoré), 13 contrats Python réussis, APK debug et androidTest construits avec JDK 21. Les aperçus du dialogue et de son entrée dans Mise en forme ont été inspectés. Aucun modèle n'a été exécuté pendant ces tests.

Le [run Actions 35648476423](https://github.com/Uhama91/DictAI/actions/runs/35648476423) a réussi sur le commit `a7b104f427de47f4006929b030989065eff27904`. L'APK public et l'artefact CI sont identiques : 87 563 641 octets, SHA-256 `63e8d1945abc4571cd7ba57116b9d6539e4ff1113230421a7ff64218003e5d66`. Le code 38, le certificat identique, la signature v2, les notices et les alignements ELF/ZIP 16 Ko des 12 bibliothèques ont été contrôlés ; aucun poids n'est inclus. Le guide `docs/poco-latency-test-guide.md` contient le téléchargement et les étapes Poco. Les critères des six cas sont dans `docs/poco-latency-case-review.md`. Aucune installation, mesure de latence ou évaluation de qualité n'a encore été effectuée sur le téléphone pour cette livraison.

### Retour du 22 septembre et ordre des prochaines actions

Sur Pad 7, l'utilisateur rapporte pour son deuxième essai avec 0.9.9 : récupération finale ASR de 218 ms, post-traitement de 20 086 ms, insertion à 20 664 ms, aucun appel partiel, un appel final arrivé à échéance et aucun premier fragment. Le texte source est conservé. Ce cas ne permet pas de juger la qualité d'une réponse du modèle, puisqu'aucune correction n'a abouti ; il suffit à rendre prioritaire le témoin léger. Le chargement historique de 5 303 ms n'est pas ajouté à ces durées : l'attente du backend pour ce nouvel appel est de 10 ms.

Le diagnostic des notes doit être réparé en parallèle (plan du 22 septembre), sans changement de modèle. La prochaine expérience de modèle consiste à porter le banc des six textes sur le Gemma 3 270M V3 existant. Le pilote de référence `e8249e59f43cb4b6826fff471c669cd03db0a15b` est limité à la langue française et au format Texte corrigé, avec sa consigne V3, deux threads CPU et un contexte de 8 192. Son ancien APK est au code 36 : il ne constitue pas une mise à jour du code 38/39. La comparaison exige donc une nouvelle livraison compatible, avec identité des poids vérifiée et maintien des fonctions d'overlay récentes. Elle comparera des configurations utilisables, sans attribuer toute différence au seul modèle de base.
