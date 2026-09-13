# DictAI — messages et notes depuis la même pastille Android

Direction précisée par Ullie le 9 septembre 2026, après l’étude de faisabilité Android/iOS. **Se concentrer sur Android, conserver la pastille et les gestes actuels. Les messages et les notes doivent cohabiter.** Le portage iOS est écarté pour l’instant. La prise de notes spontanée avec contexte visuel et export vers un agent IA complète la fonction de dictée existante.

Précision explicite suivante d’Ullie : ne pas oublier le parcours message. Dicter, voir le texte dans le panneau DictAI, le corriger au besoin et l’insérer volontairement dans le champ de l’application destinataire reste un usage à part entière. Une dictée destinée à un message n’exige pas la création d’une note ou d’un document. Les gestes d’insertion existants sont préservés ; le parcours note n’envoie ni n’insère son contenu à la place de l’utilisateur.

| Usage | Résultat recherché |
| --- | --- |
| Message / dictée | Texte visible dans DictAI puis insertion volontaire dans le champ cible, avec le format choisi |
| Note | Texte dicté ou saisi et captures/photos conservés ensemble, repris et exportés en PDF/HTML à la demande |

La pastille, le panneau de texte et la transcription en direct servent les deux usages. La destination choisie par le geste de l’utilisateur détermine l’action. Il n’est pas demandé de remplacer cette interaction par un nouveau sélecteur obligatoire.

La transcription en direct avec Nemotron sert cette prise de notes : lire ou étudier un contenu, capturer ce qui mérite d’être conservé, ajouter ses commentaires à la voix ou au clavier, puis poursuivre sa lecture. La tablette et le stylet renforcent l’intérêt de ce parcours selon Ullie. Étudiants et personnes effectuant des recherches sont des usages envisagés, sans étude utilisateurs réalisée à ce stade.

## Parcours à préserver et à fluidifier

1. Depuis le contenu consulté, accéder à Mes notes par le geste gauche de la pastille, puis créer une note. Elle s’ouvre en pause, sans démarrer le micro.
2. Capturer l’écran ou prendre une photo pour conserver le contexte dans cette note.
3. Commenter à la voix ou au clavier, alterner pause et reprise, agrandir le panneau au besoin.
4. Ajouter d’autres éléments visuels et commentaires dans la même note, en gardant leur association et leur ordre.
5. Récupérer un PDF ou un HTML contenant l’ensemble, puis le fournir à l’agent choisi.

La note se construit au fil de la séance. Les fichiers PDF/HTML sont générés à la demande depuis son état sauvegardé ; ce ne sont pas des fichiers reconstruits continuellement pendant la dictée. La création directe d’une note, la capture et l’export doivent rester utilisables sans devoir commencer par dicter.

## État vérifié dans le code actuel

`OverlayService.showNotesOverlay` propose Nouvelle note ; `openNote` ouvre un éditeur en état PAUSED. La saisie, la reprise de dictée et l’agrandissement du panneau existent. Les captures/photos sont associées à la note ou au brouillon, avec des repères utilisés pour restituer l’ordre dans les exports. Le micro peut continuer pendant une capture d’écran ; la caméra le met actuellement en pause puis prévoit sa reprise au retour. Ne pas annoncer un enregistrement audio continu pendant la prise de photo.

`NoteHtmlExport` produit un seul fichier avec texte UTF-8, images JPEG intégrées en base64, numéros, types d’images et dates. Aucune image distante ni dossier compagnon. `NotePdfExport` produit du texte sélectionnable et insère les images selon les mêmes repères. Les exports n’appellent pas de modèle. La limite actuelle est dix images par note ; les URL ou références bibliographiques de ce qui est capturé ne sont pas récupérées automatiquement.

Ullie a déjà confirmé stockage, presse-papier et récupération PDF/HTML. Il a fait lire un fichier à ChatGPT et à Grok. L’analyse du contenu visuel de chaque image n’est pas encore démontrée par ce retour. Ne pas lui redemander les validations déjà données et ne pas les présenter comme des essais exécutés par Codex.

Le stylet est un usage évoqué par Ullie. Le code inspecté fournit un éditeur de texte, sans canevas de tracés manuscrits. Écriture convertie en texte par le clavier/système et conservation de dessins, flèches ou annotations sont des capacités distinctes. Le second usage reste à préciser avant toute implémentation ; ne pas considérer une demande de moteur d’encre comme acquise.

## Export et transmission du contexte à l’agent

Le HTML autonome est une bonne archive transportable : texte et images sont contenus dans un fichier qui peut être ouvert hors ligne. Cette intégration repose sur les [URL data documentées par MDN](https://developer.mozilla.org/en-US/docs/Web/URI/Reference/Schemes/data).

Un agent disposant d’outils de fichiers et de vision peut extraire les images du HTML et les lire avec les passages associés. Cette capacité doit être effectivement utilisée : le texte base64 d’une image ne suffit pas à lui seul comme entrée visuelle. Un import de fichier peut n’extraire que le texte ; [Claude documente notamment ce comportement pour les documents non PDF](https://support.claude.com/en/articles/8241126-upload-files-to-claude).

Le PDF est pertinent pour relire, partager et transmettre la disposition visuelle à un destinataire qui analyse les pages. Il ne faut pas supposer cette analyse universelle : les modes d’import et de traitement changent selon le service. La [documentation PDF de Claude](https://platform.claude.com/docs/en/build-with-claude/pdf-support) distingue elle-même des chemins de lecture textuelle et visuelle.

Recommandation pour cette orientation : conserver les deux exports actuels. HTML pour une archive complète et pour les agents capables d’extraire ses images ; PDF pour la lecture et les destinataires dont l’analyse visuelle PDF est vérifiée. Une extraction côté agent peut améliorer la transmission sans changer les gestes de capture sur le téléphone. Aucune nouvelle option universelle « compatible avec tous les agents » n’est promise.

## Prochain travail utile

Priorité à la continuité capture → commentaire → capture et à la conservation du contexte, avec une prise en main simple sur téléphone et tablette, tout en préservant la rapidité du parcours message et son insertion volontaire. Le nettoyage local conserve son objectif déjà précisé : retirer les hésitations et répétitions involontaires, améliorer la cohérence, sans rechercher une transcription parfaite ni compliquer l’un ou l’autre usage.

Pour vérifier la transmission visuelle, utiliser une courte note dont une image contient un détail absent du texte dicté. Le destinataire doit retrouver ce détail et le rattacher au bon commentaire. Cela distingue une vraie lecture des images d’une réponse fondée uniquement sur le texte. Ce contrôle n’a pas été exécuté dans cette étape.

Cette note fixe la direction et les critères. Inspection du code et des sources documentaires seulement ; aucune modification de l’application, aucun envoi à un agent tiers et aucune nouvelle APK.
