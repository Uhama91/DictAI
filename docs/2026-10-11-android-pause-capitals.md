# DictAI Android 0.9.18 — majuscules après une pause

La correction des majuscules après une pause est portée sur Android. L’aperçu en direct et le texte final utilisent la même règle : « Je prends le temps de réfléchir Tu vois ce que je veux dire Je suis toujours dans la même phrase » devient « Je prends le temps de réfléchir tu vois ce que je veux dire je suis toujours dans la même phrase ».

Une durée de silence n’ajoute ni point ni frontière de phrase. La correction locale traite les continuations françaises courantes, les pronoms, les articles, les mots de liaison et les élisions. Les mots ambigus sont conservés par prudence. Les points, points d’interrogation, points d’exclamation et paragraphes produits par le moteur ou saisis par l’utilisateur conservent les vrais débuts de phrase.

Les noms du vocabulaire personnel, les sigles, les citations, les identifiants et les liens restent protégés. Le texte retouché au clavier reste intact ; seule la continuation reconnue peut être corrigée. La langue choisie au début de l’enregistrement est respectée : les règles françaises ne modifient pas la dictée anglaise. Aucun modèle de correction supplémentaire n’est nécessaire.

## Vérification

Quatre régressions ont été observées en échec avant la correction, avec des résultats vocaux contrôlés : pauses naturelles, reprise après retouche, adverbes/élisions et préfixe modifié au clavier. La suite complète passe ensuite avec 774 tests, sans échec ni test ignoré. Le service Android réel est couvert de l’aperçu jusqu’à la publication du texte et à son enregistrement dans l’historique. Les protections du vocabulaire et l’option de langue sont vérifiées à cette frontière.

L’APK 0.9.18-dictai, code 47, est construit avec l’identifiant et le certificat de test précédents. Ses neuf bibliothèques natives passent le contrôle ELF et ZIP à 16 Ko. Cinq essais instrumentés sur un émulateur Android ARM64 passent : insertion dans un champ externe vide ou déjà rempli, plusieurs pauses et reprise avec retouche. Le runner coupe le processus cible au démarrage ; le script `scripts/verify_pause_insertion_emulator.py` reconnecte donc le service après ce démarrage. Les premiers essais ont aussi nécessité de relancer System UI après une erreur ANR. Les parcours natifs ci-dessous utilisent des résultats ASR contrôlés ; ils ne constituent pas une validation acoustique d’une voix humaine sur Poco F7 ou Pad 7.

![Continuation française et vocabulaire protégé, thème clair](research/android-pause-capitals/renders/pause-phone-light.png)

![Continuation française et vocabulaire protégé, thème sombre](research/android-pause-capitals/renders/pause-phone-dark.png)

![Phrase continue réellement insérée dans un champ externe sur émulateur](research/android-pause-capitals/renders/android-sentence-inserted.png)

## Publication

La publication GitHub du nouvel APK et l’actualisation du tableau de téléchargement sont en cours. Le paquet Mac 0.4.9 de la préversion précédente sera repris sans changement d’octets.
