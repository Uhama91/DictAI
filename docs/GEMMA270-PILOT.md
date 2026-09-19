# Pilote Gemma 3 270M V3

Cette version d’essai embarque un modèle Gemma 3 270M instruction-tuned,
adapté par fine-tuning LoRA, puis fusionné et quantifié au format GGUF Q8_0.
Elle vise le post-traitement local en français : ponctuation, majuscules,
paragraphes et corrections prudentes dans le contexte de la transcription.

Le paquet est un pilote. Il ne revendique pas de validation sur téléphone et ne
garantit pas une transcription parfaite. Le manifeste embarqué fixe la version
du prompt, le modèle de base, la taille et les empreintes SHA-256 du GGUF, du
dossier adaptateur, de ses poids et du prompt.

## Activer le pilote

1. Téléchargez l’artefact `dictai-gemma270-v3-test` depuis GitHub Actions et
   vérifiez `SHA256SUMS`.
2. Installez l’APK sur un appareil Android ARM64.
3. Dans DictAI, ouvrez **Moteur de post-traitement** et choisissez **Local**.
4. Dans **Format de la dictée**, choisissez **Texte corrigé**. C’est ce format
   qui utilise le modèle Gemma du pilote.
5. Le format **Texte** reste le mode sans modèle de langage.

Le modèle est copié dans l’espace privé de l’application au premier usage. Si
son empreinte ou sa taille ne correspondent pas au manifeste, l’installation
est refusée et le texte original reste disponible.

## Provenance et conditions d’utilisation

Gemma is provided under and subject to the Gemma Terms of Use found at
ai.google.dev/gemma/terms.

Les fichiers distribués sont modifiés : le modèle a reçu un fine-tuning LoRA,
puis une fusion et une quantification GGUF Q8_0. Les restrictions de la section
3.2 des Gemma Terms of Use et la Gemma Prohibited Use Policy s’appliquent.
Les textes complets sont joints dans
[`app/src/gemma270Pilot/assets/local-format/`](../app/src/gemma270Pilot/assets/local-format/).

Le fichier du modèle sera téléchargé depuis l’asset épinglé suivant lorsqu’il
sera publié :

`https://github.com/Uhama91/DictAI/releases/download/gemma270-v3-model/gemma3-270m-postclean-v3-q8_0.gguf`
