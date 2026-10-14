# DictAI for macOS

Native Mac companion to DictAI for Android. Dictate into an editable floating panel, review the text, then insert it into a supported app or save it as a note.

The current preview is **0.4.14, build 24**, for **macOS 14 or later**. The app is universal for Apple silicon and Intel. Speech recognition on a physical Intel Mac remains unverified. The app interface is currently in French; this guide describes the controls in English.

[Download the Mac installer](https://github.com/Uhama91/DictAI/releases/download/dictai-20261014-macos-0.4.14-preview/DictAI-macOS.pkg) · [Release notes](https://github.com/Uhama91/DictAI/releases/tag/dictai-20261014-macos-0.4.14-preview)

## Install and set up

1. Quit DictAI, run the PKG installer, then open `/Applications/DictAI.app`.
2. Allow microphone access and speech recognition when prompted. DictAI requires Apple Speech recognition on the device; it does not fall back to a remote speech service.
3. Allow Accessibility for the installed DictAI app in macOS settings. This enables the global shortcut and text insertion. Refresh the shortcut check in DictAI after changing the permission.
4. Place the cursor in TextEdit, press and release Option, speak a short sentence, then press and release Option again to finish and insert.

The preview package is ad hoc signed and is not Apple-notarized. If macOS blocks it, follow [Apple's guidance for opening apps](https://support.apple.com/en-us/102445).

After an ad hoc signed update, the exact installed app may need to be removed and re-added in Accessibility. Select `/Applications/DictAI.app`, then refresh the shortcut check in DictAI. Install updates over the existing application to preserve local data.

Keep DictAI open in the menu bar for the Option shortcut to work. You can enable launch at login in the app's settings.

## Dictation controls

| Gesture | Action |
|---|---|
| One Option press while idle | Start dictation |
| One Option press while listening | Finish and insert |
| One Option press while paused | Resume the same draft |
| Two quick Option presses | Pause or resume |
| Three quick Option presses | Cancel without inserting; keep a recoverable draft |

The panel provides equivalent buttons. Option combined with another key or a click does not trigger these commands. Gesture timing follows the Mac's double-click setting.

Click the floating transcript to open the quick editor. Its window control opens a full, resizable Mac editor. Pause to revise the text, then resume the same draft. The floating panel can also be moved.

Manual edits stay protected when recognition continues or revises earlier words. Resuming after a pause preserves sentence continuity and casing; a pause alone does not start a new sentence.

A correction can be explicitly remembered as personal vocabulary. Its row and button disappear immediately after learning, with no persistent success message, so the editor regains its height. Rules already learned are not offered again.

## Insert and recover text

DictAI restores external focus after editing and resolves the current external target at insertion time. In supported custom editors, including cmux/OpenCode, version 0.4.14 uses a single native Paste command and prevents a second paste of the same text.

DictAI does not press Enter or Send. You decide when to submit a message. Compatibility depends on the field and its supported native actions; password and other secure fields are excluded. Unsupported targets keep the transcript available to copy.

The menu bar gives quick access to the latest transcript, recent history, the library, and settings. The local history keeps the last ten dictations; the eleventh replaces the oldest. It remains available after quitting and helps recover text after an insertion failure.

Canceling preserves a recoverable draft. A restored account draft opens in a review state, ready to insert; restoration does not submit it to another app.

## Notes and vocabulary

Save notes deliberately and organize them in folders. Saved notes remain until you delete them and are separate from the rolling dictation history. You can search notes, export text, and view images received from Android.

Add a term to the vocabulary or store a replacement such as `dicta => DictAI`. Remembering a correction always requires an explicit action.

## Optional sync

No account is needed for local dictation or notes. Signing in with Google enables Supabase sync for saved notes, folders, images, vocabulary, and shared preferences.

Sync is periodic, so another device may not receive a change immediately. A completed server exchange does not confirm receipt by another device. Network failures leave the local library available.

Notes created without an account stay in the local scope when you sign in. They are not automatically imported into the account; signing out returns you to the local library.

Secrets, permissions, installed models, audio, drafts, local recent history, and panel positions do not sync. Account credentials and the OpenRouter key stay in the Mac's Keychain.

## Speech and optional correction

Mac dictation uses Apple Speech with on-device recognition required. It does not use the downloaded Android speech models. Initial model availability may require a connection.

Apple's recognizer may revise provisional text and punctuation. Manual corrections remain protected as dictation continues.

Optional OpenRouter correction sends the transcript and useful vocabulary to the selected provider. It never sends recorded audio for this correction. Provider charges may apply. The key is local to this Mac; it does not sync. The transcript remains available if correction fails.

Read the [privacy policy](../../PRIVACY.md) before enabling optional services.

## Build and test

The app requires macOS 14 or later. The existing scripts build the universal app and installer:

```sh
cd desktop/macos
./scripts/test.sh
./scripts/build-installer.sh
```

The 0.4.14 test suite passes 202 tests on Apple silicon and under Rosetta. The maintainer has also validated the real Mac voice workflow and insertion into cmux/OpenCode without duplicate text. Rosetta testing does not establish physical Intel speech recognition support.

## Support and licenses

Report a problem in [GitHub Issues](https://github.com/Uhama91/DictAI/issues) with the macOS version, DictAI version, target app, and a non-sensitive reproduction example.

DictAI builds on [Phone Whisper by kafkasl](https://github.com/kafkasl/phone-whisper). See the project [license](../../LICENSE), [attribution notice](../../NOTICE), and [third-party notices](../../app/src/main/assets/THIRD_PARTY_NOTICES.txt).
