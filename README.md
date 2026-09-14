<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="DictAI logo">
</p>

# DictAI

Local Android dictation with a floating overlay.

DictAI lets you dictate into most apps without switching keyboards. Tap the
floating button, speak, and edit the transcript before inserting it into the
currently focused text field when the app exposes a standard Android input
field.

It provides:

- **Local on-device transcription** with sherpa-onnx or transcribe.cpp
- **Optional cloud cleanup** through OpenRouter for punctuation, formatting,
  and conservative corrections
- A persistent overlay, accessibility insertion, local notes, and PDF/HTML
  export

DictAI is a modified derivative of [Phone Whisper by kafkasl](https://github.com/kafkasl/phone-whisper).
See the repository [NOTICE](NOTICE), [LICENSE](LICENSE), and [bundled third-party notices](app/src/main/assets/THIRD_PARTY_NOTICES.txt)
for provenance and license terms.


## Install

### Download the APK

Download the latest build from [DictAI releases](https://github.com/Uhama91/DictAI/releases).

Open the APK on your phone, install it, and launch DictAI once to complete the
initial setup.

### Build from source

Requires JDK 21 and the Android SDK. Set `JAVA_HOME` to a JDK 21 installation
before running `make`. On macOS with Android Studio, for example:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

```bash
git clone https://github.com/Uhama91/DictAI.git && cd DictAI
make build
```

The debug APK is written to:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

For an ADB install:

```bash
make adb-install
```

## How it works

1. DictAI displays a small overlay on top of your apps.
2. Tap the overlay to start recording and tap again to finish.
3. The selected local model transcribes the audio on the device.
4. If enabled, cloud cleanup sends the transcript, formatting instructions, and
   any configured vocabulary or protected spellings to OpenRouter; audio stays
   on the device.
5. DictAI keeps the transcript available for editing during the dictation and
   pause flows, then, when you finish, inserts it into the focused field when
   possible and keeps a clipboard copy as a fallback.

## Setup

On first launch:

1. Open **DictAI**.
2. Grant the **microphone** permission and allow the overlay.
3. Enable the **Accessibility Service** so DictAI can insert text into another
   app's focused field.
4. Download and select a local transcription model.
5. Optionally enable cloud cleanup and enter your own OpenRouter API key.

The floating overlay is ready after setup.

## Why does DictAI need Accessibility?

DictAI uses Android Accessibility Service to identify the focused text field and
insert dictated text after you explicitly interact with the overlay. It can
also capture a screenshot when you explicitly request the note-capture feature.

It does not replace your keyboard, monitor browsing, collect screen content for
analytics, or run background automation.

## Privacy

DictAI transcribes audio locally on the device. Audio is not sent to a server by
the app's normal transcription path.

Cloud cleanup is optional. When enabled, DictAI sends the transcript text, your
selected formatting instructions, and any configured vocabulary or protected
spellings directly from the device to OpenRouter, using your own API key. Audio
is not sent for this cleanup step.

DictAI has no project backend, accounts, analytics, or uploaded-recording
collection. See the full [privacy policy](PRIVACY.md).


## Local models

Models are stored in app storage under:

```bash
/data/data/com.uhama.whisperpin/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Nemotron 3.5 Handy (FR/EN) | 751 MB | Recommended; transcribe.cpp |
| Parakeet 0.6B (FR/EN) | 465 MB | sherpa-onnx transducer |
| Nemotron 3.5 Live (FR/EN) | 453 MB | Experimental streaming model |
| Nemotron 3.5 Compact (FR/EN) | 621 MB | Compact transcribe.cpp model |

Models are downloaded to the app's private storage. The catalog includes
sherpa-onnx archives and pinned GGUF downloads from the Handy Computer model
repository.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

DictAI works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, DictAI copies the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use DictAI in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Optional cloud cleanup requires your own OpenRouter API key

## License and attribution

The Phone Whisper-derived source is distributed under the Apache License 2.0;
see [LICENSE](LICENSE) and [NOTICE](NOTICE). DictAI changes are identified in
the modified inherited files. Bundled native libraries and models may use
separate terms; see [THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt)
and the license texts in [app/src/main/assets/licenses](app/src/main/assets/licenses).

## DictAI 0.6 — édition et formats

- Le résultat final est copié dans le presse-papiers, même si l’insertion réussit ou si aucun champ n’est sélectionné.
- Pendant une dictée en streaming, toucher le texte ouvre le clavier. Les corrections manuelles restent présentes pendant l’arrivée des mots suivants et dans le résultat final. Une ponctuation finale ou un retour à la ligne commence la suite par une majuscule.
- Un tap déclenche la dictée. Maintenir la pastille immobile 400 ms jusqu’à la vibration autorise son déplacement dans toutes les directions ; relâcher mémorise sa position. Cet appui maintenu remplace l’ancien push-to-talk. Un glissement direct vers le haut ouvre les formats au relâchement, sans déplacer la pastille : au moins 56 dp et une distance verticale au moins double de l’horizontale, sans limite de vitesse une fois le mouvement commencé.
- Dans **Formats de post-traitement**, choisir Texte corrigé, Liste à puces ou Mail. Créer, modifier ou supprimer des formats personnels avec un nom et des consignes. Le choix est mémorisé.
- Dans **Moteur de post-traitement**, la version normale propose **Cloud** ou **Désactivé** ; Cloud nécessite une clé OpenRouter. L’APK d’essai **0.7.1-wp-local-test** ajoute **Local**, limité aux listes et aux paragraphes de mail avec un modèle inclus. Les formats personnels et Texte corrigé nécessitent le cloud. Le choix cloud existant est conservé lors d’une mise à jour. En cas d’indisponibilité, le texte reste récupérable et un message signale l’absence de mise en forme.

### Vérification sur téléphone / tablette

1. Dicter dans un champ sélectionné, puis coller ailleurs : les deux textes doivent être identiques.
2. Dicter sans champ sélectionné : récupérer le résultat avec Coller.
3. Corriger un nom, ajouter un point puis continuer à parler : vérifier la conservation de la correction, la majuscule et la position du curseur. Tester aussi une sélection, une suppression et le clavier en paysage.
4. Maintenir jusqu’à la vibration, puis déplacer dans les quatre directions : aucun format ni enregistrement ne doit se déclencher. Glisser directement vers le haut, vite ou lentement : la pastille reste fixe et le menu s’ouvre au relâchement. Revenir sous le seuil annule le raccourci. Tester aussi un maintien sans déplacement, les diagonales, les quatre bords, la rotation et une interruption tactile.
5. Créer un format personnalisé, relancer l’application, le modifier puis le supprimer. Tester sans réseau et sans clé : le texte doit rester récupérable.

L’APK de chaque branche est disponible dans **Actions → Build DictAI APK → Artifacts → dictai-debug-apk** une fois la compilation réussie.

Le défilement suit les dernières lignes tant qu’aucune correction volontaire n’est en cours ; le focus automatique d’Android ne suspend pas ce suivi. Pendant une correction, le curseur et le point de lecture sont conservés. Retour ou reprise du micro libèrent l’éditeur et permettent de suivre les nouveaux mots. Le bouton Modifier agrandit le panneau et ouvre le clavier ; un toucher direct place le curseur avant son ouverture.

## Pause et reprise de la dictée

Pendant une dictée déjà en cours, un glissement direct vers le bas sur la pastille met en pause au relâchement (56 dp minimum, mouvement principalement vertical). La pastille reste fixe. Pour la déplacer, maintenir d’abord 400 ms jusqu’à la vibration. Un maintien seul ne termine pas la dictée.

La pause coupe et libère le microphone, fige les mises à jour automatiques et garde le champ visible. Le symbole « Ⅱ » indique la pause. Le texte peut être corrigé, complété au clavier ou effacé. Un tap reprend la même session et conserve les ajouts. Pour un message, le tap suivant termine la dictée et tente l’insertion dans le champ de l’application, avec copie selon la disponibilité du champ. Pour une note ouverte volontairement, le tap suivant remet en pause : l’insertion exige le bouton Insérer… puis confirmation.

Vérifier sur **Xiaomi Pad 7** et **Poco F7**, avec le micro intégré puis les écouteurs OnePlus :

1. Dicter une phrase, descendre brièvement sur la pastille, attendre le symbole de pause. Vérifier que le microphone n’est plus utilisé.
2. Parler pendant la pause : ces paroles ne doivent apparaître ni dans l’aperçu ni dans le résultat final.
3. Corriger un mot et ajouter plusieurs lignes au clavier pendant la pause ; déplacer le curseur vers le début.
4. Appuyer une fois pour reprendre : texte conservé, curseur et vue revenus en bas. Continuer à parler : les nouvelles lignes restent visibles.
5. Appuyer à nouveau pour terminer : vérifier les ajouts manuels et les deux portions dictées dans le presse-papiers et le champ cible.
6. Répéter plusieurs pauses ; tester une reprise immédiate pendant la fermeture du micro, une interruption du Bluetooth, la rotation, le double tap d’annulation et l’arrêt du service pendant la pause.

Le texte est sauvegardé localement pendant la dictée et les corrections. Après destruction du service, il est restauré en pause ; la reprise ouvre une nouvelle session audio et ajoute la suite au brouillon protégé. L’audio de l’ancienne session n’est pas conservé. Le brouillon est supprimé après la fin ou l’annulation de la dictée.

Les gestes de format et de pause affichent une indication progressive sur la pastille et un retour haptique au seuil. Ils ne déplacent jamais le bouton. Le maintien immobile jusqu’à la vibration affiche « Déplacer » et réserve tout le mouvement suivant au positionnement.

Dès le démarrage du micro, le champ de transcription apparaît avec « Écoute en cours… », sans attendre les premiers mots du modèle. Il est déjà éditable. Les lectures audio sont limitées à 20 ms par bloc ; le tampon matériel conserve sa taille minimale requise. Le délai de reconnaissance dépend toujours du modèle et de l’appareil.

### Panneau de texte et brouillon (0.6.5)

- « Agrandir / Réduire » passe de trois lignes à un panneau pouvant occuper 82 % de la hauteur disponible, ajusté au bord de la pastille et au clavier.
- « Masquer » cache le texte et le clavier sans arrêter la dictée. Glisser vers le haut pendant une dictée ou une pause réaffiche le panneau.
- Le réglage « Afficher le texte pendant la dictée » définit le comportement au démarrage de la prochaine dictée.
- Les corrections manuelles restent prioritaires. Après une retouche manuelle, le nettoyage général est ignoré. Un format explicitement choisi peut toujours être appliqué au brouillon corrigé.
- Le brouillon est privé, stocké dans les données locales de l’application, exclues des sauvegardes Android. Après un arrêt du service, le micro peut nécessiter une réactivation depuis l’application avant la reprise. Effacer les données ou désinstaller l’application supprime ce brouillon.

À vérifier sur appareil : agrandir aux quatre bords, ouvrir le clavier en portrait/paysage, masquer puis réafficher, modifier en pause, verrouiller/déverrouiller puis reprendre ; enfin arrêter puis relancer le service pour vérifier la restauration du texte et l’ajout de la suite sans reformulation.

### Notes locales et menus flottants (0.6.6)

- Glisser directement vers la gauche range la dictée ouverte en note, sans la coller, puis ouvre « Mes notes ». Au repos, le même geste ouvre la liste. Un accès « Mes notes » existe aussi dans les réglages ; « Terminer » est disponible dans le panneau d’une note.
- Une nouvelle note s’ouvre sans activer le micro et peut être écrite au clavier. Tap sur la pastille : dicter. En pause, un tap reprend après la fenêtre de double tap de 280 ms ; pendant la dictée de note, un tap met en pause. Deux taps rangent la note, sans la supprimer ni l’insérer. Un glissement vers le haut réaffiche la note ; seul le bouton Insérer… demande son insertion.
- Les notes ouvertes sont sauvegardées automatiquement. Le rangement finalise les derniers mots localement. Les titres sont tirés de la première ligne (neuf mots, 60 caractères maximum), sans modèle ni réseau. Les titres renommés sont conservés.
- Appuyer sur une note la rouvre en pause. Un appui maintenu ouvre Renommer / Supprimer. La liste affiche les notes récemment modifiées en premier, avec titre en gras, aperçu distinct, date de modification et nombre d’images.
- « Insérer… » termine l’écoute si nécessaire puis présente le texte final à confirmer. « Insérer le texte » tente son insertion dans le champ de l’application ; « Rester dans la note » ne dépose rien. Si l’insertion n’est pas possible, le texte est copié pour collage manuel. La note reste ouverte et conservée après insertion. La propre fenêtre de DictAI est exclue des cibles. Les images se transmettent par l’export PDF/HTML.
- Le menu des formats est une liste flottante au-dessus de la pastille, ou en dessous si l’espace manque. Les menus restent dans les limites de l’écran et se ferment au toucher extérieur.
- Les icônes agrandir/réduire et masquer sont en haut à droite, avec des cibles tactiles de 48 dp. Le mode agrandi atteint 82 % de la hauteur disponible. La croix masque seulement le panneau.

Essais sur appareil : écrire une note sans micro, la ranger, ouvrir une autre note et revenir à la première ; dicter/reprendre, ranger par double tap pendant la pause, renommer puis poursuivre, supprimer une seule note, annuler puis confirmer une insertion dans une autre application ; vérifier les menus aux quatre bords et le verrouillage/redémarrage avec une note ouverte.

Suivi du développement par étapes : [demandes et validations](docs/SUIVI-DEVELOPPEMENT.md). Le 350M reste exclu de la compilation normale. Après l’échec de la réécriture libre, une configuration de copie contrainte est disponible dans un [prototype séparé à essayer sur téléphone](docs/ESSAI-LLM-LOCAL.md) : mots conservés sur le corpus, découpage encore imparfait, latence appareil à mesurer.

Le correctif 0.9.5 et son essai ciblé sont décrits dans [Notes et édition](docs/NOTES-ET-EDITION-0.9.5.md). Les gestes, le clavier et le rendu de cette version restent à vérifier sur appareil ; les tests instrumentés sont compilés sans être exécutés ici.
