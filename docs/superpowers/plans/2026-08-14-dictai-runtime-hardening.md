# DictAI Runtime Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development and execute this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify DictAI’s batch and streaming ASR runtime behind one contract, make accessibility registration lifecycle-safe, reject invalid audio starts, and restore the overlay after reboot without arming the microphone illegally.

**Architecture:** `OverlayService` remains the Android foreground-service shell and overlay owner, but concrete ASR runtimes move behind `DictationAsrEngine`/`DictationAsrSession`. `InjectionGateway` becomes the only process-local access point to the accessibility controller, while `BootReceiver` starts the existing service in `specialUse` mode only.

**Tech Stack:** Kotlin 2.2, Android SDK 34, AudioRecord, foreground services, sherpa-onnx, transcribe.cpp JNI, JUnit 4, Gradle.

**Specification:** `docs/superpowers/specs/2026-08-14-dictai-runtime-hardening-design.md`

**Repository policy:** Do not commit, push, publish, or modify unrelated user changes. The working branch and worktree already exist. Record RED and GREEN command evidence in the implementation handoff.

---

## File map

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/kafkasl/phonewhisper/DictationAsrEngine.kt` | Unified ASR engine/session contracts, batch and streaming adapters, factory |
| `app/src/test/kotlin/com/kafkasl/phonewhisper/DictationAsrEngineTest.kt` | Backend selection, finalization mapping and cancellation tests |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/RecordingStartGate.kt` | Pure recording readiness decision using the unified engine state |
| `app/src/test/kotlin/com/kafkasl/phonewhisper/RecordingStartGateTest.kt` | START, LOADING, RELOAD_REQUIRED and UNAVAILABLE gate decisions |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/InjectionGateway.kt` | Lifecycle-safe process-local registry for `InjectionController` |
| `app/src/test/kotlin/com/kafkasl/phonewhisper/InjectionGatewayTest.kt` | Replacement and stale-unregistration tests |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/AudioRecordStartPolicy.kt` | Pure validation decisions for AudioRecord startup |
| `app/src/test/kotlin/com/kafkasl/phonewhisper/AudioRecordStartPolicyTest.kt` | Buffer, initialization and recording-state cases |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/RecordingStartupTransaction.kt` | Transactional recorder/session acquisition and symmetric cleanup |
| `app/src/test/kotlin/com/kafkasl/phonewhisper/RecordingStartupTransactionTest.kt` | Construction, start, session and cleanup failure paths |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayRestartPolicy.kt` | Accepted boot/update actions and construction of an unarmed restart intent |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/BootReceiver.kt` | Safe Android broadcast boundary |
| `app/src/test/kotlin/com/kafkasl/phonewhisper/OverlayRestartPolicyTest.kt` | Exact action allowlist and absence of microphone arm action |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt` | Use unified ASR/session, gateway and audio guards; retain UI and FGS shell |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt` | Register/unregister through `InjectionGateway` |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt` | Read accessibility availability through `InjectionGateway` |
| `app/src/main/kotlin/com/kafkasl/phonewhisper/OnboardingActivity.kt` | Read accessibility availability through `InjectionGateway` |
| `app/src/main/AndroidManifest.xml` | Declare `BootReceiver` and its three actions |
| `app/build.gradle.kts` | Bump the identifiable test build to versionCode 9 / versionName 0.5.5-wp |
| `CLAUDE.md` | Add one concise Session Log row after successful verification |
| Existing tests | Adapt compile-time expectations without weakening assertions |

## Task 1: Add the lifecycle-safe injection gateway

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/InjectionGateway.kt`
- Create: `app/src/test/kotlin/com/kafkasl/phonewhisper/InjectionGatewayTest.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/OnboardingActivity.kt`

- [ ] **Step 1: Write the failing gateway tests**

Cover these behaviors with real `InjectionController` instances:

```kotlin
@Test fun `latest registered controller is returned`()
@Test fun `unregistering stale controller keeps replacement`()
@Test fun `unregistering current controller clears gateway`()
```

Add an `@After` cleanup that clears only the controller created by the test.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*InjectionGatewayTest'
```

Expected: compilation failure because `InjectionGateway` does not exist.

- [ ] **Step 3: Implement the minimal registry**

Use `AtomicReference<InjectionController?>`. `register` sets the current instance, `unregister` uses `compareAndSet(controller, null)`, and `current` returns the reference.

- [ ] **Step 4: Route all call sites through the gateway**

- In `onServiceConnected`, call `InjectionGateway.register(this)`.
- In `onDestroy`, call `InjectionGateway.unregister(this)`.
- Remove the public static `controller` field.
- Replace direct service-controller reads in the overlay, activities and onboarding with `InjectionGateway.current()`.
- Preserve the conservative `null` behavior for sensitive-target checks and clipboard fallback.

- [ ] **Step 5: Run focused and related tests and verify GREEN**

```bash
./gradlew :app:testDebugUnitTest --tests '*InjectionGatewayTest' --tests '*InjectionControllerTest' --tests '*SensitiveTargetPolicyTest'
```

Expected: all selected tests pass.

## Task 2: Add the unified ASR engine/session seam

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/DictationAsrEngine.kt`
- Create: `app/src/test/kotlin/com/kafkasl/phonewhisper/DictationAsrEngineTest.kt`
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/RecordingStartGate.kt`
- Create: `app/src/test/kotlin/com/kafkasl/phonewhisper/RecordingStartGateTest.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt`
- Modify: `app/src/test/kotlin/com/kafkasl/phonewhisper/OverlayRecordingStopCoordinatorTest.kt` to remove the migrated loading-only gate assertion
- Modify: `app/src/test/kotlin/com/kafkasl/phonewhisper/TranscriptionEngineTest.kt` only if a signature must be adapted

- [ ] **Step 1: Write failing selection tests**

Create fake engines and loader counters. Prove:

```kotlin
@Test fun `streaming model invokes only streaming loader`()
@Test fun `batch model invokes only batch loader`()
@Test fun `missing selected backend returns null`()
```

The test-facing factory overload must accept `batchLoader` and `streamingLoader` lambdas.

- [ ] **Step 2: Run the selection tests and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests '*DictationAsrEngineTest'
```

Expected: compilation failure because the ASR seam does not exist.

- [ ] **Step 3: Implement contracts, adapters and selection factory**

Implement:

```kotlin
internal enum class DictationAsrMode { BATCH, STREAMING }

internal interface DictationAsrEngine : Closeable {
    val modelName: String
    val mode: DictationAsrMode
    fun start(
        language: DictationLanguage,
        onPreview: (String, String) -> Unit,
    ): DictationAsrSession
}

internal interface DictationAsrSession {
    fun acceptPcm16(buffer: ByteArray, length: Int)
    fun finish(fullPcm: ByteArray): TranscriptionEngine.Result
    fun cancel()
    fun cancelAndAwait(): Boolean
}
```

The production factory must use `LiveStreamingTranscriber.supports(modelName)` to select exactly one loader. Batch and streaming adapters must own and close their existing native runtime.

- [ ] **Step 4: Write failing finalization and cancellation tests**

Use a small internal pure mapper or a fake native-session port to cover:

```kotlin
@Test fun `batch finish delegates the complete PCM once`()
@Test fun `streaming success maps text`()
@Test fun `streaming empty maps no text and no error`()
@Test fun `streaming timeout maps expiration error`()
@Test fun `streaming failure maps unavailable error`()
@Test fun `streaming cancel is delegated`()
@Test fun `streaming cancel and await is delegated with its result`()
```

- [ ] **Step 5: Run the new tests and verify RED for the missing mapping**

```bash
./gradlew :app:testDebugUnitTest --tests '*DictationAsrEngineTest'
```

Expected: the new mapping/cancellation assertions fail for the expected missing behavior.

- [ ] **Step 6: Implement the minimal session adapters and verify GREEN**

```bash
./gradlew :app:testDebugUnitTest --tests '*DictationAsrEngineTest' --tests '*LiveStreamingTranscriberTest' --tests '*TranscriptionEngineTest'
```

Expected: all selected tests pass.

- [ ] **Step 7: Refactor `OverlayService` to use the unified engine**

Replace:

- `local` and `streamingLocal` with `asrEngine`;
- `liveSession` with `asrSession`;
- `ResidentEngine<LoadedLocalEngine>` with `ResidentEngine<DictationAsrEngine>`;
- model-specific start/finalization branches with the session contract.

Move `RecordingStartGate` into its own production file and refactor it to accept one `hasAsrEngine` precondition. It must no longer mention the concrete engine classes or call `LiveStreamingTranscriber.supports()`. Add focused tests for all four decisions: START, LOADING, RELOAD_REQUIRED and UNAVAILABLE. Preserve a `DictationAsrMode` in the per-recording snapshot so existing batch/stream logging remains accurate.

Keep `RecordingStopCoordinator`, per-recording option snapshots, vocabulary, cloud cleanup, UI state and logging behavior unchanged. Keep `asrEngine` safely published across worker threads. When changing models, call `asrSession?.cancelAndAwait()` before replacing the resident engine.

- [ ] **Step 8: Run the ASR and stop-coordination regression set**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*DictationAsrEngineTest' \
  --tests '*LiveStreamingTranscriberTest' \
  --tests '*TranscriptionEngineTest' \
  --tests '*RecordingStartGateTest' \
  --tests '*OverlayRecordingStopCoordinatorTest'
```

Expected: all selected tests pass.

## Task 3: Reject invalid AudioRecord starts before recording state is published

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/AudioRecordStartPolicy.kt`
- Create: `app/src/test/kotlin/com/kafkasl/phonewhisper/AudioRecordStartPolicyTest.kt`
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/RecordingStartupTransaction.kt`
- Create: `app/src/test/kotlin/com/kafkasl/phonewhisper/RecordingStartupTransactionTest.kt`
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayService.kt`

- [ ] **Step 1: Write failing pure-policy tests**

Cover:

```kotlin
@Test fun `positive buffer initialized recorder and recording state may start`()
@Test fun `zero or negative buffer is rejected`()
@Test fun `uninitialized recorder is rejected`()
@Test fun `recorder not recording after start is rejected`()
```

Use an enum result type so failures remain distinguishable without Android mocks.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests '*AudioRecordStartPolicyTest'
```

Expected: compilation failure because the policy does not exist.

- [ ] **Step 3: Write failing transaction tests**

Use fake recorder and session ports with counters. Cover:

```kotlin
@Test fun `construction failure does not create a session`()
@Test fun `uninitialized recorder is released without creating a session`()
@Test fun `start exception releases recorder without creating a session`()
@Test fun `non-recording recorder is stopped and released`()
@Test fun `session creation failure stops and releases recorder`()
@Test fun `successful transaction returns recorder and session without cleanup`()
```

- [ ] **Step 4: Run the transaction tests and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests '*RecordingStartupTransactionTest'
```

Expected: compilation failure because the transaction does not exist.

- [ ] **Step 5: Implement the minimal policy and transaction, then integrate them**

In `OverlayService.startRec()`:

1. reject an invalid `getMinBufferSize` result before constructing `AudioRecord`;
2. catch construction and `startRecording` failures;
3. reject `STATE_UNINITIALIZED`;
4. reject a post-start state other than `RECORDSTATE_RECORDING`;
5. keep the recorder local while a `RecordingStartupTransaction` opens the ASR session;
6. cancel the session, stop the recorder and release it if session creation fails;
7. publish the recorder and session only after the transaction returns `Started`;
8. set `RECORDING`, vibrate and launch the reader thread only after publication;
9. if publication or reader launch throws, cancel the session, stop and release the recorder, clear every temporary field, and restore `IDLE`.

Do not add a blocking first-PCM wait in this lot.

- [ ] **Step 6: Run focused and stop-lifecycle tests and verify GREEN**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*AudioRecordStartPolicyTest' \
  --tests '*RecordingStartupTransactionTest' \
  --tests '*OverlayRecordingStopCoordinatorTest'
```

Expected: all selected tests pass.

## Task 4: Restore the overlay safely after boot or package replacement

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/OverlayRestartPolicy.kt`
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/BootReceiver.kt`
- Create: `app/src/test/kotlin/com/kafkasl/phonewhisper/OverlayRestartPolicyTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write failing restart-policy tests**

Cover:

```kotlin
@Test fun `boot completed is accepted`()
@Test fun `package replaced is accepted`()
@Test fun `xiaomi quick boot is accepted`()
@Test fun `unknown action is rejected`()
@Test fun `accepted action creates start descriptor without service action`()
```

Do not construct an Android `Intent` in a JVM test. The pure policy returns a descriptor containing `shouldStart` and nullable `serviceAction`; assert `shouldStart` and `serviceAction == null` for accepted actions.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests '*OverlayRestartPolicyTest'
```

Expected: compilation failure because the policy does not exist.

- [ ] **Step 3: Implement the policy, receiver and manifest declaration**

The receiver must:

- ignore every unapproved action;
- obtain a pure restart descriptor, then create a plain explicit intent for `OverlayService`;
- call `ContextCompat.startForegroundService`;
- catch and log only the exception class, without private data;
- never send `ACTION_ARM_MIC`.

Declare `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and `android.intent.action.QUICKBOOT_POWERON` in the manifest, with `android:exported="false"`. Keep `OverlayService.onCreate()` as the `specialUse`-only foreground promotion.

- [ ] **Step 4: Run the focused test and manifest build check**

```bash
./gradlew :app:testDebugUnitTest --tests '*OverlayRestartPolicyTest' :app:processDebugMainManifest
```

Expected: tests pass and the debug manifest is processed successfully.

## Task 5: Full regression verification and test APK

**Files:**
- Modify only if verification exposes a regression: files already in this plan’s scope
- Modify: `app/build.gradle.kts`
- Modify after successful verification: `CLAUDE.md`
- Produce: `app/build/outputs/apk/debug/app-debug.apk`
- Copy after successful verification: `/Users/ulliemaillot/Downloads/dictai-runtime-hardening-2026-08-14.apk`

- [ ] **Step 1: Bump the test build identity**

Set `versionCode = 9` and `versionName = "0.5.5-wp"`. Do not change the application ID, namespace or signing configuration.

- [ ] **Step 2: Inspect scope before verification**

```bash
git status --short
git diff --check
git diff --stat
```

Expected: only this lot’s files plus pre-existing unrelated user files; no whitespace errors.

- [ ] **Step 3: Run the complete JVM suite and debug build in one fresh command**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, zero failing tests, APK produced.

- [ ] **Step 4: Verify the APK and manifest facts**

```bash
test -s app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/build-tools/34.0.0/aapt dump xmltree \
  app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml
```

Expected: a non-empty APK and a manifest containing `BootReceiver`, `android:exported="false"`, the three receiver actions, `OverlayService`, `specialUse|microphone`, versionCode 9 and versionName 0.5.5-wp.

- [ ] **Step 5: Copy the verified artifact without modifying source**

```bash
cp app/build/outputs/apk/debug/app-debug.apk \
  /Users/ulliemaillot/Downloads/dictai-runtime-hardening-2026-08-14.apk
shasum -a 256 /Users/ulliemaillot/Downloads/dictai-runtime-hardening-2026-08-14.apk
```

Expected: copy succeeds and a SHA-256 is printed.

- [ ] **Step 6: Update the repository Session Log**

Add one concise row to `CLAUDE.md` summarizing the unified ASR seam, transactional audio startup, lifecycle-safe injection gateway, unarmed boot restoration, version 0.5.5-wp, automated verification and APK path. Keep the existing log intact and stay within the repository’s two-line session-log limit.

- [ ] **Step 7: Perform the final requirements audit**

Confirm with `rg` and diff inspection:

- no `WhisperAccessibilityService.controller` references remain;
- `OverlayService` does not reference concrete ASR classes;
- the boot path never uses `ACTION_ARM_MIC`;
- no code, comments or documentation from FluidVoice were copied;
- no commit or push occurred.

## Task 6: Prepare the Poco F7 and Pad 7 acceptance handoff

**Files:**
- Read only: `docs/superpowers/specs/2026-08-14-dictai-runtime-hardening-design.md`
- Produce in the final handoff: the exact manual checklist and APK checksum

- [ ] **Step 1: State the automated/manual boundary honestly**

Do not claim that reboot restoration works on HyperOS based only on JVM tests or manifest inspection. State that the artifact passed automated verification and that physical-device acceptance remains.

- [ ] **Step 2: Hand off the complete two-device matrix**

For both Poco F7 and Xiaomi Pad 7, instruct the tester to:

1. install version 0.5.5-wp over the current app;
2. open DictAI once and verify the microphone becomes armed;
3. run one batch dictation and one streaming dictation with preview, final text and injection;
4. remove the app from recents and dictate in another application;
5. reboot and verify the overlay returns with the microphone unarmed;
6. reopen DictAI and verify dictation works;
7. change models and verify clean reload without a crash.

Expected: the morning tester has an APK, SHA-256 and reproducible checklist; no unperformed device test is reported as passed.
