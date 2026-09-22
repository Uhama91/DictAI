#!/usr/bin/env python3
"""Stage a small allowlist of Gemma 3 run reports for Actions artifact upload."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import tempfile
from pathlib import Path


RESULT_SOURCE_PATHS = (
    "prepare/manifest.json",
    "prepare/config_snapshot.json",
    "evaluation_budget.json",
    "preflight/report.json",
    "supervisor/supervisor_report.json",
    "supervisor/status.json",
    "supervisor/supervisor.log",
    "training/manifest.json",
    "training/resource_telemetry.json",
    "training/status.json",
    "training/mps_memory.jsonl",
    "parent_evaluation/manifest.json",
    "parent_evaluation/preflight.json",
    "parent_evaluation/resource_telemetry.json",
    "parent_evaluation/supervisor/supervisor_report.json",
    "parent_evaluation/supervisor/status.json",
    "parent_evaluation/supervisor/supervisor.log",
    "parent_evaluation/predictions.jsonl",
    "candidate_evaluation/manifest.json",
    "candidate_evaluation/preflight.json",
    "candidate_evaluation/resource_telemetry.json",
    "candidate_evaluation/supervisor/supervisor_report.json",
    "candidate_evaluation/supervisor/status.json",
    "candidate_evaluation/supervisor/supervisor.log",
    "candidate_evaluation/predictions.jsonl",
)
ADAPTER_OUTPUT_PATHS = (
    "training/best/README.md",
    "training/best/adapter_config.json",
    "training/best/adapter_model.safetensors",
    "training/best/chat_template.jinja",
    "training/best/tokenizer.json",
    "training/best/tokenizer_config.json",
    "training/best/training_args.bin",
)
LEGAL_RESULT_PATHS = (
    "legal/Gemma-Terms-of-Use.html",
    "legal/NOTICE.txt",
    "legal/SOURCE.txt",
)
RESULT_PATHS = (*RESULT_SOURCE_PATHS, *ADAPTER_OUTPUT_PATHS, *LEGAL_RESULT_PATHS, "artifact_manifest.json")
MAX_SINGLE_RESULT_BYTES = 300_000_000
MAX_RESULT_BYTES = 800_000_000


class ResultPackageError(RuntimeError):
    """An output cannot be added without violating the report allowlist."""


def _directory_sha256(directory: Path) -> str:
    files = sorted(path for path in directory.rglob("*") if path.is_file())
    if not files:
        raise ResultPackageError("completed training adapter directory is empty")
    digest = hashlib.sha256()
    for path in files:
        if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
            raise ResultPackageError("adapter output contains a symlink or non-regular file")
        digest.update(path.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _phase_status(root: Path, relative: str, expected_model: str) -> tuple[str, int | None]:
    path = root / relative / "manifest.json"
    if not path.is_file() or path.is_symlink():
        return "absent", None
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return "invalid", None
    if manifest.get("model_kind") != expected_model:
        return "invalid", None
    return str(manifest.get("status", "unknown")), manifest.get("records_written")


def _training_status(root: Path) -> tuple[str, int | None, bool]:
    path = root / "training/manifest.json"
    if not path.is_file() or path.is_symlink():
        return "absent", None, False
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return "invalid", None, False
    updates = manifest.get("completed_updates")
    completed = manifest.get("status") == "completed" and updates == 64
    adapter = root / "training/best"
    adapter_ok = False
    if completed and adapter.is_dir() and not adapter.is_symlink():
        try:
            actual_names = {path.name for path in adapter.iterdir()}
            expected_names = {Path(relative).name for relative in ADAPTER_OUTPUT_PATHS}
            adapter_ok = (
                actual_names == expected_names
                and
                _directory_sha256(adapter) == manifest.get("artifacts", {}).get("adapter_sha256")
                and all((adapter / name).is_file() and not (adapter / name).is_symlink() for name in expected_names)
            )
        except OSError:
            adapter_ok = False
    return str(manifest.get("status", "unknown")), updates if isinstance(updates, int) else None, bool(completed and adapter_ok)


def package_completed_results(prepared_dir: Path, destination: Path) -> dict[str, object]:
    """Copy only reviewed output names; accept incomplete runs for diagnosis."""
    prepared_dir = Path(prepared_dir)
    destination = Path(destination)
    if destination.exists() or destination.is_symlink():
        raise ResultPackageError("refusing to overwrite an existing result artifact")
    if not prepared_dir.exists() or not prepared_dir.is_dir() or prepared_dir.is_symlink():
        return {"status": "empty", "files": []}

    parent_status, parent_records = _phase_status(prepared_dir, "parent_evaluation", "parent")
    training_status, completed_updates, adapter_complete = _training_status(prepared_dir)
    candidate_status, candidate_records = _phase_status(prepared_dir, "candidate_evaluation", "candidate")
    run_status = (
        "completed"
        if parent_status == candidate_status == "completed"
        and parent_records == candidate_records == 108
        and adapter_complete
        else "incomplete"
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="gemma3-result-package-", dir=destination.parent) as temporary:
        staging = Path(temporary) / "results"
        staging.mkdir()
        file_records: dict[str, dict[str, object]] = {}
        total_bytes = 0

        def add_bytes(relative: str, data: bytes) -> None:
            nonlocal total_bytes
            if len(data) > MAX_SINGLE_RESULT_BYTES:
                raise ResultPackageError(f"allowlisted report exceeds its per-file cap: {relative}")
            total_bytes += len(data)
            if total_bytes > MAX_RESULT_BYTES:
                raise ResultPackageError("review artifact exceeds the 800 MB cap")
            output = staging / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            with output.open("xb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            file_records[relative] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}

        for relative in RESULT_SOURCE_PATHS:
            source = prepared_dir / relative
            if not source.exists() and not source.is_symlink():
                continue
            if source.is_symlink() or not source.is_file() or not stat.S_ISREG(source.stat().st_mode):
                raise ResultPackageError(f"allowlisted result is not a regular file: {relative}")
            if source.stat().st_size > MAX_SINGLE_RESULT_BYTES:
                raise ResultPackageError(f"allowlisted report exceeds its per-file cap: {relative}")
            data = source.read_bytes()
            add_bytes(relative, data)

        if adapter_complete:
            for relative in ADAPTER_OUTPUT_PATHS:
                source = prepared_dir / relative
                if source.is_symlink() or not source.is_file() or not stat.S_ISREG(source.stat().st_mode):
                    raise ResultPackageError(f"completed adapter component is absent or unsafe: {relative}")
                add_bytes(relative, source.read_bytes())

            payload_legal_root = Path(__file__).resolve().parent / "legal"
            for relative in LEGAL_RESULT_PATHS:
                source = payload_legal_root / Path(relative).name
                if source.is_symlink() or not source.is_file() or not stat.S_ISREG(source.stat().st_mode):
                    raise ResultPackageError(f"required Gemma terms attachment is absent or unsafe: {relative}")
                add_bytes(relative, source.read_bytes())

        if not file_records:
            return {"status": "empty", "files": []}
        artifact_manifest = {
            "schema_version": 1,
            "status": run_status,
            "phases": {
                "parent_evaluation": {"status": parent_status, "records_written": parent_records},
                "training": {"status": training_status, "completed_updates": completed_updates, "adapter_included": adapter_complete},
                "candidate_evaluation": {"status": candidate_status, "records_written": candidate_records},
            },
            "files": file_records,
            "base_model_included": False,
            "parent_adapter_included": False,
            "credentials_included": False,
        }
        add_bytes("artifact_manifest.json", (json.dumps(artifact_manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8"))
        staging.rename(destination)
    return {"status": run_status, "path": str(destination), "files": sorted(file_records)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    result = package_completed_results(args.prepared_dir, args.output_dir)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
