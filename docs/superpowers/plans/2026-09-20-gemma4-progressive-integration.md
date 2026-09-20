# Raccordement de la correction progressive à l'overlay

Lot complexe, à attribuer après acceptation du buffer et du moteur CPU. Deux revues du principal sur les fichiers finaux. Le raccordement s'active uniquement pour le pilote fine-tuné ; la variante existante conserve son chemin. Propriété prévue : un coordinateur testable, ses tests, l'adaptation minimale d'EditableTranscript et le raccordement dans OverlayService. Ne pas modifier les poids, les garde-fous lexicaux ou les consignes.

## Une continuation unique

Un run pilote possède un coordinateur de segments avec un backend du cœur CPU partagé. Ne pas créer en parallèle la session de spéculation historique sur le texte entier : elle concurrencerait les demandes progressives et pourrait repasser sur les retouches. Enregistrement, pause, reprise, arrêt, changement de note et annulation gardent leurs gates existants. La fermeture du coordinateur est idempotente et annule uniquement son backend.

Le brut est assemblé comme aujourd'hui à partir de committed.trim() et tentative.trim(), séparés par un espace lorsque nécessaire. La limite stable est exactement la longueur du préfixe committed dans cet assemblage. La transformation fournie à EditableTranscript.update reçoit sa continuation brute exacte ; son début peut être calculé par la différence des longueurs de l'assemblage et de cette continuation. Déduire la limite stable relative avant toute correction de vocabulaire ou de nombres. Une normalisation ne doit jamais servir à mesurer les offsets ASR. Un backend sans committed ne produit aucun segment anticipé.

Le buffer rend ses sorties acceptées et le reliquat brut normalisé DANS le callback transform d'EditableTranscript.update. Ainsi la valeur displayed de l'éditeur et le texte réellement visible coïncident. Ajouter si nécessaire un accès étroit en lecture au préfixe ancré de l'éditeur, pour alimenter le contexte précédent du buffer ; ne pas exposer ses listes internes. L'appelant transmet le nombre de mots de la dictée complète pour le seuil de démarrage, même après une retouche humaine.

Le coordinateur exécute au plus une demande de segment à la fois. Il construit LocalFormatRequest à partir du segment normalisé, avec phase et contexte du buffer, puis applique acceptOutput à la réponse complète avant publication. Aucune sous-chaîne issue de onChunk n'est du texte accepté. Un résultat déclenche sur main une nouvelle présentation du même dernier snapshot ASR ; il est revalidé par identifiant et époque avant acceptation. Des mots ajoutés après le segment ne provoquent pas son annulation. Les demandes invalidées ne doivent pas attendre leur ancienne réponse pour bloquer la file suivante indéfiniment.

## Corrections manuelles et IME

Lors d'un vrai changement clavier : EditableTranscript.edit garde la propriété du préfixe visible, le coordinateur annule son backend et réinitialise le buffer dans une nouvelle époque. Les prochains mots reconnus restent traitables : ne plus utiliser hasUserEdits comme interdiction globale de tout nettoyage futur dans le pilote. Le comportement initial protège volontairement tout le préfixe visible au moment de l'édition ; il ne prétend pas refaire automatiquement les mots antérieurs laissés en brut.

Les ancrages de références d'images doivent également invalider les anciens segments, en conservant leur sémantique distincte de retouche manuelle. Garder les marqueurs comme termes protégés. Le remplacement visuel minimal et TranscriptSelectionMapping restent utilisés. Si une composition IME est active, différer la publication visuelle du résultat ; ne pas remplacer les caractères composés. Après composition, vérifier à nouveau l'époque et la source avant application. Les publications d'un ancien run ou d'une ancienne note sont refusées.

## Arrêt et résultat final

À l'arrêt, attendre la transcription finale existante puis la passer au même état de continuation. Une révision ASR de la source d'un segment accepté invalide les segments touchés, comme prévu par le buffer. Les segments encore valides ne sont pas retraités. Le reste est demandé en FINAL et peut être très court ; pour une dictée courte c'est le premier appel au modèle.

Le résultat final est le rendu assemblé par l'éditeur, avec le préfixe humain protégé. Ne pas soumettre de nouveau tout ce texte au LLM historique ni effectuer une suppression globale des hésitations sur les mots de l'utilisateur. Le traitement déterministe autorisé s'applique à la continuation normalisée avant demande et sert de repli. Si le calcul échoue ou dépasse le budget final, conserver le brut du reliquat et les segments déjà acceptés. L'attente finale du pilote doit être explicite et bornée, au plus 20 secondes pour ce premier raccordement ; le budget produit visé reste beaucoup plus court et sera mesuré. Aucun gain de latence n'est revendiqué par ce simple plafond.

Conserver l'injection, l'archivage, les images, les gates d'annulation et la ponctuation finale prudente. Adapter les indications de disponibilité à la présence du coordinateur pour ne pas afficher « Non disponible » parce que l'ancienne session est absente. Une indication discrète « Correction en cours… » suffit ; aucun détail de moteur dans la dictée. Les diagnostics distinguent segments acceptés et reliquat conservé.

## Vérification

Tests RED/GREEN avec faux backend, latches et dispatcher contrôlé : segment accepté visible avant arrêt ; queue dictée pendant calcul ; correction utilisateur puis nouveaux mots nettoyés ; résultat ancien ignoré ; révision ASR ; pause/reprise ; annulation/changement de note ; finalisation courte et longue ; délai final ; préparation de vocabulaire qui change le nombre de mots ; listes et paragraphes ; aucun chunk brut publié. Réutiliser les tests de sélection et de protection de l'éditeur. Pas de sleeps de coordination arbitraires.

Après tests et build, inspecter visuellement le parcours et les libellés dans l'AVD disponible si possible, sans charger des poids réels pour en déduire une latence téléphone. La concurrence Nemotron, la mémoire, la chauffe et la vitesse sur Poco F7/Pad 7 restent un test physique distinct. Le pilote ne doit pas être décrit comme validé sur ces appareils sans preuve.
