# Réunion : attribution différée sur une chronologie audio conservée

> Exécution autorisée dans la continuité du chantier Réunion. Astra conduit la conception et les deux revues ; Luna 6 Max implémente et teste chaque tâche bornée. La tâche est complexe. Aucune modification des modèles ou bibliothèques natives dans ce correctif.

**But :** afficher le texte Handy sans attendre les voix et rapprocher les résultats arrivant plus tard des mots d’origine, sans déplacer les horodatages au gré de la vitesse des moteurs ni créer un bloc par mot.

**Architecture :** les deux branches consomment le même PCM ordonné, avec des positions absolues depuis le premier échantillon. L’attribution utilise le recouvrement des intervalles audio existants. La rétention suit l’avancement de la branche lente. Une file audio privée sur disque absorbe le retard des voix ; elle ne prétend pas accélérer leur calcul. L’affichage provisoire conserve des passages continus, puis les révisions établissent les changements de personne.

**Constats :** test5 conserve les chunks révisables selon l’avancement Handy moins 120 secondes, même si les voix n’ont pas encore analysé leur intervalle. Sa file des voix coupe à 120 secondes en RAM. Le reducer protège les fragments provisoires non édités comme s’il s’agissait d’ancrages manuels ; le panneau conserve les lignes vides. Ces points ont été vérifiés à la lecture du code et reproduits par des tests avant correction. La vidéo privée reste hors du dépôt.

**Contraintes :** Handy Q8 et le diariseur installés restent identiques ; aucune compensation par une constante de latence calculée sur l’heure de réception. Conserver l’annulation, la barrière de préparation, une même position du dernier échantillon traité par les deux branches, les retouches, les attributions manuelles et les images. Pas d’envoi d’audio utilisateur. Ressources bornées avec échec explicite de l’attribution si une limite est atteinte, sans perte de la transcription Handy. La précision phonétique des temps de tokens reste une limite indépendante.

## 1. Conservation des mots jusqu’au traitement des voix

Propriétaire : Luna `late_alignment` — MeetingHandyTranscriptAssembler.kt et son test.

- [x] RED : première phrase avec temps et texte complets ; Handy avance au-delà de la fenêtre de rétention ; une fenêtre de voix tardive doit encore réviser cette phrase. Répéter avec le chemin du snapshot inchangé.
- [x] Remplacer la purge liée uniquement à Handy par une frontière tenant compte des voix stabilisées. Ne pas oublier les mots avant que leur intervalle ait été examiné ; conserver une marge de révision après stabilisation.
- [x] Garder la limite de chunks en mémoire. Si cette limite imposerait d’abandonner un chunk encore en attente, exposer un indicateur interne attributionRetentionExhausted. La passerelle désactivera explicitement les voix ; les mots doivent continuer à être émis exactement une fois.
- [x] GREEN : retard variable, voix avant/après Handy, fin tardive, silence, texte inchangé, limite de chunks, ponctuation/UTF-8, absence de duplication. Les tests de purge existants doivent distinguer les chunks déjà analysés des chunks en attente.

## 2. Retard audio des voix conservé hors du chemin de calcul Handy

Propriétaire : Luna `delayed_audio` — HandyMeetingNativeBridge.kt, MeetingEngine.kt, nouvelle file MeetingDiarizationBacklog.kt et tests dédiés.

- [x] RED : bloquer le moteur de voix, faire admettre plus de 120 secondes tout en laissant Handy publier ; libérer les voix et comparer tous les échantillons reçus, l’ordre et les compteurs finaux.
- [x] En production, utiliser une file mémoire courte et une file privée sur disque réutilisant MeetingAudioQueue/MeetingAudioSpool. Un worker de transfert réalise les I/O ; le hook d’admission PCM conserve son contrat sans JNI ni I/O. Préparer le cache avant de déclarer les deux branches prêtes.
- [x] Garder l’entrée mémoire bornée et la réserve disque bornée à la capacité existante de MeetingAudioQueue. Distinguer échec de stockage et limite atteinte. Toute rupture interrompt l’attribution sans reprendre sur une timeline comportant un trou.
- [x] Si les voix prennent de l’avance sur Handy, borner cette avance à une marge inférieure à la fenêtre de probabilités conservée (60 secondes pour une fenêtre de 120 secondes), afin de ne pas perdre les probabilités avant l’arrivée des mots. L’attente doit être réveillée par les progrès de l’ASR, la fin de la session et l’annulation.
- [x] Propager attributionRetentionExhausted comme limite explicite des voix. Préserver le texte final avant l’attente de drainage, les révisions tardives, l’annulation et le nettoyage privé.
- [x] GREEN : cache réellement utilisé, FIFO octet pour octet, copie des buffers, arrêt commun, annulation sous blocage natif et I/O, limites, échec de la préparation ou de l’écriture, Handy lent, arrêt sans données, isolation de deux sessions et cleanup. Aucun appel d’I/O dans le hook de capture.

## 3. Présentation continue et révision des tours

Propriétaire : Luna `coherent_turns` — MeetingTranscriptReducer.kt, MeetingProjection.kt, MeetingPanelController.kt et leurs tests ; captures Android dédiées si nécessaire.

- [x] RED : snapshots successifs « bonjour les », « bonjour les amis », puis mots tous horodatés mais sans voix ; les suffixes provisoires ne doivent pas devenir trois tours permanents. Une révision tardive doit créer deux tours si deux canaux différents sont établis.
- [x] Réserver la protection des ancrages aux retouches et attributions manuelles ; ne pas figer la fragmentation d’un suffixe provisoire non édité. Conserver les identifiants des vrais passages autant que possible.
- [x] Éliminer les lignes vides issues des révisions dans le panneau, en préservant une édition active et les blocs d’images. Ne pas supprimer une retouche ou une attribution manuelle sous prétexte de regroupement.
- [x] Éviter les répétitions d’en-tête pour la continuation d’un passage provisoire adjacent, sans prétendre qu’il s’agit d’une personne identifiée et sans fusionner des personnes distinctes confirmées.
- [x] GREEN : reconstruction sans perte/duplication, frontière A/B/A, retouches pendant l’attribution tardive, répétitions lexicales, curseur/composition, images, champs volontairement vidés. Capturer et inspecter le rendu avant/après.

## Intégration et critères de livraison

- [x] Une seule exécution Gradle à la fois dans ce worktree ; utiliser le JDK 21 d’Android Studio. Préserver les preuves RED/GREEN avant les suites générales.
- [x] Rejeu intégrant l’assembleur et le reducer avec un retard supérieur à deux minutes et des résultats livrés à des vitesses variables : texte identique, ordre chronologique, même attribution finale que le traitement sans retard.
- [ ] Vérification native sur la fixture publique disponible ; mesurer séparément le résultat d’alignement et la durée du traitement. Aucun résultat sur émulateur ne prouve la latence sur le Poco.
- [x] Deux revues Astra sur le même état figé : contrat fonctionnel/UX, puis concurrence, ressources, sécurité et régressions. Corrections bornées puis nouvelles vérifications si nécessaire.
- [x] Suites JVM normales et prototype : 1 135 tests chacune ; APK et androidTest compilés, signatures et alignement contrôlés ; identité prototype test6/version40, version habituelle 35 conservée.
- [ ] Publication sur GitHub Actions dans la continuité de la demande de version test, avec vérification de l’artefact et de son SHA.
- [ ] Bilan en français : comportement obtenu, preuve du rattrapage, limite de précision des horodatages et validation physique restante. Mise à jour du journal CLAUDE.md en deux lignes maximum.

Sources consultées directement par Astra : [API de diarisation NVIDIA](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/main/include/nemo_speech/diar.h), qui expose des intervalles et des probabilités sans transcription ; [contrat des bindings Handy](https://github.com/handy-computer/transcribe.cpp/blob/main/docs/bindings.md). Le code épinglé du dépôt, et non la branche amont courante, détermine les capacités livrées.
