# DictAI macOS 0.4.5 — accès rapide et installation

L’icône DictAI de la barre des menus donne accès à la dernière transcription complète, à l’historique des dix dernières dictées, à la bibliothèque, aux réglages et à la fermeture complète de l’application. Pendant une dictée, le menu propose aussi la pause ou la reprise. La copie rapide est désactivée lorsque l’historique est vide et ne modifie ni les notes ni le brouillon.

Le tampon d’historique est local et borné à dix entrées. Il survit à une fermeture de l’application ; la onzième dictée remplace la plus ancienne. Il ne synchronise ni texte ni audio. Les notes enregistrées volontairement restent indépendantes.

## Vérifications

- 151 tests dans 22 suites réussis sur ARM ; 151 tests réussis sur Intel via Rosetta.
- Copie du texte intégral dans un vrai presse-papiers isolé : plusieurs paragraphes et Unicode, dernière dictée prioritaire sur le brouillon, bibliothèque intacte.
- Historique vide : contenu du presse-papiers préservé.
- Installateur universel ARM/Intel vérifié, signature locale valide, version 0.4.5 / build 15.
- Installation réelle dans `/Applications` après validation de l’authentification macOS par l’utilisateur.
- Treize fichiers de données identiques avant la première ouverture ; contenu intégral antérieur conservé après ouverture : 62 notes, 56 entrées de vocabulaire, 10 dossiers et 7 images. Identité de l’appareil et empreintes des images conservées. Sauvegarde privée des données et de l’ancienne application hors du dépôt.
- Microphone, reconnaissance vocale et accessibilité confirmés dans l’application installée après renouvellement des autorisations de la nouvelle signature locale.
- Dictées physiques de l’utilisateur insérées dans Codex : textes complets retrouvés dans le fichier local et dans la page d’historique de l’application installée, sans nouvelle note permanente.

Le paquet local installé est `DictAI-0.4.5-macOS-test.pkg`, SHA-256 `fc1deff8b518b127d11c1a45acb5d1cb6fb28ec5fe5485eb050e3741c88df65d`. Les captures de tests ci-dessous utilisent des données synthétiques ; aucune donnée personnelle de la bibliothèque n’est publiée.

## Aperçu natif de l’historique

![Historique macOS avec dix dictées synthétiques](research/recent-dictations/macos-dark.png)

## Validation physique restante

Les dictées physiques dans Codex et leur récupération dans l’historique sont confirmées. Le contrôle du menu installé et la relecture après fermeture sont en cours. L’éviction de la onzième dictée est couverte par les tests natifs. Le Poco et la tablette ne sont pas accessibles par ADB : leur installation et leur essai physique restent à effectuer.
