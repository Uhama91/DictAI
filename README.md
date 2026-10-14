<p align="center"><img src="docs/logo.png" width="112" height="112" alt="DictAI logo"></p>

# DictAI

**Dictate, review, and write in your apps.**

DictAI brings local voice dictation to Android phones, Android tablets, and macOS. Speak into a floating panel, edit your transcript, then insert it into a supported text field or save it as a note.

The current app interface is in French. This guide describes its functions in English.

## Downloads

Android phones and tablets use the same APK. Direct downloads do not require a GitHub account.

<!-- dictai-downloads:start -->
| Device | Download | Version | Requirements |
|---|---|---|---|
| Android phone | [Download](https://github.com/Uhama91/DictAI/releases/download/dictai-20261014-macos-0.4.14-preview/DictAI-android.apk) | 0.9.18-dictai (test) | Android 11+ · ARM64 |
| Android tablet | [Download](https://github.com/Uhama91/DictAI/releases/download/dictai-20261014-macos-0.4.14-preview/DictAI-android.apk) | 0.9.18-dictai (test) | Android 11+ · ARM64 |
| Mac | [Download](https://github.com/Uhama91/DictAI/releases/download/dictai-20261014-macos-0.4.14-preview/DictAI-macOS.pkg) | 0.4.14 (test) | macOS 14.0+ · Apple silicon and Intel |

Versions marked "test" are preview builds. Read the installation instructions before opening them.

Installation and changes: [Release notes](https://github.com/Uhama91/DictAI/releases/tag/dictai-20261014-macos-0.4.14-preview).
<!-- dictai-downloads:end -->

The Android APK is unchanged in this release. The Mac package is universal, ad hoc signed, and not Apple-notarized. Speech recognition on a physical Intel Mac remains unverified.

## From speech to text

1. Place the cursor in the app where you want to write.
2. Start dictation with the floating control on Android or **Option (⌥)** on Mac.
3. Speak, pause to make corrections if needed, then finish to insert the text.

DictAI does not press Enter or Send. You send messages yourself. Insertion depends on the target app and field; password and other secure fields are excluded. If insertion fails, the text remains available to copy.

On Mac, a single Option press starts dictation when idle, finishes and inserts while listening, or resumes the same draft while paused. Two quick presses pause or resume; three cancel without inserting and preserve a recoverable draft. The panel offers equivalent buttons.

The Mac editor opens as a floating panel or a full, resizable window. Manual corrections remain protected when speech recognition continues or revises earlier words. Supported custom editors, including cmux/OpenCode, use a native Paste action with protection against duplicate insertion.

## Keep a note

Save text in notes and organize it in folders. Saved notes are separate from the local rolling history of the last ten dictations and remain until you delete them.

On Android, add screenshots or photos when you explicitly request a capture. A note can contain up to ten images. Export notes as PDF, HTML, or text and images; inserting into another app transfers the text.

Personal vocabulary helps with names and recurring terms. On Mac, you can explicitly remember a correction from the editor. Once you remember it, its prompt disappears and the editor regains the space; existing rules are not offered again.

## Set up Android

Install the APK, then follow the setup assistant to allow microphone access, download a local speech model, and enable display over other apps. Enable Accessibility for insertion into supported fields and for captures you request. Notes and manual copying remain available without it.

Xiaomi, Redmi, and Poco devices may also require restricted-settings access, unrestricted battery use, and automatic startup. Check these settings if the floating control disappears.

For an update, install the APK over the existing app with its matching signature to preserve app data. This release keeps the existing Android test signature.

## Set up Mac

Quit DictAI before running the PKG installer, then open `/Applications/DictAI.app`. Allow microphone access, speech recognition, and Accessibility in macOS settings. Refresh the shortcut check in DictAI after changing permissions.

After an ad hoc signed update, macOS may require you to remove and re-add the installed `/Applications/DictAI.app` entry in Accessibility, then refresh the shortcut check. For opening a package that macOS blocks, see [Apple's installation guidance](https://support.apple.com/en-us/102445).

Keep DictAI running in the menu bar to use Option. Try a short dictation in TextEdit before using your usual app. See the [Mac setup and usage guide](desktop/macos/README.md) for controls, recovery, and troubleshooting.

## Local use and optional services

**Local dictation.** Android uses a downloaded speech model. Mac uses Apple Speech with on-device recognition required and no remote speech fallback. Model availability and initial downloads may require a connection. No account is required for local-only use.

**Optional cloud correction.** With your OpenRouter key, DictAI can send the transcript and useful vocabulary for text correction. Recorded audio is not sent for this correction. Provider charges may apply. The key stays on the device, in Keychain on Mac, and does not sync. If correction fails, the transcript remains available.

**Optional account sync.** Sign in with Google to sync saved notes, folders, images, vocabulary, and shared preferences through Supabase. Sync is periodic and is not guaranteed to be instant.

Secrets, permissions, installed models, audio, drafts, recent dictation history, and panel positions do not sync. Mac notes created without an account stay in local storage and are not automatically imported when you sign in.

Read the [privacy policy](PRIVACY.md) before enabling optional services.

## Build and test

Android requires JDK 21 and the Android SDK. From the repository root:

```sh
make build
make test
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For Mac:

```sh
cd desktop/macos
./scripts/test.sh
./scripts/build-installer.sh
```

The [Mac guide](desktop/macos/README.md) covers requirements and distribution limits. Automated tests do not establish compatibility with every device or text field.

Report problems in [GitHub Issues](https://github.com/Uhama91/DictAI/issues), including your device, app version, target app, and steps to reproduce with a non-sensitive example.

## Attribution and licenses

DictAI builds on [Phone Whisper by kafkasl](https://github.com/kafkasl/phone-whisper). The repository includes the adapted Android app and a native macOS companion.

The project is distributed under the [Apache 2.0 license](LICENSE). See [NOTICE](NOTICE) for attribution, [third-party notices](app/src/main/assets/THIRD_PARTY_NOTICES.txt), and [included licenses](app/src/main/assets/licenses) for components with separate terms.
