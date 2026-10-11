#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Library/Developer/CommandLineTools}"
# AppKit window/focus tests and shortcut timers share NSApplication and its main loop.
if [[ "$DEVELOPER_DIR" == /Library/Developer/CommandLineTools ]]; then
  frameworks="$DEVELOPER_DIR/Library/Developer/Frameworks"
  swift test --no-parallel -Xswiftc -F -Xswiftc "$frameworks" -Xlinker -F -Xlinker "$frameworks" -Xlinker -rpath -Xlinker "$frameworks" -Xlinker -rpath -Xlinker "$DEVELOPER_DIR/Library/Developer/usr/lib" "$@"
else
  swift test --no-parallel "$@"
fi
