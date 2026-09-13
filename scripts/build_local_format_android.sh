#!/usr/bin/env bash
set -euo pipefail
readonly llama_commit="1511ce3bc3f087376c8526b4ad07100bfabb277f"
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ndk_root="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to NDK 28.2.13676358}"
cmake_bin="${DICTAI_CMAKE:-cmake}"
source_dir="${DICTAI_LLAMA_SOURCE_DIR:-$repo_root/.native-cache/llama.cpp-v0.1.2}"
build_dir="${DICTAI_LLM_BUILD_DIR:-$repo_root/.native-cache/local-format-android}"
bundle_dir="${DICTAI_LLM_BUNDLE_DIR:-$repo_root/app/src/main/jniLibs/arm64-v8a}"
if [[ ! -d "$source_dir/.git" ]]; then
    mkdir -p "$(dirname "$source_dir")"
    git clone --depth 1 --branch v0.1.2 https://github.com/ggml-org/llama.cpp.git "$source_dir"
fi
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$llama_commit" ]] || [[ -n "$(git -C "$source_dir" status --porcelain --untracked-files=no)" ]]; then
    echo "llama.cpp source must be a clean checkout at $llama_commit" >&2
    exit 1
fi
if ! grep -q '28.2.13676358' "$ndk_root/source.properties"; then
    echo "Expected Android NDK 28.2.13676358" >&2
    exit 1
fi
strip_tool="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
stage_dir="$build_dir/verified-bundle"
mkdir -p "$stage_dir"
for variant in baseline arm82; do
    variant_build="$build_dir"
    target="dictai_llm"
    arm82=OFF
    if [[ "$variant" == arm82 ]]; then
        variant_build="${build_dir}-arm82"
        target="dictai_llm_arm82"
        arm82=ON
    fi
    "$cmake_bin" -S "$repo_root/app/src/main/cpp/llm" -B "$variant_build" -G "Unix Makefiles" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static \
        -DDICTAI_ARM82_VARIANT="$arm82" -DLLAMA_SOURCE_DIR="$source_dir"
    "$cmake_bin" --build "$variant_build" --target "$target" --parallel "${DICTAI_BUILD_JOBS:-3}"
    "$strip_tool" --strip-unneeded "$variant_build/out/lib${target}.so"
    ANDROID_NDK_HOME="$ndk_root" DICTAI_LLM_BUNDLE_DIR="$variant_build/out" \
        DICTAI_LLM_LIBRARY_NAME="lib${target}.so" bash "$repo_root/scripts/test_local_format_bundle.sh"
    install -m 0755 "$variant_build/out/lib${target}.so" "$stage_dir/lib${target}.so"
done
ANDROID_NDK_HOME="$ndk_root" DICTAI_LLM_BUNDLE_DIR="$stage_dir" bash "$repo_root/scripts/test_local_format_bundle.sh"
mkdir -p "$bundle_dir"
install -m 0755 "$stage_dir/libdictai_llm.so" "$bundle_dir/libdictai_llm.so"
install -m 0755 "$stage_dir/libdictai_llm_arm82.so" "$bundle_dir/libdictai_llm_arm82.so"
install -m 0644 "$source_dir/LICENSE" "$repo_root/app/src/main/assets/local-format/llama-LICENSE.txt"
printf 'Built isolated local formatters at llama.cpp %s: %s\n' "$llama_commit" "$bundle_dir"
