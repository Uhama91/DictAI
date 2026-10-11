# DictAI Mac 0.3.4 — Trois gestes sur Option

Les gestes fonctionnent depuis la pastille, l’édition rapide, la grande fenêtre et lorsque vous travaillez dans une autre application.

| Geste | Pendant l’écoute | En pause |
|---|---|---|
| Un appui isolé | Terminer et insérer le texte, ou enregistrer la note | Terminer et insérer, ou enregistrer |
| Deux appuis rapides | Mettre en pause | Reprendre le même brouillon |
| Trois appuis rapides | Annuler sans envoyer | Annuler sans envoyer |

Quand aucune dictée n’est ouverte, **un appui isolé démarre une nouvelle dictée**. Deux ou trois appuis ne démarrent pas le microphone. L’annulation ferme l’interface et conserve le brouillon récupérable. Les boutons Pause/Reprendre/Annuler et Échap restent disponibles.

## Rythme des appuis

Le délai entre le relâchement d’Option et l’appui suivant utilise **le réglage de double-clic du Mac**, lu au démarrage de la surveillance du clavier. C’est un choix d’adaptation au rythme déjà configuré par l’utilisateur ; Apple ne prescrit pas ce réglage pour ce geste de dictée. [Apple — délai de double-clic](https://developer.apple.com/documentation/appkit/nsevent/doubleclickinterval), [Apple — modifier la vitesse du double-clic](https://support.apple.com/fr-fr/guide/mac-help/mchlp1138/mac).

L’application attend ce court délai après un ou deux appuis avant d’exécuter la commande. Le troisième appui annule dès son relâchement : **un triple appui ne commence jamais par insérer ou mettre en pause**. Si la touche est déjà enfoncée pour le deuxième ou le troisième appui, le minuteur attend son relâchement. Un quatrième appui accidentel ne relance pas aussitôt une dictée : il faut laisser passer un intervalle sans appui après l’annulation.

Il suffit d’appuyer puis de relâcher, sans forcer. Les contacts de moins de 25 ms et les appuis prolongés de plus de 1,2 seconde sont ignorés ; cette dernière limite augmente si le délai macOS dépasse 600 ms. Une lettre, un autre modificateur, un clic ou un défilement interrompt la séquence en attente. Option + lettre et Option + clic conservent donc leur usage normal. Les touches Option gauche et droite sont prises en charge séparément ; les maintenir ensemble n’est pas une commande.

Si le moteur vocal finalise encore une pause, une demande de reprise est conservée et exécutée après les derniers mots. Un appui simple à ce moment demande au contraire de terminer. La correction configurée reste appliquée avant l’insertion, y compris lorsque l’on termine depuis la pause.

## Vérifications

**98 tests réussis dans 14 suites**, compilation de production et signature locale vérifiées. La version **0.3.4, build 7**, est installée dans `~/Applications/DictAI.app`. Les 13 notes et les fichiers de données, dont le brouillon, sont inchangés après le remplacement de l’application. Une sauvegarde de la version précédente est disponible dans `~/Downloads/DictAI-macOS-before-0.3.4.zip`. Le réglage du Mac contrôlé dans l’application vaut **500 ms**.

Les tests distinguent les séquences à un, deux et trois appuis ; ils couvrent les limites de temps, les rebonds, les appuis longs, les combinaisons clavier, les deux touches Option, l’annulation d’un minuteur et un retard du fil principal. Les transitions Pause/Reprendre/Annuler sont testées dans les trois présentations, avec conservation du brouillon et absence d’insertion sur un double ou un triple appui.

Les trois autorisations (microphone, reconnaissance vocale, accessibilité) sont renouvelées et confirmées dans l’application. Le moteur atteint l’état « À votre écoute ». Cet essai sans parole se termine par « No speech detected » à l’arrêt ; il ne valide donc pas encore la reconnaissance de la voix ni les gestes physiques.

Ces tests alimentent le détecteur et son adaptateur natif sans simuler de frappe dans macOS. L’outil de pilotage ne sait pas produire Option seule : la validation des gestes physiques sur le clavier reste distincte de ces tests automatisés.

[Tests](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/gestures-0.3.4-tests.log) · [Compilation](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/gestures-0.3.4-build.log)

## Aperçus vérifiés

Captures de l’application native en mode de démonstration isolé. Le texte simulé reste dans cette démonstration. Les libellés des gestes, les commandes de pause/reprise, le logo et l’agencement ont été inspectés dans les trois présentations.

![Pastille en pause et rappel des trois gestes](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/gestures-0.3.4-pill.png)

![Édition rapide avec les gestes Option](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/gestures-0.3.4-editor.png)

![Grande fenêtre de travail avec reprise et rappel des gestes](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/gestures-0.3.4-window.png)
