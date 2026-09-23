# Essai Gemma 3 270M V3 — guide de test

Cette préversion permet d'observer le formatage final d'une dictée avec la
référence Gemma 3 270M V3 existante. Les poids V3 sont inchangés. Le candidat
d'entraînement attention + MLP a été rejeté et n'est pas utilisé dans cet
essai. Aucune mesure de qualité ou de latence sur téléphone n'est encore
disponible.

## Préparer l'application

1. Installez l'APK de test comme mise à jour de DictAI. Ne désinstallez pas
   l'application et n'effacez pas ses données : l'identifiant d'application
   et le certificat de signature sont conservés pour cette mise à jour.
2. Dans **Mise en forme**, choisissez le moteur **Local** et le format
   **Texte corrigé**.
3. Installez le modèle **Gemma 3 270M V3** depuis l'application. Le fichier
   est téléchargé séparément ; il fait environ 292 Mo. Son identité attendue
   est consignée dans la notice de l'APK et dans la release du modèle
   [`gemma270-v3-model`](https://github.com/Uhama91/DictAI/releases/tag/gemma270-v3-model).
4. Utilisez une courte phrase en français, puis terminez la dictée. Répétez le
   même essai quelques fois et distinguez le premier chargement du modèle des
   utilisations suivantes.

## Observer le résultat

La limite de **3 secondes** concerne l'attente de correction après la
finalisation de la transcription ASR. Elle ne représente pas le délai total
entre l'arrêt de la dictée et l'insertion du texte. Si la correction dépasse
cette limite ou si sa proposition est refusée par les garde-fous, le texte
reconnu reste disponible. Vérifiez que vous pouvez le relire et le modifier.

Pour comparer des essais, notez le modèle du téléphone ou de la tablette, la
version Android et DictAI, si le modèle venait d'être chargé pour la première
fois ou s'il avait déjà été utilisé, le délai entre la fin de l'ASR et le
résultat, et si le texte est resté disponible après une expiration. Ne
transmettez pas le contenu d'une dictée personnelle dans un rapport de mesure.

Ce pilote teste le format **Texte corrigé** en français. Les listes, les mails
et la correction progressive par Gemma ne sont pas pris en charge. Il n'y a
pas de bascule cloud implicite ; l'ASR direct et l'édition humaine restent
disponibles. La latence réelle sur téléphone reste à mesurer.
