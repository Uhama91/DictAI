# Gemma 0.8.0 — premiers retours du téléphone d’Ullie

Source : rapport du menu DictAI et essais manuels copiés par Ullie le 9 septembre 2026. Appareil Xiaomi 25053PC47G, Android 16. [Mesures, sorties et exemples manuels](benchmarks/local-format/gemma4-poco-f7-user-2026-09-09.json). Aucun téléphone n’a été piloté par Codex.

## Exécution et vitesse constatées

Le banc utilise bien Gemma 4 E2B avec LiteRT-LM 0.17.0, GPU, MTP activé et thinking désactivé (budget zéro). Le moteur était déjà chargé ; sa dernière initialisation a pris 11 818 ms. Les caches système et GPU n’ont pas été vidés.

Sur 16 appels au modèle : premier fragment médian 1 191 ms, retour complet médian 1 986,5 ms, minimum 1 296 ms et maximum 3 047 ms. Les deux acquiescements à 0 ms n’appellent pas le modèle et sont exclus de ces statistiques. Les temps ne comprennent pas l’arrêt ASR, l’affichage ni l’insertion. Les deux passages répètent les mêmes huit sources appelant le modèle.

PSS du processus : 1 825 → 2 092 Mio ; RAM disponible : 3 137 → 3 103 Mio ; état thermique Android : 0 aux deux échantillons. Le PSS peut exclure de la mémoire GPU partagée ; ces échantillons ne prouvent pas l’absence de ralentissement thermique entre les mesures.

Ullie juge la latence acceptable sur une liste et un mail court en dictée réelle. Cela établit un ressenti favorable sur ces exemples, pas une mesure complète pour tous les formats et longueurs.

## Qualité et rejets

Parmi les 16 appels LLM, 10 réponses sont acceptées ; six de ces réponses réussissent les critères ciblés de regroupement. Les acquiescements directs sont comptés séparément. Chaque résultat est identique dans les deux passages ; il ne s’agit pas de 16 cas indépendants.

- Liste d’actions : le brut supprime « et » devant la troisième action. Le contrôle exige tous les mots de la source et rejette donc la réponse, même si le découpage paraît utile.
- Mail anglais avec interdiction : ajout de « Sincerely, [Your Name] », non dicté. Rejet justifié.
- Mail anglais avec nombre : conversion de « 2 » en « two ». Rejet par la politique de conservation actuelle.
- Courses sans virgules : trois puces au lieu de six. Le contenu est conservé, le découpage est incorrect.
- « du lait de la ferme du pain » : une puce au lieu de deux. Le complément doit rester avec le lait, le pain doit être séparé.
- Mail français, courses avec compléments et liste anglaise avec compléments : critères ciblés réussis.

Le libellé « Repères de contenu : 0/N ; absents : … » après rejet est trompeur : `LocalFormatBenchmarkDialog` examinait `output.orEmpty()`, donc une chaîne vide après rejet. Il ne prouve pas que les mots ont disparu du brut. Une correction locale affiche désormais « non évalués (aucune sortie validée) » ; elle ne modifie pas les contrôles d’acceptation ni la génération. Cette correction n’est pas encore dans l’APK 0.8.0 publiée.

Vérification de cette correction d’affichage : compilation Kotlin du prototype réussie (`:app:compileDebugKotlin -PlocalFormatPrototype=true`), `git diff --check` et recomptage du rapport JSON réussis. Aucun nouveau test de génération ou essai Android effectué.

## Essais manuels

La liste est jugée très satisfaisante par Ullie, avec chiffres en symboles. Le résultat visible garde cependant « Du pain du lait » sur une seule puce : pain et lait restent deux produits regroupés. Les autres puces sont séparées. La source ASR exacte avant formatage n’est pas disponible pour contrôler la fidélité.

Le premier mail (21 mots dans le résultat fourni) comporte salutation, corps et fermeture séparés ; Ullie juge la latence faible. Les deuxième et troisième mails (60 et 93 mots) restent en un seul bloc.

Vérification du code de 0.8.0 : `OverlayService.processStoppedRecording` attend au plus 5 000 ms à la finalisation ; une réponse rejetée, une erreur ou un délai dépassé conserve la transcription. Une réponse fidèle mais sans paragraphes peut également être acceptée : la validation ne garantit pas le découpage. Les deux textes rapportés sont sous la limite de 512 mots et 16 000 caractères du formateur. Leur longueur seule ne suffit donc pas à prouver un refus d’entrée.

La cause exacte des deux mails longs reste indéterminée sans le diagnostic de leur publication. Le contenu de **Dernier post-traitement**, immédiatement après un mail long resté en bloc, a été demandé à Ullie : format demandé, moteur/appliqué, état, attente finale et arrêt vers insertion permettent de départager délai, rejet, résultat accepté sans découpage ou route incorrecte.

Un quatrième mail reste également en bloc. Le diagnostic transmis ensuite est daté du 9 septembre 2026 à 09:12:47 +0200 et indique **Format : Texte**, moteur demandé local, transcription conservée, aucun appel natif, post-traitement 1 ms, arrêt → insertion 367 ms. Le GPU était prêt ; le dernier chargement reste 11 818 ms. Il confirme le chemin direct d’une dictée Texte, pas l’exécution du mail précédent. On ne peut pas en déduire qu’Ullie avait choisi Texte pour son mail : une dictée ultérieure a pu remplacer le rapport. Le code de 0.8.0 écrase effectivement l’unique rapport à chaque publication.

Correction préparée dans 0.8.1 : conserver atomiquement le dernier rapport de dictée et, dans un autre emplacement, le dernier rapport d’un format demandé (instructions non vides capturées au début de la dictée), même si le formatage échoue. Le menu présente ce dernier format par défaut ; un bouton permet de consulter la dernière dictée. Le mode Texte sans appel est décrit comme attendu. Les métadonnées restent seules conservées ; pas de dictée, vocabulaire ni clé API dans les rapports. Les rapports déjà écrasés en 0.8.0 ne sont pas récupérables.

Les tests de régression couvrent un mail en échec suivi de plusieurs dictées Texte, la recréation des préférences, le prochain format et les anciennes préférences. Le modèle installé, ses consignes et les délais ne sont pas modifiés. La nouvelle APK sera publiée via GitHub Actions après vérification ; le diagnostic d’un mail long reste nécessaire pour corriger sa cause réelle.

Cinquième essai : Ullie confirme avoir vérifié le choix Mail ; le rapport communiqué à 09:16:49 indique encore Texte, sans appel natif, post-traitement 1 ms et arrêt → insertion 477 ms. Ne pas attribuer cela à une erreur de sélection de sa part. Le message contient aussi une explication dictée après le mail, qui pourrait avoir remplacé le diagnostic ; aucun lien sûr entre ce rapport et le mail ne peut être établi.

Contrôle du chemin de sélection : le menu de la pastille écrit le format sélectionné, puis `startRec` le capture avant l’enregistrement. Changer les réglages après le démarrage concerne la prochaine dictée. Le code utilisait ensuite une deuxième copie nullable des options ; ses chemins de secours pouvaient construire des options par défaut (Texte). Rien ne démontre que ce secours a été utilisé ici. Dans 0.8.1, les options sont obligatoires et immuables sur `ActiveDictationRun`, et l’arrêt utilise cette même instance que l’overlay et la préparation Gemma, y compris dans le secours de capture. La copie globale nullable et les replis implicites vers Texte sont supprimés.

0.8.1 publiée et vérifiée : [APK directe](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34323447465/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34323447465), [contrôles](VERIFICATION-GEMMA-0.8.1.md). Suite : essai Mail sur cette version puis copie du diagnostic conservé. Les consignes et le délai Gemma n’ont pas été modifiés en l’absence de diagnostic Mail fiable.

## Diagnostic Mail reçu et correction 0.8.2

Rapport du 9 septembre 2026 à 09:25:39 +0200, version 0.8.0 : **Format Mail**, appel natif oui, nouveau calcul, **sortie rejetée : texte non conservé**. Attente finale 4 957 ms, post-traitement 4 959 ms, arrêt → insertion 5 590 ms. Ce cas est un rejet de fidélité, pas le déclenchement du délai d’attente de cinq secondes. Il confirme que le choix Mail est transmis au moteur dans cet essai.

Reproduction sur ordinateur avec la consigne de production et le même paquet officiel : le mail est structuré mais le mot **normalement** est supprimé avant la fermeture. Tous les autres mots restent dans l’ordre. L’ancien contrôle rejette la totalité du formatage. Le mail court de contrôle est accepté. [Entrées, brut, différences et sortie reconstruite](benchmarks/local-format/gemma4-long-mail-rejection-2026-09-09.json). Les sorties CPU/MTP désactivé peuvent différer de celles du GPU Android ; le mot exact omis sur téléphone n’est pas connu. Les temps CPU ne sont pas une estimation de latence téléphone.

0.8.2 rétablit les petites omissions dans les mails à partir des mots exacts de la source, puis repasse le contrôle strict de fidélité. Maximum trois mots et 5 % des unités lexicales, alignement unique dans l’ordre, premiers/derniers mots conservés. Les nombres, fragments techniques, quotes et coupures incertaines ne sont pas inférés. Les omissions à une coupure de paragraphe ne sont restaurées que devant une fermeture explicite et non ambiguë. Listes, ajouts, substitutions, réordonnancements et troncatures gardent les contrôles précédents.

Le cas reproduit conserve maintenant tous les mots, dont normalement, avec salutation, corps et fermeture séparés. Aucun second appel au modèle et aucun allongement du délai ne sont ajoutés. Le diagnostic expose uniquement le nombre de mots rétablis, pas leur contenu. Le banc passe à dix exemples, dont ce mail long ; son brut avant reconstruction reste visible.

La correction porte sur la cause reproduite du rejet ; elle ne garantit pas tous les mails ni une latence inférieure aux 5,590 s de l’essai téléphone. Les erreurs de regroupement de listes et les autres reformulations restent des limites. Les résultats finaux de compilation et de publication de 0.8.2 sont consignés séparément.

0.8.2 publiée et téléchargée pour vérification : [APK directe](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34325226264/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34325226264), [rapport de vérification](VERIFICATION-GEMMA-0.8.2.md). Elle inclut aussi la conservation des diagnostics introduite en 0.8.1 ; Gemma déjà téléchargé est conservé lors de la mise à jour.

## Banc 0.8.2 sur le POCO F7 — correction confirmée, marge de temps insuffisante

Ullie fournit les vingt passages du banc de 0.8.2 : [mesures et sorties archivées](benchmarks/local-format/gemma4-poco-f7-082-user-2026-09-09.json). Le mail long réussit deux fois, avec un mot rétabli (normalement), tous les repères conservés et les paragraphes attendus. Le brut GPU montre la même omission que la reproduction CPU. Cette correction est donc confirmée sur le téléphone pour cet exemple.

Le mail long termine en **5 136 ms et 4 978 ms**. Le banc attend directement le moteur (limite de 20 s) et mesure son retour avant validation, alors que `LocalFormattingSession.finish` dispose de 5 000 ms à la finalisation. Si le calcul entier doit démarrer à cet instant, le premier temps dépasse la limite, et le second ne laisse que 22 ms avant validation et aléas d’exécution. Une génération anticipée peut réduire le temps restant. Le banc réussi ne garantit donc pas la réussite de la dictée réelle ; aucun nouveau diagnostic de délai dépassé n’a été fourni à ce stade.

Sur dix sources répétées deux fois : dix-huit appels au LLM, douze réponses acceptées, dont huit réussissent les critères ciblés et quatre gardent un mauvais regroupement ; six réponses sont rejetées. Les deux acquiescements directs ne sont pas des générations LLM. Les échecs inchangés concernent la suppression de et dans la liste d’actions, la signature anglaise inventée, la conversion 2 → two, les courses regroupées par paires et le complément de la ferme non séparé du pain. La correction ciblée du mail ne valide pas ces autres formats.

Dernière initialisation : 7 472 ms, moteur déjà chargé pendant le banc. Premier fragment médian sur les dix-huit appels : 1 363 ms ; retour complet médian : 2 376,5 ms. Sur les huit sources LLM communes au banc 0.8.0, le retour complet médian est de 2 196 ms contre 1 986,5 ms précédemment ; les conditions appareil/caches/mémoire diffèrent, donc ce n’est pas une preuve de régression causée par le code.

PSS processus 1 753 → 1 947 Mio, RAM disponible 2 183 → 2 559 Mio, état thermique Android 0 aux deux relevés. Ne pas attribuer les durées à une cause thermique ou mémoire sur ces seules mesures.

Suite prioritaire : rendre la finalisation des mails longs plus rapide et régulière avec les mots conservés. Ne pas présenter une simple hausse du délai comme une amélioration de vitesse. Les cas de regroupement et les rejets anglais restent distincts. Aucun changement d’application ni nouvelle APK n’est réalisé à la réception de ce seul rapport.

Ne pas modifier le délai de cinq secondes, remplacer le modèle ou relâcher globalement la fidélité sur la seule base d’un bloc de texte. Gemma reste la base d’essai ; priorité à l’identification de l’échec des mails longs avec le ressenti de faible latence conservé.


## Retour 10:08 — délai dépassé et correction dans l’overlay

Rapport fourni par Ullie le 9 septembre, 0.8.2, 10:08:10 +0200 : format Mail, local, modèle chargé en 7 472 ms auparavant, calcul en attente, appel natif effectué, attente finale 5 003 ms, post-traitement 5 007 ms et arrêt → insertion 5 846 ms. Le résultat est la transcription source en une ligne. Il s’agit bien cette fois d’un délai dépassé, pas du rejet de fidélité du premier mail. Le rapport ne sépare pas le temps de file du temps natif : ne pas attribuer toutes les cinq secondes à la file.

Le nouveau texte commence par Bonjour, voici le nouveau test concernant la génération d’un nouveau mail, se termine par Cordialement, Monsieur le Testeur et dépasse la longueur du cas du banc précédent. Sa source exacte est ajoutée au test `SimpleEmailLayoutTest` et au banc intégré.

Ullie signale aussi que la dernière ligne devient tronquée dans le petit overlay après correction ; la vue reste près de l’avant-dernière ligne. Le code cessait explicitement le suivi dès que l’éditeur avait le focus, or celui-ci reste acquis après saisie. 0.8.3 dissocie curseur et défilement : attente de fin de mise en page, remise à zéro du défilement interne de l’EditText, positionnement du ScrollView au bas réel, y compris après redimensionnement. Le suivi attend 900 ms sans interaction et respecte sélection, composition et toucher. Un nouveau texte déclenche le suivi ; une retouche seule ne déplace pas automatiquement la vue.

Pour le mode Texte, le LLM reste absent en local. Le nouveau réglage Nettoyage léger du texte active des règles finales FR/EN, partagées également avec l’entrée du mode cloud. Il supprime certains euh/uh/um non cités et je je/I I ; il conserve nous nous, vous vous, très très, les négations, les nombres, les citations et les termes du vocabulaire. Une retouche manuelle désactive ce nettoyage global pour la dictée. Quelques formes interrogatives explicites reçoivent un point d’interrogation ; aucune compréhension générale des questions implicites n’est annoncée. Le cloud choisi explicitement conserve sa réécriture existante.

Le mail conventionnel bénéficie d’une disposition directe de la salutation, du corps et de la signature. Seuls les cas avec bornes reconnues et signature non ambiguë passent ; chaque mot reste vérifié par la projection stricte. Citations, fermetures multiples, destinataire incertain et post-scriptum non reconnu restent hors de cette voie. Cette opération ne génère pas tous les mots via Gemma ; elle n’attend pas une génération obsolète pour terminer. Les autres mails restent soumis à la limite finale de 5 000 ms. Le modèle, le prompt, GPU/MTP et thinking off sont conservés.

Le banc distingue explicitement le traitement direct et le LLM : ne pas présenter la durée directe comme une accélération de Gemma. Onze sources, deux passages, avec un ancien mail long forcé via Gemma pour comparaison. Les résultats téléphone de 0.8.3 restent à recevoir ; les tests JVM et la compilation ne valident pas le rendu HyperOS ni sa latence réelle.

0.8.3 publiée et téléchargement public vérifié : [APK directe](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34329249048/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34329249048), [contrôles et limites](VERIFICATION-GEMMA-0.8.3.md). 357 tests JVM et 18 tests Python réussis ; test Android du défilement compilé, non exécuté. Installation en mise à jour conserve le modèle.


## Réévaluation du plafond de cinq secondes — 0.8.4

Ullie demande de mesurer le temps complet au-delà de cinq secondes : un léger dépassement ne justifie pas de jeter une mise en forme presque terminée. Le banc téléphone de l’ancien mail long (4 978 / 5 136 ms) étaye cette objection. Le dernier mail manuel a été interrompu après 5 003 ms d’attente finale ; sa durée GPU complète était alors inconnue ; les mesures 0.8.4 ci-dessous complètent ce constat.

Sur l’ordinateur, l’ancien mail long avait terminé en 15 399 ms et le court en 7 281 ms, CPU deux threads. Une nouvelle génération du dernier mail de 144 mots termine en 24 923 ms, premier fragment à 6 457 ms, sans changement lexical et avec mise en paragraphes acceptée. [Source, sortie et paramètres](benchmarks/local-format/gemma4-latest-long-mail-host-duration-2026-09-09.json). Essai CPU isolé, moteur déjà chargé, aucune construction Gradle simultanée, limite de 45 s non atteinte. Les conditions CPU/MTP diffèrent du GPU du téléphone ; ne pas utiliser un ratio pour annoncer une durée Android.

0.8.4 rétablit la génération Gemma pour les mails de 60 mots ou plus. La limite de finalisation Mail devient 8 000 ms comme marge d’essai, sans délai minimum ; les autres formats gardent 5 000 ms. Ce réglage augmente la possibilité de terminer, pas le débit du modèle. Le diagnostic contient le plafond réellement utilisé. Un message long peut encore échouer par dépassement ou fidélité ; ces états restent distincts.

Le nouveau menu Mesurer les mails longs avec Gemma exécute uniquement les deux sources longues, chacune deux fois, sans règle de mise en page directe. Il appelle le moteur jusqu’à son plafond de 20 000 ms, hors préchargement initial mais file comprise. Les mesures séparent attente avant natif, génération jusqu’au retour, validation et écarts à 5/8 s. Elles n’incluent pas arrêt ASR ni insertion et ne prédisent donc pas exactement le délai d’une dictée anticipée. Si un essai expire, le rapport indique une attente observée et une durée nécessaire inconnue ; il ne transforme pas le timeout en temps de génération complète.

Protocole proposé lors de la publication : installer la version, ouvrir ce menu avec la dictée au repos, attendre les quatre résultats et copier le rapport. Les mesures ont depuis été fournies dans le banc complet ci-dessous. Ne pas désactiver le thinking off ni changer de modèle pour cette comparaison. La limite finale définitive dépendra de ces mesures et du ressenti d’Ullie.

0.8.4 publiée : [APK directe](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34335478012/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34335478012), [vérification complète](VERIFICATION-GEMMA-0.8.4.md). 361 tests JVM réussis ; téléchargement public complet, signature et alignement vérifiés. Les temps complets reçus depuis sont consignés ci-dessous ; la limite définitive reste ouverte.


## Premier rapport GPU 0.8.4 — 5,836 s et 9,233 s, rejet d’une correction de forme

Le rapport a d’abord été reçu tronqué pendant le septième cas du passage 2, puis Ullie en a fourni la suite. Il est maintenant **complet : onze sources × deux passages**, sans compter deux fois les cas recopiés dans le second message. [Archive complète](benchmarks/local-format/gemma4-poco-f7-084-user-2026-09-09-partial.json) (le nom initial partial reste conservé pour les liens). Le moteur était déjà chargé.

| Mail | Passage | Retour moteur | Validation | Appel + validation | Résultat |
|---|---:|---:|---:|---:|---|
| Ancien long | 1 | 5 807 ms | 29 ms | **5 836 ms** | Accepté, regroupement réussi, normalement rétabli |
| Ancien long | 2 | 5 217 ms | 33 ms | **5 250 ms** | Accepté, regroupement réussi, normalement rétabli |
| Dernier, 144 mots | 1 | 9 209 ms | 24 ms | **9 233 ms** | Rejeté pour conservation lexicale |
| Dernier, 144 mots | 2 | 8 879 ms | 27 ms | **8 906 ms** | Rejeté pour conservation lexicale |

L’ancien mail dépasse 5 s de 250 à 836 ms et garde plus de deux secondes de marge sous 8 s dans ces essais. Le dernier dépasse 5 s de 3 906 à 4 233 ms et 8 s de 906 à 1 233 ms. Les 20 s du banc n’ont pas été atteintes : ce sont des retours complets, contrairement au diagnostic manuel arrêté après 5 003 ms. Ces temps n’incluent ni arrêt ASR ni insertion ; une anticipation terminée plus tôt peut réduire l’attente finale réelle. Deux passages ne suffisent pas à garantir les durées futures.

Sur le dernier mail, l’attente avant appel natif est de 22 / 21 ms, le natif jusqu’au retour moteur de 9 187 / 8 858 ms et la validation de 24 / 27 ms. L’essentiel du temps observé provient de la génération, pas de la file ou du contrôle de fidélité dans ces essais. Le premier fragment arrive à 2 200 / 1 779 ms et ne constitue pas encore un mail publiable validé.

Les deux bruts du dernier mail sont identiques : ils possèdent les paragraphes attendus et remplacent uniquement locale par local, dans la phrase L’intérêt d’avoir la mise en forme en locale. Les 157 unités lexicales (144 mots séparés par espaces) restent dans le même ordre ; une seule unité diffère. La correction en local est naturelle en français, mais le validateur actuel refuse toute substitution lexicale. Vérification exécutée à la première réception contre les classes réelles compilées pour 0.8.4 : brut refusé ; même brut avec uniquement l’orthographe source locale rétablie accepté. Le brut GPU est exactement le brut CPU archivé précédemment à cette substitution près. Aucune nouvelle génération ni règle applicative ajoutée dans cette analyse.

Le plafond de 8 s ne suffit donc pas à attendre ce dernier résultat depuis zéro ; l’augmenter ne suffirait pas non plus à publier la sortie, car le contrôle la rejette ensuite. Il faut distinguer une éventuelle marge plus longue acceptable pour l’utilisateur de l’adaptation des vérifications aux corrections de forme. Ne pas accepter en bloc les signatures inventées ou les modifications de nombres : les rejets anglais et les regroupements de listes restent distincts.

Sur vingt appels Gemma : douze réponses acceptées, dont huit réussissent les critères ciblés et quatre gardent un mauvais regroupement ; huit réponses rejetées. Les deux acquiescements directs sont acceptés et ne sont pas des générations LLM. Les verdicts se répètent sur les deux passages ; les courses sans virgules restent groupées par paires et de la ferme reste attaché au pain. Ces quelques critères sur onze sources ne sont pas une évaluation générale de qualité.

PSS processus **1 791 → 1 945 Mio**, RAM disponible **2 371 → 2 734 Mio**, thermique Android **0 aux deux relevés** ; initialisation du moteur **7 546 ms**, antérieure au banc. La mémoire GPU partagée peut être exclue du PSS. Les deux points thermiques ne permettent pas de décrire les variations pendant tous les essais. Pas de nouvelle APK pour cette réception de mesures ; les conclusions et les travaux restants sont enregistrés. La suite du rapport est reçue : ne plus la redemander.
