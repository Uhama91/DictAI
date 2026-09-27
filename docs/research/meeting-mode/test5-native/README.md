# Test5 native evidence

This folder keeps compact, replayable evidence from the test5 ARM64 Android emulator runs. The audio inputs are the public synthetic French ABCA fixture and the public AMI excerpt; no user audio, model files, APKs, or mixed logcat captures are included.

## Short French ABCA history

The test5 prototype package (`com.uhama.whisperpin.meetingtest`, version `0.9.6-dictai-meeting-test5`, code 39) completed the streaming-port test in 16.69 s. The capture contains 12,780 ms of synthetic French audio. Replaying 648 ordered Handy/diarization events through the frozen test4 assembler never set `alignmentBroken`; all 19 lexical words retained their timestamps, 18 received a speaker channel, and one timed word remained unknown because no frame reached the 0.5 assignment threshold. The transcript was `Bonjour, ouvrons la réunion de la semaine, je propose jeudi matin pour le suivi d'accord merci, nous reprenons jeudi.`

See the runner and PID-filtered log in `short-history/`. The compact event timeline and test4 replay are included. The full Handy history and probability tables remain in the local build archive; their paths and SHA-256 values are recorded in `large-local-evidence.sha256`.

## Engine integration

The integrated `MeetingEngine` + Handy bridge test passed in 16.987 s on the same test5 prototype. It delivered live text before finish, then a real speaker-attribution revision before finish. The projected transcript matched the Handy transcript exactly; 19/19 words were timed, 18 were attributed, and one remained unknown. It processed all 12,780 ms of captured audio with no audio left pending when the engine closed. The final `ACTIVE` voice-state field is only the last state snapshot; it does not mean a native resource remained active after close.

See the test runner and PID-filtered log in `integrated/`.

## AMI diarization-only run

The 60-second public AMI fixture ran through `DiarizationNative` without loading Handy ASR. The single test passed, but live pacing did not keep up: 60,000 ms of audio took 96,476 ms to feed, the maximum deadline lag was 37,088 ms, and finalization took 2,211 ms. This is a sustained-throughput failure for this emulator configuration; it is not hidden by the test's green status.

The diarizer produced 6,001 stable frames at about 10 ms per frame, 25 hypothesis segments across 3 predicted channels, against 22 reference segments across 4 speakers. Frame-level DER was 0.25177228786251343, including 616 missed speaker-frames, 432 false-alarm speaker-frames, and 124 confusion speaker-frames. The pinned Python scorer `tests/ci/model_smoke.py::diarization_errors` was separately applied to the same reference and hypothesis RTTM; its DER and confusion values matched the test's output to the recorded precision. This scorer evaluates diarizer segments only, not the application's per-word speaker classifier.

The `ami/` folder contains the compact summary, progress checkpoints, reference/hypothesis RTTM, runner result, and PID-filtered log. The complete probability timeline remains local and is hashed in `large-local-evidence.sha256`.

## Identity and boundaries

`artifact-identity.txt` records the installed package, APK and JNI identities, fixture/model hashes, and pinned source/scorer revisions. The three test source hashes are in `test-source-sha256.txt`; the frozen test4 comparator has its own entry there. `large-local-evidence.sha256` covers full archives and larger tables that were deliberately not copied here.

These emulator runs do not establish behavior or performance on a Poco F7, the user's football video, French conversation quality, or diarization quality on that conversation.
