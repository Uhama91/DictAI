#!/usr/bin/env python3
"""Tests for portable Android NDK host-tool resolution."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))

from ndk_host_tools import candidate_host_tags, resolve_tool  # noqa: E402


class NdkHostToolsTest(unittest.TestCase):
    def test_apple_silicon_prefers_native_darwin_toolchain(self) -> None:
        self.assertEqual(candidate_host_tags("Darwin", "arm64")[0], "darwin-arm64")

    def test_intel_mac_can_use_x86_toolchain(self) -> None:
        self.assertEqual(candidate_host_tags("Darwin", "x86_64"), ("darwin-x86_64", "darwin-arm64"))

    def test_linux_x86_64_uses_linux_toolchain(self) -> None:
        self.assertEqual(candidate_host_tags("Linux", "x86_64"), ("linux-x86_64",))

    def test_resolves_first_available_tool(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            ndk = Path(temp_dir)
            tool = ndk / "toolchains/llvm/prebuilt/darwin-arm64/bin/llvm-readelf"
            tool.parent.mkdir(parents=True)
            tool.touch()
            tool.chmod(0o755)
            self.assertEqual(resolve_tool(ndk, "llvm-readelf", "Darwin", "arm64"), tool)

    def test_reports_missing_tool_with_host_candidates(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with self.assertRaisesRegex(FileNotFoundError, "llvm-strip.*darwin-arm64"):
                resolve_tool(Path(temp_dir), "llvm-strip", "Darwin", "arm64")


if __name__ == "__main__":
    unittest.main()
