# Pilote Android avec les véritables poids V6

Plan d'intégration complexe, à exécuter par Luna après acceptation du moteur natif et du téléchargement multipart. L'utilisateur a autorisé le nouveau modèle et son APK GitHub Actions. Le principal doit examiner les sorties natives avant publication ; aucun score mobile ne peut être déduit d'un test Mac.

## Artefact et distribution

La sélection de l'artefact attend l'essai Q6_K/QO F16. Le précédent Q4_K_M/QO F16 a été vérifié techniquement mais sa qualité régresse à 38/60 utilisables et 14 critiques ; il n'est pas sélectionné pour publication. Ne pas inscrire un hash provisoire ou un ancien modèle dans le descripteur pilote. La facade et le packaging peuvent être préparés indépendamment ; tant que le store ne désigne pas le GGUF sélectionné, le chemin CPU refuse les fichiers LiteRT.

Après sélection explicite, découper le fichier de façon binaire et séquentielle en parties d'au plus 1 800 000 000 octets ; chaque asset doit être inférieur à la limite GitHub de 2 Gio. Calculer le SHA de chaque partie et du flux concaténé, qui doit égaler celui du modèle. Ne pas inventer ces hashes dans le code. Joindre un manifeste, la licence source et une notice qui conserve Google, la conversion MLX épinglée, l'adaptateur V6, la recette de quantification, et les limites de validation. Référence de provenance : `asr-postclean-fr/reports/gemma4_v7/model_distribution_provenance.md`.

Publier ces parties sous un tag dédié identifiant le checkpoint et la quantification retenus, après contrôle de son absence. Ne pas écraser un asset existant. Vérifier la taille et le digest serveur de chaque asset lorsque GitHub les expose ; un nom ou un upload démarré n'est pas une livraison. Le descripteur Android pilote utilise ces URLs et hashes exacts. Le fichier installé possède un nouveau nom ; aucun poids LiteRT de base ne peut satisfaire ce descripteur et aucun poids n'est embarqué dans l'APK.

## Variante explicite

Ajouter une propriété `gemma4FineTunedPilot` et `BuildConfig.GEMMA4_FINE_TUNED_PILOT`, qui implique la disponibilité des formats locaux. La variante GPU existante reste distincte et son comportement est conservé. Le pilote sélectionne le moteur CPU partagé accepté et l'artefact multipart. Les autres variantes conservent leur descripteur et leur moteur.

Le pilote doit compiler et embarquer les DEUX bibliothèques JNI récentes au pin llama.cpp existant : le profil greedy a modifié une signature. Il ne doit pas utiliser les anciens binaires du dépôt. Corriger ensemble la condition de compilation dans Actions, l'exclusion de packaging Gradle et les contrôles `prepare_ci_apk.py`. Garder l'interdiction de modèles embarqués et les contrôles de licence, signature, ABI et alignement 16 Ko.

Dans le pilote fine-tuné, désactiver le raccourci `simpleEmailLayout` des requêtes de l'overlay. `SimpleEmailLayout` met actuellement en forme certains mails courts sans réécriture du corps : leurs hésitations ne passeraient donc jamais par le nouveau modèle. Garder les accusés de réception déterministes et le comportement des autres variantes. Vérifier par test qu'un mail court avec salut/corps/clôture, contenant une reprise, n'est pas traité comme un simple retour direct dans ce pilote.

Les libellés de téléchargement donnent la bonne taille et identifient le fine-tuning V6 expérimental. Le benchmark et le diagnostic décrivent CPU/greedy, pas GPU/MTP/LiteRT. Éviter les informations d'implémentation dans le parcours de dictée ; les détails restent dans le diagnostic. Les notices distinguent les deux variantes.

Vérifier en particulier `MainActivity.gemmaInstallLabel`, les constantes de titre/taille du store utilisées par `GemmaModelDownloadDialog`, le modèle nommé par `LocalFormatEngine.MODEL_FILE` et la liste fermée de runtimes dans `PostprocessingDiagnostic`. Ils doivent tous décrire le même artefact et le même moteur sélectionnés.

`LocalFormatBenchmarkDialog` contient aussi un message d'échec « État GPU » et « Aucun repli CPU ». Dans le pilote CPU, le message doit correspondre au moteur choisi, sans présenter le calcul CPU comme un repli. Les sources et licences de la notice actuelle décrivent seulement le package LiteRT officiel ; ajouter la provenance des poids V6 et de llama.cpp pour le pilote, en conservant les notices nécessaires aux bibliothèques effectivement embarquées.

## Actions et preuve

Ajouter une sélection explicite du pilote au workflow, un nom d'artefact/APK dédié `dictai-gemma4-v6-test`, et des notes de prerelease décrivant les véritables changements et les limites. Les libellés anciens 0.9.5 doivent être remplacés pour ce pilote. Garder JDK 21, SDK/NDK épinglés et le correctif setup-android `packages: platform-tools`.

Tester les branches normal/GPU/pilote des contrôles de packaging avec de minuscules APK de test et vérifier les constantes de build. Exécuter les tests Android appropriés et assembleDebug sous JDK 21. Après les deux revues du principal sur les hashes finaux, commit explicite des seuls fichiers nécessaires : ne pas inclure les dossiers reports de compilation, binaires de staging ou données du modèle. Push sans force, exécution GitHub Actions, attente du résultat, contrôle du SHA de l'APK et de son contenu réel. Fournir un lien téléchargeable vers le nouvel APK seulement après la réussite complète du run et la présence vérifiée de l'artefact.

Le traitement progressif éditable demeure à intégrer et à tester selon son contrat propre. Une release expérimentale ne vaut pas validation de latence, mémoire ou interaction sur Poco F7/Pad 7 ; aucun appareil n'est connecté au moment de ce plan.

## Préparation réversible du candidat Q6 pendant l'évaluation ARM

Le descripteur du candidat Q6 peut maintenant être préparé et testé dans le worktree pendant l'évaluation complète. Cela ne sélectionne pas les poids et ne permet pas leur publication ou celle de l'APK. Le fichier réel et les trois parties ont déjà été vérifiés, y compris côté GitHub dans la release privée de travail 392486209 ; les hashes doivent provenir de son manifeste existant. Utiliser le tag `gemma4-v6-1956-q6-evaluation-20260920`, qui sera rendu public seulement si le candidat est retenu. Si Q6 est rejeté, ce raccordement reste non livré et doit être adapté à la décision explicite.

Lot complexe borné : `GemmaModelStore.kt`, `LocalFormatEngineFacade.kt`, `PostprocessingDiagnostic.kt` et tests associés. Le store choisit le descripteur Q6 uniquement pour `GEMMA4_FINE_TUNED_PILOT` ; une fonction testable permet de vérifier les deux choix. La taille, le SHA global, le nom du fichier et les trois URLs/SHA/tailles correspondent au manifeste. Le GPU et le build normal conservent exactement le descripteur LiteRT existant. Les diagnostics testant explicitement `pilot=false` doivent utiliser le nom GPU même dans une compilation pilote. Le titre utilisateur indique « Gemma 4 E2B V6 expérimental », sans promesse de qualité ni validation FR/EN ajoutée. Aucun nouveau moteur, changement de téléchargement ou réentraînement dans ce lot.

Luna atteste, applique le TDD avec petits fixtures, exécute les tests pertinents sous JDK21 (un Gradle à la fois, deux workers et heap2G), puis gèle les fichiers pour deux revues du principal. Aucun modèle n'est chargé localement ; aucun commit, push, publication, nouveau téléchargement de poids ou build natif n'est autorisé par cette préparation.

Pour la vérification locale préparatoire, réutiliser les deux bibliothèques de staging du lot de décodage, déjà compilées et revues avec le C++ inchangé. Lot moyen : vérifier leurs SHA, copier hors staging, retirer les symboles avec le NDK macOS, contrôler ELF AArch64, SONAME, unique export JNI_OnLoad, dépendances et alignement LOAD 16 Ko, puis installer les copies dans le bundle ignoré par Git. Ne pas altérer les originaux de preuve ni écraser sans préserver un binaire existant inattendu. Aucune nouvelle compilation native ou inférence n'est nécessaire ; la CI reconstruira les deux bibliothèques depuis le C++ publié. La fabrication locale d'APK attend le descripteur relu et le créneau Gradle libéré.
