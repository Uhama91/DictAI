# Notes avec contexte visuel — essai 0.9.0

Demande d’Ullie du 9 septembre 2026 : capturer le contexte depuis la pastille, jusqu’à dix images par note, avec un repère à la position du texte au moment du geste. Le PDF est destiné à la lecture par l’utilisateur. Le HTML doit être autonome. Le transfert vers une conversation doit préparer ensemble texte et images, sans sélection manuelle image par image.

## Utilisation

Dans une note ouverte ou pendant une dictée, le panneau contient les icônes **capture d’écran**, **appareil photo** et **partage**. Les miniatures se trouvent sur la même bande et défilent horizontalement. Touchez une miniature pour l’agrandir ; maintenez-la pour retirer l’image.

Une capture d’écran masque temporairement toutes les fenêtres DictAI et le clavier, puis photographie l’écran sous-jacent. La dictée peut continuer. L’appareil photo utilise l’application caméra du téléphone : le micro est mis en pause et reprend au retour si la dictée était en cours. Les images sont enregistrées dans une note locale, même si la dictée est ensuite fermée. La note est limitée à dix images.

Le repère `[[Image 1]]` est inséré à la fin du texte **déjà visible au geste**, avant les nouveaux mots. Il marque le contexte sans charger une grande image dans l’éditeur. Ce n’est pas une synchronisation mot à mot avec l’audio encore en attente de transcription. Une référence supprimée manuellement ne détruit pas l’image : les exports la joignent à la fin avec la mention « repère retiré du texte ». Retirer la miniature supprime l’image et sa référence de la note.

Pour une note contenant des images, le geste **envoyer vers le haut en pause** prépare le partage groupé Android. Terminer la dictée contenant des images fait de même, au lieu d’insérer seulement des références textuelles dans une autre application. Le bouton partage du panneau ouvre les choix d’export et masque le panneau pour laisser le lecteur ou la destination visibles. Fermer cet écran réaffiche la note précédemment ouverte en pause si elle est toujours active. Caméra et export utilisent des écrans temporaires séparés de l’accueil DictAI. Depuis **Mes notes**, ouvrez la note puis ce bouton ; le menu obtenu par maintien sur une note propose aussi « Partager / exporter ».

- **Partager le texte et les images** : prépare un `Note.txt` complet et les JPEG, avec leur numéro visible dans une marge ajoutée aux copies de partage. Les photos conservées dans la note ne sont pas modifiées. Le texte est aussi proposé comme texte du message et copié, jusqu’à 80 000 caractères pour respecter les limites de transfert Android ; au-delà, il reste intégralement dans le fichier TXT. Le sélecteur Android présente les applications compatibles. L’utilisateur choisit la destination et garde la main sur l’envoi.
- **Lire en PDF** : crée un PDF à texte sélectionnable avec les images à leurs repères et ouvre le lecteur disponible. « Enregistrer » permet de choisir un dossier. Le PDF n’est créé que sur demande.
- **Exporter en HTML autonome** : un seul fichier UTF-8 avec texte échappé, images JPEG intégrées en `data:image/jpeg;base64`, légendes et dates. Pas de scripts, de ressources distantes ni de dossier d’images compagnon. Le sélecteur de fichiers permet de l’enregistrer.
- **Enregistrer le texte et les images** : conserve les pièces du partage groupé dans un sous-dossier choisi par l’utilisateur, si l’application destinataire ne sait pas recevoir ce partage.

## Compatibilité : limites vérifiées

Le presse-papier et le service d’accessibilité ne peuvent pas obliger une application tierce à insérer simultanément du texte et plusieurs pièces jointes dans la conversation déjà ouverte. Android fournit `ACTION_SEND_MULTIPLE` et des autorisations de lecture temporaires ; chaque application décide des types reçus et du traitement du texte. Certaines peuvent ne pas figurer dans le sélecteur, ouvrir une nouvelle conversation ou ignorer le texte du message. **Aucune compatibilité de bout en bout ChatGPT/Claude/Telegram/Docs/Notion n’est encore validée sur le téléphone.** Le partage groupé évite la sélection séparée des images lorsqu’il est accepté ; il ne constitue pas un collage universel.

La conservation des fichiers et l’analyse de leur contenu sont distinctes :

- ChatGPT prend en charge les images JPEG jointes directement. L’aide officielle réserve actuellement la lecture visuelle des PDF à Enterprise ; ce comportement n’est donc pas une base universelle pour les notes visuelles.
- Claude indique analyser les images des PDF de 100 pages ou moins, mais extraire seulement le texte des autres documents, y compris HTML.
- Gemini indique lui aussi lire les autres types de documents comme du texte ; les données base64 d’un HTML ne deviennent pas automatiquement des entrées visuelles.
- L’adaptateur Telegram de l’instance Icara active regroupe les albums et conserve les pièces jointes. La passerelle route les images vers la vision native ou auxiliaire et garde les documents accessibles par chemin local. Cette inspection du code ne prouve ni une réception réelle depuis DictAI ni l’extraction automatique des images d’un HTML. Aucune configuration Icara n’a été modifiée et aucun message Telegram n’a été envoyé.

Le HTML autonome est donc une archive portable ; un agent outillé peut en extraire les images. Le partage texte + images les fournit directement aux applications qui acceptent ces pièces jointes.

## Ressources, récupération et confidentialité

Stockage interne : anciennes notes JSON dans les préférences privées conservées, métadonnées d’images ajoutées, JPEG dans `files/note_images`. Capture en attente enregistrée pour récupérer le résultat après recréation du service ou de l’activité caméra. Un échec retire sa référence et conserve le texte. La capture inachevée récupérée peut être annulée depuis la bande des miniatures.

Une seule capture en attente. Images ramenées à un côté maximal de 2 048 pixels, JPEG qualité 92, miniatures 160 pixels. Les métadonnées EXIF ne sont pas recopiées. La capture reçoit transitoirement un bitmap de l’écran ; les exports décodent successivement les images. Le moteur PDF Android peut conserver des ressources de pages jusqu’à la fermeture du document : la consommation réelle sur le téléphone reste à mesurer. HTML : base64 écrit en flux, sans construire dix longues chaînes en mémoire. Les exports restent sept jours dans le cache de partage pour laisser les applications destinataires les lire ; les originaux durables restent associés à la note.

Capture, sauvegarde, miniatures, PDF, HTML et partage fonctionnent sans LLM. Le mode **Texte** reste sans LLM. **Texte corrigé**, Liste et Mail peuvent utiliser le moteur choisi pour le texte ; les références sont protégées. Le modèle ne reçoit ni pixels, ni fichiers d’image, ni chemins locaux. Aucun transfert automatique au cloud. Le partage explicite transmet naturellement les pièces à l’application choisie.

Pas de nouvelle permission caméra ou galerie : l’application caméra système reçoit l’accès temporaire à son seul fichier de sortie. Le service d’accessibilité doit déclarer la capacité de capture ; il peut être nécessaire de le désactiver/réactiver après mise à jour si Android refuse la nouvelle capacité. Les écrans protégés peuvent être refusés ou masqués par Android. Aucun contournement. Les actions image/export demandent un téléphone déverrouillé. Le comportement existant de la pastille sur l’écran verrouillé n’est pas rendu universel : il dépend d’Android et du constructeur.

## Sources consultées le 9 septembre 2026

- [Android : service de capture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)).
- [Android : partage de données et pièces multiples](https://developer.android.com/develop/ui/compose/sharing/send).
- [Android : permissions temporaires avec FileProvider](https://developer.android.com/training/secure-file-sharing/share-file).
- [MDN : données intégrées dans des URL data](https://developer.mozilla.org/en-US/docs/Web/URI/Reference/Schemes/data).
- [OpenAI : images jointes dans ChatGPT](https://help.openai.com/en/articles/8400551-chatgpt-image-inputs-faq).
- [OpenAI : lecture visuelle des PDF](https://help.openai.com/en/articles/10416312-visual-retrieval-with-pdfs-faq).
- [Claude : documents et images](https://support.claude.com/en/articles/8241126-upload-files-to-claude).
- [Gemini : documents](https://ai.google.dev/gemini-api/docs/document-processing).

## Vérification de la version

391 tests JVM réussis dans 58 suites, sans échec ni saut. APK de l’application et APK des tests instrumentés compilées. Signature, ZIP et les dix bibliothèques natives vérifiés avec alignement 16 Ko. APK locale finale : 79 708 746 octets, SHA256 `65f58843b718859d435efa88fadea7dd74fa26b16aa7e578f5756a0f4972b033`.

Export HTML synthétique produit en invoquant la classe Kotlin compilée : 70 552 octets, deux JPEG intégrés. Ouvert dans le navigateur, images et ordre du contexte vérifiés visuellement. Les tests JVM vérifient aussi le décodage base64 octet pour octet, l’échappement du texte et l’absence de ressources externes. Test Android dédié au stockage réel et au rendu du PDF compilé ; aucun appareil connecté pour l’exécuter. La latence d’export sur téléphone et les partages dans les applications destinataires ne sont pas mesurés. Aucun nouvel essai d’inférence Gemma requis : aucun modèle/runtime modifié.

Publication terminée : [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34350930791), [APK directe 0.9.0](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34350930791/dictai-local-layout-test.apk), source `17fe5cfb1c0e4ee8b8eab012f458c022c509295e`, version 29 / 0.9.0-wp-gemma-test. Téléchargement public HTTP 200 intégral : 78 750 888 octets, SHA-256 `873f9853f58ba927cfd9f4ffbba91199592a894113c1c543a1bed6249e4c54f0`, conforme à SHA256SUMS, à l’empreinte de l’asset GitHub et au journal CI. Signature, version, ABI ARM64, alignement ZIP et dix bibliothèques natives 16 Ko vérifiés sur ce téléchargement. Les 1 048 entrées de l’APK publiée ont exactement les mêmes contenus décompressés que l’APK testée localement ; seule la représentation du conteneur diffère.
