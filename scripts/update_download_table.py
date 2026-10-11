#!/usr/bin/env python3
"""Refresh the README download table from published, verified GitHub release assets."""
import argparse
import hashlib
import json
import os
import re
import urllib.request
from pathlib import Path

REPOSITORY = "Uhama91/DictAI"
GITHUB = f"https://github.com/{REPOSITORY}/releases/"
START = "<!-- dictai-downloads:start -->"
END = "<!-- dictai-downloads:end -->"
PLATFORMS = {"android": ("DictAI-android.apk", "versionCode"), "macos": ("DictAI-macOS.pkg", "buildNumber")}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_release(release, manifest):
    tag = release.get("tag_name", "")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", tag), "Invalid release tag")
    require(not release.get("draft") and release.get("published_at"), "Release is not published")
    require(release.get("html_url") == GITHUB + "tag/" + tag, "Unexpected release URL")
    require(type(manifest.get("schemaVersion")) is int and manifest["schemaVersion"] == 1, "Unsupported manifest")
    preview = release.get("prerelease") is True
    require(manifest.get("testOnly", False) is preview, "Preview/public manifest mismatch")
    android, mac = manifest.get("android", {}), manifest.get("macos", {})
    require(android.get("applicationId") == "com.uhama.whisperpin", "Unexpected Android application")
    require(mac.get("bundleIdentifier") == "com.uhama.dictai.desktop", "Unexpected macOS application")
    require(type(android.get("minSdk")) is int and 30 <= android["minSdk"] <= 100, "Invalid Android minimum OS")
    require(re.fullmatch(r"[0-9]{1,2}\.[0-9]{1,2}(\.[0-9]{1,2})?", str(mac.get("minSystemVersion", ""))), "Invalid macOS minimum OS")
    for platform, (filename, version_key) in PLATFORMS.items():
        data = manifest[platform]
        require(type(data.get(version_key)) is int and 0 < data[version_key] <= 2147483647, "Invalid version number")
        require(re.fullmatch(r"[0-9][A-Za-z0-9.+_-]{0,39}", str(data.get("versionName", ""))), "Invalid version label")
        expected_url = GITHUB + "download/" + tag + "/" + filename
        require(data.get("downloadUrl") == expected_url, "Unexpected installer URL")
        digest = data.get("sha256", "")
        require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest), "Missing installer SHA-256")
        assets = [a for a in release.get("assets", []) if a.get("name") == filename]
        require(len(assets) == 1, "Missing or duplicate installer")
        asset = assets[0]
        require(asset.get("state") == "uploaded" and asset.get("size", 0) > 0, "Installer is not ready")
        require(asset.get("browser_download_url") == expected_url and asset.get("digest") == "sha256:" + digest,
                "Installer differs from the verified manifest")
    for data, key, allowed in [(android, "abis", {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}),
                               (mac, "architectures", {"arm64", "x86_64"})]:
        values = data.get(key)
        require(isinstance(values, list) and values and all(isinstance(v, str) and v in allowed for v in values),
                "Missing or unsupported native architectures")
    return {"release": release, "manifest": manifest}


def choose_downloads(offers):
    require(offers, "No verified complete release; README kept unchanged")
    return {platform: max(offers, key=lambda o: (o["manifest"][platform][version_key],
                                               not o["release"]["prerelease"], o["release"]["published_at"]))
            for platform, (_, version_key) in PLATFORMS.items()}


def refresh_readme(original, offers):
    table = (render_table(choose_downloads(offers)) if offers else
             "Aucun installateur vérifié n’est actuellement disponible. Consultez les [releases](" + GITHUB.rstrip("/") + ").\n")
    return replace_table(original, table)


def render_table(selected):
    android = selected["android"]["manifest"]["android"]
    mac = selected["macos"]["manifest"]["macos"]
    api_labels = {30: "11", 31: "12", 32: "12L", 33: "13", 34: "14", 35: "15", 36: "16"}
    android_os = api_labels.get(android["minSdk"], f"API {android['minSdk']}")
    abi_labels = {"arm64-v8a": "ARM64", "armeabi-v7a": "ARM 32 bits", "x86": "x86", "x86_64": "x86_64"}
    android_compat = f"Android {android_os}+ · " + ", ".join(abi_labels[a] for a in android["abis"])
    mac_arch = {"arm64": "Apple silicon", "x86_64": "Intel"}
    mac_compat = f"macOS {mac['minSystemVersion']}+ · " + " et ".join(mac_arch[a] for a in mac["architectures"])
    versions = {p: selected[p]["manifest"][p]["versionName"] + (" (test)" if selected[p]["release"]["prerelease"] else "")
                for p in PLATFORMS}
    lines = ["| Appareil | Téléchargement | Version | Compatibilité |", "|---|---|---|---|",
             f"| Téléphone Android | [Télécharger]({android['downloadUrl']}) | {versions['android']} | {android_compat} |",
             f"| Tablette Android | [Télécharger]({android['downloadUrl']}) | {versions['android']} | {android_compat} |",
             f"| Mac | [Télécharger]({mac['downloadUrl']}) | {versions['macos']} | {mac_compat} |", ""]
    if any(selected[p]["release"]["prerelease"] for p in PLATFORMS):
        lines += ["Les versions marquées « test » sont destinées aux premiers essais. Consultez les instructions d’installation avant de les ouvrir.", ""]
    notes = list(dict.fromkeys(selected[p]["release"]["html_url"] for p in PLATFORMS))
    lines += ["Instructions et nouveautés : " + " · ".join(f"[Notes de version{'' if len(notes) == 1 else ' ' + str(i + 1)}]({url})"
                                                         for i, url in enumerate(notes)) + ".", ""]
    return "\n".join(lines)


def replace_table(readme, table):
    require(readme.count(START) == readme.count(END) == 1 and readme.index(START) < readme.index(END),
            "README must contain exactly one download block")
    before, rest = readme.split(START)
    _, after = rest.split(END)
    return before + START + "\n" + table.rstrip() + "\n" + END + after


def fetch(url, limit, api=False):
    headers = {"User-Agent": "DictAI-download-table"}
    if api:
        require(url.startswith(f"https://api.github.com/repos/{REPOSITORY}/releases?"), "Unexpected API URL")
        headers["Accept"] = "application/vnd.github+json"
        if os.environ.get("GITHUB_TOKEN"):
            headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        body = response.read(limit + 1)
    require(len(body) <= limit, "GitHub response exceeds size limit")
    return body


def published_offers():
    offers = []
    for page in range(1, 11):
        releases = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100&page={page}", 8 * 1024 * 1024, api=True))
        require(isinstance(releases, list), "Unexpected release catalog")
        for release in releases:
            if release.get("draft"):
                continue
            filename = "updates-preview.json" if release.get("prerelease") else "updates.json"
            assets = [a for a in release.get("assets", []) if a.get("name") == filename]
            if not assets:
                continue  # Model experiments and legacy builds are not complete app distributions.
            try:
                require(len(assets) == 1, "Duplicate manifest")
                asset = assets[0]
                tag = release.get("tag_name", "")
                require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", tag), "Invalid release tag")
                require(asset.get("browser_download_url") == GITHUB + "download/" + tag + "/" + filename,
                        "Unexpected manifest URL")
                require(asset.get("state") == "uploaded" and 0 < asset.get("size", 0) <= 65536, "Invalid manifest asset")
                body = fetch(asset["browser_download_url"], 65536)
                require(asset.get("digest") == "sha256:" + hashlib.sha256(body).hexdigest(), "Manifest checksum mismatch")
                offers.append(validate_release(release, json.loads(body)))
            except (ValueError, KeyError, TypeError) as error:
                print(f"Skipping {release.get('id', 'release')}: {error}")
        if len(releases) < 100:
            return offers
    raise ValueError("Release catalog exceeds pagination limit; README kept unchanged")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--readme", type=Path, default=Path("README.md"))
    args = parser.parse_args()
    original = args.readme.read_text(encoding="utf-8")
    updated = refresh_readme(original, published_offers())
    if original != updated:
        args.readme.write_text(updated, encoding="utf-8")
        print("Download table updated")
    else:
        print("Download table already current")


if __name__ == "__main__":
    main()
