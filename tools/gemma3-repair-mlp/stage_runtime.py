#!/usr/bin/env python3
"""Copy only the reviewed MLP V2 files into a fresh, verified V1 extraction."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
from pathlib import Path, PurePosixPath
from typing import Any


RUNTIME_FILES = (
    "scripts/run_gemma3_repair_mlp_v2.py",
    "configs/gemma3_repair_mlp_v2.json",
    "src/asr_postclean/gemma3_mlp_adapter.py",
)


class RuntimeStageError(RuntimeError):
    """A reviewed runtime file cannot be safely staged into the V1 payload."""


def _path_without_symlinks(root: Path, relative: str) -> Path:
    path = PurePosixPath(relative)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise RuntimeStageError(f"unsafe relative runtime path: {relative}")
    current = root
    for component in path.parts:
        current = current / component
        if current.is_symlink():
            raise RuntimeStageError(f"symlink in runtime path: {relative}")
    return current


def _read_regular_source(root: Path, relative: str) -> bytes:
    source = _path_without_symlinks(root, relative)
    try:
        info = source.stat(follow_symlinks=False)
    except OSError as error:
        raise RuntimeStageError(f"reviewed runtime source is missing: {relative}") from error
    if not stat.S_ISREG(info.st_mode):
        raise RuntimeStageError(f"reviewed runtime source is not a regular file: {relative}")
    try:
        return source.read_bytes()
    except OSError as error:
        raise RuntimeStageError(f"reviewed runtime source cannot be read: {relative}") from error


def stage_runtime(source_root: Path, workspace: Path) -> dict[str, dict[str, Any]]:
    """Stage exactly three new V2 paths, refusing all existing destinations."""
    source_root = Path(source_root)
    workspace = Path(workspace)
    if source_root.is_symlink() or workspace.is_symlink():
        raise RuntimeStageError("runtime source and workspace roots must not be symlinks")
    try:
        source_root = source_root.resolve(strict=True)
        workspace = workspace.resolve(strict=True)
    except OSError as error:
        raise RuntimeStageError("runtime source or extracted workspace is absent") from error
    if not source_root.is_dir() or not workspace.is_dir():
        raise RuntimeStageError("runtime source and extracted workspace must be directories")

    payload: dict[str, bytes] = {}
    destinations: dict[str, Path] = {}
    for relative in RUNTIME_FILES:
        payload[relative] = _read_regular_source(source_root, relative)
        destination = _path_without_symlinks(workspace, relative)
        if destination.exists() or destination.is_symlink():
            raise RuntimeStageError(f"refusing to replace an extracted file: {relative}")
        if not destination.parent.is_dir():
            raise RuntimeStageError(f"expected V1 destination directory is absent: {relative}")
        destinations[relative] = destination

    records: dict[str, dict[str, Any]] = {}
    for relative in RUNTIME_FILES:
        data = payload[relative]
        destination = destinations[relative]
        try:
            with destination.open("xb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        except OSError as error:
            raise RuntimeStageError(f"could not stage V2 runtime file: {relative}") from error
        actual = destination.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if actual != data or hashlib.sha256(actual).hexdigest() != digest:
            raise RuntimeStageError(f"staged runtime file differs from its reviewed source: {relative}")
        records[relative] = {"bytes": len(data), "sha256": digest}
    return records


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    args = parser.parse_args(argv)
    result = stage_runtime(args.source_root, args.workspace)
    print(json.dumps({"status": "staged", "files": result}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
