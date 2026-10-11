# DictAI Mac 0.3.6 — Fermeture après insertion

Le brouillon de l’application installée affichait « Le champ n’a pas confirmé l’insertion » pour une dictée vers ChatGPT, alors que le texte avait été reçu selon le signalement de l’utilisateur. DictAI rouvrait donc l’éditeur de récupération. Il s’agissait de la vérification après collage, pas d’un second déclenchement du raccourci Option.

La version 0.3.6 distingue le texte relu et confirmé du collage transmis dont le contenu ne peut pas être confirmé par l’accessibilité. Une fois le collage consommé, cette dernière situation termine la session et archive le texte dans les notes. Elle ne rouvre plus l’éditeur et ne propose plus une nouvelle insertion susceptible de créer un doublon. Une perte de cible ou un collage non consommé conserve le brouillon et son message d’erreur. Une insertion directe partielle bloque toujours un second collage.

Le raccourci reste identique dans les trois présentations : un appui sur Option termine et insère, deux appuis mettent la dictée en pause ou la reprennent, trois appuis annulent sans envoyer. En mode Note, terminer enregistre la note.

## Vocabulaire

Le Mac était en mode « Sur ce Mac », sans compte connecté, avec deux corrections enregistrées. Ces données ne sont donc pas synchronisées avec Android actuellement. Le protocole de synchronisation inclut les règles de vocabulaire ; les appareils doivent utiliser le même compte. Sur Android, la réception en arrière-plan dépend notamment d’un travail périodique demandé toutes les quinze minutes ; macOS suit un calendrier différent. Une synchronisation manuelle permet de demander un échange immédiatement.

Aucun téléphone ni aucune tablette n’était connecté à ADB. Les listes et comptes réels de ces appareils n’ont donc pas été comparés. Sur Mac, les termes simples servent d’indications au moteur vocal ; les règles « entendu => voulu » effectuent des remplacements. Une retouche humaine protège le brouillon contre une réécriture automatique. Une liste synchronisée ne garantit pas une reconnaissance vocale identique entre moteurs.

## Vérifications

- Régression reproduite avant correction : texte transmis, éditeur rouvert, session non terminée. Après correction, les trois présentations se ferment et le texte est archivé une seule fois.
- 107 tests macOS réussis. Les tests de fenêtres et de temporisateurs partagent AppKit ; le lanceur impose désormais une exécution successive après des interférences constatées en parallèle.
- 50 tests Android réussis sur le vocabulaire et les documents de synchronisation. Aucun code Android modifié.
- Build de production et signature locale vérifiés. Version 0.3.6, build 9, installée dans `~/Applications/DictAI.app` ; 31 notes, deux corrections et le brouillon conservés.
- Les trois autorisations macOS ont été renouvelées avec l’accord de l’utilisateur et sont confirmées dans DictAI. Le moteur vocal a démarré et reconnu de la parole ; l’essai a été arrêté sans insertion. Le parcours complet vers ChatGPT reste à confirmer : l’outil de contrôle interdit l’accès à cette application. Le test natif utilise une capture et une destination simulées, avec les vraies fenêtres et le vrai parcours de fin de session.

[Journal des tests](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/insertion-0.3.6-tests.log) · [Vérification de l’installation](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/insertion-0.3.6-install.json)

## Aperçus natifs du test isolé

![Pastille avant la fin de dictée](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/insertion-0.3.6-compact.png)

![Édition rapide après correction manuelle](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/insertion-0.3.6-editor.png)

![Grande fenêtre après correction manuelle](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/insertion-0.3.6-window.png)

![Après transmission : une note archivée et aucun brouillon actif dans la bibliothèque isolée](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/insertion-0.3.6-archived.png)
