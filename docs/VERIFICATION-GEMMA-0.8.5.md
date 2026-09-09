# Vérification DictAI 0.8.5 — corrections et choix explicite du texte

9 septembre 2026. Vérifications locales terminées sur la branche codex/intentional-format-swipe ; publication GitHub Actions terminée et téléchargement public vérifié.

## Périmètre

- Texte (ID cleanup conservé) n’appelle aucun LLM local ou cloud, quelle que soit la longueur. Texte corrigé est une sélection distincte ; moteur choisi dans les réglages. L’export direct d’une note ouverte reste direct.
- Mails et textes corrigés d’au moins 60 mots : maximum local de 10 s, résultat utilisé dès qu’il est prêt et accepté. Mails courts 8 s ; listes/textes corrigés courts 5 s. Banc indépendant à 20 s.
- Correction limitée des accords/orthographes reconnus, hésitations non citées, répétitions adjacentes de mots/expressions et déterminants repris. Conservation des noms, du vocabulaire, des nombres, des négations et signes techniques. Les petits cardinaux exactement équivalents sont remis dans leur présentation source. Aucun jugement sémantique universel.
- Paragraphes du corps demandés dans le prompt ; trois cas supplémentaires dans le banc (quatorze sources × deux passages). Paragraphes comptés séparément des repères lexicaux. Une correction qui modifie le nombre de mots ne reçoit pas un faux score fondé sur les positions de l’ancienne source.
- Les générations libres restent privées jusqu’au retour complet et à la validation. Un texte retouché manuellement garde le contrôle strict de disposition. Aucun repli cloud automatique.

## Vérifications locales

Le brut exact du dernier mail fourni par Ullie est couvert par un test de régression : la sortie avec en local doit être acceptée, sans réintroduire la faute source en locale. Le test de session utilise un moteur simulé terminant après 9,1 s, pour vérifier le nouveau délai ; ce n’est pas une mesure de Gemma.

Les générations exploratoires sur ordinateur emploient LiteRT-LM CPU deux threads, MTP désactivé et thinking désactivé. Elles ne prédisent ni le résultat exact ni le délai GPU Android. Aucun téléphone connecté à Codex.

378 tests JVM dans 56 suites : zéro échec, erreur ou test ignoré. Régression sur le brut réel 0.8.4, restauration des omissions observées sur ordinateur, corrections FR/EN, protection des noms/quotes/nombres/négations/identifiants, rejet des ajouts et troncatures, formats distincts et génération simulée terminant après 9,1 s. Le contrôle des paragraphes distingue le corps de la salutation/signature.

APK prototype ARM64 construite ; dix bibliothèques natives conformes à l’alignement ELF/ZIP 16 Ko, signature et contrat du paquet vérifiés. Taille locale : 79 478 211 octets ; SHA-256 `246d6bded39a03793ab975f44bc68648bd9bb4880b4ef396f4a9a20b3d6922d0`. Aucun poids Gemma ajouté à l’APK. `git diff --check` réussi. Les tests instrumentés d’interface restent à exécuter sur Android.

## Essais du modèle sur ordinateur

[Sources, paramètres, prompts et sorties des trois étapes](benchmarks/local-format/gemma4-editing-085-host-2026-09-09.json). Quatorze générations CPU effectivement exécutées pendant le développement : six avec la première consigne, six avec la deuxième, deux textes avec la consigne finale de paragraphes. La dernière évaluation comprend six sources : quatre générations à prompt inchangé reprises de la deuxième étape et deux nouvelles générations de texte. Toutes les sorties finales ont été revérifiées avec les classes de l’APK finale, sans régénérer inutilement les mêmes réponses.

- Le brut GPU fourni par Ullie, anciennement rejeté pour locale → local, est accepté.
- La nouvelle génération CPU du mail utilisateur est acceptée après rétablissement de quatre unités lexicales omises ; la faute locale est corrigée et post-traitement reçoit son trait d’union. Le corps reste en un seul paragraphe : ce cas ne valide pas la subdivision thématique.
- Les textes corrigés FR et EN sont acceptés, avec répétitions retirées, accords/orthographe corrigés et deux paragraphes chacun.
- Le mail à trois sujets est accepté avec trois paragraphes dans le corps.
- Le mail anglais avec interdiction ajoute encore Karim à la fin ; cette signature inventée reste rejetée. La liste de courses peut encore rester groupée en une puce : mots conservés, découpage insuffisant.

Ainsi cinq réponses sur six passent les contrôles de modification dans cette évaluation exploratoire ; cela n’est pas un taux général de qualité. Les compteurs de paragraphes ne prouvent pas leur pertinence. Les durées CPU sont archivées uniquement comme mesures hôte ; le GPU Android et le ressenti avec la marge de 10 s restent à mesurer par Ullie.

## Limites de la correction

Il s’agit de corrections de surface reconnues, avec un budget de substitutions, et non d’une validation sémantique universelle. Les répétitions adjacentes sont limitées à quatre mots par expression ; le rétablissement d’omissions est limité à six unités et 5 % en mode correction, avec alignement unique (trois unités dans l’ancien mode strict). Le clitique c’ avant est peut être rétabli depuis la source. Les nombres équivalents pris en charge sont de petits cardinaux FR/EN ; dates, décimaux, identifiants et valeurs différentes ne sont pas convertis par cette étape. Les autres modifications continuent de conserver la transcription en cas de doute.

## Publication vérifiée

- Source : `dbe60a0d862bcbeab0449e05efb9647b35a48cdd`.
- [Actions 34345121225](https://github.com/Uhama91/DictAI/actions/runs/34345121225) : construction et publication réussies. Contrôles natifs, 18 tests Python, tests JVM, signature, alignement et contrat du paquet réussis.
- Artefact `dictai-local-layout-test`, ID `10101472179` ; [prerelease](https://github.com/Uhama91/DictAI/releases/tag/gemma-test-34345121225), [APK directe](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34345121225/dictai-local-layout-test.apk).
- Téléchargement intégral public HTTP 200 vérifié : **78 633 365 octets**, SHA-256 **f63c8cc3e09ce2156caabe858dbddfdeb3babd52cc8592021a8ec101be172780**. Empreinte identique au fichier SHA256SUMS, au journal Actions et au digest de l’asset GitHub.
- L’empreinte globale diffère de l’APK locale issue de constructions incrémentales. La comparaison de toutes les entrées ZIP confirme des noms et contenus strictement identiques, y compris chaque fichier DEX. Ne pas présenter les empreintes globales comme identiques.
- APK publiée contrôlée de nouveau : versionCode 28, versionName 0.8.5-wp-gemma-test, paquet com.uhama.whisperpin, ARM64, minSdk 30 ; dix bibliothèques natives, alignement 16 Ko et signature valides.

Installer en mise à jour conserve le fichier Gemma existant. La latence réelle de cette consigne et de la nouvelle marge sur téléphone reste à mesurer ; aucune nouvelle installation Android par Codex.
