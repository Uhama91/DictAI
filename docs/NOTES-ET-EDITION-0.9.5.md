# DictAI 0.9.5 — notes protégées et correction plus accessible

Version Android ARM64 : `0.9.5-wp-gemma-test`, code 34. Cette livraison répond au retour d’Ullie sur les insertions accidentelles d’une note, la précision du curseur et la séparation titre/aperçu. La pastille reste le point d’entrée des messages et des notes.

**[Télécharger directement l’APK 0.9.5](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34404357683/dictai-local-layout-test.apk)** — environ 79 Mo, à installer en mise à jour. [Construction vérifiée](https://github.com/Uhama91/DictAI/actions/runs/34404357683).

## Comportements

| Intention | Appui sur la pastille pendant la dictée | Sortie vers une autre application |
| --- | --- | --- |
| Message démarré depuis le repos | Arrêter puis insérer, parcours direct conservé | Insertion ou copie selon la disponibilité du champ |
| Note ouverte ou créée depuis Mes notes | Mettre en pause ; appuyer à nouveau pour reprendre | Bouton **Insérer…**, lecture du texte final, puis **Insérer le texte** |

Une note s’ouvre en pause, prête pour le clavier ou le micro. **Terminer** la sauvegarde et retourne à Mes notes. Un double appui qui annulait une dictée range maintenant la note au lieu de la supprimer. Le glissement vers le haut réaffiche la note et ne lance aucune insertion. Pour un message dont le panneau a été masqué, ce geste réaffiche d’abord le texte.

La confirmation montre tout le texte à déposer et rappelle que les images passent par l’export. **Rester dans la note**, Retour ou fermeture du dialogue ne déposent rien et ne remplacent pas le presse-papier. Après une insertion confirmée, la note reste ouverte et enregistrée. La confirmation est ponctuelle : reprise, changement de note, fermeture, modification ou destruction la révoquent ; son acceptation vérifie encore l’identifiant et le texte complet. L’application destinataire est celle dont le champ est disponible à cette action, sans promesse de verrouillage à une application nommée.

Le contexte NOTE est conservé dans le brouillon et dans la session de dictée. La protection s’applique aussi au moment de publier le résultat, après la préparation du texte ou une capture en attente. Une ancienne note dont l’identifiant a disparu ne redevient pas un message à cause de cette disparition. La fin du traitement sauvegarde les images et rouvre la note après libération de la session.

## Correction et présentation

- Le premier toucher acquiert la fenêtre de l’overlay ; le clavier attend le focus de la fenêtre et le relâchement du doigt. Le curseur conserve le point choisi avant le déplacement provoqué par le clavier. Les sélections et appuis longs natifs restent disponibles.
- **Modifier** agrandit le panneau et demande le clavier. Le texte passe à 16 sp avec plus d’espace entre les lignes et autour du champ.
- Une correction volontaire suspend le suivi automatique du texte jusqu’à libération de l’éditeur. Le focus attribué automatiquement par Android n’est pas pris pour une correction. Retour, reprise du micro, masquage et commandes de note libèrent ce focus ; aucune fermeture automatique sur un toucher extérieur au panneau n’est ajoutée.
- Mes notes présente le titre en gras, un aperçu distinct, puis la date de modification et le nombre d’images. Les commandes restent des cibles tactiles séparées.
- Les couleurs partagées utilisent un charbon chaud, du crème et du sauge ; cartes et commandes sont plus arrondies. Les couleurs Material et le thème nuit suivent cette palette. Le rendu garde une base sombre, conformément à l’hypothèse annoncée en attendant une éventuelle préférence différente.

Les contrastes calculés sur les couleurs opaques sont de 12,60:1 pour le texte principal sur le panneau, 6,12:1 pour le texte secondaire sur les cartes, 7,57:1 pour l’accent sur les commandes, et 9,77:1 pour le texte sombre sur sauge. Cela vérifie les paires de couleurs, pas l’ensemble de l’accessibilité de l’application.

## Vérification et limites

Publication vérifiée le 9 septembre 2026 : [APK directe 0.9.5](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34404357683/dictai-local-layout-test.apk), [release](https://github.com/Uhama91/DictAI/releases/tag/gemma-test-34404357683), [GitHub Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34404357683). Source `633d83cfdc6422dead8e4ffb395e6d54487aa96d`. Téléchargement public HTTP 200, **78 865 904 octets**, SHA-256 `ea2e8ee4e751a86329ec3ca7601f7ae3ef74862ac1a3a9d103289a859a89e8a8`. Empreinte identique à l’APK locale, à SHA256SUMS, au digest GitHub et au journal CI ; les **1 048 entrées ZIP** sont identiques. Certificat de mise à jour identique à 0.9.4, version code34 et alignement ELF/ZIP 16 Ko vérifiés. **440 tests JVM / 63 suites**, 18 tests du contrôleur APK et contrat natif réussis ; tests Android compilés localement et en CI, sans exécution sur appareil.

La revue indépendante du code a vérifié les destinations, les confirmations et les captures différées. Ses deux remarques ont été corrigées : réouverture prioritaire du panneau masqué et distinction entre focus automatique et édition volontaire.

Les tests ciblent le parcours tap/pause/reprise, les destinations différées, la restauration sans identifiant, les confirmations anciennes/doubles/modifiées, le clavier retardé et le suivi de la transcription. Des tests Android utilisent le vrai EditText pour le curseur, le vrai stockage de brouillon et le ScrollView pour le maintien du point de lecture. Ils sont compilés ; leur exécution nécessite un appareil.

**Aucun appareil Android accessible pendant ce travail.** Il ne s’agit pas d’un audit visuel ou tactile exécuté sur téléphone. Les essais ci-dessous restent à effectuer sur le téléphone/tablette, notamment avec le clavier et la surcouche utilisés par Ullie. Les résultats de compilation et l’APK finale sont consignés dans [VERIFICATION-GEMMA.md](VERIFICATION-GEMMA.md).

## Essai ciblé sur téléphone

1. Laisser un champ actif dans une autre application, ouvrir une note par le glissement à gauche, dicter puis appuyer sur la pastille : pause dans la note, aucun texte déposé. Reprendre, mettre en pause, puis Terminer : retrouver la note complète dans Mes notes.
2. Ouvrir une note, toucher directement un mot au milieu du texte puis le corriger. Vérifier le curseur au premier toucher, l’ouverture du clavier et le maintien de la position pendant l’arrivée de nouvelles paroles. Vérifier aussi Modifier, sélection longue, Retour puis reprise du micro ; le suivi des nouveaux mots doit reprendre après la correction.
3. Dans une note, choisir Insérer… puis Rester dans la note : aucun dépôt. Refaire et confirmer : un seul dépôt du texte affiché, note toujours conservée. Masquer une note puis glisser vers le haut : retrouver le texte sans dialogue d’insertion.
4. Faire une dictée de message depuis le repos : arrêt/insertion directe toujours disponibles. Vérifier aussi le cas où une capture se termine pendant la mise en pause ou le rangement d’une note.

Le moteur, les délais de Gemma et le nettoyage des hésitations de 0.9.4 ne sont pas modifiés. Les validations déjà données par Ullie pour le stockage des captures/photos, leur presse-papier, les exports PDF/HTML et le vocabulaire restent acquises ; ne pas lui redemander une campagne complète sans régression constatée.
