#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.native-cache/meeting"
REPORT="$ROOT/app/build/reports/meeting/native"
SOURCE="$CACHE/source/nemo-speech"
SPM_SOURCE="$CACHE/source/sentencepiece"
SPM_BUILD="$CACHE/build/sentencepiece"
SPM_PREFIX="$CACHE/deps/sentencepiece"
BUILD="$CACHE/build/android-arm64"
MODELS="$CACHE/models"
NDK="${ANDROID_SDK_ROOT:-/Users/ulliemaillot/Library/Android/sdk}/ndk/28.1.13356709"
if [[ ! -d "$NDK" ]]; then
    NDK="/Users/ulliemaillot/Library/Android/sdk/ndk/28.1.13356709"
fi
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
PATCH="$ROOT/scripts/native/meeting-android.patch"
NEMO_REV=97a15afa5caa9bce5baaa86c1184103877af4101
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
mkdir -p "$REPORT" "$CACHE/source" "$CACHE/models"
BUILD_LOG="$REPORT/t1-build.log"
exec > >(tee -a "$BUILD_LOG") 2>&1

printf 'T1 Android CPU probe build\nNDK=%s\nAPI=30 ABI=arm64-v8a STL=c++_static\nJobs=%s\n' "$NDK" "$JOBS"
cmake --version | head -1
git --version
[[ -f "$TOOLCHAIN" ]] || { printf 'ERROR: NDK toolchain not found: %s\n' "$TOOLCHAIN" >&2; exit 2; }
command -v make >/dev/null || { printf 'ERROR: make is required\n' >&2; exit 2; }

if [[ ! -d "$SOURCE/.git" ]]; then
    git clone --filter=blob:none --no-checkout https://github.com/NVIDIA/NeMo-Speech.cpp.git "$SOURCE"
fi
git -C "$SOURCE" fetch --depth 1 origin "$NEMO_REV"
git -C "$SOURCE" checkout --detach "$NEMO_REV"
git -C "$SOURCE" submodule update --init --depth 1 ggml
actual_nemo_rev="$(git -C "$SOURCE" rev-parse HEAD)"
actual_ggml_rev="$(git -C "$SOURCE/ggml" rev-parse HEAD)"
printf 'NeMo-Speech.cpp=%s\nGGML=%s\n' "$actual_nemo_rev" "$actual_ggml_rev"
[[ "$actual_nemo_rev" == "$NEMO_REV" ]] || { printf 'ERROR: NeMo source revision mismatch\n' >&2; exit 2; }
[[ "$actual_ggml_rev" == c03b4e2bcece5134827881af90242086daf75be5 ]] || { printf 'ERROR: GGML gitlink mismatch\n' >&2; exit 2; }
mkdir -p "$SOURCE/app/src/main/cpp/meeting"
STAGING="$SOURCE/app/src/main/cpp/meeting"
for source_file in CMakeLists.txt meeting_probe.cpp meeting_jni.cpp meeting_native_logic.cpp \
    meeting_native_logic.h meeting_jni.exports; do
    install -m 0644 "$ROOT/app/src/main/cpp/meeting/$source_file" "$STAGING/$source_file"
done

if git -C "$SOURCE" apply --check "$PATCH"; then
    git -C "$SOURCE" apply "$PATCH"
else
    grep -Fq 'add_library(nemo_speech_asr STATIC ${ASR_SOURCES})' "$SOURCE/src/asr/CMakeLists.txt" || {
        printf 'ERROR: expected static ASR CMake patch is not present\n' >&2
        exit 2
    }
    grep -Fq 'add_subdirectory(app/src/main/cpp/meeting)' "$SOURCE/CMakeLists.txt" || {
        printf 'ERROR: meeting probe CMake patch is not present\n' >&2
        exit 2
    }
    printf 'Pinned Android CMake patch already applied.\n'
fi

if [[ ! -d "$SPM_SOURCE/.git" ]]; then
    git clone --filter=blob:none --no-checkout https://github.com/google/sentencepiece.git "$SPM_SOURCE"
fi
git -C "$SPM_SOURCE" fetch --depth 1 origin "$SPM_REV"
git -C "$SPM_SOURCE" checkout --detach "$SPM_REV"
actual_spm_rev="$(git -C "$SPM_SOURCE" rev-parse HEAD)"
printf 'SentencePiece=%s\n' "$actual_spm_rev"
[[ "$actual_spm_rev" == "$SPM_REV" ]] || { printf 'ERROR: SentencePiece revision mismatch\n' >&2; exit 2; }

cmake -G 'Unix Makefiles' \
    -S "$SPM_SOURCE" \
    -B "$SPM_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30 \
    -DCMAKE_ANDROID_STL_TYPE=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DSPM_BUILD_TEST=OFF \
    -DSPM_ENABLE_SHARED=OFF \
    -DSPM_ENABLE_TCMALLOC=OFF
cmake --build "$SPM_BUILD" --target sentencepiece-static --parallel "$JOBS"
mkdir -p "$SPM_PREFIX/lib" "$SPM_PREFIX/include"
install -m 0644 "$SPM_BUILD/src/libsentencepiece.a" "$SPM_PREFIX/lib/libsentencepiece.a"
install -m 0644 "$SPM_SOURCE/src/sentencepiece_processor.h" "$SPM_PREFIX/include/sentencepiece_processor.h"
LICENSE_DIR="$SPM_PREFIX/share/licenses/nemo-speech/third_party/sentencepiece"
mkdir -p "$LICENSE_DIR"
install -m 0644 "$SPM_SOURCE/LICENSE" "$LICENSE_DIR/LICENSE"
install -m 0644 "$SPM_SOURCE/third_party/absl/LICENSE" "$LICENSE_DIR/absl-LICENSE"
install -m 0644 "$SPM_SOURCE/third_party/darts_clone/LICENSE" "$LICENSE_DIR/darts-clone-LICENSE"
install -m 0644 "$SPM_SOURCE/third_party/protobuf-lite/LICENSE" "$LICENSE_DIR/protobuf-lite-LICENSE"

fetch_model() {
    local repo="$1" revision="$2" filename="$3" expected_size="$4" expected_sha="$5"
    local output="$MODELS/$filename" temporary="$MODELS/$filename.part"
    local url="https://huggingface.co/$repo/resolve/$revision/$filename"
    local actual_sha actual_size
    if [[ -f "$output" ]]; then
        actual_sha="$(shasum -a 256 "$output" | awk '{print $1}')"
        actual_size="$(wc -c < "$output" | tr -d '[:space:]')"
        if [[ "$actual_sha" == "$expected_sha" && "$actual_size" == "$expected_size" ]]; then
            printf 'MODEL_OK %s bytes=%s sha256=%s\n' "$output" "$actual_size" "$actual_sha"
            return
        fi
    fi
    curl -fL --retry 3 --retry-delay 1 --progress-bar "$url" -o "$temporary"
    actual_sha="$(shasum -a 256 "$temporary" | awk '{print $1}')"
    actual_size="$(wc -c < "$temporary" | tr -d '[:space:]')"
    [[ "$actual_sha" == "$expected_sha" ]] || { printf 'ERROR: model SHA mismatch for %s\n' "$filename" >&2; exit 2; }
    [[ "$actual_size" == "$expected_size" ]] || { printf 'ERROR: model size mismatch for %s\n' "$filename" >&2; exit 2; }
    mv "$temporary" "$output"
    printf 'MODEL_OK %s bytes=%s sha256=%s\n' "$output" "$actual_size" "$actual_sha"
}
fetch_model nvidia/nemotron-3.5-asr-streaming-0.6b "$ASR_REV" "$ASR_FILE" "$ASR_SIZE" "$ASR_SHA"
fetch_model nvidia/Nemotron-3-Diarization "$DIAR_REV" "$DIAR_FILE" "$DIAR_SIZE" "$DIAR_SHA"
FIXTURE="$SOURCE/test_files/diar/ami_en2002d_2132.wav"
FIXTURE_SHA=40397e464c4f51d8c71f8859d056ad99fb1ba6ff
actual_fixture_blob="$(git -C "$SOURCE" hash-object "$FIXTURE")"
[[ "$actual_fixture_blob" == "$FIXTURE_SHA" ]] || { printf 'ERROR: upstream fixture Git blob mismatch\n' >&2; exit 2; }
actual_fixture_sha="$(shasum -a 256 "$FIXTURE" | awk '{print $1}')"
printf 'FIXTURE %s bytes=%s git_blob_sha1=%s sha256=%s\n' "$FIXTURE" "$(wc -c < "$FIXTURE" | tr -d '[:space:]')" "$actual_fixture_blob" "$actual_fixture_sha"

cmake -G 'Unix Makefiles' \
    -S "$SOURCE" \
    -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30 \
    -DCMAKE_ANDROID_STL_TYPE=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
    -DNEMO_SPEECH_BUILD_ASR=ON \
    -DNEMO_SPEECH_BUILD_DIAR=ON \
    -DNEMO_SPEECH_BUILD_TTS=OFF \
    -DNEMO_SPEECH_BUILD_NMT=OFF \
    -DNEMO_SPEECH_BUILD_S2S=OFF \
    -DNEMO_SPEECH_BUILD_CLI=OFF \
    -DNEMO_SPEECH_BUILD_MIC_CAPTURE=OFF \
    -DNEMO_SPEECH_BUILD_HTTP=OFF \
    -DNEMO_SPEECH_BUILD_GRPC=OFF \
    -DNEMO_SPEECH_BUILD_EXAMPLES=OFF \
    -DNEMO_SPEECH_BUILD_TESTS=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    -DNEMO_SPEECH_GGML_PATCHED=OFF \
    -DNEMO_SPEECH_DEPENDENCY_PREFIX="$CACHE/deps" \
    -DSENTENCEPIECE_STATIC_LIB:FILEPATH="$SPM_PREFIX/lib/libsentencepiece.a" \
    -DSENTENCEPIECE_INCLUDE_DIR:PATH="$SPM_PREFIX/include" \
    -DGGML_CUDA=OFF \
    -DGGML_METAL=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_BLAS=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DGGML_CPU_ALL_VARIANTS=OFF \
    -DGGML_BUILD_TESTS=OFF
cmake --build "$BUILD" --target meeting_probe --parallel "$JOBS"

PROBE="$BUILD/bin/meeting_probe"
LLVM_HOST_PREBUILT=""
for candidate in "$NDK"/toolchains/llvm/prebuilt/*; do
    if [[ -d "$candidate" ]]; then
        LLVM_HOST_PREBUILT="$candidate"
        break
    fi
done
[[ -n "$LLVM_HOST_PREBUILT" ]] || { printf 'ERROR: NDK LLVM host tools were not found\n' >&2; exit 2; }
READELF="$LLVM_HOST_PREBUILT/bin/llvm-readelf"
NM="$LLVM_HOST_PREBUILT/bin/llvm-nm"
[[ -x "$PROBE" ]] || { printf 'ERROR: probe executable was not produced\n' >&2; exit 2; }
"$READELF" --file-header --program-headers --dynamic-table --wide "$PROBE" > "$REPORT/meeting_probe.readelf.txt"
"$NM" --dynamic --defined-only --demangle "$PROBE" > "$REPORT/meeting_probe.dynamic-symbols.txt"
grep -Fq 'Class:                             ELF64' "$REPORT/meeting_probe.readelf.txt"
grep -Fq 'Machine:                           AArch64' "$REPORT/meeting_probe.readelf.txt"
if grep -E 'NEEDED.*(libggml|libsentencepiece|libc\+\+_shared)' "$REPORT/meeting_probe.readelf.txt"; then
    printf 'ERROR: probe has a forbidden shared runtime dependency\n' >&2
    exit 2
fi
if grep -E 'ggml_|sentencepiece|absl::|google::protobuf' "$REPORT/meeting_probe.dynamic-symbols.txt"; then
    printf 'ERROR: private dependency symbols are exported\n' >&2
    exit 2
fi
load_count=0
while IFS= read -r alignment; do
    load_count=$((load_count + 1))
    hex_alignment="${alignment#0x}"
    numeric_alignment=$((16#$hex_alignment))
    ((numeric_alignment >= 16384)) || {
        printf 'ERROR: ELF LOAD alignment below 16 KiB: %s\n' "$alignment" >&2
        exit 2
    }
done < <("$READELF" --program-headers --wide "$PROBE" | awk '$1 == "LOAD" {print $NF}')
((load_count > 0)) || { printf 'ERROR: no ELF LOAD segments were read\n' >&2; exit 2; }
printf 'ELF_OK ABI=arm64-v8a load_segments=%s minimum_alignment=16KiB\n' "$load_count"
printf 'PROBE %s bytes=%s sha256=' "$PROBE" "$(wc -c < "$PROBE" | tr -d '[:space:]')"
shasum -a 256 "$PROBE"
printf 'OUTPUTS source=%s\nmodels=%s\nprobe=%s\nreadelf=%s\n' "$SOURCE" "$MODELS" "$PROBE" "$REPORT/meeting_probe.readelf.txt"
