# DictAI Mac 0.2 — dictée directe et design carnet

> Version actuelle : [DictAI Mac 0.3 — texte, édition rapide et fenêtre native](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/walkthrough-0.3.md).

Version installée sur ce Mac, le 7 octobre 2026. Le second appui sur **Option** termine la reconnaissance, applique la correction configurée, puis déclenche l’insertion dans le champ d’origine. Le panneau se ferme après livraison ; une erreur conserve le texte et ouvre la relecture.

[Application installée](/Users/ulliemaillot/Applications/DictAI.app) · [Télécharger le ZIP 0.2](/Users/ulliemaillot/Downloads/DictAI-macOS-0.2.0.zip) · [Guide](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/README.md) · [Preuves de vérification](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/verification-0.2.json)

## Le parcours adapté au Mac

| Intention | Commande |
|---|---|
| Dicter dans une autre application | Placer le curseur, appuyer sur Option, parler, puis appuyer de nouveau sur Option. |
| Voir le texte | Déplier la pastille. |
| Corriger avant insertion | Cliquer sur **Relire** pendant l’écoute, puis sur **Insérer le texte** après les retouches. |
| Créer une note DictAI | Cliquer sur **Dicter une note** ; Option termine et enregistre dans le dossier sélectionné. |
| Classer un brouillon | Choisir une destination dans **Classer**, y compris **Sans dossier**. |
| Retenir une correction de vocabulaire | Retoucher le texte puis utiliser **Mémoriser ce mot** lorsque la suggestion apparaît. |
| Fermer sans perdre le texte | Appuyer sur Échap. |

La petite pastille ne prend pas le focus du champ actif. Le panneau agrandi reste déplaçable et redimensionnable. Les combinaisons Option avec d’autres touches conservent leur fonctionnement habituel. Les retouches humaines restent protégées des résultats tardifs de reconnaissance ou de correction.

## Identité de l’application

Le logo est repris du tracé du lanceur Android : trois boucles cursives sur fond crème. L’icône du Mac, la barre des menus et les vues utilisent cette même géométrie. Le design reprend la police Caveat, les couleurs du carnet Android, le papier réglé discret et les thèmes clair/sombre. Le corps des notes conserve une police de lecture native.

Les skills **Impeccable**, **make-interfaces-feel-better** et **frontend-patterns** ont guidé l’adaptation : hiérarchie, états compréhensibles, commandes natives, composants séparés et actions cohérentes. Les références visuelles Android ont été confrontées aux ressources actuelles du code.

### Panneau éditable

Capture réelle de la version compilée, dans une bibliothèque temporaire. Le texte a été saisi pour vérifier l’interface ; cette image ne prouve pas la transcription au micro. Le bouton d’insertion est désactivé ici car aucun champ externe n’a été capturé.

![Panneau DictAI en thème clair](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/redesign-overlay-light.png)

### Bibliothèque et thèmes

Le brouillon ci-dessus a été classé, puis retrouvé dans la bibliothèque. Les données de démonstration restent séparées des notes personnelles.

![Note classée dans la bibliothèque claire](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/redesign-library-light.png)

![Accueil du carnet sombre](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/redesign-home-dark.png)

### Pastille compacte

La capture suivante montre son état de relecture. Pendant l’écoute, elle affiche l’état d’enregistrement et la commande pour terminer avec Option.

![Pastille compacte en relecture](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/redesign-compact-dark.png)

## Vérification du mobile

L’interface Android actuelle a également été rendue et inspectée, avec les mêmes critères de lisibilité et de cohérence. Trois tests natifs passent : clair, sombre, écran de 320 dp avec texte à 130 %. Les palettes et la typographie sont la référence commune. Cette livraison modifie le Mac ; elle ne remplace pas l’APK Android ni son interface.

![Accueil Android clair, rendu du code actuel](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/android-main-light.png)

![Accueil Android sombre, rendu du code actuel](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/android-main-dark.png)

![Android étroit avec texte agrandi, contenu défilant](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/android-main-small-large-font.png)

## Vérifications et limites

- **41 tests Mac réussis**, dont 11 nouveaux tests du parcours de dictée : attente du résultat final, insertion unique, vocabulaire, correction asynchrone, relecture explicite, mode Note, annulation et erreurs. Les frontières micro/insertion sont contrôlées ; ces tests ne constituent pas un essai matériel.
- **Compilation release et signature locale vérifiées**, version 0.2.0 ouverte depuis `~/Applications`. La note personnelle existante est toujours présente après la mise à jour. Une archive de la version précédente est conservée dans Téléchargements.
- **Audit visuel natif effectué**, avec une passe de correction du pied de navigation et de l’indication du champ cible, puis une confirmation visuelle.
- **Trois autorisations actives** après accord explicite et validation Touch ID : Microphone, Reconnaissance vocale et Accessibilité. L’entrée Accessibilité de l’ancienne compilation a été remplacée par celle de la version installée. L’essai réel Option → voix → champ externe reste à confirmer : l’outil de contrôle ne sait pas envoyer un appui sur une touche modificatrice seule ; le document TextEdit est prêt pour l’essai manuel demandé.
- La synchronisation facultative conserve le contrat Android v1 pour notes, dossiers, images, vocabulaire et préférences. La connexion Google et la convergence réelle Mac/Android restent à valider avec un compte connecté.
- Les sous-dossiers utilisent les chemins de noms déjà décrits dans le guide ; le contrat Android v1 ne comporte pas encore de relation parent.
- Livraison Apple Silicon, macOS 14 minimum. Pas de validation Intel ni de notarisation publique. Le moteur est Apple Speech local ; les modèles Android et le mode réunion ne sont pas portés.

Journal des tests : [Mac](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/redesign-tests.log), [Android](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/android-design-tests.log), [compilation](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/redesign-build.log).

![Autorisations reconnues par la version installée](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/permissions-0.2.png)
