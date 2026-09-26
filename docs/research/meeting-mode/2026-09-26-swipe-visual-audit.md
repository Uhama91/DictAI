# Réunion test3 — audit visuel des gestes

**Captures de l’APK test3, révision 10.** Le cercle Réunion est agrandi : environ 33,6 dp de diamètre tracé dans la pastille de 74 × 44 dp. La marge visible reste d’environ 5,2 dp en haut et en bas. APK SHA-256 : `ca50108b3197c1647d6f51f25ae6871fe1456e715d8b1ce63381e8b9edac7147`.

Parcours exécuté sur émulateur Android ARM64 API 36 avec de vrais événements tactiles. Les paroles et intervenants sont simulés : ces captures vérifient l’interface, pas la reconnaissance vocale. Le scénario est vert en 17,325 secondes sur l’utilisateur principal de l’émulateur (journal final des gestes, révision 10).

Les 13 images natives du parcours gestuel, de 1080 × 2400 pixels, ont été inspectées. Le cercle reste contenu dans la pastille au repos, en écoute et pendant la fermeture. Les menus de dossiers restent lisibles, la pastille demeure accessible dans les nouveaux parcours et le retour affiche la racine. La liste principale défile quand son contenu dépasse la hauteur disponible. Le texte reste affiché pendant l’annulation. Les étapes de retouche, pause, confirmation et création d’une nouvelle session restent fonctionnelles.

## Cercle agrandi : pause et rotation

Les cinq captures supplémentaires ci-dessous ont été inspectées. Le test de dessin natif passe en 9,308 secondes. La pastille centrale utilise ses dimensions réelles ; l’aperçu de 216 × 216 dp permet de voir la courbe en détail. Les deux angles de rotation et la reprise de pause gardent une marge ; le retour en Dictée retrouve l’onde horizontale et sa hauteur de dessin de 32 dp. Aucun audio ni modèle n’est utilisé par cette fixture.

| Réunion en pause | Rotation, premier instant |
| --- | --- |
| ![Réunion en pause](ui-renders/swipe-instrumentation-final-rev10/cursive-wave-captures/cursive-wave-meeting-fixtures-simulated/01-meeting-paused-static.png) | ![Rotation, premier instant](ui-renders/swipe-instrumentation-final-rev10/cursive-wave-captures/cursive-wave-meeting-fixtures-simulated/02-meeting-silent-animation-a.png) |

| Rotation, second instant | Retour en pause |
| --- | --- |
| ![Rotation, second instant](ui-renders/swipe-instrumentation-final-rev10/cursive-wave-captures/cursive-wave-meeting-fixtures-simulated/03-meeting-silent-animation-b.png) | ![Retour en pause](ui-renders/swipe-instrumentation-final-rev10/cursive-wave-captures/cursive-wave-meeting-fixtures-simulated/04-meeting-paused-again.png) |

Retour en Dictée

![Retour en Dictée](ui-renders/swipe-instrumentation-final-rev10/cursive-wave-captures/cursive-wave-meeting-fixtures-simulated/05-dictation-existing-wave.png)

## Navigation et annulation

| Dossier avant le geste sur la liste | Racine après le geste sur la liste |
| --- | --- |
| ![Dossier avant le geste sur la liste](ui-renders/swipe-instrumentation-final-rev10/captures/00-simulated-folder-before-list-swipe.png) | ![Racine après le geste sur la liste](ui-renders/swipe-instrumentation-final-rev10/captures/00-simulated-folder-root-after-list-swipe.png) |

| Dossier avant le geste sur la pastille | Racine après le geste sur la pastille |
| --- | --- |
| ![Dossier avant le geste sur la pastille](ui-renders/swipe-instrumentation-final-rev10/captures/00-simulated-folder-before-pill-swipe.png) | ![Racine après le geste sur la pastille](ui-renders/swipe-instrumentation-final-rev10/captures/00-simulated-folder-root-after-pill-swipe.png) |

| Dossier pendant une réunion simulée | Retour à la racine, réunion toujours active |
| --- | --- |
| ![Dossier pendant une réunion simulée](ui-renders/swipe-instrumentation-final-rev10/captures/00-simulated-folder-active-before-pill-swipe.png) | ![Retour à la racine, réunion toujours active](ui-renders/swipe-instrumentation-final-rev10/captures/00-simulated-folder-active-root-after-pill-swipe.png) |

| Réunion simulée avant annulation | Fermeture après le geste, texte conservé |
| --- | --- |
| ![Réunion simulée avant annulation](ui-renders/swipe-instrumentation-final-rev10/captures/06-simulated-meeting-before-cancel-swipe.png) | ![Fermeture après le geste, texte conservé](ui-renders/swipe-instrumentation-final-rev10/captures/06-simulated-meeting-cancellation-in-progress.png) |

| Sélection du mode sans démarrage du micro | Retouche du texte et du nom |
| --- | --- |
| ![Sélection du mode sans démarrage du micro](ui-renders/swipe-instrumentation-final-rev10/captures/01-menu-no-start.png) | ![Retouche du texte et du nom](ui-renders/swipe-instrumentation-final-rev10/captures/02-renamed-and-edited-turns.png) |

| Confirmation après pause | Note enregistrée avec la retouche |
| --- | --- |
| ![Confirmation après pause](ui-renders/swipe-instrumentation-final-rev10/captures/03-paused-confirmation.png) | ![Note enregistrée avec la retouche](ui-renders/swipe-instrumentation-final-rev10/captures/04-finished-structured-note.png) |

Nouvelle session vide

![Nouvelle session vide](ui-renders/swipe-instrumentation-final-rev10/captures/05-new-session-empty-profiles.png)

## Parcours du moteur de production, avant le micro

Ces deux captures supplémentaires ont été inspectées. Elles montrent le menu ouvert depuis Dictée, puis le panneau Réunion prêt avant le démarrage du micro. Le test AudioRecord passe en 24,948 secondes ; ses états d’écoute, pause et fermeture sont établis par les journaux, pas par ces images. La galerie comporte au total 20 captures.

| Menu depuis Dictée | Réunion prête avant le micro |
| --- | --- |
| ![Menu depuis Dictée](ui-renders/swipe-instrumentation-final-rev10/runtime-captures/c67f13bf-868b-47c0-bd8d-46a3a729ba11-3131753/dictation-swipe-menu-open.png) | ![Réunion prête avant le micro](ui-renders/swipe-instrumentation-final-rev10/runtime-captures/c67f13bf-868b-47c0-bd8d-46a3a729ba11-3131753/meeting-panel-ready-before-microphone.png) |
