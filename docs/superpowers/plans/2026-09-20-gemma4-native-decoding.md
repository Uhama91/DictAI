# Profil de décodage natif du pilote Gemma fine-tuné

## Objectif et périmètre

Le modèle a été évalué en greedy sans pénalité de répétition. Le JNI actuel impose une pénalité 1,05 et un échantillonnage top-k/température à tous les modèles. Ajouter un profil explicite qui permet de reproduire le décodage d'évaluation tout en conservant le comportement par défaut des appels existants. Lot complexe, deux revues du principal sur les empreintes finales.

## Contrat

- Ajouter un type Kotlin fermé pour le profil, avec mode historique par défaut et mode Gemma fine-tuné greedy. Le passage à travers LocalFormatNativeApi et les deux bindings JNI est explicite ; ne pas déduire le profil d'un nom de fichier ou du contenu du prompt.
- Le JNI refuse un profil inconnu. Le mode historique conserve ses pénalités, température, seed et grammaire. Le mode Gemma emploie llama_sampler_init_greedy sans pénalité ni échantillonnage. Ne pas ajouter de grammaire au profil Gemma ; refuser une combinaison incohérente plutôt que prétendre reproduire le test.
- Conserver les arrêts EOG du vocabulaire, les limites de contexte/sortie, les retours null à la troncature, les chunks UTF-8 et l'annulation. Le profil ne change ni l'enveloppe ni le tokenizer ; les IDs EOG réels seront vérifiés sur le GGUF avant branchement.
- Aucun branchement à LocalFormatEngine, changement de modèle, téléchargement, UI ou Actions dans ce lot. Le nouveau paramètre a une valeur par défaut pour les appels Kotlin existants, mais la signature JNI et ses deux enregistrements doivent être cohérents.

## Vérification

TDD ciblé : profil historique par défaut, transmission explicite du profil Gemma, conservation des garanties d'annulation/chunks, combinaison grammaire interdite et validation des paramètres. Compiler les deux variantes natives à partir du commit épinglé, dans des dossiers de build distincts, sans remplacer les bibliothèques de l'application avant revue. Exécuter les tests JVM pertinents. Ne pas confondre compilation et test d'inférence : ce dernier attendra le modèle réel.

La conversion E2B et sa surveillance ont priorité : préparer code/tests pendant son exécution, mais attendre sa fin avant compilation native ou suite Gradle consommatrice. Aucune nouvelle infrastructure de test ou refonte du moteur n'est requise.
