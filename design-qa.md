# Design QA DictAI — 12 septembre 2026

## Statut

**BLOQUÉ — aucune validation visuelle runtime passée.**

`final result: blocked`

Sources de vérité visuelles :
`docs/design/2026-09-12-carnet/concepts/ivoire-encre-sauge-v2.png` et les
références approuvées sous
`docs/design/2026-09-12-carnet/selected/` (`main-sombre.png`,
`preferences-clair.png`, `dictee-clair.png`, `mise-en-forme-clair.png`,
`overlay-clair-enregistrement.png`, `overlay-sombre-pause.png`,
`notes-clair.png`, `photo-clair.png`, `export-clair.png`). Le screenshot de
l’implémentation est indisponible ; la comparaison pleine vue et par régions
reste impossible sans runtime.

Évaluation visuelle des cinq surfaces — accueil/réglages, overlay, notes,
photo et export — **NON VALIDÉE** pour la typographie, l’espacement, les
couleurs, les images et la copie. La revue statique et les tests attestent le
chemin de code, pas la fidélité rendue à l’écran.

La compilation, les tests unitaires et la revue statique du diff sont passés,
mais aucune capture de l’application modifiée n’est disponible. Le seul essai
de runtime a utilisé l’AVD jetable `dictai-api30` avec Android 30 Google APIs,
390 × 844 px, densité 160 et accélération logicielle `-accel off`. La machine
n’a pas KVM ; le premier boot a déclenché le watchdog Android dans
`system_server`, puis ADB n’a pas fourni un framework stable pour installer et
lancer DictAI. L’AVD et ses processus ont été arrêtés ensuite. Aucun téléphone
réel n’a été utilisé.

## Contrôles réalisés

- `:app:testDebugUnitTest :app:assembleDebug` : 462 tests, 0 échec, 0 erreur,
  0 ignoré ; APK debug assemblé.
- APK final après le clipping des coins :
  `app/build/outputs/apk/debug/app-debug.apk`, 90 419 811 octets,
  SHA-256 `04e40304745e4bb8fd6546c5adfa5e8b896dddf6bd9a5a0aafb49969c4234e97`.
- `git diff --check` : propre.
- Revue statique des chemins accueil/réglages, thèmes, overlay, notes, caméra,
  contrôleur/panneau d’export et pont sélecteur.
- Les références ImageGen sélectionnées ont été observées avant
  implémentation ; les PNG font 853 × 1844 pixels pour une cible de contenu
  390 × 844 dp.
- Les tests unitaires `ExportGenerationGate` et `OverlayBridgeReturnGate`
  vérifient l’invalidation des résultats tardifs, la reprise après arrière-plan,
  les deux ordres reprise/focus et la consommation unique du retour.

## Contrôles encore impossibles

Le rendu réel et les interactions suivantes restent à vérifier sur un runtime
Android stable :

- accueil, sous-pages de réglages et choix Système/Clair/Sombre après recréation ;
- overlay compact/agrandi, clavier, sélection, défilement, animation de taille,
  indicateurs RECORDING/PAUSED/PROCESSING et respect des animations désactivées ;
- persistance réelle des notes, menus ⋮, vignettes et retour depuis une note ;
- viseur Camera2, permissions, rotation, capture et recoloration en cours de
  session ;
- génération et rendu `PdfRenderer`/`WebView`, mémoire, pages, retry, fermeture
  et retour au même brouillon ;
- sélecteurs Android Enregistrer/Partager, retour focus/reprise et rejet d’un
  ancien jeton de bridge ;
- TalkBack, tailles de police, contraste calculé par ressource, zones tactiles
  48 dp et comportement sans micro réel.

Les tests unitaires ne lancent ni `Activity`, ni `WindowManager`, ni
`PdfRenderer`, ni `WebView`, et ne constituent donc pas une preuve de design QA.
Une prochaine passe pourra débloquer ce document avec un appareil ou un
émulateur accéléré stable, puis produire les captures main, réglages, overlay,
notes, photo et export dans `docs/design/2026-09-12-carnet/current/`.
