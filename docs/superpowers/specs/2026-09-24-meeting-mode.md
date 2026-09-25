# DictAI — Mode Réunion en direct

Statut : spécification de développement, 24 septembre 2026. Rédaction et arbitrage : Astra, agent principal ; implémentation autorisée par l'utilisateur. Preuve native Android obtenue, intégration de la fonction en cours ; performances sur téléphone non mesurées.

Plan associé : [plan d'implémentation détaillé](../plans/2026-09-24-meeting-mode.md). La rédaction et les mises à jour de ces deux documents restent la responsabilité de l'agent principal ; elles ne sont pas déléguées aux agents d'implémentation.

## But

Permettre de participer à une réunion tout en suivant et en corrigeant une transcription qui attribue automatiquement les prises de parole. Conserver Nemotron pour reconnaître les mots. Activer une analyse des voix uniquement en mode Réunion.

## Deux modes

| Mode | Comportement |
| --- | --- |
| Dictée | Fonctionnement individuel actuel. Aucun chargement ni calcul de diarisation. |
| Réunion | Transcription progressive, attribution des passages aux intervenants, renommage et filtrage des voix pendant l'enregistrement. |

Le mode est choisi avant l'enregistrement. Une nouvelle réunion crée un nouvel espace de profils. Une pause dans la même réunion conserve les profils et les noms. Une reprise après arrêt du processus nécessite une stratégie spécifique : les noms sauvegardés ne suffisent pas à réidentifier les voix après perte du cache acoustique.

Les noms et attributions restent sauvegardés avec le compte rendu de leur réunion. En revanche, le cache acoustique n'est pas réutilisé pour reconnaître les personnes dans une nouvelle réunion ; fermer une réunion permet de libérer ce cache et le modèle de diarisation.

## Pastille et gestes — précision utilisateur du 24 septembre

Le choix du mode est accessible depuis le glissement vers le haut de la pastille, dans le menu qui donne déjà accès aux formats. Ajouter un choix explicite « Dictée / Réunion » en tête de ce menu. Les formats gardent leur sélection propre à Dictée ; choisir Réunion ne sélectionne pas un format de nettoyage. Le choix du mode reste possible depuis l’accueil et l’assistant. Une session en cours, même en pause, doit être terminée avant de changer de mode.

Cette règle vaut aussi pour la Dictée actuelle : sa pause conserve la session qui permet de reprendre. Un changement de mode affiche alors « Terminez la dictée avant de changer de mode. ». Une note simplement consultée ou retouchée, sans session d’enregistrement encore vivante, ne bloque pas le choix du mode ; elle est sauvegardée avant le prochain enregistrement.

En Réunion, conserver le dessin cursif, l’épaisseur du trait, les couleurs et l’enveloppe de la pastille. Les boucles forment une couronne circulaire qui tourne sur elle-même et donne un effet de spirale. Elles ne défilent plus horizontalement. Ce dessin indique le mode ; son mouvement indique une capture micro réellement active, y compris pendant un silence entre deux interventions. Les boucles restent fixes au repos, pendant la préparation, dès l’arrêt effectif du micro, en pause et après la fin. Le chargement reste indiqué séparément. Respecter le réglage Android qui désactive les animations et conserver un état textuel accessible.

| État Réunion | Appui sur la pastille | Glissement vers le bas |
| --- | --- | --- |
| Prête, sans session | Démarrer une nouvelle réunion | Aucune sauvegarde vide |
| Préparation | Aucun second démarrage | Aucune finalisation implicite |
| Écoute | Mettre en pause | Inviter à mettre en pause avant d’enregistrer |
| Mise en pause | Attendre la fin de la pause, sans reprise différée | Attendre la pause effective |
| Pause | Reprendre la même réunion, avec ses profils | Proposer d’enregistrer la transcription |
| Finalisation ou fermeture | Aucune commande concurrente | Aucune seconde sauvegarde |
| Terminée ou restaurée | Nouvelle réunion après sauvegarde de la note ouverte | Enregistrer les retouches de la note ouverte |

La proposition affichée en pause porte « Enregistrer la transcription ? », avec « Enregistrer et terminer » et « Continuer la réunion ». La seconde commande, Retour ou une fermeture du dialogue laisse la réunion en pause ; seul un nouvel appui reprend l’écoute. Confirmer draine les derniers résultats, termine la session native et enregistre une note structurée avec les prénoms, attributions, retouches et images. Il s’agit de la transcription, pas d’un fichier audio. La confirmation de succès attend l’écriture durable. Une erreur conserve le document accessible et propose « Réessayer » ; elle ne relance pas le micro.

La sauvegarde automatique du brouillon continue pendant la réunion. Le geste vers le bas est la commande explicite de fin et d’enregistrement de la note. Le panneau propose les mêmes commandes accessibles, afin qu’aucune action ne dépende uniquement d’un geste. Les raccourcis de Dictée gardent leur comportement actuel ; le glissement vers le haut d’une dictée en pause conserve notamment son action existante d’insertion ou de réaffichage du texte.

Une réunion nouvelle sans texte ni image ne crée pas de note vide. En revanche, vider volontairement une note déjà enregistrée doit sauvegarder cette suppression : son ancien texte ne doit pas réapparaître à la réouverture. Le texte saisi dans un passage documentaire compte aussi comme contenu, même sans parole reconnue ni image. Dans « Passages à vérifier », un corps volontairement vidé est indiqué par « (Texte supprimé) », séparément du texte reconnu d’origine.

La notification persistante suit aussi le mode et l’état réels : « Réunion en cours », « Réunion en pause — appuyez pour reprendre », puis « Réunion terminée ». Elle indique la préparation, la mise en pause et l’enregistrement de la transcription pendant ces transitions. Une sauvegarde en échec reste signalée. Elle ne contient aucun texte reconnu ni prénom, et une nouvelle hypothèse sans changement d’état ne provoque pas une nouvelle notification.

## Intervenants

Une note Réunion rouverte retrouve son document, ses intervenants et ses retouches, même si Dictée était sélectionné auparavant. Au repos, cette ouverture explicite sélectionne Réunion sans démarrer le microphone ; la couronne reste fixe. Les modèles absents n’empêchent pas de consulter ou corriger la note. Tant qu’un autre enregistrement possède sa session, y compris en pause ou pendant sa fermeture, l’ouverture d’une autre note est refusée en conservant le document courant. Une note rouverte ne récupère pas une mémoire vocale passée : un nouvel enregistrement crée une autre réunion après sauvegarde des dernières retouches.

Si le moteur est déclaré indisponible mais qu’aucune session ne détient encore de réservation, la lecture et l’édition d’une note connue restent possibles. Le changement de mode et le nouveau départ peuvent rester refusés ; ce refus ne doit pas empêcher de sauvegarder des corrections documentaires. Une structure inconnue demeure en lecture seule, avec un texte de repli consultable par défilement.

- Créer « Personne 1 », puis « Personne 2 », etc., à mesure que des voix distinctes sont reconnues. Ne pas demander de présentation préalable.
- Conserver une identité interne stable, indépendante du nom affiché. Les profils sont propres à la réunion ; pas de reconnaissance d'identité entre réunions.
- Toucher un intervenant pour saisir son prénom. Le changement actualise ses étiquettes passées et futures, ainsi que les exports. Il ne remplace jamais un mot similaire dans le corps d'une phrase.
- Autoriser des prénoms identiques pour deux personnes différentes ; conserver un repère distinct dans le sélecteur.
- Autoriser la correction manuelle de l'intervenant d'un passage. Cette attribution devient prioritaire sur les révisions automatiques.
- Le modèle retenu propose huit canaux de locuteurs. Une voix ignorée reste suivie et occupe un canal. L'ignorer ne libère pas une neuvième place.
- Ne pas promettre la détection fiable d'une neuvième personne : au-delà de la capacité, le modèle peut confondre des voix. Ne jamais recycler volontairement l'identité d'une personne pour un nouveau locuteur.

## Ignorer une voix

Décision de conception par défaut, réversible : masquer les interventions passées et futures de cette voix. L'interface indique « Masquer ses interventions dans cette réunion » et permet de tout réafficher.

Le profil reste dans la liste avec la mention « Ignoré » et une commande « Réafficher ». Les passages filtrés sont exclus du texte affiché et des exports ordinaires ; le texte interne reste disponible pour annuler le filtre. Le filtrage ne doit donc pas être présenté comme une suppression des données. Il ne nécessite pas de conserver un enregistrement audio permanent.

L'analyse continue de reconnaître la voix ignorée pour éviter de la recréer avec un autre numéro. Aucun gain de calcul n'est promis par ce filtrage. Si l'attribution reste incertaine, conserver le passage sous « Intervenant à confirmer » plutôt que le masquer arbitrairement.

Distinguer le filtrage des paroles attribuées et la séparation acoustique. Une voix forte qui couvre une autre personne peut empêcher la reconnaissance de certains mots. La diarisation seule ne restitue pas un signal propre et ne garantit pas la transcription de deux voix simultanées.

## Direct et corrections

Le texte apparaît pendant la réunion, sans attendre l'arrêt de l'enregistrement. Les premiers mots peuvent précéder une attribution suffisamment stable ; utiliser alors une étiquette provisoire. Une révision de locuteur actualise les étiquettes en préservant les retouches du corps de texte.

« Instantané » signifie ici une transcription progressive avec un délai court à mesurer, jamais une promesse de délai nul. Mesurer séparément le délai des mots, celui de l'attribution et la réactivité des commandes. Les latences de tampon publiées par NVIDIA excluent le calcul et ne constituent pas des mesures sur Poco F7.

La correction manuelle reste disponible pendant l'enregistrement. Une révision ASR, un renommage, un changement d'attribution ou un filtre ne doit pas écraser les retouches. Préserver les suppressions, la composition clavier, la sélection, le point de lecture et les images. Les nouvelles paroles continuent d'arriver sans déplacer le curseur de correction.

Si une révision ne permet plus de retrouver avec certitude un passage corrigé, conserver le texte affiché et rendre la proposition reconnue consultable dans « Passages à vérifier ». Ne pas dupliquer tout l'énoncé ni élargir une attribution manuelle à des paroles nouvelles. Cette consultation reste accessible lorsqu'une voix est ignorée. Les images jointes restent à leur position dans le document, indépendamment du filtre vocal.

L'interface proposée comporte un choix « Dictée / Réunion », une liste compacte d'intervenants et un corps de transcription organisé par prises de parole. Les commandes « Renommer », « Ignorer » et « Réafficher » sont accessibles sans interrompre le micro. Une attribution incertaine reste visible et corrigeable.

## Exemple de comportement

1. Personne 1 : « Nous pouvons commencer. »
2. Personne 2 : « Le rendez-vous est prévu mardi. »
3. L'utilisateur renomme Personne 1 en Sophie et Personne 2 en Karim.
4. Les deux étiquettes déjà affichées deviennent Sophie et Karim.
5. Sophie reprend la parole : son nouveau passage apparaît sous Sophie.
6. L'utilisateur corrige « mardi » en « jeudi » pendant que la transcription continue. Cette correction reste protégée.
7. Une troisième voix est reconnue ; l'utilisateur la marque « Ignoré ». Ses passages sont filtrés selon la portée choisie, sans interrompre Sophie ni Karim.

## Architecture à respecter

Les données métier doivent conserver séparément : session, intervenants, segments audio horodatés, attributions automatiques, attributions manuelles, texte reconnu, retouches et état de filtrage. Le texte affiché est une projection de ces données ; il ne constitue pas la seule source de vérité.

Conserver la même horloge audio pour les mots et les activités des locuteurs. Les numéros de canaux du modèle ne doivent pas devenir directement des identifiants persistants sans gestion de la session. Ne pas relancer une diarisation indépendante sur chaque fragment en supposant que ses numéros correspondent aux précédents.

Ne pas demander à un modèle de nettoyage textuel de deviner les voix. Un éventuel nettoyage doit préserver les frontières de prises de parole, les noms et les retouches. Donner la priorité au micro et à la transcription ; ne pas charger automatiquement un modèle de nettoyage supplémentaire pour satisfaire ce mode.

Vérifier la capacité effective du modèle avant le démarrage : les exemples du SDK peuvent sélectionner par défaut l'ancien Sortformer à quatre voix. Utiliser le modèle à huit voix explicitement. Qualifier les dépendances natives pour éviter un conflit entre les versions de GGML déjà embarquées et celles du nouveau SDK.

En cas d'échec de la diarisation pendant une réunion, conserver les textes et les retouches, afficher l'indisponibilité de l'attribution et continuer la transcription si le runtime garantit encore un flux ASR valide. Si cette continuité n'est pas disponible, arrêter visiblement la capture et conserver le brouillon. Ne pas inventer de locuteur, ne pas réutiliser automatiquement un nom après réinitialisation, et ne pas basculer vers un service distant. Borner les files audio et la mémoire pour les longues réunions ; tout retard ou trou doit rester visible dans les mesures.

## Point de départ vérifié, avant implémentation

- `DictationAsrEngine.kt` publie deux chaînes, texte confirmé et provisoire, sans segments de locuteurs.
- `TranscribeCppNative.kt` expose `full`, `committed` et `tentative`, sans horodatages de mots ni identifiants de locuteurs.
- `OverlayService.kt` et `EditableTranscript.kt` gèrent déjà l'édition pendant la dictée et la protection d'un préfixe modifié. Ajouter des noms dans cette chaîne plate ne suffirait pas à assurer renommage et filtrage fiables.
- `TranscriptNotes.kt` conserve principalement un texte et des images. La persistance structurée des réunions demande une extension compatible avec les notes existantes.
- Le moteur natif actuel est `handy-computer/transcribe.cpp`, révision `553f1099a2b3a5bc4421894be171f09960fc0f3a`. Le nouveau SDK NVIDIA est une intégration distincte à qualifier, pas une option déjà disponible dans le binaire actuel.
- La voie Réunion cible `NVIDIA/NeMo-Speech.cpp`, révision `97a15afa5caa9bce5baaa86c1184103877af4101`, et un pont JNI dédié vers son API C++. Cette API possède `set_interim_words`, `stable_speaker_time` et `refresh_speaker_tags`. Dans cette révision, l'API C ne branche pas `set_interim_words` : sa simple activation des options de diarisation ne suffit donc pas à garantir des mots attribués dans les résultats provisoires. La sonde doit vérifier cette différence.
- Le paquet Réunion utilisera les deux GGUF officiels compatibles avec ce runtime, dont les révisions et empreintes sont fixées dans le plan. Leur téléchargement total représente 848 560 480 octets, soit environ 849 Mo. Le GGUF Handy actuel reste réservé à la voie Dictée tant qu'une compatibilité n'est pas démontrée.
- Des modifications locales de gestion des images sont en cours. Toute implémentation devra être isolée et préserver ces modifications.
- ADB voit un émulateur Android arm64 ; aucun Poco F7 physique n'est connecté au moment de ce cadrage.

## Validation avant de promettre le direct sur téléphone

1. Qualifier le modèle exact à huit locuteurs et son runtime natif : format, révision, licence, mémoire, API de streaming et stabilité des identifiants.
2. Exécuter une sonde Android arm64 alimentée au rythme réel d'un enregistrement français à trois voix. Vérifier que des étiquettes sortent avant la fin du fichier. Un traitement rapide après l'arrêt ne valide pas le besoin.
3. Mesurer Nemotron seul, puis Nemotron et diarisation ensemble, avec mêmes audio et réglages. Publier délais médians et au 95e percentile, retard accumulé, mémoire et erreurs d'attribution. Un débit moyen inférieur au temps réel ne suffit pas si les pointes rendent le direct inutilisable.
4. Valider sur le Poco F7 pendant au moins trente minutes : pauses, retours d'un intervenant, changements rapides, voix éloignée, bruit et chevauchements. Mesurer température et consommation. La réussite sur émulateur ne valide pas cette étape.
5. Tester renommage après plusieurs passages, homonymes, filtre/réaffichage, correction simultanée, suppression manuelle, nouvelle session et reprise. Aucune retouche perdue ; aucun prénom ne migre vers un autre profil lors d'une réinitialisation.
6. Tester une voix ignorée simultanée à une voix conservée. Conserver les mots attribuables à la personne retenue ; signaler l'incertitude lorsque la séparation n'est pas fiable.
7. Vérifier la dictée simple : aucun chargement du modèle de diarisation, comportement et performances inchangés à la variabilité de mesure près.

La première livraison technique doit être cette preuve de streaming réel. Une interface simulée ou un APK qui affiche seulement une liste d'intervenants ne constitue pas une validation de la fonction.

## Sources techniques

La [fiche NVIDIA Nemotron 3 Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization) décrit les huit locuteurs, la mémoire de session et le streaming. Le [guide d’association à Nemotron 3.5 ASR](https://huggingface.co/nvidia/Nemotron-3-Diarization/blob/main/ASR_INTEGRATION_GUIDE.md) précise les limites des voix superposées. [NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp) et son [SDK natif](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/main/docs/sdk.md) documentent les interfaces ; leur disponibilité ne prouve aucune performance Android.
