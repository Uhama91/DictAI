# Capture en série et blocs d’images — plan d’implémentation

> Exécution par tâches bornées Luna 6 Max, TDD et preuves ; arbitrage Astra. Aucun commit ni push demandé. Classification : complexe, deux passes de review finales sur le même état figé.

**Objectif :** zoomer et agrandir la caméra, scanner des documents, valider plusieurs captures ensemble, les insérer au curseur et déplacer leur bloc dans le texte, puis exporter avec des images de taille raisonnable.

**Architecture :** conserver le stockage des notes et les marqueurs historiques comme sérialisation interne ; présenter des éléments graphiques atomiques dans l’éditeur. Une session de capture persistante retient les images avant validation. Les exports consomment l’ordre du texte et bornent les dimensions.

**Stack :** Kotlin, Android Camera2, spans natifs et événements tactiles, ML Kit Document Scanner 16.0.0, PDF Android, HTML autonome.

**Arbitrage éditeur :** un caractère objet U+FFFC et un `ReplacementSpan` représentent chaque bloc à l’écran. Les offsets des brouillons restent exprimés dans le texte brut ; la conversion entre offsets affichés et offsets bruts est centralisée. La sérialisation des notes rétablit les marqueurs historiques pour les exports. Le texte transmis à la dictée, au LLM ou à une application ne contient aucun caractère objet. Supprimer un bloc retire aussi sa référence persistante pour éviter sa réapparition à l’export. Chaque lecture/écriture de l’éditeur dans le service doit passer par les conversions appropriées.

## Spécification et contraintes

- Photo : zoom pincement et contrôle accessible, même zoom à la prévisualisation et à la capture ; bouton Agrandir/Réduire ; toutes les commandes restent accessibles en portrait et paysage.
- Série : jusqu’à la capacité restante de la note (10 images), vignettes, retrait et validation explicite ; l’annulation ne modifie pas le texte. Les screenshots utilisent une barre flottante compacte laissant l’application cible manipulable.
- Scanner : choix explicite dans la caméra, recadrage/perspective du SDK, plusieurs pages, import local des JPEG ; échec des services Google sans perte de la note ou de la série en cours.
- Ancrage : mémoriser le curseur avant la perte de focus ; ne pas remplacer le texte sélectionné. Une série est un bloc avec un compteur. Appui long et glissement pour déplacer, action accessible alternative au curseur.
- Édition : les marqueurs ne sont pas présentés comme du texte éditable ; sélection/suppression atomique du bloc, texte environnant conservé ; compatibilité avec les anciennes notes et l’ordre d’export.
- Export : proportions conservées, aucune image étirée, photos compactes, scans suffisamment lisibles, texte qui reprend sous les images et pagination sans chevauchement.
- Préserver les changements déjà présents, les fichiers médias originaux, le comportement audio et les protections des captures. Aucun contenu d’image envoyé à un LLM.

## Contrat de session partagé

Dans `NoteImageStore.kt`, étendre `PendingNoteCapture` avec `batch: Boolean = false`, `images: List<NoteImage> = emptyList()`, `accepted: Boolean = false`, `maxImages: Int = NoteImage.MAX_IMAGES`.
`complete` est vrai sur erreur ou, pour une série, sur acceptation ; conserver le comportement historique hors série. `allImages` donne la liste de série ou l’image historique.

API : `beginBatch(kind: NoteImageKind, resume: Boolean, number: Int, maxImages: Int): PendingNoteCapture`, `acceptBatch(id: String)`, `cancelBatch(id: String)`, `removeBatchImage(id: String, imageId: String)`, `store(id: String, bitmap: Bitmap, kind: NoteImageKind? = null)`.
L’identifiant pending demeure l’identifiant de session ; chaque image de série possède son propre UUID. `store` ajoute une image sans terminer la série. `acceptBatch` exige au moins une image. `cancelBatch` retire seulement les fichiers possédés par la série non validée et publie une annulation. `clearPending` conserve les images validées.
Ajouter `NoteImageKind.SCAN("Document scanné")` sans changer les anciennes valeurs.

## Tâche 1 — caméra, scanner et stockage (Luna capture)

Fichiers : `NoteCameraActivity.kt`, `NoteCameraGeometry.kt`, `NoteImageStore.kt`, nouveaux helpers caméra, `app/build.gradle.kts`, tests associés. Ne pas modifier OverlayService ni NoteImages.

- [x] RED : `beginBatch` puis deux `store` produisent deux UUID et `complete == false` ; `acceptBatch` rend les deux images ; annulation nettoie seulement la série. Zoom borné et géométrie en écran réduit.
- [x] Implémenter le contrat, le zoom Camera2, Agrandir/Réduire, vignettes/suppression/Valider, scanner et gestion du cycle de vie.
- [x] GREEN : 19 tests ciblés sous JDK 21, un test Camera2 sur émulateur ; six rendus natifs portrait, étroit et paysage dans `docs/media-2026-09-24/`.

## Tâche 2 — overlay, screenshots et blocs (Luna éditeur)

Fichiers : `OverlayService.kt`, `OverlayTranscriptEditor.kt`, `NoteImages.kt`, `DictationDraftStore.kt`, `DraftImageContext.kt`, nouveaux helpers de blocs/série screenshot et tests associés.

- [x] RED : insertion entre « Avant » et « Après » au curseur 5 ; deux captures restent ensemble ; déplacement conserve tous les caractères hors marqueurs et l’identité des images ; suppression partielle ne casse pas un bloc.
- [x] Implémenter ancrage avant perte de focus, barre de série screenshot Capturer/Valider/Annuler avec compteur et retrait, livraison groupée et retour au texte après validation.
- [x] Présenter les images comme des spans graphiques atomiques, sélectionner/déplacer par geste et proposer une action de déplacement au curseur ; garder une sérialisation compatible avec les exports.
- [x] GREEN : 61 tests JVM/Robolectric ciblés, dont les parcours réels du service MESSAGE et NOTE ; récupération de brouillon, glissement, annulation et limite ; rendus natifs de l’éditeur et de la barre screenshot inspectés.

## Tâche 3 — exports compacts (Luna export)

Fichiers : `NotePdfExport.kt`, `NoteHtmlExport.kt`, nouveau helper pur de dimensions, tests et fixtures uniquement.

- [x] RED : dimensions bornées sans agrandissement ni changement de ratio, textes avant/après image, ordre modifié, scan portrait et image très haute, HTML échappé.
- [x] Implémenter photos compactes (hauteur PDF autour de 230 pt), scans plus grands mais plafonnés (environ 420 pt), dimensions HTML/print cohérentes, prévention des pages vides.
- [x] GREEN : tests et génération d’un PDF Android de trois pages et d’un HTML synthétique mixte ; chaque page PDF et les variantes HTML ont été rendues et inspectées.

## Vérification et review

- [x] Risques relus : retour du scanner/pause caméra ; résultat tardif après annulation ; dictée qui progresse pendant la capture ; IME/sélection au voisinage d’un bloc ; écrans étroits/paysage. Le parcours SDK Google et les gestes physiques restent une vérification sur téléphone.
- [x] Luna exécute la suite JVM/Robolectric et assembleDebug avec `/Applications/Android Studio.app/Contents/jbr/Contents/Home` : 593 tests, aucun échec ni test ignoré, compilation réussie.
- [x] Astra vérifie conformité/UX et sécurité, puis qualité et régressions sur un état figé. Les deux passes finales portent sur le SHA agrégé `2cdd810a00259a1795963ca6ba9dea04b721f07a7d451e9da2d116396ae5431b` ; aucun constat bloquant.
- [x] Images inspectées par Astra, intégrées dans `docs/2026-09-24-capture-images.md`. APK local `~/Downloads/dictai-0.9.6-captures-scanner.apk`, version 0.9.6-dictai/code 35, 88 159 728 octets ; SHA-256 source/copie `214bd25d4fabba066f38ff9e5daeac011192f21f826162d07b0b77866a4db442`. Vérification téléphone explicitement distincte ; l’AVD utilisé pour les cinq tests Android a été fermé après les essais.
