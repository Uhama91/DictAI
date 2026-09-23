# APK expérimental Gemma 3 : identité explicite et attente courte

Lot complexe, deux revues Astra sur artefact inchangé avant publication. Objectif : livrer un essai réellement différent du Gemma 4 CPU ayant atteint 20 secondes, avec une identité de modèle vérifiable. L'utilisateur autorise déjà l'APK GitHub Actions. Aucun résultat de rapidité mobile n'est présumé.

## Choix de poids et portée

Préparer le branchement avec la référence Gemma 3 270M V3 déjà publiée et exportée. URL vérifiée le 23 septembre via API GitHub : `https://github.com/Uhama91/DictAI/releases/download/gemma270-v3-model/gemma3-270m-postclean-v3-q8_0.gguf`, taille 291545280, SHA-256 `6c4b7b6654c9638287c31e50fd0bf849f33a2ff2dd93bee685bdd9632f20ecf5`. Ne pas télécharger un autre modèle sous cette identité. L'essai d'entraînement MLP reste indépendant : si retenu et export vérifié, le descripteur sera remplacé dans un lot explicite ; sinon l'APK pourra servir d'essai de latence de la référence V3, sans prétendre contenir des poids nouvellement améliorés. La publication exige une décision finale écrite du principal après résultat MLP.

Cette variante qualifie seulement le format prédéfini Texte corrigé français en phase finale. Les listes, mails, formats personnalisés et corrections progressives du modèle ne sont pas qualifiés. L'ASR en direct, le déplacement/redimensionnement de l'overlay et l'édition humaine restent disponibles. Les formats non pris en charge localement doivent conserver le texte, sans bascule cloud implicite et sans lancement natif. Les choix cloud restent disponibles. Le branchement G3 remet explicitement `instructions` à vide et `simpleEmailLayout` à faux pour ce seul format prédéfini, dont la consigne figée vient du helper ; la politique `validation` reste celle du garde aval.

## Branchement Android

Ajouter `-Pgemma3RepairPilot=true`, exclusif du pilote G4, impliquant le prototype local pour les contrôles d'installation et conservant les deux JNI CPU. VersionCode 40, versionName `0.9.11-dictai-gemma3-test`, applicationId et clé debug inchangés. Ne pas augmenter les versions des autres variantes.

Séparer explicitement le profil G3 du CPU G4 et du GPU LiteRT dans la façade et le magasin. Brancher le profil `LocalFormatCpuProfile` préparé en parallèle ; aucun changement de ses sources dans ce lot. Même intégrité du téléchargement et isolation par SHA, sans supprimer le G4 déjà installé. Afficher « Gemma 3 270M V3 expérimental », le fichier exact, CPU llama.cpp et la limite de 3 secondes dans les diagnostics G3. Ne pas utiliser un booléen G4 pour prétendre à cette identité. Garder les signatures booléennes historiques des helpers testés si nécessaire, avec un paramètre G3 explicitement transmis ; éviter une refonte générale.

Pour les messages et les notes, utiliser le coordinateur existant avec un paramètre `allowPartialRequests` vrai par défaut et faux pour G3. En mode faux, `update` suit toujours la source, les révisions ASR et les éditions humaines, mais ne crée aucun travail PARTIAL ; `finish` reste une seule demande FINAL sur le reliquat. Son attente G3 est de 3000 ms, file incluse, et l'annulation existante refuse toute réponse tardive. Ne pas effacer le contexte précédent pour rendre artificiellement une requête admissible : le profil G3 refusera un contexte non vide. Le préfixe corrigé par l'utilisateur conserve sa protection. Ne jamais démarrer G3 sur les modes LIST/EMAIL ni sur une langue non prise en charge.

La note doit traverser la même finalisation bornée que le message avant sauvegarde. Vérifier le chemin de l'overlay qui passe actuellement par le coordinateur avant la branche `archiveAsNote`, pour éviter le chemin historique de session qui sauvegarde trop tôt. Les diagnostics de notes et messages doivent rapporter l'appel effectif, le refus ou l'expiration et l'attente configurée, sans texte enregistré. N'afficher « LLM appliqué » que pour une sortie réellement acceptée.

## Interface de test

Sur cette seule variante, expliquer en une phrase lisible dans Mise en forme : « Essai Gemma 3 : texte corrigé français, attente limitée à 3 s. Listes et mails non pris en charge par ce modèle. » Adapter les libellés d'installation et du benchmark à G3. Masquer le benchmark de mails longs et les tests list/mail inadaptés. Réutiliser les six textes de latence (trois passages) si leurs requêtes respectent le contrat G3 ; rapporter chargement, appel et repli séparément. Le benchmark doit avoir la même limite de 3 secondes et le même prompt/profil que l'overlay.

## Vérification et périmètre

Luna 6 Max possède façade, magasin/descripteur, Gradle, coordinateur, raccordement overlay, diagnostics, page de réglages, benchmark et tests correspondants. Elle n'est pas seule : les fichiers du profil CPU et de sa garde sont gérés séparément. Aucun workflow CI, script de packaging ou notice de distribution dans ce lot, aucun commit/push.

TDD ciblé : sélection exclusive des trois routes, descripteur exact, paramètres G3, aucun PARTIAL en mode final-only, finalisation des notes/messages, réponse tardive ignorée, édition humaine/révision ASR préservées, langue/mode incompatibles sans JNI, diagnostic identité/borne corrects. Tests historiques G4/GPU inchangés. Attendre la fin du lot de profil pour la seule exécution Gradle coordonnée sous JDK21 ; utiliser d'abord les tests purs disponibles. Avant livraison, exécuter tests Android JVM appropriés puis assembleDebug et assembleDebugAndroidTest avec le flag G3. Produire un rendu de la page de réglages avec le dispositif Robolectric existant et le faire inspecter par Astra. Les vérifications d'APK Actions, signature, version et SHA appartiennent au lot de publication suivant.
