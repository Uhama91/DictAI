#!/bin/bash
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Library/Developer/CommandLineTools}"
package="${1:?Indiquez le paquet à vérifier.}"
stage=$(mktemp -d)
pkgutil --expand-full "$package" "$stage/expanded"
app="$stage/expanded/DictAI.pkg/Payload/Applications/DictAI.app"
test -d "$app"
xcrun lipo "$app/Contents/MacOS/DictAI" -verify_arch arm64 x86_64
codesign --verify --deep --strict "$app"
python3 - "$stage/expanded" <<'PY'
import pathlib, plistlib, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
distribution = ET.parse(root / 'Distribution').getroot()
assert set(distribution.find('options').get('hostArchitectures').split(',')) == {'arm64', 'x86_64'}
assert distribution.find('allowed-os-versions/os-version').get('min') == '14.0'
assert distribution.find('pkg-ref/must-close/app').get('id') == 'com.uhama.dictai.desktop'
payload = root / 'DictAI.pkg/Payload'
assert {p.name for p in payload.iterdir()} == {'Applications'}
assert {p.name for p in (payload / 'Applications').iterdir()} == {'DictAI.app'}
app = payload / 'Applications/DictAI.app'
with (app / 'Contents/Info.plist').open('rb') as file:
    info = plistlib.load(file)
assert info['CFBundleIdentifier'] == 'com.uhama.dictai.desktop'
assert info['LSMinimumSystemVersion'] == '14.0'
assert not info.get('DictAIPreview', False)
assert not any(p.name in {'library.json', 'draft.json', 'setup.json', 'device-id'} for p in app.rglob('*'))
package = ET.parse(root / 'DictAI.pkg/PackageInfo').getroot()
assert package.get('relocatable') == 'false' and not package.findall('relocate/bundle'), 'The installer must not relocate to an old user Applications copy'
assert (root / 'DictAI.pkg/Scripts/preinstall').is_file()
print('Installer verified: arm64 + x86_64, macOS 14+, production app only, no personal data, no relocation.')
PY
if command -v trash >/dev/null; then trash "$stage"; fi
