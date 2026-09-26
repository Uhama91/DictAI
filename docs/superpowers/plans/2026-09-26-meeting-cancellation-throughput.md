# Annulation et continuité du mode Réunion

**Objectif :** permettre l’annulation par glissement à droite, retrouver la racine des dossiers et corriger les causes identifiées de démarrage lent, de saturation audio et de perte de texte.

**Base :** `codex/meeting-mode`, commit `8a12242`, dernière Actions réussie `36204337558`. Le worktree Réunion existant est réutilisé. Les autres travaux et preuves présents sont préservés.

**Exécution :** politique Sol–Luna canonique. Luna 6 Max implémente en TDD ; Astra conduit deux revues distinctes du même état figé, fonctionnelle puis adversariale. Aucun commit ni push sans autorisation explicite. Kotlin, Android, Robolectric et JNI C++.

## Contrat utilisateur

- Un glissement horizontal à droite sur la pastille annule l’opération en cours, y compris la préparation et la finalisation. Le micro est arrêté sans attendre que tout le retard de transcription soit traité.
- En réunion, le texte déjà obtenu et les retouches restent dans le brouillon. L’audio restant à transcrire est abandonné à l’annulation. Aucun document existant n’est supprimé.
- Dans un dossier de notes, y compris « Sans dossier », ce même geste revient à la liste des dossiers (écran « Mes notes », action « Mes dossiers »). Il est reconnu sur la liste et sur la pastille, avec priorité au retour lorsque le dossier est ouvert.
- Les gestes verticaux, le défilement et le déplacement après appui long restent distincts. Un geste interrompu, insuffisant ou vers la gauche n’annule rien.
- Le double appui ne sert plus à annuler une session active. L’ouverture historique de l’application depuis le repos peut être conservée.
- Le cercle de boucles du mode Réunion est agrandi dans la pastille existante de 74 × 44 dp, avec une marge conservée pendant toute la rotation. La hauteur de dessin de Dictée reste à 32 dp.
- Le retard du moteur ne provoque plus une coupure après dix secondes d’audio en attente. Le tampon temporaire reste privé, borné et supprimé après utilisation ; une véritable limite de stockage doit rester signalée.
- Une transcription complète ne doit pas être remplacée par une liste de mots partiellement attribués. En cas d’incertitude, conserver le texte sans inventer son auteur.

## Diagnostic établi

`MeetingAudioQueue` contient 320 000 octets, soit dix secondes de PCM16 mono à 16 kHz. Son débordement déclenche la finalisation. `MeetingPillInteraction` n’offre aucune annulation et ignore les gestes pendant les transitions. Dans le contrôleur, l’annulation native n’est envoyée que si aucune commande FINISH n’a déjà été envoyée.

Le démarrage JNI exécute `Recognizer::warmup()` avant la disponibilité du micro. Dans la source épinglée, ce préchauffage traite quatre secondes de silence ASR puis vingt-quatre secondes de silence pour la diarisation. Le contexte droit RNNT est laissé à la valeur générique 1 ; le modèle fournit sa propre géométrie entraînée. Enfin, le reducer utilise les mots horodatés dès qu’ils sont valides, sans vérifier qu’ils couvrent le texte complet.

## Lot 1 — Gestes et navigation

**Fichiers :** `OverlayService.kt`, `MeetingPillInteraction.kt`, helpers de gestes et tests correspondants. Responsable : Luna `swipe_ui`.

- [x] Reproduire les absences de swipe droit par des événements tactiles Robolectric.
- [x] Ajouter une décision horizontale partagée, un retour dossier qui intercepte les enfants cliquables et un routage vers les annulations existantes.
- [x] Vérifier préparation, écoute, pause, traitement et finalisation ; vérifier aussi les retours de dossier, le défilement vertical, le déplacement après maintien, les gestes interrompus et les taps différés.
- [x] Relire les libellés et inspecter les 13 captures natives du parcours Android sur émulateur ARM64.
- [x] Intégrer la demande complémentaire de cercle plus grand : viewport Réunion de 44 dp, retour à 32 dp en Dictée ; refaire les captures et vérifier le rayon tracé, trait compris. Les 13 captures du parcours et les 5 captures de dessin final ont été inspectées.
- [x] Vérifier que l’aide d’accessibilité annonce le retour aux dossiers lorsque le menu est ouvert, puis retrouve l’aide d’annulation après sa fermeture.

## Lot 2 — Cycle de vie, file audio et intégrité du texte

**Fichiers :** contrôleur, moteur, file et stockage temporaire dans `meeting/`, avec leurs tests. Responsable : Luna `meeting_pipeline`. Le reducer et ses tests sont confiés à Luna `meeting_native`.

- [x] Reproduire l’annulation ignorée après FINISH, les courses de sauvegarde et les futurs laissés en attente.
- [x] Donner priorité à CANCEL, arrêter le micro et rejeter les résultats tardifs ; conserver la réservation native jusqu’à la fermeture réelle.
- [x] Utiliser en production un spool privé segmenté, borné à 128 Mio d’audio en attente, avec libération progressive de l’espace. Garder une petite file mémoire et une interface de test contrôlable. Passer explicitement le répertoire de cache depuis l’application.
- [x] Vérifier l’ordre exact des PCM avec un moteur volontairement lent, les checkpoints, la vidange normale, l’annulation et le nettoyage des fichiers ; vérifier les vraies limites de stockage.
- [x] Reproduire une phrase complète accompagnée de mots horodatés incomplets. Corriger sans perdre le texte ni écraser les retouches humaines ; vérifier les tests d’attribution existants.

## Lot 3 — Coût natif et validation

**Fichiers :** JNI Réunion, configuration/probe natifs, tests et binaire associé. Responsable : Luna `meeting_native`.

- [x] Reproduire le coût du préchauffage et conserver la référence avant modification.
- [x] Retirer le préchauffage synthétique du démarrage interactif. L’essai du contexte RNNT fourni par le modèle (`-1`, soit 3) est retiré après une régression sur le premier mot du témoin français ; rétablir explicitement le contexte historique 1 et garder les assertions existantes.
- [x] Reconstruire JNI avec les sources épinglées et vérifier ABI, empreinte et alignement 16 Kio.
- [x] Comparer une même fixture vocale avant/après lorsque le runtime local le permet. Distinguer les mesures d’émulateur des performances sur Poco.

## Validation finale et points de revue

- Annulation durant une sauvegarde déjà engagée : aucune suppression ni future abandonnée.
- Retour depuis une ligne de dossier : aucun clic ou appui long déclenché par le swipe.
- Appel natif encore en cours après annulation : aucune réutilisation prématurée du modèle.
- Stockage plein, erreur de fichier ou interruption : arrêt explicite et sortie accessible.
- Métadonnées de voix partielles ou révisées : texte conservé et retouches protégées.

Après les tests ciblés, exécuter les suites des variantes normale et Réunion, construire l’APK isolé et vérifier son identité, sa signature et son alignement. Inspecter les captures disponibles. Produire un bilan avec les preuves et les limites physiques restantes ; demander l’autorisation de publication seulement lorsque le résultat est prêt à être publié.

Validation locale terminée le 27 septembre : 1 022 tests par variante, 9 tests Android sur l’APK retenu, tests natifs et paquet conformes ; 20 captures inspectées. Deux revues Astra favorables sur le manifeste rev10 `29f381eb67b398f1403f717485c063a3a021096516d08dc282c7bacaf0a065e1`. La publication requiert l’autorisation explicite prévue par la politique locale ; les performances et la qualité acoustique restent à vérifier sur Poco F7.
