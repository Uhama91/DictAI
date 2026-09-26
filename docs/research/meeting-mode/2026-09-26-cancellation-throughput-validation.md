# Réunion test3 — annulation, navigation et continuité audio

État : validation locale acquise pour l’APK expérimental test3. Les deux revues finales sont favorables. La validation physique sur Poco F7 et la publication GitHub Actions restent à faire.

Le retour utilisateur concerne un Poco F7 et une vidéo de *The Big Bang Theory*, sans lien précis. L’utilisateur a confirmé que la vidéo et la langue sélectionnée dans DictAI étaient toutes deux en français. Les essais locaux utilisent un émulateur Android ARM64 ; ils ne reproduisent pas les conditions acoustiques ni les performances du téléphone.

## Comportements attendus

- Glisser la pastille vers la droite annule une dictée ou une réunion active, y compris pendant la préparation ou la finalisation. En réunion, le brouillon conserve le texte déjà obtenu et les corrections ; l'audio restant en attente est abandonné.
- Dans un dossier ou « Sans dossier », glisser à droite sur la liste ou sur la pastille revient à la liste principale des dossiers, intitulée « Mes notes ». Ce retour est prioritaire lorsque le menu du dossier est affiché.
- L'annulation possède aussi une action d'accessibilité. Le défilement vertical, les notes déjà ouvertes et le déplacement de la pastille après maintien doivent être préservés.
- Le cercle de boucles Réunion est agrandi à l’intérieur de la pastille de 74 × 44 dp : son diamètre tracé passe d’environ 23,6 à 33,6 dp, soit 42 % de plus. Il garde environ 5,2 dp de marge en haut et en bas, même pendant la rotation. Le passage en Dictée rétablit la hauteur de dessin initiale.
- Le retard audio est conservé temporairement dans le cache privé, avec un budget de 128 Mio de PCM en attente et une petite file mémoire. Les limites réelles de stockage restent signalées.
- Des métadonnées de voix incomplètes ne doivent plus faire disparaître une partie du texte reconnu. Les passages sans attribution fiable restent sans auteur automatique.

## Expérience audio non retenue

Une expérience de contexte RNNT a comparé le même extrait public AMI en anglais de 60 secondes, les mêmes modèles épinglés et le même émulateur, sans compilation Gradle simultanée. **Ce réglage expérimental n’est pas retenu dans la livraison**, à la suite du contrôle français décrit ci-dessous. Cet extrait anglais ne reproduit pas la vidéo du retour utilisateur. Empreinte de l'extrait : `f00f92e53115a4a6724aec0ddaf9c675df6d5fabd3a489b43fffe56ba52a3ae9`.

| Mesure avec transcription et attribution des voix | Référence | Expérience non retenue |
| --- | ---: | ---: |
| Traitement différé des 60 secondes | 49,357 s | 40,782 s |
| Temps de calcul pendant la lecture en temps réel | 43,701 s | 42,965 s |
| Retard maximal d'alimentation en temps réel | 774 ms | 1 226 ms |

Le traitement différé était environ 17 % plus court sur cette mesure ; le résultat en temps réel était plus contrasté. Ces chiffres ne décrivent pas l’APK final et ne démontrent pas une amélioration de la latence sur Poco F7.

L’expérience utilisait le contexte RNNT de la métadonnée du modèle, soit 3, au lieu du réglage historique 1. Sur le témoin français synthétique, le premier mot « Bonjour », auparavant horodaté à 640 ms, était absent des mots attribués ; le premier mot retourné était « Ouvrons » à 1 120 ms. L’assertion inchangée exigeant un début avant une seconde a détecté cette régression. Le contexte 1 est donc rétabli. La suppression du préchauffage synthétique lors de l’ouverture JNI interactive est conservée et sera vérifiée séparément.

La reconstruction des sorties finales donne 125 puis 128 mots, avec la même distance d'édition aplatie de 39 sur 153 mots de référence. La référence contient des voix superposées : cette comparaison est descriptive et ne prouve pas un gain de reconnaissance. Elle est distincte de la correction de couverture du texte dans l'interface.

Les journaux bruts sont conservés dans `app/build/reports/meeting/native/probe-baseline-sequential-ami.log`, `probe-corrected-sequential-ami.log` et `ami-sequential-transcript-comparison.txt`.

Le contrôle français final avec le contexte 1 passe **six tests JNI en 12,806 secondes**. « Bonjour » est présent dans le transcript brut et dans les mots horodatés à 640–800 ms ; le dernier mot commence à 10 560 ms. Les trois tags et la séquence stable `1,2,3,1` sont conservés, tout comme les accents UTF-8, la coexistence des neuf anciens runtimes et l’ouverture d’une seconde session. Les deux ouvertures JNI prennent 208 puis 267 ms sur cet émulateur ; ces durées excluent le lancement de l’application et ne mesurent pas la latence sur Poco. Journaux : `2026-09-27-testuser0-MeetingNativeBridgeInstrumentedTest-rev10.log` et `2026-09-27-testuser0-MeetingNativeBridgeInstrumentedTest-rev10-logcat.log`, lignes horodatées du 27 septembre à 00 h 18.

## Vérifications ciblées acquises

Les essais sont exécutés avec le JDK 21 d’Android Studio. Les cas de régression ont d’abord été observés en échec, puis les suites ciblées sont passées : contrôleur 42 tests, file audio 13 tests, moteur 21 tests, conservation du texte 41 tests et identité du prototype 1 test. Le dernier ciblage de l’interface comporte 36 tests de gestes, de cycle de vie, de transitions et de dessin. Ces nombres décrivent les suites ciblées et ne doivent pas être ajoutés aux totaux des suites complètes.

Les preuves du contrôleur et de la file sont archivées dans [le rapport du lot audio](../../../app/build/reports/meeting/cancellation-throughput/pipeline/run-2026-09-26-jdk21-20-07/README.md). Les preuves du texte et du moteur sont dans `app/build/reports/meeting/reducer/green-jdk21-2026-09-26/`.

Les scénarios couvrent notamment l’annulation après une demande de finalisation, les sauvegardes en concurrence, le rejet des résultats tardifs, l’ordre des blocs audio stockés sur disque, les erreurs de stockage et la conservation des retouches quand les mots horodatés deviennent incomplets ou sont révisés.

La bibliothèque JNI reconstruite avec le contexte historique 1 est un ELF ARM64 avec alignement 16 Kio. Son empreinte SHA-256 est `3a7c06e33a052996aa5aa637bfb8bd79f523a6b96c81da0aceb1af7ec2cb9bcc`. Les tests C++ natifs et les 13 tests des outils de compilation passent après une phase rouge vérifiant ce réglage.

Le parcours Android a révélé un cas supplémentaire : `ACTION_OUTSIDE` pouvait fermer le menu avant le `DOWN` de la pastille et perdre ainsi l’action de retour au dossier. Le correctif laisse la pastille capturer cette action lorsque la touche la vise effectivement ; les autres touches extérieures ferment toujours le menu. Les événements d’un ancien menu sont ignorés. Le contrôle des coordonnées s’appuie sur le [routage des événements d’Android](https://android.googlesource.com/platform/frameworks/native/+/04d24da36e/services/inputflinger/dispatcher/InputDispatcher.cpp) : les fenêtres appartenant à un autre UID reçoivent des coordonnées masquées. La régression a été reproduite puis corrigée dans le test du service et dans le parcours sur Android.

Le scénario final `MeetingOverlayGesturesAndroidTest` réussit en 17,325 secondes sur l’émulateur ARM64 API 36 : retour à la racine depuis la liste et depuis la pastille, priorité du retour pendant une réunion active sans annulation, note conservée, puis annulation avec brouillon conservé. Les événements tactiles passent par Android ; le contenu vocal de ce scénario est simulé et explicitement identifié comme tel. Journal final : `2026-09-27-testuser0-MeetingOverlayGesturesAndroidTest-rev10-permissions-granted.log`. Les essais précédents avaient rencontré un écran en veille, puis des permissions de test absentes ; ces prérequis ont été corrigés sans changer les assertions.

## Vérifications finales

Les suites complètes passent sur l’état source final : **1 022 tests, aucun échec, aucune erreur ni test ignoré pour chacune des variantes normale et Réunion**. Les 131 fichiers XML de chaque variante sont archivés dans `swipe-full-normal-final-xml-2026-09-26/` et `swipe-full-prototype-final-xml-2026-09-26/`. Les deux anciennes attentes du libellé générique « Pastille Réunion » ont été actualisées pour vérifier le nouvel état descriptif du brouillon restauré ; leurs assertions sur les documents, les identités et l’absence d’audio sont conservées.

Les 13 captures du scénario gestuel, les 5 captures de dessin natif et les 2 captures précédant le démarrage du micro ont été inspectées et sont réunies dans [la galerie d’audit visuel](2026-09-26-swipe-visual-audit.md). Le cercle agrandi reste contenu dans la pastille, y compris aux deux angles de rotation capturés. Les nouveaux menus sont lisibles, la pastille reste accessible et le texte reste présent pendant la fermeture.

Les quatre classes instrumentées passent sur le même APK retenu, dans l’utilisateur principal de l’émulateur :

| Classe Android | Résultat | Durée |
| --- | --- | ---: |
| `MeetingNativeBridgeInstrumentedTest` | 6/6, audio français synthétique et JNI | 12,806 s |
| `MeetingOverlayGesturesAndroidTest` | 1/1, gestes Android et contenu simulé | 17,325 s |
| `CursiveWaveMeetingFixtureCaptureAndroidTest` | 1/1, dessin natif simulé | 9,308 s |
| `MeetingOverlayDefaultRuntimeAndroidTest` | 1/1, JNI et AudioRecord de production | 24,948 s |

Le parcours AudioRecord vérifie l’écoute, le passage en arrière-plan, la pause, la reprise, la finalisation et la libération des ressources. Il termine sans texte sur l’entrée micro de cet émulateur ; il valide le cycle de capture, pas la reconnaissance de paroles. Le contenu capturé n’est pas conservé par le test. Un essai précédent dans l’utilisateur secondaire n’avait pas observé l’état attendu après HOME ; cet échec n’est pas reproduit sur l’utilisateur principal avec l’APK retenu. Journaux finaux préfixés `2026-09-27-testuser0-` et suffixés `rev10.log` ; les lignes de logcat de la session finale du micro sont celles du 27 septembre à 00 h 24.

L’état final de la révision 10 comprend **364 fichiers vérifiés sans divergence**, sources et bibliothèques comprises. Le manifeste `2026-09-27-meeting-test3-source-manifest-rev10.sha256` a pour empreinte `29f381eb67b398f1403f717485c063a3a021096516d08dc282c7bacaf0a065e1`. Les deux revues Astra portent sur ce même état : conformité fonctionnelle, gestes et préservation des données pour la première ; concurrence de l’annulation, stockage borné, résultats tardifs, cas adverses de métadonnées, accessibilité et configuration native pour la seconde. Elles sont favorables après résolution de la régression RNNT et lecture des preuves finales. Le checkout contient les modifications non commitées du correctif : le commit de base `8a12242` ne doit pas être présenté comme l’identité de ce correctif.

## APK local

| Propriété | Résultat vérifié |
| --- | --- |
| Application | `com.uhama.whisperpin.meetingtest` |
| Version | `0.9.6-dictai-meeting-test3`, code 37 |
| ABI | ARM64 uniquement |
| Taille | 96 234 190 octets |
| SHA-256 | `ca50108b3197c1647d6f51f25ae6871fe1456e715d8b1ce63381e8b9edac7147` |
| Signature | `WhisperPin Debug`, identique à test2 |
| Certificat SHA-256 | `6b37c02704d31553b275a9a5f23c8eb650df04cd59f7b28074e6f2dcadbf9539` |
| Alignement ZIP | Vérification 16 Kio réussie |
| Bibliothèques natives | 13 bibliothèques conformes : ELF alignés à 16 Kio, stockage ZIP et liste d’autorisation vérifiés |

Le vérificateur strict du paquet réussit ; ses 10 tests passent après la mise à jour de l’empreinte JNI épinglée. Le contrôle direct de l’APK confirme qu’il contient la bibliothèque native attendue. Les deux bibliothèques du formateur local, habituellement construites par GitHub Actions, manquaient dans le checkout local. Elles ont été reprises à l’identique depuis l’APK vérifié de l’exécution `36204337558`, correspondant au commit de base ; leurs sources n’ont pas changé. La signature source, les empreintes, l’ABI, les segments ELF alignés à 16 Kio et les exports JNI ont été vérifiés. La provenance est consignée dans `llm-library-extraction-provenance.json`. La bibliothèque Réunion reconstruite pour ce correctif n’a pas été remplacée.

Les métadonnées du paquet portent encore `ciCommit: 8a12242…`, qui désigne la base du checkout local. Il s’agit d’une compilation locale non publiée sur GitHub Actions ; son identité complète est l’empreinte de l’APK associée au manifeste de l’état local ci-dessus.

## Limites

La qualité de la transcription, la séparation des voix, la consommation de mémoire et la latence en conditions réelles restent à confirmer sur le Poco F7. Le tampon conserve l’audio en attente, mais n’accélère pas le moteur de transcription. Aucun nouveau modèle n’est introduit.

## Vérification à faire sur le Poco F7

1. Installer le prototype test3 en mise à jour du prototype Réunion existant. Sélectionner Réunion par un appui explicite dans le menu et vérifier la lisibilité du cercle.
2. Lancer une réunion en français avec le même extrait français. Noter le délai du premier texte et vérifier, sur au moins une minute, la continuité des phrases et l’attribution des voix. Conserver un extrait de référence précis pour comparer les mots manquants.
3. Glisser la pastille à droite pendant l’écoute, puis pendant une nouvelle préparation : vérifier l’arrêt du micro et la présence du brouillon déjà transcrit.
4. Pendant une réunion, ouvrir un dossier puis glisser à droite sur la liste et sur la pastille : vérifier le retour à « Mes notes » et la poursuite de l’écoute.
5. Vérifier aussi l’annulation d’une dictée classique et le déplacement de la pastille après maintien.
