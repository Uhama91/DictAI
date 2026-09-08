# Suivi des demandes d’Ullie — DictAI

Mis à jour le 8 septembre 2026. Travail en cours dans Codex sur la branche existante. Procéder par étapes testées, à la demande d’Ullie ; ne pas oublier les demandes suivantes et ne pas les considérer comme déjà livrées.

## 1. Post-traitement et correction personnelle — priorité actuelle

Décision actualisée du 8 septembre : **le LLM local n’est pas abandonné**. Les essais de réécriture libre ont échoué avec 350M et quatre candidats plus gros. Une nouvelle configuration LFM2.5-350M copie tous les mots sous contrainte et laisse le modèle choisir uniquement les puces/paragraphes. Le corpus élargi contient dix générations et deux réponses directes : tous les mots sont conservés, mais le découpage reste imparfait. La version normale conserve cloud/désactivé ; le prototype séparé `0.7.1-wp-local-test` sert à mesurer listes/mails sur téléphone. Ce n’est pas encore un remplacement général et instantané du cloud.

- [x] Prototype listes/mails avec modèle inclus et licence ; deux exemples par format et grammaire imposant la copie.
- [x] Aperçu progressif reconstruit depuis toute la source ; préparation pendant les pauses, moteur résident, annulation et retour à la source si délai dépassé.
- [x] Petit acquiescement sans salutation : résultat direct, sans attendre une génération antérieure. Le préchargement du moteur peut avoir commencé à l’ouverture de la dictée, mais aucune génération n’est nécessaire pour ce résultat.
- [ ] Validation de la qualité du découpage sur un corpus inédit plus large ; prise en charge des reformulations et formats personnels en local.
- [x] Formats cloud avec la clé API OpenRouter existante. Vocabulaire et nombres partagés entre modes ; aucune bascule cloud automatique.
- [x] Détection des remplacements manuels dans l’overlay et proposition explicite d’enregistrement `forme transcrite => forme corrigée`, y compris suppression puis saisie au même endroit. Suppression seule : aucune suggestion. Tests du cœur effectués.
- [x] Réglage nombres en chiffres / lettres / transcription conservée, FR/EN, avec protections des cas ambigus et tests.
- [ ] Validation des gestes et de la suggestion vocabulaire sur téléphone.
- [x] Exécution du JNI réel sur ordinateur : fidélité, UTF-8, annulation/reprise, grammaire invalide, troncature et délai vérifiés.
- [ ] Mesure de la latence complète sur le téléphone d’Ullie. Le banc intégré teste le moteur isolé ; la dictée réelle doit aussi vérifier arrêt ASR → insertion.

Fichiers de reprise : [essai sur appareil](ESSAI-LLM-LOCAL.md), [configuration et résultats](2026-09-08-comparatif-llm-local.md), [rapport de construction et contrôles](VERIFICATION-POST-TRAITEMENT.md). APK conservées dans `app/build/outputs/apk/verified/` : normale et `app-local-layout-test.apk`. La construction du prototype inclut un seul poids 350M ; les autres restent dans le cache de recherche. Attente additionnelle de mise en forme limitée à cinq secondes, sans garantie de latence globale avant mesure appareil. Le moteur retenu et le format sont capturés au début de la dictée ; changer le réglage prépare la suivante.

## 2. Réactivité et certitude au démarrage — après le lot LLM prioritaire

Nouvelle demande d’Ullie : après appui, il ne sait pas toujours si l’écoute a réellement commencé ; l’overlay ou les premiers mots tardent, ce qui le pousse à répéter son message. Demande conservée. Le dernier message d’Ullie remet explicitement le LLM local et sa latence en priorité ; reprendre ensuite cette incertitude sans la considérer corrigée.

- [ ] Mesurer séparément appui → retour visuel, appui → première lecture audio effective et première parole → premiers mots affichés, à froid et à chaud.
- [ ] Afficher immédiatement un état de démarrage ; passer à une indication claire d’écoute et à un retour haptique seulement quand la capture est réellement active. Ne pas faire attendre les premiers mots pour confirmer l’écoute.
- [ ] Vérifier que l’ouverture de la session ASR et les recherches de champ destinataire ne bloquent pas l’affichage ni le début de la lecture audio.
- [ ] Vérifier que les premiers mots d’un message très court sont conservés quand l’utilisateur parle immédiatement après appui. Ne pas masquer une éventuelle perte par un simple changement d’animation.
- [ ] Tester les refus micro, modèle encore en chargement, double tap, annulation au démarrage et reprise après pause.

Pistes vérifiées dans le code, sans diagnostic appareil établi : `RecordingStartupTransaction.start()` démarre AudioRecord avant `openSession()` ; le lecteur audio et le retour visuel complet sont lancés ensuite dans `OverlayService.startRec()`. La recherche de sensibilité du champ destinataire est aussi faite avant le démarrage, même lorsque le cloud est désactivé. Mesurer ces étapes avant de les réorganiser, en conservant les garanties d’annulation et de libération audio.

## 3. Envoyer depuis la pause — après la réactivité

Demande exacte : après une pause et une éventuelle fin de saisie à la main dans l’overlay, glisser la pastille vers le haut doit terminer la dictée et insérer le texte courant dans le champ de l’application destinataire, en le copiant aussi dans le presse-papiers. Ne pas relancer le microphone.

- [ ] En pause : afficher l’indication « ↑ Envoyer » et utiliser la finalisation existante qui préserve les retouches manuelles.
- [ ] Même action pour un brouillon/note éditable en pause, sans session micro active.
- [ ] Pendant une pause encore en cours de traitement, mémoriser la demande et envoyer une seule fois après arrêt effectif du lecteur audio.
- [ ] Vérifier absence de reprise micro, absence de double insertion, conservation des retouches, focus du champ destinataire et copie effective.
- [ ] Conserver le choix de format par glissement vers le haut au repos et préciser le geste pour réafficher le texte pendant l’enregistrement.

Repères : `OverlayService.kt` : gestion ACTION_UP / `exportOpenNote` / `stopRec` / `finishAfterPause`. `injectOrCopy` copie déjà le texte avant d’essayer l’insertion. Ne pas déclencher l’envoi réel d’un message dans l’application destinataire : seule l’insertion dans son champ est demandée.

## 4. Simplifier l’offre de modèles de transcription — à étudier

Ullie est satisfait de Nemotron actuel et propose de conserver un modèle principal, éventuellement adapté à la puissance du téléphone ou de la tablette. La suppression des autres modèles n’est pas décidée : rechercher puis présenter une recommandation étayée avant de réduire le catalogue.

Catalogue actuellement dans `ModelDownloader.kt` :
- Nemotron 3.5 Handy Q8_0, recommandé : fichier GGUF 751 094 240 octets.
- Nemotron 3.5 Compact Q6_K : fichier GGUF 621 356 512 octets.
- Nemotron 3.5 Live ONNX int8 : taille de téléchargement annoncée 453 Mo.
- Parakeet 0.6B v3 ONNX int8 : taille de téléchargement annoncée 465 Mo.

Ne pas confondre taille du téléchargement, taille installée, mémoire de travail et vitesse. Une quantification plus petite ne garantit pas un débit supérieur sur tous les processeurs.

- [ ] Vérifier les variantes actuelles dans les sources officielles, couverture FR/EN, prise en charge par le moteur de l’application et limites matérielles.
- [ ] Comparer la mémoire en pic et le débit réel sur le téléphone de référence et au moins un appareil plus modeste.
- [ ] Examiner une présentation simple : modèle recommandé, variante économique si validée, autres modèles dans des options avancées plutôt qu’un long catalogue au premier lancement.
- [ ] Étudier un court test local de capacité avant recommandation automatique ; ne pas promettre une compatibilité universelle d’après la RAM ou le nom du processeur seulement.

## Contraintes de reprise

- Changements précédents de l’utilisateur à préserver : `CLAUDE.md` et `docs/2026-09-07-voix-personnelle-et-gestes.md`.
- Publication GitHub autorisée le 8 septembre par Ullie : il doit pouvoir télécharger l’APK depuis son téléphone. Publier la version d’essai via Actions et une prerelease avec lien APK direct. Aucune installation sur appareil effectuée par Codex.
- Ullie demande de ne plus reconfirmer chaque opération déjà autorisée ; regrouper les écritures et vérifications soumises aux restrictions techniques du harness.
- Les demandes présentes de l’utilisateur priment sur ce suivi. Mettre à jour ce fichier après chaque étape avec les résultats réels des tests et les limites restantes.
