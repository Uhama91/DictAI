#!/usr/bin/env python3
"""Verify and stage the installable Gemma 3 270M pilot APK.

The model is deliberately checked twice: once before Gradle packages it and
once after packaging, using the bytes read back from the APK.  The latter
prevents a stale, truncated, or unrelated model from reaching the CI artifact.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


EXPECTED_MODEL_FILENAME = "gemma3-270m-postclean-v3-q8_0.gguf"
MAX_MODEL_SIZE_BYTES = 1_000_000_000
EXPECTED_BASE_MODEL = "google/gemma-3-270m-it"
EXPECTED_INSTRUCTION_SHA256 = "a95ce1093ba1308bddd5d2006eff73b6a936623261fbbfe6fe54562dfe3cd6f6"
EXPECTED_RELEASE_URL = (
    "https://github.com/Uhama91/DictAI/releases/download/"
    "gemma270-v3-model/gemma3-270m-postclean-v3-q8_0.gguf"
)
PILOT_MODE_MARKER = (
    "format=corrected\n"
    "engine=local\n"
    f"model={EXPECTED_MODEL_FILENAME}\n"
)

REQUIRED_MANIFEST_FIELDS = frozenset(
    {
        "filename",
        "size_bytes",
        "sha256",
        "label",
        "base_model",
        "base_revision",
        "adapter_directory_sha256",
        "adapter_weights_sha256",
        "instruction_sha256",
        "release_url",
        "prompt_version",
        "language",
        "format",
        "runtime",
        "context_size",
        "max_new_tokens",
    }
)
REQUIRED_ASSET_NAMES = frozenset(
    {
        "assets/local-format/gemma270-model.json",
        "assets/local-format/LICENSE.txt",
        "assets/local-format/NOTICE.txt",
        "assets/local-format/PROHIBITED_USE_POLICY.txt",
    }
)
REQUIRED_NATIVE_LIBS = frozenset(
    {
        "libdictai_llm.so",
        "libdictai_llm_arm82.so",
    }
)
MODE_MARKER_NAME = "assets/gemma270-pilot-mode.txt"
HEX_SHA256_LENGTH = 64


class VerificationError(ValueError):
    """Raised when the pilot manifest, model, or APK is unsafe to distribute."""


@dataclass(frozen=True)
class ArtifactPaths:
    apk: Path
    sha256sums: Path
    notice: Path


def _require_text(manifest: Mapping[str, Any], key: str) -> str:
    value = manifest.get(key)
    if not isinstance(value, str) or not value.strip():
        raise VerificationError(f"manifest field {key!r} must be a non-empty string")
    if value.strip().casefold() in {"todo", "placeholder", "unknown", "tbd"}:
        raise VerificationError(f"manifest field {key!r} still contains a placeholder")
    return value


def _require_sha256(manifest: Mapping[str, Any], key: str) -> str:
    value = _require_text(manifest, key).lower()
    if len(value) != HEX_SHA256_LENGTH or any(char not in "0123456789abcdef" for char in value):
        raise VerificationError(f"manifest field {key!r} must be a SHA-256 hexadecimal digest")
    return value


def validate_manifest(manifest: Mapping[str, Any]) -> dict[str, Any]:
    """Validate the release manifest and return a detached JSON-compatible copy."""

    if not isinstance(manifest, Mapping):
        raise VerificationError("model manifest must be a JSON object")
    missing = sorted(REQUIRED_MANIFEST_FIELDS - set(manifest))
    if missing:
        raise VerificationError("manifest is missing required fields: " + ", ".join(missing))

    filename = _require_text(manifest, "filename")
    if filename != EXPECTED_MODEL_FILENAME or "/" in filename or "\\" in filename:
        raise VerificationError(f"manifest filename must be {EXPECTED_MODEL_FILENAME!r}")
    if not filename.endswith(".gguf"):
        raise VerificationError("pilot model must be a GGUF file")

    size_bytes = manifest.get("size_bytes")
    if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes <= 0:
        raise VerificationError("manifest size_bytes must be a positive integer")
    if size_bytes >= MAX_MODEL_SIZE_BYTES:
        raise VerificationError("manifest size_bytes must stay below the 1 GB mobile budget")
    _require_sha256(manifest, "sha256")
    _require_text(manifest, "label")
    base_model = _require_text(manifest, "base_model")
    if base_model != EXPECTED_BASE_MODEL:
        raise VerificationError(f"manifest base_model must be {EXPECTED_BASE_MODEL!r}")
    base_revision = _require_text(manifest, "base_revision").lower()
    if not re.fullmatch(r"[0-9a-f]{40}", base_revision):
        raise VerificationError("manifest base_revision must be a 40-character commit SHA")
    _require_sha256(manifest, "adapter_directory_sha256")
    _require_sha256(manifest, "adapter_weights_sha256")
    if _require_sha256(manifest, "instruction_sha256") != EXPECTED_INSTRUCTION_SHA256:
        raise VerificationError("manifest instruction_sha256 does not match the V3 prompt")

    release_url = _require_text(manifest, "release_url")
    if release_url != EXPECTED_RELEASE_URL:
        raise VerificationError("manifest release_url does not match the pinned public asset URL")
    if _require_text(manifest, "prompt_version") != "v3":
        raise VerificationError("manifest prompt_version must be v3")
    if _require_text(manifest, "language").casefold() != "fr":
        raise VerificationError("manifest language must be fr")
    if _require_text(manifest, "format") != "corrected":
        raise VerificationError("manifest format must be corrected")
    if _require_text(manifest, "runtime") != "gemma270-v3-q8-cpu":
        raise VerificationError("manifest runtime must be gemma270-v3-q8-cpu")
    for key, expected in (("context_size", 8192), ("max_new_tokens", 4096)):
        value = manifest.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value != expected:
            raise VerificationError(f"manifest {key} must be {expected}")

    # JSON round-tripping catches custom Mapping implementations and prevents
    # callers from mutating a validated object while an APK is being checked.
    return json.loads(json.dumps(dict(manifest), ensure_ascii=False))


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise VerificationError(f"manifest not found: {path}") from error
    except UnicodeDecodeError as error:
        raise VerificationError(f"manifest is not valid UTF-8: {path}") from error
    except json.JSONDecodeError as error:
        raise VerificationError(f"manifest is not valid JSON: {path}") from error
    return validate_manifest(manifest)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_model_file(model_path: Path, manifest: Mapping[str, Any]) -> None:
    validated = validate_manifest(manifest)
    if not model_path.is_file():
        raise VerificationError(f"model file not found: {model_path}")
    if model_path.name != validated["filename"]:
        raise VerificationError(
            f"model filename is {model_path.name!r}; expected {validated['filename']!r}"
        )
    contents = model_path.read_bytes()
    if len(contents) != validated["size_bytes"]:
        raise VerificationError(
            f"model size_bytes mismatch: got {len(contents)}, expected {validated['size_bytes']}"
        )
    actual = sha256_bytes(contents)
    if actual != validated["sha256"].lower():
        raise VerificationError(f"model sha256 mismatch: got {actual}, expected {validated['sha256']}")


def _forbidden_legacy_name(name: str) -> bool:
    lowered = name.casefold()
    # Historical notices and shared runtimes may remain in the APK for legal
    # attribution.  The pilot must exclude the old model *weights* themselves.
    if lowered.endswith(".litertlm"):
        return True
    if lowered.endswith(".gguf") and any(
        marker in lowered for marker in ("gemma4", "gemma-4", "lfm2.5-350m", "350m")
    ):
        return True
    return False


def _apk_model_path(manifest: Mapping[str, Any]) -> str:
    return "assets/local-format/" + str(manifest["filename"])


def verify_apk(apk_path: Path, manifest: Mapping[str, Any]) -> None:
    """Verify the pilot contract in an already-built APK."""

    validated = validate_manifest(manifest)
    if not apk_path.is_file():
        raise VerificationError(f"APK not found: {apk_path}")

    try:
        with zipfile.ZipFile(apk_path) as apk:
            names = apk.namelist()
            name_set = set(names)
            duplicates = sorted(name for name in name_set if names.count(name) > 1)
            if duplicates:
                raise VerificationError("APK contains duplicate entries: " + ", ".join(duplicates))

            forbidden = sorted(name for name in names if _forbidden_legacy_name(name))
            if forbidden:
                raise VerificationError(
                    "forbidden legacy Gemma4/350M model weights are present: " + ", ".join(forbidden)
                )

            model_path = _apk_model_path(validated)
            model_entries = sorted(
                name for name in names if name.casefold().endswith((".gguf", ".litertlm"))
            )
            if model_entries != [model_path]:
                raise VerificationError(
                    "pilot APK must contain only its pinned GGUF model; found "
                    + ", ".join(model_entries or ["none"])
                )
            model_bytes = apk.read(model_path)
            if len(model_bytes) != validated["size_bytes"]:
                raise VerificationError(
                    f"packaged model size_bytes mismatch: got {len(model_bytes)}, expected {validated['size_bytes']}"
                )
            actual_model_sha = sha256_bytes(model_bytes)
            if actual_model_sha != validated["sha256"].lower():
                raise VerificationError(
                    f"packaged model sha256 mismatch: got {actual_model_sha}, expected {validated['sha256']}"
                )

            missing_assets = sorted(REQUIRED_ASSET_NAMES - name_set)
            if missing_assets:
                raise VerificationError("missing pilot license/metadata assets: " + ", ".join(missing_assets))
            if MODE_MARKER_NAME not in name_set or apk.read(MODE_MARKER_NAME).decode("utf-8") != PILOT_MODE_MARKER:
                raise VerificationError("pilot mode marker must select local engine and corrected format")

            packaged_manifest_name = "assets/local-format/gemma270-model.json"
            try:
                packaged_manifest = json.loads(apk.read(packaged_manifest_name).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise VerificationError("packaged Gemma 270M manifest is not valid UTF-8 JSON") from error
            if validate_manifest(packaged_manifest) != validated:
                raise VerificationError("packaged Gemma 270M manifest differs from the checked manifest")

            missing_native = sorted(
                "lib/arm64-v8a/" + name
                for name in REQUIRED_NATIVE_LIBS
                if "lib/arm64-v8a/" + name not in name_set
            )
            if missing_native:
                raise VerificationError("missing pilot native libraries: " + ", ".join(missing_native))
    except zipfile.BadZipFile as error:
        raise VerificationError(f"APK is not a valid ZIP archive: {apk_path}") from error


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _notice_text(manifest: Mapping[str, Any]) -> str:
    return (
        "DictAI Gemma 270M V3 — APK d’essai\n"
        "====================================\n\n"
        "Activation : installez l’APK, choisissez le moteur Local, puis le format "
        "« Texte corrigé ». Le format « Texte » reste "
        "le mode sans modèle de langage.\n\n"
        "Le paquet contient le modèle GGUF quantifié Q8_0 et les bibliothèques natives "
        "du pilote. Il s’agit d’une version de test ; aucune validation sur téléphone "
        "n’est revendiquée ici.\n\n"
        f"Modèle : {manifest['filename']}\n"
        f"Taille : {manifest['size_bytes']} octets\n"
        f"SHA-256 : {manifest['sha256']}\n"
        f"Modèle de base : {manifest['base_model']} ({manifest['base_revision']})\n\n"
        "Gemma is provided under and subject to the Gemma Terms of Use found at "
        "ai.google.dev/gemma/terms. Le modèle a été adapté par fine-tuning LoRA puis "
        "fusionné et quantifié en GGUF Q8_0 ; consultez LICENSE.txt, NOTICE.txt et "
        "PROHIBITED_USE_POLICY.txt avant toute redistribution ou utilisation.\n"
    )


def prepare_artifact(apk_path: Path, manifest: Mapping[str, Any], output_dir: Path) -> ArtifactPaths:
    validated = validate_manifest(manifest)
    verify_apk(apk_path, validated)
    output_dir.mkdir(parents=True, exist_ok=True)
    apk_destination = output_dir / "dictai-gemma270-v3-test.apk"
    shutil.copyfile(apk_path, apk_destination)
    digest = _sha256_file(apk_destination)
    sha256sums = output_dir / "SHA256SUMS"
    sha256sums.write_text(f"{digest}  {apk_destination.name}\n", encoding="utf-8")
    notice = output_dir / "dictai-gemma270-v3-test-notice.txt"
    notice.write_text(_notice_text(validated), encoding="utf-8")
    return ArtifactPaths(apk=apk_destination, sha256sums=sha256sums, notice=notice)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--model", type=Path, help="Fetched GGUF to verify before packaging")
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    args = parser.parse_args(argv)
    try:
        manifest = load_manifest(args.manifest)
        if args.model is not None:
            verify_model_file(args.model, manifest)
        paths = prepare_artifact(args.apk, manifest, args.dist)
    except (OSError, VerificationError) as error:
        print(f"Gemma 270M pilot verification failed: {error}", file=sys.stderr)
        return 1
    print(f"Verified {paths.apk} ({_sha256_file(paths.apk)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
