#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/build-app.sh
preview="$PWD/dist/DictAI Preview.app"
ditto "$PWD/dist/DictAI.app" "$preview"
/usr/libexec/PlistBuddy -c 'Set :CFBundleIdentifier com.uhama.dictai.desktop.preview' "$preview/Contents/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleName DictAI Preview' "$preview/Contents/Info.plist"
/usr/libexec/PlistBuddy -c 'Set :CFBundleDisplayName DictAI Preview' "$preview/Contents/Info.plist"
/usr/libexec/PlistBuddy -c 'Add :DictAIPreview bool true' "$preview/Contents/Info.plist" 2>/dev/null || /usr/libexec/PlistBuddy -c 'Set :DictAIPreview true' "$preview/Contents/Info.plist"
codesign --force --sign - "$preview"
printf '%s\n' "$preview"
