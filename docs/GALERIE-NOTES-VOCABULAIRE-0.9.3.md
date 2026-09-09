# DictAI 0.9.3 — corrections au clavier, galerie et notes illustrées

Retour d’Ullie après 0.9.2, le 9 septembre 2026 : AF → CAF et un prénom corrigé en pause n’ont pas déclenché la suggestion. Le geste habituel est d’effacer lettre après lettre, sans sélectionner. Les captures/photos doivent aussi être accessibles dans le stockage du téléphone. Les images sont à conserver dans une note lorsque celle-ci est enregistrée volontairement.

## Corriger sans sélectionner

Le suivi observe la différence réelle entre l’ancien texte et le nouveau, même si le clavier annonce le remplacement d’une zone de composition entière. Il garde le mot source pendant les suppressions successives et la nouvelle saisie. Une insertion seule dans un mot existant compte aussi : AF → CAF, Haron → Haroun. Un petit groupe supprimé en remontant caractère par caractère conserve son origine : ma yotte → Maillot.

Après une courte pause de frappe (1 seconde, au moins 1,5 seconde si le clavier garde une composition), la puce compacte **Enregistrer dans le vocabulaire** propose la paire source → correction. Toucher la puce confirme ; fermer ou ignorer ne crée aucune règle. Elle disparaît après 12 secondes. La sélection d’un autre texte masque la proposition. Une correction encore modifiée est revérifiée au moment du bouton. Le suivi d’une même correction expire après 15 secondes sans nouvelle frappe.

Une suppression complète sans remplacement ne propose rien. La frappe d’un nouveau mot n’apprend pas ses préfixes intermédiaires. Une mise à jour programmée de Nemotron ne commence jamais un apprentissage ; sa continuation peut évoluer pendant la correction manuelle. Les limites restent 100 caractères et 8 mots par terme. Il s’agit de règles explicites locales, pas d’un entraînement du modèle. Une paire ambiguë comme cloud → Claude s’appliquera aux occurrences correspondantes ; elle reste modifiable dans Mon vocabulaire.

Les règles validées sont appliquées pendant l’affichage de la transcription, sans appel LLM, avec les mêmes règles de limites de mots et de non-cascade qu’en 0.9.2. Le mode Texte et les réglages Gemma/cloud sont inchangés.

## Captures et photos dans le téléphone

Chaque capture réussie est copiée au presse-papier et enregistrée comme JPEG dans **Pictures/DictAI**, visible dans la galerie/Photos de l’appareil. Le nom de l’album ou son délai d’indexation peuvent varier selon la galerie. Le sélecteur d’images d’une conversation permet donc aussi de retrouver l’image quand Gboard ou cette conversation ne permet pas son collage.

La publication MediaStore est faite avec IS_PENDING : une image devient visible après la copie complète. En cas d’erreur, seule la nouvelle entrée incomplète est retirée. Pas de recompression lors de la sauvegarde : le JPEG déjà créé par DictAI est recopié. Aucune permission de lecture de la photothèque n’est demandée pour créer ces images appartenant à l’application (Android 11 minimum).

Le presse-papier contient un URI JPEG, sans texte ni marqueur. Une nouvelle capture remplace son contenu courant. L’historique et l’acceptation des images dépendent de Gboard et de l’application destinataire. Aucun collage ou envoi automatique n’est ajouté. Le diagnostic image précise si la sauvegarde dans Photos a réussi. Une erreur de stockage est affichée séparément d’un échec du presse-papier.

## Enregistrer volontairement une note illustrée

Pendant une dictée, les dix premières captures sont aussi retenues dans le brouillon local, avec une miniature et une position liée au texte déjà affiché au moment du geste. Le geste **vers la gauche** sauvegarde le texte et ces images comme note. Ouvrir explicitement l’export sauvegarde également cette note. Une note déjà ouverte reçoit ses nouvelles captures dans son contexte.

Les repères `[[Image N]]` sont insérés lorsque les captures passent du brouillon à la note. Une dictée ordinaire envoyée dans une conversation ne reçoit pas de repères supplémentaires. Les repères suivent les ajouts ou corrections de texte selon un alignement simple ; une réécriture ASR de plusieurs zones peut donner une position approximative. Ce n’est pas une synchronisation exacte avec les mots audio qui n’étaient pas encore affichés.

La note est limitée à dix images, mais les captures suivantes restent possibles dans la galerie et le presse-papier ; un message signale cette limite. Les images publiques de Photos restent présentes lorsqu’une miniature, un brouillon ou une note privée est supprimé. L’utilisateur les retire depuis sa galerie s’il le souhaite. Les anciennes notes et leurs pièces restent conservées.

## Un fichier contenant réellement texte et images

Dans **Mes notes → Partager / exporter** :

- **Lire le PDF**, **Enregistrer le PDF** ou **Partager un PDF · texte et images** : un document avec texte sélectionnable et images à leur emplacement, créé sur demande avec le moteur PDF Android.
- **Fichier HTML unique · texte et images** : un seul fichier `.html`, avec les JPEG encodés dans le document, sans dossier d’images à fournir à côté. Il s’ouvre hors ligne dans un navigateur et conserve les textes/captures dans l’ordre. L’encodage base64 augmente d’environ un tiers le volume des JPEG ; il est écrit en flux pour éviter de les charger tous comme longues chaînes en mémoire.

Pour un agent capable d’ouvrir et d’extraire des fichiers, le HTML autonome est exploitable : lire les blocs de texte et décoder chaque URI `data:image/jpeg;base64,…` en image. Pour un chatbot qui traite visuellement les PDF, le PDF peut aussi fournir le contexte des images. Il n’existe pas de garantie commune à tous les chatbots : un HTML accepté en pièce jointe peut n’être traité que comme du texte.

Exemple documenté : l’API OpenAI transmet texte extrait et pages en images pour les PDF destinés à un modèle visuel ; les documents non PDF, dont HTML, sont extraits comme texte sans leurs images intégrées. C’est une propriété de cette API, pas une preuve de comportement de l’application ChatGPT Android, de tous ses forfaits ou d’un autre chatbot. [Documentation officielle des fichiers en entrée](https://developers.openai.com/api/docs/guides/file-inputs).

Capture, galerie, miniatures, sauvegarde de notes et export ne sollicitent aucun LLM. Les images ne sont pas transmises au modèle local ou cloud de correction du texte. Leur coût reste celui du stockage et des opérations graphiques ; latence et consommation réelles sur le Xiaomi restent à mesurer.

## Vérifications et essais restant sur téléphone

Tests JVM : édition lettre par lettre, remplacement composé, insertion de lettre, source multi-mots, saisie neuve, suppression sans remplacement, confirmation périmée, révisions ASR et déplacement/matérialisation des repères. Tests Android ajoutés pour les événements réels Editable/IME et pour la copie MediaStore, sa persistance après suppression de note et l’identité des JPEG. Ces tests Android sont compilés, mais ne sont pas exécutés faute d’appareil ou d’émulateur connecté. Ils ne constituent pas un test de Gboard.

Essai ciblé après installation : dicter « AF », mettre en pause, effacer les deux lettres puis taper « CAF ». Attendre brièvement et confirmer la puce AF → CAF. Refaire une dictée avec la forme source pour vérifier le remplacement en direct. Prendre une capture/photo et la retrouver dans Photos → DictAI. Enregistrer une dictée illustrée à gauche, rouvrir la note et exporter PDF/HTML pour contrôler la place des images.

Référence Android : [enregistrement de médias dans le stockage partagé](https://developer.android.com/training/data-storage/shared/media), consultée le 9 septembre 2026. La publication et les preuves de construction sont consignées ci-dessous après vérification de l’APK GitHub.


## Livraison vérifiée

Publication vérifiée le 9 septembre 2026 : [APK directe 0.9.3](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34364610947/dictai-local-layout-test.apk), [GitHub Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34364610947), source `38d88b3ff6d3e93f7b2fa88115b405449f34000a`. Téléchargement public complet HTTP 200, **78 816 652 octets**, SHA-256 `984ecf4c694c5ce73eac95323744c68ace08fa57360fa1244c53c6c3a67bd3ef`. Empreinte identique à l’APK locale finale, à SHA256SUMS, au digest GitHub et au journal CI ; les **1 048 entrées ZIP** sont également identiques. Signature de mise à jour, version code32 / 0.9.3-wp-gemma-test, ABI ARM64 et alignement ZIP/natif 16 Ko vérifiés. **418 tests JVM / 60 suites**, 18 tests du contrôleur d’alignement et contrat natif réussis. APK et tests Android compilés ; tests Android non exécutés, aucun appareil connecté à Codex. Les gestes Gboard, l’overlay, MediaStore/galerie et le rendu PDF restent à vérifier sur téléphone.


## Premier retour terrain après livraison — 9 septembre 2026

Ullie confirme que la capture d’écran et la photo sont enregistrées dans le téléphone et présentes dans le presse-papier. Son second retour confirme explicitement le bon enregistrement des deux types d’images.

Il a aussi récupéré une note en PDF et en HTML via le bouton de récupération des fichiers et vérifié que le fichier était lisible. Il l’a fourni à ChatGPT et à Grok : tous deux ont réussi à le lire. Lecture confirmée dans ses essais ; le contenu visuel effectivement interprété et chaque combinaison PDF/HTML avec chaque application ne sont pas détaillés.

Le mail fourni a désormais une salutation, un corps, une fermeture et une signature correctement séparés. La liste est jugée réactive et globalement satisfaisante, avec une coupure potentiellement manquée entre couches et eau et une limite signalée sur les formulations plus naturelles. Le [suivi du retour](SUIVI-DEVELOPPEMENT.md#retour-terrain-du-9-septembre--mail-liste-et-captures) distingue les observations des hypothèses techniques.

Le retour suivant confirme aussi le parcours du vocabulaire : proposition « grek → Grok » (source décrite comme grec écrit avec un K final), enregistrement accepté et graphie corrigée dès la deuxième occurrence dictée. Le geste exact de remplacement n’est pas redétaillé. La récupération PDF/HTML et la réutilisation de la correction sont désormais confirmées par Ullie. La réouverture ultérieure dans DictAI, le placement précis des images et leur interprétation par les destinataires ne sont pas détaillés.

Ullie signale ensuite des « euh » spontanés conservés avec le moteur Local. Le [suivi](SUIVI-DEVELOPPEMENT.md#retour-terrain-du-9-septembre--mail-liste-et-captures) décrit les chemins à distinguer : format actif, retouche manuelle entraînant une conservation stricte, résultat du modèle accepté ou repli sur la source. Ce signalement ne remet pas en cause la correction de vocabulaire qu’il vient de valider. Aucun nouvel essai appareil par Codex ; les résultats de construction ci-dessus restent ceux de la livraison.
