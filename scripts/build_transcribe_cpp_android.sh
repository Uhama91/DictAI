#!/usr/bin/env bash
set -euo pipefail

readonly transcribe_repo_url="https://github.com/handy-computer/transcribe.cpp.git"
readonly transcribe_commit="553f1099a2b3a5bc4421894be171f09960fc0f3a"
readonly android_abi="arm64-v8a"
readonly android_api="30"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ndk_root="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to Android NDK r28b}"
toolchain_file="$ndk_root/build/cmake/android.toolchain.cmake"
strip_tool=$(find "$ndk_root/toolchains/llvm/prebuilt" -path '*/bin/llvm-strip' -print | head -n 1)
build_root=$(mktemp -d "${TMPDIR:-/tmp}/transcribe-cpp-android.XXXXXX")
source_dir="${TRANSCRIBE_CPP_SOURCE_DIR:-$build_root/transcribe.cpp}"
bundle_dir="${TRANSCRIBE_CPP_BUNDLE_DIR:-$repo_root/app/src/main/jniLibs/$android_abi}"
stage_dir="$build_root/bundle"

if [[ ! -f "$toolchain_file" || ! -x "$strip_tool" ]]; then
    echo "Android NDK r28b toolchain is incomplete: $ndk_root" >&2
    exit 1
fi

if [[ ! -d "$source_dir/.git" ]]; then
    git clone "$transcribe_repo_url" "$source_dir"
fi
git -C "$source_dir" checkout --detach "$transcribe_commit"
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$transcribe_commit" ]]; then
    echo "Could not resolve transcribe.cpp commit $transcribe_commit" >&2
    exit 1
fi

transcribe_build="$build_root/transcribe-build"
cmake -S "$source_dir" -B "$transcribe_build" -G "Unix Makefiles" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain_file" \
    -DANDROID_ABI="$android_abi" \
    -DANDROID_PLATFORM="android-$android_api" \
    -DANDROID_STL=c++_static \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384' \
    -DTRANSCRIBE_BUILD_SHARED=ON \
    -DTRANSCRIBE_BUILD_EXAMPLES=OFF \
    -DTRANSCRIBE_BUILD_TESTS=OFF \
    -DTRANSCRIBE_BUILD_TOOLS=OFF \
    -DTRANSCRIBE_BUILD_REAL_MODEL_TESTS=OFF \
    -DTRANSCRIBE_INSTALL=OFF \
    -DTRANSCRIBE_GGML_BACKEND_DL=OFF \
    -DGGML_CPU=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_LTO=OFF \
    -DGGML_ACCELERATE=OFF \
    -DGGML_BLAS=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_METAL=OFF \
    -DGGML_VULKAN=OFF
cmake --build "$transcribe_build" --parallel

bridge_build="$build_root/bridge-build"
cmake -S "$repo_root/app/src/main/cpp" -B "$bridge_build" -G "Unix Makefiles" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain_file" \
    -DANDROID_ABI="$android_abi" \
    -DANDROID_PLATFORM="android-$android_api" \
    -DANDROID_STL=c++_static \
    -DTRANSCRIBE_SOURCE_DIR="$source_dir" \
    -DTRANSCRIBE_LIBRARY_DIR="$transcribe_build/src" \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384'
cmake --build "$bridge_build" --parallel

mkdir -p "$stage_dir"
install -m 0755 "$bridge_build/libtranscribe_jni.so" "$stage_dir/libtranscribe_jni.so"
install -m 0755 "$transcribe_build/src/libtranscribe.so" "$stage_dir/libtranscribe.so"
install -m 0755 "$transcribe_build/ggml/src/libggml.so" "$stage_dir/libggml.so"
install -m 0755 "$transcribe_build/ggml/src/libggml-base.so" "$stage_dir/libggml-base.so"
install -m 0755 "$transcribe_build/ggml/src/libggml-cpu.so" "$stage_dir/libggml-cpu.so"
"$strip_tool" --strip-unneeded "$stage_dir"/*.so

ANDROID_NDK_HOME="$ndk_root" TRANSCRIBE_CPP_BUNDLE_DIR="$stage_dir" "$repo_root/scripts/test_transcribe_cpp_bundle.sh"
mkdir -p "$bundle_dir"
for library in "$stage_dir"/*.so; do
    install -m 0755 "$library" "$bundle_dir/$(basename "$library")"
done

echo "Built transcribe.cpp $transcribe_commit for $android_abi (API $android_api): $bundle_dir"
