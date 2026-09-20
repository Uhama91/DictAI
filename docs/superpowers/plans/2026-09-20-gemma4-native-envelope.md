# Enveloppe native du prompt Gemma V6

Lot moyen, Luna Max, une revue finale. Prolonger uniquement le helper pur `GemmaFineTunedPrompt` et ses tests/fixtures. Ne pas brancher de moteur ou modifier le téléchargement, la validation, le JNI, les options utilisateur ou la consigne française gelée.

La preuve `asr-postclean-fr/reports/gemma4_v7/native_prompt_parity/attempt-1/report.json` (SHA `3682ac3818de99a64b9de4a6ce0afb4a897f0371a748a52f75920fb5e4bcba86`) établit six égalités complètes d'IDs entre le vrai template HF et llama-tokenize. Ajouter une entrée clairement nommée qui compose le USER existant avec l'enveloppe exacte : `<bos><|turn>user\n` + USER + `<turn|>\n<|turn>model\n`. Aucune espace ou nouvelle ligne finale supplémentaire, aucun message système, aucun marqueur de raisonnement, aucun deuxième BOS.

Conserver les six fixtures USER existantes immuables. Ajouter des fixtures distinctes du prompt complet, dérivées directement des fichiers de preuve HF présents, avec empreintes/provenance. Vérifier les octets UTF-8 pour les six couples mode/phase, y compris les caractères de contrôle et Unicode déjà couverts. TDD : test rouge avant ajout de l'entrée, puis test vert ; exécuter les tests JVM nécessaires avec JDK 21 et compiler l'APK debug local pour vérifier la compilation. L'APK de cette étape ne contient aucun nouveau poids et n'est pas le livrable demandé par l'utilisateur. Aucun commit/push/Actions à ce stade.

Le JNI futur devra reconnaître les tokens spéciaux et éviter l'ajout automatique d'un BOS. Le pilote tokenizer l'a testé, mais ce lot ne modifie pas la production. Le comportement face à des chaînes ressemblant à des tokens spéciaux dans une dictée restera à vérifier lors de l'intégration.
