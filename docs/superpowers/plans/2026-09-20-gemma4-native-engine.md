# Moteur partagé pour les poids Gemma fine-tunés

Lot complexe, deux revues du principal sur les empreintes finales. L'implémentation appartient à Luna Max. Ce lot prépare le moteur sans modifier les téléchargements, les écrans, Gradle, les workflows ni le choix du moteur de production.

## Contrat

Créer un moteur CPU testable utilisant `LocalFormatNative` et le GGUF installé fourni par une dépendance injectée. Le service et le benchmark doivent pouvoir acquérir deux propriétaires d'un même cœur, avec une seule file et un seul modèle résident. La fermeture d'un propriétaire annule ses requêtes seulement ; la dernière fermeture libère le natif sur le worker, après le retour de l'inférence. Une acquisition pendant la fermeture différée doit conserver ou recharger proprement le cœur. Aucun chargement, fermeture native ou attente sur le thread UI.

L'API doit proposer les opérations déjà consommées sur `LocalFormatEngine` : état, nom du runtime, code d'échec fixe, durée de chargement, warm, préparation benchmark, backend et close. Utiliser une interface commune minimale si nécessaire ; éviter une copie du moteur GPU et ne pas modifier son comportement. Les dépendances natives et l'horloge doivent être substituables dans les tests. Le propriétaire est fermé de manière idempotente.

Les requêtes restent sérialisées. Chaque backend possède son époque d'annulation : annuler un backend ne doit jamais interrompre celui d'un autre propriétaire. L'enregistrement et l'entrée effective dans le natif doivent couvrir la course annulation/entrée ; une requête annulée ne peut pas revenir avec un résultat ni un chunk visible. La fermeture du dernier propriétaire ne libère pas un handle pendant son utilisation. Si le natif ne revient pas, refuser de créer un second modèle ou de poursuivre une file obsolète.

Le prompt utilise exclusivement `GemmaFineTunedPrompt.build` puis `buildNativeEnvelope`, mode TEXT/LIST/EMAIL issu de layoutKind. Ajouter à `LocalFormatRequest` deux champs de données optionnels en fin de constructeur : phase (FINAL par défaut) et contexte précédent (chaîne vide par défaut). Ils devront participer à l'égalité des requêtes pour empêcher une réutilisation entre deux phases/contexte différents. Ne pas modifier les règles textuelles gelées. Aucune grammaire, décodage `GemmaFineTunedGreedy`, contexte natif 4096, deux threads CPU. Conserver les budgets de sortie existants et le plafond total existant de 20 secondes pour ce pilote ; délai déjà passé en file ou au chargement déduit avant génération. Un résultat incomplet reste null, conformément au JNI. Le chargement a une attente bornée de 45 secondes pour les appelants ; un appel natif encore en cours garde son propriétaire mémoire jusqu'à son retour.

Conserver les retours directs déterministes et les validations existantes. Les préfixes libres ne deviennent pas du texte final accepté. Les chaînes contenant les délimiteurs spéciaux du tokenizer (dans source, contexte ou termes protégés) sont refusées avec repli brut avant l'appel natif, sans réécriture silencieuse de la dictée. Ne journaliser aucun prompt, texte produit ou message d'exception native. Un fichier absent retourne un diagnostic model_missing ; aucune récupération automatique cloud/GPU.

Le tokenizer exporté comporte 24 tokens spéciaux. Un garde simple refuse les séquences `<|` ou `|>`, ainsi que les cinq formes exactes `<pad>`, `<eos>`, `<bos>`, `<unk>` et `<mask>` ; cela couvre les 24 sans dépendre d'IDs. Cette vérification concerne les données interpolées, jamais l'enveloppe contrôlée construite par le helper.

## Vérification

Écrire et observer d'abord des échecs comportementaux pour prompt exact, phase/contexte dans l'identité, partage du chargement, budget restant, annulation avant entrée native, annulation pendant génération, deux propriétaires dont un ferme, dernière fermeture pendant génération, libération unique, et absence de chunks/résultats obsolètes. Utiliser barrières/latches et faux natif, sans modèle réel ni sleeps arbitraires. Les tests sont suivis de la suite Kotlin pertinente. Les changements éventuels dans le wrapper natif déjà accepté doivent être motivés et soumis de nouveau aux deux revues ; aucun changement C++ attendu.

La sélection réelle de ce moteur, son artefact téléchargeable, les libellés et les bibliothèques APK feront l'objet du lot d'intégration après revue de la génération GGUF. La correction progressive de segments reste un lot distinct qui utilisera les nouveaux champs phase/contexte.
