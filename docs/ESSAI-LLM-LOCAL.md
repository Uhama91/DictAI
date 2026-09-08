# Essai du petit LLM local — DictAI 0.7.1

APK : `app/build/outputs/apk/verified/app-local-layout-test.apk` (version `0.7.1-wp-local-test`, ARM64, Android 11 ou ultérieur). Le modèle 350M est déjà inclus ; il n’y a pas d’autre téléchargement de LLM à faire. L’APK utilise le même identifiant et la même signature de développement que les builds de ce dépôt. La compilation normale est conservée à côté dans `app-normal.apk`.

## Mesurer le moteur sur le téléphone

1. Installer l’APK d’essai et ouvrir DictAI.
2. Toucher **Tester le modèle local**. Laisser la dictée au repos et garder cet écran ouvert jusqu’à la fin.
3. Le test exécute six exemples FR/EN : quatre appels au modèle, dont le premier avec chargement, et deux messages courts sans génération.
4. Toucher **Copier les résultats** après la fin. Le rapport contient l’appareil, le moteur CPU choisi, les temps et les textes synthétiques obtenus. Aucune dictée personnelle ni clé API n’y est incluse.

Ce test n’active pas automatiquement le moteur local. Il mesure chargement/premier fragment/fin de génération ; il ne mesure pas toute la chaîne ASR, l’affichage réel ou l’insertion. Les timings ordinateur ne remplacent pas ces résultats.

## Essayer dans une vraie dictée

1. **Moteur de post-traitement → Local — listes et mails (essai)**.
2. **Formats de post-traitement → Liste à puces** ou **Mail → Utiliser**. Le choix s’applique à la prochaine dictée. Le glissement vers le haut ouvre aussi les formats lorsque la pastille est au repos.
3. Activer **Afficher le texte pendant la dictée** pour voir la mise en page progressive. Sélectionner un champ destinataire, puis dicter normalement.
4. Essayer une liste courte, un mail déjà formulé avec sa salutation et un simple « OK, ça marche ». Juger l’attente après arrêt ainsi que la conservation des noms, nombres et négations. Tester d’abord au calme.

La copie du texte et son insertion dans le champ ne déclenchent pas l’envoi du message dans l’application destinataire. Pour revenir au comportement cloud : sélectionner **Cloud** avec la clé API déjà configurée.

## Périmètre réel

Le modèle choisit des puces et des paragraphes en conservant les mots source dans leur ordre. Il ne rédige pas un mail à partir d’une consigne telle que « écris à Julie pour lui dire… », n’enlève pas les hésitations et ne réalise pas les formats personnalisés. Le cloud reste disponible pour ces demandes.

Les dix générations du corpus élargi conservent tous les mots. La mise en page n’est pas toujours satisfaisante : en-tête attaché à une puce, groupe coupé en deux, listes avec citations laissées sur une ligne, fins de mail irrégulières. Les aperçus gardent toute la fin de la source, même avant que le modèle ne l’ait parcourue. En cas d’échec ou d’attente supplémentaire au-delà de cinq secondes après l’ASR, la transcription source est conservée. La latence téléphone reste à mesurer.

Les nombres et le vocabulaire personnel sont traités avant la mise en page. Les autres demandes (réactivité de l’écoute, envoi depuis la pause, modèles adaptés aux appareils) restent enregistrées dans [le suivi](SUIVI-DEVELOPPEMENT.md).
