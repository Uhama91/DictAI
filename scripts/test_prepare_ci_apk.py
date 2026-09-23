"""Synthetic ZIP contract tests for the APK packaging variants."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))
from prepare_ci_apk import (  # noqa: E402
    LOCAL_FORMAT_LIBRARIES,
    GEMMA3_PILOT_NOTICE_ASSET,
    OUTPUT_NAMES,
    PILOT_NOTICE_ASSET,
    REQUIRED_ASSETS,
    stage_apk,
    variant_from_environment,
    verify_apk,
)


def write_fixture(
    path: Path,
    *,
    local: bool,
    model: str | None = None,
    pilot_notice: bool = False,
    gemma3_notice: bool = False,
) -> None:
    entries = list(REQUIRED_ASSETS) + ["lib/arm64-v8a/liblitertlm_jni.so"]
    if local:
        entries.extend(sorted(LOCAL_FORMAT_LIBRARIES))
    if pilot_notice:
        entries.append(PILOT_NOTICE_ASSET)
    if gemma3_notice:
        entries.append(GEMMA3_PILOT_NOTICE_ASSET)
    if model:
        entries.append(model)
    with zipfile.ZipFile(path, "w") as archive:
        for name in entries:
            archive.writestr(name, b"fixture")


class PrepareCiApkTest(unittest.TestCase):
    def test_accepts_normal_gpu_and_pilot_contracts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            normal = root / "normal.apk"
            gpu = root / "gpu.apk"
            pilot = root / "pilot.apk"
            write_fixture(normal, local=True)
            write_fixture(gpu, local=False)
            write_fixture(pilot, local=True, pilot_notice=True)

            self.assertIn("lib/arm64-v8a/libdictai_llm.so", verify_apk(normal, "normal"))
            self.assertNotIn("lib/arm64-v8a/libdictai_llm.so", verify_apk(gpu, "gpu"))
            self.assertIn("lib/arm64-v8a/libdictai_llm_arm82.so", verify_apk(pilot, "pilot"))

            for variant, apk in (("normal", normal), ("gpu", gpu), ("pilot", pilot)):
                destination, digest = stage_apk(apk, root / variant, variant)
                self.assertEqual(destination.name, OUTPUT_NAMES[variant])
                self.assertEqual(len(digest), 64)
                self.assertEqual((destination.parent / "SHA256SUMS").read_text(), f"{digest}  {destination.name}\n")

    def test_pilot_requires_both_local_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "pilot.apk"
            write_fixture(apk, local=True, pilot_notice=True)
            with zipfile.ZipFile(apk, "r") as source:
                entries = {name: source.read(name) for name in source.namelist()}
            entries.pop("lib/arm64-v8a/libdictai_llm_arm82.so")
            with zipfile.ZipFile(apk, "w") as target:
                for name, data in entries.items():
                    target.writestr(name, data)
            with self.assertRaisesRegex(AssertionError, "both local-format"):
                verify_apk(apk, "pilot")

    def test_gpu_rejects_local_libraries_and_all_variants_reject_models(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            gpu_with_local = root / "gpu-local.apk"
            write_fixture(gpu_with_local, local=True)
            with self.assertRaisesRegex(AssertionError, "GPU prototype"):
                verify_apk(gpu_with_local, "gpu")

            model = root / "model.apk"
            write_fixture(
                model,
                local=True,
                model="assets/local-format/partial.gguf",
                pilot_notice=True,
            )
            with self.assertRaisesRegex(AssertionError, "no old or partial model"):
                verify_apk(model, "pilot")

    def test_environment_uses_workflow_pilot_switch_and_keeps_gpu_switch(self) -> None:
        with patch.dict(os.environ, {"PROTOTYPE": "true"}, clear=True):
            self.assertEqual(variant_from_environment(), "gpu")
        with patch.dict(os.environ, {"GEMMA4_PILOT": "true", "PROTOTYPE": "true"}, clear=True):
            self.assertEqual(variant_from_environment(), "pilot")
        with patch.dict(os.environ, {"GEMMA4_FINE_TUNED_PILOT": "true"}, clear=True):
            self.assertEqual(variant_from_environment(), "normal")
        with patch.dict(os.environ, {"GEMMA3_PILOT": "true"}, clear=True):
            self.assertEqual(variant_from_environment(), "gemma3-pilot")
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(variant_from_environment(), "normal")

    def test_stage_digest_does_not_read_entire_destination_at_once(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            apk = root / "pilot.apk"
            write_fixture(apk, local=True, pilot_notice=True)
            with patch("pathlib.Path.read_bytes", side_effect=AssertionError("whole-file read")):
                destination, digest = stage_apk(apk, root / "staged", "pilot")
            self.assertEqual(len(digest), 64)
            self.assertEqual(destination.stat().st_size, apk.stat().st_size)

    def test_gemma4_pilot_requires_its_v6_notice(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            pilot = root / "pilot-without-notice.apk"
            normal = root / "normal-without-notice.apk"
            gpu = root / "gpu-without-notice.apk"
            write_fixture(pilot, local=True)
            write_fixture(normal, local=True)
            write_fixture(gpu, local=False)

            with self.assertRaisesRegex(AssertionError, "pilot notice"):
                verify_apk(pilot, "pilot")
            verify_apk(normal, "normal")
            verify_apk(gpu, "gpu")

    def test_gemma3_pilot_requires_its_notice_and_both_cpu_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            apk = root / "gemma3.apk"
            write_fixture(apk, local=True, gemma3_notice=True)

            names = verify_apk(apk, "gemma3-pilot")
            self.assertIn(GEMMA3_PILOT_NOTICE_ASSET, names)
            destination, digest = stage_apk(apk, root / "dist", "gemma3-pilot")
            self.assertEqual(destination.name, "dictai-gemma3-test.apk")
            self.assertEqual(len(digest), 64)

            with self.assertRaisesRegex(AssertionError, "pilot notice"):
                verify_apk(apk, "pilot")

    def test_gemma3_pilot_rejects_missing_notice_or_either_cpu_library(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            no_notice = root / "no-notice.apk"
            write_fixture(no_notice, local=True)
            with self.assertRaisesRegex(AssertionError, "Gemma 3 pilot notice"):
                verify_apk(no_notice, "gemma3-pilot")

            model = root / "gemma3-with-model.apk"
            write_fixture(
                model,
                local=True,
                model="assets/local-format/partial.gguf",
                gemma3_notice=True,
            )
            with self.assertRaisesRegex(AssertionError, "no old or partial model"):
                verify_apk(model, "gemma3-pilot")

            one_library = root / "one-library.apk"
            write_fixture(one_library, local=True, gemma3_notice=True)
            with zipfile.ZipFile(one_library, "r") as source:
                entries = {name: source.read(name) for name in source.namelist()}
            entries.pop("lib/arm64-v8a/libdictai_llm_arm82.so")
            with zipfile.ZipFile(one_library, "w") as target:
                for name, data in entries.items():
                    target.writestr(name, data)
            with self.assertRaisesRegex(AssertionError, "both local-format"):
                verify_apk(one_library, "gemma3-pilot")

    def test_environment_rejects_conflicting_gemma3_and_gemma4_routes(self) -> None:
        with patch.dict(
            os.environ,
            {"GEMMA3_PILOT": "true", "GEMMA4_PILOT": "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "cannot be combined"):
                variant_from_environment()
        with patch.dict(
            os.environ,
            {"GEMMA3_PILOT": "true", "PROTOTYPE": "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "cannot be combined"):
                variant_from_environment()


if __name__ == "__main__":
    unittest.main()
