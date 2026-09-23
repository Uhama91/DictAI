"""Unit tests for the Gemma 3 APK package/version/signing identity check."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))
from check_gemma3_apk_identity import verify_apk_identity  # noqa: E402


AAPT_OUTPUT = (
    "package: name='com.uhama.whisperpin' versionCode='40' "
    "versionName='0.9.11-dictai-gemma3-test'\n"
)
CERTIFICATE_OUTPUT = (
    "Signer #1 certificate SHA-256 digest: "
    "6b37c02704d31553b275a9a5f23c8eb650df04cd59f7b28074e6f2dcadbf9539\n"
)


class CheckGemma3ApkIdentityTest(unittest.TestCase):
    def run_verification(self, *, badging: str = AAPT_OUTPUT, certificates: str = CERTIFICATE_OUTPUT):
        responses = [
            subprocess.CompletedProcess(["aapt"], 0, stdout=badging, stderr=""),
            subprocess.CompletedProcess(["apksigner"], 0, stdout=certificates, stderr=""),
        ]
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "app-debug.apk"
            apk.write_bytes(b"synthetic apk input")
            with patch("check_gemma3_apk_identity.subprocess.run", side_effect=responses):
                return verify_apk_identity(apk, aapt="aapt", apksigner="apksigner")

    def test_accepts_only_the_reviewed_gemma3_application_identity(self) -> None:
        result = self.run_verification()
        self.assertEqual(result["application_id"], "com.uhama.whisperpin")
        self.assertEqual(result["version_code"], 40)
        self.assertEqual(result["version_name"], "0.9.11-dictai-gemma3-test")

    def test_rejects_wrong_package_version_and_signing_certificate(self) -> None:
        badging_outputs = (
            AAPT_OUTPUT.replace("com.uhama.whisperpin", "com.other.app"),
            AAPT_OUTPUT.replace("versionCode='40'", "versionCode='39'"),
            AAPT_OUTPUT.replace("0.9.11-dictai-gemma3-test", "0.9.10-dictai-latency-test"),
        )
        for badging in badging_outputs:
            with self.subTest(badging=badging):
                with self.assertRaisesRegex(AssertionError, "APK identity mismatch"):
                    self.run_verification(badging=badging)

        with self.assertRaisesRegex(AssertionError, "signing certificate mismatch"):
            self.run_verification(certificates=CERTIFICATE_OUTPUT.replace("6b37", "0b37"))

    def test_rejects_ambiguous_or_unreadable_identity_metadata(self) -> None:
        duplicate_signer = CERTIFICATE_OUTPUT + CERTIFICATE_OUTPUT.replace("Signer #1", "Signer #2")
        with self.assertRaisesRegex(AssertionError, "exactly one signer"):
            self.run_verification(certificates=duplicate_signer)
        with self.assertRaisesRegex(AssertionError, "package metadata not found"):
            self.run_verification(badging="no package metadata\n")

    def test_uses_aapt_and_apksigner_to_read_the_real_package_and_signature(self) -> None:
        responses = [
            subprocess.CompletedProcess(["aapt"], 0, stdout=AAPT_OUTPUT, stderr=""),
            subprocess.CompletedProcess(["apksigner"], 0, stdout=CERTIFICATE_OUTPUT, stderr=""),
        ]
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "app-debug.apk"
            apk.write_bytes(b"synthetic apk input")
            with patch("check_gemma3_apk_identity.subprocess.run", side_effect=responses) as run:
                verify_apk_identity(apk, aapt="/sdk/aapt", apksigner="/sdk/apksigner")

        commands = [invocation.args[0] for invocation in run.call_args_list]
        self.assertEqual(commands[0][:3], ["/sdk/aapt", "dump", "badging"])
        self.assertEqual(commands[1][:3], ["/sdk/apksigner", "verify", "--print-certs"])
        self.assertEqual(commands[0][-1], str(apk))
        self.assertEqual(commands[1][-1], str(apk))


if __name__ == "__main__":
    unittest.main(verbosity=2)
