# Mode Réunion — preuves et avancement

Suivi rédigé par Astra, agent principal. Spécification et plan : `docs/superpowers/{specs,plans}/2026-09-24-meeting-mode.md`. Ce fichier décrit les résultats observés ; les objectifs du plan ne sont pas des résultats de tests.

## État de la vérification intégrée — 25 septembre

Les variantes normale et Réunion passent chacune **974 tests, sans échec, erreur ni test ignoré**, répartis dans 127 rapports XML. Les deux APK et l’APK des tests Android ont été assemblés sous JDK 21. Les premiers échecs de fixtures et leurs corrections sont conservés dans la chronologie ci-dessous.

L’APK isolé contrôlé sur l’émulateur porte le SHA-256 `c8f20dc3e07b00e5223f6b3636645657b0e813ebd4e9a6f78f89eaf312004670`. Les trois parcours Android intégrés réussissent : menus d’images en **9,211 secondes**, service avec Nemotron, JNI et AudioRecord réels en **53,02 secondes**, puis gestes et retouches en **136,511 secondes**. Les captures natives figurent dans l’artefact intégré. Cette section décrit le contrôle local préalable à la construction GitHub Actions ; le téléchargement livré doit provenir du run CI terminé et vérifié.

Les résultats ci-dessous valident des frontières différentes : tests de domaine, vrais gestes avec événements de voix simulés, moteur JNI avec audio synthétique, puis capture AudioRecord réelle sur émulateur. Aucun de ces résultats ne démontre encore la qualité de huit voix humaines, les performances du Poco F7 ou une réunion de trente minutes. La livraison GitHub Actions est donc un **APK expérimental de test**, séparé de l’installation DictAI habituelle.

## Référence T0

Worktree : `/Users/ulliemaillot/.codex/worktrees/dictai-meeting-mode/phone-whisper`, HEAD initial `26cb80d`.

- Android Studio JBR OpenJDK 21.0.6 ; Gradle 8.9 ; SDK local ; NDK 28.1.13356709 disponible.
- `:app:testDebugUnitTest :app:assembleDebug --max-workers=4` : compilation et tests réussis, 543 tests, aucun échec, erreur ou test ignoré. Journal : `app/build/reports/meeting/t0-baseline.log`.
- Les empreintes des neuf bibliothèques JNI existantes figurent dans ce même journal.
- ADB : émulateur `emulator-5554`, arm64-v8a. Aucune mesure de cette voie Réunion sur Poco F7 physique.

## Profils et projection T2

Implémentation Luna ; revue des trois classes par Astra : `MeetingModels`, `MeetingParticipants`, `MeetingProjection`.

- Tests ciblés : 7 tests de profils et 7 tests de projection réussis, soit 14/14.
- Cas couverts : ordre de découverte, huit canaux, identités de session, homonymes, renommage, filtre réversible, attribution manuelle prioritaire, texte non attribué visible, retouche vide et noms Unicode.
- La projection exclut les étiquettes sans texte après une suppression ; la structure de données conserve les ancres nécessaires à l’édition.
- Aucun branchement de ces classes à l’interface ou au micro à ce stade. Ces tests ne prouvent pas une reconnaissance vocale réelle.

La suite complète exécutée après T2 a compté 557 tests avec un échec dans le test existant `CloudCleanupTest`, puis la relance ciblée de cette classe a réussi. Le premier résultat ne doit pas être présenté comme vert. La substitution Base64 `xw` → `xx` peut préserver l’octet final `0xC7` : le test ne garantissait pas une modification des données chiffrées.

Ce test a été corrigé en modifiant un bit d’un octet décodé avant réencodage. La reproduction déterministe utilise une fixture publique de 28 octets ; le code de chiffrement n’a pas changé. Les 14 tests de `CloudCleanupTest` passent sous JDK 21. Deux revues Astra favorables sur le même fichier figé : conformité/authentification, puis mutation garantie et absence de régression de production. SHA-256 du fichier : `31d38ff22b42b8ce960c642b6e7af8f32b93907f764807ab42f4d40120535ad8`. La vérification commune T0b ci-dessous a ensuite réussi avec 665 tests.

## Sonde native T1

- Runtime NVIDIA et modèles téléchargés aux révisions prévues ; les deux empreintes de modèles ont été vérifiées par le worker natif.
- Le contrôle doit exiger un résultat attribué avant `finish` **et avant que tout l’audio soit fourni**, afin d’exclure un traitement à la fin du fichier.
- La sonde est compilée pour AArch64 ; les quatre segments ELF LOAD sont alignés au moins à 16 Ko. Les témoins AMI et français synthétique décrits ci-dessous valident le mécanisme. Les deux revues Astra sont favorables ; le pont JNI applicatif peut être développé.

La compilation Android de SentencePiece a réussi après ajout du réglage CMake `CMAKE_POLICY_VERSION_MINIMUM=3.5`. La détection de cette archive dans NeMo utilise le préfixe de dépendances parent et des chemins Android explicites ; la sonde lie aussi la bibliothèque système `log`. Il s’agit de corrections du build, sans modification des algorithmes de reconnaissance.

Première tentative interrompue sur l’AVD configuré avec un cœur et 2 Go de RAM : aucune conclusion exploitable sur le direct. L’AVD a ensuite été relancé sans effacement de données, avec quatre cœurs et 4 Go, API 36, arm64-v8a. Le témoin AMI dure exactement 60 secondes, mono 16 kHz, 960 000 échantillons.

| Passage, dans l’ordre d’exécution | Durée murale du flux | Calcul push + next + finish | Retard d’entrée maximal |
| --- | ---: | ---: | ---: |
| ASR seul, cadence réelle | 66 449 ms | 65 928 ms | 36 014 ms |
| ASR + diarisation, cadence réelle | 61 040 ms | 45 230 ms | 1 085 ms |
| ASR + diarisation, sans cadence | 47 205 ms | 46 818 ms | Sans objet |

L’échauffement a pris 60 993 ms pour le premier passage ASR et 10 066 ms pour le passage avec diarisation. Ces essais successifs ne permettent pas d’isoler le surcoût de la diarisation ni de conclure qu’elle accélère l’ASR. Les temps de chargement seuls étaient de 120 et 277 ms ; ils excluent l’échauffement.

Preuve du flux attribué : premier mot avec tag positif à 1 823 ms, pour 1 760 ms d’audio fourni ; sa fin audio est à 1 520 ms et son attribution est encore provisoire. Capacité lue dans le modèle : huit voix. Les tags 1, 2 et 3 apparaissent avant EOF ; la séquence de finales stables est `1,2,1,3`, avec retour à la première voix. Le contrôle automatique réussit pour les deux cadences. Cela vérifie le mécanisme ; l’exactitude de chaque attribution et la qualité humaine française ne sont pas établies. Journal : `app/build/reports/meeting/native/probe-emulator.log` dans le worktree.

Le témoin français synthétique dure 12,78025 secondes : Amélie, Thomas, Jacques, puis Amélie, avec des pauses d’une seconde et trois secondes de silence final. Son SHA-256 est `196439ff592c6e6a794c1914804dd996c428f378ab7dd0ff372bfb55baa521d9`. Dans le même processus, le binaire final charge les neuf bibliothèques natives existantes sans modification, puis exécute les nouveaux modèles. Les deux cadences produisent trois tags avant EOF et la séquence stable `1,2,3,1`. En cadence réelle avec diarisation : 13 308 ms de durée murale, 5 558 ms de calcul et 160 ms de retard d’entrée maximal, après 7 630 ms d’échauffement. Ce témoin synthétique ne mesure pas la qualité d’attribution des voix humaines.

Revue complexe T1 : première passe Astra sur la conformité du flux, la capacité effective et les preuves ; seconde passe sur le build, les symboles privés, les dépendances, les contrôles adverses et la préservation des bibliothèques existantes. Les deux portent sur le même gel : `meeting_probe.cpp` SHA-256 `149733df07aba9f8fbfc4b1479623d5b09530ee4db9fbf0f87fcfa727dca39ca` ; binaire `5d4ef97d7c8677a97a9f118b59c8deabba84538525d88daddaa11067790925bf`. Binaire de 49 398 928 octets ; seuls `liblog`, `libdl`, `libm` et `libc` sont requis dynamiquement. Aucun APK Réunion n’a été installé. L’émulateur a été arrêté après cette preuve.

## Codec T5a

- T5a : le premier lot de cinq tests du codec JSON a réussi. Il couvre les champs structurés, la distinction entre absence de retouche et retouche vide, les versions futures conservées telles quelles et les invariants du document.
- Revue T5a Astra approuvée après correction : le test a reproduit l’acceptation erronée d’un suffixe parasite, puis les cinq tests ont réussi avec une vérification de fin d’entrée avant toute lecture de version. SHA-256 du codec : `ead80beb275751337a92219e264a55d81d0019dc292089f13027c2863eb2d9e9` ; tests : `ab06e3f598ae7d7fffa1639532bcffa69fbd3bac6fc84bf806b73d1521c9f1a4`.
- Ces classes ne sont pas encore reliées au stockage des notes ni à l’interface.

## File audio T4a

La file PCM conserve au maximum 320 000 octets par défaut, soit dix secondes à 16 kHz en PCM16 mono. Elle copie les blocs acceptés, rejette explicitement une saturation, draine les blocs à la fin d’entrée et les libère à l’annulation. Aucun enregistrement complet de la réunion n’est accumulé dans cette file.

Les sept tests ciblés réussissent, sans échec ni erreur. Ils couvrent copie, ordre, saturation, fermeture, annulation et réveil du consommateur. Les attentes sont bornées et les threads de test sont nettoyés même si une assertion échoue. Revue moyenne Astra approuvée sur les empreintes SHA-256 suivantes : source `cddefe8fcd0013cdffa868ef28d627c3045a2e2634918c5608692db0c6ea0dab`, tests `ad7671bfe5797a8717e3ad3a85f685311121d557643828c04ac824c1ba45316f`. Le raccordement au micro reste à réaliser.

## T0b — Intégration de la base médias

Les 35 fichiers de photos/captures du checkout principal (17 suivis modifiés et 18 nouveaux) ont été copiés exactement dans le worktree après vérification : chaque destination suivie correspondait encore à HEAD et chaque nouveau chemin était absent. Le manifeste `app/build/reports/meeting/t0b-media-manifest.txt` fige les chemins et SHA-256 ; sa propre empreinte est `2ce972ed9e21cae21553659dc05b36d55687e729a5c54af36dd7c25958665674`. Les 35 empreintes source/destination sont restées identiques après le build. Le checkout principal n’a reçu aucun remplacement de code.

Commande sous JDK 21.0.6 : `./gradlew :app:testDebugUnitTest :app:assembleDebug --max-workers=4`. Résultat : 100 suites, 665 tests, zéro échec/erreur/test ignoré et build réussi. Astra a relu les résultats XML et l’artefact : `app/build/outputs/apk/debug/app-debug.apk`, 88 176 170 octets, SHA-256 `5c8e11ca9667c4dcdc7444febe94ffb5c764726807218f190dccac79556b4297`. Revue moyenne de l’import approuvée. Cet APK de référence n’expose pas encore le mode Réunion ; il ne constitue pas la livraison de la fonction.

## T3 — Revue finale des retouches et attributions

Le gel final passe 33 tests : 28 pour le reducer et 5 pour les ancres. Les régressions reproduites puis corrigées couvrent notamment les frontières de voix révisées, le passage d’un texte sans timestamps à des mots horodatés, les IDs identiques en temps audio, l’ordre des énoncés et les fins de mots non monotones. Les deux derniers cas vérifient qu’une hypothèse ambiguë ne duplique pas le texte dans un tour non retouché et n’élargit pas une attribution manuelle ignorée aux paroles de l’autre personne. Le brut est conservé pour consultation lorsque l’alignement est incertain.

Astra a effectué deux revues distinctes du même gel : conformité fonctionnelle et conservation des données, puis cas adverses, limites et maintenabilité. Décision : T3 approuvé ; le raccordement au clavier réel et la récupération en note relèvent encore de T6/T7. SHA-256 : reducer `65896b6d7b8cd9b38cd5659e68cdfd377e356af4e7457f1c1713a542ab0517a2`, ancre `e22fdf8c9ff8376337df6b7be367a6df97f9a275ade76c98bddf7a7f0a3acfc8`, tests reducer `d16e55073ed4b1c5f0c3fb9b4ddf8be4109acdf56b8d6068e1b25c801012f5b5`, tests ancre `f159b0650d59e52d2ea1ffbfec9c968b995e99605857adbf9ab793325d2c7d33`.

## T5b — Brouillon atomique

Neuf tests passent, dont deux régressions d’abord reproduites : un `finishWrite` qui ne publie pas le nouveau fichier sans lever d’exception, et une première écriture interrompue ne laissant qu’un `.new`. Le stockage vérifie le fsync et la publication, restaure le contenu antérieur en cas d’échec, conserve les versions inconnues et ne rapporte pas un faux succès. Revue moyenne Astra approuvée sur les SHA-256 : source `4956cbece2f4d905bc9026a36fa0bc7099e3ca3d190c124617eee1974e8bd73e`, tests `e2e45b705b50db09ca815e5c0966796be17d6a78589fd862c0aa9e62313cda8a`. Le regroupement des écritures et la liaison aux notes restent à intégrer.

## T4b — Pont JNI applicatif

Le gel du pont passe les cinq tests JVM de façade et les tests C++ hôte. Le test du constructeur public confirme l’absence de chargement natif à la seule création de l’objet. Un cas adverse de finales dont les horodatages se chevauchent a d’abord échoué : la ponctuation tardive pouvait viser une ancienne finale en attente. La correction choisit l’utterance la plus récente et préserve cette identité quel que soit l’ordre de stabilisation.

Astra a effectué deux revues sur le gel `11049f9f5a2ba73885827ddc116f341e97877ff0c07b9111d482498c3bd40d84` du manifeste `app/build/reports/meeting/native/t4b-freeze-manifest.txt` : conformité du streaming et sécurité JNI, puis cas adverses, ownership, isolation des dépendances et licences. Les 30 empreintes du manifeste correspondent aux fichiers relus. Les neuf bibliothèques existantes conservent leurs empreintes T1. Décision : T4b approuvé pour l’instrumentation applicative isolée ; les tests JVM/hôte ne constituent pas encore une preuve d’appel des modèles depuis l’APK.

Le binaire `libdictai_meeting.so` mesure 2 793 224 octets, SHA-256 `5ac52ca8137d34db0207ab30af171e6291a5ba21e2c0b56966d65f2bfc35da27`. Le rapport ELF confirme arm64, quatre segments LOAD à 16 Kio, et uniquement les bibliothèques système `log`, `dl`, `m`, `c` en dépendances dynamiques. Seul `JNI_OnLoad` est exporté. Les notices de code sont incluses ; GGML conserve sa licence MIT, distincte des licences Apache des autres composants et de la licence OpenMDW-1.1 des deux modèles téléchargés.

## T4c — Session audio sur un worker propriétaire

Le lot rapporte 21 tests ciblés réussis : neuf pour le moteur, sept pour la file audio et cinq pour la façade native. Le rouge initial a reproduit les huit comportements absents du squelette. Une seconde régression a reproduit l’annulation bloquée par un callback lent et l’exposition d’un message natif dans la complétion de fermeture. Ces deux défauts sont corrigés : les callbacks sont admis sous verrou puis exécutés hors verrou, et la fermeture expose un message constant sans cause native.

Astra a réalisé deux passes sur les mêmes sources figées : contrats de chargement, FIFO, fin, annulation et erreurs ; puis courses entre threads, ordre de libération, admission d’une session suivante et isolation des callbacks. Décision : T4c approuvé. SHA-256 du moteur `040f6d55882778119a0689f74984da5914069ca9cb0d58940977e3739dce6828`, tests `b6cfb2ab3c9d9830f021eb7564cd1a7b87abec2248cedc9ae9038346a4722707`. Le contrôleur applicatif doit encore revérifier runId après toute publication sur le thread principal ; les retours déjà admis peuvent finir après une annulation.

## T5c — Notes structurées et projection des exports

Le gel final passe 26 tests dans quatre classes (8 notes, 8 stockage Android, 9 projection, 1 export texte/HTML), sans échec, erreur ni test ignoré. Les XML archivés dans `app/build/reports/meeting/t5c/green-commit-rollback/` ont été relus par Astra. Les tests supplémentaires traversent la vraie méthode de stockage Android avec des préférences simulant un commit qui modifie la mémoire puis échoue : ancienne chaîne restaurée, première écriture retirée, échec de restauration signalé et chemin `apply` des notes plates conservé. Ils vérifient une branche existante ; ils ne sont pas présentés comme une nouvelle régression rouge.

Deux passes Astra sur ce même gel : conformité des identités, titres, noms, filtres, images et versions inconnues ; puis erreurs de stockage, cache, suppression de dossier partiellement réussie et absence de modification du rendu média hors projection. Décision : T5c approuvé. Les exports texte et HTML sont exercés ; le PDF appelle la même normalisation et sera contrôlé visuellement lors de la validation intégrée. Le regroupement des écritures, la réouverture dans le panneau et le micro relèvent encore de T5d/T6/T7.

Empreintes principales SHA-256 : `TranscriptNotes.kt` = `c117f99026a494add2ea982921785af5f6de65f2d7b21ca289e186825370916c` ; stockage Android = `e3b4373b91119d95713abaebb05a1baad50f56686c89c69939cd57fe1f6bd2ef` ; projection = `19bfa8b811cc83448e8397418df74f183c125a83c417f9559bbff3a7bbcfd65f` ; test du stockage = `5170d829d03ec20821dddf02e4bdf66ed4c734ac469e0b42013ea08bb88c3dcb`. Le warning Kotlin sur la branche `null, JSONObject.NULL` est conservé : la branche `else` existe et les huit tests du stockage passent ; aucune correction cosmétique n’a été introduite dans ce gel.

## T4e — APK isolé et JNI exécuté dans l’application

Les deux builds réussissent : variante normale, puis `-PmeetingPrototype=true` avec `assembleDebugAndroidTest`. Astra a relu la configuration, le test d’identité et le test instrumenté, puis vérifié les copies d’APK et leurs empreintes. La variante normale garde `com.uhama.whisperpin`, version 35 / `0.9.6-dictai`, libellé DictAI. Le prototype porte `com.uhama.whisperpin.meetingtest`, version 35 / `0.9.6-dictai-meeting-test`, libellé « DictAI Réunion — test » et provider `com.uhama.whisperpin.meetingtest.note_files`. Les poids restent hors APK. Le test instrumenté charge les anciens runtimes puis le pont Réunion et utilise le témoin français synthétique ; sa compilation puis son exécution sur l’AVD ont réussi.

Copies dans `app/build/reports/meeting/native/t4e/artifacts/` : APK normal, 88 177 142 octets, SHA-256 `b59e25c6a2ae7ad13c6878bd87a97356f2136b705928dd347017fbc993ae3044` ; prototype, 84 023 002 octets, `6591404208a919ced976f4765d0d3c07bba4394831bd859a608d978405d07a48` ; androidTest, 1 391 942 octets, `2da1907ca5f3c8e614e10717a176ea89e628ef3417e54fcb7c00a23c18b19d6c`. Test instrumenté figé : `e2088d985740a88b4e0fbee7ea87d3a845622a1fa8e563d43cd8b08d22aebda1`. Les journaux conservent aussi une première erreur de compilation du helper SHA, corrigée avant le build réussi.

La première tentative d’AVD API 36 arm64, avec quatre cœurs et 4096 Mio sans effacement, est restée environ trois minutes trente avant l’ouverture des ports ADB. Aucun appareil n’est apparu dans `adb devices` pendant cette tentative ; aucun APK n’y a été installé. Le processus a été arrêté proprement. Le rapport `t4e-avd-attempt.txt` conserve les observations, sans cause établie.

La relance avec `-no-window -gpu swiftshader_indirect` a démarré. Seuls le prototype isolé et son APK de test ont été installés, avec succès. Le runner réel passe **6 tests en 28,675 secondes**. Les deux modèles et le témoin sont vérifiés par taille et empreinte dans le test, puis le pont Kotlin/JNI exécute deux sessions distinctes dans le même processus que les neuf bibliothèques natives existantes. La première distingue trois tags et obtient la séquence stable `1,2,3,1` après 12 640 ms d’audio fourni, avant les 12 780 ms du témoin et avant `finish`. Les accents français sont préservés, les horodatages restent globaux, le nouveau handle est distinct et ses IDs d’utterance repartent à un.

Le flux de cet essai est fourni sans cadence réelle : 5 037 ms de durée murale de flux et 8 893 ms de préparation pour le premier cycle. Ces valeurs valident le mécanisme et ne mesurent pas la latence d’une réunion humaine. Les tests contrôlent aussi l’identité du package/provider, les handles invalides, l’idempotence de fermeture, les chemins absents et le PCM mal formé. Journal runner SHA-256 `0a4181edfa831b29c7da891d32d570bf11eabff3dcda313d9c216ea66ff19927` ; trace applicative `209e3f0c479832421ea84016f0bb99bcdd447ab4f6b7762d700f8ee4dd457120`, sous `app/build/reports/meeting/native/t4e/`.

Deux passes Astra sur les APK et le test figés : conformité de l’appel réel et des assertions ; puis isolation d’installation, empreintes, coexistence des runtimes, renouvellement de session et portée des mesures. Les empreintes d’APK et du test correspondent au gel ci-dessus. Décision : T4e approuvé. Ces APK intermédiaires n’exposent pas encore le parcours Réunion complet et ne constituent pas sa livraison.

## T4d — Téléchargement et vérification du paquet de modèles

Le catalogue et le stockage passent 13 tests ciblés : deux pour les métadonnées et chemins, onze pour les transferts et la concurrence. Les XML et le journal sont archivés dans `app/build/reports/meeting/t4d/green/` ; Astra les a relus. Le rouge comportemental du squelette compte douze tests, dont les dix tests de stockage en échec. Une erreur de compilation initiale est conservée séparément ; le test supplémentaire d’annulation après renommage a été ajouté ensuite et vérifié au vert, sans prétendre disposer d’un rouge propre à ce cas.

Le paquet utilise les deux tailles et SHA-256 figés. Les fichiers sont reçus dans un temporaire propre à l’opération, vérifiés et synchronisés, puis publiés ensemble par renommage atomique. Une inspection ultérieure recalcule les deux empreintes. Troncature, mauvais hash, fichier manquant, réponse trop grande, manque de place et erreur de publication ne rendent pas les modèles prêts. L’annulation ferme l’appel réseau, supprime les progressions tardives et publie son état terminal après le nettoyage temporaire. Si le paquet complet avait déjà été renommé, il reste récupérable par une inspection ultérieure. L’ancien paquet n’est pas supprimé par un échec du nouveau.

Deux passes Astra sur le même gel : conformité, intégrité des artefacts et absence de credentials ; puis courses entre commandes, callbacks hors verrou, annulation au moment de publication, nettoyage et reprise. Décision : T4d approuvé. Catalogue SHA-256 `5a4427e13bfa7e946a659cbb5cc50921d201ae10dd8716b16315394119175416`, stockage `9e5d402bccf1acbdb5f2f15a8c7556c16b244aaa8b94512c294ef60136db9a4d`, tests catalogue `28835930e3dff3b3b84647ae88412b3a40dc26938bb4a7c9c269ab7754909e42`, tests stockage `59c8b875c2f75e1450722db4c957e41498b3597b3528c6c4700db3ef0a2aeb5b`. Journal du vert : `04a2164801b40903b6070653ac9de2756e59e9945ba176d5696a0d4696916c35`. Le branchement à l’accueil et à l’assistant reste à faire.

## T4f — Point de sauvegarde audio

Le gel du moteur passe 16 tests, sans échec, erreur ni test ignoré. Le rouge initial comportait 15 tests, dont six échecs attendus sur le squelette de `checkpoint`. Une erreur de compilation intermédiaire due à des types déclarés dans une classe interne a été corrigée avant ce vert. Les preuves sont archivées sous `app/build/reports/meeting/t4f/red/` et `green/` ; Astra a vérifié le XML final et les empreintes.

Le point de sauvegarde compte les blocs acceptés, y compris celui encore en calcul lorsque la file paraît vide. Il attend la transmission des mises à jour concernées, sans finaliser le stream ni perdre les profils. Les blocs suivants appartiennent à un point ultérieur. Annulation et panne native terminent les attentes avec une erreur constante, et les continuations s’exécutent hors verrou. La pause Android complète reste à intégrer dans T7.

Deux passes Astra sur le même gel : conformité du contrat et confidentialité des erreurs ; puis courses d’admission, annulation, fin normale, callbacks et conservation des comportements existants. Décision : T4f approuvé. SHA-256 moteur `1febb3dd3401cb17f87530c29fe6519b40eddfdcd97670a55d1d101f73ea8882`, tests `48d4ad9d309a2f5f7d305571f09b75b857e4426f85da45d3d5146a670f9d83bd`, journal du vert `e5e32612e66a7e68dae4fa395abced2f263d2591e5be8a8a4ca5c69b4753e984`.

## T5d — Édition restaurée et sauvegarde regroupée

Le gel passe 17 tests : cinq pour l’éditeur, cinq pour le writer et sept pour les profils après extraction du normaliseur commun. Astra a relu les XML de `app/build/reports/meeting/t5d-green-robolectric/` et les cinq empreintes source/test. Le rouge initial comptait dix échecs attendus du squelette. Deux tentatives intermédiaires ont échoué à la compilation de fichiers T6/T4f en cours de travail ; une autre a montré trois échecs de setup Android JSON dans le runner JUnit simple. Le passage d’EditorTest à Robolectric SDK 34 a corrigé ce setup, sans contourner la validation du document en production.

La restauration conserve les identités et permet les retouches, mais ignore tout ASR tardif. La fin fait de même pour une session vivante. Le tour documentaire réservé à l’utterance zéro reste stable et peut porter des images sans profil vocal. Les écritures sont regroupées sur le dernier snapshot, avec un délai de 500 ms qui ne glisse pas à chaque mot. Les futures de flush couvrent leur génération ; une panne conserve le snapshot, signale une erreur générique et permet une tentative explicite sans boucle automatique. Une fermeture échouée refuse les nouvelles mutations tout en gardant la dernière version pour réessayer.

Deux passes Astra sur le même gel : contrats d’édition, identités, normalisation et sauvegarde ; puis mutations pendant écriture, erreurs, répétitions, fermeture et callbacks hors verrou. Décision : T5d approuvé. SHA-256 : éditeur `0b84e52e6b131f52cf833559e7a56d069d1c30d5be718f957571a9721478b10d` ; writer `82ed825ba89c342b5df3d46fb8758042019d11dc13154b5ce8e4de7dd0086087` ; profils `c927a6bd7bd2176ff32e8777cc230446a5995be2bf5ff2111cd61c7fafaf42e2` ; test éditeur `a7e8a7c5b324b86fcc85c4b92d49a20990a6c5124c14b265c8de8125fc7ed5e6` ; test writer `95e2511d8f3fba11fb0e27d557375a86597f8f2f2a26f97ecb67e56010693583`. Journal du vert `46ea0559703cd0216775c6c4bbd89e7c9a187bed4c6174d1165431d7f93c6b99` ; build en quatre secondes. Le raccordement de ces classes au panneau et aux commandes audio reste à faire.

## Documents enregistrés

La spec et le plan détaillé sont rédigés par Astra. Leur conversion mécanique figure dans [le PDF complet](plan-renders/mode-reunion-spec-plan-complets.pdf), avec [les aperçus page par page](plan-renders/README.md). L’édition actuelle compte 23 pages à 150 dpi, dont les contrats des gestes et de la spirale et le suivi après T7b. Astra a inspecté les 23 pages, puis corrigé une formulation historique concernant l’import des médias. Seule l’image de la page 6 a changé après conversion ; Astra l’a réinspectée, les 22 autres étant octet-identiques. Aucune collision ni sortie des marges n’a été relevée. Le PDF et les 23 images sont identiques dans le worktree et le checkout principal.

SHA-256 du PDF : `7e2b1ecc8ddb275a6a938ea41688c694ee1eba8097c49c078acd02da412f5f62` ; source spec : `fc79d6aafae49000749eea777f12fe6425005f8dc1b72a330bc6abb54b1fbd62` ; source plan : `2d50dc32e5b5653d0b03fa9fc265a134304ccebfe7fbd9745fc22d3b77e428af` ; renderer : `fbaf6cdf99eed86ac7e318d4644aa5dc36a0522529fc1029602008c2bbb60f2a`. Le Markdown reste la référence des précisions d’implémentation ultérieures.

## T6/T7c — Vérifications intermédiaires, intégration en cours

Le passage commun conservé sous `app/build/reports/meeting/t7c/red-adverse/` a compilé les sources et exécuté les tests. Astra a relu les XML : 16 tests des opérations d’images, dont 15 échecs sur les opérations encore absentes ; 7 tests de stockage, dont 3 échecs ; 11 tests du panneau, dont 5 échecs, dans `t6/red-from-t7c/`. Journal brut SHA-256 : `c2b3e4e18dc0e0a9722628c0e05cd4044cb1dcd986570010b19de4326e7c780c`. Ces résultats sont des étapes de développement, pas une validation de livraison.

Le stockage doit encore refuser les IDs JSON qui ne sont pas des chaînes et restaurer la valeur antérieure lorsqu’un commit échoue après modification en mémoire. Deux tests de panne de commit rencontrent pour l’instant une frontière de préférences non branchée ; ils ne prouvent pas encore que la restauration a été exercée. Le panneau doit corriger ses actions d’images, l’actualisation de thème et le compteur d’intervenants. Le cas compact s’arrête actuellement sur un rattachement de vue déjà parente ; il faudra le rejouer après correction pour contrôler effectivement la hauteur de texte visible.

## T7a — Réservation du moteur de nettoyage

Les 24 tests ciblés passent : dix pour la réservation, huit pour GemmaCall et six pour l’admission du runtime, sans échec, erreur ni test ignoré. Astra a relu les XML archivés sous `app/build/reports/meeting/t7a/green/`. Le build a duré deux secondes. Le rouge initial comportait sept échecs du squelette ; les trois cas complémentaires de fermeture ont été vérifiés au vert sans revendiquer un rouge propre à chacun.

La réservation ferme l’admission immédiatement, puis attend sur le worker propriétaire la fin du travail engagé et la fermeture du moteur. Une génération invalide les travaux en attente. Le watchdog ne lève pas cette réservation. Une annulation antérieure à la remise du jeton attend une fermeture sûre ; après remise, seul le propriétaire du jeton peut le libérer. Les fermetures de conversation, de moteur et d’une initialisation partielle signalent leurs échecs ; la garde mémorise cette incertitude et refuse tout nettoyage natif ultérieur.

Deux passes Astra ont porté sur les mêmes empreintes : conformité des barrières, annulation et confidentialité ; puis courses entre admission et worker, ordres de fermeture, nouveaux propriétaires et régressions de génération. Décision : T7a approuvé. Le branchement au choix de mode et au service relève encore de T7b/T7d.

SHA-256 : `LocalFormatEngine.kt` = `9e0ebf4f6ee5ec1a4a5ae5f33d84c8d0b8b3d1f38bee7da5ae61b34550eb6f4f` ; `LocalFormatMeetingReservation.kt` = `fc2c27cab98bf5161698e00453df7c0532d8da22662d9128e92c2315160ef9d2` ; tests = `2f35ac1a2fbb3c53ea830581e0618ad91d1459d67153980b29c2f1daf360ffe8` ; journal = `90ffc54a197c1e0a9a305ff9471e2792632b2f8e19255610053b06d6cd2809ad`.


## T6 — Panneau, première validation JVM complète

Les 13 tests du panneau et les 20 tests médias ciblés passent dans `app/build/reports/meeting/t6/green-attempt-3/` : 33 tests, zéro échec, erreur ou test ignoré. Les deux derniers échecs intermédiaires provenaient des fixtures : libellé attendu différent du menu réel, puis éditeur hors des vues attachées. La fixture de défilement vérifie maintenant un éditeur focalisé réellement visible au bas de la liste avant ajout d’une prise de parole. Le code de production n’a pas changé pour ces deux corrections.

Le passage commun exécutait aussi trois nouvelles régressions T7c : 75 tests au total, trois échecs, tous attendus dans ce lot. Il ne doit pas être présenté comme un build global vert. Journal commun SHA-256 `52ebaf812ad86d7c4a7c067a4b185e2e0dcbb2108c38f948fc445b1cd35b04c1` ; XML T7c conservés dans `app/build/reports/meeting/t7c/red-integrity/`. Les défauts reproduits concernent l’ordre d’un lot mêlant images nouvelles et déjà rattachées, un numéro durable contradictoire pendant la réconciliation et une réservation Réunion incomplète qui ne doit pas suivre la voie Dictée.

Gel T6 pour les captures à venir : contrôleur `ed46ae162831ee8bd912ee28360b7e9c4b8c9cb3a1c9c41307b1187ac2bc1ef7`, vue `ba6203e2ee18a4de277705ebdb725513eda035052c44f09cec4e467e2016a1f1`, ligne éditable `21e9110618d12aa50f757e96c5a10da0c89619522db88309d40baacdcdd367f5`, tests du panneau `836013ff27bcf8c3b9dc6f1a394acfbc926979f9d91ec93ef478bdab033b118a`. Les captures Android et les deux revues finales du panneau restent à effectuer ; les tests JVM seuls ne valident pas son rendu natif.

## T7c — Rattachement durable des images, validation finale du composant

Astra a vérifié le rouge puis le vert archivés : le dernier test reproduisait la suppression d’un fichier déjà accepté lorsque l’ancre devenait invalide. Il échouait seul parmi onze tests de stockage ; la correction limite désormais l’effacement des fichiers aux captures non acceptées. Une annulation de réservation acceptée mais invalide conserve ses images, qu’une note peut déjà référencer.

Le passage commun final sous JDK 21, avec `-PmeetingPrototype=true` et `--max-workers=4`, comporte huit suites et 83 tests, zéro échec, erreur ou test ignoré : 11 ancrages, 22 opérations, 11 projections, 6 séries de captures et 33 tests panneau/médias. Les XML sont archivés dans `app/build/reports/meeting/t7c/accepted-retention-green/` et `t6/native-captures/`. Le même passage a assemblé le prototype et son APK de tests ; journal terminé par BUILD SUCCESSFUL en 38 secondes, 73 tâches dont 28 exécutées. Les captures Android T6 sont encore en cours à ce point.

Première revue Astra du gel final : conformité du routage par session, métadonnées invalides bloquantes, correspondance durable entre IDs et numéros, ordre de capture, images conservées pendant le filtre et absence de fausse étiquette sur le tour documentaire. L’ordre de publication reste résolution persistée, document, flush du brouillon, note structurée, puis effacement de la réservation.

Seconde revue Astra sur les mêmes trois empreintes de production : récupération après publication interrompue, retouches pendant flush, répétition sans doublon, contradiction de numéro durable, offset UTF-16, borne de dix images et limite de trois flushs. Comparaison de NoteImageStore à la référence médias du checkout principal pour distinguer le changement Réunion des ajouts médias préexistants. Les erreurs de sauvegarde ne suppriment ni la réservation ni les pièces déjà acceptées. Décision : T7c approuvé comme composant ; son raccordement effectif aux commandes caméra et à la durée de vie du service reste en T7d.

Empreintes SHA-256 du gel :

- `NoteImageStore.kt` : `05703a75f02b78254b65727149fbc14cdfad84f6a558136d50bc13be1309c790`.
- `MeetingImageOperations.kt` : `4f3899511c450235c06e10aabd9215dd1b3d987205ac712ab2e2cec393830ba1`.
- `MeetingProjection.kt` : `fd9d8127c7a33a596dfaef60193a888ee484134b5ba25773fb86aadafcaa774c`.
- `NoteImageStoreMeetingAnchorTest.kt` : `d44511880c5b53af82065f334b3376d521b870bfe6895c01ae3f3c5c622fa266`.
- `MeetingImageOperationsTest.kt` : `fd4f05372e92b72ceed3eca2da61d97a99985178e8b34b0fdd48740247fd0af8`.
- `MeetingProjectionTest.kt` : `770b60ef572d9799329e315df4aae8725e884abfafdfb1f060cf2f1e54bf7716`.
- `gradle-raw.log` : `53ddc23b03dffb40c64fb59a223f04c9a8345da23311bb6cbd0f29ae5d8f146c`.

## T6 — Audit Android du panneau, composant approuvé

Le second passage de la fixture native termine normalement : `OK (1 test)`, 11,285 secondes, `INSTRUMENTATION_CODE: -1`, sortie 0. Le premier passage reste conservé comme échec : un dialogue ANR de System UI masquait les captures, puis la révocation de RECORD_AUDIO dans le nettoyage de la fixture tuait son processus avant la fin du runner. Le seul AVD de test a été redémarré sans effacement. Pour le second passage, la permission a été accordée au prototype avant le runner puis restaurée à son état initial refusé après sa fin. Aucun changement des trois sources T6 ni des APK entre ces deux essais.

Astra a inspecté les douze images du second passage : retour de voix, choix entre homonymes, saisie d’un nom long, renommage répercuté, voix ignorée avec image conservée, ouverture du clavier, erreur de sauvegarde, progression de téléchargement, thèmes clair et sombre, panneau 240 × 112 dp en paysage aux tailles de texte normale et agrandie. Le panneau compact conserve deux lignes à taille normale ; la taille agrandie utilise le défilement et l’agrandissement du panneau. La capture du clavier constate son ouverture ; elle ne remplace pas un essai de composition et de correction pendant l’ASR dans le vrai overlay. Les données de ces scènes sont simulées et aucune de ces images ne constitue une preuve de reconnaissance vocale.

Première passe Astra sur le gel : fonctions demandées, identité des commandes de participants, absence de masquage arbitraire des inconnus, édition et images, hiérarchie visuelle, dimensions réduites et thèmes. Seconde passe sur le même gel : callbacks de dialogue tardifs, séparation annulation/Inconnu, conservation des champs/IME au rendu, défilement différé invalidé, retrait des callbacks à la destruction et absence de chargement natif dans les vues. Les 13 tests du panneau et les 20 tests médias ciblés restent verts dans le passage commun de 83 tests. Décision : T6 approuvé comme panneau ; T7d/T8 doivent encore vérifier son hébergement, les gestes, la capture réelle et les sauvegardes applicatives.

Sources figées : contrôleur `ed46ae162831ee8bd912ee28360b7e9c4b8c9cb3a1c9c41307b1187ac2bc1ef7`, vue `ba6203e2ee18a4de277705ebdb725513eda035052c44f09cec4e467e2016a1f1`, ligne éditable `21e9110618d12aa50f757e96c5a10da0c89619522db88309d40baacdcdd367f5`. Journal final du runner SHA-256 `2830657884d75284aba90b98e29a9bf00631fb45ea4cc27543de4cd997e70754`, logcat `e3a0f8de0559acc6428f17fd801f7a138ace23c552b18fcd36bdddc79e4bd83f`. Preuves brutes : `app/build/reports/meeting/t6/native-captures/ui-fixtures-simulated/run-2/`.


## T7b — Choix de mode et exclusivité du processus

Les six suites ciblées passent sous JDK 21 : 16 tests du coordinateur, un test de son instance partagée, deux tests de moteur résident, 12 tests de réservation et 14 tests Gemma, soit 45 tests sans échec, erreur ni test ignoré. Astra a relu les six XML archivés dans `app/build/reports/meeting/t7b/green/`. Le journal termine par BUILD SUCCESSFUL en neuf secondes ; les squelettes T7d présents compilaient aussi, sans que leurs tests aient été exécutés.

Première revue Astra : préférence commune avec repli Dictée, verrouillage du mode pendant une session, attente des chargements et moteurs résidents avant la réunion, rejet des publications périmées et maintien de l’exclusivité jusqu’à fermeture native sûre. La validité d’un jeton vérifie son propriétaire, sa génération, son identité active et l’absence d’échec de fermeture dans le processus. L’échec de fermeture interdit les admissions ultérieures, y compris celles du formateur partagé.

Seconde revue Astra sur le même gel : concurrence entre changement de mode et réservation, ancien chargement qui finit après une bascule, jeton étranger avec le même numéro, callbacks hors verrou, notifications différées servies avec l’état courant, annulation et renouvellement d’un propriétaire. Le résultat de disponibilité est arrêté sous verrou puis transmis hors verrou ; l’hôte devra encore revalider le jeton juste avant son usage et avant d’ouvrir le micro. La garde de fermeture du formateur refuse toute nouvelle destruction après une fermeture incertaine. Les chemins Dictée existants restent couverts par les suites ciblées. Décision : T7b approuvé ; raccordement à l’accueil, à l’assistant et au service restant en T7d.

Les onze sources et tests ont été comparés au manifeste figé, sans différence. SHA-256 du manifeste : `4482d577703a9880a6624c472ee4dcb581a1edbed74960fe278ab9f8b3c1b23c` ; journal : `76d0c645b11bc2b44f926f767b1046a4bdb3db7d81a8805cf7799ab9dacb8053`. Sources principales : coordinateur `40047f9b666f2248bc4e324bd5956d97a330edc0912385d240100dad70895b55`, moteur résident `f49761b41b5f1ea83b25198093e7afd580788592e65841d2651c4243ad39756c`, formateur `3171888b4b37fac04c36103efb0747042fe3865127d50bce4983a97f80c3e4be`, réservation `23aa2738e7d89af321aa8d760a67d1b7da1a10fbe296c4a940d4214d635893c8`.


## T7d — Premier rouge du contrôleur et des gestes

Le passage commun sous JDK 21 compile les sources puis exécute 44 tests, dont 37 échecs comportementaux et aucune erreur de setup : contrôleur 21/21, transfert du brouillon 6/6, writer 7/2, spirale 5/4 et table des gestes 5/4. Les échecs correspondent aux squelettes sans comportement, à la géométrie absente ou aux commandes encore neutres. Les cinq XML et le journal ont été archivés sous `app/build/reports/meeting/t7d-red/`, avec copies par lot dans `t7d1/red/` et `t7d2/red/`. Journal SHA-256 : `f95ae6c494f32f0d744f1a739c11c74aea91b50a525f0b4a42d8bf85593fe041`. Astra a relu les assertions et les XML avant d’autoriser l’implémentation.

Une correction de test a été demandée avant le vert : les commandes répétées doivent conserver leurs futures de fin en attente jusqu’à fermeture native et publication durable, et une tentative de reprise pendant PAUSING ne doit pas être différée. Ce contrôle ne doit pas favoriser une fin réputée réussie avant `closed`. Le statut `captureActive` est aussi vérifié après arrêt du recorder, avant la fin du checkpoint, pour immobiliser la spirale pendant le traitement restant. Ces lots sont en développement ; aucune validation applicative n’est revendiquée.

## T7d.2 — Premier essai après implémentation de la spirale

Les cinq suites groupées compilent et exécutent 30 tests : six pour la spirale, sept pour l’onde existante, neuf pour les formats, cinq pour la table Réunion et trois pour les appuis en pause. Deux assertions échouent dans la suite de la spirale, toutes les autres passent. Ces deux échecs surviennent à l’attente du premier callback d’animation, avant le masquage testé ; le runner signale aussi des opérations principales en attente. Astra demande d’établir et de vérifier les préconditions réelles de fenêtre attachée, visible et animations activées avant de conclure à un défaut du scheduler. Les gardes de visibilité ne doivent pas être retirées pour satisfaire la fixture. Le rendu natif reste à effectuer.

Les cinq XML et les empreintes des quatre fichiers sont archivés dans `app/build/reports/meeting/t7d2/green-attempt/`. Journal SHA-256 : `76a2070212c453a1fd218bdec167f3bf4958d9db008ba6cfdc8a4399d34d98d9` ; BUILD FAILED en onze secondes pour ces deux assertions. La classe historique `DictationTapGestureCoordinatorTest`, présente dans un fichier au nom différent, n’était pas incluse dans ce passage ; elle sera groupée avec la correction ciblée. Aucune approbation finale de la spirale n’est annoncée à ce stade.

## T8 — Provenance incluse dans les sources

Astra a complété les notices générales et conservé le fichier `THIRD_PARTY_NOTICES.md` complet de la révision NeMo-Speech.cpp épinglée. Cela rend consultable l’attribution du code dérivé de parakeet.cpp, en plus des licences déjà copiées pour GGML et SentencePiece. Le fichier upstream et sa copie `app/src/main/assets/licenses/meeting/nemo-speech-THIRD_PARTY_NOTICES.md` ont la même empreinte SHA-256 : `865dbd5bb2fb4e08811d2d6c90ebf260645b101ba8b0a9e6ba739b1d0222acf0`. Les notices distinguent les dépendances sélectionnées dans ce build des composants optionnels décrits par upstream. Le NOTICE racine et sa copie dans les assets sont identiques, empreinte `9b24a9502f57f41f0301d68764e78d9daa649dfa5b2060e974e4e7297b2947cb`. La présence de ces nouveaux fichiers dans l’APK devra être vérifiée lors du build final.

## T7d — Deux tentatives sans résultat comportemental

La tentative 2 a échoué pendant la configuration Gradle : les options de sélection des tests avaient été placées après une tâche d’assemblage. Aucune suite n’a été exécutée et aucun nouvel APK n’a été produit. La copie préexistante retrouvée dans ce dossier a été isolée sous `pre-existing-output-unverified/` pour éviter de la présenter comme résultat de ce passage.

La tentative 3, avec la commande corrigée, a échoué pendant la compilation Kotlin du contrôleur : deux companion objects empêchaient de résoudre ses constantes. Elle n’a exécuté aucun test et n’a pas produit de nouvel APK. La correction a fusionné les constantes dans un seul companion object, puis un nouveau passage groupé a été lancé. Ces deux essais sont conservés dans `app/build/reports/meeting/t7d2/green-attempt-2/` et `green-attempt-3/` ; ils ne constituent ni un rouge comportemental ni une validation du contrôleur.

## T7d.2 — Tests de la spirale et des gestes réussis

La tentative 4 a été interrompue après diagnostic par thread dump : le test de retour de visibilité attendait `Looper.idle()` alors qu’il venait de réactiver une animation répétitive. Le dump est conservé ; le test vérifie désormais directement la notification synchrone de visibilité, sans attendre la fin de l’animation. La production n’a pas changé pendant cette correction de fixture.

Astra a relu les huit XML de la tentative 5 : 52 tests exécutés, deux échecs dans le transfert de brouillon, aucune erreur de runner ni aucun test ignoré. Les six suites UI sont vertes, soit 37 tests : spirale 6, onde existante 7, coordinateur de tap Dictée 7, formats 9, table Réunion 5 et appuis en pause 3. Les sept tests du writer passent aussi. Les deux échecs du transfert restent à traiter et le contrôleur audio n’a pas encore été testé dans ce passage.

Les APK ont été assemblés dans ce passage ; le statut global BUILD FAILED vient des deux tests de transfert. Le prototype et son APK d’instrumentation sont conservés sous `app/build/reports/meeting/t7d2/green-attempt-5/apk/`, empreintes respectives `df6f6a2705e9d19f98bd6a0b871da0ccc49c8db4223b10b86396afd7e4888f02` et `a01c0f60a7870e860c30d1686fe0e10e0b675570530a3b0738e7d81da7039a62`. Ils servent à contrôler le composant visuel ; ce ne sont pas les APK finaux de la fonctionnalité.

Journal de la tentative 5 SHA-256 : `4278fce36d550ee5f6a3ff2e9e8aa7f1ff41d8607295a146ad9ffb4015c6e455`. La capture native de la couronne à la taille réelle de la pastille est l’étape suivante.

## T8 — Protocole et outils de mesure approuvés avant exécution

Astra approuve les outils après deux revues sur le manifeste final SHA-256 `8a303a8572c55734fb55c134a63fd371fc322471cee2ac76501be340a17b85eb`. Les dix identités ont été relues et comparées. Les 18 tests Python passent, ainsi que la syntaxe shell, les calculs AWK et la vérification effective des neuf bibliothèques historiques. Une régression du vérificateur lisait stdin au lieu du manifeste ; son correctif possède désormais un test qui lance le vrai vérificateur avec stdin vide.

La première revue couvre la cadence d’acquisition, les échéances de fin de bloc, l’ordre ASR/BOTH/BOTH/ASR/ASR/BOTH, la distinction chargement/préchauffage/direct, les premières apparitions et la stabilité des attributions. La seconde couvre les sorties d’échec, la déduplication du dernier résultat même sans diarisation, les dernières révisions vides, les rapprochements ambigus, les échantillons PSS invalides et la conservation du scénario T1. La fin de fichier ne fabrique aucune stabilité ; les résultats sans attribution restent comptés. Le calcul emploie les percentiles de rang le plus proche, avec effectifs explicites.

La sonde est figée à `33cf2e0271f41af025453ebf5b600ea940e875cb1209c4d8f9d4954264e2df86` ; l’analyseur à `5c4b16b891b11913b61589c3e94d9d42e20d6b9cfa977f74f0e9ea447bb83422`. La compilation de la seule sonde est autorisée après libération de l’émulateur utilisé pour les captures. Les six mesures devront se dérouler sans build concurrent. Aucun chiffre de performance T8 n’est encore disponible à ce point ; la mesure du processus de sonde restera distincte de l’application complète et du Poco F7.

## T7d.2 — Premier audit natif de la couronne

Le runner de la fixture a réussi, un test en 10,407 secondes, sur le prototype de la tentative 5. La permission micro a été accordée avant le runner puis révoquée après sa fin pour retrouver l’état initial. Cinq PNG et une vidéo sont conservés sous `app/build/reports/meeting/t7d2/native-captures/run-1/`. Astra a inspecté les cinq images : la couronne de six boucles est visible et contenue dans la pastille 74 × 44 dp aux phases intermédiaires, avec un centre ouvert et une rotation entre les images.

Le paquet visuel n’est cependant pas approuvé : la première image montre encore l’accueil précédent, la quatrième conserve la légende d’écoute et la cinquième montre encore la pause au lieu du mode Dictée. Le test attendait l’inactivité du thread UI, ce qui ne garantit pas la présentation de la frame venant d’être modifiée. Correction demandée dans la fixture uniquement : attente bornée du rendu avant chaque capture et nouveau passage sur les mêmes sources de production. Les images sont clairement des états simulés ; elles ne prouvent pas l’ouverture du micro.

## T8 — Six mesures sur émulateur, résultats disponibles

Les six passages de la sonde Android sont terminés dans l’ordre ASR/BOTH/BOTH/ASR/ASR/BOTH, sans build ni capture simultanés. Même fichier français synthétique de 12,78025 secondes, mêmes modèles épinglés, CPU et blocs de 160 ms fournis en fin d’acquisition. Les trois passages avec diarisation donnent une séquence stable 1 → 2 → 3 → 1 avant finalisation. Il s’agit d’un contrôle du mécanisme sur trois voix synthétiques, pas d’une mesure de qualité sur huit personnes réelles.

Les percentiles réunissent 22 mots finaux par passage, soit 66 observations par variante ; les révisions ne sont pas comptées comme de nouveaux mots. Le délai part de la fin estimée du mot dans l’audio et se termine à sa publication par le runtime.

| Mesure | Médiane | 95e percentile | Observations |
| --- | ---: | ---: | ---: |
| Texte, ASR seul | 455 ms | 1 088 ms | 66 |
| Texte, avec diarisation | 501 ms | 1 303 ms | 66 |
| Première attribution positive | 501 ms | 1 303 ms | 66 |
| Attribution stable confirmée | 1 018 ms | 1 547 ms | 66 |

Sur ce fichier, tous les mots des passages avec diarisation finissent attribués et confirmés ; aucun rapprochement ambigu n’a été exclu. Cela ne démontre pas que ces mots ou ces attributions seront exacts sur une vraie réunion. L’absence de tag en ASR seul est attendue. Le texte et l’étiquette provisoire peuvent ainsi être affichés avant la confirmation du locuteur.

| Passage | Variante | Préparation | Texte p50 / p95 | Retard audio maximal | Pic PSS |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | ASR seul | 5 750 ms | 453 / 1087 ms | 5 ms | 943.2 Mio |
| 2 | ASR + diarisation | 11 069 ms | 492 / 1303 ms | 265 ms | 1080.9 Mio |
| 3 | ASR + diarisation | 11 628 ms | 488 / 1307 ms | 220 ms | 1078.9 Mio |
| 4 | ASR seul | 2 792 ms | 449 / 1089 ms | 5 ms | 942.7 Mio |
| 5 | ASR seul | 2 252 ms | 455 / 1085 ms | 79 ms | 942.9 Mio |
| 6 | ASR + diarisation | 10 147 ms | 501 / 1258 ms | 234 ms | 1078.9 Mio |

La préparation inclut chargement et préchauffage avant l’ouverture de l’horloge audio. Elle atteint environ 10 à 12 secondes avec diarisation sur cet émulateur ; elle ne doit pas être confondue avec le délai pendant l’écoute. Les pics PSS mesurés passent d’environ 943 Mio pour ASR seul à 1 081 Mio avec diarisation. La mémoire est échantillonnée toutes les 250 ms sur le processus de sonde, pendant préparation et reconnaissance ; ces lectures ajoutent elles-mêmes une petite charge et ne capturent pas nécessairement tous les pics entre deux échantillons. RSS est conservée séparément et n’est pas assimilée à PSS.

Les résultats soutiennent la poursuite du prototype en direct. Ils ne valident pas encore la latence de l’interface/JNI complète, une réunion longue, la chauffe, la consommation ou le Poco F7. Les variations de préchauffage montrent aussi l’intérêt de conserver chaque passage plutôt que d’attribuer toute différence au seul coût de la diarisation. La validation physique de 30 minutes prévue au plan reste ouverte.

Preuves : [trace brute](t8-benchmark.raw.log), [analyse JSON](t8-benchmark.summary.json). SHA-256 de la trace : `2e7686000290b1caf478b6c2ddd49e90534800da7f023292b4b3a71f9c504f98` ; JSON : `c247d6badf9463660ac1f4a75766116d3f047258fbf781530e94a0c15d036d32`. Sonde arm64 : 49 703 208 octets, SHA-256 `09e3605ba64999be95990cd2ef80bc6455cb8b199407e9f2dff7c5a0b276b34c`, quatre segments LOAD alignés à 16 Kio ; aucun nouveau build de la bibliothèque JNI dans cette étape.

## T7d — transfert du brouillon approuvé, contrôleur en correction

Astra a effectué deux revues distinctes sur les mêmes fichiers : conformité du transfert et de la sauvegarde, puis concurrence, annulation, retrait du writer et retours d’erreurs tardifs. Le registre et l’extension du writer sont approuvés pour leur contrat de propriété unique. Les callbacks d’interface restent détachables ; le prochain propriétaire attend la dernière écriture, reçoit le snapshot conservé et peut réessayer après une panne. Le host devra utiliser cette même frontière pour tous les accès au brouillon.

Empreintes figées : MeetingDraftOwnership `5f165ead695a0789fae94447e3d10f367f4c22c8ed5383ee7404de5d308edcc4`, MeetingDraftWriter `6d16bd1e71bef1aaf2dd9ccec33db021827684b4860a1f943bc283ac51e05b86`. Tests associés : `4df5536de625c686371aec141129401dc756be531aa17190eedb44440e66f29c` et `b9b8a4078af1f110f2faf6ae92095dfd24f3d4748d7ea6c0de7e8d0d15e79709`.

Les XML archivés dans `app/build/reports/meeting/t7d1/green-attempt-2/xml/` confirment 8 tests Ownership et 7 tests Writer réussis, sans erreur ni test ignoré. Ce run sous Android Studio JBR 21.0.6 reste globalement en échec : le contrôleur totalise 24 tests dont 5 échecs de fixtures asynchrones, en cours de correction. Journal SHA-256 : `37c116170a3adab0a080423df370848eae2f6443c63ed6f67734f35328f82b55`. Le premier run et ses APK étaient sous JDK 17 ; ils ne constituent pas une validation JDK 21.

La revue fonctionnelle du contrôleur a également identifié des corrections de production : arrêter réellement le lecteur lors d’une fermeture native inattendue, conserver le lease jusqu’à la fin du lecteur et du natif, supprimer le double armement qui neutralisait une annulation, et assurer le repli après une exception de finish. Ces corrections doivent recevoir leurs propres tests et les deux revues avant approbation du contrôleur. Aucun raccordement audio complet n’est déclaré validé ici.

## T7d — RED des fermetures et de l’admission

Le run `controller-lifecycle-red-1`, sous JBR 21, compile les sources puis exécute 40 tests : 30 tests Controller avec 8 échecs attendus, et 10 tests Admission échouant sur le squelette non implémenté. Les assertions reproduisent les commandes pendantes et les défauts de fermeture identifiés par Astra ; un timeout est bien un échec. Le faux claim ferme désormais son writer comme le vrai registre. Une libération micro non confirmée doit aussi conserver le lease et signaler l’incertitude. L’implémentation des corrections et de l’adaptateur commence après cette preuve RED.

## T7d.2 — dessin et table des gestes approuvés

Astra a réalisé deux revues sur les mêmes sources : dessin, phases et contrat des gestes ; puis ordonnanceur, visibilité, animations désactivées, cache du tracé et régressions de la dictée. Empreintes : CursiveWaveView `8cb37570015b5081a49a5c381245dec892eec9ba3f399c91f18ac9c1e7ee7898`, MeetingPillInteraction `440db5685d5480fb3c76ca1fc8bc09979db071c2a0c2603d5d1f2d95da4bdb60`. Les six suites UI ciblées de `t7d2/green-attempt-5` totalisent 37 tests réussis ; les échecs Ownership du même run sont distincts et ont été corrigés puis vérifiés séparément.

Le run natif 3 réussit : un test en 7,402 secondes, cinq PNG avec les états attendus, vidéo de 13,013 secondes. Astra a inspecté les cinq images et les frames vidéo de 8 à 12 secondes : l’accueil initial précède la couronne en rotation, puis le retour Dictée. La fixture reste simulée, sans audio ni modèle. Le run 1 avait des captures trop précoces ; le run 2 échouait à cause du compteur d’attente des frames. Le run 3 corrige ces défauts de fixture, sans changer le dessin.

Preuves : `app/build/reports/meeting/t7d2/capture-run-3/`, [aperçus et vidéo](ui-renders/pill/README.md). Fixture `5c12156887a5aad3f1f9f8edeaa09f0492d092146d0642ac3ea71cac8c08c57d` ; APK test reconstruit sous JBR 21 `79debcf0872320fe912edd6d54bc1a810645e98e7f4be13a1c1e0fb603591478` ; APK app réutilisé `e29ee513e1b2e4e29d37408d2e6baa756ce5b3e5de5aed14e3eb3b0240700000`. La permission micro initialement refusée a été accordée avant le runner puis retirée après sa sortie. Cette approbation couvre le composant visuel et la table pure des gestes ; les vrais gestes, dialogues, écrans et commandes micro du service restent à raccorder et à vérifier.


## T7d.2 — premiers tests des écrans et adaptateurs

Le run groupé `app/build/reports/meeting/t7d-activities-red/` compile puis exécute 19 tests : Admission 10 réussis, AudioRecord 5 échecs attendus sur le squelette, accueil 2 échecs et assistant 2 échecs reproduisant l’absence des choix Dictée/Réunion. Le build global échoue sur ces 9 assertions attendues (7 secondes, 28 tâches dont 3 exécutées). Il ne produit aucun nouvel APK. Les tentatives antérieures bloquées par le helper de permission Robolectric sont des échecs de compilation, exclus du RED comportemental.

La revue Astra de l’admission `44967832ded99fb1e13b1fb5fcc6e926925744fa0b83cadce73aae74d5895021` demande une correction supplémentaire : l’échec de fermeture du formateur ne doit pas devenir une libération sûre simplement parce que son poison reste local. La future actuelle n’expose pas cette preuve de fermeture. Le test complémentaire doit reproduire `closeEngineOnWorker=false` sans signal global préalable ; seul le chemin d’annulation sûre conserve sa libération normale.

La revue du contrôleur corrigé `86be92492ff230638c152e4600903d81b0ff3f847e109b0cf5865baad0832fa9` a aussi identifié la fermeture native pendant la création du recorder. Le démarrage doit réserver sa barrière de nettoyage avant cette création, refuser de démarrer après fermeture observée et attendre la libération du recorder créé. Ces corrections et leurs tests restent ouverts ; les deux composants ne sont pas encore approuvés.


Le RED complémentaire `app/build/reports/meeting/t7d2/adapters-red/` compile puis exécute 50 tests sous JBR 21 : Controller 32 tests dont 2 échecs, Admission 11 dont 1 échec, AudioRecord 7 échecs attendus sur le squelette. Le nouveau cas Admission reproduit la fermeture du formateur sans poison global. Le contrôleur reproduit le démarrage après fermeture pendant la création du micro ; son autre échec vient d’un test qui terminait deux fois la même future d’arrêt tout en exigeant encore une attente après la fermeture des deux ressources. Cette attente de fixture doit être corrigée, sans retarder artificiellement la libération réelle. Les 30 autres cas du contrôleur réussissent. Build global en échec attendu, 4 secondes, 28 tâches dont 2 exécutées ; aucun APK produit.


## T7d.2 — écrans vérifiés, trois corrections ciblées restantes

Le passage `app/build/reports/meeting/t7d2/grouped-red-2/` compile sous JBR 21 et exécute 64 tests : accueil 6/6, assistant 5/5, admission 11/11, contrôleur 32/34 et AudioRecord 7/8. Le build global échoue sur trois cas attendus, en 11 secondes (28 tâches dont 6 exécutées). Les XML et empreintes sont archivés avec le journal brut. Les changements de mode externes, le refus sur moteur indisponible avec réservation conservée et l’assistant Xiaomi sans accessibilité obligatoire passent.

Les deux défauts Controller restants concernent la nouvelle tentative après un refus sûr de préparation et la restauration explicite d’une note sans brouillon. Le test AudioRecord confirme que le recorder était libéré alors que sa lecture restait bloquée après une exception de stop ; une seule chaîne doit posséder stop, attente du lecteur puis release. Ces corrections sont autorisées après le RED. Le raccordement final d’OverlayService reste ouvert : les gardes temporaires des Activities évitent le préchauffage Dictée en Réunion mais ne constituent pas encore l’armement complet de la pastille.


## T7d — contrôleur et adaptateurs approuvés

Le passage `app/build/reports/meeting/t7d2/grouped-green-1/` réussit sous Android Studio JBR 21 : 64 tests, zéro échec, erreur ou test ignoré. Répartition : Controller 34, Admission 11, AudioRecord 8, accueil 6, assistant 5. Build en 9 secondes, 28 tâches dont 5 exécutées ; aucun APK n’est produit par ce passage unitaire. Les cinq XML, le journal brut et les douze empreintes source/test sont archivés.

Astra a effectué deux revues distinctes sur les mêmes artefacts : conformité de l’édition, de la restauration, des réservations et de l’arrêt ; puis courses de démarrage/fermeture, réessai, rollback, callbacks, rétention des ressources incertaines et régressions. Controller `eace617f6db31f2ea6c02a054deb62167b0a8950aad8eeacab5ec3963a441904`, Admission `591574724b5c3211969a16d2ee8f0eecbf0edd4d0b7a5e058f85f5103d11b006` et AudioRecord `1cbbd14220384d0e4e1da565ab4ee8966a6b33a51b340da1b13767669f7aef92` sont approuvés pour intégration.

Les écrans passent aussi après retrait de la description d’accessibilité qui masquait leur statut dynamique. MainActivity `33a756e1d96d6098751db7e242b83418831db4ccce158375a721901b1bead258`, OnboardingActivity `ff315df1ccaa7bcff23ad11642737f13aaf22dde4414206e705cb5b02c065eef`, panneau partagé `06a8f1c1eaeb387d0e88b22d1f0bddcf662a392986b0b72cfd9d83dd3507a26f`. Les captures des vraies Activities, le smoke AudioRecord Android et le raccordement complet d’OverlayService restent à effectuer. Une extension de l’effacement ordonné du brouillon est cadrée dans le plan pour éviter la restauration d’un snapshot après suppression ; son implémentation et sa validation restent distinctes.


## T7d.1 — effacement ordonné du brouillon approuvé

Le nouveau contrat `MeetingDraftOwnership.clear(path, expectedSessionId)` passe les deux revues Astra : ordre et identité de la session, puis concurrence avec les claims, transfert de writer, erreurs et callbacks. L’effacement attend la restitution du propriétaire et la fermeture du writer ; il refuse les contenus inconnus ou une autre session. Une panne conserve le snapshot et permet de réessayer. Le cache n’est remis à l’état absent qu’après l’effacement durable.

Le RED `t7d1/ownership-clear-red/` exécute 29 tests dont quatre échecs attendus sur le squelette. Le GREEN `t7d1/ownership-clear-green/` réussit sous JBR 21 : Ownership 13, Writer 7, Store 9, soit 29 tests sans échec, erreur ou test ignoré. Ce passage construit aussi l’APK de test des captures Activities : 5 secondes, 58 tâches dont 10 exécutées. Ownership figé : `30efd4a86a2577b60209fd2d061df4586fd87c8ab657482dcb57fa0a4e5cff7d` ; test : `23431c4d197fd173106922f6a30aad9540d4f1889a95d4c6721c47b49451c5f8`. Writer et Store restent inchangés.

## T7d.2 — huit captures Activities et capture AudioRecord vérifiées

La troisième tentative des vraies Activities réussit : un test en 21,512 secondes, huit PNG inspectés par Astra, thèmes clair/sombre et deux vues à 135 %. Les boutons, états sélectionnés, retours à la ligne et cartes restent lisibles, sans chevauchement. Les vues longues sont défilables. Le téléchargement reste explicite ; les modèles absents sont affichés. L’assistant Réunion présente l’accessibilité comme facultative pour les captures d’écran.

Les tentatives 1 et 2 échouaient dans la fixture, respectivement sur l’application réelle du facteur de police et sur un observateur de vérification des modèles attaché après la transition attendue. Les corrections portent sur la fixture. Le troisième passage applique le réglage système, vérifie le facteur reçu par les Activities et restaure 1.0. La permission micro, accordée extérieurement pour éviter la boîte de permission, est révoquée après le runner. Aucun enregistrement ni accès réseau n’est réalisé par cette fixture.

Preuves et huit images : [écrans Android](ui-renders/activities/README.md). APK app réutilisé `8ee1f290156a53df5e9b7be8008426431774899e4b65ce9a4b69fb28b30d1197`, APK de test JBR 21 `b261d12d57eee85affa138c2b34e6bca13e866b0f2daeab6ba5ac8240b5650ef`. Archive complète : `app/build/reports/meeting/t7d2-activities/adb-capture/activities/attempt-3/`.

Le smoke AudioRecord Android distinct réussit également : un test en 3,742 secondes, mono 16 kHz PCM16, deux cycles de trois blocs de 9 600 octets chacun ; 466 puis 394 ms pour les cycles mesurés, tailles paires et deux arrêts terminés. Les échantillons sont jetés, sans assertion sur une voix ou un signal non silencieux. La permission est révoquée après exécution. Ce résultat couvre l’adaptateur avec Activity visible sur émulateur ; le microphone du service en arrière-plan, les vrais gestes de la pastille et la réunion complète restent à valider après intégration.


## T7d.2 — premier raccordement du vrai menu vérifié

Les deux premiers tests d’OverlayService passent sous JBR 21 : un glissement vers le haut ouvre le menu sans choisir un mode ; l’action Réunion explicite conserve le format Dictée mémorisé. Le moteur résident réellement détenu par le service refuse une ouverture Dictée une fois Réunion sélectionné. Le GREEN `app/build/reports/meeting/t7d2-overlay/green-attempt-2/` réussit en 7 secondes, 28 tâches dont 2 exécutées, deux tests sans échec ni erreur. Empreintes de ce point intermédiaire : OverlayService `c357799ac7e9fee61836da646af7cb9d6ff8a1a004a9cdea7fc2f505f52aaa9c`, test `d83c453318152fdedefb7794c5d4d9d5ec5e81696f1cc1c0ca158f457d367efa`.

Le RED précédent compilait et reproduisait les deux absences de comportement. Le premier essai GREEN avait un défaut de réflexion dans la fixture après passage du champ moteur en délégué lazy ; il est conservé comme tel. Ce point ne vaut pas approbation du service complet : contrôleur, dialogues, médias et sauvegardes restent à raccorder, puis à relire et vérifier.

Le jalon suivant, `green-attempt-3`, réussit sous JBR 21 en 12 secondes : huit suites, 46 tests, aucun échec ni erreur. Le menu Réunion masque les formats Dictée et affiche « Ouvrir la réunion » ; le choix sélectionné possède aussi son état d’accessibilité. Les suites des gestes, de la couronne et des appuis Dictée passent. Ce jalon fige OverlayService à `cbe8452e9ac538f64d290cc52a1769fb61af350708ab4346ac4f32417f42898d` et son test à `98279522c1bab0bfb128f3fe260900e716e29de12b1a7c49c2fb8eaa12af4229`. Le raccordement du contrôleur au panneau commence après ce point ; ces 46 tests ne prouvent pas encore une réunion dans le service.

## T7d.2 — quatre régressions d’intégration reproduites

Le raccordement du service reçoit ensuite quatre tests de régression ciblés : erreur de brouillon visible pendant LISTENING, publication synchrone de l’état sur main, dialogue de fin avec fenêtre overlay et texte exact, surface sans marges cumulées après redimensionnement. Le RED `t7d2-overlay/red-review-fixes` compile et exécute huit tests dont quatre échecs attendus, en 7 secondes. Source service : `54876ae18ebab82330448823f16e3371ecb94dedf619bd825a68c4993fe51331` ; test : `7ce29b3b17839a21e0ce3f0304f53a958e16cfa20371dbff43043b6213b145fa`. Le bon journal est `gradle-red-4of8.log`, SHA-256 `e67798e7fea8855a5512770c1bfc06524fbd5c57fd1278fccab670f44da14623`. Le `gradle-raw.log` du même dossier conserve une panne de compilation antérieure ; il ne décrit pas ce RED comportemental. Les corrections restent à vérifier.

## T7d.2 — retouche après fin publiée ; réouverture encore à raccorder

Les quatre corrections précédentes passent dans l’XML du 25 septembre à 00:23:39 UTC : huit tests sans échec. Le journal brut de cette première passe verte n’a pas été conservé. La suite suivante ajoute la retouche après FINISHED : la note durable reçoit cette retouche avant remplacement du document. Une mutation temporaire retirant l’appel de publication fait échouer uniquement ce nouveau scénario, sur neuf tests ; c’est une vérification de régression après implémentation. Après restauration, `post-finish-publication-green` réussit : neuf tests, build de 9 secondes, 28 tâches dont 4 exécutées. Les deux journaux et XML sont archivés sous `app/build/reports/meeting/t7d2-overlay/`.

La passe groupée `document-route-red-publication-green` compile onze tests : neuf du service réussissent ; les deux réouvertures de notes échouent parce que l’ancien `openNote` appelle encore la sauvegarde de texte plate. La protection de `TranscriptNotes` refuse cette conversion, comme prévu. Ces échecs demandent le raccordement de la voie structurée et de la présentation opaque dans le service, sans modifier cette protection.

Sur le service figé `2c9de27d925fc44e8c904b36899b8dc6bff0c1b6c489ff9a24ca87315aad2dc1`, les deux lectures Astra des nouveaux helpers demandent encore une opération START_NEW unique, une protection des retours tardifs et de l’édition pendant transfert, l’absence de note vide, la vérification des phases, la présentation du texte reconnu des passages manuels ambigus et la gestion des erreurs de fenêtre. La disponibilité des modèles doit aussi rester limitée à la phase DOCUMENT. Le service complet n’est pas encore approuvé.

## T8 — export natif de la réunion synthétique vérifié


Un test instrumenté réussit en 1,030 seconde sur Android 16/API 36 arm64. Il utilise les vrais exporteurs PDF, HTML et texte, deux images synthétiques et une note structurée dont le cache plat est volontairement obsolète. Le texte partagé de 385 octets est identique à l’attente littérale indépendante. L’extraction du PDF confirme la correction « jeudi », Sophie deux fois, Karim une fois et le passage « Intervenant à confirmer » ; les chaînes mardi, BRUIT et CACHE sont absentes. L’image du locuteur ignoré reste présente avant l’image documentaire.

Astra a inspecté les deux pages A4 du PDF natif, rendues à 150 dpi (1 239 × 1 754 pixels). Les prénoms et corps restent lisibles, les deux images sont entières et dans l’ordre attendu, les marges et pieds de page sont conservés. Les dates utilisent ici la langue anglaise de l’émulateur. Les contrôles HTML de contenu et d’autonomie passent ; son audit visuel est traité séparément.

PDF : 243 347 octets, SHA-256 `4d0a5013ad0148b9384ee9e2949b2ce2a7a8e86339156a9c64fd69643a6ee1b2`. HTML : 122 334 octets, SHA-256 `1e5be59b2a1344083de682e9a1ce9756c7eb16faa3a179d4332008abf72a5104`. TXT et attente : `9143525c383c94ee5f558657368f2622002a4f0f258506c83f6f69736732a264`. Fixture figée : `354d16e243cac5bd3299e2660b124397d7c7a0eacc7e76239c9ee46c6b3325e6`.

Les deux APK ont été construits sous JBR 21, puis archivés avant installation du prototype seul : app `e23fd9e261014ae3b2db58090ce50a46a97502269cad0ebc853a245c5a9b35f5`, test `17df7455c3ee664add04d9eb51e94364bbe44caf813a077790468e74b7517d98`. Build 3 secondes, 66 tâches dont 9 exécutées, sans test dans cette commande d’assemblage. Aucun modèle, microphone ou réseau n’est utilisé par la fixture ; aucune permission n’a été changée. Archive : `app/build/reports/meeting/t8-export/`, copie documentaire sous `ui-renders/exports/meeting-note-f5eb72e8/`.

## T8 — audit visuel HTML terminé

Le même HTML est rendu par Playwright avec Chrome 153 installé, sans téléchargement, en headless et dans deux contextes isolés hors ligne. Astra a inspecté les trois PNG : largeur de 390 et 1 280 pixels CSS à densité 2, puis détail de la première image ouvert sur mobile. Les deux images sont décodées, les textes sont lisibles et aucun débordement horizontal n’est mesuré. Aucune requête HTTP(S), erreur console ou erreur de page n’est observée.

Les clics réels ouvrent puis referment le détail. Sur mobile, l’image reste à 360 pixels CSS car elle occupe déjà la largeur disponible ; sur grand écran, elle passe de 575 à 770 pixels puis revient à sa taille initiale. Le PNG mobile ouvert est donc visuellement identique au précédent, avec un état DOM différent vérifié. [Exports, cinq aperçus et preuves](ui-renders/exports/README.md). Aucun exporteur ni fixture Android n’a été modifié pendant cet audit.


## T7d.2 — actions documentaires du panneau approuvées

Le panneau expose « Prendre une photo », « Ajouter une capture », « Copier la transcription » et « Exporter la note » dans les phases stables. Il publie l’édition focalisée et capture son ancre avant d’ouvrir le menu ; une réponse devenue étrangère à la session ou au run est ignorée. Le service doit encore raccorder les quatre commandes et revérifier l’état au moment de leur exécution.

Le RED exécute 16 tests dont trois échecs comportementaux attendus. Le GREEN `app/build/reports/meeting/t7d2-document-actions/green-1/` passe sous JBR 21 : 16 tests, zéro échec, erreur ou test ignoré ; 9 secondes, 28 tâches dont 5 exécutées. Astra a relu le contrôleur et les trois nouveaux scénarios, puis vérifié le XML et les empreintes concordantes. Contrôleur approuvé : `b3207713a0172d4c94d8d4636416a19b572d69b4838cd79be05c40eacafa0e8b` ; test : `87e576c0f435c5eadd8fd0d84347fd7845f53ec5246464c09adacf158a6d6a59`. La fixture de réouverture des notes est préparée séparément et n’a pas été exécutée par cette commande.


Le RED d’intégration suivant, `t7d2-overlay/six-review-red-compilefix/`, compile 14 tests et reproduit six échecs attendus : les deux réouvertures structurée/opaque, l’écrasement de la phase active par la disponibilité du modèle, START périmé, la création d’une note vide et l’absence du texte reconnu dans un passage manuel ambigu. Le service est encore `2c9de27d925fc44e8c904b36899b8dc6bff0c1b6c489ff9a24ca87315aad2dc1` ; test du service `632ae5f71c7b01187fb7b141fdb63dd24806e620252a4a636ca1d4a35502b89a` et fixture documentaire `d4266846a596b8a54d81254432553fa7e7a64e48af5f2a51f4abd5ae95e1e695`. Les XML et journaux ont été relus par Astra. Le dossier `six-review-red/` conserve séparément la panne de compilation antérieure due à un import de test manquant.

## T7d — suppression volontaire d’une note enregistrée préservée

Le contrôleur consulte désormais `hasSavedNote` avant de décider qu’un document vide ne mérite aucune publication. Une note connue volontairement vidée reçoit donc sa nouvelle version ; une réunion nouvelle sans texte ni image ne crée toujours pas de note. Une erreur de consultation reste récupérable par `retrySave`, sans relancer le micro ni le moteur. Le texte reconnu reste conservé séparément de `editedText=""`.

Le RED ciblé `t7d-existing-note/controller-red/` reproduit les deux défauts attendus sur 36 tests, en 4 secondes. Le GREEN `controller-green/` réussit : 36 tests, zéro échec, erreur ou test ignoré, sous JBR 21.0.6 ; build de 5 secondes, 28 tâches dont 5 exécutées. Astra a vérifié les XML, journaux et différences, puis conduit deux revues sur les mêmes snapshots : publication et préservation documentaire, puis erreurs, réessai, fermeture et compatibilité des ports. Controller approuvé : `2fb4870a17f0d9306f1289063f25686db08b389e1936d9b3417243de2eb6e113` ; Ports : `c24c15efa61d6751b7f0eac9da7c10ea91f58a5224ed664e2fd0fb7fe8f64497` ; test : `97f0963f5b4e92076c8af69e66401af5e52c3871ed0d15e5476201b804e8c9fd`. L’hôte doit encore fournir la requête depuis son cache de notes et appliquer la même règle aux gestes.

Le premier passage groupé avec la nouvelle fixture Lifecycle est conservé séparément dans `t7d-existing-note/red/`. Il compile, puis boucle dans `ShadowPausedLooper.idle()` avant les assertions du scénario de destruction ; le thread dump et l’arrêt explicite du wrapper (code 130) sont archivés. Aucun résultat de test ne lui est attribué. La correction de la fixture neutralise uniquement les animations pour ces essais de cycle de vie ; son nouveau passage reste à effectuer.


## T7d.2 — corrections du service validées, routes documentaires reproduites

Le passage `t7d2-overlay/document-routes-red-compile2/` exécute 20 tests sous JBR 21 : les 17 tests du service réussissent, les trois tests documentaires échouent sur les défauts attendus. Le nouvel essai d’ouverture sous lease Réunion reproduit le remplacement de `activeNoteId` alors que le document courant doit rester protégé. Les deux autres échecs concernent encore la réouverture structurée et la présentation opaque. Build de 20 secondes, 28 tâches dont 2 exécutées. La tentative précédente, `document-routes-red/`, s’arrêtait à la compilation sur un import `assertSame` manquant ; aucun ancien XML ne lui est attribué.

Le checkpoint suivant, `open-note-checkpoint-2/`, compile puis exécute 35 tests en 27 secondes (28 tâches, dont 5 exécutées). Les trois essais documentaires réussissent, ainsi que les deux essais Lifecycle sur pause/reprise et destruction avec fermeture retardée. Astra a lu les XML datés du 25 septembre à 02:14 UTC. Sept échecs restent distincts : deux attentes d’armement des Activities reproduisent le défaut prévu ; cinq tests du service utilisent encore par réflexion l’ancienne signature du helper de remplacement et échouent avant leurs assertions. Ce passage n’est donc pas un GREEN global. Service testé : `ddfb37c1814e77400274d557582fec618969bba08ba119a62854ae25246f7fef`. Les deux tentatives précédentes s’arrêtaient à la compilation : typage des shadows des Activities, puis type LayoutParams de la vue de lecture seule.

Astra a effectué deux revues des corrections sur la copie immuable `post-document-red-snapshot/source/` : préservation des retouches, requête de note connue et erreurs de fenêtre, puis transferts, callbacks périmés, images en vol et récupération. Ce lot est approuvé : service `f6f9e1f464d1bf6be1750848c1a252c87644f80ab92a980a0b6605b8154f8868`, test du service `8fae2cd211e3285c8f88684c16638d795a3b84697d163b1b67df833ccdcfe841`. La fixture documentaire est `8f256832c3298c57943a995eb6b8816f21654d9a8db5325530959ae15dc533fd`. L’approbation ne couvre pas encore le service final : les routes documentaires, les gestes, la réservation Dictée, les médias et les essais Android restent ouverts. La fixture Lifecycle n’était pas incluse dans ce passage.

## T7d.2 — ouverture des notes et armement depuis les écrans approuvés

Le RED `opaque-transition-red/behavior/` exécute 19 tests sur le service précédent et reproduit deux défauts : retour d’une note opaque vers l’éditeur connu, et contournement du contrôle d’identité lors du remplacement. Le correctif retire l’ancienne présentation opaque avant de réafficher l’éditeur et partage la même validation de la session entre ouverture froide et remplacement. Les contenus inconnus ou incohérents restent consultables sans écriture ni ouverture native.

Le checkpoint `open-note-checkpoint-3/` passe les 37 tests de cette intégration : service 19, documents 3, cycle de vie 2, accueil 7, assistant 6. Les deux écrans visibles peuvent armer le FGS en Réunion ; cette action ne démarre pas d’enregistrement et le service conserve ses gardes contre le préchargement Dictée. Les XML sont datés du 25 septembre à 02:29 UTC. Le même run comprend séparément le RED de `RecordingStartupTransactionTest` : 12 tests, dont 11 échecs attendus et un succès. Le résultat global est donc 49 tests, 38 succès et 11 échecs, en 21 secondes, 28 tâches dont 5 exécutées.

Astra a conduit deux revues distinctes après ces résultats sur les mêmes artefacts : conformité documentaire et armement depuis les écrans visibles, puis transitions, erreurs, identité, préservation des corrections et absence de lancement implicite. Service approuvé pour ce lot : `f4a64be24a1c07818f7b0a32cfaa3880f9f98dd0d8ecd6bd59bb2b2a576e3deb` ; test du service : `e79d3da0e90af584258a48da86720ba21f2db71bc08a8411b0b2baf60b540ed9`. MainActivity : `44edf8ec9322f21b60b60d6a69e8665b722fa71b657f817c0f66d3c5214fff5a` ; OnboardingActivity : `e78517de9043a568c1aec625c60f3a7535fd4087107dc5b2efeb17b7c4476a75`. Les gestes réels, la réservation Dictée, les médias, la notification et les changements de mode pendant un transfert restent dans le raccordement final.

## T7d.2 — rollback du démarrage Dictée approuvé

`RecordingStartupTransaction` expose désormais `Failed.recorderReleaseConfirmed`, sans le confondre avec la fermeture ASR. Une erreur de démarrage, de création de session ou de lecture de l’état du recorder déclenche le nettoyage ; `release()` est tenté même si `stop()` échoue. Le fait reste faux lorsque `release()` échoue. Les raisons d’échec et le parcours réussi sont conservés.

Après le RED de 12 tests dont 11 échecs dans le checkpoint précédent, le GREEN ciblé réussit : 12 tests, zéro échec, erreur ou test ignoré ; JBR 21.0.6, 10 secondes, 28 tâches dont 5 exécutées. XML du 25 septembre à 02:34:36 UTC. Preuves : `app/build/reports/meeting/t7d2-recording-startup/green-attempt-1/`. Astra a vérifié ces résultats et effectué deux revues des mêmes snapshots : source `b872e88166def051945c38e88a546a041d614dcbfd846e7bb9348aab70d24aab`, test `038ee55a0177169f28025ee8c579b211201ef3d003893ff2afef096d84615ad2`. Le service doit encore utiliser cette preuve pour son lease de run. La tentative distincte `t7d2-overlay/recording-startup-green/` a été interrompue avant toute exécution de test lors du transfert du bail Gradle ; aucun verdict ne lui est attribué.

Le helper d’arrêt `RecordingStopCoordinator` expose à son tour un fait volatile de libération confirmée. Les essais couvrent un vrai lecteur bloqué, le délai de fermeture et les exceptions de stop/release. Le RED exécute 8 tests et reproduit 3 échecs attendus ; le GREEN passe les 8 tests, sans erreur ni test ignoré, en 11 secondes (28 tâches, dont 5 exécutées). XML du 25 septembre à 02:40:52 UTC ; dossiers `t7d2-overlay/recording-stop-red/` et `recording-stop-green-1/`. Deux revues Astra approuvent cette seule extension du service `10294b9353ade5838ed307e9f4a95be6a794f59a17e27bfaea01e2e639e06e08`, test `dbf87af3935fbd36d7e4cb3c533cd640e5d0c4ec142bdf6d5bafd67d915fffe7`. Elle conserve Stopped/TimedOut et attend le retour effectif de release avant de rapporter true.

Un couple d’APK intermédiaires est construit avant le raccordement des gestes, afin d’en reproduire le défaut dans la vraie fenêtre Android : 13 secondes, 66 tâches dont 9 exécutées. Archive `t7d2-overlay/meeting-gestures-baseline-apks/`, application `b18863536cec95c36c82275f189fb44ee9450ec9823fa23f0e269ba75eeada46`, test `739ebe36aa93decccc0b7e1f90a2ac786f7c210c9b17def56859ece66399eab8`. Ils ciblent uniquement les packages du prototype Réunion. Leur construction ne valide pas encore les gestes ni l’enregistrement complet.


## T7d.2 — gestes et notification : défauts reproduits

Le premier passage JVM `pill-notification-red/` exécute 21 tests : 19 réussissent et deux échouent. Le geste vers le bas en PAUSED n’ouvre pas la confirmation de fin. L’échec de notification vient d’une fixture incohérente : elle impose LISTENING au contrôleur alors que le service reste MIC_UNARMED, dont le message doit rester prioritaire. Ce premier échec ne prouve donc pas un défaut métier de notification ; la fixture doit être corrigée avant nouvelle vérification. XML du 25 septembre à 02:53:26 UTC, 7,533 secondes pour la suite. Service baseline : `10294b9353ade5838ed307e9f4a95be6a794f59a17e27bfaea01e2e639e06e08`. Le correctif reste à vérifier.

Le prévol du RED tactile est arrêté avant installation : l’ancien émulateur ne dispose que de 36 Mo libres pour un APK de 86 217 520 octets. Aucun runner ni changement de permission n’a eu lieu ; les deux modèles privés ont conservé leurs empreintes et le brouillon standard est absent. Ce prévol ne constitue pas un résultat RED. La préparation d’un AVD de test distinct, plus spacieux, est autorisée en conservant intégralement l’ancien AVD et ses données.


## Publication demandée le 25 septembre

L’utilisateur demande de déposer le résultat sur GitHub Actions une fois le travail terminé. Cette instruction autorise la publication du chantier dans une branche dédiée et son build CI après validation locale ; les restrictions antérieures de non-publication ne constituent plus une attente d’accord pour cette étape. Le checkout principal doit rester préservé. La livraison exige un run terminé et un artefact APK identifié, pas seulement un push ou une exécution démarrée.


## T7d.2 — raccordement initial des gestes : GREEN et revue

La fixture de notification corrigée vérifie d’abord la priorité MIC_UNARMED, puis place le service dans son état armé. Le RED contre le snapshot de production `10294b9353ade5838ed307e9f4a95be6a794f59a17e27bfaea01e2e639e06e08` exécute 21 tests et reproduit les deux défauts métier attendus. Il est conservé séparément dans `pill-notification-red-baseline/`, XML du 25 septembre à 03:06:17 UTC.

Le GREEN `pill-notification-green-1/` passe 42 tests : service 21, table Réunion 5, formats 9 et appui Dictée 7. JBR 21, build de 12 secondes, 28 tâches dont 5 exécutées ; XML à 03:11 UTC. Le service figé vaut `2172205965f516245fc28e9ba63832ddf9406e09dcf0b2ae897f77f5b4c35fff` et son test `ea51c6fa3c264b1a1b4b284d82661c6fe316d6e770e3b40103cdbf52f8f6535a`. Deux revues Astra demandent encore trois corrections : aide gestuelle propre à Réunion, reprise effective d’une publication de note échouée, et cohérence du snapshot publié avec la barrière de brouillon. Le lot n’est pas approuvé final.

L’AVD distinct `DictAI_Meeting_API36_20260925` a démarré sur emulator-5556 : Android 16/API 36 arm64, 4 cœurs, 4 Go RAM et partition de données de 12 Go, plus de 10 Go libres. Les configurations et la commande dédiée ont reçu deux lectures Astra ; l’ancien AVD est arrêté proprement et conservé. Les APK baseline exacts ont été installés seulement dans le nouveau prototype. Le premier runner exécute un test, mais s’arrête avant tout geste sur le contrôle de visibilité de la pastille. La lecture de la fixture révèle un mélange de coordonnées écran et de coordonnées relatives à la vue racine ; la correction du contrôle est demandée. Ce résultat est une erreur de fixture, pas un RED tactile métier. Les rapports sont conservés sous `gesture-red-avd-12g/` et les tentatives de gestes associées.

La correction géométrique de la fixture est relue à `5392323ce104dd1251000a70cbe9f821841f9794ae14e5872d9d18f3653e7d69` ; l’APK de tests correspondant vaut `10355c9b46467caaf2d078461ca16c6de4a843bf19bbdd46d505a8c614b454a4`. Le passage suivant atteint le premier glissement, puis attend en vain le texte du menu (un test, un échec, 11,854 s). Astra identifie une seconde lacune de la fixture : la lecture des fenêtres UiAutomation n’active pas `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, requis par la [documentation Android](https://developer.android.com/reference/android/app/UiAutomation#getWindows()). Cet essai ne prouve donc pas encore un défaut d’ouverture du menu. La correction reste limitée au test, avec restauration des flags et capture de diagnostic avant nettoyage ; aucun correctif produit n’est justifié par ce seul résultat.

Le gel corrigé `cb6e3451f40686760e30b650f41fa4f54b804bf2572e1168643fc7860c4755d5` reçoit une revue Astra favorable : opt-in vérifié, garde sur la présence de fenêtres, restauration même si la configuration échoue, et capture de diagnostic avant nettoyage. Le nouveau passage attend l’APK de tests reconstruit.

Indépendamment des difficultés d’instrumentation, `host-save-red-1/` reproduit bien les trois défauts du service demandés en revue : 24 tests exécutés, trois échecs, aucune erreur de compilation, XML du 25 septembre à 03:38:03 UTC. Le service reste au gel `217220…`. Les échecs portent sur l’aide Réunion, l’absence d’erreur de sauvegarde récupérable dans le panneau et la publication d’une retouche plus récente que le brouillon durable. Ce dernier cas observe séparément le contenu des notes et celui du fichier. La correction produit et son GREEN restent à vérifier.

`host-save-green-2/` passe ensuite 24 tests sans échec ni erreur (XML à 03:51:51 UTC, Gradle 10 s). Le service est figé à `7b247b58d056ab617fd3f4ca6ae0c267665479d73d0b4fbf2646b59a1cc4c142`, le test à `c4486cb1ae783df3058879eba33fbe0e5cd35ad8f275862f475c4cf72199b42f`. La fixture de course synchronise explicitement le premier brouillon durable avant d’introduire la retouche, sans traiter encore le callback de publication. La première revue Astra confirme l’aide de pause, le retry hôte et les trois tentatives de flush bornées. La seconde relève le nettoyage du jeton de publication lors d’un changement de mode ou de contrôleur, ainsi que le signalement de son erreur dans la notification d’une note restaurée. Ces corrections sont rattachées à la finalisation du cycle de vie ; le raccordement global reste ouvert.

## T8b — préparation GitHub Actions approuvée

Le gel `app/build/reports/meeting/t8b-ci/implementation-freeze-01/` contient le workflow `a06998b3aecc08d136270f0001ef9cf8b122bef08b685522bfe616d9a0ff908c`, le vérificateur `eb08f13a4b9007866e931b17ddbc0592c5d729c2afa19d9a95d0f30202e281a3` et ses tests `d4d114c5e095e0ca6b3a0165ca877c8a164933996d35440ab7fa836ec0778d07`. Luna rapporte huit tests du vérificateur et dix-huit tests du contrôle ELF verts, avec les commandes dans `verification.txt`. Le RED initial constatait l’absence du nouveau script ; les assertions exigent aussi la raison de chaque rejet. Il s’agit d’APK synthétiques pour ces huit tests, pas du futur téléchargement réel.

Deux revues Astra portent sur ce même gel : sélection exclusive des variantes, maintien des moteurs historiques, identité Android et notices ; puis rejets ZIP, préparation sans écrasement, arrêt avant upload en cas d’échec et absence de release Réunion. Décision : préparation CI approuvée. Le workflow exécutera les huit tests du vérificateur en plus de ses contrôles existants, puis produira `dictai-meeting-test` avec APK, SHA256SUMS et metadata.json. La validation d’un APK final réel, le push autorisé et l’exécution distante restent à faire après la validation applicative.


## T7d.2 — publication documentaire : annulation et notification corrigées

Le RED complété `operation-token-notification-red-2/` exécute 27 tests avec trois échecs attendus : jeton retenu après retour à Dictée, jeton retenu après passage en fermeture, erreur d’une note restaurée masquée par le statut du micro. Le GREEN `operation-token-notification-green-1/` réussit 32 tests : service 27, documents 3 et cycle de vie 2. XML du 25 septembre à 04:10 UTC, JBR 21, build de 13 secondes, 28 tâches dont 5 exécutées.

Astra a vérifié les résultats et les empreintes, puis effectué deux revues distinctes du même gel : conformité de la publication et de la notification ; puis callbacks périmés, identité des opérations et remplacement de contrôleur. L’abandon ne retire que le jeton encore courant ; une ancienne complétion ne peut pas supprimer une opération plus récente. Une erreur documentaire reste visible même sans micro armé. Lot approuvé : service `5f4aaaad58847051b1d2cd5d751ad34f98078b85896f584e7bb4213e9c6b1c1c`, test `a5aa70e20a96be700ac14c9d8f0c290230820e7ae73ab0c7a8f7dfb78ceac483`. Les médias, la réservation Dictée et les transitions externes restent dans la validation intégrée.

Le troisième essai tactile, avec l’APK de tests `8c585da6db8f14cc0a236e2e298e3239e8458f2facd4e6f6f3d53bdef46e6a04`, atteint le menu et le panneau « Prête » sur l’APK baseline `b1886353…`. Le premier appui n’y démarre pas la session : un test, un échec, 13,917 secondes. Astra a inspecté la capture de diagnostic : quatre fenêtres détectées, pastille entièrement visible et non recouverte. Cette preuve concerne le raccordement du baseline ; le parcours complet doit encore passer sur l’APK corrigé.

Les APK locaux servent à l’instrumentation. Le script historique du formateur impose un hôte Linux ; ses deux bibliothèques seront construites par GitHub Actions et exigées par le vérificateur du livrable. L’APK livré sera téléchargé depuis ce build terminé, puis identifié et vérifié ; aucun APK intermédiaire incomplet ne sera présenté comme la livraison.


## T7d.2 — titre, actions documentaires et préparation du moteur réel

La capture du premier appui révèle aussi que le repositionnement du panneau rétablissait son ancien titre « Texte sans LLM ». Le test existant a été étendu pour appeler le vrai repositionnement. Son RED reproduit le défaut ; le GREEN `title-resize-green-1/` passe 27 tests, XML à 04:28:47 UTC, build de 12 secondes. Astra approuve ce correctif visuel borné après lecture du diff, du XML et des empreintes : service `a6f351e91d3fd8ed8a6c482e77251f7f1c652719c52eedeb91363e1bf060eaa5`, test `caa3bc2837896e4e068895df5121d9ba4a27a5210aa79dcb857b56d00a29c02b`. Le titre et sa description accessible restent propres à Réunion.

La fixture médias `144d12ba15e27d9afe4e238ac9845a274d3b64d2eb43ad6e6a49d5fbc2d4573b` exécute quatre tests dans `media-red-2/` : copie, photo et export échouent parce que les actions ne sont pas encore raccordées ; le refus pendant préparation ou mise en pause réussit. XML à 04:14:59 UTC, aucune erreur de compilation ou de setup. La tentative antérieure conservée dans `media-red-1/` s’arrêtait à la compilation sur un import de test manquant et ne constitue pas un RED métier.

Les modèles réels sont préparés sur emulator-5556 dans la génération `DE1BBC77099D40F3A27A775283711797`. Les tailles et SHA finaux correspondent aux deux fichiers épinglés : 741 548 352 octets / `a5c435f2…` et 107 012 128 octets / `08456d9e…`. Le transfert adb avait rendu la main avant la fin de l’écriture du second fichier ; le contrôle initial incomplet est conservé séparément, puis le fichier complet est vérifié avant publication. Aucun fichier partiel ne reste dans le paquet. Le brouillon standard est absent ; permissions du dossier 0700 et des modèles 0600. Preuves complètes : `t7d2-default-runtime/preparation/avd-5556-run-1/preparation-final.txt`.

Astra a corrigé le contrat de coordonnées de la fixture DefaultRuntime par la même méthode déjà éprouvée pour Gestures ; l’implémentation Luna est relue à `8a2c86f11e3e253caaf3c2d0ecc879633a1a53dd641c6b7f61ca7ac8fa039e1d`. Les deux APK intermédiaires sont reconstruits sous JBR 21 en 3 secondes, 66 tâches dont 8 exécutées, puis leurs empreintes sont relues par Astra : application `773ecd5f0d83ecb6aac3a886089e15f5218c1677ea06d858192cf46d73d94b18` (86 230 828 octets) et tests `924eefd9bdb3ed2ffa48e1c5c3b53965900f2b2c73d23745c04bf79ea491bd83` (1 762 395 octets). Dossier `t7d2-overlay/default-runtime-apk-1/`. La construction seule ne valide pas le micro ni les gestes ; les résultats d’exécution suivent ci-dessous.


## T7d.2 — premiers essais du service réel et des gestes corrigés

La fixture DefaultRuntime `8a2c86f1…`, exécutée avec le testAPK `924eefd9…` et l’application baseline `b1886353…`, atteint l’écoute avec les deux modèles vérifiés et le vrai AudioRecord. Elle confirme ensuite la mise en arrière-plan, la pause, la reprise du même contrôleur/session/run et une seconde pause. Le test échoue après 96,541 secondes parce que le dialogue final « Enregistrer la transcription ? » ne s’affiche pas après la commande de fin. Ce résultat valide ces étapes intermédiaires, pas la fin complète. Le runner et la trace figurent dans `app/build/reports/meeting/t7d2-default-runtime/default-runtime-baseline-run-1/`.

Sur l’application corrigée `773ecd5f…`, la fixture Gestures `cb6e3451…` démarre la réunion par un vrai appui sur la pastille, reçoit trois interventions simulées et ouvre les deux profils. Elle échoue après 11,187 secondes parce qu’elle cherche « Personne 1 » avant la fin d’ouverture du dialogue. Astra a inspecté la capture : « Intervenants (2) », « Personne 1 » et « Personne 2 » sont bien visibles. La correction de synchronisation attend désormais le libellé exact ou le fragment demandé, pendant huit secondes au maximum, puis conserve les mêmes assertions et gestes réels. Fixture `d40381fa8feb1298c5b2f1e9dd6e8c8a4a0c45d7c3bb65440ee0f10633ae781f`, relue et approuvée pour une nouvelle exécution ; aucun vert complet n’est revendiqué à ce stade.

La fixture de transition vers Dictée est figée à `01e7af058587fc61da46ccfd9d103eaad159b8a4d45c07c6c6a1924844590c3c`. Elle couvre la publication d’une note retouchée avec image avant détachement, un changement pendant un transfert retardé et un refus réel d’écriture préservant l’édition en mémoire ainsi que le précédent brouillon durable. Astra l’a relue ; son exécution RED puis GREEN reste à réaliser avec l’intégration du service.


L’essai DefaultRuntime répété sur l’application `773ecd5f…` échoue au même point après 101,683 secondes. La lecture d’OverlayService explique le défaut : le choix « Terminer la réunion » appelle la confirmation avant la fermeture du dialogue Actions ; la garde anti-dialogue la refuse donc. Le correctif doit livrer le choix après `onDismiss` et le retrait du dialogue courant, tout en conservant la garde contre les confirmations multiples. La fixture native reste inchangée ; ce second échec est un défaut applicatif, distinct de la course de synchronisation de Gestures.

## Confirmation de fin depuis le menu — 25 septembre, 05:38 UTC

Le défaut de production observé sur l’émulateur est reproduit par le vrai clic dans l’AlertDialog « Actions de la réunion » : `finish-dialog-red-2` exécute 28 tests, dont un échec sur le titre de la confirmation. La commande est désormais transmise après `onDismiss`, une fois le dialogue retiré de l’ensemble des fenêtres suivies. La livraison reste unique ; l’annulation fournit une sélection nulle. La surcharge historique à un paramètre reste disponible.

Le passage `finish-dialog-green-2` réussit les 28 tests, sans erreur ni test ignoré, sous JBR 21 en 13 secondes. XML daté de 05:35:49 UTC. Astra a contrôlé le code et le test comportemental : service `1be2d10a580f7d7a71143ae224696bfa2a0858eceb57e5fa1c8de0d48a5eac16`, test `85a230a57c7a329091208b8488d7c2e1e658d359b9c9fd4a1bb60312d288ec04`. Approbation limitée à cet enchaînement des dialogues ; les médias et le changement de mode restent en intégration.

Les APK locaux d’instrumentation ont été reconstruits en trois secondes, 66 tâches : application `961bd9a41ba4f8f3252e0ac00ecd60d3f35620d67e3c2447424886818abb116d`, instrumentation `460adce187fcade2c3aafce1e5e7ff0a3459e58f30e3094720bb68b2664291f2`. Astra a recalculé ces empreintes. L’application porte `com.uhama.whisperpin.meetingtest`, version 35 / `0.9.6-dictai-meeting-test`. Elle comprend le correctif d’attente des dialogues de la fixture Gestures ; son exécution Android reste à confirmer. Ces APK locaux ne constituent pas les APK finaux de distribution, qui seront construits avec les deux bibliothèques LLM sur GitHub Actions.

## Derniers contrôles d’intégration — 25 septembre

Le RED `dictation-lease-red-2` compile et exécute trois scénarios réels d’OverlayService : les trois échouent sur l’absence de réservation Dictée au démarrage, pendant l’annulation et pendant la fermeture du moteur résident. XML daté de 05:42:32 UTC, build de 13 secondes. Le premier essai s’arrêtait à un type Application incorrect dans la fixture, sans exécution de test ; ce défaut de fixture a été corrigé avant le RED. La correction applicative du lease est en cours.

## Intégration du 25 septembre — lease Dictée et actions médias

Le checkpoint `t7d2-overlay/media-red-1/` daté de 06:21 UTC exécute 15 tests sous JBR 21 : les trois scénarios de réservation Dictée passent ; neuf des douze scénarios médias échouent encore. Les échecs portent sur les actions photo/capture/déplacement/retrait, les projections vides et la revalidation après écriture. Ce checkpoint a réutilisé un ancien nom de dossier ; les références antérieures à `media-red-1` ne désignent pas ce nouvel instantané. Les essais suivants utilisent des noms distincts.

Le service de ce checkpoint a pour empreinte `2b0ce83911b7fd783b5464ec16d86aee60df6318d758ca15f76988159fcf5ab3`. Les trois tests verts ne valent pas approbation finale : la revue exige encore de refuser un moteur résident périmé, de suivre le worker de nettoyage d’une reprise échouée et de revalider la réservation avant de rouvrir AudioRecord. La bascule documentaire vers Dictée et le raccordement des médias restent en cours.

L’APK de tests instrumentés `873da396c5a3db1a8970f6d413da878928c91656f255b43636c149671ba361b9` a été reconstruit en trois secondes et son empreinte recalculée par Astra. Il cible l’APK applicatif local FINISH `961bd9a…`, sans nouvelle compilation de celui-ci. Le scénario Gestures atteint le renommage puis échoue en 27,364 secondes sur la vérification du passage retouché dans le brouillon ; sa sélection de deux interventions nommées Sophie est en cours de diagnostic. Le scénario DefaultRuntime démarre le vrai moteur et le micro, passe l’Activity en arrière-plan puis échoue en 75,501 secondes en attendant la première pause. Ces échecs restent ouverts ; aucun succès global ni APK final n’est déduit de ce build.


Sur les APK `961bd9a4…` / `460adce1…`, Gestures atteint le renommage et saisit Sophie. Il échoue après 23,507 secondes sur la recherche exacte de « Valider ». Astra a inspecté `finish-dialog-run-1/gesture-captures/meeting-overlay-gestures-a7dbe449-7553-4623-988a-f9aef1f06f3c/failure-diagnostic.png` : le bon dialogue et son bouton « VALIDER » sont visibles. Le thème Android transforme la casse. Les deux fixtures Android utilisent désormais une égalité complète après trim, insensible à la casse, sans remplacer l’égalité par une recherche partielle : Gestures `706e3469274af577d0e03d0c9c2a1c2d952cd1d8221f37d5b665c67e2393fd06`, DefaultRuntime `cd2021d46d628d377fd2af37ca75a588df8acc86119ae7c7265d5e05692a389e`. Cette correction bornée est relue ; le nouveau passage instrumenté reste nécessaire.

## Vérifications ciblées du 25 septembre à 07 h 09 UTC

L’incrément copie/export passe six tests dans `t7d2-overlay/media-copy-export-green-20260925-02/` : XML du 25 septembre à 07:01:05 UTC, build de 11 secondes. Il utilise la projection structurée visible, conserve le presse-papiers lorsque celle-ci est vide, refuse un callback devenu invalide pendant une transition et n’ouvre pas d’export vide. Une note connue conserve ses suppressions volontaires. Le service figé porte l’empreinte `3cae10d60091c4355de37685b77ad46b14216eedc78272c010bfe738772dccf8`, la fixture médias `9fbc3c38b9927f9843d33b077f6a7f489afa7bf31e50b6321af715f66b2b6d34`. Le contexte documentaire avec moteur bloqué et les autres actions médias restent à raccorder.

Les cinq scénarios du lease Dictée passent dans `t7d2-overlay/dictation-lease-repeat-green-20260925-02/`, XML à 07:09:23 UTC. Même service `3cae10d6…`, fixture `f8631871cda232797b7d6ae7622e4dfbdf198208ffca31f7e7a298b740b416c5`. Le timeout intermittent précédent provenait de la fixture : Robolectric rappelle son fournisseur à chaque lecture, qui créait à tort un nouveau verrou ; la source est désormais unique par identité AudioRecord. Aucun délai ni règle de fermeture de production n’a été assoupli. Astra a contrôlé les empreintes et effectué les deux revues des protections contre le moteur périmé et la reprise après blocage du coordinateur. Le scénario précis de nettoyage d’une reprise échouée reste à vérifier.

L’image de diagnostic Gestures `a8abbd7bed8ac2fced89c360dc954a4f76fd8b52681ee8bd764e918e17d99bfb`, inspectée par Astra, montre le passage retouché après la deuxième intervention : le test avait choisi la troisième intervention de Sophie. La fixture cible maintenant le texte original exact et défile dans la liste du panneau. L’APK de tests `9211ab089350f187b5d512d04e936f3a64063f85446f0e0f1063354d7b29e58f`, compilé en deux secondes, contient ce correctif et des diagnostics d’état pour le prochain essai du vrai moteur. Il ne constitue pas un nouvel APK applicatif ni une livraison.

## Vérifications du 25 septembre : fermeture Dictée et moteur Réunion réel

Le cas de reprise Dictée échouée reproduit une fermeture trop précoce du moteur : six tests exécutés, cinq réussis et un échec attendu dans `dictation-lease-red-resume-worker-20260925-01/` (XML à 07:24:21 UTC). Le worker de libération du recorder est désormais conservé pour que la destruction du service l’attende. Le GREEN `dictation-lease-green-resume-worker-20260925-01/` passe six tests, XML à 07:26:44 UTC, build de dix secondes. Astra a relu les preuves, recalculé les empreintes et conduit deux passes distinctes du gel : service `c98d70ddf2ba13b59407133e106d87e4d2eb219110be1f8cc7f4a79aa91cb6ed`, fixture `3fcd689998f22d003b7d79a2108a54aa13b5174f0c2b4fa9bb6d88b61bb6d32b`. Lot de réservation et de fermeture Dictée approuvé ; le transfert documentaire externe reste en cours.

Sur l’application locale `961bd9a4…` et le testAPK `9211ab08…`, `MeetingOverlayDefaultRuntimeAndroidTest` passe un test en 61,936 secondes. Il utilise les deux modèles épinglés, le JNI et AudioRecord réels : écoute, arrière-plan, pause, reprise du même contrôleur/session/run, deuxième pause, confirmation de fin, brouillon durable et libération des ressources sans blocage du coordinateur. Le flux de cet émulateur ne produit aucun tour ; le test vérifie alors l’absence de note vide. Cela ne valide pas la reconnaissance de voix humaines. Preuves : `t7d2-overlay/native-test-apk-20260925-01/runs/default-runtime-run-1/`, runner SHA `47866bd04c034c3be4578c3bf38538c0b1b100c3724070a0074694c2e371c4bf`, journal du processus courant SHA `88509de0fe3a432160bd2b4dee7700414f4c78115a7c3a61fe52be0a666bcfc4`.

Le nouveau parcours Gestures atteint le bon tour édité mais échoue après 28,786 secondes : le brouillon observé a bien la bonne session et une retouche du premier tour. Astra inspecte le PNG `bc5d6e37dcdb55a04d408f26b748a25c525ebb4d6f8708aff96d33ae9aee4333` puis identifie une cause dans l’alignement des mots : le point de l’ancienne phrase peut correspondre au mot « matin » par recouvrement temporel, laissant le nouveau point comme suffixe. Une reproduction JVM exacte et la correction de cette correspondance sont demandées ; l’attente Android n’est pas affaiblie et aucun succès de sauvegarde exacte n’est annoncé pour ce passage.

## Vérification commune du 25 septembre à 08 h 28 UTC

Le lot `t7d2-overlay/photo-transition-punctuation-20260925-01/` compile et passe sept tests en treize secondes : quatre transitions documentaires, une préparation de photo et deux corrections avec ponctuation horodatée. Astra a lu les XML, le journal et les empreintes identiques avant/après : service `6942c8cadb723cbe8e3cea726fe8732c032e81584ab2952bb9fbc447d1b3abf3`, fixture médias `ce5be76be85e4ed9b966259a028193ee9e2051d1c4e10bdf254d61681f5db607`, fixture mode `a9de0d49ffc0509545efa04bc9a3022de299eb0bf6f1d3c6cb5f0eb9cd78a759`, fixture de retouche `b95880a7e33ee7a634aa1cddaefcfa0ee963ad2bcdeedd7a58bde30f45ffe6dc`.

La photo d’une note Réunion vide possède un tour documentaire durable dans le brouillon et la note avant l’ouverture de la caméra. Les transitions testées conservent retouches et images, restaurent le contexte Dictée sans retour d’export périmé, préservent le document lors d’un refus d’écriture et respectent un nouveau choix Réunion pendant le flush. La revue conserve deux corrections bornées : une notification d’un moteur déjà bloqué ne doit pas détacher une note documentaire ; un retour à Réunion pendant la restauration finale doit rouvrir le document sauvegardé.

L’ancre `c41913f31a6d6648f6265b4cd9b5f3bcd140750b011061ef64e4660af337b0c2` refuse désormais d’aligner un signe de ponctuation sur un mot lexical. Les deux cas reproduisant le flux Android sont verts, sans modification des attentes. Les cas sans horodatage et avec ponctuation accolée, la livraison complète des médias et le nouveau parcours instrumenté restent à terminer avant la publication.

## Retouches et transitions vérifiées le 25 septembre à 08 h 54 UTC

Le lancement `mode-transition-green-screenshots-red-20260925-01/` recompile les sources Kotlin sous JBR 21 et exécute 49 tests en 22 secondes : 36 tests de réconciliation, cinq d’ancre et six de transition réussissent. Les deux tests de capture d’écran échouent encore sur leur attente de préparation ; le lancement global reste donc en échec. Les résultats des suites ne sont pas présentés comme une validation globale de l’APK.

Le gel `anchor-wordless-green-20260925-01/` rassemble les 41 tests du domaine et leurs sources : ancre `d6f8e03e38c2d525f736a6c2a17cc62cc90cd034ead5e24df0e7c3918ebaa287`, réconciliation `1d5af5f72637f4637f6ca21e0543a0219e5037e64ba71b48e91c8b30050cad44`, fixture `33969a5c0d5cceb11b82d220cb3cd7e22d478ce07a6143993f91e7aecccd8b69`. Astra a recalculé les empreintes et mené deux revues distinctes de ce gel. La première confirme le point terminal protégé, les continuations réelles et la suppression volontaire. La seconde vérifie les cas ambigus, le retour `UNRESOLVED`, les limites de traitement et l’absence de nettoyage global de la ponctuation. Lot de retouche approuvé ; le parcours instrumenté sur l’APK intégré reste nécessaire.

Le même lancement fournit les six tests verts du transfert documentaire, à 08:53:51 UTC. Le service figé est `da378c22555275f9ef0cb1dbd200c161f99e5b3655b08fc5143f190e8328ded4`, la fixture `0d261e095a16c6becd7b917556481a8c4059d04bbcb2bf8db6ffab611220b1d3`. Deux revues Astra de cet instantané sont favorables : sauvegarde structurée et restauration du contexte Dictée ; puis callbacks périmés, choix successifs, moteur bloqué et refus de sauvegarde. Une notification tardive d’un préchargement invalidé ne détache plus le document ; une nouvelle sélection Réunion pendant la restauration rouvre la note sans moteur ni micro. Le raccordement de livraison des images demeure séparé de cette approbation.

## Médias du service — résultats ciblés du 25 septembre

À 09:47:05 UTC, le passage de cinq tests compile et confirme quatre comportements : préparation d’une capture d’écran documentaire sans moteur, livraison d’une photo acceptée dans la réunion structurée, déplacement d’image entre deux corps, et retrait avec conservation des fichiers jusqu’à la sauvegarde durable. Le cinquième test atteint ses assertions de retouche et d’ancre, puis échoue en recherchant la note avec un identifiant mal extrait par la fixture. Le passage global reste en échec.

La fixture utilise ensuite le véritable identifiant de session. À 09:53:56 UTC, le test de capture pendant l’écoute passe : le reconnu évolue de « Bonjour » à « Bonsoir », mais le corps protégé et l’ancre restent associés à « Bonjour ». Le même lancement inclut une nouvelle reproduction du réessai de déplacement ; elle échoue dans la préparation de panne disque, avant d’exercer le réessai. Il ne s’agit donc pas encore d’une reproduction fonctionnelle de ce défaut. Le snapshot préalable `media-screenshot-retry-attempt-1/source-snapshot/` conserve le service `eb3853e438def386c178c82ccff84fa84103a2e5639148624d17439a3d4515c4` et la fixture `c1fc7252d8308475d6de328607cc90c40c887b1f77917c44372d85955b82072d`.

La revue Astra demande la persistance effective d’un déplacement déjà appliqué en mémoire après un premier refus d’écriture, ainsi que la revalidation du contexte avant une publication tardive. La récupération des images sans panneau vivant, l’annulation et la reprise liée au même run restent à intégrer. Ces résultats ne constituent pas encore la validation de livraison de l’APK.

La reproduction corrigée à 09:59 UTC exécute les seize tests médias : quatorze passent, deux échouent sur le réessai d’un déplacement déjà appliqué et sur un retrait tardif après bascule de mode. Après correction, la même fixture `56f9b3fb181d406b66c8e5ce9caf6f7efc6ca5f12a0d3e61ecd5a7b30a41f026` passe les seize tests, sans erreur ni test ignoré, sur le service `7fcdbcd1da786c65977c8a8ffe3fabae995fdde8ac04b765eafddde310a3a398`. Le build dure douze secondes et l’exécution XML 7,613 secondes. Les sources préalables et l’XML sont conservés dans `media-retry-callback-green-attempt-1/`.

Astra a réalisé deux revues distinctes de cet instantané : conformité des actions et du parcours des menus, puis persistance, callbacks devenus périmés et réessais. Un réessai sauvegarde le document courant sans rejouer un déplacement déjà appliqué. Un callback de retrait devenu invalide ne publie ni ne supprime les fichiers. Le lot médias est approuvé ; la récupération sans panneau vivant et les essais Android de l’ensemble restent séparés de cette validation ciblée.

## Récupération des captures — 25 septembre à 10 h 46 UTC

La classe médias passe désormais vingt tests, sans échec, erreur ni test ignoré : XML à 10:46:52 UTC, durée 7,935 secondes, build de onze secondes. Le service est figé à `eca5204cc325eb7b2d117384fde9322c5adb90da97010b92a69f9e4d00770ceb` et la fixture à `6e65c1fcd353b671ce6ec103d0487681e3230aaab7cc8f75e03ca939bc30b0d3`. Une première tentative de compilation avait échoué sur une propriété appelée sur un identifiant texte ; aucun test n’avait alors été exécuté. Après correction, le cas de brouillon a d’abord passé un lancement de dix-sept tests, puis les trois cas complémentaires ont rejoint ce passage de vingt tests.

Le service recréé récupère le brouillon structuré le plus récent, même si la note contient une ancienne version. Sans brouillon, il utilise la note correspondante. Il conserve un brouillon d’une autre réunion et laisse le lot en attente. Une réservation invalide reste annulable, même sans image ; ses fichiers déjà acceptés sont conservés. Ces opérations ne créent aucun panneau, contrôleur audio ni session native. Le test de brouillon reprend ensuite la propriété du même chemin pour vérifier sa libération.

Deux revues Astra du même service figé sont favorables : récupération et parcours d’annulation, puis propriété du writer, callbacks sur le thread principal, destruction, revalidation de la réservation et absence de reprise implicite du micro. L’intégration documentaire et médias est approuvée. Les builds globaux et le nouvel essai Android restent à effectuer.

## Première vérification intégrée

Le premier build normal exécute 974 tests en 36 secondes et échoue sur cinq appels de réflexion d’`OverlayServiceMeetingRobolectricTest`. Leur helper cherche encore les deux anciens paramètres de `startNewMeetingAfterSaving`, alors que le transfert de mode a ajouté un troisième paramètre nullable. Ces cinq scénarios s’arrêtent avant leurs assertions ; il ne s’agit pas d’un build vert. L’assemblage APK n’a pas été atteint. La correction demandée se limite au helper de test, en conservant toutes les assertions. Journal : `app/build/reports/meeting/final-integrated/normal/gradle.log`.

La deuxième tentative ne conserve qu’un échec : une attente de dialogue vidait le looper principal sans attendre le worker de sauvegarde. Les deux tests concernés utilisent maintenant l’attente bornée existante de cinq secondes et le titre exact du dialogue, sans relâcher leurs assertions ni modifier la production. Fixture finale : `99c38515adc961c22803bb3b3b5e7796f2a01af408ac224e161ca5e11b2098bd`.

La troisième tentative normale réussit sous JBR 21 : **974 tests sur 127 XML, zéro échec, erreur ou test ignoré**, puis `assembleDebug` réussi. L’APK normal local fait 84 798 416 octets, SHA-256 `3e31f8ea4e3089cbf041a422c73328c09eab39829ea97877d5e7d6060a995c8e`. Les rapports et cet APK sont conservés dans `app/build/reports/meeting/final-integrated/normal-attempt-3/`. Ce fichier sert au contrôle local ; la livraison demandée reste l’APK Réunion construit et vérifié par GitHub Actions.

La variante `-PmeetingPrototype=true` réussit à son tour : **974 tests, 127 XML, aucun échec, erreur ni test ignoré**, puis assemblage de l’application et des tests Android, en 43 secondes. Identité confirmée par les métadonnées Gradle : `com.uhama.whisperpin.meetingtest`, versionCode 35, versionName `0.9.6-dictai-meeting-test`. APK applicatif local : 84 798 464 octets, SHA-256 `c8f20dc3e07b00e5223f6b3636645657b0e813ebd4e9a6f78f89eaf312004670`. APK de tests : 1 774 875 octets, SHA-256 `fe55b0b819e79afb961a567e8d57f9bf579adfb9ea0c7b438483b2bbb8ec1bba`. Ces fichiers sont figés avant installation ; leur construction ne remplace pas les résultats d’instrumentation ci-dessous.

## Menus Android intégrés et synchronisation du test de retouche

`MeetingOverlayImageMenusAndroidTest` réussit sur cet APK : un test en 9,211 secondes. Il actionne les menus du vrai service, déplace une image synthétique après le passage de Karim, relit la note durable, puis retire l’image et contrôle ses fichiers. Aucun téléchargement, moteur ni microphone n’est ouvert. Astra a inspecté les six [captures natives intégrées](ui-renders/integrated/README.md). Fixture : `b3896a37a026f9154717656a0c4d014894fb042215072d5e5944d18b527ba54a`.

Le premier parcours `MeetingOverlayGesturesAndroidTest` échoue après 16,998 secondes en recherchant le champ exact de Sophie. Sa capture montre « Sophie » et « La séance aura lieu mardi. » ; le helper cessait toutefois de rechercher si un défilement était momentanément impossible ou si le scroller n’était pas encore accessible. Le correctif reste limité au test : sonder pendant le budget existant de six secondes, avec au plus huit tentatives de défilement espacées, en conservant l’égalité du corps et la description « Paroles de Sophie ». Aucun autre passage ne peut satisfaire la recherche. Fixture corrigée et relue : `6d5847aa57677bd811cc241561bea46faf7eeebe0d47ab012c40b8a9740faf34`. Le rejeu de cette fixture est nécessaire avant de conclure au succès du parcours.

Le rejeu avec l’APK de test `15eed543b41d20a724562ddcd2c78c59ebace9b4b5128e03520454c43388d634` échoue plus tôt, après 23,274 secondes, en attendant le prénom. Sa capture montre encore « Personne 1 ». Le helper de renommage recherchait tout champ éditable parmi les fenêtres accessibles ; il pouvait ainsi cibler le panneau avant l’apparition du dialogue. La fixture finale attend désormais le titre exact « Renommer Personne 1 », choisit le champ et le bouton dans cette fenêtre, relit un nœud frais contenant « Sophie », valide puis contrôle le participant du canal 1 dans l’état réel du service. Le nom n’a plus à être déjà visible dans la portion défilée du panneau avant la recherche du passage exact. Ce correctif de test, SHA-256 `6a14f7019e9cfcb9bdbf2cade4c1faffe67e6db4e0c501f2024ce16ac69a9a49`, est relu par Astra ; le code applicatif reste inchangé.

La troisième tentative passe les assertions de renommage et atteint l’édition du corps, puis échoue après 18,574 secondes : le helper attendait le focus immédiatement après un toucher. La capture native montre le panneau revenu à sa position basse après fermeture du clavier. La correction suivante doit attendre la stabilité des coordonnées du passage exact avant de le toucher, puis son vrai focus avant la saisie. Elle conserve le budget de recherche de six secondes, les critères exacts et les assertions documentaires ; elle ne force ni le focus ni une commande du contrôleur. Le succès de cette étape reste soumis au rejeu instrumenté.

La quatrième tentative réussit : **un test en 136,511 secondes**. Le helper final `bc6bb637c2faafebb6fb6161418c33137683ded7e17063d30a1b34c15b3b66e4` attend un rectangle stable pendant 200 ms dans le même budget de six secondes, puis le champ réellement focalisé. Astra a relu ces conditions avant compilation. Le scénario conserve les vrais gestes de pastille et la vraie édition : Sophie remplace Personne 1 ; « jeudi » reste intact après la révision reconnue « mardi matin » et dans la note durable ; la pause conserve les profils, la reprise conserve la session, le dialogue ne déclenche aucune reprise implicite, puis un nouvel appui ouvre une session sans profils. Les voix et le microphone de cette fixture sont simulés ; les contrôleurs, le writer, les notes, les fenêtres et les gestes sont réels. L’application reste l’APK `c8f20…` ; seul son APK de tests a été reconstruit. Le résultat complet est conservé dans `final-integrated/device-runs/gestures-replay-attempt-4/runner-raw.log`.

Les cinq captures du parcours de gestes réussi ont été inspectées par Astra et intégrées au [rapport visuel](ui-renders/integrated/README.md#gestes-renommage-retouche-et-sauvegarde). Le prénom, la correction au jeudi, la confirmation en pause, la note terminée et la nouvelle session vide y sont lisibles. Les captures de tentatives échouées restent séparées de ces preuves finales.

## Moteur réel sur l’APK intégré

`MeetingOverlayDefaultRuntimeAndroidTest` réussit en **53,02 secondes** sur l’émulateur API 36 arm64. Les deux poids réels ont été transférés dans une génération privée unique, puis vérifiés en taille et SHA sur l’appareil. Le test traverse le magasin de modèles, la réservation, le JNI, AudioRecord et le vrai service ; il contrôle démarrage, passage en arrière-plan, pause, reprise de la même session, seconde pause, fin et brouillon durable. L’émulateur silencieux ne produit pas de paroles : ce succès ne mesure pas la qualité de transcription d’une réunion.

Le premier lancement utilisait par erreur le sous-package `.meeting` pour cette classe, qui appartient directement à `com.kafkasl.phonewhisper`. Il échouait à l’initialisation avant le corps du test ; cette invocation est conservée dans `default-runtime-attempt-1/`. Le résultat vert lu par Astra provient du lancement corrigé, `app/build/reports/meeting/final-integrated/device-runs/default-runtime-attempt-2/runner-raw.log`, sans modification de l’APK applicatif.

## Revues d’intégration du code figé

Le manifeste `final-integrated/final-source-freeze/source-manifest.tsv` contient 363 fichiers et porte le SHA-256 `11d2fd13ecad824b9c65747190651676b6df267d8bb0021c50497e93b5f44d64`. Il inclut notamment le service `eca5204…`, le contrôleur `2fb487…`, le coordinateur `40047f…`, le workflow `a06998…` et le vérificateur d’APK `eb08f1…`. `git diff --check` passe.

Astra a effectué deux lectures distinctes de cette intégration, en complément des deux revues de chaque lot complexe. La première couvre le contrat des gestes, la pause/reprise, les profils, les retouches et l’identité de la variante de test. La seconde couvre la propriété des ressources natives, les callbacks périmés, la fermeture du lecteur avant libération, la sauvegarde durable, la conservation des versions inconnues et le contrôle de l’artefact CI. Ces revues de production sont favorables. Elles ne remplacent pas le dernier parcours Android ni la vérification de l’APK construit par GitHub Actions. Les correctifs ultérieurs de sélection des champs sont limités à la fixture instrumentée et reçoivent leur propre relecture.
