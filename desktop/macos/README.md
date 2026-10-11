# DictAI pour macOS

Compagnon natif de DictAI Android. Un appui isolé sur **Option (⌥)** lance la dictée ; un nouvel appui isolé termine la reconnaissance, applique la correction configurée puis **insère automatiquement le texte dans le champ d’origine**. Deux appuis rapides mettent en pause ou reprennent le même brouillon ; trois appuis rapides annulent sans envoyer. Après livraison, la pastille se ferme, le texte est conservé dans les notes et le brouillon actif est vidé. Les boutons **Pause**, **Reprendre** et **Annuler** restent disponibles. Une erreur conserve le texte et ouvre le panneau de récupération.

## Utilisation

1. Ouvrir `DictAI.app`. Pour un usage quotidien et le lancement à la connexion, placer l’application dans `~/Applications` ou `/Applications`.
2. Lancer une première dictée et autoriser le microphone et la reconnaissance vocale. La reconnaissance Apple est forcée en mode local ; aucun repli vers un service vocal distant n’est effectué.
3. Dans Réglages → Autorisations du Mac, autoriser l’accessibilité pour le raccourci global et l’insertion. Cliquer sur « Vérifier à nouveau » après ce changement.
4. Placer le curseur dans une autre application, appuyer puis relâcher Option, parler et appuyer de nouveau sur Option. Le texte est inséré après la fin du traitement, sans clic supplémentaire.
5. Les mots récents apparaissent en bas de la pastille ; les lignes précédentes remontent avec un fondu en haut. Cliquer dessus ouvre l’éditeur rapide ; le bouton « Ouvrir dans une fenêtre » ouvre une fenêtre macOS complète, redimensionnable et compatible plein écran. Cliquer sur « Pause » pendant l’écoute pour écrire, puis sur « Reprendre » pour continuer. Ces boutons sont présents dans les deux fenêtres ; la pastille propose les mêmes commandes sous forme d’icônes. « Annuler » arrête la dictée sans l’envoyer. « Classer » enregistre le texte dans un dossier ; « Mémoriser ce mot » retient une correction.
6. Depuis la bibliothèque, « Dicter une note » enregistre une note dans le dossier sélectionné au prochain appui isolé ; ce mode ne livre pas le texte à une application externe.

Les trois gestes sont identiques dans la pastille, l’édition rapide et la grande fenêtre. Le délai entre les appuis suit le réglage de double-clic du Mac : l’application attend ce délai après un ou deux appuis pour distinguer les gestes, et annule dès le troisième relâchement. Les combinaisons Option + lettre ou clic et les appuis prolongés ne lancent pas de commande. Depuis une pause, un appui isolé termine ; deux appuis rapides reprennent.

L’application doit rester ouverte, avec son icône dans la barre des menus, pour recevoir Option. « Ouvrir DictAI à la connexion au Mac » permet de la rendre disponible après ouverture de session. Une application complètement quittée ne peut pas surveiller le clavier.

La pastille et l’éditeur rapide peuvent être déplacés ; la fenêtre complète peut aussi être redimensionnée. Échap ferme et conserve le brouillon. Fermer le panneau arrête le microphone et conserve le brouillon. Reprendre continue le brouillon ouvert. Après fermeture ou annulation, un nouvel appui sur Option commence une dictée vide et capture le champ actuellement actif. Le texte précédent est conservé dans les notes, ou met à jour la note déjà enregistrée. « Ouvrir le brouillon » puis « Reprendre » permet de continuer explicitement une dictée interrompue. Pause et les changements de vue ne vident pas le texte. Terminer une insertion ou enregistrer une note clôt la session et conserve le texte dans les notes. Les retouches manuelles sont protégées contre les résultats tardifs de reconnaissance et de nettoyage cloud.

## Identité et thèmes

L’application reprend le tracé exact du logo Android à trois boucles, y compris dans l’icône de l’application et la barre des menus. La version 0.3 ajoute les boucles animées pendant l’écoute, avec une amplitude liée au niveau sonore et un mode statique lorsque Réduire les animations est activé. La police Caveat et les couleurs viennent du mobile. Les thèmes carnet clair, carnet sombre et système suivent la préférence synchronisée `theme_mode`. La licence SIL Open Font License de Caveat est incluse dans le paquet.

[Simplification de la correction dans la version 0.4.3](../../docs/2026-10-11-cloud-cleanup-only.md).

La version **0.4.4** ajoute **Historique des dictées** dans la bibliothèque et le menu de la barre des menus : les dix dernières transcriptions peuvent être relues et copiées, même si l’insertion échoue. La onzième remplace la plus ancienne. Ce tampon reste local, survit à la fermeture et peut être vidé ; les dictées envoyées ne sont plus automatiquement archivées dans les notes. Les notes existantes et les notes enregistrées volontairement sont conservées. [Fonctionnement et vérifications](../../docs/2026-10-11-recent-dictations.md).

La version **0.4.5** complète le menu de l’icône DictAI dans la barre des menus : **Copier la dernière transcription**, accès direct à l’historique, à la bibliothèque et aux réglages, pause/reprise de la dictée en cours et **Quitter DictAI**. La copie rapide utilise le texte intégral de la dernière dictée terminée. Elle est désactivée lorsque l’historique est vide.

La version **0.4.6** utilise une image native du logo pour rendre l’icône visible dans la barre des menus, avec adaptation automatique au fond clair ou sombre.

## Notes, dossiers et vocabulaire

Les notes s’enregistrent progressivement sur le Mac. La bibliothèque propose la recherche, le classement, l’export texte, la suppression synchronisée et l’affichage des images reçues d’Android. Le menu « Classer » du panneau choisit le dossier de destination.

Un terme seul enrichit le contexte du moteur vocal ; une règle comme `dicta => DictAI` remplace un mot ou une expression après reconnaissance. Une petite retouche dans le panneau peut être mémorisée avec le bouton « Mémoriser ce mot ». Cet apprentissage est toujours explicite.

Le protocole Android actuel ne possède pas de champ parent pour les dossiers. Sur le Mac, les sous-dossiers utilisent donc des noms comme `École / Réunions`, affichés avec indentation. Android conserve le chemin complet comme nom de dossier. Il ne s’agit pas encore d’une arborescence native partagée entre les deux interfaces.

## Synchronisation facultative

La connexion Google utilise le même projet Supabase, la même table et le même protocole v1 que l’APK. Le retour OAuth PKCE réutilise le schéma déjà autorisé `com.uhama.whisperpin://auth/callback`. Les jetons et la clé OpenRouter restent dans le Trousseau ; aucun secret n’est inclus dans le code ni dans les documents synchronisés.

Chaque compte dispose de sa propre bibliothèque sur disque. Les notes créées sans compte restent dans l’espace local à la connexion ; se déconnecter permet de les retrouver. Elles ne sont pas envoyées automatiquement dans un compte nouvellement connecté.

Les horloges, suppressions et copies de conflit sont compatibles avec Android. Une modification concurrente conserve une version visible. Les images sont récupérées avant import et vérifiées par SHA-256. Un échec réseau laisse la bibliothèque locale utilisable. Une synchronisation est tentée après une modification et toutes les 30 secondes pendant que l’application est ouverte.

Le statut « Échange terminé » confirme un échange avec le serveur, pas la réception par un autre appareil. La connexion au compte personnel et la convergence Mac/téléphone demandent un essai avec les deux applications connectées.

## Ponctuation et correction facultative

La ponctuation automatique du moteur Apple est activée (`addsPunctuation`). Les résultats provisoires peuvent être révisés en cours d’écoute. La ponctuation ajoutée après une correction de mot est conservée à la jonction avec la suite ; la ponctuation modifiée ou supprimée manuellement reste prioritaire. Cette correction ne garantit pas que le moteur place toujours chaque virgule correctement.

La correction OpenRouter est le seul post-traitement par modèle. Lorsqu’elle est activée, le texte et le vocabulaire utile sont envoyés au modèle choisi pour une correction fidèle, sans mise en forme en liste ou en courriel. Les formats personnels ne sont plus exécutés. Le texte brut est conservé en cas d’échec. La clé est propre au Mac et ne se synchronise pas. Sans correction cloud, les règles simples de typographie et le vocabulaire restent appliqués immédiatement par le code. La ponctuation Apple Intelligence a été retirée.

Cette version utilise le moteur vocal Apple ; elle n’embarque pas les modèles sherpa-onnx, Nemotron ou Gemma de l’APK. Les préférences inconnues de l’interface Mac sont conservées dans le document partagé. La langue est appliquée ; les anciennes préférences de formats restent conservées pour la compatibilité des données, sans être exécutées. Le mode Réunion expérimental est abandonné et ne fait pas partie de cette version.

## Compilation et tests

macOS 14 minimum. Swift 5.9 pour l’application ; Swift 6 avec Swift Testing pour les tests. Aucun paquet externe n’est nécessaire. Le build produit une application universelle Apple Silicon et Intel. La reconnaissance vocale doit encore être validée sur un Mac Intel physique.

```sh
cd desktop/macos
./scripts/test.sh
./scripts/build-app.sh
open dist/DictAI.app
```

Les scripts utilisent les Command Line Tools par défaut. Pour Xcode, définir `DEVELOPER_DIR` avec le chemin de son dossier Developer. La signature locale est ad hoc ; une distribution publique nécessite une signature Developer ID et une notarisation. `DICTAI_SIGNING_IDENTITY` permet de choisir un certificat déjà installé.

Les tests utilisent des dossiers temporaires. L’argument `--preview` ouvre une bibliothèque isolée sans connexion ni surveillance globale du clavier, pour les essais d’interface. Les captures de contrôle sont dans `artifacts/` ; elles ne sont pas incluses dans l’app.

Le workflow `.github/workflows/macos.yml` compile et publie l’archive comme artefact `dictai-macos` lorsqu’il est exécuté sur GitHub. Les tests et la compilation de l’installateur sont exécutés à chaque modification de cette version.

## Références et provenance

Sources consultées le 6 octobre 2026 :

- [Handy](https://github.com/cjpais/Handy), commit `a94b403e0610049fafa54b0a4077db2945084dd8`, licence MIT : raccourci, transcription locale et cycle de restitution du presse-papiers.
- [FluidVoice](https://github.com/altic-dev/FluidVoice), commit `afbd56dd33e299fd3c989c61f64ad2ab848399c3`, licence GPL-3.0 : intégration macOS et gestion des touches modificatrices.
- [Apple Speech](https://developer.apple.com/documentation/speech/sfspeechaudiobufferrecognitionrequest) : reconnaissance progressive sur l’appareil.

L’implémentation Swift de DictAI est nouvelle ; aucun fichier de Handy ou FluidVoice n’est incorporé. Les contrats de données sont adaptés du code Android de ce dépôt. Les notices Apache-2.0 du projet sont incluses dans l’application.

## Aperçu de développement

`./scripts/build-preview.sh` prépare une application **DictAI Preview** séparée. Elle simule des mots et un niveau sonore dans une bibliothèque temporaire, sans ouvrir le microphone ni insérer de texte ailleurs. La version distribuée ne contient pas le marqueur de cet aperçu. Les mêmes commandes sont disponibles avec `--preview --preview-dictation`.
