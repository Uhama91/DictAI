#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.native-cache/meeting"
REPORT="$ROOT/app/build/reports/meeting/native"
PROBE="$CACHE/build/android-arm64/bin/meeting_probe"
ASR_MODEL="$CACHE/models/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf"
DIAR_MODEL="$CACHE/models/Nemotron-3-Diarization.q8_0.gguf"
FIXTURE="$CACHE/source/nemo-speech/test_files/diar/ami_en2002d_2132.wav"
FIXTURE="${MEETING_WAV:-$FIXTURE}"
RUN_LABEL="${MEETING_LABEL:-ami-upstream}"
RUN_LOG="${MEETING_LOG:-$REPORT/probe-emulator.log}"
EXISTING_RUNTIME="$ROOT/app/src/main/jniLibs/arm64-v8a"
RUNTIME_MANIFEST="$ROOT/scripts/native/meeting-existing-runtime.manifest"

verify_log() {
    local log_path="$1"
    [[ -f "$log_path" ]] || { printf 'FAIL: log absent: %s\n' "$log_path" >&2; return 2; }
    awk '
        function field(name,    pattern) {
            pattern=name "=[^[:space:]]+"
            if (match($0, pattern))
                return substr($0, RSTART + length(name) + 1, RLENGTH - length(name) - 1)
            return ""
        }
        function speaker_return(value,    parts, count, i, seen, previous) {
            count=split(value, parts, ",")
            if (count < 2) return 0
            previous=parts[1]
            seen[previous]=1
            for (i=2; i<=count; i++) {
                if (parts[i]!=previous && seen[parts[i]]) return 1
                seen[parts[i]]=1
                previous=parts[i]
            }
            return 0
        }
        /^MODEL_CAPACITY stage=diar speakers=8$/ { model_ok=1 }
        /^RUNTIME_COEXIST status=loaded / { runtime_loaded++ }
        /^RUNTIME_COEXIST_SUMMARY libraries=9 status=ok$/ { runtime_summary=1 }
        /^FINISH_BEGIN stage=diar-realtime / { finish_realtime=1 }
        /^FINISH_BEGIN stage=diar-burst / { finish_burst=1 }
        /^RESULT stage=diar-realtime / {
            tag=field("speaker_tag")+0; pushed=field("pushed_ms")+0; total=field("total_ms")+0
            if (!finish_realtime && tag>0 && pushed<total) tags_realtime[tag]=1
        }
        /^RESULT stage=diar-burst / {
            tag=field("speaker_tag")+0; pushed=field("pushed_ms")+0; total=field("total_ms")+0
            if (!finish_burst && tag>0 && pushed<total) tags_burst[tag]=1
        }
        /^SPEAKER_SEQUENCE stage=diar-realtime / && !finish_realtime {
            sequence=field("sequence"); returned=speaker_return(sequence)
        }
        END {
            n_rt=0; ids_rt=""
            for (i=1; i<=8; i++) if (tags_realtime[i]) { n_rt++; ids_rt=ids_rt (ids_rt ? "," : "") i }
            n_burst=0; ids_burst=""
            for (i=1; i<=8; i++) if (tags_burst[i]) { n_burst++; ids_burst=ids_burst (ids_burst ? "," : "") i }
            ok=1
            if (!model_ok) { print "FAIL: diarization model is not verified as eight-speaker" > "/dev/stderr"; ok=0 }
            if (runtime_loaded!=9 || !runtime_summary) { print "FAIL: existing and new native runtimes did not coexist with all nine libraries in one process" > "/dev/stderr"; ok=0 }
            if (n_rt<2) { print "FAIL: fewer than two distinct positive speaker tags before realtime EOF" > "/dev/stderr"; ok=0 }
            if (n_burst<2) { print "FAIL: fewer than two distinct positive speaker tags before burst EOF" > "/dev/stderr"; ok=0 }
            if (!returned) { print "FAIL: stable realtime speaker sequence has no return to an earlier speaker" > "/dev/stderr"; ok=0 }
            if (!finish_realtime || !finish_burst) { print "FAIL: missing finish boundary" > "/dev/stderr"; ok=0 }
            if (ok) {
                printf "PASS: 8-speaker model; realtime tags={%s}, sequence=%s returned=true; burst tags={%s}\n", ids_rt, sequence, ids_burst
            }
            exit !ok
        }
    ' "$log_path"
}

verify_runtime_manifest() {
    local remote_runtime="${1:-}"
    local runtime_name expected_size expected_sha extra library runtime_sha runtime_size
    local runtime_count=0
    [[ -f "$RUNTIME_MANIFEST" ]] || {
        printf 'FAIL: historical runtime manifest missing: %s\n' "$RUNTIME_MANIFEST" >&2
        return 2
    }
    while IFS=' ' read -r runtime_name expected_size expected_sha extra || \
        [[ -n "${runtime_name:-}" ]]; do
        [[ -n "${runtime_name:-}" && -n "${expected_size:-}" &&
           -n "${expected_sha:-}" && -z "${extra:-}" ]] || {
            printf 'FAIL: malformed historical runtime manifest row\n' >&2
            return 2
        }
        [[ "$runtime_name" =~ ^lib[a-z0-9._-]+\.so$ && "$runtime_name" != libdictai_meeting.so &&
           "$expected_size" =~ ^[0-9]+$ && "$expected_sha" =~ ^[a-f0-9]{64}$ ]] || {
            printf 'FAIL: unexpected historical runtime manifest row: %s\n' "$runtime_name" >&2
            return 2
        }
        library="$EXISTING_RUNTIME/$runtime_name"
        [[ -f "$library" ]] || { printf 'FAIL: manifest library missing: %s\n' "$library" >&2; return 2; }
        runtime_sha="$(shasum -a 256 "$library" | awk '{print $1}')"
        runtime_size="$(wc -c < "$library" | tr -d '[:space:]')"
        [[ "$runtime_sha" == "$expected_sha" && "$runtime_size" == "$expected_size" ]] || {
            printf 'FAIL: historical runtime identity mismatch: %s\n' "$runtime_name" >&2
            return 2
        }
        if [[ -n "$remote_runtime" ]]; then
            printf 'EXISTING_RUNTIME source=%s bytes=%s sha256=%s\n' "$library" "$runtime_size" "$runtime_sha"
            adb -s "$DEVICE" push "$library" "$remote_runtime/$runtime_name"
            coexist_args+=(--coexist-lib "$remote_runtime/$runtime_name")
        else
            printf 'RUNTIME_MANIFEST_ENTRY name=%s bytes=%s sha256=%s\n' \
                "$runtime_name" "$runtime_size" "$runtime_sha"
        fi
        runtime_count=$((runtime_count + 1))
    done < "$RUNTIME_MANIFEST"
    ((runtime_count == 9)) || {
        printf 'FAIL: expected exactly nine historical arm64 runtime libraries, got %s\n' \
            "$runtime_count" >&2
        return 2
    }
    printf 'RUNTIME_MANIFEST_OK libraries=%s\n' "$runtime_count"
}

if [[ "${1:-}" == "--verify-manifest" ]]; then
    [[ $# -eq 1 ]] || { printf 'Usage: %s --verify-manifest\n' "$0" >&2; exit 2; }
    verify_runtime_manifest
    exit
fi

if [[ "${1:-}" == "--verify-log" ]]; then
    [[ $# -eq 2 ]] || { printf 'Usage: %s --verify-log <probe-log>\n' "$0" >&2; exit 2; }
    verify_log "$2"
    exit
fi

if [[ ! -x "$PROBE" ]]; then
    printf 'FAIL: native probe executable missing: %s\n' "$PROBE" >&2
    exit 2
fi

for model in "$ASR_MODEL" "$DIAR_MODEL"; do
    [[ -f "$model" ]] || { printf 'FAIL: model missing: %s\n' "$model" >&2; exit 2; }
done
[[ -f "$FIXTURE" ]] || { printf 'FAIL: selected probe audio missing: %s\n' "$FIXTURE" >&2; exit 2; }
[[ -f "$RUNTIME_MANIFEST" ]] || { printf 'FAIL: historical runtime manifest missing: %s\n' "$RUNTIME_MANIFEST" >&2; exit 2; }
printf 'a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae  %s\n' "$ASR_MODEL" | shasum -a 256 -c
printf '08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1  %s\n' "$DIAR_MODEL" | shasum -a 256 -c

DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ABI="$(adb -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$ABI" == "arm64-v8a" ]] || { printf 'FAIL: expected arm64-v8a emulator, got %s\n' "$ABI" >&2; exit 2; }
REMOTE_DIR=/data/local/tmp/dictai-meeting
mkdir -p "$REPORT"
adb -s "$DEVICE" shell mkdir -p "$REMOTE_DIR"
adb -s "$DEVICE" push "$PROBE" "$REMOTE_DIR/meeting_probe"
adb -s "$DEVICE" push "$ASR_MODEL" "$REMOTE_DIR/asr.gguf"
adb -s "$DEVICE" push "$DIAR_MODEL" "$REMOTE_DIR/diar.gguf"
adb -s "$DEVICE" push "$FIXTURE" "$REMOTE_DIR/input.wav"
adb -s "$DEVICE" shell chmod 0700 "$REMOTE_DIR/meeting_probe"
REMOTE_RUNTIME="$REMOTE_DIR/existing-runtime"
adb -s "$DEVICE" shell mkdir -p "$REMOTE_RUNTIME"
coexist_args=()
verify_runtime_manifest "$REMOTE_RUNTIME"
fixture_sha="$(shasum -a 256 "$FIXTURE" | awk '{print $1}')"
fixture_size="$(wc -c < "$FIXTURE" | tr -d '[:space:]')"
mkdir -p "$(dirname "$RUN_LOG")"
printf 'PROBE_INPUT label=%s source=%s bytes=%s sha256=%s\n' "$RUN_LABEL" "$FIXTURE" "$fixture_size" "$fixture_sha" | tee "$RUN_LOG"
remote_command="LD_LIBRARY_PATH=$REMOTE_RUNTIME $REMOTE_DIR/meeting_probe --asr $REMOTE_DIR/asr.gguf --diar $REMOTE_DIR/diar.gguf --wav $REMOTE_DIR/input.wav"
for library_path in "${coexist_args[@]}"; do
    remote_command="$remote_command $library_path"
done
set -o pipefail
adb -s "$DEVICE" shell "$remote_command" 2>&1 | tee -a "$RUN_LOG"
verify_log "$RUN_LOG"
