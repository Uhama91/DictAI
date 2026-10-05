# Privacy Policy for DictAI

> This policy is a modified derivative of the original Phone Whisper policy;
> see [NOTICE](NOTICE) for project provenance.

DictAI is an Android dictation app that records speech, transcribes it locally,
and inserts the result into text fields across apps.

## Data handling

DictAI's normal transcription path runs on the device using models that you
download into the app's private storage. Audio is not sent to a DictAI server.

### Optional cloud cleanup

If you enable cloud cleanup, DictAI sends the transcript text, the selected
formatting instructions, and any configured vocabulary or protected spellings
directly from the device to OpenRouter. This step is optional and does not send
the recorded audio.

## API key

If you enable cloud cleanup, your OpenRouter API key is stored in secure local
app storage and used to authenticate the request sent directly to OpenRouter.

DictAI does not operate a relay server for these requests.

## Accessibility Service

DictAI uses Android Accessibility Service to identify the currently focused text
field and insert dictated text after you explicitly interact with the floating
overlay button. It can also capture a screenshot only when you explicitly
request the note-capture feature.

DictAI is not designed to monitor browsing, collect screen content for
analytics, or perform background automation.

## Optional account and device synchronization

DictAI works locally without an account. If you choose to sign in with Google,
Supabase Auth hosts your account identifier and email address. Supabase hosts
your vocabulary, shared preferences, personal formats, saved folders, notes,
and the original images attached to those notes so your devices can retrieve
them. Existing saved content on a device is included when that device connects.

Database policies isolate each user's rows. Images use a private storage bucket
and authenticated downloads. Network exchanges use HTTPS. Account credentials
are encrypted on the device using AndroidKeyStore; API keys, audio recordings,
unfinished dictation drafts, model installations, permissions, and overlay
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

OpenRouter and the model provider selected through it may process the transcript
text, selected formatting instructions, and configured vocabulary or protected
spellings according to their own terms and privacy policies.

## Contact

For privacy questions, open an issue in the
[DictAI repository](https://github.com/Uhama91/DictAI/issues).
