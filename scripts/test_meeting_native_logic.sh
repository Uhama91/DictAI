#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/.native-cache/meeting/build/host-tests/meeting_native_logic_test"
mkdir -p "$(dirname "$OUT")"
c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$ROOT/app/src/main/cpp/meeting" \
    "$ROOT/app/src/main/cpp/meeting/meeting_native_logic.cpp" \
    "$ROOT/app/src/test/cpp/meeting_native_logic_test.cpp" \
    -o "$OUT"
"$OUT"
