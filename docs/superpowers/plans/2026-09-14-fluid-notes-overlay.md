# DictAI : gestes continus et organisation des notes

**Objectif :** sélectionner un format sans lever le doigt, organiser les notes et leurs images, déplacer et redimensionner la fenêtre ouverte, corriger la marque de l’accueil.

**Architecture :** conserver le service et le stockage existants ; isoler les décisions de geste, de géométrie et de rangement dans des helpers testables. Préserver les modifications déjà présentes et les données des notes existantes.

**Exécution :** politique canonique Sol–Luna ; implémentation et tests par Luna Max, aucune publication ni commit. Lots gestes et notes complexes : deux passes de revue sur le résultat stabilisé. Marque : revue moyenne.

**Résultat vérifié :** 524 tests réussis, aucun échec ni test ignoré ; `assembleDebug` réussi. Exécution avec `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'` (Java 21 requis par les tests Gemma). Version 0.9.6-dictai, code 35. APK : `~/Downloads/dictai-0.9.6-gestes-dossiers.apk`. Les preuves détaillées figurent dans `app/build/reports/task-final-verification.log` et les deux journaux de tâche associés. Deux passes de revue effectuées après correction : fonction/interface, puis persistance/régressions. Rendus natifs de l’accueil inspectés ; aucun appareil Android connecté, gestes physiques à valider.

## 1. Gestes et fenêtre

- [x] Tests RED du geste : ouverture depuis le format actuel, mouvement supplémentaire, validation au relâchement et annulation.
- [x] Intégration dans `OverlayService.kt` et helper dédié ; garder le menu tactile après un simple balayage.
- [x] Tests RED de géométrie : déplacement indépendant, quatre coins, écran réduit, rotation et clavier.
- [x] Poignée visible, redimensionnement de la fenêtre réduite, réinitialisation, position mémorisée et pointe orientée vers la pastille.
- [x] Limites proposées : minimum 240 × 160 dp sous réserve de place disponible ; maximum 92 % de largeur et 75 % de hauteur disponible. Garder la taille actuelle comme référence standard.
- [x] Tests ciblés GREEN puis revue UX et revue régressions.

## 2. Notes et dossiers

- [x] Tests RED de rétrocompatibilité, dossiers vides, renommage, déplacement, suppression sans perte de notes.
- [x] Étendre `TranscriptNotes.kt` et `AndroidTranscriptNoteStorage.kt` ; interfaces de rangement dans les fonctions notes de `OverlayService.kt`.
- [x] Garder « Sans dossier ». Proposer les dossiers existants ou leur création pour une nouvelle sauvegarde dès qu’un dossier existe ; ne pas interrompre chaque sauvegarde automatique.
- [x] Tests RED du déplacement des repères images entre les passages de texte et de la conservation de leur identité.
- [x] Actions accessibles Monter/Descendre et export respectant cet ordre.
- [x] Tests GREEN puis revue fonctionnelle et revue stockage/régressions.

## 3. Marque de l’accueil

- [x] Identifier le rognage réel du I dans `MainActivity.kt` et la géométrie du mode marque dans `CursiveWaveView.kt`.
- [x] Garantir la place des débords de glyphes ; représenter les boucles de la pastille sans modifier son animation.
- [x] Vérifier les tailles de police et écrans étroits ; relire les textes de l’interface.

## Vérification intégrée

- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug` : suite complète et APK construits par Luna après stabilisation des lots.
- [x] Revue des différences finales et des preuves, en distinguant vérifications JVM et validations Android réellement exécutées.
- [x] Documenter le résultat dans le journal du projet et fournir l’APK sans installation implicite sur un téléphone.
