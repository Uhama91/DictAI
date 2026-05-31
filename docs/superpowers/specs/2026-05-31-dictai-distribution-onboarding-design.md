# DictAI — Distribution publique + Onboarding d'installation — Design

**Date :** 2026-05-31
**Objectif :** permettre à n'importe quel utilisateur Android de télécharger DictAI (sideload, hors Play Store) et de réussir son installation, malgré la friction OEM (Xiaomi/HyperOS surtout), via un onboarding in-app qui détecte et guide.

---

## Décisions validées

| Sujet | Choix |
|---|---|
| Public v1 | Large, sideload (hors Play Store) |
| Assistant d'installation | **Onboarding in-app** (principal) ; site minimal |
| Couverture appareils | Xiaomi-first + générique (étapes universelles partout, étapes Xiaomi détectées) |
| Hébergement APK | **GitHub Releases** (build par la CI, attaché à une release taguée) |
| Hébergement site | **Cloudflare Pages**, domaine `dictai.uhama.fr` |
| Mises à jour | Vérif + bandeau notif in-app (API GitHub), lien de téléchargement |
| Nom de package final | **`com.uhama.dictai`** (one-way door — gravé à la 1re release publique) |
| Signature | Clé release stable, stockée chiffrée en **secrets GitHub Actions** ; sauvegarde sécurisée obligatoire |

**Portes à sens unique :** une fois publié, `applicationId` et la clé de signature ne peuvent plus changer sans casser les mises à jour (désinstallation/réinstallation forcée des users). Sauvegarder le keystore release hors-CI (gestionnaire de secrets / coffre).

---

## Brique 1 — APK signé release + renommage package

- `applicationId` `com.uhama.whisperpin` → `com.uhama.dictai` (le `namespace` Kotlin reste `com.kafkasl.phonewhisper`, inchangé).
- Mettre à jour les constantes dépendantes : `ACTION_ARM_MIC = "com.uhama.dictai.ARM_MIC"`.
- Générer un keystore release (`dictai-release.jks`, alias dédié). **Ne jamais committer.**
- `signingConfigs { release { ... } }` lisant storeFile/passwords depuis variables d'env (`DICTAI_KEYSTORE_*`) ; `buildTypes.release.signingConfig = release`, `isMinifyEnabled = false` (v1, pas de proguard pour éviter de casser sherpa/kmp-ai).
- Bump `versionName` (ex. `1.0.0`) + `versionCode` (4).

## Brique 2 — CI GitHub Release

- Étendre `.github/workflows/build.yml` : sur tag `v*`, décoder le keystore (base64 en secret `DICTAI_KEYSTORE_B64`), injecter les mots de passe (secrets), `./gradlew assembleRelease`, puis créer une **GitHub Release** avec l'APK signé attaché (`softprops/action-gh-release`).
- Secrets requis : `DICTAI_KEYSTORE_B64`, `DICTAI_KEYSTORE_PASSWORD`, `DICTAI_KEY_ALIAS`, `DICTAI_KEY_PASSWORD`.
- Sortie : URL stable `https://github.com/Uhama91/DictAI/releases/latest/download/DictAI.apk`.

## Brique 3 — Site Cloudflare Pages (`dictai.uhama.fr`)

- Page statique unique (HTML/CSS, thème « carnet » vert/crème cohérent avec l'app) : pitch court, capture/loop animée, **bouton « Télécharger DictAI »** → release GitHub, section **permissions expliquées** (pourquoi micro/accessibilité/overlay — transparence, crédibilité sideload), mini-aperçu des étapes d'install (rassurer avant download).
- Déploiement `wrangler pages deploy` (projet Cloudflare dédié `dictai`). DNS CNAME `dictai` → pages.dev.

## Brique 4 — Onboarding in-app (cœur)

- `OnboardingActivity` affichée au 1er lancement si des étapes manquent ; re-déclenchable depuis les réglages (« Refaire la configuration »).
- Modèle `SetupStep(id, titre, explication, detect: (Context)->Boolean, action: (Activity)->Unit, oemOnly: Boolean)`.
- Re-vérification de tous les `detect` à chaque `onResume` → les lignes passent au vert automatiquement.
- Étapes (ordre) : 1) Micro (RECORD_AUDIO, runtime) · 2) Overlay (`canDrawOverlays`, ACTION_MANAGE_OVERLAY_PERMISSION) · 3) Paramètres restreints *(Xiaomi/A13+, inféré, instructions illustrées)* · 4) Accessibilité (services actifs, ACTION_ACCESSIBILITY_SETTINGS) · 5) « Interrompre si non utilisée » → off *(Xiaomi, instructions)* · 6) Batterie sans restriction (`isIgnoringBatteryOptimizations`, intent) · 7) Autostart *(Xiaomi, deep-link MIUI sinon instructions)* · 8) Notifications (A13+, runtime).
- Étapes Xiaomi (3, 5, 7) visibles **seulement** si `Build.MANUFACTURER`/`ro.miui.*` indiquent MIUI/HyperOS, sinon masquées → parcours court sur Pixel/Samsung pur.
- Réutilise les étapes réelles cartographiées sur Poco F7 (cf. `CLAUDE.md` du projet).
- Écran final « DictAI est prêt 🎉 » → démarre la pastille (OverlayService).

## Brique 5 — Vérif de mise à jour in-app

- Au lancement (throttlé ~1×/jour), GET `https://api.github.com/repos/Uhama91/DictAI/releases/latest`, comparer `tag_name`/versionCode au `BuildConfig.VERSION_CODE`.
- Si plus récent → bandeau discret « Mise à jour dispo » dans MainActivity avec bouton → page release. Pas de permission supplémentaire. Échec réseau = silencieux.

---

## Ordre de construction

1. **Brique 4 (Onboarding)** d'abord — pur code app, testable immédiatement sur device (flux APK debug actuel). C'est la valeur perçue.
2. **Brique 1 (signature + rename)** — fige `com.uhama.dictai` + keystore release.
3. **Brique 2 (CI Release)** — premier APK signé publié.
4. **Brique 3 (Site)** — pointe sur la release.
5. **Brique 5 (Update check)** — boucle la boucle.

## Tests

- Onboarding : matrice états permissions (accordée/refusée) → la checklist reflète l'état réel ; re-vérif `onResume` ; masquage OEM testé via simulation de marque. Validation device Poco F7 (HyperOS).
- Release : APK signé s'installe par-dessus une install `com.uhama.dictai` précédente (même clé) sans désinstallation.
- Update check : réponse API mockée (plus récent / identique / erreur réseau) → bandeau correct, jamais de crash.

## Risques

- **Perte clé release** = mises à jour impossibles → sauvegarde hors-CI obligatoire.
- **Accessibilité = permission scrutée** : messages de transparence clairs (site + onboarding) ; pas de log de contenu (déjà appliqué).
- Étapes Xiaomi non-détectables (3, 5, 7) reposent sur l'action manuelle de l'utilisateur → soigner les instructions illustrées.
