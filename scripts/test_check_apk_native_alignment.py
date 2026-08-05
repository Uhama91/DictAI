#!/usr/bin/env python3
"""Tests de régression pour le contrôleur des bibliothèques natives de l'APK."""

from __future__ import annotations

import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_apk_native_alignment.py")


def elf_with_load_alignment(alignment: int, byte_order: str = "<") -> bytes:
    """Construit le plus petit ELF64 contenant un segment PT_LOAD."""
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02" + (b"\x01" if byte_order == "<" else b"\x02")
    struct.pack_into(byte_order + "Q", data, 32, 64)  # e_phoff
    struct.pack_into(byte_order + "H", data, 54, 56)  # e_phentsize
    struct.pack_into(byte_order + "H", data, 56, 1)  # e_phnum
    struct.pack_into(byte_order + "I", data, 64, 1)  # p_type = PT_LOAD
    struct.pack_into(byte_order + "Q", data, 112, alignment)  # p_align
    return bytes(data)


class CheckApkNativeAlignmentTest(unittest.TestCase):
    def run_check(self, entries: list[tuple[str, bytes, int]]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name, contents, compression in entries:
                    archive.writestr(name, contents, compress_type=compression)
            return subprocess.run(
                [sys.executable, str(SCRIPT), str(apk)],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_rejects_llama_library_even_with_16_kib_alignment(self) -> None:
        result = self.run_check(
            [("lib/arm64-v8a/libllama.so", elf_with_load_alignment(16 * 1024), zipfile.ZIP_STORED)]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("libllama.so", result.stdout)

    def test_rejects_library_with_4_kib_alignment(self) -> None:
        result = self.run_check(
            [("lib/arm64-v8a/libexample.so", elf_with_load_alignment(4 * 1024), zipfile.ZIP_STORED)]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("p_align=[4096]", result.stdout)

    def test_accepts_library_with_16_kib_alignment(self) -> None:
        result = self.run_check(
            [("lib/arm64-v8a/libexample.so", elf_with_load_alignment(16 * 1024), zipfile.ZIP_STORED)]
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_apk_without_native_libraries(self) -> None:
        result = self.run_check([])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no native libraries", result.stdout)

    def test_rejects_compressed_native_library_even_with_16_kib_alignment(self) -> None:
        result = self.run_check(
            [("lib/arm64-v8a/libexample.so", elf_with_load_alignment(16 * 1024), zipfile.ZIP_DEFLATED)]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not stored", result.stdout)

    def test_rejects_zero_pt_load_alignment(self) -> None:
        result = self.run_check(
            [("lib/arm64-v8a/libexample.so", elf_with_load_alignment(0), zipfile.ZIP_STORED)]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("p_align=[0]", result.stdout)

    def test_rejects_unit_pt_load_alignment(self) -> None:
        result = self.run_check(
            [("lib/arm64-v8a/libexample.so", elf_with_load_alignment(1), zipfile.ZIP_STORED)]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("p_align=[1]", result.stdout)

    def test_rejects_truncated_elf_without_traceback(self) -> None:
        truncated_elf = b"\x7fELF\x02\x01" + b"\0" * 57
        result = self.run_check(
            [("lib/arm64-v8a/libexample.so", truncated_elf, zipfile.ZIP_STORED)]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("truncated ELF header", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_accepts_big_endian_elf64_with_16_kib_alignment(self) -> None:
        result = self.run_check(
            [("lib/arm64-v8a/libexample.so", elf_with_load_alignment(16 * 1024, ">"), zipfile.ZIP_STORED)]
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(
            result.stdout,
            "All 1 native libraries have 16 KB ELF alignment, are stored, and are allowed.\n",
        )

    def test_rejects_elf32_without_traceback(self) -> None:
        elf32 = bytearray(elf_with_load_alignment(16 * 1024))
        elf32[4] = 1
        result = self.run_check([("lib/armeabi-v7a/libexample.so", bytes(elf32), zipfile.ZIP_STORED)])

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not an ELF64 file", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_rejects_elf_without_pt_load_segment(self) -> None:
        elf_without_load = bytearray(elf_with_load_alignment(16 * 1024))
        struct.pack_into("<H", elf_without_load, 56, 0)
        result = self.run_check(
            [("lib/arm64-v8a/libexample.so", bytes(elf_without_load), zipfile.ZIP_STORED)]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no PT_LOAD segment", result.stdout)

    def test_reports_non_conforming_library_in_mixed_apk(self) -> None:
        result = self.run_check(
            [
                ("lib/arm64-v8a/libconforming.so", elf_with_load_alignment(16 * 1024), zipfile.ZIP_STORED),
                ("lib/x86_64/libmisaligned.so", elf_with_load_alignment(4 * 1024), zipfile.ZIP_STORED),
            ]
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("lib/x86_64/libmisaligned.so", result.stdout)


if __name__ == "__main__":
    unittest.main()
