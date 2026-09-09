# Vérification DictAI 0.8.3 — suivi du texte et mails simples

Vérification locale du 9 septembre 2026, version `0.8.3-wp-gemma-test`, code 26.

- 357 tests JVM réussis dans 53 suites, zéro échec et zéro test ignoré.
- Régression du mail exact fourni à 10:08 : séparation salutation/corps/fermeture/signature, tous les mots conservés. Test FR/EN des chiffres, négations, noms et adresses ; les cas ambigus, citations et post-scriptum restent hors voie directe.
- Finalisation testée contre un ancien calcul qui ignore l’annulation : la voie directe termine sans attendre sa génération ; le diagnostic porte direct, applied et nativeStarted=false. Ce contrôle ne mesure pas la vitesse GPU.
- Nettoyage léger : hésitations, répétitions limitées, citations ouvertes/fermées, vocabulaire, identifiants, emphase/reflexifs, quelques questions explicites et idempotence.
- Politique de suivi : fin de correction, sélection, composition, toucher, reprise et changement de dictée. Un test instrumenté EditText/ScrollView vérifiant dernière ligne et curseur après redimensionnement est ajouté et compilé. **Non exécuté**, aucun Android connecté.
- APK application et APK de tests instrumentés construites. Signature, contenu et alignement ELF/ZIP 16 Ko vérifiés ; dix bibliothèques natives conformes.
- APK locale : 78 584 209 octets ; SHA-256 `3f068d7686e05ef87c3ed5e39a656684fda5eae576acdb6671a758944b9c807b`.
- `git diff --check` réussi. Java 21, SDK/Build Tools 35.0.0, Gradle 8.9, prototype Gemma.

Le suivi utilise un ScrollView après mise en page, indépendamment du focus conservé par le clavier. Il laisse le curseur en place et suspend le suivi pendant une correction active. La confirmation visuelle HyperOS reste à faire.

Les mails simples passent par des règles de disposition, puis le contrôle strict des mots de la source. Ce n’est pas une accélération du modèle. Les autres mails et les listes conservent Gemma, son prompt, GPU/MTP et thinking désactivé. L’attente finale maximale reste 5 s ; les cas hors voie directe peuvent encore la dépasser. Le banc comporte onze sources × deux passages et identifie le moteur par cas ; l’ancien mail long garde une passe Gemma seul par passage pour comparaison.

Le nettoyage du mode Texte est activé par défaut, désactivable, sans appel LLM, et n’est pas appliqué globalement aux dictées retouchées à la main. Il ne corrige pas le sens ni toutes les questions implicites. Le cloud reste un choix explicite avec clé API ; aucune bascule automatique.

## Publication

Construction et publication GitHub Actions à suivre après le commit de ces sources. Les mesures de latence téléphone de 0.8.3 restent à recevoir ; ne pas confondre les tests hôte et le banc Android.
