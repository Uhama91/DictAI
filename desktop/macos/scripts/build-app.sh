#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Library/Developer/CommandLineTools}"
configuration="${CONFIGURATION:-release}"
for architecture in arm64 x86_64; do
  swift build -c "$configuration" --arch "$architecture"
done
arm_dir=$(swift build -c "$configuration" --arch arm64 --show-bin-path)
intel_dir=$(swift build -c "$configuration" --arch x86_64 --show-bin-path)
mkdir -p dist
staging=$(mktemp -d "$PWD/dist/build-XXXXXXXX")
destination="$staging/DictAI.app"
mkdir -p "$destination/Contents/MacOS" "$destination/Contents/Resources"
xcrun lipo -create "$arm_dir/DictAI" "$intel_dir/DictAI" -output "$destination/Contents/MacOS/DictAI"
xcrun lipo "$destination/Contents/MacOS/DictAI" -verify_arch arm64 x86_64
swiftc scripts/GenerateIcon.swift Sources/DictAI/Views/DictAIEmblem.swift -o .build/generate-icon
.build/generate-icon .build/DictAI.iconset
iconutil -c icns .build/DictAI.iconset -o "$destination/Contents/Resources/DictAI.icns"
cp -R Resources/Fonts "$destination/Contents/Resources/"
cp Resources/Info.plist "$destination/Contents/Info.plist"
cp ../../LICENSE ../../NOTICE "$destination/Contents/Resources/"
codesign --force --sign "${DICTAI_SIGNING_IDENTITY:--}" --options runtime --entitlements Resources/Entitlements.plist "$destination"
codesign --verify --deep --strict "$destination"
if [[ -e "$PWD/dist/DictAI.app" ]]; then mv "$PWD/dist/DictAI.app" "$staging/Previous-DictAI.app"; fi
mv "$destination" "$PWD/dist/DictAI.app"
if command -v trash >/dev/null; then trash "$staging"; fi
destination="$PWD/dist/DictAI.app"
ditto -c -k --sequesterRsrc --keepParent "$destination" "$PWD/dist/DictAI-macOS.zip"
printf '%s\n' "$destination"
