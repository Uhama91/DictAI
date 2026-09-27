# Réunion test4 — galerie Android vérifiée

Les 32 images ci-dessous sont des captures natives de l’AVD Android API 36 ARM64. Astra les a toutes inspectées. Elles vérifient la présentation et les parcours, sans démontrer la latence ou la précision sur Poco F7.

L’APK principal local est `0ad1d9dd283a2d33edf6e42b19db6d1cfcc0e3a332c234bd1dec8ab1d74d0732`. La fixture finale utilise l’APK AndroidTest `d6db33d57fccbc6313eb0b045398fef5d0df6f4cab5621f07e8a4a55320951c6` et réussit en 19,957 s. Les gestes passent en 22,916 s et le cycle de vie runtime en 25,792 s, avec le même APK principal.

Les 17 captures de panneau ont un contenu, des temps et des statuts **simulés**. Les 13 captures de gestes utilisent aussi des moteurs simulés. Le test runtime utilise les vrais JNI et AudioRecord, mais son micro d’émulateur ne produit aucun passage reconnu (`turns=0`). Le test audio intégré, ses résultats et l’échec de débit prolongé sont décrits dans le [rapport de validation](../../2026-09-27-handy-conversation-validation.md).

L’audit a conduit à ancrer l’étiquette de personne en haut d’un long passage compact. Le dernier jeu stabilise les rotations, affiche la taille réelle du catalogue et retire une ancienne pastille de test qui masquait du texte. Le panneau minimal tronque volontairement le titre et nécessite un défilement pour le texte long. L’écran normal montre l’ensemble de la conversation de démonstration ; les captures de début et de fin y sont donc semblables.

## Panneau : données simulées, rendu Android réel

Trois voix et retour à la personne précédente.

![Trois voix et retour à la personne précédente.](final-panel-fixture-v3-clean/01-three-voices-return.png)

Dialogue de personnes homonymes : identités distinctes.

![Dialogue de personnes homonymes : identités distinctes.](final-panel-fixture-v3-clean/02-homonyms-native-dialog.png)

Nom long dans le dialogue natif de renommage.

![Nom long dans le dialogue natif de renommage.](final-panel-fixture-v3-clean/03-long-rename-native-dialog.png)

Renommage appliqué dans le document.

![Renommage appliqué dans le document.](final-panel-fixture-v3-clean/04-renamed-homonyms.png)

Voix ignorée et action d’image conservées.

![Voix ignorée et action d’image conservées.](final-panel-fixture-v3-clean/05-ignored-voice-image-action.png)

Champ éditable actif et clavier Android.

![Champ éditable actif et clavier Android.](final-panel-fixture-v3-clean/06-focused-edit-field.png)

Erreur de sauvegarde et action de nouvelle tentative.

![Erreur de sauvegarde et action de nouvelle tentative.](final-panel-fixture-v3-clean/07-live-save-error.png)

Téléchargement simulé : taille dérivée du catalogue, 859 Mo.

![Téléchargement simulé : taille dérivée du catalogue, 859 Mo.](final-panel-fixture-v3-clean/08-model-download-progress.png)

Thème clair.

![Thème clair.](final-panel-fixture-v3-clean/09-manual-light-theme.png)

Thème sombre.

![Thème sombre.](final-panel-fixture-v3-clean/10-manual-dark-theme.png)

Panneau minimal 240 × 112 dp, texte long : initiale visible en haut.

![Panneau minimal 240 × 112 dp, texte long : initiale visible en haut.](final-panel-fixture-v3-clean/11-landscape-compact-240x112-normal-font.png)

Même contrainte avec police agrandie ; contenu accessible par défilement.

![Même contrainte avec police agrandie ; contenu accessible par défilement.](final-panel-fixture-v3-clean/12-landscape-compact-240x112-large-font.png)

Conversation A/A/B/A : continuation sans titre répété, temps audio, image et passage incertain.

![Conversation A/A/B/A : continuation sans titre répété, temps audio, image et passage incertain.](final-panel-fixture-v3-clean/13-conversation-aaba-normal-top-portrait-simulated.png)

Fin du même document : les cinq passages tiennent ici entièrement à l’écran.

![Fin du même document : les cinq passages tiennent ici entièrement à l’écran.](final-panel-fixture-v3-clean/14-conversation-aaba-normal-bottom-portrait-simulated.png)

Panneau minimal : initiale, temps et audio en attente.

![Panneau minimal : initiale, temps et audio en attente.](final-panel-fixture-v3-clean/15-conversation-aaba-compact-top-backlog-simulated.png)

Panneau minimal : « Voix indisponibles » reste visible pendant la transcription.

![Panneau minimal : « Voix indisponibles » reste visible pendant la transcription.](final-panel-fixture-v3-clean/16-conversation-aaba-compact-voices-unavailable-simulated.png)

Panneau minimal au bas du défilement : passage sans attribution. Le haut de sa ligne est hors de la fenêtre.

![Panneau minimal au bas du défilement : passage sans attribution. Le haut de sa ligne est hors de la fenêtre.](final-panel-fixture-v3-clean/17-conversation-aaba-compact-bottom-simulated.png)

## Gestes : interactions Android réelles, moteurs et document simulés

Dossier avant glissement dans la liste.

![Dossier avant glissement dans la liste.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/00-simulated-folder-before-list-swipe.png)

Retour à la racine après glissement dans la liste.

![Retour à la racine après glissement dans la liste.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/00-simulated-folder-root-after-list-swipe.png)

Dossier avant glissement sur la pastille.

![Dossier avant glissement sur la pastille.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/00-simulated-folder-before-pill-swipe.png)

Retour à la racine après glissement sur la pastille.

![Retour à la racine après glissement sur la pastille.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/00-simulated-folder-root-after-pill-swipe.png)

Dossier ouvert pendant une session.

![Dossier ouvert pendant une session.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/00-simulated-folder-active-before-pill-swipe.png)

Navigation ramenée à la racine pendant la session.

![Navigation ramenée à la racine pendant la session.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/00-simulated-folder-active-root-after-pill-swipe.png)

Le glissement ouvre le menu ; le choix du mode attend un toucher.

![Le glissement ouvre le menu ; le choix du mode attend un toucher.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/01-menu-no-start.png)

Personne renommée et passage retouché.

![Personne renommée et passage retouché.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/02-renamed-and-edited-turns.png)

Confirmation en pause, retouches conservées.

![Confirmation en pause, retouches conservées.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/03-paused-confirmation.png)

Réunion sauvegardée dans une note structurée.

![Réunion sauvegardée dans une note structurée.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/04-finished-structured-note.png)

Nouvelle session sans profils hérités.

![Nouvelle session sans profils hérités.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/05-new-session-empty-profiles.png)

Réunion avant annulation, couronne contenue dans la pastille.

![Réunion avant annulation, couronne contenue dans la pastille.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/06-simulated-meeting-before-cancel-swipe.png)

Annulation engagée : fermeture indiquée et texte conservé.

![Annulation engagée : fermeture indiquée et texte conservé.](final-gestures/meeting-overlay-gestures-644fd9f5-aa6e-47ef-bbbe-0c3543ddfc4c/06-simulated-meeting-cancellation-in-progress.png)

## Runtime de production : JNI et AudioRecord réels

Menu réel Dictée/Réunion avant sélection.

![Menu réel Dictée/Réunion avant sélection.](final-runtime/5f9803b20f7b4a5ca2ed2b4519a759c1-21460694/dictation-swipe-menu-open.png)

Modèles exacts du catalogue v2 prêts avant ouverture du micro.

![Modèles exacts du catalogue v2 prêts avant ouverture du micro.](final-runtime/5f9803b20f7b4a5ca2ed2b4519a759c1-21460694/meeting-panel-ready-before-microphone.png)

Les autorisations temporaires ont été restaurées après le runner : micro refusé, appop overlay par défaut, aucun service d’overlay actif et utilisateur initial au premier plan. Les images originales sont en 1080 × 2400 ou 2400 × 1080 pixels.

