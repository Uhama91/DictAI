# Comparaison du budget CPU de l’attribution

Demande acceptée : conserver Handy et mesurer les voix seules, puis leur exécution concurrente, avant de choisir un réglage ou de revenir à l’ancien moteur intégré.

## Constats et limites

La capture de test6 montre un retard affiché passant de 8 à 125 secondes entre 15 et 168 secondes de vidéo. Des passages anciens reçoivent encore des étiquettes. Cela indique un traitement en retard, sans prouver un arrêt du modèle ni mesurer son seul temps de calcul.

Le code impose un thread CPU au moteur de voix, avec une priorité Android d’arrière-plan et une priorité Java minimale. Handy utilise quatre threads. La capture PCM et ses repères temporels sont déjà communs. Les modèles, le profil de diarisation, les horodatages, le stockage audio et le texte ne doivent pas changer pendant cette comparaison.

## Travail borné (complexe)

1. Luna ajoute un paramètre interne de 1 à 4 threads au port de diarisation Kotlin/JNI, avec validation des deux côtés, tests RED/GREEN et compatibilité des appels existants. Le défaut reste un thread jusqu’à l’arbitrage.
2. Luna ajoute un banc Android reproductible utilisant les vrais ports natifs : voix seules, Handy seul, puis pont concurrent actuel avec priorités et nombre de threads explicités. Séparer vérification des fichiers, chargement et mesure. Les mêmes blocs PCM et cadences sont utilisés. Enregistrer progression temporelle stable, retard, temps d’inférence, premier texte, fin du traitement et mémoire ; aucune transcription privée dans les preuves versionnées.
3. Préparer les fichiers une fois. Lancer un court essai de faisabilité, puis des passes séquentielles de 60 secondes au moins si le dispositif fonctionne : un thread/arrière-plan en référence ; un thread/priorité normale ; deux threads/priorité normale ; quatre seulement si les résultats le justifient. Handy conserve quatre threads. Répéter les comparaisons utiles avec l’ordre inversé, sans Gradle ni compilation en parallèle.
4. Astra arbitre à partir des mesures. Un réglage ne devient candidat que s’il améliore le débit des voix sans dégrader matériellement le texte. L’émulateur ne constitue pas une preuve de performance sur le Poco. Si le chargement empêche toute mesure, documenter la limite et conserver le défaut ; ne pas prétendre avoir accéléré le moteur.
5. Pour tout changement livré : deux revues Astra distinctes du même état figé (fonctionnement/sécurité, puis régressions/maintenabilité), tests adaptés, identité des bibliothèques et de l’APK vérifiée. Publier une nouvelle version de test via Actions seulement si une modification candidate est justifiée, en précisant la validation physique restante.

## Exécution

Réutiliser le worktree `dictai-meeting-mode`, préserver les preuves non suivies et les données locales. Un seul build ou essai natif à la fois. Aucun effacement d’application ni arrêt de processus utilisateur. Les données de la vidéo restent locales. Ne pas changer les modèles, la géométrie de diarisation ou la durée des tampons pour masquer une insuffisance de débit.

## Extension après les mesures CPU : taille des blocs

Le cas des voix seules avec un thread en arrière-plan n’a consommé que 43,76 secondes d’audio sur les 60 secondes, même après 180 secondes de rattrapage. Le cas à deux threads et à priorité normale accumule également du retard. Un dernier contrôle à quatre threads/priorité normale complète cette comparaison ; les essais concurrents longs sans candidat viable sont écartés.

Le runtime épinglé recalcule, à chaque bloc de 1,04 seconde, les probabilités de la mémoire des voix, des trames récentes et du nouveau bloc. Le coût augmente lorsque cette mémoire se remplit. Si quatre threads ne suffisent pas, un second axe d’essai distinct est autorisé : regrouper uniquement le calcul d’identification par blocs de 8 secondes, puis de 4 secondes si le premier résultat le justifie. Il s’agit d’une hypothèse à vérifier, pas d’un gain acquis.

Luna expose un paramètre interne `chunkFrames` : 0 conserve le profil natif du modèle ; 50 et 100 désignent respectivement 4 et 8 secondes sur la grille native de 80 ms. Les autres valeurs sont refusées en Kotlin avant le chargement JNI et en C++. Seule la taille de bloc est remplacée dans la géométrie résolue : mémoire des voix, FIFO, contexte, modèle, capture, textes Handy et horodatages demeurent identiques. Le défaut de production reste 0. Ne pas réinitialiser les identités entre blocs et ne supprimer aucun échantillon audio.

Mesurer d’abord les voix seules sur le même extrait français de 60 secondes, puis le pont concurrent seulement si le débit paraît viable. Vérifier les limites et la transmission du paramètre par TDD ; vérifier ensuite toutes les trames et la fin de flux native sur Android. Avant tout choix de production, contrôler aussi la qualité d’attribution sur le jeu AMI annoté déjà présent. Le résultat recherché est un délai d’identification borné avec conservation du texte immédiat, pas un affichage artificiellement avancé des noms.

## Arbitrage après les premiers essais à huit secondes

Les blocs de huit secondes à deux threads/priorité normale terminent les voix seules et le pont concurrent avec un retard des voix borné sur la minute observée. Le texte Handy final est identique au contrôle et le score AMI ne régresse pas globalement. Cependant, Handy seul présente une forte variabilité entre les deux contrôles : le pic de file passe de 3,68 à 19,1 secondes. Ces mesures ne permettent pas de garantir l’absence de contention ni une latence physique précise. L’essai concurrent à priorité d’arrière-plan produit un retard croissant et n’est pas retenu.

Dernier contrôle de durée autorisé : même profil deux threads/priorité normale/huit secondes, depuis l’offset 15 s jusqu’à la fin disponible de la vidéo récente, sur une limite de bloc de 20 ms. Le préfixe de 60 secondes doit avoir la même empreinte que le contrôle. Observer les progressions au-delà de 60, 90, 120 et 150 secondes ; ne pas créer une boucle audio artificielle et ne pas relancer les autres profils.

Si le traitement reste borné et termine sans perte de texte/audio, ce profil peut devenir une version de test isolée pour le Poco, explicitement expérimentale. Cela ne constitue pas une promotion de production stable ni une validation de la fluidité sur téléphone. L’émulateur reste une preuve du fonctionnement et un signal de débit ; les affirmations de latence sur Poco exigent sa mesure physique. Une version de test candidate utilisera le prochain numéro de prototype, avec contrôles des deux variantes et publication Actions vérifiée.
