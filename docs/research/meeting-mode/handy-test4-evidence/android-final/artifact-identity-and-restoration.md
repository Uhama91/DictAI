# Android final fixture evidence

The final UI fixture run used `emulator-5558`, Android test user 0, and the
existing test APK. Its fixture data is synthetic UI-only content; no model or
audio was loaded. The test emitted 17 native Android screenshots, pulled from
`/sdcard/Android/data/com.uhama.whisperpin.meetingtest/files/meeting-ui-fixtures-simulated-v2/`
to `docs/research/meeting-mode/ui-renders/handy-test4/final-panel-fixture-v3-clean/`.
The final pull reported 17 files and 1,344,252 bytes. Dimensions are recorded in
`capture-dimensions-tool-output.txt`, and per-file SHA-256 values are in
`capture-sha256.txt`.

The final fixture source is
`app/src/androidTest/kotlin/com/kafkasl/phonewhisper/meeting/MeetingPanelNativeFixtureCaptureAndroidTest.kt`,
SHA-256 `910c2aea3d39f9be0de4bfc6bd015cfbd8e0f30361c0519da05643e7bc207a94`.
The AndroidTest APK is
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, SHA-256
`d6db33d57fccbc6313eb0b045398fef5d0df6f4cab5621f07e8a4a55320951c6`, size
2,349,855 bytes. The main APK was not rebuilt; its existing file is
`app/build/outputs/apk/debug/app-debug.apk`, SHA-256
`0ad1d9dd283a2d33edf6e42b19db6d1cfcc0e3a332c234bd1dec8ab1d74d0732`, size
93,938,974 bytes.

The AndroidTest assembly succeeded; the single fixture test passed in 19.957 s.
The runner's exact observed stdout is transcribed in
`fixture-runner-tool-transcription.txt`. The successful Gradle output excerpt
is transcribed in `androidtest-assemble-tool-transcription.txt`; neither file
pretends to be a raw process log.

After the runner, the observed emulator state was restored: current user 0;
`RECORD_AUDIO` denied for users 0 and 10; `SYSTEM_ALERT_WINDOW` app-op default
for user 0, no explicit operation and default mode for user 10; screen-off
timeout 2,147,483,647 ms for user 0 and 60,000 ms for user 10; no active app
service. These values were read from the command outputs recorded in this
task. `git diff --check` passed for the fixture source.
