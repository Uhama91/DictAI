# Revues du port de diarisation et du candidat Réunion test7

Document chronologique d’un périmètre complexe. Les premières passes portent sur le port de mesure, alors que le défaut demeure un thread et les priorités de production sont inchangées. L’extension des blocs, puis le candidat Réunion test7, sont examinés séparément après les mesures correspondantes.

## Première passe Astra — fonctionnement et sécurité

Le paramètre est propre à la session et transmis au gestionnaire GGML qui alimente le calcul. Kotlin et C++ rejettent les valeurs hors de 1 à 4. La première lecture a identifié une régression : la résolution de `JniBindings` précédait la validation Kotlin et pouvait charger la bibliothèque pour une entrée invalide. Luna l’a reproduite par un échec sur `UnsatisfiedLinkError`, puis a déplacé la validation avant cette résolution. Les cas du chemin vide et des nombres de threads 0 et 5 passent sans initialiser JNI ; les tests vérifient aussi le défaut 1 et la transmission explicite de 4.

La signature JNI enregistrée comprend bien le nouvel entier. Les chemins historiques de transcription, la capture, la géométrie des trames et la fermeture des ressources ne changent pas. Le binaire reconstruit est ARM64/API 30, avec alignement à 16 Kio et export unique `JNI_OnLoad`. Les deux constantes du contrôleur de paquet ont été actualisées ; ses 11 tests passent après un échec reproduisant l’ancienne empreinte.

Conclusion de cette passe : aucun défaut restant n’a été identifié dans le port et le paquet. Cette conclusion autorise les mesures et ne valide ni un réglage plus rapide ni une nouvelle version à distribuer.

## Seconde passe Astra — régressions et maintenabilité

Nouvelle lecture du contrat public, de l’enregistrement JNI, de la construction du gestionnaire GGML et des tests : les paramètres restent propres à chaque session, sans variable globale mutable de budget CPU. Les appels existants conservent un thread et les modèles sont inchangés. Le chemin d’injection réservé au code interne permet de vérifier la transmission sans charger le modèle ; les tests de l’entrée publique couvrent séparément l’ordre réel de validation.

Les empreintes relues après cette passe correspondent au même état de production que la première. Les contrôles de paquet épinglent le nouveau binaire et le binaire Handy inchangé. L’architecture Android reste ARM64, sans nouvelle permission ni modification du stockage. Les comportements existants de fermeture, de fin de flux et de lecture des trames restent couverts par les tests ciblés, mais la nouvelle signature JNI doit encore être exercée sur Android par le banc réel.

Arbitrage : port acceptable pour les comparaisons. Un changement ultérieur du budget ou des priorités de production nécessitera une review complémentaire et des mesures comparatives ; aucune promesse de fluidité sur le Poco ne découle de ces deux passes.

## État examiné

| Fichier de production | SHA-256 |
| --- | --- |
| `DiarizationNative.kt` | `fd0a561bc78ac630aa4f14aac5a8564d200b7d397c0fb8ac8a35d006c32a93dd` |
| `meeting_diarization_jni.cpp` | `96c42beac92ff65068a398defe734a2a6990bce807144fa64ddd9559e3ffc7a0` |
| `meeting_native_logic.cpp` | `22ab56b0fada091ab5d203a6f87d1ef04cc91628e7410b304d2dc5d15b5c71e2` |
| `meeting_native_logic.h` | `9b2ccea96e34fe452db9534a78cc6a3a7557f4882ca1faac993381841ca468a8` |
| `libdictai_meeting.so` | `2244ccc5a55e447eae3207638770fd7ebc5ebab104355da75c6fdeaa12e2b819` |
| `prepare_meeting_ci_apk.py` | `3fbe557c8bfec892b10614641de8ee27632e47d131bbbefa6149bae84e032143` |
| `test_prepare_meeting_ci_apk.py` | `f81f6fb9243daa0017be086d217073732cee35cae365c86c5de2fad585c38f17` |

Preuves locales : `app/build/reports/meeting/cpu-comparison/port/`. Le banc de mesure et un éventuel changement du réglage de production demandent encore leur contrôle propre ; ce document ne clôt pas la livraison.

## Extension : paramètre de taille de bloc

Après les mesures CPU, le port a été étendu avec `chunkFrames`. Les deux nouvelles passes ci-dessous portent sur l’état figé dont les empreintes figurent dans ce paragraphe, et remplacent les empreintes précédentes pour le port courant. Les réglages de production restent un thread et le profil natif du modèle.

Première passe Astra, fonctionnement et sécurité : les seuls overrides admis sont 50 et 100 trames de 80 ms ; 0 conserve le profil du modèle. La validation Kotlin précède toujours la résolution JNI, la validation C++ précède la construction de session, et la signature enregistrée est `(Ljava/lang/String;II)J`. Le code résout d’abord toute la géométrie du modèle, puis remplace uniquement `chunk_len` pour un override non nul. Le constructeur de `DiarStream` conserve sa validation des limites. Les tests RED montrent le contrat manquant, puis les sept tests JVM ciblés, le test C++ et les onze tests du packager passent. Le binaire reconstruit conserve ARM64/API 30, l’alignement à 16 Kio et l’export unique `JNI_OnLoad`. Aucun défaut bloquant n’a été identifié pour lancer les mesures natives.

Seconde passe Astra, régressions et maintenabilité : nouvelle lecture des appels existants, des bindings, de l’enregistrement JNI et des validations. Aucun appel de production ne demande l’override ; les autres champs de géométrie, les buffers audio, le repère temporel, l’ASR et le cycle de fermeture ne changent pas. La valeur 0 préserve aussi le profil d’un modèle différent, au lieu d’imposer aveuglément le chunk V3. Les paramètres restent propres à chaque session. Les sept empreintes ont été recalculées et sont identiques à celles de la première passe. Aucun défaut supplémentaire n’a été identifié ; la qualité des attributions et le débit des blocs de huit secondes restent à mesurer sur Android.

| Fichier courant | SHA-256 |
| --- | --- |
| `DiarizationNative.kt` | `4ed16b7584e3ee6548c04c1b67e57c4f6f8a0a42c0ca46f0772ee1fd594263ef` |
| `meeting_diarization_jni.cpp` | `87f1caaaab7117b1fbc2c836b8e44aeedf2fa512c84117a4a152c96e98ee7478` |
| `meeting_native_logic.cpp` | `4e32ed2ebc19973140eb36efa859b5a943d2573172bf25513fa80d7b3b6a3cf5` |
| `meeting_native_logic.h` | `f9d3d13e8faa2dbd4f8d29b060786c753c928b635d314237140480060eae80d2` |
| `libdictai_meeting.so` | `4067084ca70b18906ff478d429652adc3dccb8c5f40e60715d6599564cadd248` |
| `prepare_meeting_ci_apk.py` | `4e95d9ef47e6fe5c1365590d0eed89160d4ddbdfcaa42062f9eecae9ee986aaf` |
| `test_prepare_meeting_ci_apk.py` | `c9f30064f7c7846b07f64c91cc831e36109c5b8e31c835d20b0ba4384f8b689a` |

Preuves de l’extension : `app/build/reports/meeting/cpu-comparison/chunk-frames/`. Ce contrôle autorise les essais, sans promouvoir un nouveau réglage ni valider le Poco.

## Candidat Réunion test7 — première passe Astra

Après les mesures natives et le contrôle prolongé de 154,90 secondes, lecture du candidat figé : seul le pont de réunion demande explicitement deux threads et `chunkFrames=100`. Le port conserve ses défauts un thread/profil du modèle. Le thread de voix passe à la priorité Java normale (5), puis applique la priorité Android normale (0) avant l’ouverture du runtime. L’injection de test est renommée sans changer son contrat. Le test du thread réel reproduit l’ancienne priorité avant le correctif et vérifie la nouvelle à l’ouverture du moteur.

La capture, les limites des files, le repère temporel, le texte Handy et les conditions de fermeture ne sont pas modifiés. Les blocs de huit secondes n’introduisent pas de déplacement des dates audio. Le binaire JNI est exactement celui exercé par les comparaisons natives, `4067084c…`, et Handy reste épinglé à `68b2733a…`. L’identité prototype devient version 41/test7, sans changer la version 35 des variantes habituelles. Les tests de paquet et d’identité ont été lus avec leurs résultats ; le test d’identité encore fixé sur test6 a été corrigé avant de figer cette passe.

Conclusion fonctionnelle et sécurité : pas de défaut bloquant identifié dans cet état. Le résultat justifie un prototype expérimental ; le score AMI limité et la variabilité de l’émulateur ne permettent pas d’affirmer que chaque parole sera correctement attribuée sur Poco.

## Candidat Réunion test7 — seconde passe Astra

Nouvelle lecture distincte des appels de `MeetingEngine`, des paramètres du pont, des bindings Kotlin/JNI, des validations C++ et des changements de tests. Les paramètres restent propres à chaque session. La fenêtre de probabilités conserve des indices absolus et un parcours glissant ; l’augmentation du bloc ne remplace pas les trames fines par une étiquette unique de huit secondes. Les snapshots, le rattrapage, l’annulation et la réserve audio utilisent les chemins existants. Aucune nouvelle permission, aucun service et aucun accès réseau ne sont ajoutés.

Le banc Android utilise les mêmes ports réels et cadence son admission PCM indépendamment du calcul. Les mesures de temps mural, d’échantillonnage des voix et les limites de comparaison sont explicites dans le bilan. Le texte privé et les fichiers audio sont exclus de la publication. Le contrôle AMI conserve ses seuils et son scorer ; il ne doit pas être présenté comme un score de l’attribution par mot de l’interface.

Les dix-sept empreintes du manifeste local `test7/reviewed-source-sha256.txt` ont été vérifiées à nouveau et correspondent à l’état de la première passe. La suite complète normale repasse verte sans modifier le test de geste qui avait échoué ; la suite prototype corrigée est verte. Le premier échec intermittent et l’attente d’identité obsolète restent consignés dans le bilan, sans prétendre que leur cause est un changement du moteur.

Arbitrage : avis favorable à la publication de Réunion test7 sur Actions après contrôles des APK. La validation physique Poco, les chevauchements, les interventions très brèves et les réunions de durée supérieure à l’essai restent ouverts.

| Fichier modifié par le choix du candidat | SHA-256 figé |
| --- | --- |
| `HandyMeetingNativeBridge.kt` | `3836dea412a6301b10c360876cf9eebda660a727b22ce5fa7a4c2cd04b527f82` |
| `app/build.gradle.kts` | `da6d4b308beb3fa4ea2b6fc16779a18846d68a0f650805fcadf71eeb4fe66363` |
| `prepare_meeting_ci_apk.py` | `12a69dbbbbec48ea12e7fecf847a5e9f67d75471e6b109f0d4f9568ba7eb6c73` |
| `test_prepare_meeting_ci_apk.py` | `56f1c0a32154bcb88bb362d35dfb3dadc4f02e210b7d227d6e4cbda81b3c1ad0` |
| `MeetingPrototypeIdentityTest.kt` | `1fada1306fce39e38d1755ba49998e5d62abffc2327a0c8b5d9f687eb619e7e7` |

Les empreintes Kotlin/C++/JNI du port restent celles de la section d’extension. Les preuves de cette phase se trouvent sous `app/build/reports/meeting/cpu-comparison/test7/`.
