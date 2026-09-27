# Réunion Handy — diagnostic et validation

## Objectif

Retrouver la transcription progressive de la dictée Handy pendant une conversation française, puis attribuer les paroles aux intervenants selon leur position dans l'audio. Le signalement concerne une conversation réelle entre deux personnes sur Poco F7. Les voix simultanées restent un cas incertain ; leur séparation parfaite n'est pas un critère de cette correction.

Base de travail : `c4aaa8fc982f951338ef80f30c71b8c3e77fa81b`, variante Réunion test3. Les captures utilisateur sont privées et ne sont pas publiées dans ce dossier. Aucun enregistrement de cette conversation n'a été fourni : elles ne permettent pas de calculer la précision ou le décalage du téléphone.

## Cause établie

Réunion n'utilisait pas le chemin de dictée Handy. Son ASR NeMo et son identification des voix travaillaient successivement sur le même worker. Un calcul de diarisation long pouvait ainsi empêcher la lecture du résultat ASR et laisser la file audio grossir. Le panneau affichait séparément chaque petit résultat, sans présenter ses temps dans l'audio.

Les deux chemins utilisent le microphone mono à 16 kHz avec `AudioSource.MIC`. La différence constatée n'établit donc pas un problème de choix de source audio. Les caractéristiques du Poco ne suffisent pas non plus à conclure que son matériel est incapable de suivre la conversation.

## Mesures disponibles

Émulateur Android ARM64 uniquement, fixture française synthétique de 12,78 s, entrée cadencée par blocs de 20 ms, quatre threads CPU. Le temps du flux commence à l'alimentation audio ; il exclut le chargement du modèle et inclut l'attente imposée par la cadence audio. Aucun préchauffage synthétique ni trace par graphe dans les passes suivantes.

| Exécution | Premier texte | Durée du flux | Chargement du modèle |
| --- | ---: | ---: | ---: |
| Handy, réglages de dictée | 1,517 s | 16,524 s | 7,028 s |
| Handy, deuxième passage | 1,294 s | 12,955 s | 2,039 s |
| NeMo ASR seul, contexte historique | 7,921 s | 30,351 s | 0,644 s |

Les caches de fichiers diffèrent entre les passes. Le deuxième Handy demandait des mots horodatés, mais le moteur a retourné des tokens comme au premier passage : son amélioration de temps ne peut pas être attribuée à cette option. Ces résultats indiquent un écart de délai sur l'émulateur. Ils ne prédisent pas le Poco et ne constituent pas une comparaison linguistique générale : les deux moteurs ont transcrit différemment une partie de la fixture.

Le profilage AMI de 60 s, avec traces de graphes et préchauffage séparé, a localisé 155,098 s de travail dans l'alimentation synchrone de diarisation. Le flux ASR + diarisation a duré 277,468 s, contre 104,926 s pour l'ASR seul. Cette passe sert à localiser le coût ; elle n'est pas comparable au démarrage réel de l'application. Une autre passe AMI en rafale a été interrompue et n'entre pas dans les résultats.

Les traces de diagnostic locales sont dans `app/build/reports/meeting/conversation-2026-09-27/` : `handy-fr-20ms.log`, `handy-fr-20ms-word.log`, `nemo-fr-20ms-cold-uninstrumented.log`, `french-probe.log` et `ami-probe.log`.

## Contrat de la correction

- Handy conserve son modèle Q8, sa langue et ses réglages de dictée.
- L'identification des voix possède un worker indépendant. Ses calculs et son chargement ne conditionnent pas la publication du texte Handy.
- Le texte original reste la référence. Les tokens horodatés sont assemblés sans décoder séparément des fragments d'octets UTF-8. Un alignement incertain conserve la parole sans lui inventer de temps ou d'intervenant.
- Une attribution tardive met à jour le passage existant et respecte les retouches, attributions manuelles et images.
- Les continuations rapprochées d'une même personne sont regroupées visuellement. Les repères affichés viennent de l'audio ; les anciennes notes ne reçoivent pas de faux temps.
- L'annulation reste disponible pendant l'écoute et la finalisation. La prochaine session attend la fermeture effective des ressources.

La spécification détaillée est [Réunion : transcription Handy prioritaire](../../superpowers/specs/2026-09-27-meeting-handy-live.md).

## État de la validation

Le raccord Handy/diarisation est implémenté et sélectionné par défaut pour test4. La compilation et les suites JVM des deux variantes réussissent après correction d’une attente de taille obsolète dans le test d’onboarding. Les contrôles Android finaux, les revues et l’artefact Actions sont consignés séparément à la fin de ce rapport. Le test prolongé des voix reste un échec de débit, détaillé ci-dessous.

### Historique des vérifications intermédiaires

Une passe intermédiaire a exécuté 142 tests JVM/Robolectric sans échec : catalogue et réutilisation des modèles, projection, panneau, sérialisation, réducteur, cycle de vie du moteur et contrats des deux passerelles. Trace locale : `/tmp/meeting-model-store-ui-native-green.log`. Elle précède le raccordement complet Handy/diarisation et ne prouve pas encore leur performance simultanée.

Une seconde sélection, après correction de l'assembleur et ajout des états de retard, passe avec 122 tests sans échec (`/tmp/meeting-progress-ui-green3.log`, JDK 21, `-Pkotlin.incremental=false`). Elle couvre projection (19), panneau (23), réglages Réunion (7), service overlay (29), moteur (32) et assembleur Handy (12). Les nombres des deux sélections se recouvrent et ne doivent pas être additionnés. Une exécution précédente perturbée par deux compilations simultanées a été écartée. Les vérifications sont désormais exécutées dans un créneau unique.

Le test de réouverture a reproduit un arrêt du rafraîchissement après masquage du panneau en pause. Le raccord de visibilité corrigé reprend le compteur sans nouvelle hypothèse ASR ; la fin de session et la destruction du service retirent le rappel. Le test de cadence float32 a aussi reproduit l'inclusion d'une trame supplémentaire aux frontières exactes de 10 ms ; le calcul corrige uniquement l'erreur de représentation numérique et conserve le refus des zones réellement non couvertes.

La sélection suivante passe avec 160 tests (`/tmp/meeting-pipeline-coordinator-green-final.log`) : coordinateur hybride (11), moteur (33), assembleur (16), AudioRecord (10), panneau (23), projection (19), réglages (7) et réducteur (41). Les régressions reproduites puis corrigées concernent la lecture des voix après 164 s, les appels après annulation, le drainage des callbacks malgré une interruption, et une révision des voix qui attendait inutilement le verrou ASR. L'assembleur préserve une horloge monotone, reprend une fenêtre coupée au milieu d'un caractère UTF-8 et évite de redécoder un texte inchangé tout en maintenant sa purge par âge. Cette sélection précède le dernier ajustement de visibilité du statut et la nouvelle expérience native à un thread.

Les deux passerelles natives ont été recompilées pour ARM64 avec des segments ELF alignés à 16 Kio. Le moteur `libtranscribe.so` et les bibliothèques GGML de Handy sont inchangés ; seule sa passerelle JNI est étendue. Les huit autres entrées du manifeste des runtimes conservent leur taille et leur empreinte antérieures. La vérification du bundle, celle du manifeste et les 13 tests des scripts natifs passent.

Empreintes intermédiaires des passerelles, à revérifier dans l'APK final. La première passerelle de diarisation ci-dessous est invalidée par le contrôle de durée décrit ensuite :

| Bibliothèque | SHA-256 |
| --- | --- |
| `libdictai_meeting.so` | `665c456596e1db0dc8caae24b1e48a3dd7e914d23716e2474144c86f16e55085` |
| `libtranscribe_jni.so` | `68b2733aaa6638ffe03254e5f6719eefc78e49e9272aeeb3fc5961f2ef446b5b` |

Le premier test simultané a révélé une erreur de conversion dans la nouvelle passerelle autonome de diarisation : un nombre d'échantillons était passé au convertisseur qui attendait un nombre d'octets. Les 408 968 octets du PCM, soit 12,78025 s, ne produisaient que 640 trames de 10 ms, soit 6,4 s. Le résultat simultané de cette passe ne valide donc ni l'alignement ni les performances. Le test de durée ajouté échoue sur cette version (`/tmp/meeting-diarization-duration-red.log`). Le correctif conserve la longueur originale en octets et vérifie le nombre d'échantillons convertis. Sa nouvelle compilation a l'empreinte `ae55386375c600dd6eef872394d913a9a2405fd06553eeb38ac3b7c19fece6e4`. L'exécution corrigée couvre bien 1 279 trames, soit 12,79 s, à moins de deux trames de l'entrée. L'ancien chemin natif Réunion ne comportait pas cette double division.

La lecture directe des métadonnées du GGUF confirme huit canaux (`sortformer.num_speakers=8`), une sortie haute résolution sans sous-échantillonnage et une cadence de 10 ms. Il ne faut pas confondre le nombre de canaux disponibles avec le nombre de personnes réellement présentes.

Ces résultats intermédiaires ne remplacent pas les contrôles finaux du paquet test4.

La fluidité, les erreurs de reconnaissance et la stabilité des noms dans une conversation naturelle restent à mesurer sur le Poco F7 physique.

### Coexistence à quatre threads par modèle : configuration rejetée

Le test court corrigé réussit ses assertions de texte, de couverture audio et de compatibilité avec l'API Dictée. Il échoue toutefois au critère de fluidité : le calcul concurrent ralentit très fortement Handy. L'AVD ARM64 API 36 possède quatre cœurs et 4 096 Mio de RAM ; les moteurs utilisent le CPU. Le GPU d'affichage de l'émulateur ne signifie pas que les modèles l'utilisent.

| Chemin | Premier texte | Fin du texte Handy | Retard audio maximal Handy |
| --- | ---: | ---: | ---: |
| Handy seul avant | 2,729 s | 18,890 s | 5,254 s |
| Handy + diarisation, quatre threads chacun | 2,509 s | 85,766 s | 69,678 s |
| Handy seul après | 2,837 s | 21,885 s | 8,627 s |

Les temps commencent au début d'alimentation, après chargement, pour 12,78025 s d'audio. Dans le passage combiné, les appels diarisation occupent 99,093 s au total ; sa finalisation prend 1,904 s et le drainage après Handy 17,870 s. Le PSS observé avec les deux moteurs chargés atteint 1 358 076 Kio au jalon avant fermeture ; ce n'est pas une mesure exhaustive du pic.

Le rapprochement final avec le réducteur et la projection conserve exactement le texte : 19 mots lexicaux alignés, 18 attribués, un sans canal, trois canaux utilisés et cinq lignes projetées. Ces comptes ne prouvent pas à eux seuls que chaque identité est correcte dans une conversation naturelle. Les journaux, XML et paramètres AVD sont archivés sous `app/build/reports/meeting/conversation-2026-09-27/test4-short/`.

La configuration à quatre threads par modèle n'est pas promue ; le test long correspondant n'a pas été lancé. L'expérience suivante, rapportée ci-dessous, conserve Handy et ses quatre threads, et limite uniquement la diarisation autonome à un thread. La priorité d'arrière-plan seule n'a pas suffi.

Le candidat à un thread porte l'empreinte JNI `83a19a5794a020bd56e60212136261141e776f2cc24e22d0151f73dec2c0a546`. Le patch reproductible ajoute un paramètre dont la valeur par défaut reste quatre ; seule la passerelle autonome de diarisation demande un. Le constructeur historique NeMo et Handy conservent leurs réglages. Le script d'application refuse une source divergente et accepte sans la modifier une source déjà corrigée. Ses cas d'application réelle, de seconde application et de divergence passent ; les 16 tests d'outillage et les 11 tests du vérificateur de paquet sont verts.

### Coexistence avec un thread pour les voix : test court réussi

La même comparaison A/B/A conserve exactement le texte des trois passages, ainsi que celui de l'API Dictée historique. La couverture diarisation reste de 1 279 trames pour 12,78025 s de PCM.

| Chemin | Premier texte | Fin du texte Handy | Retard audio maximal Handy |
| --- | ---: | ---: | ---: |
| Handy seul avant | 2,427 s | 19,737 s | 5,861 s |
| Handy + diarisation, un thread pour les voix | 2,663 s | 20,549 s | 6,870 s |
| Handy seul après | 2,360 s | 16,494 s | 3,199 s |

Le surcoût de fin de texte est de 0,812 s face au premier témoin et 4,055 s face au second, soit environ 4 à 25 %. La variation des témoins interdit une promesse de coût nul. Le ralentissement majeur à 85,766 s n'est plus reproduit. La diarisation demande encore 37,340 s dans ses appels d'alimentation, puis 4,167 s de finalisation et lecture des trames ; son drainage après le marqueur Handy dure 25,295 s. Le retard voix maximal est de 6,960 s d'audio et le PSS au jalon avec les deux modèles ouverts de 1 359 079 Kio.

Ce résultat a permis de poursuivre avec le test de 63,9 s et l'intégration au moteur réel. Il ne valide pas à lui seul la rapidité de l'identification des voix ni les performances du Poco. Preuves locales : `app/build/reports/meeting/conversation-2026-09-27/test4-thread-cap/` ; APK intermédiaire de mesure toujours identifié test3, sans promotion de version.

### Visibilité du statut

Une sélection de 52 tests passe après le dernier ajustement (`/tmp/meeting-compact-voice-green2.log`) : panneau 23 et service overlay 29. La fenêtre normale dispose de deux lignes de statut. En fenêtre compacte, une panne d'identification conserve la mention « Voix indisponibles » même si de l'audio reste à transcrire ; le détail complet reste accessible. Le RED a reproduit les deux défauts avant correction. L'audit des captures finales confirme la visibilité de cette mention en fenêtre compacte.

L’audit des douze premières captures a ensuite révélé que le nom d’une personne pouvait sortir de la fenêtre compacte lorsque le texte était long et la police agrandie. Le label était centré verticalement sur toute la ligne de transcription. Un nouveau test à 240 × 112 dp et une police de facteur 1,35 a reproduit l’invisibilité réelle de sa première ligne. Le label compact est désormais ancré en haut, avec 4 dp de marge ; le nom et le repère audio restent visibles au début du texte. Les 72 tests ciblés passent après correction (panneau 24, projection 19, service 29), trace `/tmp/meeting-compact-speaker-green-targeted.log`. La disposition normale reste identique.

La première capture instrumentée s’est terminée par un arrêt Android dû à la révocation de la permission micro dans le nettoyage du test. Le micro préaccordé à l’extérieur du runner a permis de révéler une attente erronée dans la fixture : elle exigeait de trier un passage à travers une barrière sans temps. Le document simulé a été corrigé pour respecter le contrat ; la production continue de préserver ces barrières. L’état initial de permission doit être restauré après la fin des runners.

### Protocole de comparaison

La sonde Android compare Handy seul, Handy avec diarisation indépendante, puis Handy seul à nouveau. Chaque bloc de 20 ms est livré à son échéance audio absolue ; ce n'est pas un test en rafale. La version existante de l'API Dictée reçoit aussi la fixture entière. Le texte doit apparaître avant la finalisation et rester identique entre les chemins.

Le worker de voix ouvre ses ressources après avoir adopté sa priorité d'arrière-plan. Sa file est bornée à 120 secondes ; une saturation invalide la mesure au lieu de laisser tomber des morceaux d'audio. La fin du texte Handy est mesurée séparément de l'attente de finalisation des voix. Les relevés de mémoire précisent à quel moment les moteurs sont ouverts et ne sont pas présentés comme un pic exhaustif.

Un second test compare les deux chemins sur 63,9 secondes, obtenues en répétant cinq fois le même PCM français. Il sert à observer la charge une fois le contexte natif rempli ; les raccords répétés ne constituent pas une évaluation linguistique d'une conversation naturelle. Un point est relevé toutes les dix secondes d'audio.

Sa première exécution à un thread s'est arrêtée avant la passe combinée sur une assertion du test : les morceaux bruts contenaient deux espaces ASCII là où le texte Handy normalisé en contenait un. Le moteur normalise ces suites d'espaces dans `model.cpp:638` ; le contrôle doit reproduire cette règle, déjà respectée par l'assembleur applicatif. Ce résultat est un RED du contrôle de sonde, sans conclusion sur la coexistence prolongée. La comparaison intégrale des textes A/B reste requise après correction du test.

### Charge prolongée : limite de débit des voix confirmée sur l'AVD

Après correction du contrôle d'espaces, le témoin Handy termine 63,901 s d'audio en 81,030 s, avec un premier texte à 2,419 s. Le passage combiné atteint les jalons suivants :

| Audio envoyé | Temps écoulé, Handy + voix | Audio encore à identifier |
| ---: | ---: | ---: |
| 10 s | 14,233 s | 4,720 s |
| 20 s | 28,542 s | 12,640 s |
| 30 s | 42,385 s | 20,560 s |
| 40 s | 56,788 s | 29,520 s |
| 50 s | 70,816 s | 38,480 s |
| 60 s | 84,778 s | 46,400 s |

Le worker de voix n'a pas terminé dans les 120 s accordées à son drainage après Handy. Le test échoue sur cette limite ; aucune comparaison finale A/B ni attribution prolongée n'est déclarée réussie. Sa file ne déborde pas pendant les 63,9 s de capture, mais cela ne démontre pas qu'elle pourrait suivre une réunion indéfiniment. Le processus de test et son worker ont été confirmés arrêtés avant l'exécution suivante.

Cette mesure étaye la protection relative du flux Handy, tout en rejetant une affirmation de diarisation soutenue en temps réel sur cet AVD. Le témoin Handy lui-même y dépasse la durée audio. L'exécution intégrée courte suivante vérifie séparément les révisions de texte, les attributions et la fermeture dans `MeetingEngine`.

### Moteur intégré : texte direct et révisions vérifiés

Le test Android `HandyMeetingBridgeEngineInstrumentedTest` passe en 57,5 s avec le vrai `MeetingEngine`, le coordinateur Handy/diarisation, le réducteur et la projection. Les callbacks sont rejoués dans leur ordre de livraison, sans tri préalable qui masquerait une erreur de concurrence.

| Observation | Résultat |
| --- | ---: |
| Chargement jusqu'à disponibilité | 2,724 s |
| Capture de la fixture | 12,781 s |
| Drainage ASR après capture, avant demande de fin | 10,216 s |
| Premier texte depuis début de capture | 3,127 s |
| Première attribution et première révision des voix | 3,721 s |
| Mises à jour avant demande de fin / total | 12 / 17 |
| Révisions des voix préservant le texte | 9 / 9 |
| Mots lexicaux alignés / attribués / canal inconnu | 19 / 18 / 1 |
| Attente de finalisation et fermeture | 27,470 s |

Le texte final projeté est exactement celui du témoin Handy. Les temps des mots et des prises de parole restent dans la durée audio. Le compteur final confirme 12,780 s captées et traitées, aucune durée ASR en attente et une fermeture achevée. Le dernier état de voix mis en cache reste `ACTIVE` après fermeture ; il décrit le dernier calcul observé et ne représente pas une ressource encore ouverte. Le panneau utilise la phase de session pour arrêter son rafraîchissement.

### Arbitrage de livraison

Le raccord applicatif est autorisé comme **prototype test4**, destiné à l'essai Poco. Les preuves fonctionnelles valident la publication indépendante du texte et sa révision sans perte. Le test prolongé reste un échec de débit sur l'AVD, conservé sans augmentation de son délai ni requalification en réussite. Aucune fluidité physique, stabilité des identités dans une conversation naturelle ou diarisation soutenue en temps réel n'est déclarée validée. Le choix d'un thread pour les voix privilégie Handy ; une saturation éventuelle de la file secondaire doit rendre les voix indisponibles tout en laissant continuer la transcription.

### Vérifications finales de l'application

Après la correction du label compact, les suites JVM complètes normale et prototype passent chacune avec **1 099 tests, sans échec, erreur ni test ignoré**. Les 135 fichiers XML de chaque variante sont archivés séparément dans `handy-test4-evidence/jvm-normal-final-post-ui-xml/` et `handy-test4-evidence/jvm-prototype-final-post-ui-xml/`. Les anciennes passes de 1 098 tests précèdent ce dernier cas de régression.

Le test `MeetingOverlayGesturesAndroidTest` passe sur l'AVD en 22,916 s : gestes de la vraie pastille, retour à la racine des dossiers, retouches et noms conservés à travers pause/sauvegarde, puis annulation de réunion. Le contenu du document et les frontières modèle/micro y sont simulés ; les treize captures sont de vrais rendus Android, pas des résultats de reconnaissance.

Le test `MeetingOverlayDefaultRuntimeAndroidTest` passe en 25,792 s avec le catalogue v2 exact, `production-jni+AudioRecord`, puis pause/reprise et fermeture. Les deux ressources natives et le lecteur sont fermés et la réservation libérée. Aucun texte n'a été reconnu sur ce microphone d'émulateur (`turns=0`) : ce contrôle valide le branchement et son cycle de vie, pas la précision linguistique. La preuve de texte reconnu vient de la fixture audio du test intégré décrit plus haut.

La fixture de présentation finale passe en 19,957 s. Les 17 captures propres, les 13 captures de gestes et les deux captures runtime ont toutes été inspectées par Astra ; la [galerie Android](ui-renders/handy-test4/README.md) distingue les contenus simulés des contrôles natifs réels. Le micro, les autorisations d’overlay, les délais d’écran et l’utilisateur actif ont été restaurés après les runners.

Les [deux revues finales](handy-test4-evidence/reviews.md) portent sur le même manifeste immuable de 61 fichiers (`f4c2f57d74e2bfed144747ae4c703a96519f2c6ab0d6dc042265b73e418c2dd3`). L’APK local de l’application, version `0.9.6-dictai-meeting-test4`, code 38, porte le SHA-256 `0ad1d9dd283a2d33edf6e42b19db6d1cfcc0e3a332c234bd1dec8ab1d74d0732`. L’APK AndroidTest final porte `d6db33d57fccbc6313eb0b045398fef5d0df6f4cab5621f07e8a4a55320951c6`. L’identité test4 est isolée de l’application normale ; la signature, les 13 bibliothèques natives sans poids de modèle, leur alignement à 16 Kio et les empreintes des deux JNI ont été vérifiés.

La première exécution [GitHub Actions 36292940843](https://github.com/Uhama91/DictAI/actions/runs/36292940843), sur `cff47bf7bbe2575f36ab20b30d39e7ca453667bb`, a échoué sur un test parmi les 1 099 cas JVM. `new meeting publishes edits made after finish before replacing the document` attendait que la note soit publiée puis vérifiait immédiatement le remplacement du contrôleur. Or le service effectue ensuite plusieurs opérations asynchrones de fermeture, libération et réouverture. L'assertion à la ligne 843 arrivait donc avant l'achèvement attendu. Aucun artefact APK n'a été produit par cette exécution. Le correctif de test attend aussi un nouveau contrôleur non nul, en conservant la vérification de la retouche sauvegardée et le délai maximal existant ; il ne modifie pas la production.

Après correction, les 29 tests de `OverlayServiceMeetingRobolectricTest` passent en variante normale (8,375 s) et prototype (8,159 s). Les deux revues complémentaires acceptent ce changement de test seul. Les sources de production, les JNI et les fixtures Android restent identiques ; leurs contrôles précédents ne sont pas relancés. Le manifeste et les traces du correctif CI sont archivés séparément dans `handy-test4-evidence/`.

La livraison GitHub Actions exige encore de contrôler l’artefact reconstruit sur le commit corrigé. L’empreinte locale ne doit pas être présentée comme celle de cet artefact CI.

### Lecture du microphone

Le test comportemental a d'abord observé une lecture de 3 200 octets au lieu des 640 attendus. Après correction, les dix tests `MeetingAudioRecordTest` passent (`/tmp/meeting-audio-record-green.log`). Les lectures de 20 ms gardent un tampon matériel d'au moins 6 400 octets, augmenté selon le minimum demandé par Android ou deux blocs plus grands. Cette preuve porte sur le contrat du lecteur et sa fermeture ; elle ne mesure pas la latence du microphone physique du Poco.

## Sources primaires

- [Handy : construction du résultat streaming](https://github.com/handy-computer/transcribe.cpp/blob/553f1099a2b3a5bc4421894be171f09960fc0f3a/src/arch/parakeet/model.cpp) : le résultat streaming contient des tokens, sans lignes de mots.
- [Handy : API native](https://github.com/handy-computer/transcribe.cpp/blob/553f1099a2b3a5bc4421894be171f09960fc0f3a/include/transcribe.h).
- [NeMo-Speech.cpp épinglé](https://github.com/NVIDIA/NeMo-Speech.cpp/tree/97a15afa5caa9bce5baaa86c1184103877af4101).
- [NVIDIA : diarisation et limites des voix simultanées](https://huggingface.co/blog/nvidia/nemotron-diarization).
- [NVIDIA Nemotron 3.5 ASR](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) et [Nemotron 3 Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization).
