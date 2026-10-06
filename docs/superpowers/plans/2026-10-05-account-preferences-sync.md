# Account Preferences Sync Implementation Plan

> **For agentic workers:** Implement and review each independently testable component. Steps use checkbox syntax for tracking.

**Goal:** Relier les préférences, dossiers, notes et images du téléphone et de la tablette à un compte hébergé DictAI, facultatif.

**Architecture:** Supabase Auth Google OAuth PKCE et PostgreSQL RLS. Chaque appareil publie une réplique indépendante ; un document versionné fusionne les entrées et conserve les suppressions.

**Tech Stack:** Kotlin Android API 30–34, OkHttp 4.12, WorkManager 2.9.1, Supabase Auth et PostgREST.

**Spec:** `docs/superpowers/specs/2026-10-05-account-preferences-sync.md`.

## Global Constraints

- Conserver les identifiants Android existants et les fichiers personnels déjà présents dans le checkout.
- Ne jamais synchroniser les clés API, les enregistrements audio, les captures en attente ou les paramètres propres au matériel.
- Synchroniser les notes enregistrées, dossiers et originaux JPEG, avec validation avant import et protection de l’éditeur actif.
- Utiliser uniquement une clé publique Supabase dans l’APK et HTTPS en production.
- Ne jamais armer le microphone depuis un travail de synchronisation.
- Refuser une version de document inconnue ; conserver les données locales lors d’une erreur.

## Review Focus

- Deux ajouts hors ligne doivent converger sans écrasement d’un vocabulaire entier.
- Une suppression doit survivre au retour d’un ancien appareil.
- Une modification reçue pendant l’édition du vocabulaire doit être conservée.
- Une session déconnectée ou remplacée ne doit recevoir aucun résultat d’un ancien échange.
- Un utilisateur doit être incapable de lire ou modifier les répliques d’un autre UUID.

## Tasks

1. [x] Document de fusion : `PreferenceSyncDocument.kt` et tests d’ajouts, suppressions, convergence et limites. Commencer par un test qui exige la présence des deux ajouts après `left.merge(right)`.
2. [x] Stockage Android : `PreferenceSyncStore.kt` et tests de liste autorisée, reprise après interruption et compte distinct. Vérifier qu’un import ne modifie jamais `credential_openrouter` ni `btn_x`.
3. [x] Transport : `SupabasePreferenceTransport.kt` et tests HTTP avec MockWebServer. Vérifier la pagination complète, les UUID, les limites et l’absence de jetons dans les URL.
4. [x] Compte : client Auth PKCE, session chiffrée et tests du retour OAuth, du renouvellement et des erreurs. Un code reçu sans vérificateur doit être refusé avant tout HTTP.
5. [x] Coordination et interface : observation des préférences, WorkManager, écran de compte et intégration aux préférences. Tester le refus des réponses après déconnexion, puis produire et inspecter les rendus natifs.
6. [x] Hébergement : migrations PostgreSQL, test RLS à deux utilisateurs et configuration Google/Supabase. Client Web créé, scopes d’identité déclarés et fournisseur Google actif ; redirection OAuth PKCE vers Google vérifiée.
7. [x] Vérification finale : suite JVM/Robolectric complète, APK, revue indépendante et compte rendu avec captures. Indiquer explicitement l’état du déploiement et des essais physiques.

## État de livraison

714 tests JVM/Robolectric et APK du 6 octobre 2026 vérifiés. Les deux migrations et le bucket privé sont déployés sur le projet dédié Paris, avec isolation réelle entre deux sessions Auth. Le client Google et son fournisseur Supabase sont actifs ; les scopes se limitent à l’identité. La redirection OAuth PKCE vers le client et le retour attendus est vérifiée. Le retour de connexion dans l’APK et les essais physiques sur deux appareils restent à réaliser.
