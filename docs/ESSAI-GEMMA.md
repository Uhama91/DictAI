# DictAI 0.8.1 — essai Gemma sur téléphone

Ullie a choisi Gemma après la recherche comparative. Le prototype utilise Gemma 4 E2B dans LiteRT-LM 0.17.0, sur GPU, avec MTP activé et thinking désactivé (budget zéro). Nemotron reste le moteur de transcription. Ce document décrit l'essai et ses limites ; il ne remplace pas une mesure sur le POCO F7.

## Installation depuis le téléphone

**[Télécharger directement l'APK Gemma 0.8.1](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34323447465/dictai-local-layout-test.apk)** — 78 551 445 octets, environ 79 Mo. [Publication GitHub](https://github.com/Uhama91/DictAI/releases/tag/gemma-test-34323447465), [construction Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34323447465). Le téléchargement public et son empreinte ont été vérifiés après publication. Installer en mise à jour conserve Gemma déjà téléchargé.

Installer l'APK de la prerelease Gemma, puis ouvrir DictAI et toucher **Installer Gemma 4 E2B** dans les réglages de post-traitement. Le modèle officiel fait 2 588 147 712 octets, environ 2,6 Go. Il est téléchargé une seule fois depuis le dépôt officiel épinglé. Garder cet écran ouvert ; quitter l'application met le téléchargement en pause et une nouvelle ouverture de la ligne permet de le reprendre.

Le modèle est utilisable uniquement après contrôle de sa taille et de son SHA-256. Une interruption conserve les octets partiels ; une reprise vérifie la plage et la version du fichier. Une fois installé, les listes et mails locaux fonctionnent sans connexion ni clé API. Le premier chargement et la préparation du GPU peuvent prendre plusieurs secondes ; ils sont lancés à l'avance dans le service, hors de l'interface.

Le téléchargement séparé permet de fournir une APK installable directement depuis GitHub : le paquet Gemma standard dépasse à lui seul la limite par fichier d'une release. Le poids 350M n'est plus inclus dans ce prototype.

## Essai dans la dictée

Choisir **Local** dans **Moteur de post-traitement**, puis **Liste** ou **Mail** par le geste habituel vers le haut au repos. Le mode Texte et les acquiescements courts conservent un chemin direct. Une modification finale de la transcription invalide un calcul anticipé devenu obsolète. Le texte source reste disponible si le moteur échoue, dépasse le délai ou fournit une réponse rejetée.

Après une dictée, ouvrir **Dernier post-traitement** et copier le diagnostic. Il indique le modèle, la route réellement appliquée, la configuration GPU/MTP/thinking, le délai de finalisation et l'arrêt vers insertion/copie. Ce diagnostic ne contient ni dictée, ni vocabulaire, ni clé API. Le cloud reste un choix explicite avec la clé de l'utilisateur ; aucune bascule automatique n'est effectuée.

À partir de 0.8.1, ce menu présente par défaut le **dernier format demandé**, même si une dictée en mode Texte a été faite ensuite. Son titre et la date du rapport permettent de le reconnaître. Le bouton **Dernière dictée** donne accès au rapport le plus récent de tous les modes. La mise à jour ne peut pas récupérer un rapport déjà écrasé par 0.8.0 : effectuer un essai Mail après installation, puis copier ce diagnostic. Le format est fixé au démarrage et partagé par l’affichage et la finalisation ; le choisir avant de commencer à dicter.

## Mesure reproductible

**Tester Gemma sur ce téléphone** exécute dix sources FR/EN à partir de 0.8.2, deux passages, dont deux réponses directes sans appel au modèle. Les sources comprennent listes sans virgules, compléments à conserver, nombres, négations, signatures et le mail long ayant reproduit une omission. Les critères de regroupement sont distincts de la conservation du texte. Le résultat brut des exemples est disponible après refus ou rétablissement de mots sources.

Le banc partage le moteur avec l'overlay. Il précise si le modèle était déjà chargé ; il ne prétend pas vider les caches. Le premier fragment natif ne constitue pas le texte final validé. Le temps du banc exclut arrêt ASR, affichage et insertion. Des échantillons PSS du processus, RAM disponible et état thermique Android sont indiqués, sans prétendre mesurer toute la mémoire GPU ni une consommation électrique.

Pour évaluer le ressenti, comparer ensuite de vraies dictées courtes avec le précédent 350M : délai après le geste de fin, format correct et texte conservé. Répéter à chaud puis après plusieurs minutes d'utilisation sur batterie. Une mesure isolée de débit sur ordinateur ou sur un autre téléphone ne prédit pas cette latence.

## Implémentation et vérification

Le service et le banc partagent une seule instance du modèle avec des conversations indépendantes. Chargement, génération, annulation JNI et destruction sont exécutés hors UI. Une annulation abandonne les sorties tardives ; si le natif ne répond pas, aucune seconde instance GPU n'est créée. L'API d'initialisation n'est pas interruptible : les ressources sont conservées jusqu'au retour natif pour éviter une libération pendant leur utilisation.

Les contrôles de téléchargement et de transport sont testés avec des fixtures locales. Les tests JVM n'exécutent pas le GPU Android. Les résultats de construction, signature et alignement 16 Ko sont consignés dans [VERIFICATION-GEMMA.md](VERIFICATION-GEMMA.md). La construction locale exige Java 21 pour les classes de l'AAR officiel ; Kotlin 2.4.10 et D8/R8 9.1.43 sont épinglés, avec cible JVM 17 et minSdk Android 30 conservés.

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
