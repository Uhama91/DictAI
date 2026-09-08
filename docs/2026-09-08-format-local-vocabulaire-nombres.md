# DictAI 0.7 : formats locaux, vocabulaire et nombres

## Résultat des premiers essais — 8 septembre 2026

Le moteur natif ARM64 compile, utilise une bibliothèque isolée de 4 Mo, respecte
l’alignement de 16 Ko et expose seulement JNI_OnLoad. **Le poids LFM2.5-350M n’est
pas retenu pour une livraison normale** : le test du prompt complet perd pain/lait
et les quantités d’une liste, et perd le contenu d’un mail. Un prompt plus court
améliore la liste française mais déforme encore le mail et transforme « pens »
en « Pencot » en anglais. Une génération techniquement terminée ne valide donc pas
la fidélité du contenu.

La compilation normale garde cloud/désactivé, vocabulaire personnel et réglage des
nombres, sans inclure le poids de 229 Mo. La branche locale ci-dessous est un prototype
activable au build par `-PlocalFormatPrototype=true`, pour poursuivre la comparaison
avec une configuration différente. Le [nouvel essai de copie contrainte](ESSAI-LLM-LOCAL.md)
conserve les mots sur le corpus mais reste à mesurer sur téléphone.

## Comportement du nouvel essai de mise en page locale

Le nouvel essai `0.7.1-wp-local-test` utilise une copie contrainte qui conserve tous les mots.
Dans les réglages, **Moteur de post-traitement → Local** prend en charge les puces
et les paragraphes des formats Liste et Mail. Les formats personnalisés restent en cloud. Le choix du format
reste accessible par glissement vers le haut au repos. Une courte liste reste prise en charge ; un mail de cinq mots au plus sans salutation reconnue
peut passer directement sans génération. Le mode texte sans consigne particulière
conserve le chemin direct, avec vocabulaire et présentation des nombres.

Le modèle LFM2.5-350M Q4_K_M est inclus dans l’APK (229 312 224 octets), avec sa
licence et son attribution. Il est copié une fois dans le stockage privé, après
vérification SHA-256. Son extraction et son chargement se font hors de l’interface.
Cela ajoute environ 229 Mo au téléchargement et une copie privée de même taille.
Il n’y a ni clé ni requête cloud dans le chemin local.

Les mêmes formats, corrections de vocabulaire et réglages de nombres sont disponibles
avec **Cloud**, via la clé OpenRouter existante. Une mise en forme explicitement choisie
peut s’appliquer à un brouillon retouché dans les deux modes. Les règles de vocabulaire
et de nombres sont communes aux moteurs et n’exigent pas de requête supplémentaire.

Le moteur prépare une mise en forme quand un brouillon cesse de changer pendant
une seconde. Il conserve au plus une demande en attente ; les brouillons périmés
ne s’accumulent pas. À l’arrêt, un résultat préparé est réutilisé seulement si texte,
langue, consignes et vocabulaire protégé correspondent exactement. Sinon la demande
finale remplace la préparation périmée. La génération finale apparaît progressivement
dans le panneau ; l’insertion reçoit seulement un résultat terminé. Les sorties
interrompues, vides ou tronquées sont rejetées et le texte source reste le repli.

La mise en forme peut travailler sur un brouillon retouché ; les graphies du
vocabulaire présentes dans ce brouillon sont explicitement protégées. La grammaire et le contrôle complet imposent la conservation des unités source ; le découpage
reste imparfait et nécessite un contrôle sur des dictées réelles. Le traitement du texte ne réentraîne pas Nemotron.

## Vocabulaire par correction

Dans le brouillon DictAI, sélectionner un mot ou une courte expression et le remplacer
peut proposer **Enregistrer au vocabulaire : ancien → nouveau**. Le clavier peut
remplacer directement la sélection ou la supprimer puis saisir au même endroit.
La proposition attend une saisie stabilisée et la fin de la composition du clavier.
Une suppression seule ne propose aucune règle. Enregistrer reste une action explicite.
Le sens de la règle est toujours la mauvaise transcription vers la graphie voulue.

La proposition concerne l’éditeur de DictAI. Elle ne surveille pas les retouches
effectuées dans les autres applications après insertion. Les règles sont consultables
et modifiables dans **Mon vocabulaire**.

## Présentation des nombres

**Écriture des nombres** offre **En chiffres**, **En lettres** et **Conserver la
transcription**. Le choix est mémorisé et fixé pour la dictée en cours ; le changer
pendant une session prend effet à la suivante. En chiffres est le choix initial.
Ce traitement local n’appelle aucun LLM. Les articles, identifiants et formes ambiguës
doivent rester intacts ; le périmètre exact est couvert par les tests de nombres.

## Construction et vérification

Le modèle est récupéré par `python3 scripts/fetch_local_format_model.py`, à une
révision épinglée et avec taille + empreinte obligatoires. Il est exclu de Git.
Le moteur natif de mise en forme est isolé de GGML utilisé par la reconnaissance.
La compilation et les vérifications d’APK sont regroupées dans
`bash scripts/verify_local_postprocessing.sh --prototype` (normale + prototype séparés).

Les journaux de diagnostic portent sur les durées de chargement, post-traitement et
arrêt → publication ; ils n’enregistrent ni la dictée ni les mots du vocabulaire.

## Essais sur Poco F7 et Pad 7

1. Mode avion, Local sélectionné : dicter une liste courte, une liste longue, un mail,
   en français et en anglais. Essayer les formats personnels en cloud. Vérifier le format et les faits.
2. Comparer première dictée et suivantes ; dicter sans pause puis avec pauses naturelles.
   Mesurer l’attente après arrêt, pas seulement le débit de génération.
3. Pendant une préparation, changer la fin de la dictée, corriger un nom puis terminer :
   aucun ancien brouillon ne doit remplacer le texte final.
4. Annuler pendant la mise en forme puis dicter à nouveau : aucune sortie tardive.
5. Sélectionner « didi » puis taper « Dydy » : enregistrer la proposition, redicter le mot.
   Tester aussi supprimer puis taper, supprimer seulement, annuler, déplacement du curseur,
   mot accentué, remplacement par collage et suggestions/composition du clavier.
6. Comparer les trois choix de nombres avec « vingt-trois élèves », « deux virgule cinq »,
   « un rendez-vous », un identifiant et un numéro de téléphone. Vérifier les articles.
7. Conserver une retouche manuelle pendant une reprise ; vérifier les notes, le collage,
   les glissements, le clavier, la rotation et le redémarrage du service.

Les tests de compilation et sur ordinateur ne constituent pas une mesure de latence
sur le téléphone. La comparaison cloud/local reste une mesure terrain à effectuer.
