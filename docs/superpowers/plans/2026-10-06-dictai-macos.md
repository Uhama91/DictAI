# DictAI macOS — plan d’implémentation

**Objectif :** une application native macOS, déclenchée par un appui isolé sur Option, avec transcription locale, correction dans un panneau flottant et bibliothèque synchronisée avec Android.

**Architecture :** SwiftUI et AppKit ; un module `DictAICore` indépendant de l’interface porte le protocole Android v1, la fusion causale, le vocabulaire et la détection du raccourci. Les services natifs gèrent le micro, Speech, l’accessibilité, le Trousseau et Supabase. Stockage atomique dans Application Support, séparé par compte ; utilisation sans compte conservée.

**Périmètre confirmé :** appui pour démarrer/arrêter ; dossiers et notes DictAI uniquement. macOS 14 minimum. Le Mac dispose de Command Line Tools Swift utilisables ; Xcode réclame une acceptation de licence, qui ne sera pas automatisée.

## Livrables et vérification

- [x] `desktop/macos/Package.swift`, `Sources/DictAICore` et `Tests/DictAICoreTests` : tests des tombstones, conflits, horloges, notes canoniques Android, vocabulaire, raccourcis composés et modifications humaines pendant la transcription.
- [x] `Sources/DictAI/Services` : transcription Apple strictement locale, touche Option isolée, capture de la cible avant l’overlay, insertion contrôlée, connexion Google PKCE et stockage des secrets dans le Trousseau.
- [x] `Sources/DictAI/Sync` : même table Supabase et même contrat que l’APK ; pagination complète, téléchargement des images vérifié, conservation des conflits et isolation entre comptes.
- [x] `Sources/DictAI/Views` : bibliothèque, recherche, dossiers, notes éditables, vocabulaire avec règles de remplacement, préférences, overlay. Dossiers imbriqués représentés par des chemins de noms compatibles avec Android, sans changement de schéma distant.
- [x] `scripts/build-app.sh`, `Resources/Info.plist`, documentation et archive `.app` : compilation, tests, lancement réel et captures du rendu natif.

## Situations à couvrir

1. Option utilisée avec une autre touche ne doit pas démarrer le micro ; la fermeture du panneau arrête la capture.
2. Une retouche humaine ne doit pas disparaître lors d’un résultat ASR ou d’un retour de synchronisation.
3. Un élément supprimé à distance ne doit pas réapparaître ; une modification concurrente doit conserver une copie visible.
4. Une connexion ou une déconnexion pendant une requête ne doit jamais publier les données d’un compte dans un autre.
5. Un refus du micro ou de l’accessibilité, un réseau indisponible et une image manquante doivent laisser les données locales utilisables.

## Sources étudiées

- [Handy](https://github.com/cjpais/Handy) : raccourci global, transcription locale, cycle capture puis insertion.
- [FluidVoice](https://github.com/altic-dev/FluidVoice) : intégration macOS native, panneau flottant, cible d’insertion.
- Contrat de référence : `PreferenceSyncDocument.kt`, `AccountNotesSyncStore.kt`, `SupabaseAccountAuth.kt` et `SupabasePreferenceTransport.kt` du dépôt Android.

Implémentation directe dans cette session, dans un nouveau sous-projet ; le code Android et les données réelles sont préservés. Validation matérielle du micro et connexion au compte personnel signalées séparément des tests automatisés.

## Résultat observé

30 tests automatisés réussis ; compilation release et signature locale vérifiées. Bibliothèque, classement, vocabulaire et panneau éditable contrôlés dans une bibliothèque temporaire ; texte du panneau enregistré en note. Captures et limites détaillées dans `desktop/macos/walkthrough.md`. Les tests du microphone, du raccourci global et de l’insertion dans un autre logiciel nécessitent les autorisations macOS ; aucune autorisation n’a été accordée. La connexion au compte personnel et la convergence réelle avec Android restent à vérifier. Le workflow GitHub Actions est préparé, sans publication distante.
