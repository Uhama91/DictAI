# Gemma 4 fine-tuné : faisabilité LiteRT-LM

État du 21 septembre 2026. Recherche documentaire et inspection des fichiers seulement ; aucune conversion, installation de runtime ou inférence supplémentaire exécutée.

La voie GPU reste techniquement candidate. Google documente la conversion de safetensors personnalisés, notamment après fine-tuning, vers LiteRT-LM pour Gemma 4 E2B. Il faut exporter nos poids et vérifier leurs sorties ; charger le modèle de base officiel ne constituerait pas une comparaison de notre modèle entraîné. [Documentation Gemma 4](https://developers.google.com/edge/litert-lm/models/gemma-4).

Le convertisseur accepte un répertoire Hugging Face local. Sa recette de quantification par défaut est `dynamic_wi8_afp32` ; ne pas présumer qu'une exportation utilisera une recette INT4. Le gabarit de conversation Gemma 4 doit être vérifié explicitement. [Conversion GenAI](https://developers.google.com/edge/litert/conversion/pytorch/genai).

Les exports locaux BF16 et F32 du checkpoint 1956 existent dans `/Users/ulliemaillot/dev_project/asr-postclean-fr/exports/`. Leur présence a été vérifiée, sans recharger les tenseurs. Avant tout essai, reprendre leur manifeste et leurs preuves de parité ; il ne faut ni reconstruire un parent différent ni perdre les adaptateurs entraînés.

L'exporteur avertit que la conversion conserve plusieurs copies des poids et recommande une machine Linux avec au moins 32 Go de RAM. Le budget de l'essai devra donc identifier l'hôte, la RAM disponible, l'espace temporaire, la durée maximale et les conditions d'arrêt avant lancement. Aucune conversion complète n'est lancée sur le Mac dans ce premier lot. [Contraintes du convertisseur](https://github.com/google-ai-edge/litert-torch/blob/main/litert_torch/generative/README.md#known-issues).

Les chiffres officiels sur le Galaxy S26 Ultra distinguent le premier token du débit de génération. Ils ne donnent ni le temps de correction complète sur Poco F7 ni la qualité de notre export. Le MTP est également une optimisation du décodage, pas une garantie sur toute la chaîne transcription/correction. [Mesures et MTP](https://developers.google.com/edge/litert-lm/models/gemma-4).

## Décision pour le prochain essai

1. Recevoir la chronologie CPU du Poco et vérifier ce qui domine réellement : fin ASR, attente du moteur, génération ou propositions rejetées.
2. Si l'essai GPU reste justifié, figer un export de nos poids, sa recette, son tokenizer, son gabarit et les versions du convertisseur/runtime. Prévoir un seul essai borné et les six mêmes textes de correction.
3. Vérifier d'abord une sortie complète, les tokens spéciaux, l'arrêt, les négations et les nombres. Une boucle de tokens `<pad>`, une sortie vide ou un écart de sens interdit toute conclusion de vitesse utile.
4. Mesurer ensuite le temps complet et le résultat réellement applicable, séparément du premier fragment. Ne pas attribuer à la conversion une amélioration de qualité venant d'un autre modèle.

Le premier APK de mesure conserve le moteur CPU, les poids, les consignes et les délais actuels. Il ne consomme pas l'unique essai de faisabilité GPU prévu dans le plan et ne constitue pas encore une optimisation.
