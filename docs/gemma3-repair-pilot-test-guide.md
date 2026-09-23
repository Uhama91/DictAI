# Essai DictAI 0.9.12 avec Gemma 3 — guide de test

Cette préversion permet d'observer le formatage final d'une dictée avec la
référence Gemma 3 270M V3 existante. Les poids V3 sont inchangés. Le candidat
d'entraînement attention + MLP a été rejeté et n'est pas utilisé dans cet
essai. Un retour utilisateur du 23 septembre mesure 1 987 ms de
post-traitement et 2 382 ms entre l'arrêt et l'insertion, sans expiration.
Il signale encore des corrections insuffisantes. Cet essai isolé, dont
l'appareil n'est pas encore confirmé, ne qualifie pas la qualité ou la
latence générale du modèle.

La version préparée **0.9.12-dictai-gemma3-test** (code 41) ajuste le filtre
qui valide les propositions du modèle : certaines corrections locales de
grammaire et certains faux départs peuvent être acceptés lorsqu'ils sont
reconnus et que les informations autour sont conservées. Ce changement ne
garantit pas que le modèle proposera ces corrections. Il n'ajoute pas de
nouveaux poids et ne constitue pas un nouveau fine-tuning.

Sur 108 sorties V3 déjà enregistrées, la simulation du texte livré après
validation passe de 67 à 72 correspondances exactes avec la correction
attendue. Les cinq gains relus concernent des répétitions et des
autocorrections explicites de date. Ce résultat ne mesure ni la latence sur
téléphone ni une réussite générale. La grammaire reste partiellement couverte :
huit corrections synthétiques attendues sont encore refusées, dont quatre
qui relevaient du périmètre initialement envisagé.

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
disponibles. Dans la version 0.9.11, le diagnostic peut afficher
« correction progressive appliquée » malgré un traitement uniquement final :
les compteurs des appels partiels et finaux permettent de vérifier le travail
réellement effectué. La version 0.9.12 corrige ce libellé pour identifier le
traitement final. Plusieurs essais sur un même appareil restent nécessaires
pour caractériser la latence.

## Vérifier les corrections

Essayez d'abord quelques phrases synthétiques courtes : un doublon
« on on », un groupe de mots répété, puis une autocorrection explicite de
jour (« lundi, non, mardi »). Vérifiez aussi qu'une phrase déjà correcte
reste intacte, notamment un pronom réfléchi (« nous nous préparons »), une
insistance (« très très »), une négation et une date sans autocorrection.
Une bonne correction grammaticale proposée par le modèle doit conserver
les noms, nombres et détails voisins ; un retour au texte reconnu signifie
qu'aucune correction validée n'a été livrée.
