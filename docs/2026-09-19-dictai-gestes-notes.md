# DictAI : gestes, fenêtre et notes

Les fonctions ci-dessous sont présentes dans la branche locale `codex/dictai-0.9.6`.

| Besoin | Utilisation |
| --- | --- |
| Déplacer la fenêtre ouverte | Faire glisser la poignée `⠿` dans son en-tête. La pastille garde sa position ; la pointe reste orientée vers elle. |
| Modifier la largeur ou la hauteur | Faire glisser l’un des quatre repères de coin, en mode réduit. |
| Retrouver la position et la taille standard | Maintenir la poignée `⠿` sans la déplacer. |
| Choisir rapidement un format | Depuis la pastille au repos, glisser vers le haut, garder le doigt appuyé, poursuivre le glissement pour changer la sélection, puis relâcher. |
| Ouvrir simplement le menu des formats | Relâcher après le premier glissement ; le format actuel reste sélectionné. |
| Changer la place d’une image | Dans une note, maintenir sa miniature puis choisir « Monter l’image » ou « Descendre l’image ». L’image peut traverser un passage de texte ou une autre image. |
| Organiser les notes | Dans « Mes notes », créer un dossier même vide, le renommer ou le supprimer. Les actions d’une note permettent de la déplacer dans un dossier existant, un nouveau dossier ou « Sans dossier ». |

La taille personnalisée est limitée à **92 % de la largeur et 75 % de la hauteur disponibles**. Le minimum visé est **240 × 160 dp**, réduit si nécessaire pour tenir dans l’espace restant. Le clavier, la rotation et la place de la pastille sont pris en compte. La position et la taille sont mémorisées.

Les dossiers sont facultatifs. Dès qu’un dossier existe, DictAI propose où ranger une nouvelle note ; les sauvegardes automatiques n’interrompent pas la dictée. Supprimer un dossier conserve ses notes dans « Sans dossier ». Les exports reprennent l’ordre des images dans le texte.

L’accueil utilise les mêmes boucles cursives que la pastille. Une marge de mesure supplémentaire réserve le trait supérieur du I final.

## Aperçus natifs de l’accueil

Ces images sont produites par les vues Android sous Robolectric. Elles permettent de contrôler le dessin et la mise en page ; elles ne remplacent pas un essai tactile sur téléphone.

![Accueil clair : I complet et boucles cursives](../app/build/robolectric-renders/main-light.png)

![Accueil sombre](../app/build/robolectric-renders/main-dark.png)

![Écran étroit avec grande police ; le contenu défile verticalement](../app/build/robolectric-renders/main-small-large-font.png)

## Vérification du 19 septembre 2026

La suite JVM/Robolectric complète a été réexécutée sous le JDK 21 : **527 tests, aucun échec, aucune erreur et aucun test ignoré**. Les 524 tests existants couvrent les dossiers, l’ordre des images à l’export, la sélection continue des formats et le déplacement indépendant du panneau ; trois tests natifs supplémentaires couvrent les poignées des coins.

Le test diagnostique Robolectric utilise les `hitRect` après mesure et layout, puis envoie un appui au centre de chacune des quatre poignées. Avant correction, il échoue avec les rectangles `y=48..84` et `y=76..112` à 160 dp, soit un chevauchement de 8 dp. La correction conserve les gouttières et limite la hauteur à la moitié de l’espace disponible, avec un plafond de 36 dp ; sans espace, les poignées sont masquées. À 120 dp elles mesurent 12 dp, à 160 dp 32 dp, puis reviennent à 36 dp dans le panneau note standard de 268 dp.

### Rendus natifs des coins

![Panneau réduit 240 × 160 dp avant correction : les bandes tactiles se recouvrent](../app/build/robolectric-renders/overlay-corner-targets-reduced-before-fix.png)

![Panneau réduit 240 × 160 dp après correction : les quatre poignées restent dans le panneau](../app/build/robolectric-renders/overlay-corner-targets-reduced-after-fix.png)

![Panneau réduit à 120 dp sous contrainte extrême du clavier](../app/build/robolectric-renders/overlay-corner-targets-keyboard-120.png)

![Panneau note standard 312 × 268 dp après correction](../app/build/robolectric-renders/overlay-corner-targets-standard-note-after-fix.png)

Le rendu à 120 dp est une contrainte extrême : le texte dispose de peu de hauteur et doit rester défilable ; l’aperçu ne constitue pas une promesse de confort de lecture dans cet espace.

### Preuves et livraison

- [Test diagnostique Robolectric](../app/src/test/kotlin/com/kafkasl/phonewhisper/OverlayCornerHandlesDiagnosticTest.kt)
- [Journal de la suite complète](../app/build/reports/task-final-verification-corner-fix-2026-09-19.log)
- [Journal `assembleDebug`](../app/build/reports/assembleDebug-corner-fix-2026-09-19.log)
- [Journal RED avant correction](../app/build/reports/overlay-corners-red-before-artifact-2026-09-19.log)
- Sources relues lors des deux passes finales : `OverlayService.kt` — SHA-256 `67e4a5db…` ; `OverlayCornerHandlesDiagnosticTest.kt` — SHA-256 `43cb376e…`.
- [APK debug corrigé](</Users/ulliemaillot/Downloads/dictai-0.9.6-gestes-dossiers-corrige.apk>) — `com.uhama.whisperpin`, versionCode 35, versionName `0.9.6-dictai`, SHA-256 `9085115349f4776b79de26890646bbe5316a4ac44a8eed240cb1449e33b2b7df`

`adb devices -l` ne signalait aucun appareil pendant la vérification. Les gestes réels sur Poco F7 ou Pad 7 restent donc à valider sur matériel ; les résultats ci-dessus couvrent la géométrie, le rendu natif et le routage Robolectric.
