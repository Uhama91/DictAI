# Téléchargements DictAI sur la page du dépôt

Objectif : donner aux testeurs une seule adresse, `https://github.com/Uhama91/DictAI`, avec les installateurs à jour dès l’introduction. La présentation reprend le regroupement par plateforme de [RustDesk](https://github.com/rustdesk/rustdesk/releases), adapté aux trois appareils pris en charge.

Périmètre validé : téléphones et tablettes Android 11 ou plus récent, ARM64 ; Mac Apple silicon et Intel sous macOS 14 ou plus récent. Le téléphone et la tablette utilisent le même APK. Le site et l’achat d’un domaine restent prévus ultérieurement.

1. Ajouter des tests du renouvellement des liens : versions indépendantes, préversions signalées, fichiers absents ou incohérents refusés, anciennes versions ignorées, reste du README préservé.
2. Extraire les architectures des installateurs dans le générateur de manifeste existant. Conserver la séparation entre `updates-preview.json` et `updates.json`.
3. Créer un script qui lit les releases publiées, vérifie leur manifeste et les empreintes GitHub des installateurs, puis renouvelle uniquement le tableau balisé du README.
4. Installer un workflow GitHub Actions sur `main`, déclenché lors de la publication ou de la modification d’une release et disponible manuellement. La construction d’un APK seule ne change pas les liens distribués.
5. Joindre le manifeste de test à la préversion existante, publier la page d’accueil et vérifier une exécution réelle du workflow, les liens publics et le rendu du tableau sur ordinateur et téléphone.

Validation : tests Python ciblés, analyse du workflow, téléchargement anonyme des fichiers, audit visuel du README publié. Les installateurs existants ne changent pas ; une nouvelle validation vocale physique n’est pas nécessaire pour ce tableau.
