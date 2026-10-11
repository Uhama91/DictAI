# DictAI Mac 0.3.2 — Pause, Annuler et nouvelle dictée

Version 0.3.2 installée dans [Applications](/Users/ulliemaillot/Applications/DictAI.app). [Archive ZIP](/Users/ulliemaillot/Downloads/DictAI-macOS-0.3.2.zip). Les huit notes et le brouillon existants sont conservés ; leurs fichiers sont identiques avant et après installation.

Les trois présentations proposent maintenant les mêmes opérations :

- **Pause** suspend l’écoute sans envoyer le texte. Le brouillon reste modifiable.
- **Reprendre** poursuit le brouillon ouvert, y compris après une correction manuelle.
- **Terminer** ou **Option pendant l’écoute** finalise le texte puis l’insère, ou l’enregistre en mode Note.
- **Annuler** ou **Échap** arrête sans envoyer et ferme le panneau. Le brouillon reste récupérable.

Après fermeture ou annulation, le prochain appui sur Option commence une **nouvelle dictée** dans le champ actuellement actif. L’ancien texte reste dans les notes. Pour continuer explicitement un ancien brouillon, ouvrir le brouillon puis choisir Reprendre.

Le défaut reproduit était une reprise automatique après fermeture : « Ancienne dictée » devenait « Ancienne dictée Nouvelle dictée ». Les tests vérifient maintenant que seule « Nouvelle dictée » figure dans le nouveau brouillon et que le champ de destination est capturé à nouveau.

## Vérification visuelle

Captures de l’application native dans une bibliothèque isolée avec parole simulée. Pause, correction, reprise, agrandissement et annulation ont été actionnés dans l’interface. Une nouvelle dictée après annulation a été vérifiée : le marqueur de l’ancien brouillon reste dans sa note et n’apparaît pas dans le nouveau texte.

![Pause et Annuler dans l’édition rapide](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/pause-0.3.2-editor.png)

![Grande fenêtre en pause avec Reprendre et Annuler](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/pause-0.3.2-window.png)

## Ponctuation

La [ponctuation automatique Apple](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/addspunctuation) était déjà activée. Un défaut complémentaire a été reproduit après une correction manuelle : la virgule ou le point ajouté ensuite par le moteur à la jonction entre le mot corrigé et la suite était ignoré. Ce cas est corrigé ; la ponctuation modifiée ou supprimée par l’utilisateur garde la priorité. Cette amélioration ne garantit pas la qualité de toutes les virgules produites par le moteur vocal.

**66 tests réussis dans 10 suites**, avec échecs des nouveaux tests observés avant les corrections. Compilation release et signature locale vérifiées. Le retour utilisateur confirme le bon fonctionnement des transitions et de la conservation du texte dans la version précédente ; le parcours vocal physique de cette nouvelle version n’a pas encore été retesté.

[Tests](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/pause-0.3.2-tests.log) · [Compilation](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/pause-0.3.2-build.log)

L’accessibilité est renouvelée et confirmée par DictAI après la validation Touch ID. Les autorisations Microphone et Reconnaissance vocale ont été réinitialisées uniquement pour DictAI ; macOS les redemandera au prochain démarrage de la dictée.
