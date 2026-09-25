#!/usr/bin/env bash
set -euo pipefail

if [[ "${MEETING_T8_APPROVED:-}" != "1" ]]; then
    printf 'T8 execution requires explicit review authorization: MEETING_T8_APPROVED=1\n' >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.native-cache/meeting"
PROBE="$CACHE/build/android-arm64/bin/meeting_probe"
ASR_MODEL="$CACHE/models/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf"
DIAR_MODEL="$CACHE/models/Nemotron-3-Diarization.q8_0.gguf"
FIXTURE="$ROOT/app/src/androidTest/assets/meeting/synthetic-fr-ABCA-16k-mono.wav"
FIXTURE_SHA=196439ff592c6e6a794c1914804dd996c428f378ab7dd0ff372bfb55baa521d9
FIXTURE_SIZE=409046
ASR_SHA=a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae
ASR_SIZE=741548352
DIAR_SHA=08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1
DIAR_SIZE=107012128
RUNTIME_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
RUNTIME_MANIFEST="$ROOT/scripts/native/meeting-existing-runtime.manifest"
REMOTE_HELPER="$ROOT/scripts/native/meeting-benchmark-pass-remote.sh"
REPORT_ROOT="$ROOT/app/build/reports/meeting/t8"
RUN_ID="${MEETING_T8_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
[[ "$RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]] || {
    printf 'FAIL: invalid run id\n' >&2
    exit 2
}
RUN_DIR="$REPORT_ROOT/runs/$RUN_ID"
mkdir -p "$REPORT_ROOT/runs"
mkdir "$RUN_DIR" || { printf 'FAIL: run directory already exists: %s\n' "$RUN_DIR" >&2; exit 2; }
RAW_LOG="$RUN_DIR/benchmark.raw.log"
SUMMARY="$RUN_DIR/benchmark.summary.json"

sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
byte_count() { wc -c < "$1" | tr -d '[:space:]'; }
require_identity() {
    local file="$1" expected_size="$2" expected_sha="$3" actual_size actual_sha
    [[ -f "$file" ]] || { printf 'FAIL: required input missing: %s\n' "$file" >&2; exit 2; }
    actual_size="$(byte_count "$file")"
    actual_sha="$(sha256 "$file")"
    [[ "$actual_size" == "$expected_size" && "$actual_sha" == "$expected_sha" ]] || {
        printf 'FAIL: input identity mismatch: %s bytes=%s sha256=%s\n' \
            "$file" "$actual_size" "$actual_sha" >&2
        exit 2
    }
}

[[ -x "$PROBE" ]] || { printf 'FAIL: meeting_probe is missing or not executable: %s\n' "$PROBE" >&2; exit 2; }
[[ -f "$RUNTIME_MANIFEST" ]] || { printf 'FAIL: runtime manifest missing\n' >&2; exit 2; }
[[ -f "$REMOTE_HELPER" ]] || { printf 'FAIL: remote pass helper missing\n' >&2; exit 2; }
require_identity "$ASR_MODEL" "$ASR_SIZE" "$ASR_SHA"
require_identity "$DIAR_MODEL" "$DIAR_SIZE" "$DIAR_SHA"
require_identity "$FIXTURE" "$FIXTURE_SIZE" "$FIXTURE_SHA"

runtime_count=0
while IFS=' ' read -r runtime_name expected_size expected_sha extra || [[ -n "${runtime_name:-}" ]]; do
    [[ -n "${runtime_name:-}" && -z "${extra:-}" ]] || {
        printf 'FAIL: malformed historical-runtime manifest row\n' >&2
        exit 2
    }
    [[ "$runtime_name" =~ ^lib[a-z0-9._-]+\.so$ && "$runtime_name" != libdictai_meeting.so ]] || {
        printf 'FAIL: unexpected historical-runtime filename: %s\n' "$runtime_name" >&2
        exit 2
    }
    require_identity "$RUNTIME_DIR/$runtime_name" "$expected_size" "$expected_sha"
    runtime_count=$((runtime_count + 1))
done < "$RUNTIME_MANIFEST"
((runtime_count == 9)) || { printf 'FAIL: expected exactly nine historical runtime libraries, got %s\n' "$runtime_count" >&2; exit 2; }

DEVICE="${ANDROID_SERIAL:-emulator-5554}"
adb -s "$DEVICE" get-state >/dev/null
ABI="$(adb -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$ABI" == "arm64-v8a" ]] || {
    printf 'FAIL: expected an arm64-v8a Android target, got %s\n' "$ABI" >&2
    exit 2
}

PROBE_SHA="$(sha256 "$PROBE")"
PROBE_SIZE="$(byte_count "$PROBE")"
REMOTE_DIR="/data/local/tmp/dictai-meeting-t8/$RUN_ID"
{
    printf 'T8_RUN id=%s device=%s abi=%s schedule=ASR/BOTH/BOTH/ASR/ASR/BOTH cadence_ms=160\n' \
        "$RUN_ID" "$DEVICE" "$ABI"
    printf 'PROBE path=%s bytes=%s sha256=%s\n' "$PROBE" "$PROBE_SIZE" "$PROBE_SHA"
    printf 'ASR_MODEL path=%s bytes=%s sha256=%s\n' "$ASR_MODEL" "$ASR_SIZE" "$ASR_SHA"
    printf 'DIAR_MODEL path=%s bytes=%s sha256=%s\n' "$DIAR_MODEL" "$DIAR_SIZE" "$DIAR_SHA"
    printf 'AUDIO path=%s bytes=%s sha256=%s\n' "$FIXTURE" "$FIXTURE_SIZE" "$FIXTURE_SHA"
    printf 'SOURCE_SHA meeting_probe.cpp=%s\n' "$(sha256 "$ROOT/app/src/main/cpp/meeting/meeting_probe.cpp")"
    printf 'SOURCE_SHA pass_helper=%s\n' "$(sha256 "$REMOTE_HELPER")"
    printf 'SOURCE_SHA analyzer=%s\n' "$(sha256 "$ROOT/scripts/analyze_meeting_benchmark.py")"
    printf 'COEXIST_MANIFEST path=%s rows=%s\n' "$RUNTIME_MANIFEST" "$runtime_count"
    cat "$RUNTIME_MANIFEST"
    printf 'MEASUREMENT_SCOPE native probe process only; no APK/JNI/UI/Poco inference\n'
    printf 'PSS sampling cadence=250ms; PSS and RSS separate; overhead=proc scans plus adb stream\n'
} | tee "$RAW_LOG"

adb -s "$DEVICE" shell mkdir -p "$REMOTE_DIR/existing-runtime"
adb -s "$DEVICE" push "$PROBE" "$REMOTE_DIR/meeting_probe"
adb -s "$DEVICE" push "$ASR_MODEL" "$REMOTE_DIR/asr.gguf"
adb -s "$DEVICE" push "$DIAR_MODEL" "$REMOTE_DIR/diar.gguf"
adb -s "$DEVICE" push "$FIXTURE" "$REMOTE_DIR/synthetic-fr.wav"
adb -s "$DEVICE" push "$REMOTE_HELPER" "$REMOTE_DIR/meeting-benchmark-pass-remote.sh"
adb -s "$DEVICE" shell chmod 0700 "$REMOTE_DIR/meeting_probe" \
    "$REMOTE_DIR/meeting-benchmark-pass-remote.sh"
while IFS=' ' read -r runtime_name expected_size expected_sha extra || [[ -n "${runtime_name:-}" ]]; do
    adb -s "$DEVICE" push "$RUNTIME_DIR/$runtime_name" "$REMOTE_DIR/existing-runtime/$runtime_name"
done < "$RUNTIME_MANIFEST"

variants=(asr both both asr asr both)
for index in "${!variants[@]}"; do
    pass_id=$((index + 1))
    variant="${variants[$index]}"
    printf 'HOST_PASS_BEGIN pass=%s variant=%s command=adb -s %q shell sh %q %q %q %q\n' \
        "$pass_id" "$variant" "$DEVICE" "$REMOTE_DIR/meeting-benchmark-pass-remote.sh" \
        "$pass_id" "$variant" "$REMOTE_DIR" | tee -a "$RAW_LOG"
    adb -s "$DEVICE" shell sh "$REMOTE_DIR/meeting-benchmark-pass-remote.sh" \
        "$pass_id" "$variant" "$REMOTE_DIR" 2>&1 | tee -a "$RAW_LOG"
    printf 'HOST_PASS_END pass=%s variant=%s\n' "$pass_id" "$variant" | tee -a "$RAW_LOG"
done

python3 "$ROOT/scripts/analyze_meeting_benchmark.py" --input "$RAW_LOG" --output "$SUMMARY"
printf 'T8_ANALYSIS status=ok summary=%s\n' "$SUMMARY" | tee -a "$RAW_LOG"
printf 'T8_RUN_COMPLETE raw=%s summary=%s\n' "$RAW_LOG" "$SUMMARY"
