from __future__ import annotations

import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path


PACKAGE = Path(__file__).resolve().parents[1]
SCRIPT = PACKAGE / "scripts/check_arm_capabilities.py"
SPEC = importlib.util.spec_from_file_location("check_arm_capabilities", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
arm = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(arm)


class ArmCapabilityTests(unittest.TestCase):
    def test_all_aarch64_feature_lines_must_have_dotprod_and_fp16_capabilities(self):
        cpuinfo = """Processor : 0\nFeatures : fp asimd evt aes pmull sha1 sha2 fphp asimdhp asimddp\n\nProcessor : 1\nFeatures : fp asimd evt aes pmull sha1 sha2 fphp asimdhp asimddp\n"""
        report = arm.validate_cpu_capabilities("aarch64", cpuinfo)
        self.assertEqual(report["machine"], "aarch64")
        self.assertEqual(report["feature_line_count"], 2)
        self.assertEqual(report["required_capabilities"], list(arm.REQUIRED_CPU_CAPABILITIES))
        self.assertTrue(report["all_lines_support_required"])

    def test_non_aarch64_is_refused_even_when_fixture_has_required_words(self):
        cpuinfo = "Features : asimd asimddp fphp asimdhp\n"
        with self.assertRaisesRegex(arm.ArmCapabilityError, "aarch64"):
            arm.validate_cpu_capabilities("x86_64", cpuinfo)

    def test_missing_capability_on_one_cpu_line_is_refused(self):
        cpuinfo = """Features : asimd asimddp fphp asimdhp\nFeatures : asimd asimddp fphp\n"""
        with self.assertRaisesRegex(arm.ArmCapabilityError, "asimdhp"):
            arm.validate_cpu_capabilities("aarch64", cpuinfo)

    def test_empty_features_line_is_refused_even_after_a_complete_line(self):
        cpuinfo = """Features : asimd asimddp fphp asimdhp\nFeatures :\n"""
        with self.assertRaisesRegex(arm.ArmCapabilityError, "line 1.*asimd"):
            arm.validate_cpu_capabilities("aarch64", cpuinfo)

    def test_missing_features_section_is_refused(self):
        with self.assertRaisesRegex(arm.ArmCapabilityError, "Features"):
            arm.validate_cpu_capabilities("aarch64", "Processor : 0\n")

    def test_cmake_cache_requires_requested_arch_and_both_feature_checks(self):
        cache = """# synthetic CMake cache\nGGML_CPU_ARM_ARCH:STRING=armv8-a+dotprod+fp16\nHAVE_DOTPROD:INTERNAL=1\nHAVE_FP16_VECTOR_ARITHMETIC:INTERNAL=ON\n"""
        report = arm.validate_cmake_cache(cache)
        self.assertEqual(report["requested_arch"], arm.EXPECTED_ARM_ARCH)
        self.assertEqual(report["checks"], {"HAVE_DOTPROD": True, "HAVE_FP16_VECTOR_ARITHMETIC": True})

    def test_cmake_cache_rejects_missing_or_false_checks(self):
        cache = """GGML_CPU_ARM_ARCH:STRING=armv8-a+dotprod+fp16\nHAVE_DOTPROD:INTERNAL=1\nHAVE_FP16_VECTOR_ARITHMETIC:INTERNAL=\n"""
        with self.assertRaisesRegex(arm.ArmCapabilityError, "HAVE_FP16_VECTOR_ARITHMETIC"):
            arm.validate_cmake_cache(cache)

    def test_cli_writes_separate_cpu_and_cmake_reports_without_model_loading(self):
        cpuinfo = "Features : asimd asimddp fphp asimdhp\n"
        cache = """GGML_CPU_ARM_ARCH:STRING=armv8-a+dotprod+fp16\nHAVE_DOTPROD:INTERNAL=1\nHAVE_FP16_VECTOR_ARITHMETIC:INTERNAL=1\n"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cpu_path = root / "cpuinfo"
            cache_path = root / "CMakeCache.txt"
            output = root / "capabilities.json"
            cpu_path.write_text(cpuinfo, encoding="utf-8")
            cache_path.write_text(cache, encoding="utf-8")
            exit_code = arm.main(
                [
                    "--machine",
                    "aarch64",
                    "--cpuinfo",
                    str(cpu_path),
                    "--cmake-cache",
                    str(cache_path),
                    "--output",
                    str(output),
                ]
            )
            self.assertEqual(exit_code, 0)
            payload = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(payload["machine"], "aarch64")
            self.assertEqual(payload["cmake"]["requested_arch"], arm.EXPECTED_ARM_ARCH)
            self.assertFalse(payload["model_loaded"])


class PackageAlignmentTests(unittest.TestCase):
    def test_manifest_and_workflow_pin_the_arm_alignment_contract(self):
        manifest = json.loads((PACKAGE / "package_manifest.json").read_text(encoding="utf-8"))
        alignment = manifest["arm_build_alignment"]
        self.assertEqual(alignment["machine"], "aarch64")
        self.assertEqual(alignment["compiler_arch"], arm.EXPECTED_ARM_ARCH)
        self.assertEqual(alignment["cpu_capabilities"], list(arm.REQUIRED_CPU_CAPABILITIES))
        self.assertEqual(
            alignment["cmake_checks"],
            ["HAVE_DOTPROD", "HAVE_FP16_VECTOR_ARITHMETIC"],
        )
        files = manifest["files"]
        self.assertIn("scripts/check_arm_capabilities.py", files)
        self.assertIn("tests/test_arm_capabilities.py", files)
        workflow = (PACKAGE.parents[1] / ".github/workflows/evaluate-gemma4-q6.yml").read_text(encoding="utf-8")
        self.assertIn("scripts/check_arm_capabilities.py", workflow)
        self.assertIn("-DGGML_CPU_ARM_ARCH=armv8-a+dotprod+fp16", workflow)
        self.assertIn("HAVE_DOTPROD", workflow)
        self.assertIn("HAVE_FP16_VECTOR_ARITHMETIC", workflow)
        for relative in ("scripts/check_arm_capabilities.py", "tests/test_arm_capabilities.py"):
            path = PACKAGE / relative
            self.assertEqual(files[relative]["bytes"], path.stat().st_size)
            self.assertEqual(
                files[relative]["sha256"],
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )


if __name__ == "__main__":
    unittest.main()
