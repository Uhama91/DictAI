# Privacy Policy for DictAI

> This policy is a modified derivative of the original Phone Whisper policy;
> see [NOTICE](NOTICE) for project provenance.

DictAI is a dictation app for Android and macOS that records speech, transcribes
it locally, and inserts the result into supported text fields across apps.

## Data handling

DictAI's normal transcription path runs on the device. Android uses models
downloaded into the app's private storage. macOS uses Apple Speech with
on-device recognition required and no remote speech fallback. Initial model
availability may require a connection. Audio is not sent to a DictAI server.

### Optional cloud cleanup

If you enable cloud cleanup, DictAI sends the transcript text with a fixed conservative correction prompt
directly from the device to OpenRouter. Relevant vocabulary may accompany the
transcript. The transcript contains any vocabulary substitutions made locally;
protected spellings are checked before
accepting the result. This step is optional and does not send
the recorded audio.

## API key

If you enable cloud cleanup, your OpenRouter API key is stored in secure local
app storage on Android or Keychain on macOS and used to authenticate the request
sent directly to OpenRouter.

DictAI does not operate a relay server for these requests.

## Accessibility Service

DictAI uses the Android Accessibility Service to identify the currently focused text
field and insert dictated text after you explicitly interact with the floating
overlay button. It can also capture a screenshot only when you explicitly
request the note-capture feature.

On macOS, Accessibility enables the Option shortcut and insertion into the
selected external app. DictAI checks the active app, window, field role, and
available Paste action to select an insertion method. Local insertion
diagnostics contain structural metadata such as app identifiers and focus
status, without transcripts, field values, selections, or clipboard contents.

DictAI is not designed to monitor browsing, collect screen content for
analytics, or perform background automation.

## Optional account and device synchronization

DictAI works locally without an account. If you choose to sign in with Google,
Supabase Auth hosts your account identifier and email address. Supabase hosts
your vocabulary, shared preferences, historical format definitions, saved folders, notes,
and the original images attached to those notes so your devices can retrieve
them. Android includes existing saved content when that device connects. On
Mac, notes created without an account remain in a separate local scope and are
not automatically imported into the signed-in account.
Historical format definitions are kept for compatibility and are never executed
by this version of the app.

Database policies isolate each user's rows. Images use a private storage bucket
and authenticated downloads. Network exchanges use HTTPS. Account credentials
are stored using AndroidKeyStore on Android and Keychain on macOS; API keys,
audio recordings, unfinished dictation drafts, recent dictation history,
model installations, permissions, and overlay
positions are excluded from account synchronization. Hosted content is not
end-to-end encrypted; the hosting operator can administer the stored content.

Signing out stops exchanges on that device and removes its session. It retains
local notes and preferences, and does not delete the hosted account or content.
Deleted items are retained as synchronization tombstones, and older device
replicas or private image objects can retain previous content. For hosted
account deletion, contact the project maintainer through the repository link
below. Client-side account deletion and automatic image-retention cleanup are
not included in this initial version.

DictAI does not collect analytics, crash reports, or uploaded audio recordings.
Without account synchronization, notes and captures remain local unless you
explicitly export, save, or share them.

The last ten dictations are kept in a rolling history on each device. This
history and recoverable drafts are separate from deliberately saved notes and
are not synchronized to the account.

OpenRouter and the model provider selected through it may process the transcript
text, including locally corrected vocabulary, according to their own terms and
privacy policies.

## Update checks

DictAI checks public version metadata on GitHub over HTTPS when the application
is opened or used. Automatic checks are limited to once a day after a successful
response; a manual check is available in settings. GitHub receives standard
connection information, including your IP address. Update requests contain no
audio, transcript, notes, vocabulary, account identifiers, or API keys. Version
comparison and the update cache remain on the device. Clicking the update banner
opens the installer download in your browser.

## Contact

For privacy questions, open an issue in the
[DictAI repository](https://github.com/Uhama91/DictAI/issues).
