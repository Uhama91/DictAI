# DictAI 0.8.4 — essai Gemma sur téléphone

Ullie a choisi Gemma après la recherche comparative. Le prototype utilise Gemma 4 E2B dans LiteRT-LM 0.17.0, sur GPU, avec MTP activé et thinking désactivé (budget zéro). Nemotron reste le moteur de transcription. Ce document décrit l'essai et ses limites ; il ne remplace pas une mesure sur le POCO F7.

## Installation depuis le téléphone

**[Télécharger directement l’APK 0.8.4](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34335478012/dictai-local-layout-test.apk)** — environ 79 Mo. [Publication GitHub](https://github.com/Uhama91/DictAI/releases/tag/gemma-test-34335478012), [construction GitHub Actions](https://github.com/Uhama91/DictAI/actions/runs/34335478012). Installer en mise à jour conserve Gemma déjà téléchargé. Le résultat des contrôles de publication figure dans [la vérification 0.8.4](VERIFICATION-GEMMA-0.8.4.md).

Installer l'APK de la prerelease Gemma, puis ouvrir DictAI et toucher **Installer Gemma 4 E2B** dans les réglages de post-traitement. Le modèle officiel fait 2 588 147 712 octets, environ 2,6 Go. Il est téléchargé une seule fois depuis le dépôt officiel épinglé. Garder cet écran ouvert ; quitter l'application met le téléchargement en pause et une nouvelle ouverture de la ligne permet de le reprendre.

Le modèle est utilisable uniquement après contrôle de sa taille et de son SHA-256. Une interruption conserve les octets partiels ; une reprise vérifie la plage et la version du fichier. Une fois installé, les listes et mails locaux fonctionnent sans connexion ni clé API. Le premier chargement et la préparation du GPU peuvent prendre plusieurs secondes ; ils sont lancés à l'avance dans le service, hors de l'interface.

Le téléchargement séparé permet de fournir une APK installable directement depuis GitHub : le paquet Gemma standard dépasse à lui seul la limite par fichier d'une release. Le poids 350M n'est plus inclus dans ce prototype.

## Essai dans la dictée

Choisir **Local** dans **Moteur de post-traitement**, puis **Liste** ou **Mail** par le geste habituel vers le haut au repos. Le mode Texte et les acquiescements courts conservent un chemin direct. Une modification finale de la transcription invalide un calcul anticipé devenu obsolète. Le texte source reste disponible si le moteur échoue, dépasse le délai ou fournit une réponse rejetée.

Après une dictée, ouvrir **Dernier post-traitement** et copier le diagnostic. Il indique le modèle, la route réellement appliquée, la configuration GPU/MTP/thinking, le délai de finalisation et l'arrêt vers insertion/copie. Ce diagnostic ne contient ni dictée, ni vocabulaire, ni clé API. Le cloud reste un choix explicite avec la clé de l'utilisateur ; aucune bascule automatique n'est effectuée.

À partir de 0.8.1, ce menu présente par défaut le **dernier format demandé**, même si une dictée en mode Texte a été faite ensuite. Son titre et la date du rapport permettent de le reconnaître. Le bouton **Dernière dictée** donne accès au rapport le plus récent de tous les modes. La mise à jour ne peut pas récupérer un rapport déjà écrasé par 0.8.0 : effectuer un essai Mail après installation, puis copier ce diagnostic. Le format est fixé au démarrage et partagé par l’affichage et la finalisation ; le choisir avant de commencer à dicter.

## Mesurer les mails longs — 0.8.4

Ouvrir **Mesurer les mails longs avec Gemma**, laisser la dictée au repos et garder l’écran ouvert. Le test traite les deux mails longs déjà fournis, deux fois chacun, puis propose **Copier les résultats**. Tous les passages sont réalisés par Gemma ; aucune règle de disposition directe n’est utilisée. Un calcul peut continuer jusqu’à 20 secondes après préparation initiale, file comprise.

Le rapport donne séparément la préparation du moteur, l’attente avant le natif, le premier fragment, le retour du moteur, la validation et l’écart aux seuils de 5/8 secondes. Une interruption laisse la durée totale nécessaire inconnue. Ces temps de banc excluent l’arrêt du micro/ASR et l’insertion dans une autre application.

En dictée, un mail de **60 mots ou plus** est de nouveau confié à Gemma. Le délai maximal Mail passe à **8 secondes**, à titre d’essai : l’application utilise une sortie dès qu’elle est prête et validée. Les listes restent à 5 secondes. Les petits acquiescements et certains mails courts conventionnels restent directs. Le diagnostic mentionne la limite réellement utilisée. Cette marge ne prétend pas accélérer le modèle et sera réévaluée avec le rapport Android.

Les mesures CPU sur ordinateur sont 15,399 secondes pour le premier mail long et 24,923 secondes pour le dernier mail de 144 mots. Elles ne prédisent pas le temps GPU du téléphone. Le premier mail avait terminé sur téléphone en 4,978 / 5,136 secondes. Le rapport 0.8.4 reçu ensuite donne, validation comprise, 5,836 secondes pour le premier mail et 9,233 secondes pour le dernier. Ce dernier est structuré mais rejeté pour une substitution locale → local ; une attente plus longue seule ne résoudrait pas ce rejet. La copie ne contient pas les deux mails du second passage. [Protocole et limites](VERIFICATION-GEMMA-0.8.4.md).

## Changements 0.8.3

Le petit overlay reprend le suivi des mots nouveaux après une correction, même lorsque le clavier conserve le focus. Le curseur reste à sa position ; le suivi attend la fin de la composition, de la sélection ou du toucher et 900 ms sans interaction. La vue se recale après mise en page et redimensionnement. Le rendu sur HyperOS reste à confirmer sur appareil.

En mode Texte, **Nettoyage léger du texte** est activé par défaut dans les réglages : retrait de certaines hésitations et répétitions de pronoms, sans LLM. Citations, vocabulaire, négations et répétitions expressives restent protégés. Une dictée retouchée manuellement échappe au nettoyage global. Quelques questions explicites sont ponctuées ; les questions implicites et la correction générale du sens restent hors de ces règles. Ce passage est disponible également avant le cloud choisi avec clé API.

Dans la voie courte (moins de 60 mots depuis 0.8.4), pour un mail avec salutation et signature clairement reconnues, la mise en paragraphes est faite directement puis les mots sont vérifiés. Le diagnostic porte **traitement direct, sans appel LLM**. Cette voie n’attend pas la génération complète de tout le mail. Gemma reste utilisé pour les cas incertains et les listes ; une génération trop longue peut encore dépasser l’attente finale de huit secondes pour Mail (cinq pour Liste). Aucun changement du modèle ou du thinking.

## Mesure reproductible

**Tester Gemma sur ce téléphone** exécute onze sources FR/EN, deux passages. Chaque résultat indique **direct local** ou **Gemma**. Les deux mails longs sont générés entièrement par Gemma, comme dans le nouveau test dédié aux mails. L’ancien cas garde son nom **Gemma seul (comparaison 0.8.2)** pour retrouver les mesures précédentes. Une réponse directe ne mesure pas la vitesse du LLM. Les critères de regroupement restent distincts de la conservation du texte.

Le banc partage le moteur avec l'overlay. Il précise si le modèle était déjà chargé ; il ne prétend pas vider les caches. Le premier fragment natif ne constitue pas le texte final validé. Le temps du banc exclut arrêt ASR, affichage et insertion. Des échantillons PSS du processus, RAM disponible et état thermique Android sont indiqués, sans prétendre mesurer toute la mémoire GPU ni une consommation électrique.

Pour évaluer le ressenti, comparer ensuite de vraies dictées courtes avec le précédent 350M : délai après le geste de fin, format correct et texte conservé. Répéter à chaud puis après plusieurs minutes d'utilisation sur batterie. Une mesure isolée de débit sur ordinateur ou sur un autre téléphone ne prédit pas cette latence.

## Implémentation et vérification

Le service et le banc partagent une seule instance du modèle avec des conversations indépendantes. Chargement, génération, annulation JNI et destruction sont exécutés hors UI. Une annulation abandonne les sorties tardives ; si le natif ne répond pas, aucune seconde instance GPU n'est créée. L'API d'initialisation n'est pas interruptible : les ressources sont conservées jusqu'au retour natif pour éviter une libération pendant leur utilisation.

Les contrôles de téléchargement et de transport sont testés avec des fixtures locales. Les tests JVM n'exécutent pas le GPU Android. Les résultats de construction, signature et alignement 16 Ko sont consignés dans [VERIFICATION-GEMMA-0.8.4.md](VERIFICATION-GEMMA-0.8.4.md). La construction locale exige Java 21 pour les classes de l'AAR officiel ; Kotlin 2.4.10 et D8/R8 9.1.43 sont épinglés, avec cible JVM 17 et minSdk Android 30 conservés.

Commande de vérification : `bash scripts/verify_gemma_android.sh`. La commande historique `bash scripts/verify_local_postprocessing.sh --prototype` redirige vers ce contrôle Gemma.

## Qualité observée avant publication

Une passe CPU sur ordinateur avec la consigne de production figée a produit dix réponses complètes. Six passent le contrôle de fidélité ; cinq de ces six ont une structure satisfaisante. Les courses de l'utilisateur conservent tous les produits mais restent regroupées en trois puces au lieu de six. Quatre réponses sont rejetées pour suppression d'un mot, conversion d'un nombre, duplication ou modification de signature. [Sorties exactes et protocole](benchmarks/local-format/gemma4-integration-probe-2026-09-09.json).

Une seconde hypothèse a demandé uniquement les indices de coupure pour éviter toute réécriture et réduire la sortie à générer. Neuf plans sur dix sont techniquement valides, mais aucun découpage ne correspond aux attentes ; cette variante n'est pas intégrée. [Résultats de l'hypothèse indices](benchmarks/local-format/gemma4-layout-plan-probe-2026-09-09.json).

Le prototype est donc livré pour mesurer l'exécution GPU réelle et tester Gemma dans la dictée, avec conservation du texte après rejet. La qualité globale des formats reste ouverte. Ces petites sondes exploratoires ne sont pas un taux de réussite représentatif ; leurs durées CPU ne prédisent pas celles du téléphone.

Retour ultérieur : le rapport Mail fourni par Ullie confirme un rejet de fidélité après 4 957 ms de finalisation. La reproduction sur ordinateur supprime normalement avant cordialement. À partir de 0.8.2, de petites omissions certaines sont rétablies depuis la source avant validation, sans seconde génération. Les mots, nombres et leur ordre restent conservés ; ajouts, substitutions, troncatures et cas ambigus restent refusés. [Analyse et limites](2026-09-09-retour-gemma-telephone.md#diagnostic-mail-reçu-et-correction-082).

## Sources et poids exact

- [Paquet officiel épinglé](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1), fichier `gemma-4-E2B-it.litertlm`, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`.
- [API Android LiteRT-LM](https://developers.google.com/edge/litert-lm/android), GPU, préchargement, annulation et MTP.
- [Recherche et essais précédents](2026-09-09-recherche-avancee-llm-mobile.md). Les performances éditeur concernent d'autres appareils ; la latence POCO F7 reste à établir.
