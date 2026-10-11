# DictAI : vérification des gestes et des coins de l’overlay

**Objectif :** vérifier les cinq fonctions demandées déjà présentes en 0.9.6 et garantir que chaque coin du panneau réduit possède une zone tactile distincte.

**Architecture :** conserver les limites de taille, la géométrie indépendante de la pastille, les dossiers facultatifs et la sélection continue des formats. Une éventuelle correction reste limitée au placement des poignées dans les gouttières du panneau.

**Exécution :** Luna Max réalise les tests et toute correction ; revue fonctionnelle puis revue des régressions par l’agent principal. Aucun commit ni push.

## Vérification de l’existant

- [x] Exécuter la suite JVM/Robolectric avec JDK 21 et construire l’APK.
- [x] Inspecter les rendus de l’accueil : boucles cursives, I complet, modes clair/sombre et grande police.
- [x] Vérifier les dossiers, le déplacement des images et la conservation de leur ordre à l’export.
- [x] Vérifier les gestes de sélection, leur annulation et le déplacement indépendant du panneau.

## Coins du panneau réduit

**Fichiers concernés :** `OverlayService.kt`, tests Robolectric du panneau et rendus natifs.

À 160 dp de hauteur, les deux bandes de 48 dp laissent 64 dp pour deux poignées de 36 dp. Le calcul actuel suggère un chevauchement de 8 dp. Reproduire ce cas par un test des rectangles réels et du routage tactile avant de modifier le code.

- [x] Produire un test de non-chevauchement qui échoue sur le code actuel et un rendu de diagnostic.
- [x] Si confirmé, limiter la hauteur de chaque poignée à la moitié de la place disponible entre les bandes, avec un plafond de 36 dp. Garder la largeur et les gouttières existantes.
- [x] Vérifier les quatre coins, le panneau standard, la hauteur minimale et un espace réduit par le clavier. Les zones restent dans le panneau et hors des boutons et du texte.
- [x] Relancer les tests concernés, puis la suite intégrée et la construction de l’APK après toute correction.
- [x] Inspecter les rendus finaux et fournir un guide avec les images, l’APK et les limites de validation sur appareil.

Résultat : la suite finale compte 527 tests verts. Les fichiers de production et de test relus lors des deux passes finales sont `OverlayService.kt` (préfixe SHA-256 `67e4a5db`) et `OverlayCornerHandlesDiagnosticTest.kt` (préfixe SHA-256 `43cb376e`). La validation physique reste ouverte car `adb devices -l` était vide.
