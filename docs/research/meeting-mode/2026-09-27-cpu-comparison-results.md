# Débit de l’identification des intervenants

## Question examinée

La capture de Réunion test6 montre des noms attribués de plus en plus tard, tandis que Handy continue à transcrire. Le test distingue le coût du moteur de voix de la concurrence avec Handy. Les mesures ci-dessous sont celles d’un émulateur Android ARM64 à quatre vCPU et 4 Gio ; elles ne mesurent pas les performances du Poco F7. Aucun Poco n’était connecté lors de cette série.

## Protocole et limites

Le même extrait français de 60 secondes est lu depuis un PCM mono 16 kHz de 1 920 000 octets, envoyé par blocs de 20 ms à cadence réelle dans `MeetingEngine`. La capture continue indépendamment de l’inférence ; les files ne sont pas contournées pour embellir le résultat. Empreinte du PCM : `cc3394e33b569b7d0d84a09827b7a3389a37d3c1b667cfbaf18a1322296dedf1`. L’audio privé et les transcriptions ne sont pas versionnés.

Les modèles et la géométrie native restent identiques pendant la série CPU. Le chargement est mesuré séparément. La borne de rattrapage est de 180 secondes après la fin de l’entrée audio. Les durées d’acceptation désignent du temps mural dans les appels natifs, pas du temps CPU strict. Un test qui termine correctement peut néanmoins être trop lent pour une réunion.

La progression stable des voix seules est échantillonnée toutes les cinq secondes d’audio consommé. Son retard affiché ne doit pas être comparé directement au pont concurrent, qui actualise cette progression plus souvent. La couverture finale et le coût natif sont exploitables séparément.

## Série CPU, blocs natifs de 1,04 seconde

| Cas | Résultat sur les 60 s | Rattrapage après la capture | Temps mural dans les appels natifs d’acceptation |
| --- | --- | --- | --- |
| Handy seul | 60 s consommées, texte final produit | 6,19 s | 31,44 s |
| Voix seules, 1 thread, arrière-plan | 43,76 s consommées à la borne ; 16,24 s encore en file | Échec de la borne de 180 s | Pas de total final |
| Voix seules, 2 threads, priorité normale | 56,24 s consommées à la borne ; 3,76 s encore en file | Échec de la borne de 180 s | Pas de total final |
| Voix seules, 4 threads, priorité normale | 60 s consommées ; 6 001 trames stables | 114,34 s | 167,36 s, soit 2,79 fois la durée audio |

Handy seul publie le premier texte après 2,62 secondes d’entrée audio. Son texte natif final contient 1 152 octets, SHA-256 `575b7ce4dad94285d10ef54f7ab3e88e3c8985fd20cc82b5f8e049404d1dd2ab`. Cette empreinte servira à distinguer une régression du texte d’un simple changement de regroupement par personne.

Le cas à quatre threads termine sans perte d’échantillons : 60 000 ms capturées et consommées, 6 001 trames sur 6 001 stables, couverture native 60 010 ms, dans la tolérance d’un bloc PCM. La mémoire PSS maximale observée est d’environ 224 Mio. L’échec des autres cas est un dépassement de délai de traitement, sans crash ni manque de mémoire observé.

Les mesures à deux et quatre threads changent aussi la priorité par rapport au réglage de production. Elles comparent donc des configurations complètes et n’isolent pas l’effet de chaque paramètre. La comparaison ne permet pas de conclure que toutes les combinaisons CPU possibles ont été testées.

## Interprétation

L’identification seule n’arrive pas à suivre le direct avec les configurations testées. Handy ne peut donc pas être la seule cause du retard. Revenir à l’ancien pont de réunion ne supprimerait pas le coût du même modèle d’identification.

La lecture du runtime épinglé montre que chaque bloc recalcule les probabilités sur la mémoire des voix, les trames récentes et le nouveau bloc. Le coût augmente pendant le remplissage de cette mémoire. Cette structure explique un axe d’optimisation possible ; les mesures ne constituent pas un profilage prouvant que chaque milliseconde de retard vient de ce recalcul.

La documentation NVIDIA distingue bien la durée audio attendue pour former un bloc du temps nécessaire à son calcul. Les profils à faible latence ne garantissent donc pas à eux seuls un fonctionnement en temps réel sur un CPU Android. Source : [fiche officielle Nemotron 3 Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization#setting-up-streaming-configuration).

## Blocs de huit secondes : voix seules

Le cas deux threads/priorité normale avec `chunkFrames=100` termine la même minute d’audio avec 9,48 secondes de rattrapage, contre un dépassement de la borne de 180 secondes pour ce même budget CPU avec la géométrie native. Il conserve les 60 000 ms de PCM et produit 6 001 trames toutes stables, couvrant 60 010 ms. Les appels d’acceptation prennent 38,63 secondes au total, puis la finalisation native 6,12 secondes. Le total du test, chargement compris, est de 71,84 secondes.

Le coût n’est pas uniforme : 10,02 secondes d’acceptation pour la première moitié du contenu, puis 28,61 secondes pour la seconde. Une fois la mémoire remplie, les trois derniers blocs complets demandent environ 7,1 secondes chacun pour huit secondes d’audio. La marge est donc faible, même si le débit moyen est satisfaisant. La PSS maximale observée est d’environ 183 Mio.

Les attributions progressent par paliers : le dernier point échantillonné couvre 32 secondes au temps source 40 s, 40 secondes au temps 50 s, puis 48 secondes au temps 60 s. L’échantillonnage des voix seules toutes les cinq secondes contribue à ce retard visible dans les mesures. Aucun intervalle audio n’est déplacé pour compenser le délai de calcul.

Ce résultat a justifié l’essai concurrent avec Handy et le contrôle de précision sur AMI présentés ci-dessous. Pris isolément, il ne justifierait pas un nouveau réglage de production : la charge de Handy peut absorber la marge restante et les plus grands blocs peuvent modifier les attributions. Cas mesuré : `cpu-diar-only-19889763`, fichiers `runs/diar-only-2normal-100-60s-*`.

## Concurrence avec Handy, deux threads à priorité normale

Le pont concurrent termine correctement : 60 secondes consommées par les deux chemins, 6 001 trames stables, couverture 60 010 ms et aucune file restante. Le texte Handy final est strictement identique au contrôle, avec la même empreinte SHA-256. Le premier texte arrive à 1,68 s et la première mise à jour attribuée à 10,15 s. Le retard des voix atteint au maximum 12,1 s pendant la capture et le rattrapage final dure 12,33 s.

En revanche, le coût Handy augmente : 65,89 s dans les appels natifs d’acceptation, contre 31,44 s dans le premier contrôle isolé. Sa file audio atteint 9,28 s, contre 3,68 s auparavant. Le bon point à 55 secondes — 1,32 s en attente pour Handy — ne suffit pas à qualifier l’ensemble d’instantané. La mémoire PSS maximale observée atteint environ 1,31 Gio.

Ce résultat conserve le contenu et améliore les voix, mais ne clôt pas le critère de réactivité du texte. Un contrôle Handy seul dans l’ordre inversé et un essai concurrent à priorité d’arrière-plan complètent donc la comparaison. Cas : `cpu-both-20139869`, fichiers `runs/both-2normal-100-60s-*`.

## Vérification de l’attribution sur AMI

Sur l’extrait public AMI EN2002d de 60 secondes, le profil deux threads/blocs de huit secondes obtient un DER par trame de 23,72 %, contre 25,18 % dans le reçu historique du même modèle au profil natif. La confusion entre locuteurs est presque identique : 2,69 % contre 2,66 %. Cette comparaison limitée ne prouve pas une amélioration générale ; elle ne montre pas de régression globale sur cet extrait.

Le résultat contient toujours trois locuteurs segmentés pour quatre dans la référence. Le quatrième n’intervient dans la référence que par deux passages de 0,36 et 0,37 seconde, dont un chevauchement ; la détection de ces très brèves interventions reste donc une limite de cet essai. Le score porte sur les segments issus des probabilités du modèle, pas sur le classificateur par mot de l’interface.

La fin de flux couvre toutes les 6 001 trames et les stabilise. Les seuils de segmentation, le scorer et les fichiers AMI restent ceux du test existant. Reçu : `runs/ami-2normal-100-60s-summary.txt`. L’audio privé de la vidéo n’a pas d’annotation de référence permettant de calculer un score équivalent.

## Contrôle dans l’ordre inversé et priorité d’arrière-plan

La seconde passe Handy seul conserve exactement le texte final et traite les 60 secondes, mais sa file atteint 19,1 secondes, contre 3,68 secondes au premier contrôle. Les temps d’acceptation natifs totalisent 49,76 secondes et se répartissent de façon très inégale : 40,75 secondes pour la première moitié du contenu et 9,01 secondes pour la seconde. Le premier texte arrive à 3,76 secondes. Le test finit par rattraper son retard, avec seulement 0,28 seconde de finalisation après la capture. Cette variabilité empêche d’attribuer avec certitude l’écart de la passe concurrente à la seule concurrence entre modèles. Cas : `cpu-handy-only-20410958`, fichiers `runs/handy-only-60s-reversed-*`.

Le profil concurrent à deux threads et blocs de huit secondes, mais à priorité d’arrière-plan, termine également avec le même texte et toutes les trames. Il est cependant écarté : la file Handy monte à 25,36 secondes, le retard des voix à 36 secondes et le rattrapage final dure 62 secondes. Aux temps source 30, 45 et 60 secondes, les voix couvrent respectivement 16, 24 et 24 secondes. Il s’agit d’un retard croissant, pas d’un blocage définitif. Fichiers : `runs/both-2background-100-60s-*`.

## Vérification prolongée : 154,90 secondes

La passe finale concurrente conserve deux threads, la priorité normale et les blocs de huit secondes. Elle utilise l’audio de la vidéo privée à partir de 15 secondes, jusqu’à la dernière limite complète de 20 ms : 154,90 secondes, soit 4 956 800 octets. Son préfixe de 60 secondes est strictement identique à l’extrait comparatif. SHA-256 du fichier complet, vérifié localement et sur Android : `dffd60878341789245ce7bc42a0636fdf2b4e187c7e9f6734ee7f66260fb7ed6`.

Le test réussit en 175,27 secondes au total. Les 154 900 ms sont capturées et consommées par les deux chemins ; les 15 491 trames sont toutes stabilisées, couvrant 154 910 ms dans la tolérance d’un bloc audio. L’attribution reste active jusqu’au bout et ses files sont vides à la fermeture. La capture dure 155,12 secondes et le rattrapage final 16,32 secondes, dont 9,11 secondes de finalisation native des voix.

| Temps source | Audio consommé par Handy | Audio couvert par les voix stables | Retard des voix |
| --- | --- | --- | --- |
| 60 s | 59,28 s | 56 s | 4 s |
| 90 s | 89,52 s | 80 s | 10 s |
| 120 s | 119,76 s | 112 s | 8 s |
| 150 s | 149,98 s | 144 s | 6 s |

Le premier texte arrive à 1,53 seconde et la première attribution à 8,51 secondes. Pendant la capture, la file audio Handy atteint au maximum 2,16 secondes et le retard des voix 11,42 secondes. Le délai des noms reste donc borné sur cet essai, avec des mises à jour par paliers. Ces mesures décrivent les files du moteur, pas une latence mot à mot filmée sur l’interface du Poco.

Les appels d’acceptation totalisent 80,36 secondes pour Handy et 44,00 secondes pour les voix. La mémoire PSS maximale observée est de 1 396 573 Kio, soit environ 1,33 Gio. Le texte final compte 575 mots lexicaux ; le texte natif Handy et sa projection ont la même empreinte `c939f9f1c390dc032e169df7f4c1a24a72f52483f336d6dcd102557c2a29ff55`. Aucune référence annotée ni passe Handy seul de cette durée n’est disponible : cette égalité vérifie la conservation interne du texte, pas sa fidélité linguistique à une référence humaine.

Cas : `cpu-both-20838583`, fichiers `runs/both-2normal-100-154_90s-*`. Le succès de cette passe ne gomme pas la variabilité des essais courts sur le même hôte.

## Décision pour Réunion test7

Préparer une APK expérimentale version 41 avec Handy inchangé et, dans le pont de réunion, deux threads pour les voix, des blocs de huit secondes et une priorité normale. Le port natif conserve ses valeurs par défaut pour les autres appelants. La capture partagée, les horodatages, la réserve audio, les règles d’attribution et les garanties de conservation du texte restent inchangés. Les plus grands blocs réduisent la fréquence des calculs ; ils n’abaissent pas la résolution temporelle des sorties du modèle à huit secondes.

Le compromis est explicite : le texte peut avancer avant les noms, qui sont ensuite affectés sur leurs positions audio d’origine. Les essais justifient une nouvelle version à tester sur le Poco, pas une promesse de fluidité universelle ni d’attribution exacte de chaque mot. La mémoire, le démarrage, les très brèves interventions, les chevauchements et les réunions plus longues restent à valider sur le téléphone réel.

## Vérification du candidat

Le test du thread réel de diarisation a d’abord échoué sur la priorité Java minimale (1), puis a réussi avec la priorité normale (5), au moment de l’ouverture du moteur. La callback Android est neutralisée seulement dans ce test JVM ; le banc Android mesure séparément les priorités réelles. Le port Kotlin conserve sept tests verts, le contrat C++ passe et les onze tests du contrôleur de paquet vérifient notamment les empreintes natives et l’identité de test7.

La première suite normale a signalé un échec du test de confirmation après glissement vers le bas ; ce test a ensuite réussi isolément, sans modification de l’overlay ni de son test. La première suite prototype a repéré l’attente d’identité encore fixée sur test6 : cette attente a été corrigée en test7/version 41, puis le test ciblé a réussi. Ces premières sorties sont conservées dans `app/build/reports/meeting/cpu-comparison/test7/`, avec les nouvelles passes complètes et les contrôles de paquet.

Les passes complètes finales comptent chacune 1 140 tests, sans échec ni test ignoré, en variante normale et en prototype. Les XML de chaque variante sont archivés séparément sous `test7/{normal,prototype}/test-results/`. Les deux revues Astra du même état figé sont consignées dans `2026-09-27-cpu-comparison-reviews.md`.

Les deux variantes réussissent `assembleDebug` et `assembleDebugAndroidTest`. Le prototype local vérifié est `com.uhama.whisperpin.meetingtest`, version 41, `0.9.6-dictai-meeting-test7`, ARM64, 93 197 694 octets. Son SHA-256 est `3cac3d2626dce21d4d7f2c951c7404c2cc42c8cac53711691d63324d115fc2d3`. La signature v2 et l’alignement ZIP à 16 Kio passent ; le certificat conserve l’empreinte `6b37c02704d31553b275a9a5f23c8eb650df04cd59f7b28074e6f2dcadbf9539`. Les empreintes natives vérifiées sont `4067084ca70b18906ff478d429652adc3dccb8c5f40e60715d6599564cadd248` pour le moteur de réunion et `68b2733aaa6638ffe03254e5f6719eefc78e49e9272aeeb3fc5961f2ef446b5b` pour le pont Handy inchangé.

Cette identité décrit le build local du contenu candidat, avant son commit. L’artefact produit par Actions a ensuite été vérifié séparément, comme indiqué ci-dessous.

## Publication GitHub Actions vérifiée

Le [run 36337242721](https://github.com/Uhama91/DictAI/actions/runs/36337242721) termine en succès sur le commit `50c29490348c62a0be8336d70315f4503c1d9268`. Il produit un unique artefact `dictai-meeting-test`, ID `10937443534`, disponible depuis [la page de téléchargement](https://github.com/Uhama91/DictAI/actions/runs/36337242721/artifacts/10937443534). L’archive annoncée par GitHub fait 93 198 710 octets, avec le digest `sha256:edd3addbbacc5c9965d23ee84e296cf8e925ec615c5fb258c1ee4f9ffa2f99e1`.

L’APK téléchargé fait 93 197 694 octets et son SHA-256 `3cac3d2626dce21d4d7f2c951c7404c2cc42c8cac53711691d63324d115fc2d3` correspond au fichier `SHA256SUMS`, aux métadonnées et à l’APK local. `ciCommit` désigne le commit exact du run. L’identité Android est `com.uhama.whisperpin.meetingtest`, version 41/test7, ARM64. La signature v2, le certificat inchangé, l’alignement ZIP à 16 Kio et les deux empreintes natives épinglées ont été vérifiés sur ce fichier téléchargé.

Preuves locales : `app/build/reports/meeting/cpu-comparison/test7/ci-download-36337242721/dictai-meeting-test/`. La publication est validée ; les performances et la qualité d’attribution sur le Poco F7 restent à mesurer physiquement.

## Preuves locales

- Plan et limites : `docs/superpowers/plans/2026-09-27-meeting-cpu-comparison.md`.
- Reçus Kotlin/C++/binaire : `app/build/reports/meeting/cpu-comparison/port/`.
- Mesures Android : `app/build/reports/meeting/cpu-comparison/runs/`.
- Identifiants : Handy `cpu-handy-only-18306308`, voix 1/arrière-plan `cpu-diar-only-18450939`, voix 2/normale `cpu-diar-only-18762040`, voix 4/normale `cpu-diar-only-19120742`.

Les fichiers logcat contiennent aussi les cas précédents : lire les sections délimitées par le bon `phase=prepared runId=...`, et non toutes les lignes comme s’il s’agissait d’un seul essai.
