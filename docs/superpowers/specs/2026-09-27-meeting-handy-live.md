# Réunion : transcription Handy prioritaire et attribution indépendante

## Besoin confirmé

L'utilisateur conserve Nemotron 3.5 Handy Q8 pour la dictée et veut retrouver son comportement pendant une conversation française entre deux personnes sur Poco F7. Le texte doit apparaître pendant la parole. La confirmation du nom peut arriver plus tard, au bon emplacement ; la séparation parfaite de voix simultanées n'est pas le critère prioritaire.

Avant cette correction, la dictée et Réunion capturent toutes deux un micro mono 16 kHz via `AudioSource.MIC`, avec des moteurs différents : Handy/transcribe.cpp pour la dictée, NeMo-Speech.cpp pour Réunion. La dictée utilise des blocs d'au plus 20 ms, Réunion des blocs de 100 ms. Le profilage de Réunion montre que la diarisation synchrone peut retarder considérablement le flux ; aucune mesure sur émulateur ne prédit à elle seule la vitesse du Poco. Le nouveau lecteur Réunion utilise lui aussi des blocs de 20 ms, avec un tampon matériel indépendant d'au moins 200 ms.

## Décisions

1. Réutiliser le modèle Handy Q8 et le moteur déjà employés par la dictée. Conserver leurs réglages de reconnaissance. L'accès aux fragments horodatés doit être additif et ne doit pas modifier les méthodes utilisées par la dictée.
2. Traiter la diarisation dans un worker indépendant. Le modèle de diarisation existant reste utilisable sans charger l'ASR NeMo. L'affichage d'un résultat Handy ne dépend jamais de la fin d'un calcul de diarisation.
3. Attribuer les mots selon leurs temps dans l'audio, jamais selon l'instant où les résultats arrivent. Ne pas extrapoler le dernier intervenant sur une zone audio que la diarisation n'a pas encore traitée. Afficher « Intervenant à confirmer » en attendant.
4. Une attribution tardive révise le passage existant. Elle ne duplique pas son texte, ne le rajoute pas à la fin et ne remplace pas une attribution ou une retouche humaine.
5. Conserver les files audio bornées, leur stockage temporaire privé, l'annulation immédiate côté interface et la libération prouvée des ressources avant une autre session.

## Contrats de données

La sonde et le code de Handy montrent que le streaming fournit des tokens horodatés, même lorsqu'on demande des mots : la liste des mots reste vide. La passerelle expose donc le texte UTF-8 original, les octets bruts de chaque token, leurs temps relatifs à l'audio et la frontière de tokens engagés. Les octets d'un caractère accentué peuvent être répartis sur plusieurs tokens ; ils ne sont jamais décodés indépendamment en chaînes Java.

Le contrat de lecture contient `fullTextUtf8`, `firstTokenIndex`, `totalTokenCount`, `committedTokenCount`, `tokenBytes`, `tokenByteEnds`, `tokenStartsMs` et `tokenEndsMs`. La fenêtre demandée est bornée à 8 192 tokens. L'assembleur rapproche leur concaténation du texte original après la même réduction des espaces ASCII U+0020 que Handy ; il ne normalise pas les autres caractères d'espacement. Un échec de rapprochement conserve le texte sans lui inventer d'alignement. Les ponctuations finales peuvent porter des temps au-delà de l'audio réel : elles restent dans le texte mais ne prolongent pas artificiellement l'intervalle lexical. Aucun temps précis n'est fabriqué lorsqu'un passage n'a pas d'alignement exploitable.

Ces temps proviennent des trames d'émission du décodeur Handy (`step_at_emit` et `duration_frames`). Ils fournissent un ancrage dans l'audio, pas une garantie d'alignement phonétique exact de chaque mot. La cadence de 10 ms de la diarisation n'implique donc pas une précision de 10 ms de la transcription. Une frontière de voix ambiguë reste non attribuée.

Le découpage des énoncés doit conserver exactement les caractères du texte original, notamment accents, apostrophes, négations et ponctuation. Un découpage interne borné peut servir le réducteur existant, limité à 512 mots par hypothèse ; il ne doit pas provoquer un nouveau titre de personne à chaque bloc. La finalisation native ne supprime aucun texte encore provisoire.

La passerelle de diarisation expose les probabilités, leur origine temporelle, la cadence des trames et une frontière de stabilité. Sa fenêtre de lecture est limitée à 16 384 trames. Le modèle V3 produit une trame toutes les 10 ms ; la géométrie interne à 80 ms n'est pas sa cadence de sortie. L'attribution exige que tout l'intervalle lexical soit couvert par des trames stables et non ambiguës. Le GGUF épinglé déclare huit canaux : les indices natifs 0 à 7 deviennent les canaux métier 1 à 8 ; le canal métier 0 reste inconnu. La durée totale des trames doit couvrir la durée du PCM fourni, à l'arrondi de trame près.

Le travail conservé pour les révisions est limité à 120 secondes d'audio et à 256 blocs d'énoncé ; les paroles déjà présentes dans le document sont préservées. La file secondaire accepte au plus 120 secondes de PCM. Si elle sature, l'identification des voix devient indisponible pour cette session : aucun trou audio n'est masqué par une reprise avec une horloge décalée. Un retard ou une panne de diarisation ne doit pas supprimer les paroles déjà transcrites ni bloquer l'affichage ASR. Ces bornes ne constituent pas une garantie de mémoire native constante : les moteurs possèdent aussi leur propre historique.

## Orchestration et cycle de vie

`MeetingEngine` conserve la responsabilité de l'admission des blocs, de sa file principale, des checkpoints et de la fermeture. La composition Handy/diarisation est placée derrière une frontière dédiée et testable, avec dépendances injectées.

- Le chemin principal alimente Handy et publie son résultat sans attendre la diarisation. La disponibilité initiale attend Handy seulement ; le chargement de la diarisation a lieu sur son worker indépendant.
- Le chemin secondaire reçoit une copie bornée du même audio, dans le même ordre. Il publie ensuite des révisions d'attribution.
- Les callbacks utilisent des identifiants et des révisions monotones. Les résultats d'une ancienne session sont ignorés.
- L'annulation ferme les entrées, abandonne les blocs encore en attente et empêche toute nouvelle publication. Elle ne bloque pas le thread de l'interface pendant un calcul natif.
- La fermeture attend la libération effective des deux chemins avant de rendre la réservation native. Une fermeture incertaine conserve le mécanisme de protection existant.
- La fin de capture finalise d'abord le texte Handy ; l'attribution peut encore se compléter. L'utilisateur doit toujours pouvoir annuler une finalisation en retard.
- Les diagnostics ne contiennent ni audio, ni transcription, ni noms d'intervenants.

La sonde courte a précédé l'implémentation. Le pont Handy possède `open`, `acceptPcm16`, `snapshot`, `finish` et `close`. Le pont de diarisation autonome expose une fenêtre de trames, son indice absolu, sa cadence, ses probabilités et ses frontières totale et stable. Les deux bibliothèques JNI restent séparées pour ne pas mêler leurs implémentations GGML. Les noms Kotlin précis sont partagés entre les responsables des deux lots.

## Présentation

La projection conserve l'identité de chaque éditeur. Un indicateur séparé masque le titre visuel des continuations d'une même personne identifiée quand elles se suivent à moins de 1 500 ms. Le libellé logique et l'action d'attribution restent accessibles. Une nouvelle personne ou une zone incertaine rétablit un titre.

Afficher des repères relatifs à l'audio uniquement pour les passages dont la provenance temporelle est connue. Les documents anciens et les replis non alignés n'affichent pas de temps inventé. Le tri d'une séquence de passages alignés ne traverse jamais un passage sans repère fiable.

Le retard observable est présenté comme une durée d'audio encore à traiter. Ce compteur n'est ni une promesse de durée d'attente, ni une mesure exacte du délai perçu avant l'apparition des mots.

## Modèles et compatibilité

Le catalogue Réunion doit désigner explicitement Handy si ce chemin est retenu après la sonde. Vérifier taille et SHA-256 avant publication du paquet. Un fichier Handy déjà installé dans la même application peut être réutilisé seulement après vérification d'intégrité ; l'absence de ce fichier passe par le téléchargement explicite existant. Ne pas charger silencieusement le GGUF NeMo avec le runtime Handy. Préserver les brouillons et notes existants.

## Preuves requises

- Sonde française : paramètres exacts de la dictée Handy, texte complet, mots/temps, premier résultat, coûts et mémoire. Comparer à un témoin NeMo dans des conditions communes ; séparer initialisation, calcul et cadence audio.
- Test déterministe : bloquer la diarisation avec un verrou de test, alimenter Handy factice et observer le texte avant de libérer la diarisation. Libérer ensuite et vérifier une révision de la même prise de parole.
- Tests A/B/A, résultat tardif, frontière encore inconnue, chevauchement non attribuable, retouche, attribution manuelle et image attachée.
- Tests annulation pendant chargement, calcul et finalisation ; fermeture des deux ressources, absence de callback tardif et conservation du texte déjà présent.
- Test d'une longue suite de résultats : mémoire de travail bornée, hypothèses sous la limite du réducteur et absence de duplication aux frontières des blocs.
- Comparaison native du texte Handy avec le chemin dictée existant : aucun changement de modèle, de langue ou de réglage masqué par l'activation des horodatages.
- Comparaison native de Handy seul avec Handy et diarisation simultanés, sur la même fixture française : premier texte, temps de calcul, retard audio, mémoire et fermeture. Les workers indépendants partagent encore les ressources du téléphone ; des doubles de test ne suffisent pas à prouver leur coexistence. Vérifier ensuite le chemin intégré avec assembleur et interface.
- Captures Android de la conversation en fenêtre normale et compacte, puis audit visuel des titres, temps, retouches et images.
- Deux revues Astra du même état immuable, tests ciblés puis vérifications de livraison justifiées, APK Actions et identité du paquet vérifiés.

La fluidité physique et la qualité d'une conversation naturelle française restent à confirmer sur Poco F7. Le compte rendu distinguera cette limite des garanties obtenues par tests automatisés.

## Arbitrage après la première mesure simultanée corrigée

Sur l'AVD à quatre cœurs, Handy et la diarisation utilisant chacun quatre threads donnent une fin de texte à 85,766 s pour 12,78025 s d'audio, contre 18,890 s et 21,885 s pour les témoins Handy seuls. Le texte et la couverture temporelle sont conservés, mais cette configuration est rejetée pour son coût. L'expérience bornée suivante conserve les paramètres Handy et limite la seule diarisation autonome à un thread CPU. Le runtime NeMo historique garde sa valeur par défaut de quatre threads. Ce réglage doit être reproductible par un patch épinglé et mesuré avant tout raccord de production.

La comparaison corrigée à un thread conserve le texte et réduit fortement ce surcoût, sans l’annuler. Le test prolongé de 63,9 s ne termine toutefois pas le traitement des voix dans les 120 s de drainage accordées. Le moteur intégré court publie le texte pendant l’écoute et conserve toutes les paroles au fil des neuf révisions de voix. Le raccord par défaut est donc retenu pour **test4 expérimental**, avec cet échec de débit consigné dans le rapport. La validation d’une réunion longue sur Poco reste ouverte ; elle ne peut pas être déduite du passage des tests fonctionnels.
