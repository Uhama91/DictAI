# Double revue finale — Réunion test4

Verdict Astra : livraison **expérimentale test4** autorisée. La transcription Handy et l'identification différée des voix sont raccordées et testées. La diarisation soutenue en temps réel n'est pas validée : l'essai de 63,9 s échoue sur l'AVD. Aucun résultat de cette revue ne garantit la fluidité ou la précision sur Poco F7.

## État immuable examiné

Les deux passes portent sur les mêmes 61 fichiers applicatifs, natifs, tests et scripts recensés dans [source-manifest.sha256](source-manifest.sha256). Empreinte du manifeste : `f4c2f57d74e2bfed144747ae4c703a96519f2c6ab0d6dc042265b73e418c2dd3`. Le contrôle des 61 empreintes réussit après les lectures finales et l'audit visuel. Les documents de revue et les journaux sont ajoutés ensuite sans modifier cet état.

| Élément | SHA-256 |
| --- | --- |
| APK principal local, code 38, test4 | `0ad1d9dd283a2d33edf6e42b19db6d1cfcc0e3a332c234bd1dec8ab1d74d0732` |
| APK AndroidTest final | `d6db33d57fccbc6313eb0b045398fef5d0df6f4cab5621f07e8a4a55320951c6` |
| Fixture des captures finales | `910c2aea3d39f9be0de4bfc6bd015cfbd8e0f30361c0519da05643e7bc207a94` |
| JNI diarisation, un thread | `83a19a5794a020bd56e60212136261141e776f2cc24e22d0151f73dec2c0a546` |
| JNI Handy étendu | `68b2733aaa6638ffe03254e5f6719eefc78e49e9272aeeb3fc5961f2ef446b5b` |

Le runtime Handy livré reste inchangé (`e7878a83c00e70a11ac8bc05d6ba43685fb0edea865dcdd81f52e79442a5c4e4`). La dernière modification après compilation de l'application concerne uniquement la fixture AndroidTest : taille du catalogue dérivée, stabilisation de l'orientation et captures portrait. Les tests gestes et runtime utilisent le même APK principal ; les captures finales utilisent le nouvel APK AndroidTest.

## Passe 1 — conformité fonctionnelle

Revue directe de `HandyMeetingNativeBridge`, `MeetingEngine`, `MeetingHandyTranscriptAssembler`, des façades JNI, du catalogue et magasin de modèles, puis du raccord panneau/service et des tests associés.

- Le texte provient de Handy avec les paramètres de dictée. Le worker indépendant des voix ne conditionne ni le premier texte ni sa finalisation. Le défaut des deux constructeurs publics du moteur est le coordinateur hybride.
- Les octets UTF-8 et le texte Handy normalisé restent la référence. Les mots ne sont attribués que si toute leur plage est couverte par une voix unique et stable. Une incertitude ou un chevauchement reste sans attribution ; un alignement manquant conserve le texte sans inventer de temps.
- Les révisions ont des identifiants stables et respectent les retouches et attributions humaines. La projection groupe les continuations proches sans fusionner leurs identités éditables. Les barrières sans temps, les images et le curseur sont préservés.
- Le catalogue v2 épingle Handy Q8 et la diarisation. Préparer réutilise les fichiers accessibles dans l'espace privé de l'application après contrôle de taille et SHA, puis publication atomique ; l'ancien catalogue n'est pas considéré prêt. Une autre application Android ne partage pas automatiquement cet espace.
- L'en-tête indique le retard mesurable et la panne de voix. Le rafraîchissement reprend après réouverture et s'arrête avec la session. Le petit panneau conserve le nom et le temps en haut du texte, y compris avec police agrandie.
- Les essais réels courts démontrent la publication avant finalisation, le texte final conservé et neuf révisions de voix sans modification des paroles. Les suites JVM finales exécutent 1 099 tests dans chacune des deux variantes ; 16 tests d'outillage et 11 du paquet passent.

Résultat : conforme au périmètre fonctionnel expérimental. Les 32 captures finales ont été inspectées par Astra : [galerie et limites des preuves](../ui-renders/handy-test4/README.md).

## Passe 2 — concurrence, dégradation et limites

Revue du même manifeste, en ciblant les frontières d'annulation et de fermeture, les files audio, les révisions tardives, les replis d'alignement et les résultats Android bruts.

- Annuler vide la file secondaire et supprime les publications tardives sans attendre le calcul natif sur le fil d'interface. La fermeture attend effectivement les workers et callbacks ; une nouvelle session n'obtient pas prématurément leurs ressources. Un appel natif déjà engagé n'est pas préemptible.
- La file PCM de voix est limitée à 120 s. Son débordement ou son échec de chargement rend les voix indisponibles et laisse Handy continuer. Les tests déterministes couvrent les courses, l'interruption pendant fermeture et les publications après annulation. Les essais de gestes vérifient aussi le retour des dossiers et la conservation des retouches.
- La fenêtre révisable est bornée à 120 s et 256 fragments ; sa purge n'efface pas du texte qui n'aurait jamais été publié. L'historique intégral du document et certains caches de texte continuent néanmoins de croître avec la durée : il n'y a pas de garantie de RAM constante pour une réunion illimitée.
- Les compteurs incluent le bloc ASR en calcul, pas seulement la file. Les horodatages de tokens sont des estimations du décodeur ; une trame de diarisation à 10 ms ne signifie pas un alignement phonétique exact à 10 ms.
- La configuration quatre threads par modèle a été rejetée après un fort ralentissement. Avec un thread de voix, le court conserve le texte mais présente encore un surcoût variable de 4 à 25 % pour Handy et une finalisation des voix tardive.
- Le XML du test prolongé contient un échec réel : les voix ne terminent pas dans les 120 s accordées après Handy. À 60 s d'audio envoyé, 46,4 s restent à identifier. Le résultat final combiné n'est donc pas déclaré validé. Cette limite interdit de présenter test4 comme une solution de diarisation soutenue en temps réel.
- Le contrôle runtime `production-jni+AudioRecord` valide ouverture, pause, reprise et libération, mais reconnaît zéro passage sur le micro de l'émulateur. Il n'est pas utilisé comme preuve linguistique. Les scènes UI sont simulées et ne sont pas des résultats de reconnaissance.
- L'identité isolée test4, les 13 bibliothèques sans modèles embarqués, l'alignement ELF/ZIP à 16 Kio, la signature et les empreintes natives sont contrôlés. La vérification de l'APK reconstruit par Actions reste une étape de livraison séparée.

Résultat : aucun obstacle supplémentaire identifié pour un essai expérimental clairement limité. **Pas d'approbation d'une version stable ni d'une promesse de délai inférieur à une seconde.** La prochaine preuve nécessaire concerne la latence, la stabilité des personnes et la tenue prolongée sur le Poco physique.

## Preuves principales

- [Rapport complet et mesures](../2026-09-27-handy-conversation-validation.md).
- [Comptes et empreintes](test-counts.json), [contrôles du paquet](apk-check-final-post-ui.json).
- [Comparaison courte native](native/ports-short-results.xml), [moteur intégré](native/engine-integrated-results.xml), [échec prolongé conservé](native/ports-long-corrected-results.xml).
- [Galerie Android auditée](../ui-renders/handy-test4/README.md).

## Complément après le premier passage CI

Le run [36292940843](https://github.com/Uhama91/DictAI/actions/runs/36292940843) a échoué sur une attente prématurée d'un test de remplacement de document. L'état initial ci-dessus reste archivé. Le seul changement applicatif au sens du manifeste est un test JVM, `OverlayServiceMeetingRobolectricTest.kt`, dont l'empreinte corrigée est `c4da1274da4f8eda0cf2261938ccff8d95e2564587978f3aedadbddb224c10aa`. Les 60 autres fichiers du manifeste initial conservent leurs empreintes ; aucune source de production, bibliothèque ou fixture Android n'a changé.

**Passe de conformité sur le correctif figé.** Astra a lu le test et l'enchaînement réel `startNewMeetingAfterSaving` : publication de la note, fermeture, restitution de propriété, effacement de l'ancien brouillon puis acquisition du nouveau. Attendre la seule publication ne prouvait pas l'achèvement de ce parcours. Les quatre lignes ajoutées attendent maintenant un contrôleur non nul et distinct avant l'assertion finale. La vérification de la retouche sauvegardée est conservée.

**Passe adversariale sur le même correctif.** La condition ne peut pas réussir sur le `null` transitoire entre les contrôleurs. Elle utilise le même mécanisme borné de traitement de la boucle principale, sans pause arbitraire supplémentaire, sans augmentation du délai et sans suppression d'assertion. Le test rouge de CI reste la preuve du défaut. Les 29 cas de la classe passent ensuite en variante normale et en prototype. Les précédentes preuves natives et visuelles restent valables pour le code de production inchangé ; elles ne sont pas réexécutées pour ce changement de test.

Verdict : correctif de test accepté. Le [manifeste CI corrigé](source-manifest-ci-fix.sha256) et les preuves sous `ci-fix/` distinguent ce nouvel état des 1 099 résultats locaux précédents. La publication reste conditionnée au succès de la nouvelle exécution GitHub et à la vérification de son APK. La limite du test prolongé des voix demeure inchangée.
