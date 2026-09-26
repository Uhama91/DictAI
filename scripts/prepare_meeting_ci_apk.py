#!/usr/bin/env python3
"""Validate and atomically stage the isolated DictAI Meeting CI APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path


APPLICATION_ID = "com.uhama.whisperpin.meetingtest"
VERSION_CODE = 36
VERSION_NAME = "0.9.6-dictai-meeting-test2"
ABI = "arm64-v8a"
MEETING_LIBRARY = "lib/arm64-v8a/libdictai_meeting.so"
MEETING_LIBRARY_SHA256 = "5ac52ca8137d34db0207ab30af171e6291a5ba21e2c0b56966d65f2bfc35da27"
HISTORICAL_LIBRARIES = frozenset(
    {
        "libggml-base.so",
        "libggml-cpu.so",
        "libggml.so",
        "libonnxruntime.so",
        "libsherpa-onnx-c-api.so",
        "libsherpa-onnx-cxx-api.so",
        "libsherpa-onnx-jni.so",
        "libtranscribe.so",
        "libtranscribe_jni.so",
    }
)
MEETING_NOTICES = frozenset(
    {
        "OpenMDW-1.1.txt",
        "SOURCES.md",
        "absl-LICENSE.txt",
        "darts-clone-LICENSE.txt",
        "esaxx-LICENSE.txt",
        "ggml-LICENSE.txt",
        "nemo-speech-LICENSE.txt",
        "nemo-speech-NOTICE.txt",
        "nemo-speech-THIRD_PARTY_NOTICES.md",
        "protobuf-lite-LICENSE.txt",
        "sentencepiece-LICENSE.txt",
    }
)
GENERAL_REQUIRED_ENTRIES = frozenset(
    {
        "assets/local-format/gemma-NOTICE.txt",
        "assets/local-format/Apache-2.0.txt",
        "assets/local-format/layout-list.prompt",
        "assets/local-format/layout-email.prompt",
        "assets/local-format/llama-LICENSE.txt",
        "assets/licenses/Apache-2.0.txt",
        "assets/licenses/NOTICE.txt",
        "assets/licenses/sherpa-onnx-1.13.4-LICENSE.txt",
        "assets/licenses/onnxruntime-1.27.0-LICENSE.txt",
        "assets/THIRD_PARTY_NOTICES.txt",
        "lib/arm64-v8a/liblitertlm_jni.so",
        "lib/arm64-v8a/libdictai_llm.so",
        "lib/arm64-v8a/libdictai_llm_arm82.so",
    }
)
MODEL_WEIGHT_SUFFIXES = (".gguf", ".litertlm")
MODEL_PARTIAL_SUFFIXES = (".part", ".partial", ".tmp", ".temp", ".download", ".incomplete")


class VerificationError(Exception):
    """An APK could not be proven to match the Meeting artifact contract."""


@dataclass(frozen=True)
class ApkIdentity:
    applicationId: str
    versionCode: int
    versionName: str
    abi: str


def parse_badging(output: str) -> ApkIdentity:
    package_line = next((line for line in output.splitlines() if line.startswith("package:")), None)
    if package_line is None:
        raise VerificationError("aapt badging has no package record")

    package_match = re.search(r"\bname='([^']+)'", package_line)
    version_code_match = re.search(r"\bversionCode='([^']+)'", package_line)
    version_name_match = re.search(r"\bversionName='([^']*)'", package_line)
    if not package_match or not version_code_match or not version_name_match:
        raise VerificationError("aapt badging package record is incomplete")
    try:
        version_code = int(version_code_match.group(1))
    except ValueError as error:
        raise VerificationError("aapt badging versionCode is not an integer") from error

    native_abis: set[str] = set()
    for line in output.splitlines():
        if line.startswith("native-code:"):
            native_abis.update(re.findall(r"'([^']+)'", line))
    if native_abis != {ABI}:
        rendered = ", ".join(sorted(native_abis)) or "none"
        raise VerificationError(f"APK ABI must be {ABI}; found {rendered}")

    identity = ApkIdentity(
        applicationId=package_match.group(1),
        versionCode=version_code,
        versionName=version_name_match.group(1),
        abi=ABI,
    )
    if identity.applicationId != APPLICATION_ID:
        raise VerificationError(f"applicationId must be {APPLICATION_ID}; found {identity.applicationId}")
    if identity.versionCode != VERSION_CODE:
        raise VerificationError(f"versionCode must be {VERSION_CODE}; found {identity.versionCode}")
    if identity.versionName != VERSION_NAME:
        raise VerificationError(f"versionName must be {VERSION_NAME}; found {identity.versionName}")
    return identity


def sha256_bytes(contents: bytes) -> str:
    return hashlib.sha256(contents).hexdigest()


def _is_model_weight(path: str) -> bool:
    name = path.lower()
    if name.endswith(MODEL_WEIGHT_SUFFIXES):
        return True
    return any(
        weight_suffix in name and name.endswith(MODEL_PARTIAL_SUFFIXES)
        for weight_suffix in MODEL_WEIGHT_SUFFIXES
    )


def verify_apk(apk_path: Path, badging_output: str) -> tuple[ApkIdentity, str, int, str]:
    """Check the APK payload and return identity, APK hash, size, and engine hash."""
    identity = parse_badging(badging_output)
    try:
        with zipfile.ZipFile(apk_path) as apk:
            bad_entry = apk.testzip()
            if bad_entry is not None:
                raise VerificationError(f"APK zip contains a corrupt entry: {bad_entry}")
            names = apk.namelist()
            duplicates = sorted(name for name, count in Counter(names).items() if count > 1)
            if duplicates:
                raise VerificationError(f"APK zip contains duplicate entries: {', '.join(duplicates)}")
            name_set = set(names)

            model_entries = sorted(name for name in names if _is_model_weight(name))
            if model_entries:
                raise VerificationError(f"APK must not contain model weights: {', '.join(model_entries)}")

            required = set(GENERAL_REQUIRED_ENTRIES)
            required.update(f"assets/licenses/meeting/{name}" for name in MEETING_NOTICES)
            required.update(f"lib/arm64-v8a/{name}" for name in HISTORICAL_LIBRARIES)
            required.add(MEETING_LIBRARY)
            missing = sorted(required - name_set)
            if missing:
                raise VerificationError(f"Missing APK entry: {', '.join(missing)}")

            engine_sha = sha256_bytes(apk.read(MEETING_LIBRARY))
            if engine_sha != MEETING_LIBRARY_SHA256:
                raise VerificationError(
                    f"SHA-256 mismatch for {MEETING_LIBRARY}: {engine_sha}"
                )
    except VerificationError:
        raise
    except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile) as error:
        raise VerificationError(f"invalid APK zip: {error}") from error

    with apk_path.open("rb") as stream:
        apk_sha = hashlib.file_digest(stream, "sha256").hexdigest()
    return identity, apk_sha, apk_path.stat().st_size, engine_sha


def read_badging(apk_path: Path, aapt: str) -> str:
    try:
        result = subprocess.run(
            [aapt, "dump", "badging", str(apk_path)],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as error:
        raise VerificationError(f"aapt dump badging failed: {error}") from error
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise VerificationError(f"aapt dump badging failed: {detail}")
    return result.stdout


def resolve_commit(explicit: str | None, repository: Path) -> str:
    commit = explicit or os.environ.get("GITHUB_SHA")
    if not commit:
        try:
            result = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            )
            commit = result.stdout.strip()
        except (OSError, subprocess.CalledProcessError) as error:
            raise VerificationError(f"cannot determine CI commit: {error}") from error
    if not re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        raise VerificationError("CI commit must be a full 40-character Git SHA")
    return commit.lower()


def stage_artifact(
    apk_path: Path,
    output_dir: Path,
    identity: ApkIdentity,
    apk_sha: str,
    size_bytes: int,
    engine_sha: str,
    commit: str,
) -> None:
    if output_dir.exists():
        raise VerificationError(f"output directory already exists: {output_dir}")
    output_dir.parent.mkdir(parents=True, exist_ok=True)

    staging = Path(tempfile.mkdtemp(prefix=".meeting-test-stage-", dir=output_dir.parent))
    try:
        apk_name = "dictai-meeting-test.apk"
        shutil.copyfile(apk_path, staging / apk_name)
        (staging / "SHA256SUMS").write_text(f"{apk_sha}  {apk_name}\n", encoding="utf-8")
        metadata = {
            **asdict(identity),
            "apk": apk_name,
            "sizeBytes": size_bytes,
            "sha256": apk_sha,
            "meetingEngineSha256": engine_sha,
            "ciCommit": commit,
        }
        (staging / "metadata.json").write_text(
            json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        os.rename(staging, output_dir)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--aapt", default=None, help="pinned Android build-tools aapt executable")
    parser.add_argument("--output-dir", type=Path, default=Path("dist/meeting-test"))
    parser.add_argument("--commit-sha", default=None)
    args = parser.parse_args(argv)

    repository = Path(__file__).resolve().parents[1]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    aapt = args.aapt or (str(Path(sdk) / "build-tools/35.0.0/aapt") if sdk else "aapt")
    try:
        if not args.apk.is_file():
            raise VerificationError(f"APK does not exist: {args.apk}")
        badging = read_badging(args.apk, aapt)
        identity, apk_sha, size_bytes, engine_sha = verify_apk(args.apk, badging)
        commit = resolve_commit(args.commit_sha, repository)
        stage_artifact(args.apk, args.output_dir, identity, apk_sha, size_bytes, engine_sha, commit)
    except VerificationError as error:
        print(f"Meeting APK verification failed: {error}", file=sys.stderr)
        return 1
    except OSError as error:
        print(f"Meeting APK staging failed: {error}", file=sys.stderr)
        return 1

    print(
        f"Verified {identity.applicationId} {identity.versionName}: "
        f"{size_bytes} bytes, SHA256={apk_sha}; staged at {args.output_dir}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
