#!/usr/bin/env python3
"""Verify the reviewed package, version, and signing identity of the G3 APK."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path


EXPECTED_APPLICATION_ID = "com.uhama.whisperpin"
EXPECTED_VERSION_CODE = 40
EXPECTED_VERSION_NAME = "0.9.11-dictai-gemma3-test"
EXPECTED_CERTIFICATE_SHA256 = "6b37c02704d31553b275a9a5f23c8eb650df04cd59f7b28074e6f2dcadbf9539"

PACKAGE_PATTERN = re.compile(
    r"^package: name='(?P<application_id>[^']+)' "
    r"versionCode='(?P<version_code>[0-9]+)' "
    r"versionName='(?P<version_name>[^']*)'",
    re.MULTILINE,
)
CERTIFICATE_PATTERN = re.compile(
    r"^Signer #[0-9]+ certificate SHA-256 digest: (?P<digest>[0-9a-fA-F]{64})$",
    re.MULTILINE,
)


def _run(command: list[str]) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, check=False, text=True, capture_output=True)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit status {result.returncode}"
        raise RuntimeError(f"command failed ({command[0]}): {detail}")
    return result


def verify_apk_identity(
    apk: Path,
    *,
    aapt: str | Path = "aapt",
    apksigner: str | Path = "apksigner",
) -> dict[str, str | int]:
    if not apk.is_file():
        raise FileNotFoundError(f"APK not found: {apk}")

    badging = _run([str(aapt), "dump", "badging", str(apk)]).stdout
    package_match = PACKAGE_PATTERN.search(badging)
    if package_match is None:
        raise AssertionError("APK package metadata not found in aapt badging output")

    actual = {
        "application_id": package_match.group("application_id"),
        "version_code": int(package_match.group("version_code")),
        "version_name": package_match.group("version_name"),
    }
    expected = {
        "application_id": EXPECTED_APPLICATION_ID,
        "version_code": EXPECTED_VERSION_CODE,
        "version_name": EXPECTED_VERSION_NAME,
    }
    if actual != expected:
        raise AssertionError(f"APK identity mismatch: expected {expected}, found {actual}")

    certificate_output = _run([str(apksigner), "verify", "--print-certs", str(apk)]).stdout
    certificate_digests = CERTIFICATE_PATTERN.findall(certificate_output)
    if len(certificate_digests) != 1:
        raise AssertionError(
            "APK must have exactly one signer with a SHA-256 certificate digest; "
            f"found {len(certificate_digests)}"
        )
    certificate_sha256 = certificate_digests[0].lower()
    if certificate_sha256 != EXPECTED_CERTIFICATE_SHA256:
        raise AssertionError(
            "APK signing certificate mismatch: expected "
            f"{EXPECTED_CERTIFICATE_SHA256}, found {certificate_sha256}"
        )

    return {**actual, "certificate_sha256": certificate_sha256}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", type=Path, default=Path("aapt"))
    parser.add_argument("--apksigner", type=Path, default=Path("apksigner"))
    args = parser.parse_args(argv)

    identity = verify_apk_identity(args.apk, aapt=args.aapt, apksigner=args.apksigner)
    print(
        "Verified Gemma 3 APK identity: "
        f"{identity['application_id']} {identity['version_name']} "
        f"(versionCode {identity['version_code']}), "
        f"certificate SHA-256={identity['certificate_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
