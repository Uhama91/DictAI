#!/usr/bin/env python3
"""Verify and stage a normal, GPU-prototype, or CPU pilot APK."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import zipfile
from pathlib import Path


VARIANTS = frozenset({"normal", "gpu", "pilot", "gemma3-pilot"})
OUTPUT_NAMES = {
    "normal": "dictai-debug.apk",
    "gpu": "dictai-local-layout-test.apk",
    "pilot": "dictai-gemma4-v6-test.apk",
    "gemma3-pilot": "dictai-gemma3-test.apk",
}
REQUIRED_ASSETS = (
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
)
PILOT_NOTICE_ASSET = "assets/local-format/gemma4-v6-pilot-NOTICE.txt"
GEMMA3_PILOT_NOTICE_ASSET = "assets/local-format/gemma3-repair-pilot-NOTICE.txt"
LOCAL_FORMAT_LIBRARIES = frozenset(
    {
        "lib/arm64-v8a/libdictai_llm.so",
        "lib/arm64-v8a/libdictai_llm_arm82.so",
    }
)


def variant_from_environment() -> str:
    gemma3_pilot = os.environ.get("GEMMA3_PILOT") == "true"
    gemma4_pilot = os.environ.get("GEMMA4_PILOT") == "true"
    prototype = os.environ.get("PROTOTYPE") == "true"
    if gemma3_pilot and (gemma4_pilot or prototype):
        raise ValueError("Gemma 3 pilot cannot be combined with another local-format prototype route")
    if gemma3_pilot:
        return "gemma3-pilot"
    if gemma4_pilot:
        return "pilot"
    # Keep the existing CI helper contract for the GPU-only prototype.
    if prototype:
        return "gpu"
    return "normal"


def verify_apk(apk: Path, variant: str) -> set[str]:
    if variant not in VARIANTS:
        raise ValueError(f"unknown APK variant: {variant}")
    if not apk.is_file():
        raise AssertionError(f"APK not found: {apk}")

    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        models = [name for name in names if name.endswith((".gguf", ".litertlm"))]
        assert not models, "Gemma is installed in-app; no old or partial model may be bundled"
        missing_assets = sorted(set(REQUIRED_ASSETS) - names)
        assert not missing_assets, f"Missing required APK notices/assets: {', '.join(missing_assets)}"
        if variant == "pilot" and PILOT_NOTICE_ASSET not in names:
            raise AssertionError(f"pilot notice missing: {PILOT_NOTICE_ASSET}")
        if variant == "gemma3-pilot" and GEMMA3_PILOT_NOTICE_ASSET not in names:
            raise AssertionError(f"Gemma 3 pilot notice missing: {GEMMA3_PILOT_NOTICE_ASSET}")
        assert "lib/arm64-v8a/liblitertlm_jni.so" in names, "Missing LiteRT-LM Android runtime"

        has_local = names & LOCAL_FORMAT_LIBRARIES
        if variant == "gpu":
            assert not has_local, "GPU prototype must not package the CPU local-format JNI pair"
        else:
            assert has_local == LOCAL_FORMAT_LIBRARIES, (
                f"{variant} must package both local-format JNI libraries; "
                f"found {sorted(has_local)}"
            )
        return names


def file_digest(path: Path, algorithm: str = "sha256") -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stage_apk(apk: Path, output: Path, variant: str) -> tuple[Path, str]:
    verify_apk(apk, variant)
    output.mkdir(parents=True, exist_ok=True)
    name = OUTPUT_NAMES[variant]
    destination = output / name
    shutil.copyfile(apk, destination)
    digest = file_digest(destination)
    (output / "SHA256SUMS").write_text(f"{digest}  {name}\n", encoding="utf-8")
    return destination, digest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--output-dir", type=Path, default=Path("dist"))
    parser.add_argument("--variant", choices=sorted(VARIANTS), default=None)
    args = parser.parse_args(argv)
    variant = args.variant or variant_from_environment()
    destination, digest = stage_apk(args.apk, args.output_dir, variant)
    print(f"Verified {variant} APK: {destination} ({destination.stat().st_size} bytes), SHA256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
