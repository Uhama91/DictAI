#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
bundle_dir="${TRANSCRIBE_CPP_BUNDLE_DIR:-$repo_root/app/src/main/jniLibs/arm64-v8a}"
ndk_root="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to Android NDK r28b}"
tool_root=$(find "$ndk_root/toolchains/llvm/prebuilt" -maxdepth 1 -mindepth 1 -type d | head -n 1)

if [[ -z "$tool_root" ]]; then
    echo "Android NDK host toolchain not found under $ndk_root" >&2
    exit 1
fi

readelf="$tool_root/bin/llvm-readelf"
if [[ ! -x "$readelf" ]]; then
    echo "llvm-readelf not found at $readelf" >&2
    exit 1
fi

required_libs=(
    libtranscribe_jni.so
    libtranscribe.so
    libggml.so
    libggml-base.so
    libggml-cpu.so
)

for library in "${required_libs[@]}"; do
    path="$bundle_dir/$library"
    if [[ ! -f "$path" ]]; then
        echo "Missing native bundle member: $path" >&2
        exit 1
    fi

    "$readelf" -h "$path" | grep -E 'Class:.*ELF64' >/dev/null
    "$readelf" -h "$path" | grep -E 'Machine:.*AArch64' >/dev/null
    "$readelf" -lW "$path" | awk '
        $1 == "LOAD" {
            found = 1
            if ($NF != "0x4000") bad = 1
        }
        END { exit(found && !bad ? 0 : 1) }
    '
    if "$readelf" -SW "$path" | grep -E '\.debug_' >/dev/null; then
        echo "Unstripped debug section in $library" >&2
        exit 1
    fi
done

require_needed() {
    local library=$1
    local dependency=$2
    "$readelf" -d "$bundle_dir/$library" | grep -F "Shared library: [$dependency]" >/dev/null
}

require_symbol() {
    local library=$1
    local symbol=$2
    "$readelf" -Ws "$bundle_dir/$library" | grep -E "GLOBAL.*$symbol$" >/dev/null
}

require_needed libtranscribe_jni.so libtranscribe.so
require_needed libtranscribe.so libggml.so
require_needed libtranscribe.so libggml-base.so
require_needed libtranscribe.so libggml-cpu.so
require_needed libggml.so libggml-base.so
require_needed libggml-cpu.so libggml-base.so

require_symbol libtranscribe_jni.so JNI_OnLoad
for symbol in \
    transcribe_init_backends_default \
    transcribe_open \
    transcribe_session_free \
    transcribe_stream_begin \
    transcribe_stream_feed \
    transcribe_stream_get_text \
    transcribe_stream_finalize \
    transcribe_stream_reset; do
    require_symbol libtranscribe.so "$symbol"
done

echo "transcribe.cpp Android arm64-v8a bundle contract: PASS"
