<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper

Push-to-talk dictation for Android.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Local on-device transcription** with sherpa-onnx
- **Cloud transcription** with OpenAI Whisper
- **Optional cleanup** with OpenAI to fix punctuation and grammar

If you try it and it genuinely saves you time, consider [sponsoring](https://github.com/sponsors/kafkasl)


## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/kafkasl/phone-whisper/releases).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/kafkasl/phone-whisper.git && cd phone-whisper
make build
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. The final text is always copied to the clipboard, whether insertion succeeds or not.

## Setup

### First-time setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: paste your OpenAI API key

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Phone Whisper supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to OpenAI's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to OpenAI's chat API

I don't run a backend for this app. In cloud mode, requests go straight from your phone to OpenAI using your own API key.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.kafkasl.phonewhisper/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Parakeet 110M | 100 MB | Best default |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

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
- Cloud mode requires your own OpenAI API key

## Support the project

If Phone Whisper saves you time, you can sponsor the project on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it.

## DictAI 0.6 — édition et formats

- Le résultat final est copié dans le presse-papiers, même si l’insertion réussit ou si aucun champ n’est sélectionné.
- Pendant une dictée en streaming, toucher le texte ouvre le clavier. Les corrections manuelles restent présentes pendant l’arrivée des mots suivants et dans le résultat final. Une ponctuation finale ou un retour à la ligne commence la suite par une majuscule.
- Maintenir le micro immobile pendant 250 ms enregistre ; relâcher termine la dictée et publie le résultat. Pour déplacer la pastille dans toutes les directions, commencer à la faire glisser et garder le doigt posé jusqu’à la position souhaitée. Un déplacement vers le haut suivi d’une courte pause avant de relâcher reste un déplacement. Pour ouvrir les formats, effectuer un geste franchement vertical vers le haut et relâcher rapidement : au moins 56 dp, en 220 ms maximum, avec une distance verticale au moins double de l’horizontale. Le menu s’ouvre uniquement au relâchement et la pastille retrouve sa position initiale. Une fois l’appui maintenu déclenché, un mouvement ne sélectionne plus de format et n’annule plus la dictée. Ces seuils constituent un réglage initial à vérifier sur téléphone.
- Dans **Formats de post-traitement**, choisir Texte corrigé, Liste à puces ou Mail. Créer, modifier ou supprimer des formats personnels avec un nom et des consignes. Le choix est mémorisé.
- La mise en forme utilise le nettoyage cloud existant : activer cette option et configurer une clé OpenRouter. Les restrictions existantes sur les champs sensibles restent applicables. En cas d’indisponibilité, le texte corrigé manuellement est conservé et un message signale l’absence de mise en forme.

### Vérification sur téléphone / tablette

1. Dicter dans un champ sélectionné, puis coller ailleurs : les deux textes doivent être identiques.
2. Dicter sans champ sélectionné : récupérer le résultat avec Coller.
3. Corriger un nom, ajouter un point puis continuer à parler : vérifier la conservation de la correction, la majuscule et la position du curseur. Tester aussi une sélection, une suppression et le clavier en paysage.
4. Repositionner la pastille lentement vers le haut, puis tester un déplacement rapide suivi d’une pause avant relâchement : aucun menu ne doit s’ouvrir. Faire un geste bref vers le haut et relâcher : le menu doit s’ouvrir seulement au relâchement, sans déplacement permanent ni dictée. Tester les diagonales, le retour après un déplacement latéral, les quatre bords et la rotation. Maintenir pour dicter puis bouger le doigt vers le haut : la dictée doit continuer et être publiée au relâchement. Vérifier aussi Fermer, le tap simple, le double tap d’annulation et une interruption tactile.
5. Créer un format personnalisé, relancer l’application, le modifier puis le supprimer. Tester sans réseau et sans clé : le texte doit rester récupérable.

L’APK de chaque branche est disponible dans **Actions → Build WhisperPin APK → Artifacts → whisperpin-debug-apk** une fois la compilation réussie.

Le curseur et le défilement suivent les dernières lignes à chaque mise à jour automatique, même avec le clavier ouvert. En pause, le texte reste librement éditable et le défilement manuel est conservé. La reprise ramène immédiatement à la fin du texte.

## Pause et reprise de la dictée

Pendant une dictée déjà en cours, un geste bref vers le bas sur la pastille met en pause au relâchement (56 dp minimum, 350 ms maximum, mouvement principalement vertical). Un déplacement plus lent reste un repositionnement du bouton.

La pause coupe et libère le microphone, fige les mises à jour automatiques et garde le champ visible. Le symbole « Ⅱ » indique la pause. Le texte peut être corrigé, complété au clavier ou effacé. Un tap reprend la même session et conserve les ajouts. Le tap suivant termine normalement la dictée, copie le résultat dans le presse-papiers et tente l’insertion dans le champ de l’application.

Vérifier sur **Xiaomi Pad 7** et **Poco F7**, avec le micro intégré puis les écouteurs OnePlus :

1. Dicter une phrase, descendre brièvement sur la pastille, attendre le symbole de pause. Vérifier que le microphone n’est plus utilisé.
2. Parler pendant la pause : ces paroles ne doivent apparaître ni dans l’aperçu ni dans le résultat final.
3. Corriger un mot et ajouter plusieurs lignes au clavier pendant la pause ; déplacer le curseur vers le début.
4. Appuyer une fois pour reprendre : texte conservé, curseur et vue revenus en bas. Continuer à parler : les nouvelles lignes restent visibles.
5. Appuyer à nouveau pour terminer : vérifier les ajouts manuels et les deux portions dictées dans le presse-papiers et le champ cible.
6. Répéter plusieurs pauses ; tester une reprise immédiate pendant la fermeture du micro, une interruption du Bluetooth, la rotation, le double tap d’annulation et l’arrêt du service pendant la pause.

La pause conserve la session en mémoire ; elle ne constitue pas une sauvegarde durable si Android arrête le service.
