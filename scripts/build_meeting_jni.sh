#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.native-cache/meeting"
REPORT="$ROOT/app/build/reports/meeting/native"
SOURCE="$CACHE/source/nemo-speech"
SPM_SOURCE="$CACHE/source/sentencepiece"
SPM_PREFIX="$CACHE/deps/sentencepiece"
BUILD="$CACHE/build/android-arm64"
MODELS="$CACHE/models"
NDK="${ANDROID_SDK_ROOT:-/Users/ulliemaillot/Library/Android/sdk}/ndk/28.1.13356709"
[[ -d "$NDK" ]] || NDK="/Users/ulliemaillot/Library/Android/sdk/ndk/28.1.13356709"
NEMO_REV=97a15afa5caa9bce5baaa86c1184103877af4101
GGML_REV=c03b4e2bcece5134827881af90242086daf75be5
SPM_REV=17d7580d6407802f85855d2cc9190634e2c95624
ASR_REV=1c8deaecc64b91f034d73e08dd8b64625eb3395d
DIAR_REV=f667ed73aee57d40cc39428eb768b4fd87a0a29e
ASR_FILE=nemotron-3.5-asr-streaming-0.6b.q8_0.gguf
DIAR_FILE=Nemotron-3-Diarization.q8_0.gguf
ASR_SHA=a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae
DIAR_SHA=08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1
ASR_SIZE=741548352
DIAR_SIZE=107012128
JOBS=4
STAGING="$SOURCE/app/src/main/cpp/meeting"
LIB="$BUILD/lib/libdictai_meeting.so"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a/libdictai_meeting.so"

mkdir -p "$REPORT"
BUILD_LOG="$REPORT/t4b-build.log"
exec > >(tee -a "$BUILD_LOG") 2>&1

printf 'T4b JNI Android build\nNDK=%s\nAPI=30 ABI=arm64-v8a STL=c++_static\nJobs=%s\n' "$NDK" "$JOBS"
printf 'NeMo-Speech.cpp=%s\nGGML=%s\nSentencePiece=%s\nASR model revision=%s\nDiar model revision=%s\n' \
    "$NEMO_REV" "$GGML_REV" "$SPM_REV" "$ASR_REV" "$DIAR_REV"
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || {
    printf 'ERROR: NDK toolchain is missing\n' >&2
    exit 2
}
[[ -d "$SOURCE/.git" && -d "$SPM_SOURCE/.git" && -f "$BUILD/CMakeCache.txt" ]] || {
    printf 'ERROR: T1 pinned sources and Android build cache must exist first\n' >&2
    exit 2
}
for setting in \
    'ANDROID_ABI:UNINITIALIZED=arm64-v8a' \
    'ANDROID_PLATFORM:UNINITIALIZED=android-30' \
    'CMAKE_ANDROID_STL_TYPE:UNINITIALIZED=c++_static' \
    'CMAKE_BUILD_TYPE:STRING=Release' \
    'CMAKE_POSITION_INDEPENDENT_CODE:UNINITIALIZED=ON'; do
    grep -Fqx "$setting" "$BUILD/CMakeCache.txt" || {
        printf 'ERROR: cached Android build setting mismatch: %s\n' "$setting" >&2
        exit 2
    }
done
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$NEMO_REV" ]] || {
    printf 'ERROR: NeMo-Speech.cpp revision mismatch\n' >&2
    exit 2
}
[[ "$(git -C "$SOURCE/ggml" rev-parse HEAD)" == "$GGML_REV" ]] || {
    printf 'ERROR: GGML revision mismatch\n' >&2
    exit 2
}
[[ "$(git -C "$SPM_SOURCE" rev-parse HEAD)" == "$SPM_REV" ]] || {
    printf 'ERROR: SentencePiece revision mismatch\n' >&2
    exit 2
}
[[ -f "$SPM_PREFIX/lib/libsentencepiece.a" ]] || {
    printf 'ERROR: validated static SentencePiece archive is missing\n' >&2
    exit 2
}

verify_cached_model() {
    local path="$1" expected_size="$2" expected_sha="$3" actual_size actual_sha
    [[ -f "$path" ]] || { printf 'ERROR: validated model is missing: %s\n' "$path" >&2; exit 2; }
    actual_size="$(wc -c < "$path" | tr -d '[:space:]')"
    actual_sha="$(shasum -a 256 "$path" | awk '{print $1}')"
    [[ "$actual_size" == "$expected_size" && "$actual_sha" == "$expected_sha" ]] || {
        printf 'ERROR: cached model identity mismatch: %s\n' "$path" >&2
        exit 2
    }
    printf 'MODEL_CACHE_OK %s bytes=%s sha256=%s\n' "$path" "$actual_size" "$actual_sha"
}
verify_cached_model "$MODELS/$ASR_FILE" "$ASR_SIZE" "$ASR_SHA"
verify_cached_model "$MODELS/$DIAR_FILE" "$DIAR_SIZE" "$DIAR_SHA"

mkdir -p "$STAGING"
for source_file in CMakeLists.txt meeting_probe.cpp meeting_jni.cpp meeting_native_logic.cpp \
    meeting_native_logic.h meeting_jni.exports; do
    install -m 0644 "$ROOT/app/src/main/cpp/meeting/$source_file" "$STAGING/$source_file"
done
grep -Fq 'add_library(nemo_speech_asr STATIC ${ASR_SOURCES})' "$SOURCE/src/asr/CMakeLists.txt" || {
    printf 'ERROR: the pinned Android source patch did not make ASR static\n' >&2
    exit 2
}

# CMake reads the same pinned API/dependency cache as T1. The only requested target is the new JNI library.
cmake -S "$SOURCE" -B "$BUILD"
cmake --build "$BUILD" --target dictai_meeting --parallel "$JOBS"
[[ -f "$LIB" ]] || { printf 'ERROR: JNI shared library was not produced\n' >&2; exit 2; }

LLVM_HOST_PREBUILT=""
for candidate in "$NDK"/toolchains/llvm/prebuilt/*; do
    if [[ -d "$candidate" ]]; then LLVM_HOST_PREBUILT="$candidate"; break; fi
done
[[ -n "$LLVM_HOST_PREBUILT" ]] || { printf 'ERROR: NDK LLVM tools were not found\n' >&2; exit 2; }
STRIP="$LLVM_HOST_PREBUILT/bin/llvm-strip"
READELF="$LLVM_HOST_PREBUILT/bin/llvm-readelf"
NM="$LLVM_HOST_PREBUILT/bin/llvm-nm"
[[ -x "$STRIP" && -x "$READELF" && -x "$NM" ]] || {
    printf 'ERROR: required NDK LLVM tools are unavailable\n' >&2
    exit 2
}
"$STRIP" --strip-unneeded "$LIB"
"$READELF" --file-header --program-headers --dynamic-table --wide "$LIB" \
    > "$REPORT/libdictai_meeting.readelf.txt"
"$NM" --dynamic --defined-only --demangle "$LIB" \
    > "$REPORT/libdictai_meeting.dynamic-symbols.txt"
grep -Fq 'Class:                             ELF64' "$REPORT/libdictai_meeting.readelf.txt"
grep -Fq 'Machine:                           AArch64' "$REPORT/libdictai_meeting.readelf.txt"
if grep -E 'NEEDED.*(libggml|libsentencepiece|libc\+\+_shared)' \
    "$REPORT/libdictai_meeting.readelf.txt"; then
    printf 'ERROR: meeting JNI library has an external model-runtime dependency\n' >&2
    exit 2
fi
if grep -E 'ggml_|sentencepiece|absl::|google::protobuf|Java_com_' \
    "$REPORT/libdictai_meeting.dynamic-symbols.txt"; then
    printf 'ERROR: private dependency/JNI implementation symbols are exported\n' >&2
    exit 2
fi
defined_symbols="$(awk 'NF >= 3 {print $3}' "$REPORT/libdictai_meeting.dynamic-symbols.txt")"
[[ "$defined_symbols" == JNI_OnLoad ]] || {
    printf 'ERROR: expected JNI_OnLoad to be the only exported defined symbol, got: %s\n' \
        "$defined_symbols" >&2
    exit 2
}

load_count=0
while IFS= read -r alignment; do
    load_count=$((load_count + 1))
    hex_alignment="${alignment#0x}"
    numeric_alignment=$((16#$hex_alignment))
    ((numeric_alignment >= 16384)) || {
        printf 'ERROR: ELF LOAD alignment below 16 KiB: %s\n' "$alignment" >&2
        exit 2
    }
done < <("$READELF" --program-headers --wide "$LIB" | awk '$1 == "LOAD" {print $NF}')
((load_count > 0)) || { printf 'ERROR: no ELF LOAD segments were read\n' >&2; exit 2; }

LICENSE_SOURCE="$ROOT/app/src/main/assets/licenses/meeting"
for notice in nemo-speech-LICENSE.txt nemo-speech-NOTICE.txt ggml-LICENSE.txt \
    sentencepiece-LICENSE.txt absl-LICENSE.txt darts-clone-LICENSE.txt \
    esaxx-LICENSE.txt protobuf-lite-LICENSE.txt OpenMDW-1.1.txt SOURCES.md; do
    [[ -s "$LICENSE_SOURCE/$notice" ]] || {
        printf 'ERROR: bundled meeting license notice is missing: %s\n' "$notice" >&2
        exit 2
    }
done

mkdir -p "$(dirname "$DEST")"
install -m 0644 "$LIB" "$DEST"
cmp -s "$LIB" "$DEST" || { printf 'ERROR: staged library differs from build output\n' >&2; exit 2; }
if git -C "$ROOT" check-ignore -q "$DEST"; then
    printf 'ERROR: the new meeting library is still ignored by git\n' >&2
    exit 2
fi
printf 'ELF_OK ABI=arm64-v8a api=30 load_segments=%s minimum_alignment=16KiB exports=%s\n' \
    "$load_count" "$defined_symbols"
printf 'LIBRARY %s bytes=%s sha256=' "$LIB" "$(wc -c < "$LIB" | tr -d '[:space:]')"
shasum -a 256 "$LIB"
printf 'STAGED %s\n' "$DEST"
printf 'REPORTS %s\n' "$REPORT"
