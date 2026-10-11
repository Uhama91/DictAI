# DictAI Mac 0.3.5 — Terminer vraiment la dictée

Après une insertion réussie, la session revient au repos : l’overlay se ferme, le brouillon actif est vidé et le texte est conservé dans les notes. Le prochain appui isolé sur Option commence une nouvelle dictée. Une demande tardive d’ouverture de l’éditeur ne rouvre pas la transcription terminée.

La pause et l’annulation gardent le brouillon récupérable. Un véritable échec d’insertion conserve l’éditeur et son message d’erreur. Si l’archivage échoue après une insertion réussie, le brouillon reste disponible et une erreur apparaît dans la bibliothèque ; le texte déjà envoyé n’est pas réinséré.

Le contrôle d’insertion accepte maintenant un espace final retiré ou transformé en espace insécable, ainsi que les fins de ligne équivalentes. Il vérifie toujours les mots, leur ordre et les paragraphes. Ces variantes reproduisaient un faux échec dans les tests : le champ contenait le texte, mais DictAI ouvrait la récupération. La cause exacte de la réouverture signalée dans le champ utilisé n’a pas été observée directement.

## Vérifications

- 101 tests réussis : fin de session dans les trois présentations, nouvelle dictée sans ancien texte, résultat vocal tardif ignoré, conservation en cas d’erreur et absence de double insertion.
- Les tests de régression ont échoué avant le correctif, puis réussi après.
- Le rythme Option simple/double/triple reste celui de la version 0.3.4.

[Tests](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/completion-0.3.5-tests.log) · [Échec reproduit avant correction](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/completion-0.3.5-red.log)

## Installation et aperçu

La version 0.3.5 (build 8) est installée dans `~/Applications/DictAI.app`. Les trois autorisations macOS sont renouvelées et confirmées dans l’application ; le démarrage du moteur vocal a été vérifié. Signature vérifiée et fichiers de données inchangés à l’installation : 13 notes et le brouillon existant sont conservés. La sauvegarde précédente est dans `~/Downloads/DictAI-macOS-before-0.3.5.zip`.

L’essai natif isolé confirme la fermeture après Terminer depuis l’édition rapide et la grande fenêtre, ainsi que l’archivage et une nouvelle session indépendante. Il utilise une dictée simulée en mode Note ; le test vocal avec insertion dans une application externe reste à effectuer.

![Session terminée : note conservée et brouillon actif vide, dans la démonstration isolée](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/completion-0.3.5-closed.png)
