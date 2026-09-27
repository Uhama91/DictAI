# Réunion test 5 : chronologie commune et attribution des voix

## Contrat demandé

Handy reste le moteur de transcription. Le modèle Réunion fournit uniquement les probabilités de présence des intervenants dans l'audio. Le microphone doit démarrer après la préparation des deux modèles, puis chaque bloc accepté alimente les deux branches dans le même ordre. Les résultats sont rapprochés selon leur position dans cet audio, jamais selon leur heure d'arrivée.

L'arrêt ferme les deux entrées sur le même dernier échantillon. La finalisation des calculs peut prendre des durées différentes. Les mots restent visibles pendant le traitement des voix ; une attribution tardive révise leur passage sans recopier le texte.

## Diagnostic natif court

Le premier diagnostic utilise la fixture publique synthétique française ABCA déjà présente dans les tests. Il ne reproduit ni la vidéo de football fournie par l'utilisateur ni sa conversation privée. L'émulateur ARM64 API 36 a redémarré sans effacement de ses données. Les mesures suivantes proviennent d'un seul essai, sans compilation concurrente.

| Mesure | Résultat |
|---|---:|
| Audio fourni aux deux moteurs | 408 968 octets PCM16, soit 12,78025 s |
| Chargement Handy | 1,160 s |
| Chargement du modèle de voix | 0,184 s |
| Premier texte après début d'alimentation | 1,297 s |
| Texte Handy final | 13,049 s après début d'alimentation |
| Retard audio maximal de la branche voix | 1,600 s |
| Somme des appels de traitement des voix | 7,188 s |
| Finalisation des voix | 0,856 s |
| Drainage après fin Handy | 1,314 s |
| Trames finales de voix | 1 279, cadence voisine de 10 ms |

Le texte final correspond exactement au texte attendu de cette fixture. Le classifieur du test 4, appliqué au résultat final, attribue 18 mots sur 19 aux canaux 1, 2 et 3. Le dernier « jeudi. », ancré entre 10,560 et 10,720 s, demeure inconnu : les probabilités de cette plage restent sous 0,5. Le nombre de mots attribués ne mesure pas leur exactitude par locuteur.

Cet essai ne reproduit donc pas le symptôme rapporté. Il ne remplace pas non plus l'échec prolongé du test 4 : les conditions d'exécution et la durée diffèrent. La valeur finale `alignmentBroken=false` ne démontre pas que l'historique des résultats intermédiaires est exempt d'erreur.

Preuves brutes locales : `app/build/reports/meeting/conversation-2026-09-27/test5-short/`. Le ZIP contient les octets et temps des tokens Handy, les probabilités Float32 finales et six fenêtres intermédiaires des voix, ainsi que les métadonnées et les raisons d'attribution. Son SHA-256 est `b016e8163f80db453707124a6956f0aadd3e9d50ad71256d93e4554c0d97d5d1`.

## Historique et moteur intégré du prototype 39

Le diagnostic d'historique exécuté manuellement sur `com.uhama.whisperpin.meetingtest` (test 5, version 39) conserve 648 événements ordonnés : 640 snapshots Handy pendant le flux, son résultat final, six fenêtres intermédiaires de voix et leur finalisation. Le rejeu dans le classifieur figé du test 4 ne rencontre aucun `alignmentBroken` ; les 19 mots finaux sont horodatés, avec 18 attributions et un inconnu. Cette fixture ne reproduit donc pas la perte générale d'attribution rapportée.

L'essai retenu est le PID 10704 à 10:11, avec le cache `test5-short-abca-1940593`. Son archive locale `test5-short-history-prototype/replay-files.tar` a pour SHA-256 `25bba7c4e1a709dd10cb634f0a6a7af069e369fc73495de53e4e7725a102692a`. Le log complet conserve aussi les essais précédents, dont une exécution de la variante normale ; ne pas mélanger leurs mesures. La commande Gradle des tests connectés a nettoyé les paquets de test et leurs caches. Les essais retenus suivants utilisent une installation manuelle en mise à niveau et la commande `am instrument`, puis une extraction des preuves avant tout nettoyage.

Le test intégré passe ensuite par le véritable `MeetingEngine` et `HandyMeetingNativeBridge`, sur le même APK prototype (`12758a60e95ed48e16efe28e66e4cd7826dc0fd91aa4d8e7dee327d755b008b7`). L'exécution (PID 12757) donne :

| Mesure du moteur intégré | Résultat |
|---|---:|
| Préparation avant disponibilité | 1,135 s |
| Premier texte après début du flux | 1,342 s |
| Première attribution et première révision de voix | 1,502 s |
| Mises à jour pendant l'écoute, avant arrêt | 9 |
| Révisions des voix seules | 2, texte projeté préservé |
| Audio capturé et traité | 12 780 ms dans les deux compteurs, attente finale nulle |
| Attente de fin et fermeture | 1,727 s, fermeture confirmée |

Le texte final reste exactement celui attendu ; les 19 mots gardent leurs temps, 18 sont attribués et un demeure inconnu. L'état de voix mis en cache à la fermeture n'est pas un indicateur de ressource native encore ouverte. Ce test d'intégration ne mesure pas la latence de chaque mot d'une conversation naturelle et ne constitue pas une comparaison A/B contrôlée avec le test 4.

## Conversation AMI de 60 secondes : limite de débit mesurée

Un essai séparé passe la fixture publique anglaise `ami_en2002d_2132.wav` dans `DiarizationNative`, avec le profil actuel à un thread et sans charger Handy. Les 1 920 000 octets PCM sont alimentés par blocs de 20 ms. Le test fonctionnel termine avec succès (1/1, 99,522 s) ; son succès ne signifie pas que le calcul tient le temps réel.

| Mesure AMI | Résultat |
|---|---:|
| Durée audio | 60,000 s |
| Chargement des voix | 0,071 s |
| Temps écoulé pour alimenter et traiter le flux | 96,476 s |
| Temps cumulé des appels de traitement | 88,620 s |
| Retard maximal sur le rythme audio | 37,088 s |
| Finalisation / fermeture | 2,211 s / 0,025 s |
| Probabilités finales | 6 001 trames, toutes stables |
| Locuteurs après segmentation native / référence | 3 / 4 |
| Erreur de diarisation (DER, sans marge aux frontières) | 25,18 % |
| Part de confusion entre locuteurs | 2,66 % |

Le calcul du DER est recoupé en important le scoreur Python amont épinglé et en lui donnant les RTTM exportés : le résultat correspond au port Kotlin. Le dénominateur est de 4 655 trames-locuteurs, avec 616 omissions, 432 fausses détections et 124 confusions. Cette mesure porte sur le diariseur et sa segmentation native ; elle ne mesure ni la transcription française ni l'attribution des mots par le classifieur de DictAI.

Le débit est insuffisant pour du direct sur cet émulateur dans cet essai. Les repères audio communs corrigent le rattachement temporel mais ne résolvent pas cette limite de calcul. Aucun changement de profil ni de bibliothèque native n'est livré sur la seule base du test court. L'essai AMI ne remplace pas non plus une mesure prolongée avec les deux moteurs concurrents ou sur Poco F7.

Preuves locales : `app/build/reports/meeting/conversation-2026-09-27/test5-ami-diarization/`, exécution manuelle PID 14913. Les preuves compactes publiables sont conservées dans `test5-native/`.

## Corrections et tests ciblés

L'admission de chaque bloc déclenche maintenant la copie vers la file des voix avant le réveil du consommateur Handy. Une barrière de préparation attend le modèle de voix après publication du handle annulable, puis autorise la capture. Les transitions de disponibilité, d'échec et d'annulation réveillent cette attente.

À la fin, un écart entre les compteurs des deux branches désactive explicitement les attributions. La frontière stable des voix est plafonnée au PCM capturé, y compris lorsqu'elles avancent plus vite que Handy ; les temps des mots restent limités à l'audio traité par Handy.

Le classifieur agrège les probabilités selon la durée exacte de recouvrement du mot. Il exige une couverture complète et stable, une voix dominante suffisamment distincte et au moins 75 % de la durée avec cette voix seule active. Une voix concurrente active pendant au moins 25 % de la durée du mot, le silence ou une couverture incomplète restent non attribués. Ces seuils sont expérimentaux. Le rejeu des Float32 du diagnostic court conserve les 19 canaux du test 4 à l'identique : aucune amélioration de couverture ni preuve supplémentaire d'exactitude sur cet extrait.

Les tests ciblés du moteur (38) et de la passerelle (11) passent après correction. Ils couvrent le modèle lent à charger, son échec de chargement, l'annulation pendant l'attente, l'identité du premier bloc, Handy bloqué pendant que les voix reçoivent les suivants, les silences, la fin commune, les offres refusées et les erreurs du hook. Les XML de la dernière correction sont archivés dans `app/build/meeting-fanout-evidence/`.

Les suites ciblées d'attribution, d'assembleur et d'identité passent également, ainsi que les 11 tests du packager. L'identité prototype est `com.uhama.whisperpin.meetingtest`, version 39 / `0.9.6-dictai-meeting-test5`.

## Vérification complète des variantes

Sous le JBR 21 d'Android Studio, `:app:testDebugUnitTest :app:assembleDebug` réussit pour la variante normale, puis pour `-PmeetingPrototype=true`. Chaque variante compte 1 115 tests dans 136 rapports XML, sans échec, erreur ni test ignoré. Les preuves sont archivées dans `test5-final/normal/` et `test5-final/prototype/` ; les empreintes des cinq fichiers de production revus sont conservées dans `test5-final/reviewed-source-sha256.txt`.

`assembleDebugAndroidTest` réussit également pour le prototype. Le packager valide l'APK local de 93 164 926 octets, SHA-256 `12758a60e95ed48e16efe28e66e4cd7826dc0fd91aa4d8e7dee327d755b008b7`, identique à celui des essais natifs retenus. Sa signature v2 est valide, les 13 bibliothèques ARM64 respectent l'alignement ELF de 16 Kio et `zipalign` réussit. Le certificat SHA-256 est `6b37c02704d31553b275a9a5f23c8eb650df04cd59f7b28074e6f2dcadbf9539`. Les empreintes JNI restent celles épinglées pour le test 4.

Les métadonnées locales ont été produites avant le commit : leur champ `ciCommit` désigne le HEAD antérieur de la copie de travail modifiée, pas la provenance d'une publication test 5. Le contrôle initial a seulement dû remplacer le chemin absent de `aapt` 35.0.0 par l'outil local installé en 36.0.0 ; aucun code n'a été modifié pour le faire passer.

## Revue de la production figée

La première passe Astra, après correction du plafonnement au PCM capturé, valide le contrat fonctionnel et les chemins d'échec sur les empreintes suivantes. Elle ne constitue pas une mesure de précision sur la vidéo de l'utilisateur.

| Fichier | SHA-256 |
|---|---|
| `MeetingNative.kt` | `dca9e604acce77e1678411474231346360f54b2315a979a4b46b6e000f4ed11f` |
| `MeetingEngine.kt` | `0bf4c3a95b7beaf0cbab84b019abed2dd685d7523f9d30e5e8a429d5cfc8881d` |
| `HandyMeetingNativeBridge.kt` | `df2171e4a23f032e605f384ac73ef0b95b6e34746c60baa9bc5d8383c8ba2cf3` |
| `MeetingHandyTranscriptAssembler.kt` | `a678373bd30847539b50151d64c983317ba60b4d660a28a277b6efb3ed1f053e` |
| `MeetingSpeakerAttribution.kt` | `fa31004efc3a042e3ad6df219f11b63941320e19fd59866a265c014aa8ea6fb6` |

La seconde passe Astra, sur les mêmes empreintes, examine les inversions de verrous, la réutilisation des buffers, l'annulation pendant l'ouverture ou un calcul natif, le drainage et la conservation des retouches. Aucun point bloquant supplémentaire n'est trouvé. Le hook ne fait ni JNI ni I/O ; l'annulation ne prend pas le verrou sérialisant les producteurs. Si un hook générique lève une exception après engagement du bloc, ce seul bloc peut déjà entrer dans l'ASR avant le signal d'échec. Aucune offre suivante n'est admise et la session échoue explicitement ; aucune reprise avec un trou temporel n'est autorisée.

## Publication GitHub Actions

Le commit `503fb097ce1977a7ace0711f504aabe0fababf22` (`fix(meeting): synchronize shared audio capture [meeting-test]`) est publié sur `codex/meeting-mode`. La [construction 36306954957](https://github.com/Uhama91/DictAI/actions/runs/36306954957) s'est achevée avec succès le 27 septembre 2026 à 08:48:38 UTC. Les étapes de test, de compilation Android, de contrôle des bibliothèques natives, de signature, d'alignement et de packaging du prototype ont toutes réussi.

L'artefact [dictai-meeting-test, ID 10927212965](https://github.com/Uhama91/DictAI/actions/runs/36306954957/artifacts/10927212965) appartient à ce commit et n'est pas expiré au moment du contrôle. L'empreinte SHA-256 de l'archive fournie par GitHub est `3087dfb0bc18a4d14135348763e9f46145cad70270985b0c1eb75971e03b7262` ; elle diffère de celle du fichier APK contenu dans l'archive.

L'archive a été téléchargée puis vérifiée : le contrôle CRC et la vérification du manifeste SHA-256 ont réussi. L'identité est `com.uhama.whisperpin.meetingtest`, version 39 / `0.9.6-dictai-meeting-test5`, et la métadonnée `ciCommit` correspond au commit publié. L'APK de 93 164 926 octets a exactement la même empreinte `12758a60e95ed48e16efe28e66e4cd7826dc0fd91aa4d8e7dee327d755b008b7` que le binaire local des essais natifs. Sur le fichier téléchargé, la signature v2, le certificat, l'alignement ZIP, l'alignement 16 Kio des 13 bibliothèques ELF et les deux empreintes JNI épinglées ont été vérifiés. Les reçus compacts sont publiés dans `test5-actions/` ; l'APK téléchargé reste dans le répertoire local de build.

## Limites de validation

La page YouTube a été consultée, mais son audio n'a pas été analysé ici. Le Poco F7 n'est pas connecté. Les temps sur émulateur ne prédisent pas son comportement en conversation naturelle. Les horodatages Handy sont des positions d'émission du décodeur ; ils ne garantissent pas la frontière phonétique exacte de chaque mot. Un chevauchement ou une zone insuffisamment couverte doit pouvoir rester sans attribution.

L'insertion dans ChatGPT Android doit être retestée après activation du service d'accessibilité propre à l'application Réunion test. L'utilisateur a confirmé l'état « À activer dans les réglages » ; dans cet état, le chemin de repli copie le texte dans le presse-papiers. Aucune correction spécifique à ChatGPT n'est établie à ce stade.
