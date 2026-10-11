#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Library/Developer/CommandLineTools}"
./scripts/build-app.sh
version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' Resources/Info.plist)
stage=$(mktemp -d "$PWD/dist/installer-XXXXXXXX")
mkdir -p "$stage/payload/Applications" "$stage/scripts" "$stage/resources"
ditto dist/DictAI.app "$stage/payload/Applications/DictAI.app"
cp installer/preinstall "$stage/scripts/preinstall"
chmod 755 "$stage/scripts/preinstall"
cp installer/*.html "$stage/resources/"
pkgbuild --analyze --root "$stage/payload" "$stage/components.plist"
/usr/libexec/PlistBuddy -c 'Set :0:BundleIsRelocatable false' "$stage/components.plist"
/usr/libexec/PlistBuddy -c 'Set :0:BundleHasStrictIdentifier true' "$stage/components.plist"
pkgbuild --root "$stage/payload" --component-plist "$stage/components.plist" \
  --scripts "$stage/scripts" --identifier com.uhama.dictai.desktop.pkg \
  --version "$version" --install-location / --ownership recommended "$stage/DictAI.pkg"
cat > "$stage/Distribution.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
  <title>DictAI $version</title>
  <welcome file="welcome.html" mime-type="text/html"/>
  <conclusion file="conclusion.html" mime-type="text/html"/>
  <options customize="never" require-scripts="false" hostArchitectures="arm64,x86_64"/>
  <domains enable_anywhere="false" enable_currentUserHome="false" enable_localSystem="true"/>
  <allowed-os-versions><os-version min="14.0"/></allowed-os-versions>
  <choices-outline><line choice="dictai"/></choices-outline>
  <choice id="dictai" visible="false" title="DictAI"><pkg-ref id="com.uhama.dictai.desktop.pkg"/></choice>
  <pkg-ref id="com.uhama.dictai.desktop.pkg" version="$version" onConclusion="None">DictAI.pkg</pkg-ref>
  <pkg-ref id="com.uhama.dictai.desktop.pkg"><must-close><app id="com.uhama.dictai.desktop"/></must-close></pkg-ref>
</installer-gui-script>
EOF
output="$PWD/dist/DictAI-$version-macOS-test.pkg"
build_product() {
  productbuild --distribution "$stage/Distribution.xml" --resources "$stage/resources" \
    --package-path "$stage" "$@" "$output"
}
if [[ -n "${DICTAI_INSTALLER_IDENTITY:-}" ]]; then
  build_product --sign "$DICTAI_INSTALLER_IDENTITY"
else
  build_product
fi
./scripts/verify-installer.sh "$output"
if [[ -n "${DICTAI_NOTARY_PROFILE:-}" ]]; then
  if [[ -z "${DICTAI_INSTALLER_IDENTITY:-}" || "${DICTAI_SIGNING_IDENTITY:--}" == '-' ]]; then
    echo 'La notarisation nécessite les identités Developer ID Application et Installer.' >&2
    exit 1
  fi
  xcrun notarytool submit "$output" --keychain-profile "$DICTAI_NOTARY_PROFILE" --wait
  xcrun stapler staple "$output"
  xcrun stapler validate "$output"
  spctl --assess --type install --verbose=2 "$output"
  public_output="$PWD/dist/DictAI-$version-macOS.pkg"
  mv "$output" "$public_output"
  output="$public_output"
else
  echo 'VERSION DE TEST : signature Developer ID et notarisation non validées.' >&2
fi
shasum -a 256 "$output" > "$output.sha256"
if command -v trash >/dev/null; then trash "$stage"; fi
printf '%s\n' "$output"
