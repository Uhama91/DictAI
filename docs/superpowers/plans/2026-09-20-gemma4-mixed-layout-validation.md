# Validation des listes avec prose environnante

## Besoin et périmètre

Le probe JVM du20 septembre rejette les20 listes du parent Gemma4 évaluées, notamment parce que `GemmaFaithfulLayout` exige une puce sur chaque ligne. En modes TEXT et EMAIL, les puces nouvellement ajoutées échouent également au contrôle de ponctuation. Le besoin utilisateur comprend une introduction et une conclusion autour des listes, et des énumérations locales dans le texte corrigé.

Correction bornée dans le worktree `/Users/ulliemaillot/.codex/worktrees/gemma4-layout-validation/phone-whisper`. Classée complexe en raison de la protection du texte : TDD Luna Max et deux reviews principales distinctes sur le même état final. Aucun commit, push, APK ou changement de modèle pendant ce lot. L'entraînement en cours reste dans l'autre projet.

## Fichiers autorisés

- `GemmaFaithfulLayout.kt` et, si nécessaire pour séparer clairement le parsing, un petit helper de structure dédié dans le même package.
- Tests JVM correspondants, avec régressions via `LocalFormatRequest.acceptOutput` et `GemmaConservativeEditing`.
- Rapport de preuve et session log du worktree. Les sources `LocalFormatting.kt`, `GemmaConservativeEditing.kt`, prompts, moteurs, overlay et téléchargement ne changent pas.

## Contrat fonctionnel

Le modèle peut proposer des puces non numérotées `•`, `-` ou `*` uniquement en début de ligne avec un séparateur et un contenu non vide. Normaliser ces marqueurs de présentation vers `•` afin de ne pas afficher un nombre positif comme un nombre négatif. Une ligne en prose avant, entre ou après des éléments reste de la prose ; ne pas la convertir automatiquement en puce. Conserver l'ordre, les paragraphes et la projection des mots.

Le mode LIST exige toujours une véritable liste, sauf raccourci direct déjà validé ; une réponse entièrement en prose ne devient pas acceptable du seul fait de cette modification. TEXT et EMAIL autorisent des listes locales. Les listes numérotées ne sont pas ajoutées par ce lot : leurs nombres ne doivent pas être effacés comme s'il s'agissait de balisage.

Le parsing doit distinguer le balisage de présentation de la ponctuation porteuse d'information. Les signes de nombres, décimales, adresses, chemins, contractions, citations et marqueurs d'image restent protégés. Un tiret au milieu d'un mot ou une multiplication n'est pas une puce. Le contenu cité ne doit pas recevoir de nouveau balisage. Ne pas retirer arbitrairement les symboles présents dans la source, ni valider un marqueur vide.

Les contrôles lexicaux restent obligatoires après normalisation de la seule structure. Cette tâche n'autorise aucun nouveau mot, aucune suppression ou substitution de nom/nombre/négation, aucune nouvelle réparation de déterminant, conjonction ou date. Les réparations autorisées par `GemmaConservativeEditing` restent exactement celles existantes. Les réponses incomplètes, les modifications de mots et les permutations continuent d'être rejetées selon les mêmes règles. Aucun contrôle de présence de mots ne sera présenté comme preuve sémantique.

## Vérification

Avant modification, compiler et exécuter les tests ciblés existants sous JDK21. Ajouter des tests RED reproduisant : liste avec introduction/conclusion ; liste au milieu d'un texte corrigé avec phrase finale intacte ; mail avec salutation, liste et clôture ; puces normalisées ; conservation des lignes en prose. Ajouter les cas adverses de signes/numéros, coordonnées techniques, citations, marqueurs vides, mot ou négation manquants, terme protégé et changement de valeur. Vérifier aussi le rejet de prose seule en mode LIST et l'absence de nouvelle acceptation des listes numérotées inventées.

Après GREEN, exécuter les tests JVM pertinents et la suite Android unité si les ressources le permettent, sans second processus GPU. Limiter Gradle à2Go et utiliser le compilateur Kotlin en processus si nécessaire. Ne pas lancer d'assemblage natif ou d'émulateur pendant l'entraînement.

Enfin rejouer les120 réponses existantes du probe Android avec les classes de ce worktree, en préservant les résultats antérieurs et leurs sources. Écrire les nouveaux résultats dans `/Users/ulliemaillot/dev_project/asr-postclean-fr/reports/gemma4_v7/android_mixed_layout_probe/`, avec empreintes des sources/classes et avant/après par ID. Vérifier que les erreurs critiques précédemment bloquées restent bloquées ; toute nouvelle acceptation critique impose une analyse et une correction, sans affaiblir la grille. Une hausse d'acceptation n'est pas à elle seule un gain de qualité.

Escalader au principal si le contrat ne peut pas être respecté sans modifier les règles lexicales, les prompts ou le moteur. Les deux passes principales couvriront séparément le comportement attendu puis les scénarios adverses, la complexité et les régressions.
