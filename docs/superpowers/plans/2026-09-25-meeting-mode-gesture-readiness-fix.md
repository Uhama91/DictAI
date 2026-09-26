# Réunion — correction du menu et de la disponibilité des modèles

Rédaction, conception et arbitrage : Astra XHigh. Implémentation et tests : Luna 6 Max.
Date : 25 septembre 2026. Base : `1ed6dd942656cf70880eb5e37188216acc8e912a`.

## Constat et spécification complémentaire

Les captures du téléphone montrent un menu qui disparaît au relâchement du glissement et un panneau « Modèle indisponible » alors que les réglages affichent « Modèles prêts ». Elles montrent aussi deux pastilles. La coexistence des applications normale et de test est une explication possible, qui ne remplace pas le diagnostic des deux défauts.

Le menu commun des modes et des formats doit rester ouvert après un glissement vers le haut, court ou long. Ce geste ne sélectionne aucun mode ni aucun format. Le choix se fait ensuite par un toucher explicite. L’annulation du geste, le déplacement de la pastille par appui long et les gestes d’enregistrement conservent leurs règles.

La disponibilité affichée doit refléter le magasin de modèles actuel : vérification en cours, téléchargement, prêt ou absent/erreur. Une tentative refusée pendant la vérification ne doit pas figer l’affichage. Lorsque les modèles deviennent prêts, le démarrage redevient accessible, mais aucun téléchargement ni aucune fin de vérification ne doit démarrer le micro. Les erreurs réelles du moteur et les protections des réunions restaurées restent actives.

Cette spécification complémentaire remplace uniquement l’ancienne sélection continue d’un format pendant le glissement dans le menu commun. Les formats restent accessibles par toucher. Le modèle, ses fichiers et la reconnaissance des voix ne changent pas.

## Diagnostic établi par lecture du code

1. Dans `OverlayService`, le chemin Dictée utilise `FormatSwipeGesture`. Après l’ouverture, une suite normale de mouvements dépassant 24 dp arme une sélection ; `ACTION_UP` appelle `selectFormat`, qui ferme le menu. Les tests existants reproduisent surtout un seul mouvement d’ouverture et valident séparément cette ancienne sélection continue.
2. `openMeetingPanel` relance `MeetingModelStore.refresh`. Le magasin publie temporairement `Checking` pendant la vérification des fichiers. Un démarrage à cet instant produit la phase `MODEL_UNAVAILABLE` du contrôleur. Le listener actualise le rendu, mais le panneau et la pastille continuent à utiliser cette phase historique au lieu de la disponibilité actuelle. Le contrôleur autorise déjà une nouvelle tentative explicite depuis cette phase.
3. Les réglages et le service utilisent le même magasin partagé du processus et le même répertoire privé. Aucun processus distinct n’est déclaré dans le manifeste. Une reproduction contrôlée doit confirmer les deux premiers mécanismes avant toute correction.

## Lot 1 — reproductions RED

Responsable : Luna, avec attestation `gpt-6-luna, effort max`. Astra garde la recherche et les décisions de conception.

- Ajouter un test du listener réel de la pastille : Dictée, plusieurs mouvements vers le haut au-delà du seuil, relâchement, menu encore présent et touchable, mode et format inchangés, puis choix explicite Réunion.
- Couvrir aussi le geste rapide DOWN/UP et le retour Réunion vers Dictée. Conserver les contrôles d’annulation et de déplacement.
- Reproduire la disponibilité par un vrai `MeetingModelStore` de test utilisant des fichiers minuscules validés par le catalogue de test et un worker contrôlé : démarrage refusé pendant `Checking`, puis publication `Ready`. Vérifier le texte et les actions du panneau, la commande de la pastille et l’absence d’ouverture du micro avant une nouvelle action.
- Si le montage existant nécessite une simulation du port natif, la limiter à l’audio et au moteur. Ne pas remplacer le chemin de disponibilité que l’on cherche à tester.
- Ajouter une couverture des états absent, téléchargement, erreur native et document restauré pour éviter un faux « prêt ».
- Conserver les commandes, codes de sortie et échecs attendus dans le compte rendu. Un échec de compilation ne constitue pas la reproduction du défaut.

## Lot 2 — corrections minimales

Fichiers autorisés : `OverlayService.kt`, le helper de geste seulement si nécessaire, les tests correspondants et les tests Android d’intégration.

- Faire du geste du menu commun une ouverture seule : aucune validation ni prévisualisation d’un autre format par la trajectoire du doigt. Garder le format réellement choisi surligné et le menu touchable au relâchement.
- Pour les phases non démarrées `DOCUMENT` et `MODEL_UNAVAILABLE`, dériver le panneau et la commande de la pastille de la disponibilité courante. La phase historique reste une tentative refusée ; `start` reste le seul point d’admission du micro et du moteur. Éviter une nouvelle machine d’états parallèle.
- Ne pas convertir `ERROR`, une fermeture, une capture active ou une réunion restaurée en nouveau démarrage automatique. Ne pas contourner les validations des fichiers.
- Revoir les callbacks différés pour ne pas réafficher un instantané de disponibilité dépassé lorsqu’un état plus récent est déjà publié.
- Escalader à Astra si la reproduction indique une autre cause. Aucun changement des bibliothèques natives ou des poids n’est prévu.

## Lot 3 — validation et identité de livraison

Luna est seule opératrice de Gradle et ADB. Utiliser JDK 21 et notre AVD `DictAI_Meeting_API36_20260925`, sans effacer ses données. Aucun téléphone n’était connecté au diagnostic initial.

1. Observer GREEN sur les reproductions et les suites ciblées de gestes, de contrôleur et de lifecycle.
2. Exécuter les tests unitaires complets et `assembleDebug` pour les variantes normale et prototype, avec au maximum quatre workers.
3. Exécuter un parcours Android réel : glissement long depuis Dictée, relâchement, choix Réunion, vérification des modèles, état prêt, démarrage explicite puis pause. Ajouter des assertions du menu après relâchement ; ne pas se contenter d’invoquer directement les commandes.
4. Vérifier un parcours où la disponibilité passe de non prête à prête. Les captures utilisent uniquement des données synthétiques. Les captures personnelles fournies par l’utilisateur restent hors du dépôt.
5. Produire un aperçu Markdown avec captures natives, puis inspection visuelle par Astra. Rendre aussi ce plan pour l’audit documentaire, sans modifier son texte.
6. Distinguer la mise à jour par `versionCode = 36` uniquement pour le prototype et `versionName = 0.9.6-dictai-meeting-test2`. Conserver le package et la signature du prototype pour mettre à jour l’installation existante et conserver les modèles téléchargés.

## Lot 4 — revue et publication

Complexité : complexe par prudence, car la correction relie une interaction tactile à l’admission de l’enregistrement. Astra réalise deux passes distinctes sur le même instantané : conformité fonctionnelle/UX et protections, puis régressions et maintenabilité.

Après ces revues et les vérifications, Luna publie uniquement les fichiers du correctif sur `codex/meeting-mode`, sans fusion ni push forcé, avec le marqueur `[meeting-test]`. L’autorisation de livraison GitHub Actions est déjà donnée par l’utilisateur.

Attendre la fin de GitHub Actions, télécharger son APK et vérifier package, versions, signature, bibliothèques natives et SHA-256. Présenter le lien vers l’artefact exact et distinguer les preuves sur émulateur de la validation tactile encore à faire sur le Poco. Préserver les modifications des autres tâches et les notes existantes.

## Critères de clôture

- Les deux reproductions échouent sur la base et passent après correction.
- Le menu reste ouvert après les gestes courts et longs ; le toucher permet de changer de mode dans les deux sens.
- Un modèle validé ne reste pas affiché indisponible après une tentative prématurée ; le micro attend une action explicite.
- Les contrôles complets requis passent et les captures sont inspectées.
- Un nouvel APK GitHub Actions terminé et vérifié est disponible, avec une identité distincte de la version signalée.
