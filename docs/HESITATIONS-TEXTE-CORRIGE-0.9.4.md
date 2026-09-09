# DictAI 0.9.4 — hésitations dans Texte corrigé

Ullie confirme le 9 septembre qu’il utilisait déjà **Texte corrigé** et le moteur Local quand des « euh » spontanés restaient présents. Le parcours du vocabulaire fonctionne : proposition grek → Grok, enregistrement puis réutilisation dès la deuxième occurrence. Le défaut des hésitations est traité séparément.

## Cause établie dans le code et correction

La consigne demandait au modèle d’enlever les hésitations, mais le validateur acceptait aussi une copie inchangée qui les contenait. Un rejet, un délai dépassé ou une absence de résultat republiait la source intacte. Une retouche manuelle faisait en plus passer le modèle en conservation stricte des mots. Les tests reproduisent ces mécanismes ; le diagnostic GPU de la dictée d’Ullie n’a pas été reçu, donc aucun de ces chemins n’est attribué exclusivement à son essai.

Dans **Texte corrigé**, les hésitations euh/heu/uh/um (avec allongements simples) sont maintenant retirées par des règles locales avant la génération. L’anticipation et la finalisation utilisent la même préparation. Le repli est ce texte nettoyé : la suppression ne dépend plus du choix du modèle et reste acquise si sa sortie est refusée ou indisponible. Aucun appel modèle supplémentaire.

Seuls les segments effectivement saisis ou corrigés à la main sont protégés de ce retrait. Corriger Grok ne protège plus les hésitations dictées dans le reste du texte. Les retouches lexicales restent soumises à la conservation stricte pendant l’appel au modèle. Un brouillon restauré sans provenance détaillée est protégé dans son ensemble ; les nouvelles paroles ajoutées restent nettoyables.

Les virgules séparant les éléments d’une énumération sont conservées. Citations (y compris apostrophes avec élisions), mentions comme « le mot euh », vocabulaire, adresses/fichiers, acronymes en majuscules et hésitations saisies à la main sont conservés. Un texte constitué uniquement d’une interjection est conservé pour éviter une publication vide. Ce traitement ne supprime pas toutes les onomatopées et ne reformule pas le propos. Les négations, nombres et répétitions expressives restent intacts.

Les modes Texte, Mail, Liste et les formats personnels gardent leur comportement. Le réglage Nettoyage léger du texte reste propre au mode Texte ; la préparation de Texte corrigé est liée au choix explicite de ce format. La sauvegarde directe d’une note, les captures et les exports ne changent pas. Modèle, thinking et plafonds d’attente ne changent pas.

## Diagnostic et essai ciblé

Dernier post-traitement indique **Hésitations retirées avant correction : N · règles locales** lorsqu’un retrait a eu lieu. En cas d’échec du modèle, le rapport distingue ce nettoyage réussi de la correction non appliquée. Le compteur ne contient aucun texte dicté ni terme du vocabulaire.

Sur téléphone, sélectionner Texte corrigé, dicter « Euh, je consulte Grok et euh je garde les 23 dossiers ». Résultat attendu : « Je consulte Grok et je garde les 23 dossiers. » Vérifier aussi une correction de Grok au milieu de la même dictée suivie de nouvelles hésitations. Une citation volontaire contenant euh doit rester intacte.

## Vérification

Construction propre finale réussie : **429 tests JVM dans 61 suites**, zéro échec ni test ignoré ; 18 tests du contrôleur APK réussis ; contrat natif transcribe.cpp réussi. APK ARM64 construite, signature de mise à jour identique à 0.9.3, version code33 / 0.9.4-wp-gemma-test et alignement ELF/ZIP 16 Ko vérifiés. Contrôle des dépendances et absence d’ancien modèle embarqué réussis.

APK locale finale : **78833036 octets**, SHA-256 `7dad952b7d9f204bf256823126dddbce62855bc55d982b922da021fd63def42c`. Les tests couvrent copie inchangée par le modèle, repli après échec/rejet, citations avec élision, ponctuation d’énumérations, Grok corrigé dans la même dictée, effacement puis saisie, reprise d’un brouillon, vocabulaire automatique et diagnostic sans contenu dicté. Revue indépendante effectuée ; ses deux constats sur citations et virgules sont corrigés et couverts.

Publication Actions et contrôle du téléchargement public en cours. Aucun appareil Android connecté à Codex ; essai terrain de 0.9.4 encore à effectuer.
