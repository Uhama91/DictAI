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

## Data collection

I do not run a backend for DictAI and do not collect user accounts, analytics,
crash reports, or uploaded recordings. Dictation drafts and notes remain in the
app's local storage. Captures stay local unless you explicitly save them to
Photos or export or share them.

OpenRouter and the model provider selected through it may process the transcript
text, selected formatting instructions, and configured vocabulary or protected
spellings according to their own terms and privacy policies.

## Contact

For privacy questions, open an issue in the
[DictAI repository](https://github.com/Uhama91/DictAI/issues).
