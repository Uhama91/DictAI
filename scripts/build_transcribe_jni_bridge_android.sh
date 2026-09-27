#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cache_root="$repo_root/.native-cache/meeting-conversation-2026-09-27"
source_dir="$cache_root/transcribe.cpp"
build_dir="$cache_root/build/android-arm64-jni-bridge"
report_dir="$repo_root/app/build/reports/meeting/conversation-2026-09-27/native-jni"
bundle_dir="$repo_root/app/src/main/jniLibs/arm64-v8a"
ndk_root="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-/Users/ulliemaillot/Library/Android/sdk}/ndk/28.1.13356709}"
toolchain_file="$ndk_root/build/cmake/android.toolchain.cmake"
readonly expected_revision="553f1099a2b3a5bc4421894be171f09960fc0f3a"
readonly expected_transcribe_sha="e7878a83c00e70a11ac8bc05d6ba43685fb0edea865dcdd81f52e79442a5c4e4"

mkdir -p "$report_dir"
exec > >(tee "$report_dir/build.log") 2>&1

[[ -f "$toolchain_file" ]] || { echo "Missing Android NDK toolchain: $toolchain_file" >&2; exit 2; }
[[ -d "$source_dir/.git" ]] || { echo "Pinned transcribe.cpp checkout is missing: $source_dir" >&2; exit 2; }
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$expected_revision" ]] || {
    echo "transcribe.cpp source revision mismatch" >&2
    exit 2
}
[[ -f "$source_dir/include/transcribe.h" ]] || { echo "Pinned transcribe.h is missing" >&2; exit 2; }
[[ -f "$bundle_dir/libtranscribe.so" ]] || { echo "Delivered libtranscribe.so is missing" >&2; exit 2; }
actual_transcribe_sha=$(shasum -a 256 "$bundle_dir/libtranscribe.so" | awk '{print $1}')
[[ "$actual_transcribe_sha" == "$expected_transcribe_sha" ]] || {
    echo "Delivered libtranscribe.so SHA-256 mismatch: $actual_transcribe_sha" >&2
    exit 2
}

echo "Pinned transcribe.cpp=$expected_revision"
echo "Delivered libtranscribe.so SHA-256=$actual_transcribe_sha (reused, not rebuilt)"
echo "NDK=$ndk_root ABI=arm64-v8a API=30 STL=c++_static"

cmake -S "$repo_root/app/src/main/cpp" -B "$build_dir" -G "Unix Makefiles" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain_file" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30 \
    -DANDROID_STL=c++_static \
    -DTRANSCRIBE_SOURCE_DIR="$source_dir" \
    -DTRANSCRIBE_LIBRARY_DIR="$bundle_dir" \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384'
cmake --build "$build_dir" --target transcribe_jni --parallel 4

tool_root=$(find "$ndk_root/toolchains/llvm/prebuilt" -maxdepth 1 -mindepth 1 -type d | head -n 1)
strip_tool="$tool_root/bin/llvm-strip"
readelf_tool="$tool_root/bin/llvm-readelf"
symbol_tool="$tool_root/bin/llvm-nm"
library="$build_dir/libtranscribe_jni.so"
[[ -x "$strip_tool" && -x "$readelf_tool" && -x "$symbol_tool" && -f "$library" ]] || {
    echo "Built JNI library or required NDK tools are missing" >&2
    exit 2
}
"$strip_tool" --strip-unneeded "$library"
"$readelf_tool" --file-header --program-headers --dynamic-table --wide "$library" \
    > "$report_dir/libtranscribe_jni.readelf.txt"
"$symbol_tool" --dynamic --defined-only --demangle "$library" \
    > "$report_dir/libtranscribe_jni.dynamic-symbols.txt"
grep -Fq 'Class:                             ELF64' "$report_dir/libtranscribe_jni.readelf.txt"
grep -Fq 'Machine:                           AArch64' "$report_dir/libtranscribe_jni.readelf.txt"
grep -Fq 'Shared library: [libtranscribe.so]' "$report_dir/libtranscribe_jni.readelf.txt"
grep -Eq '[[:space:]]JNI_OnLoad$' "$report_dir/libtranscribe_jni.dynamic-symbols.txt" || {
    echo "JNI_OnLoad is missing from the dynamic symbol table" >&2
    exit 2
}
load_count=0
while IFS= read -r alignment; do
    load_count=$((load_count + 1))
    hex_alignment="${alignment#0x}"
    numeric_alignment=$((16#$hex_alignment))
    ((numeric_alignment >= 16384)) || { echo "LOAD alignment below 16 KiB: $alignment" >&2; exit 2; }
done < <("$readelf_tool" --program-headers --wide "$library" | awk '$1 == "LOAD" {print $NF}')
((load_count > 0)) || { echo "No ELF LOAD segments found" >&2; exit 2; }

old_bridge_sha=$(shasum -a 256 "$bundle_dir/libtranscribe_jni.so" | awk '{print $1}')
install -m 0644 "$library" "$bundle_dir/libtranscribe_jni.so"
cmp -s "$library" "$bundle_dir/libtranscribe_jni.so"
echo "ELF_OK class=ELF64 machine=AArch64 loads=$load_count minimum_alignment=16KiB JNI_OnLoad=present"
echo "REPLACED libtranscribe_jni.so old_sha256=$old_bridge_sha"
echo "NEW libtranscribe_jni.so bytes=$(wc -c < "$bundle_dir/libtranscribe_jni.so" | tr -d '[:space:]') sha256=$(shasum -a 256 "$bundle_dir/libtranscribe_jni.so" | awk '{print $1}')"
echo "REPORTS $report_dir"
