# Suivi des demandes d’Ullie — DictAI

Mis à jour le 9 septembre 2026. Travail en cours dans Codex sur la branche existante. Procéder par étapes testées, à la demande d’Ullie ; ne pas oublier les demandes suivantes et ne pas les considérer comme déjà livrées.

## Retour 10:08 — overlay, mode Texte et attente Mail — 0.8.3

Nouveau diagnostic réel reçu : Mail, appel natif oui, calcul en attente, délai final dépassé à 5 003 ms, arrêt → insertion 5 846 ms. Il confirme le manque de marge du mail plus long. Ullie juge les listes locales globalement satisfaisantes malgré les deux premiers produits regroupés et remet en priorité le suivi de la dernière ligne après correction dans le petit overlay.

Correctifs locaux :
- Le suivi du texte ne dépend plus de `EditText.hasFocus()`. Défilement après mise en page, reprise après 900 ms sans interaction ; aucun déplacement forcé du curseur. Sélection, composition clavier et toucher suspendent le suivi. Redimensionnement et clavier conservé pris en compte.
- Mode Texte : nettoyage final déterministe FR/EN, réglage activé par défaut et désactivable. Hésitations non citées et répétitions limitées de pronoms ; vocabulaire, identifiants, citations, nombres, négations et répétitions expressives protégés. Aucun nettoyage global après retouche manuelle. Ponctuation de quelques questions explicites ; les questions implicites et reformulations ne sont pas résolues.
- Mails simples : salutation, corps, fermeture explicite et signature sont séparés directement, puis validation complète des mots et signes techniques. Cette voie contourne même un ancien calcul en cours ; aucune hausse du délai de 5 s. Les mails ambigus restent confiés à Gemma, avec ses limites. Listes inchangées.
- Le banc contient onze cas répétés deux fois ; il distingue voie directe et Gemma. L’ancien mail long conserve un essai Gemma seul pour comparaison ; le nouveau mail est testé avec la voie directe de l’overlay.

357 tests JVM réussis dans 53 suites ; APK application et tests instrumentés compilées, signature et alignement 16 Ko vérifiés. [APK 0.8.3 publiée](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34329249048/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34329249048), source `36223d9300f582dc94e2e20d6b305391fc9a594a`. Téléchargement public complet et SHA vérifiés, 78 584 209 octets. Le test instrumenté de géométrie EditText/ScrollView avec focus conservé est compilé mais son exécution reste à faire sur Android. [Vérification](VERIFICATION-GEMMA-0.8.3.md). Aucun appareil n’est connecté à Codex. Voir [analyse](2026-09-09-retour-gemma-telephone.md#retour-1008--délai-dépassé-et-correction-dans-loverlay).

## Choix Gemma — prototype 0.8.0

**Retour 0.8.2 reçu :** le mail long est validé deux fois sur le GPU du POCO F7, avec normalement rétabli et paragraphes corrects. Durées 5 136 / 4 978 ms : la correction de fidélité est confirmée pour ce cas, mais la finalisation réelle reste fragile face à la limite de 5 000 ms, contrairement au banc qui peut attendre 20 s. [Résultats complets et limites](2026-09-09-retour-gemma-telephone.md#banc-082-sur-le-poco-f7--correction-confirmée-marge-de-temps-insuffisante). Les autres échecs de listes et mails anglais persistent. Priorité suivante : vitesse et régularité des mails longs ; ne pas remplacer cette priorité par un simple allongement de l’attente. Pas de nouvelle APK pour ce rapport seul.

**Cause enfin identifiée sur un rapport Mail à 09:25:39 :** Gemma appelé, résultat rejeté pour fidélité, attente 4 957 ms, total arrêt → insertion 5 590 ms. Pas un dépassement du délai sur cet essai. Reproduction CPU : un seul mot supprimé, normalement, alors que les paragraphes sont corrects. 0.8.2 en préparation : rétablir de petites omissions depuis la source avec alignement unique puis contrôle strict, sans autre génération ; ajouts, substitutions, troncatures et cas ambigus refusés. Nouveau cas de mail long dans le banc et compteur de mots rétablis dans le diagnostic. Voir [le retour détaillé](2026-09-09-retour-gemma-telephone.md#diagnostic-mail-reçu-et-correction-082). Le diagnostic valide Mail est reçu : ne plus le redemander comme s’il manquait encore.

0.8.2 publiée et vérifiée : 343 tests JVM dans 50 suites, zéro échec ; APK signée/alignée 16 Ko, 78 567 829 octets, SHA-256 `624f4a623df77d8d6d9544f8dde7563b549ec7d85b8959d1668f85fcf51abc5a`. [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34325226264), [APK directe](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34325226264/dictai-local-layout-test.apk), source `38ddfff38a045301908d484883b3f09ed17253fc`, artefact `dictai-local-layout-test`. Téléchargement public complet et empreinte vérifiés, identiques à l’APK locale. [Vérification et limites](VERIFICATION-GEMMA-0.8.2.md). Suite : résultat sur téléphone du mail long avec cette version ; ne pas déclarer toutes les reformulations ou la latence longue résolues.

**Retour téléphone reçu après publication :** [analyse des mesures et essais manuels](2026-09-09-retour-gemma-telephone.md). GPU/MTP/thinking off confirmés dans le banc ; initialisation 11,818 s, retour complet médian 1,987 s sur 16 appels LLM. Ullie juge la vitesse et le mail court satisfaisants ; deux mails plus longs restent en bloc. Leur diagnostic « Dernier post-traitement » est demandé pour distinguer délai de 5 s, rejet ou résultat sans paragraphes. Le libellé de repères 0/N après rejet est corrigé localement, sans modification de la génération et sans nouvelle APK publiée à ce stade. La cause des mails longs reste ouverte.

Le rapport reçu à 09:12:47 correspond à **Texte**, sans appel natif et 367 ms jusqu’à l’insertion. Il ne permet pas d’expliquer les mails. 0.8.0 remplace le diagnostic après chaque dictée ; 0.8.1 conserve séparément le dernier format demandé, y compris après échec, et l’affiche par défaut. Correction du statut Texte et du score des repères après rejet ; moteur et délai inchangés. Vérification et publication Actions en cours. Le diagnostic Mail est encore demandé pour identifier la cause du formatage long.

À 09:16:49, second rapport Texte (477 ms) malgré le choix Mail vérifié par Ullie. Ne pas supposer une mauvaise sélection : le rapport peut appartenir à la dictée explicative suivante. Vérification du code et suppression en 0.8.1 de la copie nullable d’options à l’arrêt : le format est désormais obligatoire et immuable sur la dictée, partagé par l’overlay et la finalisation, sans repli implicite vers Texte.

0.8.1 publiée : 331 tests JVM dans 49 suites réussis localement, APK signée et alignée 16 Ko ; [résultats](VERIFICATION-GEMMA-0.8.1.md). [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34323447465), artefact `dictai-local-layout-test`, [APK directe](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34323447465/dictai-local-layout-test.apk). Source `50f54ec21468ee702e93687c37a69211a3f26161`, 78 551 445 octets, SHA-256 `ab2d3525bdfc1af90e0b6ada268e258a10c344c77e54ef98a12dd253f7401b90` ; téléchargement public complet vérifié. Un nouvel essai Mail sur cette version permettra de lire un diagnostic conservé même après les dictées d’explication ; la cause des mails longs n’est pas déclarée résolue.

Ullie autorise l'intégration de Gemma. [Notice de l'essai](ESSAI-GEMMA.md) : LiteRT-LM 0.17.0 GPU + MTP, thinking désactivé, modèle partagé et préchauffé, téléchargement officiel de 2,6 Go depuis l'application avec reprise et contrôle SHA. L'APK ne contient plus le poids 350M. Validation locale terminée : 328 tests JVM dans 48 suites et 18 tests Python réussis, APK prototype (79 311 099 octets) et standard construites/signées/alignées 16 Ko. [Vérification](VERIFICATION-GEMMA.md). Publication terminée : [APK directe Gemma 0.8.0](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34319036761/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34319036761), source `4f6ad676392165b0be028217703d00c5f66c05bf`. APK Actions de 78 551 445 octets, SHA-256 `41801b4f8c3c0a090fbd70aef3966bcb2dc7f8223575b473758bb3e5e8dddfa6`, téléchargée intégralement et vérifiée après publication. Aucun test GPU Android effectué sur le POCO F7 dans cette session.

Les sondes du prompt de production donnent dix générations complètes, six fidèles acceptées, dont cinq formats satisfaisants ; le découpage des courses reste imparfait. Une variante produisant des indices de coupure a échoué sur le regroupement et n'est pas intégrée. Le prototype mesure Gemma en situation réelle, sans présenter la qualité locale comme résolue.

## Étape du 9 septembre — retour réel d’Ullie

Qualité du 350M **non validée** : les regroupements de liste et fermetures de mail signalés sont reproduits. La latence est jugée satisfaisante par Ullie. Comparaison de quatre modèles avec le JNI réel et variantes de consigne : 84 générations archivées ; les alternatives améliorent certains cas mais restent irrégulières. Aucun remplacement de modèle ni variante de prompt n’est livré comme correction acquise. [Résultats et décision](2026-09-09-retour-essai-local.md).

Version 0.7.2 : format/moteur visibles dans l’overlay, retrait Ranger/Coller, préparation du panneau avant premier tap, numérotation générique et énumération avec un corrigées, point final de prose, geste ↑Envoyer pendant la pause. 251 tests JVM et 18 tests Python réussis ; APK normale et prototype vérifiées dans [le rapport](VERIFICATION-POST-TRAITEMENT.md) ; gestes et gain au démarrage restent à confirmer sur téléphone. L’étape suivante du LLM reste ouverte et prioritaire.

## Complément : modèle plus lourd et thinking

Ullie accepte une application plus lourde pour améliorer la compréhension, exige des essais sans thinking et donne l'autorisation de poursuivre sans reconfirmer chaque étape. La session du 9 septembre est passée en accès complet. À la reprise, respecter le profil technique courant ; l'autorisation utilisateur persiste.

Rapport Xiaomi reçu : à chaud premier fragment 700 ms / fin 1723 ms sur la liste FR, 613 ms / 1304 ms sur le mail EN. Le JNI fonctionne dans le banc isolé, sans preuve de la route de chaque dictée réelle.

Nouveaux essais de Qwen3.5-2B (1,28 Go) et Qwen3-4B Instruct Q3_K_S (1,89 Go), sans thinking : aucun remplacement retenu pour la qualité/latence testée. Résultats et limites dans [la note canonique](2026-09-09-retour-essai-local.md#mesures-téléphone-et-essais-complémentaires) et [le rapport](benchmarks/local-format/larger-models-2026-09-09.json). Ne pas refaire les mêmes variantes de consigne.

Version 0.7.3 : diagnostic de la dernière publication, six cas du banc avec scores de regroupement distincts, plages de comptage avec 1 et recherche d'énumération corrigées. Modèle 350M inchangé ; qualité locale toujours ouverte. Validation locale terminée : 285 tests JVM dans 43 suites, 18 tests Python, APK normale et prototype signées et vérifiées (bibliothèques ARM64, alignement 16 Ko, poids unique et empreinte).

Publication 0.7.3 vérifiée : [APK directe](https://github.com/Uhama91/DictAI/releases/download/local-layout-test-34296957796/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34296957796), source `52e0663c8629471efd472e76b5905083f6d76a35`. Taille 290 068 953 octets ; SHA-256 `3a49b1c7bb19400849bc6082994cab5d87b8adffe86c6d4779818ea10dc2f015`, identique au contrôle du journal Actions et à l'empreinte de l'asset GitHub. Aucune installation sur téléphone réalisée par Codex.

Nouvelle priorité explicite : recherche approfondie des modèles récents et des fournisseurs de versions compressées pour mobile. La vélocité prime sur la taille du téléchargement. Comparer le couple modèle/runtime et les preuves sur le POCO F7, sans déduire la latence Android des essais CPU sur ordinateur. Aucun remplacement automatique de modèle à partir d'une annonce de débit.

Recherche approfondie terminée : [rapport et sources](2026-09-09-recherche-avancee-llm-mobile.md), [PDF](../output/pdf/dictai-recherche-llm-mobile.pdf). Gemma 4 E2B mobile avec LiteRT-LM GPU est la priorité d'expérimentation ; Qwen3.5-2B MNN reste un comparateur. Les nouveaux contrôles de Bonsai-4B, Luth-2-0.8B et Gemma 4 comprennent 27 générations exploratoires sur ordinateur, dont 26 complètes. Aucun candidat n'est prêt à remplacer le 350M : Gemma améliore certains formats mais conserve des erreurs de fidélité ou de regroupement ; Luth et Bonsai restent insuffisants dans les configurations essayées. Aucun modèle ni runtime nouveau n'est intégré à l'APK dans cette étape, aucune mesure POCO F7 effectuée. Suite : corpus inédit, prototype GPU sans thinking, puis délai final et mémoire mesurés avec la transcription résidente sur téléphone.

## 1. Post-traitement et correction personnelle — priorité actuelle

Décision actualisée du 8 septembre : **le LLM local n’est pas abandonné**. Les essais de réécriture libre ont échoué avec 350M et quatre candidats plus gros. Une nouvelle configuration LFM2.5-350M copie tous les mots sous contrainte et laisse le modèle choisir uniquement les puces/paragraphes. Le corpus élargi contient dix générations et deux réponses directes : tous les mots sont conservés, mais le découpage reste imparfait. La version normale conserve cloud/désactivé ; le prototype séparé `0.7.1-wp-local-test` sert à mesurer listes/mails sur téléphone. Ce n’est pas encore un remplacement général et instantané du cloud.

- [x] Prototype listes/mails avec modèle inclus et licence ; deux exemples par format et grammaire imposant la copie.
- [x] Aperçu progressif reconstruit depuis toute la source ; préparation pendant les pauses, moteur résident, annulation et retour à la source si délai dépassé.
- [x] Petit acquiescement sans salutation : résultat direct, sans attendre une génération antérieure. Le préchargement du moteur peut avoir commencé à l’ouverture de la dictée, mais aucune génération n’est nécessaire pour ce résultat.
- [ ] Validation de la qualité du découpage sur un corpus inédit plus large ; prise en charge des reformulations et formats personnels en local.
- [x] Formats cloud avec la clé API OpenRouter existante. Vocabulaire et nombres partagés entre modes ; aucune bascule cloud automatique.
- [x] Détection des remplacements manuels dans l’overlay et proposition explicite d’enregistrement `forme transcrite => forme corrigée`, y compris suppression puis saisie au même endroit. Suppression seule : aucune suggestion. Tests du cœur effectués.
- [x] Réglage nombres en chiffres / lettres / transcription conservée, FR/EN, avec protections des cas ambigus et tests.
- [ ] Validation des gestes et de la suggestion vocabulaire sur téléphone.
- [x] Exécution du JNI réel sur ordinateur : fidélité, UTF-8, annulation/reprise, grammaire invalide, troncature et délai vérifiés.
- [ ] Mesure de la latence complète sur le téléphone d’Ullie. Le banc intégré teste le moteur isolé ; la dictée réelle doit aussi vérifier arrêt ASR → insertion.

Fichiers de reprise : [essai sur appareil](ESSAI-LLM-LOCAL.md), [configuration et résultats](2026-09-08-comparatif-llm-local.md), [rapport de construction et contrôles](VERIFICATION-POST-TRAITEMENT.md). APK conservées dans `app/build/outputs/apk/verified/` : normale et `app-local-layout-test.apk`. La construction du prototype inclut un seul poids 350M ; les autres restent dans le cache de recherche. Attente additionnelle de mise en forme limitée à cinq secondes, sans garantie de latence globale avant mesure appareil. Le moteur retenu et le format sont capturés au début de la dictée ; changer le réglage prépare la suivante.

Publication du 9 septembre effectuée : [APK 0.7.2 directe sur GitHub](https://github.com/Uhama91/DictAI/releases/download/local-layout-test-34290412481/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34290412481). Taille 290 036 185 octets, SHA-256 `97d9846ec1b2509084f9c2d8d4b81652dd5e4b375c660064a1f252cfe49e249d`. Le modèle 350M est inclus ; source compilée `0f157a708b4bc4016eaef17e49962f210b8d43e1`. Ullie juge la latence du précédent essai acceptable ; la qualité du découpage reste non validée. Aucune installation sur appareil réalisée par Codex.

## 2. Réactivité et certitude au démarrage — après le lot LLM prioritaire

Nouvelle demande d’Ullie : après appui, il ne sait pas toujours si l’écoute a réellement commencé ; l’overlay ou les premiers mots tardent, ce qui le pousse à répéter son message. Demande conservée. Le dernier message d’Ullie remet explicitement le LLM local et sa latence en priorité ; reprendre ensuite cette incertitude sans la considérer corrigée.

- [ ] Mesurer séparément appui → retour visuel, appui → première lecture audio effective et première parole → premiers mots affichés, à froid et à chaud.
- [ ] Afficher immédiatement un état de démarrage ; passer à une indication claire d’écoute et à un retour haptique seulement quand la capture est réellement active. Ne pas faire attendre les premiers mots pour confirmer l’écoute.
- [ ] Vérifier que l’ouverture de la session ASR et les recherches de champ destinataire ne bloquent pas l’affichage ni le début de la lecture audio.
- [ ] Vérifier que les premiers mots d’un message très court sont conservés quand l’utilisateur parle immédiatement après appui. Ne pas masquer une éventuelle perte par un simple changement d’animation.
- [ ] Tester les refus micro, modèle encore en chargement, double tap, annulation au démarrage et reprise après pause.

Pistes vérifiées dans le code, sans diagnostic appareil établi : `RecordingStartupTransaction.start()` démarre AudioRecord avant `openSession()` ; le lecteur audio et le retour visuel complet sont lancés ensuite dans `OverlayService.startRec()`. Depuis 0.7.2, la recherche de sensibilité est évitée lorsque le cloud n’est pas demandé ; le panneau masqué est préattaché et les états sont rendus immédiatement sur main. Mesurer ces étapes avant de les réorganiser, en conservant les garanties d’annulation et de libération audio.

## 3. Envoyer depuis la pause — après la réactivité

Demande exacte : après une pause et une éventuelle fin de saisie à la main dans l’overlay, glisser la pastille vers le haut doit terminer la dictée et insérer le texte courant dans le champ de l’application destinataire, en le copiant aussi dans le presse-papiers. Ne pas relancer le microphone.

- [x] Implémenté, validation appareil restante : en pause, afficher l’indication « ↑ Envoyer » et utiliser la finalisation existante qui préserve les retouches manuelles.
- [x] Implémenté, validation appareil restante : même action pour un brouillon/note éditable en pause, sans session micro active.
- [x] Implémenté, validation appareil restante : pendant une pause encore en cours de traitement, mémoriser la demande et envoyer une seule fois après arrêt effectif du lecteur audio.
- [ ] Vérifier absence de reprise micro, absence de double insertion, conservation des retouches, focus du champ destinataire et copie effective.
- [x] Conserver le choix de format par glissement vers le haut au repos et préciser le geste pour réafficher le texte pendant l’enregistrement.

Repères : `OverlayService.kt` : gestion ACTION_UP / `exportOpenNote` / `stopRec` / `finishAfterPause`. `injectOrCopy` copie déjà le texte avant d’essayer l’insertion. Ne pas déclencher l’envoi réel d’un message dans l’application destinataire : seule l’insertion dans son champ est demandée.

## 4. Simplifier l’offre de modèles de transcription — à étudier

Ullie est satisfait de Nemotron actuel et propose de conserver un modèle principal, éventuellement adapté à la puissance du téléphone ou de la tablette. La suppression des autres modèles n’est pas décidée : rechercher puis présenter une recommandation étayée avant de réduire le catalogue.

Catalogue actuellement dans `ModelDownloader.kt` :
- Nemotron 3.5 Handy Q8_0, recommandé : fichier GGUF 751 094 240 octets.
- Nemotron 3.5 Compact Q6_K : fichier GGUF 621 356 512 octets.
- Nemotron 3.5 Live ONNX int8 : taille de téléchargement annoncée 453 Mo.
- Parakeet 0.6B v3 ONNX int8 : taille de téléchargement annoncée 465 Mo.

Ne pas confondre taille du téléchargement, taille installée, mémoire de travail et vitesse. Une quantification plus petite ne garantit pas un débit supérieur sur tous les processeurs.

- [ ] Vérifier les variantes actuelles dans les sources officielles, couverture FR/EN, prise en charge par le moteur de l’application et limites matérielles.
- [ ] Comparer la mémoire en pic et le débit réel sur le téléphone de référence et au moins un appareil plus modeste.
- [ ] Examiner une présentation simple : modèle recommandé, variante économique si validée, autres modèles dans des options avancées plutôt qu’un long catalogue au premier lancement.
- [ ] Étudier un court test local de capacité avant recommandation automatique ; ne pas promettre une compatibilité universelle d’après la RAM ou le nom du processeur seulement.

## Contraintes de reprise

- Changements précédents de l’utilisateur à préserver : `CLAUDE.md` et `docs/2026-09-07-voix-personnelle-et-gestes.md`.
- Publication GitHub autorisée le 8 septembre par Ullie : il doit pouvoir télécharger l’APK depuis son téléphone. Publier la version d’essai via Actions et une prerelease avec lien APK direct. Aucune installation sur appareil effectuée par Codex.
- Ullie demande de ne plus reconfirmer chaque opération déjà autorisée ; regrouper les écritures et vérifications soumises aux restrictions techniques du harness.
- Les demandes présentes de l’utilisateur priment sur ce suivi. Mettre à jour ce fichier après chaque étape avec les résultats réels des tests et les limites restantes.
