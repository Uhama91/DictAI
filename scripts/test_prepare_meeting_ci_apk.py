#!/usr/bin/env python3
"""SDK-free contract tests for the isolated meeting APK staging script."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "prepare_meeting_ci_apk.py"
MEETING_LIBRARY = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libdictai_meeting.so"
MEETING_ASSETS = tuple(
    "assets/licenses/meeting/" + name
    for name in (
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
    )
)
HISTORICAL_LIBRARIES = (
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml.so",
    "libonnxruntime.so",
    "libsherpa-onnx-c-api.so",
    "libsherpa-onnx-cxx-api.so",
    "libsherpa-onnx-jni.so",
    "libtranscribe.so",
    "libtranscribe_jni.so",
)
BADGING = (
    "package: name='com.uhama.whisperpin.meetingtest' versionCode='35' "
    "versionName='0.9.6-dictai-meeting-test' platformBuildVersionName='14'\n"
    "native-code: 'arm64-v8a'\n"
)


def required_entries() -> dict[str, bytes]:
    entries = {
        "assets/local-format/gemma-NOTICE.txt": b"Gemma notice",
        "assets/local-format/Apache-2.0.txt": b"Local formatter license",
        "assets/local-format/layout-list.prompt": b"list prompt",
        "assets/local-format/layout-email.prompt": b"email prompt",
        "assets/local-format/llama-LICENSE.txt": b"llama license",
        "assets/licenses/Apache-2.0.txt": b"Apache license",
        "assets/licenses/NOTICE.txt": b"General notice",
        "assets/licenses/sherpa-onnx-1.13.4-LICENSE.txt": b"sherpa license",
        "assets/licenses/onnxruntime-1.27.0-LICENSE.txt": b"onnx license",
        "assets/THIRD_PARTY_NOTICES.txt": b"Third party notices",
        "lib/arm64-v8a/liblitertlm_jni.so": b"LiteRT runtime",
        "lib/arm64-v8a/libdictai_llm.so": b"legacy local formatter",
        "lib/arm64-v8a/libdictai_llm_arm82.so": b"legacy ARM formatter",
    }
    entries.update({f"lib/arm64-v8a/{name}": b"historical native library" for name in HISTORICAL_LIBRARIES})
    entries.update({name: b"meeting license fixture" for name in MEETING_ASSETS})
    entries["lib/arm64-v8a/libdictai_meeting.so"] = MEETING_LIBRARY.read_bytes()
    return entries


def write_apk(path: Path, entries: dict[str, bytes] | None = None) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as apk:
        for name, contents in (required_entries() if entries is None else entries).items():
            apk.writestr(name, contents)


class PrepareMeetingCiApkTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "fixture.apk"
        self.output = self.root / "dist" / "meeting-test"
        self.aapt = self.root / "aapt-fixture"
        self.write_aapt(BADGING)

    def write_aapt(self, output: str, exit_code: int = 0) -> None:
        self.aapt.write_text(
            "#!/usr/bin/env python3\n"
            "import sys\n"
            f"sys.stdout.write({output!r})\n"
            f"sys.exit({exit_code})\n",
            encoding="utf-8",
        )
        self.aapt.chmod(0o755)

    def run_prepare(self) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["GITHUB_SHA"] = "0123456789abcdef" * 2 + "01234567"
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--apk",
                str(self.apk),
                "--aapt",
                str(self.aapt),
                "--output-dir",
                str(self.output),
            ],
            check=False,
            capture_output=True,
            text=True,
            env=env,
        )

    def assert_rejected(self, result: subprocess.CompletedProcess[str], reason: str) -> None:
        output = result.stdout + result.stderr
        self.assertNotEqual(result.returncode, 0, output)
        self.assertIn(reason, output)
        self.assertFalse(self.output.exists())

    def test_stages_valid_meeting_apk_with_identity_hashes_and_commit(self) -> None:
        write_apk(self.apk)

        result = self.run_prepare()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        apk_out = self.output / "dictai-meeting-test.apk"
        self.assertEqual(apk_out.read_bytes(), self.apk.read_bytes())
        apk_sha = hashlib.sha256(apk_out.read_bytes()).hexdigest()
        self.assertEqual((self.output / "SHA256SUMS").read_text(), f"{apk_sha}  dictai-meeting-test.apk\n")
        metadata = json.loads((self.output / "metadata.json").read_text())
        self.assertEqual(metadata["applicationId"], "com.uhama.whisperpin.meetingtest")
        self.assertEqual(metadata["versionCode"], 35)
        self.assertEqual(metadata["versionName"], "0.9.6-dictai-meeting-test")
        self.assertEqual(metadata["abi"], "arm64-v8a")
        self.assertEqual(metadata["sizeBytes"], self.apk.stat().st_size)
        self.assertEqual(metadata["sha256"], apk_sha)
        self.assertEqual(metadata["meetingEngineSha256"], hashlib.sha256(MEETING_LIBRARY.read_bytes()).hexdigest())
        self.assertEqual(metadata["ciCommit"], "0123456789abcdef" * 2 + "01234567")

    def test_rejects_non_meeting_identity_or_abi(self) -> None:
        write_apk(self.apk)
        bad_badging = (
            (BADGING.replace("com.uhama.whisperpin.meetingtest", "com.uhama.whisperpin"), "applicationId"),
            (BADGING.replace("versionCode='35'", "versionCode='34'"), "versionCode"),
            (BADGING.replace("0.9.6-dictai-meeting-test", "0.9.6-debug"), "versionName"),
            (BADGING.replace("arm64-v8a", "x86_64"), "arm64-v8a"),
        )

        for text, reason in bad_badging:
            with self.subTest(badging=text):
                self.write_aapt(text)
                result = self.run_prepare()
                self.assert_rejected(result, reason)

    def test_rejects_missing_meeting_notice_and_historical_library(self) -> None:
        for missing in (MEETING_ASSETS[0], "lib/arm64-v8a/libonnxruntime.so"):
            with self.subTest(missing=missing):
                entries = required_entries()
                del entries[missing]
                write_apk(self.apk, entries)
                result = self.run_prepare()
                self.assert_rejected(result, missing)

    def test_rejects_missing_local_formatter_and_general_notices(self) -> None:
        for missing in (
            "lib/arm64-v8a/libdictai_llm.so",
            "lib/arm64-v8a/libdictai_llm_arm82.so",
            "assets/THIRD_PARTY_NOTICES.txt",
        ):
            with self.subTest(missing=missing):
                entries = required_entries()
                del entries[missing]
                write_apk(self.apk, entries)
                result = self.run_prepare()
                self.assert_rejected(result, missing)

    def test_rejects_modified_meeting_engine_library(self) -> None:
        entries = required_entries()
        entries["lib/arm64-v8a/libdictai_meeting.so"] += b"modified"
        write_apk(self.apk, entries)

        result = self.run_prepare()

        self.assert_rejected(result, "libdictai_meeting.so")

    def test_rejects_embedded_full_or_partial_model_weights(self) -> None:
        for name in ("assets/models/meeting.gguf", "assets/cache/asr.litertlm.partial"):
            with self.subTest(name=name):
                entries = required_entries()
                entries[name] = b"must not ship"
                write_apk(self.apk, entries)
                result = self.run_prepare()
                self.assert_rejected(result, "model weights")

    def test_rejects_aapt_failure_and_truncated_archive(self) -> None:
        write_apk(self.apk)
        self.write_aapt("aapt failed\n", exit_code=2)
        result = self.run_prepare()
        self.assert_rejected(result, "aapt dump badging failed")

        self.apk.write_bytes(b"PK\x03\x04truncated")
        self.write_aapt(BADGING)
        result = self.run_prepare()
        self.assert_rejected(result, "invalid APK zip")

    def test_does_not_overwrite_an_existing_staging_directory(self) -> None:
        write_apk(self.apk)
        self.output.mkdir(parents=True)
        marker = self.output / "keep.txt"
        marker.write_text("existing", encoding="utf-8")

        result = self.run_prepare()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("already exists", result.stdout + result.stderr)
        self.assertEqual(marker.read_text(), "existing")


if __name__ == "__main__":
    unittest.main()
