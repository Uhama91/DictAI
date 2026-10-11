#!/usr/bin/env python3
"""Prepare website/update metadata from verified Android and macOS installers. Does not publish."""
import argparse
import hashlib
import json
import os
import plistlib
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_android_signer(badging, actual, expected):
    # This certificate is backed by the public test key committed in the repository.
    test_cert = "6b37c02704d31553b275a9a5f23c8eb650df04cd59f7b28074e6f2dcadbf9539"
    require("application-debuggable" not in badging and actual.lower() != test_cert,
            "Debug or public test-key APK cannot be advertised as a public update")
    approved = (expected or "").lower().replace(":", "")
    require(re.fullmatch(r"[0-9a-f]{64}", approved) and actual.lower() == approved,
            "Android signer differs from the approved public certificate")


def validate_destination(destination, preview):
    other_manifest = "updates.json" if preview else "updates-preview.json"
    require(not (destination / other_manifest).exists(),
            "Public and preview artifacts require separate output directories")


def parse_android_abis(badging):
    native = re.search(r"^native-code: (.+)$", badging, re.MULTILINE)
    abis = re.findall(r"'([^']+)'", native[1]) if native else []
    require(abis, "Cannot read Android native architectures")
    return sorted(set(abis))


def make_manifest(android, mac, tag):
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", tag), "Invalid release tag")
    require(android["applicationId"] == "com.uhama.whisperpin", "Unexpected Android application ID")
    require(mac["CFBundleIdentifier"] == "com.uhama.dictai.desktop", "Unexpected macOS bundle ID")
    require(type(android["versionCode"]) is int and 0 < android["versionCode"] <= 2147483647, "Invalid Android version code")
    require(type(android["minSdk"]) is int and 0 < android["minSdk"] <= 100, "Invalid Android minimum OS")
    require(re.fullmatch(r"[0-9]+", mac["CFBundleVersion"]), "Invalid macOS build number")
    build = int(mac["CFBundleVersion"])
    require(0 < build <= 2147483647, "Invalid macOS build number")
    require(re.fullmatch(r"[0-9]{1,2}\.[0-9]{1,2}(\.[0-9]{1,2})?", mac["LSMinimumSystemVersion"]), "Invalid macOS minimum OS")
    for label in [android["versionName"], mac["CFBundleShortVersionString"]]:
        require(isinstance(label, str) and 0 < len(label) <= 40 and label.strip() and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in label), "Invalid version label")
    base = f"https://github.com/Uhama91/DictAI/releases/download/{tag}/"
    return {"schemaVersion": 1, "android": {**android, "downloadUrl": base + "DictAI-android.apk"},
            "macos": {"bundleIdentifier": mac["CFBundleIdentifier"], "buildNumber": build,
                      "versionName": mac["CFBundleShortVersionString"], "minSystemVersion": mac["LSMinimumSystemVersion"],
                      **({"architectures": mac["architectures"]} if "architectures" in mac else {}),
                      "downloadUrl": base + "DictAI-macOS.pkg"}}


def run(*args):
    result = subprocess.run(list(map(str, args)), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return result.stdout + result.stderr


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare(args):
    destination = args.output_directory.resolve()
    validate_destination(destination, args.allow_test_artifacts)
    tools = args.android_tools
    apk = args.android_apk.resolve()
    app = args.macos_app.resolve()
    installer = args.macos_installer.resolve()
    require(apk.is_file() and installer.is_file(), "Both installers must exist")
    badging = run(tools / "aapt", "dump", "badging", apk)
    package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    minimum = re.search(r"sdkVersion:'([0-9]+)'", badging)
    require(package and minimum, "Cannot read Android artifact metadata")
    android = {"applicationId": package[1], "versionCode": int(package[2]), "versionName": package[3],
               "minSdk": int(minimum[1]), "abis": parse_android_abis(badging)}
    signature = run(tools / "apksigner", "verify", "--print-certs", apk)
    run(tools / "zipalign", "-c", "-P", "16", "4", apk)
    cert = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    require(cert, "Missing APK signer")
    with (app / "Contents/Info.plist").open("rb") as stream:
        mac = plistlib.load(stream)
    run("codesign", "--verify", "--deep", "--strict", app)
    mac_signature = run("codesign", "-dv", "--verbose=2", app)
    require(installer.name.find(mac["CFBundleShortVersionString"]) >= 0, "Installer filename must contain the matching macOS version")
    stage = Path(tempfile.mkdtemp(prefix="dictai-update-package-"))
    try:
        expanded = stage / "expanded"
        run("pkgutil", "--expand-full", installer, expanded)
        installed_app = expanded / "DictAI.pkg/Payload/Applications/DictAI.app"
        with (installed_app / "Contents/Info.plist").open("rb") as stream:
            installed_info = plistlib.load(stream)
        require(installed_info == mac, "Installer metadata differs from the supplied app")
        executable = "Contents/MacOS/" + mac["CFBundleExecutable"]
        require(sha256(installed_app / executable) == sha256(app / executable), "Installer contains a different application binary")
        run("codesign", "--verify", "--deep", "--strict", installed_app)
    finally:
        if shutil.which("trash"):
            run("trash", stage)
    if not args.allow_test_artifacts:
        validate_android_signer(badging, cert[1], args.expected_android_cert)
        require("Authority=Developer ID Application:" in mac_signature and "TeamIdentifier=not set" not in mac_signature, "Missing macOS Developer ID signature")
        require("Developer ID Installer:" in run("pkgutil", "--check-signature", installer), "Missing macOS Developer ID Installer signature")
        run("xcrun", "stapler", "validate", installer)
        run("spctl", "--assess", "--type", "install", installer)
    architectures = run("xcrun", "lipo", "-archs", app / "Contents/MacOS" / mac["CFBundleExecutable"]).strip().split()
    require(architectures and set(architectures) <= {"arm64", "x86_64"}, "Unexpected macOS architectures")
    manifest = make_manifest(android, {**mac, "architectures": sorted(set(architectures))}, args.tag)
    manifest["android"].update(sha256=sha256(apk), signingCertificateSha256=cert[1].lower())
    manifest["macos"]["sha256"] = sha256(installer)
    if args.allow_test_artifacts:
        manifest["testOnly"] = True
    destination.mkdir(parents=True, exist_ok=True)
    for source, filename in [(apk, "DictAI-android.apk"), (installer, "DictAI-macOS.pkg")]:
        target = destination / filename
        if source != target:
            shutil.copy2(source, target)
    output = destination / ("updates-preview.json" if args.allow_test_artifacts else "updates.json")
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-apk", type=Path, required=True)
    parser.add_argument("--macos-app", type=Path, required=True)
    parser.add_argument("--macos-installer", type=Path, required=True)
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk")))
    installed_tools = sorted((p for p in (sdk / "build-tools").glob("*") if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", p.name) and (p / "apksigner").is_file()), key=lambda p: tuple(map(int, p.name.split("."))))
    parser.add_argument("--android-tools", type=Path, default=installed_tools[-1] if installed_tools else sdk / "build-tools/35.0.0")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--expected-android-cert")
    parser.add_argument("--allow-test-artifacts", action="store_true", help="Test distribution only; never creates updates.json")
    args = parser.parse_args()
    try:
        prepare(args)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
