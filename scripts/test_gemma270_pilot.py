#!/usr/bin/env python3
"""Contract tests for the Gemma 270M pilot package validator."""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))

from verify_gemma270_pilot import (  # noqa: E402
    EXPECTED_MODEL_FILENAME,
    PILOT_MODE_MARKER,
    REQUIRED_ASSET_NAMES,
    REQUIRED_NATIVE_LIBS,
    VerificationError,
    prepare_artifact,
    validate_manifest,
    verify_apk,
    verify_model_file,
)


MODEL = b"GGUF pilot fixture, not model weights"


def manifest_for(model: bytes = MODEL) -> dict[str, object]:
    return {
        "filename": EXPECTED_MODEL_FILENAME,
        "size_bytes": len(model),
        "sha256": hashlib.sha256(model).hexdigest(),
        "label": "Gemma 3 270M IT - V3 post-traitement",
        "base_model": "google/gemma-3-270m-it",
        "base_revision": "1" * 40,
        "adapter_directory_sha256": "a" * 64,
        "adapter_weights_sha256": "b" * 64,
        "instruction_sha256": "a95ce1093ba1308bddd5d2006eff73b6a936623261fbbfe6fe54562dfe3cd6f6",
        "release_url": "https://github.com/Uhama91/DictAI/releases/download/gemma270-v3-model/gemma3-270m-postclean-v3-q8_0.gguf",
        "prompt_version": "v3",
        "language": "fr",
        "format": "corrected",
        "runtime": "gemma270-v3-q8-cpu",
        "context_size": 8192,
        "max_new_tokens": 4096,
    }


def pilot_entries(model: bytes = MODEL, manifest: dict[str, object] | None = None) -> dict[str, bytes]:
    manifest = manifest or manifest_for(model)
    entries = {
        "assets/local-format/" + EXPECTED_MODEL_FILENAME: model,
        "assets/local-format/gemma270-model.json": json.dumps(
            manifest, ensure_ascii=False, indent=2
        ).encode("utf-8"),
        "assets/gemma270-pilot-mode.txt": PILOT_MODE_MARKER.encode("utf-8"),
        "assets/local-format/LICENSE.txt": b"Gemma Terms of Use\n",
        "assets/local-format/NOTICE.txt": b"Gemma notice\n",
        "assets/local-format/PROHIBITED_USE_POLICY.txt": b"Gemma prohibited use policy\n",
    }
    for name in REQUIRED_NATIVE_LIBS:
        entries["lib/arm64-v8a/" + name] = b"native fixture"
    return entries


def write_apk(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, contents in entries.items():
            archive.writestr(name, contents, compress_type=zipfile.ZIP_STORED)


class Gemma270PilotPackageTest(unittest.TestCase):
    def test_accepts_model_matching_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            model_path = Path(temp_dir) / EXPECTED_MODEL_FILENAME
            model_path.write_bytes(MODEL)
            verify_model_file(model_path, manifest_for())

    def test_rejects_model_with_wrong_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            model_path = Path(temp_dir) / EXPECTED_MODEL_FILENAME
            model_path.write_bytes(MODEL[:-1] + b"!")
            with self.assertRaisesRegex(VerificationError, "sha256"):
                verify_model_file(model_path, manifest_for())

    def test_rejects_manifest_above_one_gigabyte_mobile_budget(self) -> None:
        manifest = manifest_for()
        manifest["size_bytes"] = 1_000_000_000
        with self.assertRaisesRegex(VerificationError, "1 GB"):
            validate_manifest(manifest)

    def test_accepts_complete_pilot_apk_and_writes_download_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            apk = root / "app-debug.apk"
            write_apk(apk, pilot_entries())
            manifest = manifest_for()

            verify_apk(apk, manifest)
            output = prepare_artifact(apk, manifest, root / "dist")

            self.assertEqual(output.apk.name, "dictai-gemma270-v3-test.apk")
            expected_sha = hashlib.sha256(output.apk.read_bytes()).hexdigest()
            self.assertEqual(
                output.sha256sums.read_text(),
                f"{expected_sha}  dictai-gemma270-v3-test.apk\n",
            )
            self.assertIn("Texte corrigé", output.notice.read_text())
            self.assertNotIn("corrected", output.notice.read_text())
            self.assertNotIn("cleanup", output.notice.read_text())

    def test_rejects_apk_without_corrected_local_mode_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "app-debug.apk"
            entries = pilot_entries()
            entries["assets/gemma270-pilot-mode.txt"] = b"format=cleanup\nengine=local\n"
            write_apk(apk, entries)

            with self.assertRaisesRegex(VerificationError, "pilot mode marker"):
                verify_apk(apk, manifest_for())

    def test_rejects_apk_with_old_gemma4_or_350m_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "app-debug.apk"
            entries = pilot_entries()
            entries["assets/local-format/LFM2.5-350M-Q4_K_M.gguf"] = b"old model"
            write_apk(apk, entries)

            with self.assertRaisesRegex(VerificationError, "forbidden legacy"):
                verify_apk(apk, manifest_for())

    def test_keeps_historical_runtime_notice_allowed_for_attribution(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "app-debug.apk"
            entries = pilot_entries()
            entries["assets/local-format/gemma-NOTICE.txt"] = b"Historical Gemma runtime notice"
            write_apk(apk, entries)

            verify_apk(apk, manifest_for())

    def test_rejects_apk_missing_required_native_formatter(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "app-debug.apk"
            entries = pilot_entries()
            entries.pop("lib/arm64-v8a/libdictai_llm_arm82.so")
            write_apk(apk, entries)

            with self.assertRaisesRegex(VerificationError, "libdictai_llm_arm82.so"):
                verify_apk(apk, manifest_for())

    def test_required_asset_names_are_part_of_the_contract(self) -> None:
        self.assertEqual(
            REQUIRED_ASSET_NAMES,
            frozenset(
                {
                    "assets/local-format/gemma270-model.json",
                    "assets/local-format/LICENSE.txt",
                    "assets/local-format/NOTICE.txt",
                    "assets/local-format/PROHIBITED_USE_POLICY.txt",
                }
            ),
        )

    def test_rejects_non_utf8_manifest_without_traceback(self) -> None:
        from verify_gemma270_pilot import load_manifest

        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "gemma270-model.json"
            path.write_bytes(b"{\xff")
            with self.assertRaisesRegex(VerificationError, "UTF-8"):
                load_manifest(path)


if __name__ == "__main__":
    unittest.main()
