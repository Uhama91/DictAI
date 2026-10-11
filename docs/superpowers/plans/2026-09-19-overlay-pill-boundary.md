# DictAI : butée entre la fenêtre et la pastille

**Objectif :** empêcher le panneau personnalisé de se comprimer ou de traverser la pastille lors d’un déplacement ou d’un redimensionnement. Préserver les autres fonctions et le correctif des poignées déjà présent localement.

**Cause identifiée :** `OverlayPanelGeometry.keepAwayFromPill` fabrique quatre rectangles parfois rétrécis, puis choisit celui dont la position est la plus proche. Une extension horizontale peut ainsi sélectionner un bandeau très court au-dessus de la pastille. Les appels successifs à `bound` entretiennent cette réduction.

**Niveau :** complexe pour les interactions géométriques ; implémentation et tests par Luna Max, puis deux revues du même état stabilisé par l’agent principal. Aucun commit ni push autorisé.

## Comportement attendu

- Le déplacement conserve les dimensions du panneau. À la rencontre de la pastille, il s’arrête en conservant la marge de 12 dp, y compris si un événement tactile saute directement de l’autre côté.
- Le redimensionnement conserve le coin opposé. Une extension horizontale bloquée n’écrase pas la hauteur. Les quatre coins et les limites d’écran restent utilisables.
- Les mouvements successifs utilisent la géométrie réellement acceptée ; revenir en arrière après une butée répond immédiatement. L’utilisateur peut contourner la pastille par l’espace libre.
- Lorsque le panneau personnalisé réduit est visible, déplacer la pastille vers lui rencontre la même butée. L’aimantation de fin de geste doit choisir un bord accessible sans traverser le panneau. Le déplacement historique de la pastille avec panneau masqué ou non personnalisé reste inchangé.
- Restaurer une ancienne géométrie dégradée ou adapter une rotation privilégie les emplacements conservant la taille. S’il faut réduire pour tenir à l’écran, privilégier une surface lisible et respecter les minima dès que la place le permet ; ne plus choisir un bandeau uniquement parce qu’il est proche.
- La pointe continue de suivre la pastille. Les dossiers, images, formats et paramètres de dictée restent hors périmètre.

## Fichiers et vérification

- [x] RED dans `OverlayPanelGeometryTest.kt` : pastille gauche/droite, agrandissement horizontal, déplacement sans réduction et sans traversée (`overlay-pill-boundary-red-final-2026-09-19.log`, 18 tests dont 8 rouges).
- [x] Correction bornée de `OverlayPanelGeometry.kt` ; balayage de collision continu et interpolation des bords pour le redimensionnement.
- [x] Intégration dans `OverlayService.kt` : gestes successifs, déplacement et aimantation de la pastille personnalisée.
- [x] Tests ciblés du grand delta, des diagonales, du retour après butée, du redimensionnement horizontal, du minimum lisible, de la rotation/clavier et de la restauration.
- [x] Tests Robolectric des vrais listeners, maintien du texte et de la taille, absence de modification de l’ancrage de la pastille pendant un geste du panneau.
- [x] Rendus Android inspectés pour les cas pastille à gauche et à droite, intégrés au [compte rendu](/Users/ulliemaillot/dev_project/phone-whisper/docs/2026-09-19-overlay-butee.md).
- [x] Suite JVM complète (543 tests), `assembleDebug` avec JDK 21, `git diff --check`, APK distinct et revue finale ; appareil absent de `adb devices`.
