# Suivi des demandes d’Ullie — DictAI

Mis à jour le 9 septembre 2026. Travail en cours dans Codex sur la branche existante. Procéder par étapes testées, à la demande d’Ullie ; ne pas oublier les demandes suivantes et ne pas les considérer comme déjà livrées.

## Interaction notes et correction — 0.9.5 publiée et vérifiée

Retour d’Ullie : curseur difficile à placer au premier toucher, clavier qui tarde à changer de champ, titre/aperçu confondus et insertions accidentelles après ouverture volontaire d’une note. Implémentation : contexte NOTE persistant, tap pause/reprise, bouton Insérer… avec confirmation ponctuelle, note conservée après insertion, Terminer pour la ranger. Le message reste direct. Correction : acquisition coordonnée du focus/clavier, bouton Modifier, suivi suspendu seulement pendant l’édition volontaire. Liste des notes hiérarchisée et palette sombre plus chaude/arrondie. [Parcours, mécanismes et essai ciblé](NOTES-ET-EDITION-0.9.5.md). Revue indépendante du code et vérification locale terminées. Aucun audit visuel/tactile Android exécuté, faute d’appareil.

Publication vérifiée le 9 septembre 2026 : [APK directe 0.9.5](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34404357683/dictai-local-layout-test.apk), [release](https://github.com/Uhama91/DictAI/releases/tag/gemma-test-34404357683), [GitHub Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34404357683). Source `633d83cfdc6422dead8e4ffb395e6d54487aa96d`. Téléchargement public HTTP 200, **78 865 904 octets**, SHA-256 `ea2e8ee4e751a86329ec3ca7601f7ae3ef74862ac1a3a9d103289a859a89e8a8`. Empreinte identique à l’APK locale, à SHA256SUMS, au digest GitHub et au journal CI ; les **1 048 entrées ZIP** sont identiques. Certificat de mise à jour identique à 0.9.4, version code34 et alignement ELF/ZIP 16 Ko vérifiés. **440 tests JVM / 63 suites**, 18 tests du contrôleur APK et contrat natif réussis ; tests Android compilés localement et en CI, sans exécution sur appareil.

## Direction actuelle : messages et notes visuelles cohabitent sur Android

Ullie conserve la pastille et le fonctionnement actuel ; le portage iOS est écarté pour l’instant. **Précision explicite : le parcours message reste à part entière.** Dicter, voir/corriger le texte dans le panneau DictAI et l’insérer volontairement dans le champ cible doit cohabiter avec la prise de notes ; ne pas imposer une note ou un export à chaque dictée, ni remplacer les gestes actuels par un nouveau sélecteur obligatoire.

DictAI doit aussi permettre de créer rapidement une note en pause, capturer l’écran ou prendre une photo, commenter en dictée directe avec Nemotron ou au clavier, agrandir le panneau, puis récupérer le contexte complet en PDF/HTML pour un agent IA. La tablette et le stylet font partie des usages à travailler ; aucun canevas d’écriture ou de dessin libre n’est encore implémenté ni précisément demandé.

Priorité à la fluidité de cette prise de notes et au lien entre images et commentaires. Le nettoyage local reste utile à ce parcours, avec les attentes réalistes précisées ci-dessous. Les deux exports existent : HTML autonome avec JPEG intégrés, PDF avec texte sélectionnable et images. La lecture visuelle par le destinataire reste distincte de l’ouverture du fichier ; ne pas annoncer de compatibilité universelle ni redemander les validations de stockage/exports déjà reçues. [Direction, fonctionnement vérifié et critères de suite](2026-09-09-orientation-notes-android.md). Documentation uniquement à cette étape ; aucun changement des gestes ou de l’application.

## Priorité utilisateur précisée : nettoyage utile en local

Après les résultats du banc 0.9.4, Ullie précise qu’il n’attend pas une transcription parfaite comparable à celle d’un puissant modèle cloud. **La priorité est le retrait des hésitations/onomatopées parasites et répétitions involontaires, la correction des maladresses et la cohérence des phrases**, en conservant son intention. Un résultat majoritairement propre et agréable à utiliser convient. Les listes et les paragraphes deviennent des améliorations secondaires ; leurs scores ne doivent pas masquer un nettoyage réussi ni déclencher seuls de nouvelles optimisations ou une hausse de latence. Cette précision prévaut sur un classement technique mettant le découpage des listes en première priorité.

## Banc téléphone 0.9.4 reçu — 28 essais complets

Ullie fournit les 14 exemples synthétiques en deux passages, avec résultats identiques entre passages et durées différentes. **26 appels Gemma : 20 acceptés, 6 rejetés ; deux réponses directes à part. Les dix essais de listes sont acceptés lexicalement mais échouent sur les coupures.** Les six rejets concernent deux mails anglais avec signature ajoutée/dupliquée et l’ancien mail français long avec des mots ajoutés/omis. Le texte français retire euh et corrige les répétitions/accords, mais manque son deuxième paragraphe ; le texte anglais et le mail français à trois sujets atteignent leurs nombres de paragraphes ciblés.

Durées appel + validation : **1 292 à 9 135 ms**, médiane 2 224,5 ms ; 6 appels dépassent 5 s, 2 dépassent 8 s, aucun ne dépasse 10 s. Mesure isolée, moteur chargé, hors arrêt ASR et insertion ; pas de conclusion sur le délai complet de dictée. PSS 1 992 → 1 966 Mio, état thermique 0 → 0, sans mesure des pics.

Inspection du code : le banc utilise le moteur et ses contrôles mais **ne passe pas par le nouveau `CorrectedTextPreparation` de l’overlay**. Le retrait de euh dans son exemple ne valide pas à lui seul la préparation et le repli de Texte corrigé en 0.9.4. Le format de l’utilisateur est déjà confirmé ; ne plus le redemander. Les cinq listes du banc donnent désormais des exemples exacts pour le travail de segmentation, dont une phrase d’actions. [Analyse et suites ciblées](2026-09-09-retour-gemma-0.9.4.md), [données complètes](benchmarks/local-format/gemma4-poco-f7-094-user-2026-09-09.json). Cette réception n’ajoute aucune modification applicative ni nouvelle APK.

## Texte corrigé : retrait fiable des hésitations — 0.9.4 publiée et vérifiée

Ullie confirme explicitement qu’il utilisait déjà Texte corrigé ; il ne s’agit pas de lui redemander le format. Le code permettait une sortie avec hésitations et un repli inchangé. Préparation déterministe du texte avant modèle et en repli, protection des segments réellement saisis à la main, anticipation cohérente et compteur dans le diagnostic. Aucun changement de modèle, de thinking, des plafonds d’attente ou des autres formats. [Comportement, cas protégés et vérification](HESITATIONS-TEXTE-CORRIGE-0.9.4.md).

Publication vérifiée le 9 septembre 2026 : [APK directe 0.9.4](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34388786570/dictai-local-layout-test.apk), [GitHub Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34388786570), source `5ee4c5a959694fe4846307a52a2115ea35c1d17d`. Téléchargement public complet HTTP 200, **78 833 036 octets**, SHA-256 `7dad952b7d9f204bf256823126dddbce62855bc55d982b922da021fd63def42c`. Empreinte identique à l’APK locale finale, à SHA256SUMS, au digest GitHub et au journal CI ; les **1 048 entrées ZIP** sont également identiques. Certificat de mise à jour, version code33 / 0.9.4-wp-gemma-test et alignement ZIP/natif 16 Ko vérifiés. **429 tests JVM / 61 suites**, 18 tests du contrôleur APK et contrat natif réussis. Aucune génération GPU Android ni essai terrain de ce correctif effectué par Codex.

## Retour terrain du 9 septembre — mail, liste et captures

Reprise dans une nouvelle conversation Codex à la demande d’Ullie, dans la continuité de la livraison 0.9.3. Retour qualitatif fourni par l’utilisateur ; aucune nouvelle mesure sur appareil effectuée par Codex, aucun diagnostic de routage ni numéro de version installé fourni avec ce message.

- **Mail : rendu satisfaisant sur l’exemple reçu.** « Bonjour, », corps, « Cordialement, » et signature « Monsieur le Testeur. » sont correctement séparés. Ullie confirme que ce mail d’une certaine longueur fonctionne désormais. Ce résultat ne valide pas encore les mails beaucoup plus longs ou à plusieurs sujets.
- **Liste : réactivité jugée satisfaisante et découpage globalement réussi.** Sept puces reçues : « Des oranges », « Des bananes », « Du lait », « Du riz », « Du poivre », « Un ordinateur », « Des couches de l’eau ». La dernière puce regroupe potentiellement deux articles ; huit puces seraient attendues si couches et eau sont deux éléments distincts. L’entrée ASR brute n’est pas fournie.
- **Limite signalée : énumération naturelle.** Ullie constate qu’une formulation plus construite, au lieu d’articles cités successivement, n’est pas transformée en liste. Phrase source exacte, sortie correspondante et format sélectionné restent à préciser. Ne pas conclure qu’un discours télégraphique est une obligation du produit.
- **Capture d’écran :** enregistrement dans le stockage du téléphone et présence dans le presse-papier confirmés par Ullie.
- **Photo caméra :** prise de photo, enregistrement dans le téléphone et présence dans le presse-papier confirmés par Ullie. Son second retour lève le doute initial sur l’enregistrement de la photo et reconfirme celui de la capture d’écran.
- **Notes et exports :** récupération d’une note en PDF et en HTML testée avec succès par Ullie via le bouton de récupération des fichiers. Il a vérifié la lisibilité du fichier et l’a fourni à ChatGPT et à Grok ; les deux ont réussi à le lire. Le retour situe cette lecture après l’essai HTML, sans détailler chaque combinaison format/application ni l’interprétation des images intégrées.
- **Vocabulaire : parcours confirmé sur téléphone.** Ullie a obtenu la proposition de correction du mot « grec avec un K à la fin » (grek) vers « Grok », l’a enregistrée, puis la graphie Grok a été prise en compte dès la deuxième occurrence dictée. Proposition, confirmation et réutilisation sont donc validées dans cet essai ; le geste exact de remplacement n’est pas redétaillé dans ce message.
- **Nouveau problème : hésitations conservées.** Ullie signale des « euh » spontanés encore présents alors qu’il utilise le moteur Local. Le format actif et une éventuelle retouche manuelle dans cette même dictée ont été demandés pour identifier le chemin réellement exécuté.
- **Précisions encore ouvertes :** réouverture ultérieure dans DictAI, placement exact des images et interprétation visuelle par les destinataires. Ne plus classer l’enregistrement de la photo, la récupération PDF/HTML ou le parcours de vocabulaire parmi les essais non réalisés.

Inspection du code de la branche actuelle : `GemmaFormattingPrompt.LIST` demande une puce par élément ou action, même sans virgules. Le contrôle de fidélité (`GemmaFaithfulLayout`, `GemmaConservativeEditing`, `LocalFormattingSession`) peut refuser une reformulation ou une omission et laisser le texte source ; il ne garantit pas la justesse de chaque coupure. Le mode Texte ne sollicite pas de LLM ; le format Liste est un choix explicite. Sans entrée exacte et diagnostic, mauvaise segmentation, rejet de fidélité, délai dépassé et format inadapté restent des hypothèses, pas un diagnostic établi.

La capture d’écran et la caméra passent par `OverlayService.copyCapturedImage` : sauvegarde via `CapturedImageGallery` dans Pictures/DictAI puis copie de l’URI ; une solution de repli permet aussi le presse-papier si la sauvegarde échoue. Voir une image dans le presse-papier seul ne prouve donc pas son enregistrement dans la galerie.

Inspection des hésitations : le prompt `GEMMA_EDITING` demande déjà la suppression de euh/heu/uh/um et le validateur l’autorise. Après une retouche manuelle, `OverlayService.processStoppedRecording` utilise `GEMMA_PROJECTION` : le prompt conserve alors tous les mots et la suppression des hésitations n’est plus une correction autorisée. En format Texte, `LightTextCleanup` est un traitement distinct du LLM, activé seulement si son réglage est actif et sans retouche manuelle. Un rejet du résultat ou un délai dépassé peut aussi conserver la source. Ces chemins sont vérifiés dans le code ; aucun d’eux n’est encore attribué avec certitude à l’essai d’Ullie. La réutilisation automatique d’une règle de vocabulaire n’est pas à confondre avec une retouche manuelle.

Vérification ciblée du 9 septembre : **33 tests JVM réussis, sans échec ni test ignoré**, dans `LightTextCleanupTest` (5), `GemmaConservativeEditingTest` (15) et `GemmaFormattingTest` (13), avec la configuration prototype. Ils vérifient les règles et contrôles existants ; ils ne constituent pas une reproduction de la génération GPU ni du chemin suivi sur le téléphone.

Suite ciblée : identifier le format et les retouches de l’essai avec « euh » ; au besoin lire le diagnostic de cette dictée dans Dernier post-traitement → Dernière dictée. Conserver aussi la demande d’exemple naturel qui échoue en Liste pour distinguer segmentation et rejet de fidélité. Aucune modification du modèle, du prompt, du délai ou du code dans cette reprise.

## Galerie, notes volontaires et vocabulaire lettre par lettre — essai 0.9.3 publié

Nouvelle précision : Ullie efface habituellement lettre par lettre. La suggestion doit suivre le mot source jusqu’au remplacement, y compris AF → CAF et petits groupes. La sauvegarde des captures/photos dans Pictures/DictAI s’ajoute au presse-papier. Les notes avec captures sont rétablies uniquement lors d’une sauvegarde volontaire, avec dix images maximum et exports PDF/HTML autonome. [Fonctionnement, limites et essais](GALERIE-NOTES-VOCABULAIRE-0.9.3.md).

Publication vérifiée le 9 septembre 2026 : [APK directe 0.9.3](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34364610947/dictai-local-layout-test.apk), [GitHub Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34364610947), source `38d88b3ff6d3e93f7b2fa88115b405449f34000a`. Téléchargement public complet HTTP 200, **78 816 652 octets**, SHA-256 `984ecf4c694c5ce73eac95323744c68ace08fa57360fa1244c53c6c3a67bd3ef`. Empreinte identique à l’APK locale finale, à SHA256SUMS, au digest GitHub et au journal CI ; les **1 048 entrées ZIP** sont également identiques. Signature de mise à jour, version code32 / 0.9.3-wp-gemma-test, ABI ARM64 et alignement ZIP/natif 16 Ko vérifiés. **418 tests JVM / 60 suites**, 18 tests du contrôleur d’alignement et contrat natif réussis. APK et tests Android compilés ; tests Android non exécutés, aucun appareil connecté à Codex. Les gestes Gboard, l’overlay, MediaStore/galerie et le rendu PDF restent à vérifier sur téléphone.

## Presse-papier et vocabulaire — essai 0.9.2 publié

Nouvelle priorité explicite : abandonner le collage automatique et les nouvelles notes visuelles. Copier une capture/photo à la fois ; Gboard est le clavier de référence choisi par Ullie. Il colle lui-même chaque image puis termine la dictée pour insérer le texte. La caméra interne est confirmée fonctionnelle par son retour. Les anciennes notes restent conservées.

La suggestion de vocabulaire dans l’overlay est renforcée pour les remplacements via Gboard et les révisions ASR pendant la frappe. Validation explicite, proposition temporaire, corrections locales déterministes pendant le streaming, aucun appel LLM ajouté. [Implémentation, limites et protocole](PRESSE-PAPIER-VOCABULAIRE-0.9.2.md).

Publication vérifiée : [APK directe 0.9.2](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34360582995/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34360582995), source `25ceeb6cc2f2fd6f4de8707552cdd6c4412514fe`. Téléchargement public intégral HTTP 200 : **78 800 272 octets**, SHA-256 `798494dff118469e97a3c4d821053647f5e4e94e7844277392f6c62984c4ea89`. Empreinte concordante avec SHA256SUMS, le digest GitHub et le journal CI. Les **1 048 entrées internes** de l’APK publique sont identiques à celles de la construction locale finale. Signature de mise à jour, version 31 / 0.9.2-wp-gemma-test, ABI ARM64 et alignement natif/ZIP 16 Ko contrôlés. **408 tests JVM / 59 suites**, 18 tests du contrôleur d’alignement et contrat natif réussis ; tests Android compilés, non exécutés. Aucun appareil Android connecté ; la réception réelle depuis Gboard, l’overlay avec le clavier et la caméra restent à vérifier sur téléphone.

## Collage dans la conversation et viseur interne — essai 0.9.1 publié

Retour utilisateur après 0.9.0 : application **ChatGPT → Remote**, puis autres applications Android (Messenger, WhatsApp, Instagram, Claude…). Le menu de partage automatique est rejeté ; la capture doit tenter une pièce jointe dans la conversation déjà ouverte. La caméra externe fait disparaître la pastille. La correction locale reste satisfaisante sur texte court ; coût plus marqué sur texte long, sans nouvelle demande de modification du moteur.

0.9.1 retire la redirection automatique vers l’export, utilise un viseur Camera2 interne et conserve la pastille. Chaque capture tente un collage image ciblé, avec texte vide de repli pour ne pas introduire d’URI dans un éditeur texte. Le champ et la fenêtre sont revérifiés, les sélections actives protégées ; aucune activation de bouton Envoyer. Diagnostic séparé pour l’action de collage et la lecture du fichier par l’application identifiée. L’acceptation réelle par ChatGPT Remote et les messageries nécessite le téléphone ; elle n’est pas promise sur la seule base de l’API Android. [Détails et vérification](INSERTION-IMAGES-0.9.1.md).

Publication vérifiée : [APK directe 0.9.1](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34355763495/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34355763495), source `be1c92c6b4149f4c64f0fe1c642d41daed65faea`. Téléchargement public intégral HTTP 200 : **78 800 260 octets**, SHA-256 `5e7dd945ea30274d67cb9b9e8a07c175ca6c5bb6d199f2e890db5cccbd291583`. Empreintes concordantes avec SHA256SUMS, l’asset GitHub et le journal CI. Signature, version 30 / 0.9.1-wp-gemma-test, ABI ARM64, permission caméra et alignement ZIP/natif 16 Ko vérifiés sur ce téléchargement. Les **1 048 entrées internes** de l’APK publique sont identiques octet pour octet à celles de l’APK testée localement ; la représentation du conteneur diffère. 396 tests JVM / 59 suites réussis ; tests Android contrôlés compilés mais non exécutés. Aucun appareil connecté.

## Notes visuelles et exports — essai 0.9.0 publié

Demande et précisions du 9 septembre : captures sous l’overlay, photos caméra, dix images maximum, références au moment du geste, transfert groupé vers une conversation. PDF réservé à la lecture personnelle ; HTML autonome avec images intégrées pour l’archive. Ne pas présenter ces documents comme une solution de collage universel. Le partage multiple Android dépend des applications destinataires ; aucun test réel de réception mobile n’est encore effectué. Les images ne doivent pas être envoyées à Gemma ou au moteur cloud de correction.

Implémentation et limites : [notes avec images](NOTES-AVEC-IMAGES.md). [APK directe 0.9.0](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34350930791/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34350930791), source `17fe5cfb1c0e4ee8b8eab012f458c022c509295e`. 391 tests JVM / 58 suites réussis, tests Android compilés, HTML autonome vérifié visuellement. Téléchargement public complet, signature, empreinte, alignement 16 Ko et identité des 1 048 fichiers internes avec la construction locale vérifiés. Les essais sur le téléphone restent à faire ; aucun modèle ni délai de correction modifié.

## Correction souple, dix secondes et choix explicite du texte — 0.8.5

Demande actuelle : autoriser les corrections de forme et les répétitions inutiles sans changer le contenu, structurer aussi le corps des mails longs, et essayer dix secondes de finalisation locale. **Précision ultérieure prioritaire : ne pas activer le LLM automatiquement selon la longueur du mode Texte.** Une note longue doit pouvoir être copiée sans passer dans le modèle.

Version 0.8.5 publiée et vérifiée : deux formats distincts dans le menu du geste vers le haut, **Texte** (ID existant cleanup, sans LLM local ou cloud) et **Texte corrigé** (ID corrected, moteur choisi dans les réglages). La sélection existante est conservée ; Texte reste le choix initial. Les règles rapides de nombres/vocabulaire et le nettoyage léger facultatif restent disponibles sans modèle. L’export direct d’une note ouverte conserve son contenu sans appel au LLM.

Les mails et textes corrigés d’au moins 60 mots ont un maximum de 10 s ; mails courts 8 s, listes/textes corrigés courts 5 s. Le délai n’est pas une attente minimale. Le modèle reçoit une consigne de correction limitée et de structuration du corps par idées. Aucun remplacement de modèle ni thinking activé.

Le contrôle de fidélité accepte des corrections de forme reconnues, des hésitations non citées et des répétitions adjacentes ; il garde les noms/vocabulaire, nombres, négations, citations et signes techniques. Il ne valide pas toute reformulation sémantique. Le brut du dernier mail du téléphone est accepté avec locale → local. La conservation des noms et des éléments protégés reste vérifiée. Quelques équivalences de petits cardinaux peuvent être remises dans la présentation source (2 → deux → 2), sans changer leur valeur. Les textes corrigés retouchés manuellement gardent le contrôle strict de disposition pour protéger les retouches.

Banc élargi : quatorze sources × deux passages, nouveaux textes FR/EN avec corrections et mail à plusieurs sujets, compte des paragraphes du corps, comparaison à 10 s. Le comptage de paragraphes n’est pas un jugement de leur pertinence. Les premiers essais CPU ont révélé des reformulations et conversions numériques indésirables ; les résultats définitifs et les limites sont consignés dans [la vérification 0.8.5](VERIFICATION-GEMMA-0.8.5.md). Aucun appareil Android connecté à Codex.

Vérification locale terminée : 378 tests JVM / 56 suites réussis, APK signée et alignée 16 Ko, contrat du paquet vérifié. Évaluation finale exploratoire : cinq sorties acceptées sur six, textes FR/EN en deux paragraphes et mail à trois sujets en trois paragraphes ; signature anglaise inventée toujours rejetée, liste et ancien mail encore peu subdivisés. [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34345121225), [APK directe 0.8.5](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34345121225/dictai-local-layout-test.apk), source `dbe60a0d862bcbeab0449e05efb9647b35a48cdd`. Téléchargement public HTTP 200 complet vérifié : 78 633 365 octets, SHA-256 `f63c8cc3e09ce2156caabe858dbddfdeb3babd52cc8592021a8ec101be172780`. Signature, version 28/0.8.5 et alignement natif/ZIP vérifiés sur l’APK publiée. Tous les fichiers internes du ZIP sont identiques à ceux de l’APK testée localement, malgré une empreinte globale différente du conteneur. Ne pas promettre une vitesse GPU à partir des générations CPU.

## Rapport GPU 0.8.4 reçu — durée réelle et rejet distinct

Rapport complet reçu en deux messages : **22 cas, onze sources × deux passages**. Les lignes répétées dans la suite sont dédupliquées. [Données archivées](benchmarks/local-format/gemma4-poco-f7-084-user-2026-09-09-partial.json) (nom initial conservé ; contenu désormais complet).

| Mail | Passage 1, appel + validation | Passage 2, appel + validation | Résultat aux deux passages |
|---|---:|---:|---|
| Ancien long | 5 836 ms | 5 250 ms | Accepté, regroupement réussi, normalement rétabli |
| Dernier, 144 mots | 9 233 ms | 8 906 ms | Rejeté, même substitution locale → local |

L’ancien mail dépasse 5 s de 250 à 836 ms. Le dernier dépasse 8 s de 906 à 1 233 ms, mais le banc l’a laissé terminer jusqu’au bout. Ces durées isolées excluent arrêt ASR, affichage et insertion ; une génération anticipée peut réduire l’attente réelle.

Rejet exact reproduit avec les classes de l’APK 0.8.4 : une seule substitution lexicale, **locale → local**, dans en locale. Les deux bruts GPU sont identiques. Le modèle a corrigé une forme et structuré le mail ; la vérification lexicale actuelle le rejette. Rétablir manuellement locale dans la sortie brute suffit à la faire accepter. C’est une expérience diagnostique, aucun correctif de validation intégré ni nouvelle APK générée à la réception de ces mesures.

Conclusion : 8 s laisse une marge pour l’ancien mail dans les deux essais, mais reste insuffisant pour le dernier si le calcul doit être attendu en entier. Allonger le délai seul ne lèverait pas le rejet orthographique. Conserver séparément les travaux restant sur tolérance aux corrections de forme avec protection du contenu, durée acceptable des mails longs, regroupement des listes et rejets anglais. Aucun besoin de redemander la suite du rapport ni de changer le modèle ou le thinking pour interpréter ces mesures.

Sur vingt appels Gemma : douze réponses acceptées, dont huit réussissent les critères ciblés et quatre gardent un mauvais regroupement ; huit rejetées. Les deux acquiescements directs ne mesurent pas le LLM. PSS 1 791 → 1 945 Mio, RAM disponible 2 371 → 2 734 Mio, état thermique Android 0 aux deux relevés ; ces points ne décrivent pas l’état thermique pendant tout le banc.

## Mesurer les mails au-delà de cinq secondes — 0.8.4

Demande actuelle d’Ullie : laisser Gemma terminer les mails sur téléphone au-delà de 5 s avant de conclure à une latence trop forte. Le banc précédent a déjà donné 5 136 ms, soit seulement 136 ms au-delà du plafond. Cette demande prime sur l’ancienne consigne de ne pas modifier le délai sur la seule base d’un bloc de texte.

Mesures hôte retrouvées : 15 399 ms pour l’ancien mail long, 7 281 ms pour le court, CPU deux threads. Nouvelle génération complète du dernier mail (144 mots) : 24 923 ms, premier fragment 6 457 ms, tous les mots conservés et sortie acceptée. Thinking off, MTP désactivé sur CPU ; aucune prédiction de latence téléphone à partir de ce temps. [Mesure brute](benchmarks/local-format/gemma4-latest-long-mail-host-duration-2026-09-09.json).

Implémentation 0.8.4 publiée :
- Les mails de 60 mots ou plus retournent à Gemma, même avec une salutation et une signature explicites. Pas de raccourci direct pour les deux mails longs de l’utilisateur.
- Attente finale Mail portée à 8 s, à titre de marge d’essai. Résultat utilisé dès qu’il est prêt et validé, sans attente minimale imposée. Liste : 5 s. Le diagnostic enregistre la limite réellement utilisée.
- Menu **Mesurer les mails longs avec Gemma** : deux mails × deux passages, jusqu’à 20 s par appel, sans limite de 5/8 s dans ce banc. Initialisation mesurée séparément ; attente avant natif, premier fragment, retour moteur, validation, écarts à 5/8 s. Un essai interrompu ne prétend pas connaître sa durée totale.
- Modèle, prompt, GPU/MTP et thinking off conservés. Le corps des mails n’est pas réécrit par les règles rapides lors de ces essais. Les autres correctifs 0.8.3 sont conservés.

Les tests de finalisation comprennent un moteur simulé terminant après 5,15 s. Les mesures GPU Android ont depuis été fournies par Ullie (ci-dessus) ; la limite définitive reste à décider. Aucun appareil connecté à Codex. 361 tests JVM dans 54 suites réussis, APK compilée/signée/alignée 16 Ko et contrat du paquet vérifié. [Vérification](VERIFICATION-GEMMA-0.8.4.md). [APK 0.8.4 publiée](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34335478012/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34335478012), source `1464d52b07510b3b84f48c03fa0274b090d4afd8`. Téléchargement public complet vérifié, 78 600 597 octets ; SHA identique à l’APK locale et à l’asset GitHub. Les deux passages longs et le relevé final sont désormais reçus et archivés ci-dessus. Aucune nouvelle recherche de modèle nécessaire pour interpréter ces mesures.

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
