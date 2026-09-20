# Essayer le pilote expérimental Gemma 4 V6 de DictAI

Cette fiche accompagne le pilote expérimental de nettoyage et d'affichage progressif. La sélection retenue est **Gemma 4 E2B V6 expérimental**, au checkpoint `1956`, en quantification Q6. Les nouveaux poids sont distincts des anciens poids Gemma ; ils sont destinés uniquement à cet essai pilote. L'artefact, d'environ 3,93 Go, est réparti en trois parties que le téléchargement de l'application doit récupérer séparément pour le moteur CPU.

Le modèle est publié et vérifié dans la release [`gemma4-v6-1956-q6-evaluation-20260920`](https://github.com/Uhama91/DictAI/releases/tag/gemma4-v6-1956-q6-evaluation-20260920). La fabrication et la publication de l'APK restent à faire par GitHub Actions : aucun lien d'APK n'est annoncé dans cette fiche.

Dans les réglages de mise en forme, sélectionner le moteur local et installer le modèle proposé. Le téléchargement est reprenable. Après installation, les dictées et leur correction s'exécutent localement. Le diagnostic de post-traitement distingue le moteur demandé, le résultat appliqué et les délais ; il ne contient pas le texte dicté.

## Ce que les vérifications permettent déjà de dire

La revue qualitative Q6 porte sur 60 sorties hors téléphone. Elle rapporte 42 sorties brutes utilisables sur 60 (70 %), 11 critiques brutes toutes rejetées par les garde-fous, 31 propositions qui passent ces garde-fous et 29 autres qui sont rejetées avec un repli exact vers la source, ainsi que 13 sorties utilisables rejetées. Ces compteurs correspondent à des étapes différentes de la revue et ne constituent pas un score global de l'application.

Le score d'usage dans l'application et la vitesse sur téléphone ne sont pas validés. Les essais sur ordinateur ou dans GitHub Actions ne remplacent pas une vérification sur l'appareil cible.

## Trois essais courts

| Format | Dictée d'essai | Points à vérifier |
| --- | --- | --- |
| Texte corrigé | « Je voudrais le la copie du dossier, euh, avant vendredi. La réunion est mardi, non, mercredi. » | Garder « la copie », vendredi et mercredi ; retirer le faux départ et la date explicitement abandonnée. |
| Texte corrigé, puis liste à puces | « Pour demain il faut trois choses : le formulaire signé, deux photos d'identité et la copie du justificatif. Je déposerai le dossier avant midi. » | Trois éléments lisibles, avec la quantité de deux photos. Introduction et conclusion conservées. En texte corrigé, seule l'énumération devient une liste. |
| Mail | « Bonjour Claire, je vous confirme la la réunion de mercredi. Je ne pourrai pas venir avant dix heures. Merci pour votre retour. Cordialement, Zoé. » | Mail lisible, reprise retirée, nom, date, négation, horaire et signature conservés. Aucun objet ou engagement ajouté. |

Ces exemples servent au diagnostic pratique. Ils ne constituent pas un nouveau jeu de test indépendant et ne servent pas à annoncer un pourcentage de réussite.

## Une dictée longue avec retouche

Dicter plusieurs phrases terminées, au moins une soixantaine de mots, puis continuer à parler. Le traitement progressif attend un passage suffisamment stable : l'absence de changement immédiat au début est normale. Une fin de phrase encore incertaine peut rester brute pendant que les passages précédents sont traités.

Lorsque du texte apparaît, corriger un mot directement dans l'overlay, puis poursuivre la dictée. La retouche doit rester intacte, la suite doit s'ajouter et le texte final doit conserver les deux. Vérifier aussi l'ouverture du clavier, le défilement et la possibilité de déplacer le panneau. Arrêter la dictée, puis contrôler une dernière fois les noms, les nombres, les dates et les négations.

## Juger le résultat

Un résultat utile conserve toutes les informations et ne demande aucune correction, ou seulement une à deux petites retouches locales. Une phrase à reconstruire ou une liste à refaire signale un nettoyage insuffisant. Un nom, un nombre, une négation ou une condition transformés constituent une erreur importante, même si la présentation paraît soignée.

Le retour à la transcription brute est volontaire lorsque les contrôles de fidélité refusent une correction. Il protège le contenu, mais ne compte pas comme un nettoyage réussi. Pour juger le gain réel, comparer l'effort nécessaire avant et après correction.

Dans « Tester Gemma sur ce téléphone », distinguer le premier chargement des passages suivants. Le diagnostic copiable permet de relever les délais et la version utilisée. Une mesure de vitesse sur ordinateur ou dans GitHub Actions ne remplace pas cet essai sur votre appareil.
