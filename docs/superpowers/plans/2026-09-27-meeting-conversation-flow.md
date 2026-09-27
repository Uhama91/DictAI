# Réunion — continuité de conversation et diagnostic de latence

**Objectif :** suivre une conversation française dans une chronologie audio lisible, mesurer le retard réel et améliorer le traitement seulement sur des comparaisons contrôlées.

**Priorité précisée par l'utilisateur :** conversation réelle entre deux personnes, sur Poco F7. La dictée Nemotron 3.5 Handy Q8 lui paraît presque instantanée sur ce même appareil et constitue la référence à conserver. L'affichage rapide des mots quand une seule personne parle prime sur la séparation parfaite des voix simultanées. Le nom de l'intervenant peut être confirmé après les premiers mots, mais le texte doit rester attaché au bon passage.

**Base vérifiée :** `codex/meeting-mode`, `c4aaa8fc982f951338ef80f30c71b8c3e77fa81b`, APK Actions test3 version 37, JNI `3a7c06e33a052996aa5aa637bfb8bd79f523a6b96c81da0aceb1af7ec2cb9bcc`. Worktree existant réutilisé ; preuves antérieures préservées.

**Méthode :** Astra réalise recherche et arbitrages ; Luna 6 Max implémente les lots bornés et leurs tests. Chantier complexe : deux revues du même état final. Le retour demande une solution ; les corrections locales réversibles sont autorisées. Aucun audio privé n'est envoyé à un service. Le parcours reste local.

## Constats établis avant modification

- Les captures utilisateur montrent plusieurs petits fragments successifs sous le même nom, de grandes séparations et aucun repère temporel. Elles ne permettent pas de mesurer une erreur de reconnaissance ou un retard, faute d'audio synchronisé.
- `MeetingProjection.rows` produit une ligne par `MeetingTurn`, sans regrouper les fins successives d'ASR. `MeetingTurnEditor` réserve 48 dp pour chaque étiquette et au moins 48 dp au corps.
- `MeetingTurn` possède `startMs`/`endMs` mais la projection ne les affiche pas. Le document est ordonné par identifiant d'énoncé puis temps ; aucune preuve d'ordre erroné dans l'audio utilisateur ne peut être déduite des seules images.
- Le JNI choisit explicitement le CPU, RNNT droit 1, `v3-streaming`, modèle ASR 0,6 B Q8 et diarisation Q8. Le runtime utilise quatre threads CPU ; il n'est pas monothread. Les commandes réelles compilent ASR avec `-O3`.
- La dictée Handy utilise `transcribe.cpp` et une conversion Q8 différente de celle du couple NeMo-Speech.cpp/NVIDIA GGUF utilisé en mode Réunion. Les performances de l'un ne permettent pas de conclure celles de l'autre. La sonde et le code du constructeur de résultats confirment des tokens horodatés en streaming, mais aucun mot directement aligné, même en demandant `WORD`. La passerelle Kotlin actuelle n'expose que le texte. L'accès additif aux tokens doit préserver leurs octets UTF-8 et le texte original.
- L'alimentation du moteur de diarisation est synchrone avant la lecture des résultats ASR. Les fins d'énoncé peuvent aussi lancer une prévision de diarisation. Le coût de ces étapes doit être mesuré séparément.
- `queuedAudioMs` n'est pas consommé par le contrôleur de production. Le panneau continue donc d'annoncer l'écoute sans chiffrer le retard. À elle seule, la file ne comptabilise pas le bloc actuellement en cours de calcul.
- Le modèle français est supporté, mais les résultats constructeur sont des mesures de modèle et non des garanties sur Poco. Le réglage RNNT 3 a précédemment perdu un mot horodaté dans une fixture française : aucune promotion sur la seule vitesse.
- La diarisation détecte qui est actif, y compris en chevauchement. Notre intégration ASR fournit une seule séquence de mots avec un seul tag par mot. Ce n'est pas une séparation des voix et elle ne garantit pas deux transcriptions simultanées.

## Sources primaires consultées

- [NVIDIA : distinction diarisation / transcription et limites des chevauchements](https://huggingface.co/blog/nvidia/nemotron-diarization).
- [Carte Nemotron 3.5 ASR : français, contextes 80 à 1 120 ms et précision](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b).
- [Carte Nemotron 3 Diarization : configurations et délai hors calcul](https://huggingface.co/nvidia/Nemotron-3-Diarization).
- [Runtime épinglé](https://github.com/NVIDIA/NeMo-Speech.cpp/tree/97a15afa5caa9bce5baaa86c1184103877af4101) : `src/asr/recognizer.cpp`, `src/asr/diar/aosc_state.h`, `src/runtime/ggml/session.cpp`.
- [Handy : C API épinglé](https://github.com/handy-computer/transcribe.cpp/blob/553f1099a2b3a5bc4421894be171f09960fc0f3a/include/transcribe.h) et [capacités Nemotron](https://github.com/handy-computer/transcribe.cpp/blob/553f1099a2b3a5bc4421894be171f09960fc0f3a/docs/models/nemotron-3.5-asr-streaming-0.6b.md).
- [Poco F7 : Snapdragon 8s Gen 4, Adreno 825, 12 Go](https://www.mi.com/global/product/poco-f7/specs/). Aucune mesure d'exécution sur ce téléphone n'est disponible par ADB dans cette session.

## Lot A — mesure comparative native, avant réglage

Responsable Luna `meeting_native`. Propriété : sonde et preuves sous `app/build/reports/meeting/conversation-2026-09-27/` et cache natif ; pas de modification de la bibliothèque livrée avant arbitrage.

- [x] Vérifier sonde, modèles et configuration contre test3.
- [x] Mesurer ASR seul puis ASR + diarisation, sur le même français synthétique puis AMI 60 s déjà local ; mêmes fenêtres et horloge, sans compilation simultanée.
- [x] Conserver temps de calcul/audio, premier texte, retard au fil de l'audio, temps de finalisation, mémoire, mots et tags. Distinguer flux cadencé et traitement différé.
- [x] Localiser les coûts et vérifier les options CPU réellement compilées. Ne pas déduire la vitesse du Poco de celle de l'émulateur.
- [x] Autoriser une expérience uniquement avec hypothèse et comparaison qualité/temps explicites. Garder le témoin et les assertions de fidélité ; rejeter toute perte de parole non résolue.
- [x] Comparer Handy avec les paramètres exacts de la dictée, puis vérifier le coût et la couverture des timestamps demandés au C API. Aucun changement de modèle préféré n'est demandé à l'utilisateur.

### Premier profilage obtenu — aucune extrapolation au Poco

Les traces temporaires de la sonde sont dans `app/build/reports/meeting/conversation-2026-09-27/`. Langues explicites : français pour la fixture synthétique, anglais pour AMI. Ces passes activent `NEMO_SPEECH_TIMING` et effectuent un préchauffage séparé : elles localisent le travail et ne constituent pas une mesure du démarrage de l'application livrée. Une comparaison quantitative avec Handy exige des conditions communes, notamment l'absence des traces par graphe.

| Audio | Chemin | Temps du flux | Calcul dans push | Calcul dans next |
| --- | --- | ---: | ---: | ---: |
| Français synthétique, 12,78 s | ASR seul | 33,557 s | ~0 s | 32,321 s |
| Français synthétique, 12,78 s | ASR + diarisation | 37,431 s | 11,399 s | 23,168 s |
| AMI, 60 s | ASR seul | 104,926 s | ~0 s | 103,516 s |
| AMI, 60 s | ASR + diarisation | 277,468 s | 155,098 s | 117,498 s |

Ces coûts et leurs variations entre passages ne démontrent pas une accélération de l'ASR par la diarisation : les chemins de calcul et la concurrence interne diffèrent. Ils démontrent que le traitement synchrone de diarisation occupe une part importante du flux long. Le burst AMI a commencé, puis a été interrompu à 13,6 s d'audio poussé ; il est exclu des comparaisons.

### Comparaison courte sans traces de graphes

Même fixture française de 12,78 s, blocs de 20 ms cadencés, CPU quatre threads, sans préchauffage synthétique. Les caches de fichiers diffèrent : les chargements ne sont donc pas comparables.

| Chemin ASR seul | Premier texte après début d'alimentation | Temps du flux avec cadence audio | Chargement |
| --- | ---: | ---: | ---: |
| Handy, paramètres de dictée | 1,517 s | 16,524 s | 7,028 s |
| Handy, deuxième passage avec demande WORD | 1,294 s | 12,955 s | 2,039 s |
| NeMo, RNNT droit 1 | 7,921 s | 30,351 s | 0,644 s |

La demande `WORD` a encore retourné des tokens, sans mots. Les différences entre les deux passages Handy ne prouvent donc pas un gain dû à cette option. Les transcriptions des deux moteurs diffèrent sur une partie de la fixture : cette comparaison établit une différence de délai sur l'émulateur, sans conclure une supériorité linguistique générale ou un délai sur Poco. L'architecture retenue est documentée dans [la spécification Handy](../specs/2026-09-27-meeting-handy-live.md).

## Lot B — chronologie et continuité visuelle

Responsable Luna `swipe_ui`. Propriété : tests puis projection/panneau/éditeur de réunion après arbitrage. Les identifiants de texte, retouches et marqueurs d'images restent la source de vérité.

- [x] Reproduire le morcellement avec deux fins ASR d'une même prise de parole et le parcours A/B/A.
- [x] Vérifier l'horodatage selon l'audio, jamais selon l'heure d'arrivée du résultat. Ne pas attribuer un faux temps précis à un passage sans alignement.
- [x] Choisir un groupement visuel qui conserve chaque identité éditable et ne masque ni retouche, ni changement de personne, ni pause réelle.
- [x] Garder `label` comme identité logique et ajouter un indicateur de présentation séparé. Regrouper seulement des passages identifiés adjacents, alignés sur l'audio et espacés d'au plus 1 500 ms. Les passages incertains, documentaires ou sans alignement constituent des séparations.
- [x] Ajouter une provenance temporelle explicite, absente/false pour les documents anciens et les replis sans mots alignés. Un tri temporel ne traverse jamais un passage sans repère fiable.
- [x] Une révision tardive met à jour le passage existant ; elle ne le rajoute pas à la fin et ne déplace pas le curseur humain. Contrats JVM et neuf révisions natives préservant le texte vérifiés.
- [x] Garder les passages incertains explicitement incertains ; ne pas inventer des paroles ou une attribution simultanée.
- [x] Vérifier texte, ordre, retouches, images, retour dossiers et annulation ; inspecter les 32 rendus natifs finaux. Les scènes UI et de gestes ont un contenu simulé ; le texte reconnu est vérifié séparément par le test intégré.

## Lot C — retard observable et diagnostic sans contenu

Responsable Luna `meeting_pipeline`. Propriété initiale : helper, tests déterministes et compteurs moteur ; raccordement contrôleur/panneau attribué après validation de l'interface.

- [x] Test RED : 3 blocs captés, un bloc retiré de la file mais encore en calcul. Le retard doit compter ce bloc en vol.
- [x] Compteurs monotones d'audio accepté/achevé et de durée de calcul, sans audio ni texte dans les diagnostics.
- [x] Mesurer le ratio temps de calcul/durée audio traitée sans confondre attente d'entrée et calcul ; horloge injectée pour tests.
- [x] Vérifier annulation, reprise, fermeture, erreurs, valeurs bornées et absence de résultat tardif.
- [x] Raccorder un état utilisateur lisible, avec retard en secondes lorsque significatif ; ne pas transformer une simple charge en arrêt automatique.

Preuve intermédiaire B/C : sélection propre de 122 tests réussis sous JDK 21, trace `/tmp/meeting-progress-ui-green3.log`. Les captures natives et le raccord aux deux modèles réels restent distincts de cette preuve JVM/Robolectric.

## Revue et livraison

- Toute amélioration du rendu doit être séparée de la précision du modèle et de la vitesse physique.
- Deux personnes réellement simultanées exigent une stratégie de séparation ou plusieurs canaux pour garantir deux textes ; le prototype actuel ne doit pas prétendre le faire.
- Une configuration accélérée doit passer le même audio de référence et garder la fidélité avant d'être retenue.
- Vérifier les tests ciblés, les variantes concernées, les 16 Kio/signature/identité de tout nouvel APK et les gestes déjà livrés.
- Le verdict final doit dire ce qui est démontré, ce qui est corrigé, ce qui reste limité par le moteur et ce qui requiert un essai Poco.

## Lots complémentaires retenus après le diagnostic

### D — ponts et transcription indépendante

`meeting_native` possède les ponts JNI additifs et leurs façades Kotlin ; `meeting_pipeline` possède l'assembleur et le coordinateur. Les signatures et limites sont dans la spécification Handy.

- [x] Accès Handy au texte UTF-8 et aux tokens, sans changement de la dictée existante ; fenêtres bornées et finalisation de la partie active.
- [x] Diarisation autonome avec origine absolue des trames, cadence native, probabilités et frontière de stabilité ; aucune dépendance au modèle ASR NeMo.
- [x] Assembleur conservateur, fragments de 64 mots maximum, texte et ponctuation préservés ; aucune attribution si le rapprochement texte/tokens échoue. Tests JVM et rapprochement final avec les résultats natifs vérifiés.
- [x] Coordinateur à deux workers : disponibilité, révisions tardives, fermeture et annulation vérifiées avec barrières déterministes. Le test natif du chemin intégré passe en 57,5 s ; neuf révisions de voix conservent le texte.
- [x] Historique révisable limité à 120 secondes et 256 fragments d'énoncés. La file PCM diar est séparément limitée à 120 secondes : son débordement suspend l'identification, sans découper artificiellement la chronologie ni arrêter Handy.
- [x] Comparaison native Handy seul / Handy + diar réellement simultanés, mêmes entrées et paramètres. Texte, premier résultat, calcul, retard et mémoire observés. Le test court passe ; le test prolongé échoue sur le drainage des voix. Cet échec reste explicitement conservé dans le verdict de prototype.

La question de l'utilisateur sur la certitude de cette architecture impose de distinguer séparation logique et capacité matérielle : deux workers continuent de partager CPU et mémoire. Une dégradation notable dans le test simultané déclenche un nouvel arbitrage de cadence diar ; elle ne doit pas être masquée par un simple test avec faux moteurs.

La mesure corrigée à quatre threads par modèle a déclenché cet arbitrage : fin de texte Handy à 85,766 s contre 18,890 s et 21,885 s pour les témoins seuls. Cette configuration est rejetée. Expérience suivante : un thread pour la diarisation autonome, Handy inchangé, patch reproductible et nouvelle comparaison avant raccord applicatif. La couverture audio et le texte de l'essai corrigé passent ; le test long de cette configuration rejetée n'a pas été lancé.

La nouvelle comparaison courte à un thread pour les voix conserve exactement le texte : fin Handy à 20,549 s contre 19,737 s et 16,494 s pour les témoins. Le ralentissement majeur n'est plus reproduit, mais un surcoût de 4 à 25 % demeure suivant le témoin et les voix finissent encore plus tard. La prolongation sur 63,9 s échoue : le worker de voix ne termine pas dans les 120 s accordées après Handy. Le test intégré court passe ensuite. Astra autorise le raccord par défaut uniquement comme prototype test4 pour mesurer le comportement sur Poco, sans conclure à une diarisation soutenue en temps réel.

### E — modèles et raccord applicatif

`swipe_ui` possède le catalogue et le réemploi local. `meeting_pipeline` a raccordé le coordinateur par défaut après l’arbitrage expérimental ; la validation d’une version stable reste distincte de cette livraison test4.

- [x] Les deux constructeurs publics du moteur utilisent le coordinateur Handy/diarisation par défaut, après l’arbitrage expérimental ci-dessus.

- [x] Catalogue v2 épinglé sur Handy Q8 ; l'ancien paquet NeMo ne peut pas devenir Ready sous cette identité.
- [x] Lors de la préparation explicitement demandée, copie des fichiers privés déjà présents, avec taille/SHA et publication atomique. Les sources et brouillons restent intacts ; sinon téléchargement habituel.
- [x] Afficher le retard mesurable et l'indisponibilité éventuelle des voix sans bloquer la transcription. Le rafraîchissement périodique met à jour l'en-tête seulement. Les 52 tests du dernier ajustement de statut passent ; les captures natives restent un contrôle séparé.
- [x] Lectures micro de 640 octets (20 ms), avec un plancher matériel indépendant de 6 400 octets, augmenté selon le minimum Android et la taille des blocs. Dix tests AudioRecord réussis ; le test comportemental avait constaté 3 200 octets avant correction.
- [x] Captures natives, deux revues sur le manifeste final des 61 fichiers, variante test4 identifiée et APK local vérifié. Voir la galerie et les revues dans le dossier de preuves.
- [x] Livraison Actions terminée, artefact téléchargé et identité/empreintes vérifiées : [run 36293735948](https://github.com/Uhama91/DictAI/actions/runs/36293735948), commit `30d66dfa00f394745f56626745059bc970adce3d`, artefact `10922798022`, APK `bca6f0ee7c233e1b58c0566bd17845d4fb7e357fe12bf8ecaf2b94217b3a37a7`. Le premier run a révélé une attente prématurée de test ; le correctif JVM seul a été revu deux fois et ciblé dans les deux variantes avant ce second run réussi. Signature, identité test4/code 38, 13 ELF et ZIP à 16 Kio contrôlés. L’essai physique Poco et la fluidité prolongée restent ouverts.
