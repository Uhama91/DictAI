#!/usr/bin/env python3
"""Resolve Android NDK LLVM tools on supported Linux and Darwin hosts."""

from __future__ import annotations

import argparse
import os
import platform
from pathlib import Path


def candidate_host_tags(system: str | None = None, machine: str | None = None) -> tuple[str, ...]:
    system = system or platform.system()
    machine = (machine or platform.machine()).casefold()
    if system == "Darwin":
        if machine in {"arm64", "aarch64"}:
            return ("darwin-arm64", "darwin-x86_64")
        if machine in {"x86_64", "amd64"}:
            return ("darwin-x86_64", "darwin-arm64")
    if system == "Linux":
        if machine in {"arm64", "aarch64"}:
            return ("linux-aarch64", "linux-x86_64")
        if machine in {"x86_64", "amd64"}:
            return ("linux-x86_64",)
    raise ValueError(f"unsupported NDK host: {system}/{machine}")


def resolve_tool(
    ndk_root: Path,
    tool_name: str,
    system: str | None = None,
    machine: str | None = None,
) -> Path:
    tags = candidate_host_tags(system, machine)
    candidates = [
        ndk_root / "toolchains" / "llvm" / "prebuilt" / tag / "bin" / tool_name
        for tag in tags
    ]
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    paths = ", ".join(str(candidate) for candidate in candidates)
    raise FileNotFoundError(f"NDK tool {tool_name!r} not found for {tags[0]} (checked {paths})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--tool", required=True)
    args = parser.parse_args()
    print(resolve_tool(args.ndk, args.tool))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
