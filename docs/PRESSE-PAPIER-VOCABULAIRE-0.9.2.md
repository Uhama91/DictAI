# DictAI 0.9.2 — image dans Gboard et vocabulaire explicite

Demande d’Ullie du 9 septembre 2026, après l’échec du collage automatique 0.9.1 : une capture ou photo à la fois dans le presse-papier, collage manuel depuis **Gboard**, puis insertion du texte en fin de dictée. Abandon des nouvelles notes visuelles et des lots d’images. La caméra interne vient d’être testée avec succès par Ullie ; elle est conservée. Le LLM et ses délais ne changent pas.

## Images

1. Pendant la dictée ou dans une note ouverte, toucher Capture d’écran ou Photo.
2. Capture : DictAI masque la pastille, le panneau et le clavier avant de capturer le fond ; la dictée continue. Photo : le petit viseur interne met la dictée en pause puis la reprend au retour.
3. Le JPEG est remis au presse-papier Android sans exiger de champ actif, sans sélection de destinataire, sans ACTION_PASTE automatique et sans menu de partage. DictAI indique « Image copiée ».
4. Revenir à la conversation, toucher son champ puis ouvrir **Presse-papier** dans Gboard et toucher l’image proposée. Coller l’image avant d’en capturer une autre. Aucune action Envoyer n’est déclenchée par DictAI.
5. Terminer la dictée pour insérer le texte. Après une capture, une insertion directe réussie conserve l’image dans le presse-papier. Si le champ impose le collage de texte ou si l’insertion échoue, le repli copie le texte et remplace donc l’image courante.

Il n’y a plus de nouvelle note créée pour une capture, de marqueur [[Image N]], de numérotation incrustée ni de limite de dix captures par note. Les notes et images précédemment enregistrées restent lisibles, exportables et supprimables individuellement. Leur miniature propose désormais « Copier l’image ».

La capture est redimensionnée à 2048 pixels maximum, JPEG qualité 92 ; aucune vignette de note ni seconde compression pour la nouvelle copie. Chaque copie a son propre fichier temporaire : une image copiée ensuite ne remplace pas les octets de la précédente. Ces fichiers sont nettoyés après sept jours lors d’une nouvelle préparation, ou peuvent être effacés par Android comme cache ; ce mécanisme n’est pas une archive. Aucune image n’est fournie au modèle local ou cloud.

**Limites vérifiables :** Android conserve un seul ClipData courant. Gboard peut avoir son historique, mais DictAI ne le contrôle pas et ne promet ni lot ni rétention permanente. La réception JPEG dépend du champ destinataire. L’option Gboard permettant de détecter les captures enregistrées dans la galerie n’est pas nécessaire au code DictAI : il écrit lui-même le JPEG dans le presse-papier, via un content provider, dans un ClipData contenant uniquement une URI image avec MIME image/jpeg (aucun texte vide ajouté). Le test Gboard → ChatGPT Remote reste à effectuer sur l’appareil.

Sources primaires : [presse-papier Android](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste), [réception de contenu riche](https://developer.android.com/develop/ui/views/receive-rich-content), [accès au presse-papier Gboard](https://support.google.com/gboard/answer/10742542?hl=fr). La documentation Gboard décrit le panneau de collage ; elle ne certifie pas la réception d’images dans chaque chatbot.

## Vocabulaire pendant la dictée

Sélectionner un mot ou une courte expression (huit mots maximum), remplacer directement ou supprimer puis retaper à la même position. Après une pause de frappe, une puce dans la bande d’outils affiche **Enregistrer dans le vocabulaire** et **« source » → « remplacement »**, avec une croix. Cette position fixe reste accessible dans le petit overlay, sans déplacer le texte ni le curseur. La bande photo revient à la fermeture.

La proposition attend une seconde après la frappe ; avec une composition clavier encore ouverte, elle attend au moins 1,5 seconde pour permettre de confirmer un nom sans taper obligatoirement une espace. Un appui sur Enregistrer revalide le texte actuel avant de mémoriser. La croix ou douze secondes sans validation annulent la proposition. Une suppression seule, la frappe ordinaire, une correction incomplète dans un mot, les changements issus de l’ASR et les propositions ignorées n’ajoutent aucune règle. Les conflits avec une règle existante renvoient vers Mon vocabulaire ; aucune ancienne règle n’est écrasée.

Le suivi conserve la sélection explicite même quand l’IME la replie avant TextWatcher, et accepte les révisions ASR de la suite du texte sans abandonner la correction manuelle. Les mots sélectionnés et leur remplacement doivent rester intacts.

Le raccord avec l’ASR utilise désormais les mots reconnus avant normalisation : une correction de deux mots en un seul nom, ou des nombres en chiffres, ne doit pas absorber le premier mot de la suite. Les corrections du préfixe saisies par l’utilisateur restent intactes.

Les règles validées sont stockées localement dans Mon vocabulaire, au format existant. Elles s’appliquent à chaque mise à jour de transcription et au résultat final, avant les nombres et tout éventuel LLM. Elles fonctionnent en Texte sans modèle et avec les formats local/cloud. Le moteur reconnaît les expressions malgré des espaces répétés, privilégie les longues expressions, ne remplace pas dans un identifiant ni au milieu d’un mot et ne réapplique pas ses résultats en cascade dans le même passage. Les expressions régulières sont réutilisées tant que le vocabulaire ne change pas.

Exemple : confirmer `cloud → Claude` remplace ensuite cette forme partout où la règle correspond, sans deviner le sens informatique ou le prénom. L’utilisateur peut supprimer ou modifier cette règle dans Mon vocabulaire ; plusieurs variantes de transcription peuvent viser la même orthographe. Il s’agit de remplacements déterministes, pas d’un réentraînement de Nemotron.

## Vérification

Publication vérifiée : [APK directe 0.9.2](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34360582995/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34360582995), source `25ceeb6cc2f2fd6f4de8707552cdd6c4412514fe`. Téléchargement public intégral HTTP 200 : **78 800 272 octets**, SHA-256 `798494dff118469e97a3c4d821053647f5e4e94e7844277392f6c62984c4ea89`. Empreinte concordante avec SHA256SUMS, le digest GitHub et le journal CI. Les **1 048 entrées internes** de l’APK publique sont identiques à celles de la construction locale finale. Signature de mise à jour, version 31 / 0.9.2-wp-gemma-test, ABI ARM64 et alignement natif/ZIP 16 Ko contrôlés. **408 tests JVM / 59 suites**, 18 tests du contrôleur d’alignement et contrat natif réussis ; tests Android compilés, non exécutés. Aucun appareil Android connecté ; la réception réelle depuis Gboard, l’overlay avec le clavier et la caméra restent à vérifier sur téléphone.

Les tests de régression couvrent les sélections repliées avant remplacement, la composition stable, la révision de la suite ASR, la suppression seule, l’offre périmée, le rejet d’un enregistrement devenu obsolète, les expressions à espaces variables, les limites de mots et identifiants, et l’insertion préservant le presse-papier sur succès direct. Un premier test a détecté une perte du mot « de » après une correction de deux mots en un nom ; le raccord au brut ASR a corrigé ce cas.

Les tests Android compilés ajoutent la copie de deux URI image successives sans éditeur actif, un récepteur natif contrôlé, et onze captures indépendantes sans note ni vignette. Ils ne prouvent pas encore le comportement de Gboard ou ChatGPT Remote. Logs locaux ignorés : `.native-cache/clipboard-vocabulary-release-build.log`, `.native-cache/clipboard-vocabulary-alignment-tests.log`, vérification publique `.native-cache/notes-release-34360582995/verification.json`.

Installation : mettre l’APK à jour sans désinstaller DictAI pour conserver modèles, vocabulaire et notes. Aucun nouveau modèle à télécharger pour ces deux fonctions.
