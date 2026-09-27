# Réunion test 5 : diagnostiquer puis corriger l’attribution temporelle

## Retour et objectif

Sur Poco F7, le test 4 conserve les paroles avec Handy, mais, après une première identification, les passages restent souvent « Intervenant à confirmer ». Le nouvel essai porte sur le début de https://youtu.be/C292J5ZMuro, une conversation à quatre intervenants selon l’utilisateur. La page publique est accessible ; son audio n’a pas été analysé dans cette session. Ne pas présenter les fixtures synthétiques comme une reproduction de cette vidéo.

L’insertion dans ChatGPT Android est un problème distinct : l’utilisateur confirme que l’accessibilité de l’application de test indique « À activer dans les réglages ». Le chemin actuel copie le texte lorsque son contrôleur d’insertion est absent. L’activation du service est nécessaire avant d’évaluer un éventuel défaut d’injection.

## Constats vérifiés et hypothèses

- Les deux moteurs consomment le même PCM ordonné. Les horodatages sont relatifs à cet audio ; l’arrivée tardive d’un résultat ne doit pas changer le passage associé.
- Le modèle de diarisation fournit des probabilités de voix par trame, pas des mots. Le runtime NVIDIA épinglé associe une plage à la voix dont la probabilité moyenne est la plus élevée (`DiarStream::speaker_for_frames`). DictAI exige actuellement la même voix unique au-dessus de 0,5 pour chaque trame du mot : un creux de 10 ms suffit à tout rejeter.
- Le test 4 a déjà échoué au drainage de l’essai prolongé de 63,9 s : ne pas répéter le même test sans nouvelle instrumentation et ne pas attribuer cette limite au Poco sans mesure.
- Un échec d’alignement texte/tokens rend actuellement `alignmentBroken` permanent. Distinguer les mots sans temps, les plages pas encore analysées et les probabilités ambiguës avant d’arbitrer une modification.

## Travail borné et critères

1. Luna du volet natif : instrumenter les ports réels sur la fixture française existante, conserver les fenêtres de tokens et les probabilités pour un rejeu déterministe, mesurer les raisons de non-attribution et le retard par durée audio. Séparer le texte fidèle, la couverture d’attribution et l’exactitude par locuteur. Aucun audio privé ni transcription personnelle dans les preuves.
2. Luna du volet attribution : reproduire par un test rouge le veto d’une microcoupure dans une plage de parole dominée par une voix. Rapprocher les mots des plages couvertes avec un score temporel agrégé ; conserver des garde-fous pour le silence, l’égalité, le chevauchement et les changements de voix. Aucun prolongement sur des trames absentes, aucune association selon l’heure de retour. Valider le choix final sur les fenêtres natives mesurées avant livraison.
3. Si le calcul des voix est la cause dominante, comparer un nombre borné de géométries de traitement avec le même cache de locuteurs et le même Handy. Une fenêtre audio plus grande peut amortir le calcul répété du cache ; elle augmente aussi le délai minimal des étiquettes. Ne retenir un réglage qu’avec des mesures de temps, de couverture et de changements de voix.
4. Préserver le texte complet, les identifiants, les retouches, les images, la chronologie et l’annulation. Aucun modèle de transcription supplémentaire ; Handy reste inchangé.
5. Effectuer des tests ciblés, puis les vérifications adaptées aux changements retenus. Deux revues par Astra sur le même état figé pour le raccord natif/concurrent. Toute modification visuelle reçoit des captures Android inspectées. Publication Actions et identité de l’APK vérifiées si une correction est livrée ; ne pas annoncer une conversation naturelle validée sur Poco sans retour réel.

## Précision demandée pendant le travail : alimentation réellement indépendante

L’utilisateur confirme l’essai avec les deux moteurs alimentés en parallèle et un découpage fondé sur leurs horodatages. La recherche trouve deux dépendances actuelles : la copie vers Réunion suit l’appel natif Handy, et les deux restent derrière la file de traitement ASR de `MeetingEngine`. Déplacer seulement cette copie avant un appel Handy ne libérerait donc pas les blocs suivants déjà capturés.

Le raccord retenu ajoute un hook de capture à la passerelle, sans inférence ni attente de décodeur. Une fois le bloc accepté dans la file commune et avant le réveil du consommateur ASR, le même PCM est copié dans la file des voix. L’ordre d’admission est identique pour les deux branches. L’appel de traitement Handy ne doit plus réadmettre cet audio. Les passerelles historiques gardent un hook vide ; leurs comportements ne changent pas.

Le test déterminant bloque Handy sur le premier bloc et vérifie que les deuxième et troisième blocs capturés atteignent quand même le moteur des voix, une seule fois et dans l’ordre. L’annulation interdit les publications tardives. Un échec de copie ne doit jamais être suivi d’une reprise des voix avec un trou temporel : leur branche devient indisponible, ou la session signale un échec contrôlé si le contrat de la passerelle est rompu.

L’utilisateur précise ensuite une exigence impérative : le micro commence seulement quand les deux modèles sont prêts. Cette exigence remplace la disponibilité anticipée de Handy pendant le chargement des voix prévue pour le test 4. Une barrière de préparation est donc attendue sur le worker Engine, après publication du handle annulable mais avant `onReady`. Une erreur de chargement des voix empêche le début de capture ; aucune fausse indication d’écoute. Le passage des voix à ACTIVE, l’échec et l’annulation doivent tous réveiller cette attente.

L’origine est le premier échantillon accepté du micro commun. Les pauses retirent les mêmes périodes aux deux branches ; la reprise poursuit leur compteur audio commun. À l’arrêt, le même dernier échantillon est leur frontière d’entrée, puis chaque file se vide avant sa finalisation. Les calculs ne sont pas obligés de finir ensemble. Les échantillons de padding internes aux modèles ne deviennent pas une durée réellement enregistrée. Les tests doivent retarder le chargement d’un moteur et annuler pendant cette attente, puis vérifier le premier bloc, l’ordre des blocs, les pauses et la frontière de fin.

La sonde conserve le classifieur du test 4 dans une copie de référence réservée aux tests afin de comparer les mêmes fenêtres natives avant et après correction. L'extrait anglais d'AMI de 60 secondes fourni par NVIDIA, avec les références RTTM et le texte manuel, sert à examiner les identités et le débit ; il ne remplace pas une validation linguistique française.

Après le premier diagnostic frais (retard des voix maximal de 1,6 s sur 12,8 s), aucune modification de géométrie native n'est justifiée pour cette livraison. Le profil `chunk13` et les bibliothèques épinglées sont conservés. La mesure AMI utilisera la passerelle Android actuelle sur un seul profil, sans nouveau probe C++ ni framework de benchmark. Le résultat court ne remplace pas l'échec prolongé précédent.

Le plafonnement de la frontière stable des voix doit utiliser le nombre d'octets capturés et admis, non le nombre déjà traité par Handy. Les voix peuvent analyser des blocs avant le moteur de texte ; cette avance ne constitue pas du padding. Les temps d'émission du texte restent, eux, bornés par l'audio traité par Handy.

## Organisation

Risque complexe : synchronisation de deux flux natifs et attribution du texte. Astra conduit les recherches, l’arbitrage et les deux revues. Luna 6 Max réalise les modifications et les tests bornés. Un seul propriétaire ADB/Gradle à la fois ; les fichiers de production et de test restent distincts entre agents. Aucun commit ni push sans arbitrage explicite d’Astra.

## Sources examinées

- [Modèle NVIDIA](https://huggingface.co/nvidia/Nemotron-3-Diarization) : plages temporelles et latence de tampon distincte du coût de calcul.
- [Guide d’intégration NVIDIA](https://huggingface.co/nvidia/Nemotron-3-Diarization/blob/main/ASR_INTEGRATION_GUIDE.md) : modèle de voix associé à un ASR ; le montage multitalker documenté ne constitue pas une preuve du débit Android de notre raccord Handy.
- Code local épinglé NeMo-Speech.cpp `97a15afa5caa9bce5baaa86c1184103877af4101`, `diar_pipeline.cpp` et `aosc_state.h` ; Handy `553f1099a2b3a5bc4421894be171f09960fc0f3a`.
