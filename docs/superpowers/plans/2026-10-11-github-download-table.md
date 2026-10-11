# Téléchargements DictAI sur la page du dépôt

Objectif : donner aux testeurs une seule adresse, `https://github.com/Uhama91/DictAI`, avec les installateurs à jour dès l’introduction. La présentation reprend le regroupement par plateforme de [RustDesk](https://github.com/rustdesk/rustdesk/releases), adapté aux trois appareils pris en charge.

Périmètre validé : téléphones et tablettes Android 11 ou plus récent, ARM64 ; Mac Apple silicon et Intel sous macOS 14 ou plus récent. Le téléphone et la tablette utilisent le même APK. Le site et l’achat d’un domaine restent prévus ultérieurement.

1. Ajouter des tests du renouvellement des liens : versions indépendantes, préversions signalées, fichiers absents ou incohérents refusés, anciennes versions ignorées, reste du README préservé.
2. Extraire les architectures des installateurs dans le générateur de manifeste existant. Conserver la séparation entre `updates-preview.json` et `updates.json`.
3. Créer un script qui lit les releases publiées, vérifie leur manifeste et les empreintes GitHub des installateurs, puis renouvelle uniquement le tableau balisé du README.
4. Installer un workflow GitHub Actions sur `main`, déclenché lors de la publication ou de la modification d’une release et disponible manuellement. La construction d’un APK seule ne change pas les liens distribués.
5. Joindre le manifeste de test à la préversion existante, publier la page d’accueil et vérifier une exécution réelle du workflow, les liens publics et le rendu du tableau sur ordinateur et téléphone.

Validation : tests Python ciblés, analyse du workflow, téléchargement anonyme des fichiers, audit visuel du README publié. Les installateurs existants ne changent pas ; une nouvelle validation vocale physique n’est pas nécessaire pour ce tableau.

## État au 11 octobre 2026

Les cinq étapes sont réalisées et publiées sur `main`. Douze tests ciblés réussissent ; la maintenance a créé un commit du README lors de son premier essai réel sur GitHub. Le rendu ordinateur et le rendu téléphone ont été inspectés, puis corrigés pour placer les téléchargements dans la partie immédiatement visible du tableau. [Rapport et images](../../2026-10-11-github-downloads.md).

Le manifeste de test accompagne la préversion existante sans activer le canal stable des notifications dans l’application. Le site et le domaine restent à réaliser ultérieurement.

À la demande de l’utilisateur, le logo de l’introduction reprend maintenant l’icône réelle de DictAI. La présentation de Phone Whisper comme projet d’origine et les références des licences se trouvent tout en bas du README.
