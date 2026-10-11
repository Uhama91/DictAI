# DictAI Mac 0.4.2 — ponctuation française

La version 0.4.2 ajoute une correction locale des points et des majuscules à la fin de la dictée. Le modèle Apple Intelligence français est disponible sur ce Mac. Le traitement utilise tous les mots dans leur ordre d’origine : un résultat qui ajoute, supprime ou remplace un mot est refusé.

La version 0.4.2 (build 12) est installée dans `/Applications/DictAI.app`. Son exécutable correspond exactement à celui du paquet vérifié : [DictAI 0.4.2](/Users/ulliemaillot/Downloads/DictAI-0.4.2-macOS-test.pkg). L’accès au Trousseau a été validé par l’utilisateur et le compte est retrouvé.

## Retour au parcours rapide — 11 octobre 2026

L’utilisateur signale une attente trop longue pendant « Ponctuation… » et une insertion qui échoue. La ponctuation IA est donc désactivée dans l’application installée, avec confirmation de `intelligentPunctuation: false` dans le réglage persistant propre au Mac. Les prochaines dictées utilisent la typographie simple sans attendre le modèle Apple Intelligence. Aucune réinstallation ni modification des notes n’est nécessaire.

L’enquête dans les réglages macOS révèle « DictAI » désactivée et « DictAI Preview » autorisée. L’utilisateur valide la réactivation de DictAI ; l’application confirme ensuite l’accessibilité, le microphone et la reconnaissance vocale autorisés. Le raccourci Option est relancé. Les 39 notes et 56 entrées de vocabulaire affichées au début de cette intervention restent présentes. L’utilisateur confirme deux insertions rapides successives dans Codex, puis envoie les deux phrases insérées et confirme que le parcours fonctionne comme souhaité.

Les validations ci-dessous décrivent la préparation de la 0.4.2. Elles ne constituent pas une validation du parcours vocal réel après installation.

## Comportement

- Option pour terminer déclenche la ponctuation, puis l’insertion ou l’enregistrement de la note. « Relire » présente aussi le texte ponctué.
- Une pause et les redémarrages internes du moteur vocal ne déclenchent pas la correction. Une phrase interrompue reste ouverte.
- Les retouches manuelles sont conservées. Une retouche pendant le traitement demande une relecture avant l’insertion ; annuler empêche tout envoi tardif.
- Les noms reconnus, les mots du vocabulaire, les citations, les acronymes, les fichiers, les liens et les adresses électroniques sont protégés. Leur graphie d’origine est restaurée.
- Le réglage « Ponctuation intelligente sur ce Mac » est activé par défaut et enregistré uniquement sur le Mac. Il ne modifie pas les préférences Android. Si OpenRouter est déjà choisi, son format reste utilisé.

## Exemples observés avec le modèle local

Ces essais utilisent des phrases fictives et le code de production, sans microphone ni insertion dans une application externe.

| Avant | Après |
|---|---|
| je regarde l’application dans Le navigateur la dictée est terminée je peux passer à la suite | Je regarde l’application dans le navigateur. La dictée est terminée. Je peux passer à la suite. |
| Les enfants entrent. Dans la classe et ouvrent leur cahier. ensuite ils lisent. | Les enfants entrent dans la classe et ouvrent leur cahier. Ensuite ils lisent. |
| le fichier rapport_v2.pdf est prêt ouvre https://exemple.fr/a?x=3 puis contacte lea@example.fr | Le fichier rapport_v2.pdf est prêt. Ouvre https://exemple.fr/a?x=3 puis contacte lea@example.fr. |

Le modèle ne donne pas toujours le découpage attendu. Dans l’essai avec « est-ce que » au milieu du texte, il ajoute le point d’interrogation final mais laisse les deux propositions jointes. Ces résultats montrent une amélioration locale ; ils ne prouvent pas encore une qualité identique à Android sur les dictées réelles.

## Validation

144 tests réussis sur ARM et les mêmes 144 sous Rosetta : conservation des mots, de la casse du vocabulaire et des paragraphes, pause/reprise, relecture, note, insertion, retouches, annulation, changement de compte et expiration du délai. Les nouveaux tests ont d’abord reproduit les défauts avant leur correction.

Un essai supplémentaire traverse le véritable parcours applicatif avec le modèle local et une destination isolée. Il produit les trois phrases du premier exemple, puis termine la session et archive le texte. Traitement mesuré : 11,6 secondes après la fin de la dictée simulée. Le microphone et le champ Codex réels restent à tester après installation.

L’application et le paquet contiennent les architectures ARM et Intel. La signature locale et le paquet sont vérifiés. FoundationModels est lié de manière facultative dans les deux architectures pour conserver la compatibilité macOS 14+. Aucun essai sur un Mac Intel physique n’a été effectué.

L’IA peut prendre plusieurs secondes. Un délai indépendant de 25 secondes empêche le traitement de bloquer l’insertion ; le résultat tardif est ignoré et la ponctuation simple reste utilisée. Un paragraphe dépassant 3 500 caractères UTF-16 conserve son texte, sans troncature, et bénéficie de cette ponctuation simple. Un modèle indisponible ou une réponse refusée entraîne le même repli.

Sauvegardes privées avant installation : application 0.4.1 et données. Après installation, les 12 fichiers sont identiques à l’inventaire réalisé après fermeture de la 0.4.1 ; les 33 notes et 55 entrées de vocabulaire sont conservées. Au lancement initial, la version installée confirme le modèle de ponctuation local disponible, avec le réglage activé et OpenRouter désactivé. L’accessibilité était alors en attente de renouvellement ; son rétablissement et la désactivation de la ponctuation IA sont consignés plus haut.

## Aperçus natifs audités

Réglage clair :

![Réglage de ponctuation en thème clair](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/punctuation-0.4.2/punctuation-settings-light.png)

Réglage sombre :

![Réglage de ponctuation en thème sombre](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/punctuation-0.4.2/punctuation-settings-dark.png)

État du traitement :

![Ponctuation en cours dans la pastille](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/punctuation-0.4.2/punctuation-progress.png)

Texte ponctué dans l’éditeur rapide :

![Texte avec points et majuscules cohérents](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/punctuation-0.4.2/punctuation-review.png)

Ces aperçus proviennent d’une bibliothèque temporaire. Aucune donnée de démonstration n’a été ajoutée aux notes réelles.

Références techniques : [modèle local Apple](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel), [sessions FoundationModels](https://developer.apple.com/documentation/foundationmodels/languagemodelsession). Les signatures Swift du SDK installé ont également été vérifiées.
