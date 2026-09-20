# Télécharger le pilote Gemma publié en plusieurs parties

## Besoin et choix

La recette GGUF examinée représente environ 3,5 Go. La distribution autorisée passe par GitHub Releases, dont chaque asset doit rester sous 2 Gio. Préparer une installation depuis des parties binaires successives du même fichier GGUF. Le moteur recevra un fichier reconstitué unique, identique bit à bit à l'artefact quantifié. Aucun URL, taille ou hash final n'est inventé et aucun poids n'est publié dans ce lot.

Lot complexe : tests puis deux revues du principal sur l'artefact final. Propriétaire Luna : GemmaModelStore et un helper multipart spécialisé si nécessaire, tests correspondants, rapports. Ne modifier ni moteur, JNI, UI, workflow ou définition ARTIFACT officielle.

## Contrat d'installation

Étendre le descripteur d'artefact avec des parties optionnelles (URL, taille, SHA-256). Le chemin mono-fichier existant conserve son comportement et ses tests. Pour les parties, valider que leurs tailles positives totalisent exactement la taille du fichier final, que les hashes sont valides et que la configuration est cohérente avant toute requête.

Télécharger les parties dans l'ordre, directement dans le fichier temporaire final, avec un tampon borné. Ne pas conserver simultanément toutes les parties puis une seconde copie concaténée. Vérifier le SHA de chaque partie terminée et le SHA global final avant publication atomique. La vérification d'une partie peut relire une plage du fichier temporaire ; aucun chargement intégral en mémoire.

La reprise doit connaître la partie active et son offset local. Les parties déjà terminées doivent être vérifiables après interruption ; un reçu d'ETag d'une autre partie ne peut pas être réutilisé. Une réponse 206 doit confirmer offset, taille et ETag attendus. Si le serveur renvoie 200 à une reprise, remplacer seulement les octets de la partie active, en gardant le préfixe déjà vérifié. Un ETag absent ou faible interdit la reprise à l'intérieur de cette partie mais n'impose pas de jeter les parties antérieures valides. Une somme de contrôle incorrecte ne doit jamais rendre le modèle installé.

L'annulation utilise GemmaDownloadCancellation et interrompt la requête active immédiatement. La progression porte sur les octets de l'artefact complet. Conserver la vérification d'espace disque, l'exclusion mutuelle de l'installation, la récupération d'un fichier vérifié non publié et les reçus d'intégrité existants. Aucun hash des poids sur le thread UI dans installedModel(). Les erreurs réseau restent sans contenu de requête dans les diagnostics.

## Vérification bornée

Utiliser de minuscules parties et MockWebServer : reconstruction exacte, interruption dans une partie et entre parties, reprise locale avec Range/If-Range, réponse 200 après Range, ETag absent ou modifié, Content-Range incohérent, mauvais SHA de partie ou global, taille totale invalide, annulation et reprise après une nouvelle instance du store. Les cas doivent établir la conservation du préfixe et l'absence de publication partielle. Un RED préalable doit démontrer un comportement absent, pas une faute de syntaxe.

La quantification a le créneau de calcul lourd. Préparer code et tests maintenant, puis attendre son achèvement avant Gradle. Aucune modification de l'artefact officiel, aucun téléchargement réel, commit, push ou Action dans ce lot.
