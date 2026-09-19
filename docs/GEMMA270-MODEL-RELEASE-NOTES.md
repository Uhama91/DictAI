# DictAI Gemma 3 270M V3 — modèle expérimental

Cette release contient le fichier de modèle utilisé par le pilote de
post-traitement local français de DictAI. Le modèle est expérimental et n’est
pas présenté comme validé sur téléphone.

## Fichier

- Nom : `gemma3-270m-postclean-v3-q8_0.gguf`
- Format : GGUF Q8_0
- Taille : `291545280` octets
- SHA-256 : `6c4b7b6654c9638287c31e50fd0bf849f33a2ff2dd93bee685bdd9632f20ecf5`
- Base : `google/gemma-3-270m-it`
- Révision de base : `ac82b4e820549b854eebf28ce6dedaf9fdfa17b3`
- Adaptation : LoRA V3 fusionnée dans la base, puis quantification GGUF Q8_0
- Version de consigne : `v3`

## Conditions

Gemma is provided under and subject to the Gemma Terms of Use found at
ai.google.dev/gemma/terms.

Cette distribution est soumise aux Gemma Terms of Use, notamment aux
restrictions de leur section 3.2, ainsi qu’à la Gemma Prohibited Use Policy.
Les textes complets sont joints aux assets de la release :

- `LICENSE.txt`
- `PROHIBITED_USE_POLICY.txt`
- `NOTICE.txt`
- `gemma270-model.json`

Le modèle a été adapté par LoRA puis modifié par fusion et quantification.
Cette release ne contient aucun jeu de données privé, aucune clé ni aucun
secret. Elle fournit uniquement les poids et les métadonnées nécessaires à la
vérification du pilote.

## Utilisation dans DictAI

Cette release de poids est destinée à l’APK pilote `dictai-gemma270-v3-test`.
Dans DictAI, choisir le moteur **Local**, puis le format **Texte corrigé**.
Le format **Texte** reste sans modèle de langage.
