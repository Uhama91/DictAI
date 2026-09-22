# Corriger le diagnostic après une prise de notes

## Constat et périmètre

L'utilisateur confirme tester sur Xiaomi Pad 7 avec le dernier APK Actions. Le rapport copié indique pourtant 0.9.5 et le 11 septembre. La version du rapport représente l'application au moment de son enregistrement ; elle ne démontre pas la version actuellement installée.

Le code du pilote 0.9.9 présente deux causes vérifiées : la publication d'une note n'enregistre aucun diagnostic, et le dialogue préfère par défaut le dernier format demandé à la dernière dictée. Les préférences conservent les anciens rapports après une mise à jour.

Cette correction porte sur la mesure et son affichage. Les poids Gemma 4 V6, réglages, consignes, délais, transcription, édition et sauvegarde des notes sont conservés. Aucun entraînement, export ou calcul de modèle n'est prévu.

## Résultat attendu

1. Chaque publication effective d'une dictée, vers un message ou une note, enregistre son diagnostic après le succès de l'opération. Une note est désignée « note enregistrée », sans simuler une insertion ou une copie. Une annulation ne produit pas un nouveau succès.
2. Le dialogue ouvre la dernière dictée par défaut. Le dernier format distinct reste consultable explicitement. Il distingue la version installée du contexte historique du rapport, y compris dans le texte copié, et ne réécrit jamais un ancien rapport comme s'il venait d'être mesuré.
3. Le pilote passe à 0.9.10/code 39 et conserve son certificat. Le fichier Actions public est vérifié avant remise. L'installation et les temps sur Pad 7/Poco restent des validations matérielles à effectuer.

## Exécution et vérification

- Luna A : publication commune et diagnostic, tests de reproduction puis correction. Risque complexe à la frontière de publication ; deux revues du principal sur un état immuable.
- Luna B : sélection du rapport et libellés de version, tests et rendu du dialogue. Revue fonctionnelle et visuelle du principal.
- Luna C : version/notes de publication et contrats Python ; intégration puis publication uniquement après autorisation du principal.
- Un seul Gradle à la fois, JDK 21, deux workers, tas de 2 Go. Tests ciblés RED/GREEN, puis suite intégrée et construction des APK. Ne pas inclure les rapports locaux, poids ou JNI ignorés dans les commits.

Les critères de régression couvrent une ancienne mesure conservée avant une nouvelle note, la confidentialité du texte, le parcours message, l'annulation, la dernière dictée sans format après un ancien format, le contexte de version copié et l'état sans rapport. Les mesures du rapport du 11 septembre ne sont pas attribuées au test actuel.

## Validation locale du 22 septembre

Les deux chemins de notes reproduisent le défaut avec l'ancien service (deux échecs d'assertion sur quatre cas), puis les quatre cas passent après restauration du correctif. Les sept tests du dialogue passent et son rendu a été inspecté. La vérification intégrée JDK 21 compte 701 tests JVM, sans échec, erreur ni test ignoré ; les APK debug et AndroidTest sont construits. Les 32 contrats Python du packaging passent. Les deux revues du principal du code de publication et la revue du dialogue sont favorables sur les empreintes finales.

L'APK local porte le code 39 et la version 0.9.10-dictai-latency-test. La publication GitHub Actions et le contrôle du fichier public restent à effectuer. Aucune inférence ou installation sur appareil n'a été réalisée pour cette correction.
