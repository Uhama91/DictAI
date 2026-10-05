# Comptes et synchronisation des appareils DictAI

La branche `codex/device-preferences-sync` ajoute des comptes hébergés pour le produit DictAI. La connexion Google passe par Supabase Auth ; PostgreSQL héberge les préférences de chaque utilisateur. Une sauvegarde privée dans Drive ne constitue pas l’architecture retenue.

## Comportement

- Un utilisateur connecte son téléphone et sa tablette au même compte Google.
- Les corrections de vocabulaire, les préférences communes, les formats personnels, les dossiers, les notes et leurs images se retrouvent sur les deux appareils.
- Les modifications locales restent utilisables hors ligne. Les ajouts indépendants sont fusionnés, les suppressions conservées. Deux modifications de la même entrée sont départagées par une horloge logique et l’identité de l’appareil.
- Chaque appareil écrit sa propre réplique versionnée. Une politique PostgreSQL RLS lie toutes les lectures et écritures à `auth.uid()`.
- Une synchronisation se lance après une modification, à l’ouverture et périodiquement avec une connexion réseau. Android peut retarder le travail en arrière-plan.
- La déconnexion conserve les préférences locales. Elle arrête les échanges et retire la session locale, sans supprimer les données hébergées.

## Périmètre des données

Inclure le vocabulaire et ses corrections, la langue, l’écriture des nombres, le nettoyage léger, l’espace final, l’affichage de la transcription, le thème, le moteur cloud ou désactivé, le choix de modèle cloud, les formats personnels et leur sélection.

Inclure les dossiers et notes enregistrées : titres, texte, rattachement au dossier, ordre et métadonnées des images. Les originaux JPEG sont transférés dans un bucket privé, identifiés par SHA-256 ; les miniatures sont régénérées sur chaque appareil. Les éditions concurrentes de notes vivantes possèdent des copies déterministes. Une note en cours d’édition garde son texte local ; une suppression distante détache son brouillon vers une copie conservée.

Conserver localement les clés API, les modèles téléchargés et leur sélection, les permissions, l’installation, la géométrie de la pastille, les diagnostics, les captures en attente et les enregistrements audio. Le produit ne demande jamais une clé `service_role` dans l’APK.

## Authentification et confidentialité

Utiliser OAuth avec PKCE S256 dans le navigateur système. Le code de retour ne peut être échangé qu’avec le vérificateur conservé sur l’appareil. Le compte est identifié par l’UUID Supabase retourné par Auth. Les jetons de session sont chiffrés au repos avec AndroidKeyStore ; aucun jeton n’est journalisé.

Le transfert utilise HTTPS. Les données sont hébergées chez Supabase ; cette version ne promet pas de chiffrement de bout en bout. Les sessions sont séparées par backend pour éviter d’envoyer un ancien jeton à un nouvel hébergeur. Une connexion réelle demande un projet Supabase, les migrations SQL, un fournisseur Google configuré et les deux valeurs publiques de configuration du build.

## Garanties de stockage

L’état de fusion est conservé avant tout accusé de réception réseau. Une importation possède un journal de reprise, une sauvegarde de l’état précédent et une validation complète avant écriture. Les originaux et miniatures des notes vivantes sont présents avant import ; les originaux sont envoyés avant publication d’une note. Les schémas inconnus et les contenus trop volumineux interrompent la synchronisation en conservant les données locales. Une édition ouverte du vocabulaire sauvegarde la différence depuis son ouverture pour préserver les autres entrées reçues entre-temps.

## Validation attendue

Tests de convergence entre deux appareils, suppressions sans résurrection, modification durant un échange, restauration du journal, exclusion des secrets, OAuth PKCE et renouvellement de session, erreurs réseau et limites de pagination. Vérifier également RLS entre deux UUID utilisateurs et les rendus Android clair, sombre et grandes polices. Distinguer ces preuves de la validation réelle sur Poco F7 et Pad 7.
