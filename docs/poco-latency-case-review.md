# Lire les six essais de latence

Cette grille accompagne le banc synthétique de la version 0.9.9. Elle permet de comparer les réponses obtenues sur Poco sans imposer une formulation unique. Ce petit lot sert à choisir la prochaine expérience ; il ne mesure pas un taux de réussite général.

Corpus de référence : `LocalLatencyBenchmarkCases.kt`, SHA-256 `ae969c80e0221bf57333e5c3445508d803662b7233fedd97f940cf2d3bd2365e`. Les trois passages d'un même texte sont des répétitions de mesure, pas trois situations indépendantes.

## Classement humain

- **Utilisable** : message fidèle, lisible et prêt à employer ; les défauts ciblés sont nettoyés.
- **Petites retouches** : une ou deux corrections locales suffisent, sans avoir à reconstruire une phrase ou à vérifier tout le message. Une préférence de style ne constitue pas un échec.
- **À refaire** : hésitations ou répétitions encore gênantes, syntaxe dégradée, mise en forme qui demande une reprise importante.
- **Erreur de sens** : information inventée, supprimée ou déplacée ; nom, nombre, date, négation, condition ou consigne modifiés. Cette catégorie prime sur la fluidité.

Le statut « accepté » de l'application est un contrôle automatique distinct de ce classement. Une sortie peut être acceptée et rester mauvaise ; une bonne sortie peut être rejetée. Lire séparément le brut du modèle et le texte que l'application aurait appliqué.

## Points à contrôler

| Cas | Correction attendue | Informations ou comportements à préserver |
|---|---|---|
| 1 — hésitations courtes | Retirer « euh », réparer « le la », garder mercredi après l'autocorrection mardi → mercredi, simplifier la conjonction abandonnée. | Version signée, trois pages annexes, interdiction de supprimer avant midi. Ne pas inventer le rapport entre « dossier » et « version ». |
| 2 — texte court propre | Conserver une prose claire ; une réponse inchangée est correcte. | Neuf heures, salle bleue, trois participants, dossier complet, présence jusqu'à midi. |
| 3 — date corrigée | Garder le rendez-vous du mercredi quinze octobre après la reprise. | La visite de jeudi est une information distincte : ne pas l'aligner sur mercredi. Préserver Nadia, Karim, entrée nord, bibliothèque, reçu, plan, numéro du chauffeur et condition d'arrivée en avance. |
| 4 — énumérations dans le texte | Rendre lisibles les tâches avant la sortie et après le retour. Des puces locales sont utiles, en conservant la prose qui les entoure. | Vingt-quatre élèves ; vendredi, mercredi et lundi ont des rôles distincts ; départ à huit heures, fin avant seize heures ; ne pas changer le rendez-vous. Ne pas transformer chaque phrase en tâche isolée. |
| 5 — dictée longue avec reprises | Retirer « euh », « les les » et « et et » ; rendre les étapes faciles à suivre. | Conserver l'insistance « Je répète », les trois noms, salle deux, douze béchers/résultats, cinq minutes, dix heures, interdictions et conditions. Ne pas déplacer la boîte rouge et ne pas utiliser le secours sans prévenir sont deux contraintes distinctes. |
| 6 — texte long propre | Garder le contenu et son ordre logique. Paragraphes possibles ; réécriture inutile à éviter. | Horaires, mardi au samedi, dimanche fermé au public mais accès technique, réservation des groupes une semaine avant, protection des œuvres et accessibilité. Ne pas convertir toute la description en liste d'actions. |

## Associer qualité et délai

Pour chaque configuration, relever les six classements humains, les différences entre passages et les temps du rapport. Présenter ensemble le nombre de sorties utilisables ou demandant de petites retouches, les erreurs de sens, les rejets, les absences, la médiane et le maximum d'attente. Un délai court obtenu par retour au texte source après échec ne démontre pas une correction rapide.

La comparaison Gemma 3/Gemma 4 utilisera les mêmes textes et les mêmes critères, avec les consignes propres à chaque pilote identifiées. Ce document ne contient encore aucun résultat Poco et ne qualifie aucun modèle.
