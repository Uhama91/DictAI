# Préparer les mêmes entrées Gemma 4 dans Python et Android

## Portée

Le candidat entraîné a été évalué avec `build_prompt_v6_source_last`, un message utilisateur français unique. Le runtime Android emploie encore une autre instruction. Ce lot prépare une construction Kotlin byte-identique, sans activer le candidat ni modifier le moteur, le téléchargement, le validateur ou l'overlay. Tâche moyenne : Luna Max, TDD puis une revue principale. L'intégration active du runtime fera l'objet d'une étape distincte.

Créer uniquement un helper Kotlin `GemmaFineTunedPrompt.kt`, son test et des fixtures synthétiques/provenance dans ce worktree. Dans `asr-postclean-fr`, un nouveau générateur de fixtures est autorisé ; les builders historiques demeurent gelés. L'agent n'est pas seul dans les deux projets et ne modifie aucun fichier des autres lots.

## Contrat

Le helper prend explicitement le segment source, le mode (TEXT/LIST/EMAIL), la phase (partial/final), le contexte antérieur et les termes protégés. Il restitue exactement la chaîne française du builder V6 source-last : mêmes textes, espaces, sauts de ligne et ordre des champs JSON, version parent `gemma4-v6` conservée. Cette tâche porte sur le prompt utilisateur existant, pas sur la création d'une nouvelle consigne. Aucun message système ou jeton de template n'est concaténé ici.

Ne pas modifier `LocalFormatRequest` ou `GemmaFormattingPrompt` et ne pas appeler le nouveau helper en production. Aucun choix de modèle ou de moteur n'est implicite. Les modes/phases sont typés pour empêcher une faute de chaîne ; la source et le contexte ne sont ni nettoyés ni réécrits dans cette couche.

La sérialisation doit correspondre à Python `json.dumps(ensure_ascii=False, separators=(",", ":"), allow_nan=False)` pour ces chaînes/listes. Attention aux guillemets, antislashs, retours ligne, tabulations, autres contrôles JSON, accents, emoji, U+2028/U+2029 et `</` : ne pas employer une bibliothèque qui les transforme différemment sans test de parité. Aucune nouvelle dépendance générale n'est nécessaire. Une minuscule fonction dédiée est acceptable si elle est intégralement testée contre les fixtures Python. Les données synthétiques ne contiennent aucune dictée personnelle.

## Preuves

Le générateur appelle les véritables builders Python gelés pour créer des fixtures couvrant les six paires mode/phase, contexte vide/non vide, plusieurs termes protégés et les caractères limites. Chaque fixture contient entrées et résultat attendu complet ; le manifeste contient les SHA des deux builders, du générateur et des fichiers. Les tests Kotlin lisent ces fixtures et comparent les octets UTF-8, pas uniquement des fragments de consigne. Ajouter un contrôle explicite de fin source-last.

RED avant helper puis GREEN ; tests ciblés et suite unité JDK 21 à la fin si aucune autre compilation Android n'est active, `git diff --check`. La sonde GGUF s'exécute sur CPU dans l'autre projet : limiter Gradle à 2 Go. Aucun test de tokens natifs ou de qualité n'est revendiqué par cette parité de texte. Aucun commit/push/APK dans ce lot.
