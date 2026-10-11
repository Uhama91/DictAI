# DictAI Mac 0.3.3 — Corriger tout en dictant

Version **0.3.3 installée** dans [Applications](/Users/ulliemaillot/Applications/DictAI.app). [Archive ZIP](/Users/ulliemaillot/Downloads/DictAI-macOS-0.3.3.zip). Les 12 notes, le vocabulaire et le brouillon sont conservés : les deux fichiers de données sont identiques avant et après installation. L’accessibilité est confirmée ; la validation des demandes audio macOS reste nécessaire pour le test vocal réel.

La petite fenêtre et la grande fenêtre partagent désormais le même éditeur natif. Les nouveaux mots arrivent à la fin du brouillon pendant que vous corrigez un passage précédent. Votre sélection reste à sa place.

## Reprendre le fil

Un clic dans le texte, une frappe ou un défilement suspend le suivi visuel. Après **8 secondes sans interaction**, la vue rejoint les nouveaux mots. Ce retour déplace la vue, sans déplacer le curseur ni remplacer la sélection. Si vous recommencez à écrire, la vue revient à votre point de correction.

Le menu sous le texte propose **5, 8 ou 15 secondes**, ainsi qu’un retour **manuel**. Le bouton **Suivre la dictée** permet de rejoindre immédiatement les nouveaux mots. Une sélection à la souris en cours ou une composition de texte empêche le retour automatique. Le minuteur ne fait pas défiler le texte pendant une pause de l’écoute.

![Correction d’un mot pendant que la dictée continue](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/follow-0.3.3-editing.png)

![Retour automatique au texte récent dans la petite fenêtre](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/follow-0.3.3-live.png)

![La grande fenêtre suit de nouveau les mots dictés](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/follow-0.3.3-window.png)

## Pourquoi huit secondes ?

La recherche n’a pas établi de délai universel pour un éditeur de dictée. Le W3C recommande de laisser à l’utilisateur le contrôle du contenu qui défile ou se met à jour automatiquement. Le seuil de cinq secondes cité dans cette recommandation n’est pas un délai conseillé pour reprendre le défilement après une retouche. **Huit secondes est ici un choix de départ ajustable**, à confronter à l’usage, et non une durée scientifiquement optimale. [W3C — Pause, Stop, Hide](https://www.w3.org/WAI/WCAG22/Understanding/pause-stop-hide.html).

AppKit permet de gérer séparément la sélection et la zone visible. L’éditeur conserve les positions en UTF-16 et applique seulement la différence de texte reçue, au lieu de remplacer entièrement le document à chaque résultat vocal. [Apple — selectedRanges](https://developer.apple.com/documentation/appkit/nstextview/selectedranges), [Apple — scrollRangeToVisible](https://developer.apple.com/documentation/appkit/nstext/scrollrangetovisible(_:)).

## Ponctuation et continuité

La ponctuation automatique d’Apple était déjà activée. Le correctif traite les raccords entre deux séquences vocales : « Il est possible » puis « De continuer » devient « Il est possible de continuer ». Les fins de phrase explicites, les paragraphes et les noms propres couverts par les tests sont conservés. Les nouvelles lignes arrivant après une retouche sont également conservées.

Cette correction reste prudente : elle ne reconstitue pas toute la grammaire d’un texte brut et ne garantit pas une ponctuation parfaite. Le moteur vocal reste responsable de la majorité des signes de ponctuation ; le mode de correction OpenRouter existant est distinct du texte brut. [Apple — addsPunctuation](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/addspunctuation).

## Vérifications

**81 tests réussis dans 12 suites**, compilation de production et signature vérifiées. Les tests couvrent les délais, le maintien du curseur et des sélections, les caractères Unicode, la saisie pendant l’arrivée de nouveaux mots, les changements de fenêtre, la conservation des paragraphes, les raccords de capitalisation et les parcours Option/Pause/Annuler existants. Un test héberge aussi réellement la vue SwiftUI pour vérifier qu’elle réagit aux nouveaux résultats vocaux.

L’aperçu natif utilise une bibliothèque temporaire et une dictée simulée. La retouche « jeudi » → « vendredi » est conservée pendant les ajouts. Dans la grande fenêtre, « Rassembler » reste sélectionné après le retour automatique en bas. Cette vérification d’interface ne constitue pas un test de reconnaissance au microphone ni d’insertion dans une autre application.

[Journal des tests](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/follow-0.3.3-tests.log) · [Compilation](/Users/ulliemaillot/dev_project/phone-whisper/desktop/macos/artifacts/follow-0.3.3-build.log)
