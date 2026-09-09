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

Ne pas modifier le délai de cinq secondes, remplacer le modèle ou relâcher globalement la fidélité sur la seule base d’un bloc de texte. Gemma reste la base d’essai ; priorité à l’identification de l’échec des mails longs avec le ressenti de faible latence conservé.
