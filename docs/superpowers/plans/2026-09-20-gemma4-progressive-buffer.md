# Segments corrigés pendant la dictée

Lot complexe, deux revues du principal. Première brique pure Kotlin, sans modification d'OverlayService, de son éditeur, du moteur natif ou des fichiers de téléchargement. Propriété Luna : un petit état de segments progressifs, ses tests et son rapport. Le branchement à l'application fera l'objet d'une seconde étape explicite.

## Contrat de données

Conserver séparément le texte reconnu brut, sa limite stable, les segments automatiquement acceptés, et l'époque des retouches humaines. Le code reçoit le texte brut de la continuation encore modifiable : le préfixe dont l'utilisateur a pris possession reste géré par `EditableTranscript`. Une retouche humaine invalide tous les travaux en vol de cette continuation, vide ses segments automatiques et ouvre une nouvelle époque. L'intégration devra appeler ce reset après avoir ancré le nouveau texte visible dans EditableTranscript, de façon à poursuivre uniquement sur les mots dictés ensuite.

Chaque demande porte un identifiant monotone, l'époque humaine, les offsets dans le brut, la copie exacte de son segment source, le contexte précédent en lecture seule, le mode et la phase. Le contexte combine le préfixe humain/automatique déjà visible, limité à ses 120 derniers mots. La normalisation déterministe est fournie par l'appelant ; ne jamais mesurer un offset brut dans le texte normalisé, car un alias de plusieurs mots peut devenir un nom unique.

Un seul segment est en vol. De nouveaux mots ajoutés après sa fin ne l'invalident pas. Une révision ASR qui touche son segment, un changement d'époque ou un résultat d'un ancien identifiant l'invalide. À la réception, vérifier à nouveau ces conditions avant d'ajouter un résultat entièrement validé. La brique ne publie jamais les fragments de génération LLM. Une réponse rejetée ou absente laisse le segment brut visible et permet de passer à la suite sans le redemander indéfiniment.

Les segments déjà acceptés gardent une copie de leur source brute. Si l'ASR révise cette source, retirer les segments automatiques à partir du premier segment touché et restaurer le brut correspondant. Cette opération ne touche jamais le préfixe humain, qui se trouve hors de la brique. Un ajout après les segments conserve leurs sorties ; pas de retraitement du texte entier à chaque mot.

## Découpage initial, conservateur

Le mode progressif commence lorsque la dictée complète atteint au moins 60 mots. Travailler exclusivement dans la partie stable fournie par Nemotron, en réservant les 12 derniers mots stables pour les reprises encore proches. Chercher une frontière de phrase ou de paragraphe après au moins 20 mots de continuation et au plus 80 mots. Les abréviations évidentes et les décimaux ne constituent pas des fins de phrase. Si aucune frontière sûre n'est disponible, attendre davantage de contexte plutôt que figer une réparation ou un élément de liste incomplet. Ces seuils constituent un premier réglage expérimental, pas une preuve de latence optimale.

La demande live est PARTIAL. L'arrêt de l'utilisateur rend toute la continuation stable et autorise une demande FINAL sur le reliquat, même très court. Les segments déjà acceptés sont conservés. Une dictée courte n'entraîne donc aucune inférence anticipée et se traite après l'arrêt. Un backend sans distinction committed/tentative, comme le chemin Sherpa actuel, utilise uniquement cette finalisation ; ne pas inventer une stabilité à partir de snapshots identiques.

Le rendu assemble les sorties acceptées dans l'ordre, puis le reliquat brut normalisé. Préserver les sauts de ligne voulus par les listes et e-mails et ne pas dupliquer le contexte. Les sorties complètes doivent provenir des validations `LocalFormatRequest.acceptOutput` existantes avant acceptation par la brique ; celle-ci ne tente pas de juger le sens avec de nouvelles règles lexicales.

## Preuves attendues

Tests comportementaux RED/GREEN, sans Android ni poids : dictée courte différée ; démarrage sur phrase stable d'une dictée longue ; conservation de la queue instable et des 12 mots ; absence de frontière sûre ; abréviation/décimal ; ajout de mots pendant le calcul ; révision ASR d'un segment en vol ; révision d'un segment accepté ; reset humain puis traitement de la suite ; ancien résultat après reset ; rejet sans boucle de répétition ; contexte jamais ajouté à la sortie ; finalisation du seul reliquat ; conservation des puces et paragraphes ; offsets corrects malgré une normalisation qui change le nombre de mots. Prévoir les trois modes et vérifier que phase/contexte font partie de l'identité de la demande.

## Étape d'intégration ultérieure

Le raccordement devra employer cette brique dans la fonction transform de `EditableTranscript.update` pour que displayed corresponde réellement au texte que l'utilisateur édite. Il devra préserver les protections manuelles existantes, les marqueurs d'images, le vocabulaire, la sélection et la composition IME, le gate de publication du run et l'annulation au changement de note. Le coordinateur utilisera la file partagée du moteur natif ; aucun second modèle ne sera créé. L'attente finale reste bornée et le brut constitue le repli pour les segments non terminés. Une validation visuelle et les mesures concurrentes avec Nemotron restent nécessaires sur téléphone.
