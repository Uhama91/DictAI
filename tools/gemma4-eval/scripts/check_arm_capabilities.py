#!/usr/bin/env python3
"""Verify the exact ARM CPU and CMake feature contract for the Q6 build.

This probe only reads text files supplied by the caller. It never loads a model,
invokes a compiler, or inspects weights.
"""

from __future__ import annotations

import argparse
import json
import platform
import sys
from pathlib import Path
from typing import Iterable, Mapping


EXPECTED_ARM_ARCH = "armv8-a+dotprod+fp16"
REQUIRED_CPU_CAPABILITIES = ("asimd", "asimddp", "fphp", "asimdhp")
REQUIRED_CMAKE_CHECKS = ("HAVE_DOTPROD", "HAVE_FP16_VECTOR_ARITHMETIC")
_FEATURE_KEYS = {"features", "flags"}


class ArmCapabilityError(ValueError):
    """Raised when the runner cannot prove the requested ARM build contract."""


def _feature_lines(cpuinfo_text: str) -> list[tuple[str, ...]]:
    lines: list[tuple[str, ...]] = []
    for raw_line in cpuinfo_text.splitlines():
        key, separator, value = raw_line.partition(":")
        if not separator or key.strip().lower() not in _FEATURE_KEYS:
            continue
        tokens = tuple(token.strip().lower() for token in value.split() if token.strip())
        # Keep an empty Features/flags record: it represents a CPU line that
        # cannot prove the required capabilities and must therefore fail.
        lines.append(tokens)
    return lines


def validate_cpu_capabilities(machine: str, cpuinfo_text: str) -> dict[str, object]:
    """Require every Linux ARM feature line to expose all required capabilities."""

    normalized_machine = machine.strip().lower()
    if normalized_machine != "aarch64":
        raise ArmCapabilityError(
            f"ARM feature probe requires aarch64 runner, got {machine.strip() or '<empty>'}"
        )
    feature_lines = _feature_lines(cpuinfo_text)
    if not feature_lines:
        raise ArmCapabilityError("/proc/cpuinfo has no Features or flags line")

    required = set(REQUIRED_CPU_CAPABILITIES)
    missing_by_line = {
        index: sorted(required.difference(tokens))
        for index, tokens in enumerate(feature_lines)
        if required.difference(tokens)
    }
    if missing_by_line:
        details = ", ".join(
            f"line {index}: {', '.join(missing)}" for index, missing in missing_by_line.items()
        )
        raise ArmCapabilityError(f"CPU capability lines are incomplete ({details})")

    observed = sorted({token for tokens in feature_lines for token in tokens})
    return {
        "machine": normalized_machine,
        "required_capabilities": list(REQUIRED_CPU_CAPABILITIES),
        "feature_line_count": len(feature_lines),
        "all_lines_support_required": True,
        "observed_capabilities": observed,
    }


def _cache_values(cache_text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in cache_text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("//") or "=" not in line:
            continue
        left, value = line.split("=", 1)
        name = left.split(":", 1)[0].strip()
        if name:
            values[name] = value.strip()
    return values


def _cmake_true(value: str | None) -> bool:
    return value is not None and value.strip().upper() in {"1", "ON", "TRUE", "YES"}


def validate_cmake_cache(
    cache_text: str,
    *,
    expected_arch: str = EXPECTED_ARM_ARCH,
) -> dict[str, object]:
    """Require the requested architecture and both successful CMake probes."""

    values = _cache_values(cache_text)
    requested_arch = values.get("GGML_CPU_ARM_ARCH")
    if requested_arch != expected_arch:
        raise ArmCapabilityError(
            f"CMakeCache GGML_CPU_ARM_ARCH is {requested_arch!r}, expected {expected_arch!r}"
        )

    checks: dict[str, bool] = {}
    for name in REQUIRED_CMAKE_CHECKS:
        if not _cmake_true(values.get(name)):
            raise ArmCapabilityError(f"CMakeCache {name} is not enabled")
        checks[name] = True
    return {"requested_arch": requested_arch, "checks": checks}


def _write_new_json(path: Path, payload: Mapping[str, object]) -> None:
    if path.exists() or path.is_symlink():
        raise ArmCapabilityError(f"refusing to overwrite capability report: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--machine", default=platform.machine())
    parser.add_argument("--cpuinfo", type=Path, default=Path("/proc/cpuinfo"))
    parser.add_argument("--cmake-cache", type=Path)
    parser.add_argument("--expected-arch", default=EXPECTED_ARM_ARCH)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(list(argv) if argv is not None else None)

    try:
        cpu_report = validate_cpu_capabilities(
            args.machine,
            args.cpuinfo.read_text(encoding="utf-8"),
        )
        payload: dict[str, object] = {
            **cpu_report,
            "compiler_arch": args.expected_arch,
            "model_loaded": False,
        }
        if args.cmake_cache is not None:
            payload["cmake"] = validate_cmake_cache(
                args.cmake_cache.read_text(encoding="utf-8"),
                expected_arch=args.expected_arch,
            )
        _write_new_json(args.output, payload)
    except (ArmCapabilityError, OSError, UnicodeError) as error:
        print(f"ARM capability check failed: {error}", file=sys.stderr)
        return 2

    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
