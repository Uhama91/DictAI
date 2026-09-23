#!/usr/bin/env python3
"""Bounded, provenance-first Gemma 3 attention-plus-MLP experiment runner.

The default commands only validate or prepare metadata.  A real model is
loaded, and a child process is created, only by the explicit ``train
--execute`` or ``evaluate --execute`` commands after review.
"""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import fcntl
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

MODEL_ID = "google/gemma-3-270m-it"
MODEL_REVISION = "ac82b4e820549b854eebf28ce6dedaf9fdfa17b3"
PROMPT_VERSION = "v3"
CONFIG_DEFAULT = ROOT / "configs" / "gemma3_repair_mlp_v2.json"
PARENT_MANIFEST_DEFAULT = ROOT / "runs" / "gemma-3-270m-it-lora-v3" / "manifest.json"
PARENT_ADAPTER_DEFAULT = ROOT / "runs" / "gemma-3-270m-it-lora-v3" / "best"
CHILD_DATASET_DEFAULT = ROOT / "data_gemma3_repair_v1"
OLD_VALID_DEFAULT = ROOT / "data_v3" / "valid.jsonl"
RUNNER_SCHEMA = "gemma3-repair-mlp-v2-runner-1"
GIB = 1024**3
GLOBAL_ML_LOCK = Path("/tmp/asr-postclean-fr-gemma3-ml.lock")
RESOURCE_TELEMETRY_MAX_AGE_SECONDS = 10.0
TELEMETRY_STARTUP_GRACE_SECONDS = 30.0
EVALUATION_BUDGET_SCHEMA = "gemma3-repair-mlp-v2-evaluation-budget-1"
EVALUATION_BUDGET_DEFAULT_SECONDS = 1200.0
EVALUATION_FINALIZATION_RESERVE_SECONDS = 5.0
EVALUATION_CASE_DEADLINE_SECONDS = 20.0
_MPS_DRIVER_UNSET = object()
# Activated 2026-09-23 after principal functional and adversarial reviews; per-run gates remain.
EXECUTION_REVIEWED = True


class RunnerError(RuntimeError):
    """The locked continuation contract cannot be satisfied."""


class OutputExistsError(RunnerError):
    """A phase destination exists and must not be overwritten or retried."""


class IncompleteTrainingError(RunnerError):
    """The exact 64-update continuation was not completed."""


class ResourceGateError(RunnerError):
    """A required resource measurement or limit failed."""


class EvaluationBudgetError(RunnerError):
    """A gold target cannot fit within the future generation budget."""


def require_execution_reviewed(phase: str) -> None:
    if not EXECUTION_REVIEWED:
        raise RunnerError(
            f"execution_not_reviewed: {phase} is disabled until the two reviews "
            "and a fresh resource gate are recorded"
        )


@dataclass(frozen=True)
class RuntimeLimits:
    min_launch_available_bytes: int = 4 * GIB
    min_runtime_available_bytes: int = int(1.5 * GIB)
    min_free_disk_bytes: int = 10 * GIB
    rss_limit_bytes: int = 5 * GIB
    mps_driver_limit_bytes: int = 5 * GIB
    swap_delta_limit_bytes: int = 512 * 1024**2
    max_training_seconds: float = 15 * 60
    graceful_stop_seconds: float = 15.0


RUNTIME_LIMITS = RuntimeLimits()


@dataclass(frozen=True)
class ResourceSnapshot:
    available_bytes: int | None
    disk_free_bytes: int | None
    swap_used_bytes: int | None
    rss_bytes: int | None
    mps_driver_bytes: int | None


@dataclass(frozen=True)
class EvaluationSpec:
    model_kind: str
    output_dir: Path

    def __post_init__(self) -> None:
        if self.model_kind not in {"parent", "candidate"}:
            raise ValueError("evaluation requires one model: parent or candidate")


def _resolve(path: str | Path) -> Path:
    return Path(path).expanduser().resolve()


def _sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with _resolve(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sha256_directory(path: str | Path) -> str:
    directory = _resolve(path)
    files = sorted(item for item in directory.rglob("*") if item.is_file())
    if not files:
        raise RunnerError(f"adapter directory is empty: {directory}")
    digest = hashlib.sha256()
    for item in files:
        digest.update(str(item.relative_to(directory)).encode("utf-8"))
        digest.update(b"\0")
        digest.update(item.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _read_json(path: str | Path) -> dict[str, Any]:
    try:
        value = json.loads(_resolve(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RunnerError(f"cannot read JSON: {path}") from error
    if not isinstance(value, dict):
        raise RunnerError(f"JSON object required: {path}")
    return value


def _write_json_once(path: str | Path, value: Mapping[str, Any]) -> None:
    destination = _resolve(path)
    if destination.exists() or destination.is_symlink():
        raise OutputExistsError(f"refusing to overwrite output: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def _replace_json(path: str | Path, value: Mapping[str, Any]) -> None:
    destination = _resolve(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, destination)


def _write_jsonl_once(path: str | Path, rows: Iterable[Mapping[str, Any]]) -> None:
    destination = _resolve(path)
    if destination.exists() or destination.is_symlink():
        raise OutputExistsError(f"refusing to overwrite output: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(dict(row), ensure_ascii=False, sort_keys=True) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def _load_jsonl(path: str | Path) -> list[dict[str, Any]]:
    from asr_postclean.io import load_jsonl

    try:
        return load_jsonl(_resolve(path))
    except Exception as error:
        raise RunnerError(f"invalid locked JSONL dataset: {path}") from error


def seal_old_validation(path: str | Path, *, expected_records: int = 76) -> dict[str, Any]:
    validation_path = _resolve(path)
    if not validation_path.is_file():
        raise RunnerError(f"old validation file is absent: {validation_path}")
    rows = _load_jsonl(validation_path)
    ids = [str(row.get("id", "")) for row in rows]
    if len(rows) != expected_records:
        raise RunnerError(
            f"old validation must contain {expected_records} records; got {len(rows)}"
        )
    if any(not record_id for record_id in ids) or len(set(ids)) != len(ids):
        raise RunnerError("old validation IDs must be non-empty and unique")
    return {
        "path": str(validation_path),
        "records": len(rows),
        "ids": ids,
        "sha256": _sha256_file(validation_path),
    }


def verify_old_validation(path: str | Path, sealed: Mapping[str, Any]) -> dict[str, Any]:
    current = seal_old_validation(path, expected_records=int(sealed.get("records", 76)))
    if current != dict(sealed):
        raise RunnerError("old validation file or record identity changed after prepare")
    return current


def _monotonic_seconds() -> float:
    return time.monotonic()


def _host_boot_id() -> str:
    if sys.platform.startswith("linux"):
        try:
            value = Path("/proc/sys/kernel/random/boot_id").read_text(encoding="ascii").strip()
        except OSError as error:
            raise RunnerError("host boot identity is unavailable for monotonic evaluation budget") from error
        if value:
            return value
    elif sys.platform == "darwin":
        value = _run_text(("sysctl", "-n", "kern.boottime"))
        if value:
            return value
    raise RunnerError("host boot identity is unavailable for monotonic evaluation budget")


def _evaluation_budget_path(prepared_dir: str | Path) -> Path:
    return _resolve(prepared_dir) / "evaluation_budget.json"


def _read_evaluation_budget(prepared_dir: str | Path) -> dict[str, Any]:
    path = _evaluation_budget_path(prepared_dir)
    state = _read_json(path)
    if state.get("schema_version") != EVALUATION_BUDGET_SCHEMA:
        raise RunnerError("evaluation budget schema is not recognized")
    if state.get("host_boot_id") != _host_boot_id():
        raise RunnerError("evaluation budget monotonic clock belongs to a different host boot")
    return state


def _begin_evaluation_phase(
    prepared_dir: str | Path,
    model_kind: str,
    *,
    total_budget_seconds: float = EVALUATION_BUDGET_DEFAULT_SECONDS,
) -> float:
    if model_kind not in {"parent", "candidate"}:
        raise RunnerError("evaluation requires one model: parent or candidate")
    path = _evaluation_budget_path(prepared_dir)
    now = _monotonic_seconds()
    if path.exists():
        state = _read_evaluation_budget(prepared_dir)
        if float(state.get("total_budget_seconds", -1)) != float(total_budget_seconds):
            raise RunnerError("evaluation global budget changed after the first model phase")
    else:
        if model_kind != "parent":
            raise RunnerError("candidate evaluation requires a completed generated parent phase")
        state = {
            "schema_version": EVALUATION_BUDGET_SCHEMA,
            "host_boot_id": _host_boot_id(),
            "total_budget_seconds": float(total_budget_seconds),
            "phases": {},
            "total_elapsed_seconds": 0.0,
        }
    phases = state.setdefault("phases", {})
    if model_kind in phases:
        raise OutputExistsError(f"evaluation phase already exists and will not be retried: {model_kind}")
    if model_kind == "parent" and phases:
        raise RunnerError("parent evaluation must be the first model phase")
    if model_kind == "candidate":
        parent = phases.get("parent", {})
        if parent.get("status") != "completed":
            raise RunnerError("candidate evaluation requires the generated parent phase to be completed")
    elapsed_before = sum(
        float(phase.get("elapsed_seconds", 0.0))
        for phase in phases.values()
        if phase.get("status") != "running"
    )
    remaining = float(total_budget_seconds) - elapsed_before
    if remaining <= 0:
        raise EvaluationBudgetError("combined parent and candidate evaluation budget is exhausted")
    phases[model_kind] = {
        "status": "running",
        "execution_mode": "supervised_generated",
        "started_monotonic": now,
    }
    state["total_elapsed_seconds"] = elapsed_before
    _replace_json(path, state)
    return remaining


def _finish_evaluation_phase(
    prepared_dir: str | Path,
    model_kind: str,
    *,
    status: str,
    output_dir: str | Path | None = None,
    manifest_path: str | Path | None = None,
    predictions_path: str | Path | None = None,
) -> dict[str, Any]:
    state = _read_evaluation_budget(prepared_dir)
    phase = state.get("phases", {}).get(model_kind)
    if not isinstance(phase, dict) or phase.get("status") != "running":
        raise RunnerError(f"evaluation phase is not running: {model_kind}")
    ended = _monotonic_seconds()
    phase["ended_monotonic"] = ended
    phase["elapsed_seconds"] = max(0.0, ended - float(phase["started_monotonic"]))
    elapsed_before = sum(
        float(item.get("elapsed_seconds", 0.0))
        for key, item in state.get("phases", {}).items()
        if key != model_kind and item.get("status") != "running"
    )
    total_elapsed = elapsed_before + phase["elapsed_seconds"]
    budget = float(state.get("total_budget_seconds", 0.0))
    if status == "completed" and total_elapsed + EVALUATION_FINALIZATION_RESERVE_SECONDS > budget:
        status = "incomplete"
    phase["status"] = status
    if output_dir is not None:
        phase["output_dir"] = str(_resolve(output_dir))
    if manifest_path is not None:
        resolved_manifest = _resolve(manifest_path)
        if resolved_manifest.is_file():
            phase["manifest_path"] = str(resolved_manifest)
            phase["manifest_sha256"] = _sha256_file(resolved_manifest)
    if predictions_path is not None:
        resolved_predictions = _resolve(predictions_path)
        if resolved_predictions.is_file():
            phase["predictions_path"] = str(resolved_predictions)
            phase["predictions_sha256"] = _sha256_file(resolved_predictions)
    state["total_elapsed_seconds"] = total_elapsed
    _replace_json(_evaluation_budget_path(prepared_dir), state)
    return state


def _require_generated_parent_baseline(
    prepared_dir: str | Path,
    prepared_manifest: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    prepared = _resolve(prepared_dir)
    if not _evaluation_budget_path(prepared).is_file():
        raise RunnerError("training requires a completed supervised generated parent baseline of 108 cases")
    state = _read_evaluation_budget(prepared)
    parent = state.get("phases", {}).get("parent", {})
    if parent.get("status") != "completed" or parent.get("execution_mode") != "supervised_generated":
        raise RunnerError("training requires a completed supervised generated parent baseline of 108 cases")
    manifest_path = _resolve(parent.get("manifest_path", ""))
    predictions_path = _resolve(parent.get("predictions_path", ""))
    output_dir = _resolve(parent.get("output_dir", ""))
    if manifest_path.parent != output_dir or predictions_path.parent != output_dir:
        raise RunnerError("generated parent baseline paths do not share the sealed output directory")
    if not manifest_path.is_file() or not predictions_path.is_file():
        raise RunnerError("generated parent baseline artifacts are absent")
    if _sha256_file(manifest_path) != parent.get("manifest_sha256"):
        raise RunnerError("generated parent baseline manifest SHA-256 changed")
    if _sha256_file(predictions_path) != parent.get("predictions_sha256"):
        raise RunnerError("generated parent baseline predictions SHA-256 changed")
    report = _read_json(manifest_path)
    if (
        report.get("status") != "completed"
        or report.get("model_kind") != "parent"
        or report.get("execution_mode") != "supervised_generated"
        or report.get("model_loaded") is not True
        or report.get("records_expected") != 108
        or report.get("records_written") != 108
        or report.get("prediction_sha256") != parent.get("predictions_sha256")
    ):
        raise RunnerError("generated parent baseline is incomplete or not model-generated")
    if prepared_manifest is None:
        prepared_manifest = _read_prepared_manifest(prepared)
    if (
        report.get("prepared_manifest_sha256")
        != _sha256_file(prepared / "prepare" / "manifest.json")
        or report.get("child_dataset_revision")
        != prepared_manifest.get("child_dataset", {}).get("revision")
        or report.get("old_validation_sha256")
        != prepared_manifest.get("old_validation", {}).get("sha256")
    ):
        raise RunnerError("generated parent baseline does not match the sealed pilot inputs")
    if not isinstance(report.get("model_snapshot"), Mapping):
        raise RunnerError("generated parent baseline lacks the sealed model snapshot")
    expected_records = _evaluation_records(prepared_manifest)
    expected_ids = [str(record["id"]) for record in expected_records]
    rows = load_frozen_predictions(
        predictions_path,
        expected_ids,
        str(parent.get("predictions_sha256", "")),
    )
    if any(validate_evaluation_result(row, expected_id=record_id)["status"] != "complete" for row, record_id in zip(rows, expected_ids, strict=True)):
        raise RunnerError("generated parent baseline contains incomplete cases")
    return {
        "output_dir": str(output_dir),
        "manifest_path": str(manifest_path),
        "manifest_sha256": str(parent["manifest_sha256"]),
        "predictions_path": str(predictions_path),
        "predictions_sha256": str(parent["predictions_sha256"]),
        "records": len(rows),
        "model_snapshot": report.get("model_snapshot"),
    }


def load_candidate_config(path: str | Path = CONFIG_DEFAULT) -> dict[str, Any]:
    config = _read_json(path)
    validate_candidate_config(config)
    return config


def validate_candidate_config(config: Mapping[str, Any]) -> None:
    if config.get("experiment") != "gemma3_repair_mlp_v2":
        raise RunnerError("unexpected Gemma 3 repair experiment")
    model = config.get("model")
    if model != {"id": MODEL_ID, "revision": MODEL_REVISION}:
        raise RunnerError("model id or immutable revision is not the locked Gemma 3 base")
    child = config.get("child_dataset", {})
    if child.get("revision") is not None:
        raise RunnerError("candidate configuration must not hardcode the child dataset SHA")
    training = config.get("training", {})
    expected = {
        "train_records": 256,
        "valid_records": 32,
        "epochs": 1,
        "seed": 42,
        "max_length": 768,
        "dynamic_padding": True,
        "per_device_train_batch_size": 1,
        "per_device_eval_batch_size": 1,
        "gradient_accumulation_steps": 4,
        "expected_updates": 64,
        "learning_rate": 2e-5,
        "lora_r": 16,
        "lora_alpha": 32,
        "lora_dropout": 0.0,
        "prompt_version": PROMPT_VERSION,
        "edit_token_weight": 1.0,
        "validation_selection_metric": "eval_loss",
    }
    for key, value in expected.items():
        if training.get(key) != value:
            raise RunnerError(f"candidate training setting {key} must be {value!r}")
    from asr_postclean.gemma3_mlp_adapter import ALL_TARGET_MODULES

    if tuple(training.get("target_modules", ())) != ALL_TARGET_MODULES:
        raise RunnerError("candidate target modules must be exactly V3 attention plus Gemma 3 MLP")
    evaluation = config.get("evaluation", {})
    if evaluation.get("max_new_tokens") != 256 or evaluation.get("per_case_timeout_seconds") != 20:
        raise RunnerError("evaluation generation budget is not locked")
    if evaluation.get("global_timeout_seconds") != 1200:
        raise RunnerError("evaluation global budget is not locked")
    runtime = config.get("runtime", {})
    runtime_expected = {
        "offline": True,
        "min_launch_available_bytes": 4 * GIB,
        "min_runtime_available_bytes": int(1.5 * GIB),
        "min_free_disk_bytes": 10 * GIB,
        "rss_limit_bytes": 5 * GIB,
        "mps_driver_limit_bytes": 5 * GIB,
        "swap_delta_limit_bytes": 512 * 1024**2,
        "max_training_seconds": 900,
        "monitoring_required": True,
        "one_ml_process": True,
        "gradle_conflict_forbidden": True,
        "automatic_retry": False,
    }
    for key, value in runtime_expected.items():
        if runtime.get(key) != value:
            raise RunnerError(f"runtime setting {key} must be {value!r}")
    if config.get("privacy", {}).get("private_dictations_used") is not False:
        raise RunnerError("private dictations must remain excluded")


def validate_child_dataset(
    dataset_dir: str | Path,
    *,
    expected_train: int = 256,
    expected_valid: int = 32,
    train_file: str = "train.jsonl",
    valid_file: str | None = None,
) -> dict[str, Any]:
    """Validate the child manifest and split identity without reading a model."""
    from asr_postclean.io import validated_dataset_revision

    directory = _resolve(dataset_dir)
    if not directory.is_dir():
        raise RunnerError(f"child dataset directory is absent: {directory}")
    if valid_file is None:
        valid_file = "valid.jsonl" if (directory / "valid.jsonl").is_file() else "dev.jsonl"
    train_path = directory / train_file
    valid_path = directory / valid_file
    if not train_path.is_file() or not valid_path.is_file():
        raise RunnerError("child dataset requires train.jsonl and valid.jsonl/dev.jsonl")
    try:
        revision = validated_dataset_revision(directory)
    except Exception as error:
        raise RunnerError("child dataset manifest is not locked") from error
    train_rows = _load_jsonl(train_path)
    valid_rows = _load_jsonl(valid_path)
    if len(train_rows) != expected_train or len(valid_rows) != expected_valid:
        raise RunnerError(
            f"child dataset counts must be {expected_train}/{expected_valid}, "
            f"got {len(train_rows)}/{len(valid_rows)}"
        )
    train_ids = [str(row["id"]) for row in train_rows]
    valid_ids = [str(row["id"]) for row in valid_rows]
    if len(set(train_ids)) != len(train_ids) or len(set(valid_ids)) != len(valid_ids):
        raise RunnerError("child dataset IDs must be unique within each split")
    if set(train_ids) & set(valid_ids):
        raise RunnerError("child valid IDs must be disjoint from train IDs")
    def _signature(value: Any) -> str:
        import unicodedata

        return " ".join(unicodedata.normalize("NFKC", str(value)).casefold().split())

    train_sources = {_signature(row["source"]) for row in train_rows}
    valid_sources = {_signature(row["source"]) for row in valid_rows}
    train_groups = {_signature(row["group_id"]) for row in train_rows}
    valid_groups = {_signature(row["group_id"]) for row in valid_rows}
    if train_sources & valid_sources:
        raise RunnerError("child train and valid sources must be disjoint")
    if train_groups & valid_groups:
        raise RunnerError("child train and valid episodes must be disjoint")
    for split_name, rows in (("train", train_rows), ("valid", valid_rows)):
        for row in rows:
            if row.get("synthetic") is not True:
                raise RunnerError(f"child {split_name} contains a non-synthetic record")
            declared_split = str(row.get("split", ""))
            if split_name == "train" and declared_split != "train":
                raise RunnerError("child train rows must declare split=train")
            if split_name == "valid" and declared_split not in {"dev", "valid"}:
                raise RunnerError("child valid rows must declare split=dev or split=valid")
            if not str(row.get("source", "")).strip() or not str(row.get("target", "")).strip():
                raise RunnerError("child records require non-empty source and target")
    return {
        "path": str(directory),
        "revision": revision,
        "train_path": str(train_path),
        "valid_path": str(valid_path),
        "train_records": len(train_rows),
        "valid_records": len(valid_rows),
        "train_ids": train_ids,
        "valid_ids": valid_ids,
        "train_sha256": _sha256_file(train_path),
        "valid_sha256": _sha256_file(valid_path),
        "manifest_sha256": _sha256_file(directory / "manifest.json"),
    }


def _historical_training_module():
    from asr_postclean import training

    return training


def validate_parent_provenance(
    parent_manifest: str | Path,
    parent_adapter: str | Path,
    *,
    validator: Callable[..., dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Verify V3 using its own manifest revision before child data is considered."""
    manifest_path = _resolve(parent_manifest)
    adapter_path = _resolve(parent_adapter)
    if not manifest_path.is_file() or not adapter_path.is_dir():
        raise RunnerError("parent manifest or adapter directory is absent")
    manifest = _read_json(manifest_path)
    if manifest.get("status") != "completed":
        raise RunnerError("parent adapter manifest is not completed")
    model = manifest.get("model", {})
    if model.get("id") != MODEL_ID or model.get("revision") != MODEL_REVISION:
        raise RunnerError("parent model revision does not match the locked Gemma 3 base")
    parent_dataset_revision = manifest.get("dataset", {}).get("revision")
    if not isinstance(parent_dataset_revision, str) or not parent_dataset_revision:
        raise RunnerError("parent manifest lacks its own dataset revision")
    parent_config = manifest.get("config", {})
    from asr_postclean.prompting import instruction_sha256

    if parent_config.get("prompt_version") != PROMPT_VERSION or parent_config.get("prompt_sha256") != instruction_sha256(PROMPT_VERSION):
        raise RunnerError("parent prompt version or hash is incompatible")
    if not (adapter_path / "adapter_config.json").is_file() or not (
        (adapter_path / "adapter_model.safetensors").is_file()
        or (adapter_path / "adapter_model.bin").is_file()
    ):
        raise RunnerError("parent adapter lacks its immutable LoRA files")
    training = _historical_training_module()
    config = training.TrainingConfig(
        model_id=MODEL_ID,
        revision=MODEL_REVISION,
        max_length=int(parent_config.get("max_length", 1024)),
        per_device_train_batch_size=int(parent_config.get("per_device_train_batch_size", 1)),
        per_device_eval_batch_size=int(parent_config.get("per_device_eval_batch_size", 1)),
        gradient_accumulation_steps=int(parent_config.get("gradient_accumulation_steps", 4)),
        learning_rate=float(parent_config.get("learning_rate", 1e-4)),
        num_train_epochs=int(parent_config.get("num_train_epochs", 4)),
        dynamic_padding=bool(parent_config.get("dynamic_padding", True)),
        prompt_version=PROMPT_VERSION,
        validation_selection_metric=str(parent_config.get("validation_selection_metric", "eval_loss")),
        validation_max_new_tokens=int(parent_config.get("validation_max_new_tokens", 768)),
        lora_r=int(parent_config.get("lora_r", 16)),
        lora_alpha=int(parent_config.get("lora_alpha", 32)),
        lora_dropout=float(parent_config.get("lora_dropout", 0.0)),
    )
    if validator is None:
        validator = training.validate_initial_adapter
    try:
        validation = validator(
            adapter_path,
            config=config,
            dataset_revision=parent_dataset_revision,
            model_revision=MODEL_REVISION,
        )
    except Exception as error:
        raise RunnerError("historical V3 parent provenance validation failed") from error
    if not isinstance(validation, dict) or not validation.get("adapter_sha256"):
        raise RunnerError("parent validator did not return an adapter SHA")
    expected_adapter = manifest.get("artifacts", {}).get("adapter_dir")
    if expected_adapter and _resolve(manifest_path.parent / str(expected_adapter)) != adapter_path:
        raise RunnerError("parent adapter path differs from its manifest")
    return {
        "parent_manifest": str(manifest_path),
        "parent_manifest_sha256": _sha256_file(manifest_path),
        "parent_adapter": str(adapter_path),
        "parent_adapter_sha256": str(validation["adapter_sha256"]),
        "parent_dataset_revision": parent_dataset_revision,
        "child_dataset_revision": None,
        "model_id": MODEL_ID,
        "model_revision": MODEL_REVISION,
        "prompt_version": PROMPT_VERSION,
        "prompt_sha256": instruction_sha256(PROMPT_VERSION),
    }


def prepare_run(
    *,
    config_path: str | Path,
    child_dataset: str | Path,
    old_valid_path: str | Path = OLD_VALID_DEFAULT,
    parent_manifest: str | Path,
    parent_adapter: str | Path,
    output_dir: str | Path,
    parent_validator: Callable[..., dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Seal a fresh prepare phase; this function never loads a model."""
    output = _resolve(output_dir)
    if output.exists() or output.is_symlink():
        raise OutputExistsError(f"refusing to overwrite output: {output}")
    config = load_candidate_config(config_path)
    parent = validate_parent_provenance(
        parent_manifest,
        parent_adapter,
        validator=parent_validator,
    )
    child = validate_child_dataset(
        child_dataset,
        expected_train=int(config["child_dataset"]["train_records"]),
        expected_valid=int(config["child_dataset"]["valid_records"]),
        train_file=str(config["child_dataset"]["train_file"]),
        valid_file=str(config["child_dataset"]["valid_file"]),
    )
    old_validation = seal_old_validation(old_valid_path)
    from asr_postclean.prompting import instruction_sha256

    helper_paths = _sealed_helper_hashes()

    sealed = {
        "schema_version": RUNNER_SCHEMA,
        "status": "prepared",
        "phase": "prepare",
        "config_path": str(_resolve(config_path)),
        "config_sha256": _sha256_file(config_path),
        "model": config["model"],
        "parent": parent,
        "child_dataset": child,
        "old_validation": old_validation,
        "training": config["training"],
        "evaluation": config["evaluation"],
        "runtime": config["runtime"],
        "prompt": {"version": PROMPT_VERSION, "sha256": instruction_sha256(PROMPT_VERSION)},
        "helper_sha256": helper_paths,
        "runner_sha256": _sha256_file(Path(__file__).resolve()),
        "privacy": config["privacy"],
        "model_loaded": False,
        "child_dataset_revision_is_runtime_sealed": True,
    }
    manifest_path = output / "prepare" / "manifest.json"
    _write_json_once(manifest_path, sealed)
    _write_json_once(
        output / "prepare" / "config_snapshot.json",
        {"config": config, "config_sha256": _sha256_file(config_path), "child_dataset_revision": child["revision"]},
    )
    return sealed


def build_training_config(config: Mapping[str, Any]):
    training_values = config["training"]
    historical = _historical_training_module()
    return historical.TrainingConfig(
        model_id=MODEL_ID,
        revision=MODEL_REVISION,
        seed=int(training_values["seed"]),
        max_length=int(training_values["max_length"]),
        per_device_train_batch_size=int(training_values["per_device_train_batch_size"]),
        per_device_eval_batch_size=int(training_values["per_device_eval_batch_size"]),
        gradient_accumulation_steps=int(training_values["gradient_accumulation_steps"]),
        learning_rate=float(training_values["learning_rate"]),
        num_train_epochs=int(training_values["epochs"]),
        dynamic_padding=bool(training_values["dynamic_padding"]),
        prompt_version=str(training_values["prompt_version"]),
        validation_selection_metric=str(training_values["validation_selection_metric"]),
        validation_max_new_tokens=int(config["evaluation"]["max_new_tokens"]),
        lora_r=int(training_values["lora_r"]),
        lora_alpha=int(training_values["lora_alpha"]),
        lora_dropout=float(training_values["lora_dropout"]),
        edit_token_weight=float(training_values["edit_token_weight"]),
    )


def prepare_training_features(records: list[dict[str, Any]], tokenizer) -> list[dict[str, Any]]:
    """Use the historical V3 feature builder, including completion-only labels."""
    historical = _historical_training_module()
    dataset = historical.prepare_split(
        records,
        tokenizer,
        max_length=768,
        prompt_version=PROMPT_VERSION,
        edit_token_weight=1.0,
    )
    return list(dataset.features)


def require_complete_updates(global_step: int, expected: int = 64) -> int:
    if int(global_step) != expected:
        raise IncompleteTrainingError(
            f"continuation requires exactly {expected} completed updates; got {global_step}"
        )
    return expected


def enforce_offline_environment() -> dict[str, str]:
    values = {
        "HF_HUB_OFFLINE": "1",
        "TRANSFORMERS_OFFLINE": "1",
        "HF_DATASETS_OFFLINE": "1",
    }
    for key, value in values.items():
        current = os.environ.get(key)
        if current not in {None, "", "1", "true", "True"}:
            raise RunnerError(f"offline mode was explicitly disabled by {key}")
        os.environ[key] = value
    return values


def validate_launch_resources(snapshot: ResourceSnapshot, limits: RuntimeLimits = RUNTIME_LIMITS) -> None:
    if any(
        value is None
        for value in (
            snapshot.available_bytes,
            snapshot.disk_free_bytes,
            snapshot.swap_used_bytes,
            snapshot.rss_bytes,
            snapshot.mps_driver_bytes,
        )
    ):
        raise ResourceGateError("resource monitoring unavailable before launch")
    if snapshot.available_bytes < limits.min_launch_available_bytes:
        raise ResourceGateError("available memory below 4 GiB before launch")
    if snapshot.disk_free_bytes < limits.min_free_disk_bytes:
        raise ResourceGateError("free disk below 10 GiB before launch")
    if snapshot.mps_driver_bytes > limits.mps_driver_limit_bytes:
        raise ResourceGateError("MPS driver memory already exceeds 5 GiB")


def stop_reason(
    snapshot: ResourceSnapshot,
    *,
    started_at: float,
    now: float,
    baseline_swap: int | None,
    limits: RuntimeLimits = RUNTIME_LIMITS,
) -> str | None:
    if any(
        value is None
        for value in (
            snapshot.available_bytes,
            snapshot.disk_free_bytes,
            snapshot.swap_used_bytes,
            snapshot.rss_bytes,
            snapshot.mps_driver_bytes,
        )
    ):
        return "monitoring_unavailable"
    if snapshot.available_bytes < limits.min_runtime_available_bytes:
        return "available_memory_low"
    if snapshot.disk_free_bytes < limits.min_free_disk_bytes:
        return "disk_free_low"
    if baseline_swap is None or snapshot.swap_used_bytes - baseline_swap > limits.swap_delta_limit_bytes:
        return "swap_delta" if baseline_swap is not None else "monitoring_unavailable"
    if snapshot.rss_bytes > limits.rss_limit_bytes:
        return "rss_limit"
    if snapshot.mps_driver_bytes > limits.mps_driver_limit_bytes:
        return "mps_driver_limit"
    if now - started_at >= limits.max_training_seconds:
        return "max_time"
    return None


def _run_text(command: Sequence[str]) -> str:
    try:
        result = subprocess.run(command, check=False, capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return result.stdout.strip()


def _available_memory_bytes() -> int | None:
    if sys.platform.startswith("linux"):
        try:
            for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) * 1024
        except (OSError, ValueError, IndexError):
            return None
    if sys.platform == "darwin":
        output = _run_text(("vm_stat",))
        page_text = _run_text(("sysctl", "-n", "vm.pagesize"))
        try:
            page_size = int(page_text)
        except ValueError:
            return None
        pages: dict[str, int] = {}
        for line in output.splitlines():
            match = re.match(r"^Pages ([^:]+):\s+(\d+)", line)
            if match:
                pages[match.group(1)] = int(match.group(2))
        return page_size * sum(pages.get(name, 0) for name in ("free", "inactive", "speculative"))
    return None


def _swap_used_bytes() -> int | None:
    if sys.platform.startswith("linux"):
        try:
            values = {}
            for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
                key, value, *_ = line.split()
                if key in {"SwapTotal:", "SwapFree:"}:
                    values[key] = int(value) * 1024
            if "SwapTotal:" in values and "SwapFree:" in values:
                return values["SwapTotal:"] - values["SwapFree:"]
        except (OSError, ValueError, IndexError):
            return None
    if sys.platform == "darwin":
        match = re.search(r"used\s*=\s*([0-9.]+)([KMGTP])", _run_text(("sysctl", "-n", "vm.swapusage")))
        if match:
            return int(float(match.group(1)) * {"K": 1024, "M": 1024**2, "G": 1024**3, "T": 1024**4, "P": 1024**5}[match.group(2)])
    return None


def _rss_bytes(pid: int | None) -> int | None:
    if pid is None:
        return 0
    output = _run_text(("ps", "-o", "rss=", "-p", str(pid)))
    try:
        return int(output.splitlines()[-1].strip()) * 1024
    except (IndexError, ValueError):
        return None


def _mps_driver_bytes() -> int | None:
    try:
        import torch

        if not torch.backends.mps.is_available():
            return 0
        function = getattr(torch.mps, "driver_allocated_memory", None)
        return int(function()) if callable(function) else None
    except (ImportError, RuntimeError, AttributeError):
        return None


def take_resource_snapshot(
    pid: int | None = None,
    disk_path: str | Path = ROOT,
    *,
    mps_driver_override: int | None | object = _MPS_DRIVER_UNSET,
) -> ResourceSnapshot:
    try:
        disk = shutil.disk_usage(_resolve(disk_path)).free
    except OSError:
        disk = None
    return ResourceSnapshot(
        available_bytes=_available_memory_bytes(),
        disk_free_bytes=disk,
        swap_used_bytes=_swap_used_bytes(),
        rss_bytes=_rss_bytes(pid),
        mps_driver_bytes=(
            _mps_driver_bytes()
            if mps_driver_override is _MPS_DRIVER_UNSET
            else mps_driver_override
        ),
    )


def check_process_conflicts(
    processes: Iterable[Mapping[str, Any] | str], *, own_pid: int | None = None
) -> None:
    forbidden = (
        "gradle",
        "gradlew",
        "torchrun",
        "accelerate",
        "mlx",
        "train_lora.py",
        "train_gemma",
        "evaluate_gemma",
        "probe_gemma",
        "run_gemma3_repair_v1.py",
        "run_gemma3_repair_mlp_v2.py",
    )
    for process in processes:
        if isinstance(process, Mapping):
            pid = process.get("pid")
            command = str(process.get("command", ""))
        else:
            pid = None
            command = str(process)
        if own_pid is not None and pid is not None and int(pid) == own_pid:
            continue
        lowered = command.lower()
        if any(token in lowered for token in forbidden):
            executable = Path(command.split(maxsplit=1)[0]).name if command.split() else "unknown"
            label = f"pid={pid} executable={executable}" if pid is not None else f"executable={executable}"
            raise RunnerError(f"conflicting ML/Gradle process is active ({label})")


@contextlib.contextmanager
def exclusive_run_lock(path: str | Path):
    lock_path = _resolve(path)
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+", encoding="utf-8") as stream:
        try:
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RunnerError(f"another ML/Gradle run owns the lock: {lock_path}") from error
        try:
            yield stream
        finally:
            fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def run_with_existing_supervisor(
    *,
    command: Sequence[str],
    output_dir: str | Path,
    log_path: str | Path,
    telemetry_path: str | Path | None = None,
    case_deadline_seconds: float | None = None,
    limits: RuntimeLimits = RUNTIME_LIMITS,
    poll_seconds: float = 1.0,
    terminate_grace_seconds: float = 15.0,
) -> dict[str, Any]:
    """Delegate process-group lifecycle to the reviewed supervisor module."""
    from scripts.supervise_gemma4_parent1956_export import (
        SupervisorLimits,
        run_supervised,
    )

    snapshot = take_resource_snapshot(None, Path(output_dir).parent)
    validate_launch_resources(snapshot, limits)
    bounded = SupervisorLimits(
        max_runtime_seconds=limits.max_training_seconds,
        rss_limit_bytes=limits.rss_limit_bytes,
        rss_persistence_seconds=5.0,
        available_limit_bytes=limits.min_runtime_available_bytes,
        swap_delta_limit_bytes=limits.swap_delta_limit_bytes,
        disk_free_limit_bytes=limits.min_free_disk_bytes,
    )
    telemetry = _resolve(telemetry_path) if telemetry_path is not None else _resolve(output_dir) / "resource_telemetry.json"
    startup_started_at = time.monotonic()

    def probe(pid: int, disk_path: str | Path):
        child = _read_child_resource_telemetry(telemetry, pid)
        if child is None:
            sample = take_resource_snapshot(
                pid,
                disk_path,
                mps_driver_override=None,
            )
            elapsed = time.monotonic() - startup_started_at
            if elapsed >= TELEMETRY_STARTUP_GRACE_SECONDS:
                raise ResourceGateError(
                    "child resource telemetry did not arrive during the bounded startup window "
                    f"(elapsed={elapsed:.3f}s, rss={sample.rss_bytes}, "
                    f"available={sample.available_bytes}, swap={sample.swap_used_bytes})"
                )
            from scripts.supervise_gemma4_parent1956_export import ResourceSample

            return ResourceSample(
                timestamp=time.monotonic(),
                rss_bytes=sample.rss_bytes,
                available_bytes=sample.available_bytes,
                swap_used_bytes=sample.swap_used_bytes,
                disk_free_bytes=sample.disk_free_bytes,
            )
        age = time.monotonic() - float(child["timestamp"])
        if age > RESOURCE_TELEMETRY_MAX_AGE_SECONDS:
            raise ResourceGateError("child resource telemetry is stale")
        case_started = child.get("case_started_at")
        if case_deadline_seconds is not None and case_started is not None:
            if time.monotonic() - float(case_started) > case_deadline_seconds:
                raise ResourceGateError("evaluation case exceeded its hard deadline")
        sample = take_resource_snapshot(
            pid,
            disk_path,
            mps_driver_override=int(child["mps_driver_bytes"]),
        )
        if sample.mps_driver_bytes > limits.mps_driver_limit_bytes:
            raise ResourceGateError("MPS driver memory exceeded 5 GiB")
        from scripts.supervise_gemma4_parent1956_export import ResourceSample

        return ResourceSample(
            timestamp=time.monotonic(),
            rss_bytes=sample.rss_bytes,
            available_bytes=sample.available_bytes,
            swap_used_bytes=sample.swap_used_bytes,
            disk_free_bytes=sample.disk_free_bytes,
        )

    return run_supervised(
        command=command,
        repo_root=ROOT,
        output_dir=output_dir,
        log_path=log_path,
        limits=bounded,
        probe=probe,
        poll_seconds=poll_seconds,
        terminate_grace_seconds=terminate_grace_seconds,
    )


def _read_prepared_manifest(prepared_dir: str | Path) -> dict[str, Any]:
    path = _resolve(prepared_dir) / "prepare" / "manifest.json"
    manifest = _read_json(path)
    if manifest.get("schema_version") != RUNNER_SCHEMA or manifest.get("status") != "prepared":
        raise RunnerError("prepared directory does not contain a sealed runner manifest")
    return manifest


def _local_snapshot_provenance() -> dict[str, Any]:
    """Hash the already-cached base snapshot; never download or instantiate it."""
    cache_root = Path(
        os.environ.get(
            "HF_HUB_CACHE",
            os.environ.get("HUGGINGFACE_HUB_CACHE", Path.home() / ".cache" / "huggingface" / "hub"),
        )
    ).expanduser()
    snapshot = cache_root / "models--google--gemma-3-270m-it" / "snapshots" / MODEL_REVISION
    if not snapshot.is_dir():
        raise RunnerError(f"offline model snapshot is absent: {snapshot}")
    files = sorted(item for item in snapshot.rglob("*") if item.is_file())
    if not files:
        raise RunnerError("offline model snapshot is empty")
    names = {item.name for item in files}
    if "config.json" not in names or not ({"tokenizer.json", "tokenizer.model"} & names):
        raise RunnerError("offline model snapshot lacks config or tokenizer files")
    if not any(item.name.endswith((".safetensors", ".bin")) for item in files):
        raise RunnerError("offline model snapshot lacks base weights")
    file_hashes: dict[str, Any] = {}
    for item in files:
        relative = str(item.relative_to(snapshot))
        file_hashes[relative] = {"sha256": _sha256_file(item), "size": item.stat().st_size}
    return {"path": str(snapshot), "revision": MODEL_REVISION, "files": file_hashes}


def _require_local_snapshot(expected: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """Return the cached snapshot hashes, optionally requiring an exact seal."""
    current = _local_snapshot_provenance()
    if expected is not None and current != dict(expected):
        raise RunnerError("cached base/tokenizer snapshot changed after preflight")
    return current


def _sealed_helper_hashes() -> dict[str, str]:
    helper_paths = {
        "package_init": SRC / "asr_postclean" / "__init__.py",
        "io": SRC / "asr_postclean" / "io.py",
        "manifest": SRC / "asr_postclean" / "manifest.py",
        "metrics": SRC / "asr_postclean" / "metrics.py",
        "edit_weighting": SRC / "asr_postclean" / "edit_weighting.py",
        "prompting": SRC / "asr_postclean" / "prompting.py",
        "tokenization": SRC / "asr_postclean" / "tokenization.py",
        "training": SRC / "asr_postclean" / "training.py",
        "evaluation": SRC / "asr_postclean" / "evaluation.py",
        "gemma3_mlp_adapter": SRC / "asr_postclean" / "gemma3_mlp_adapter.py",
        "supervisor": ROOT / "scripts" / "supervise_gemma4_parent1956_export.py",
    }
    return {name: _sha256_file(path) for name, path in helper_paths.items()}


def _validate_prepared_integrity(manifest: Mapping[str, Any]) -> None:
    config_path = _resolve(manifest["config_path"])
    if _sha256_file(config_path) != manifest.get("config_sha256"):
        raise RunnerError("candidate configuration changed after prepare")
    if manifest.get("helper_sha256") != _sealed_helper_hashes():
        raise RunnerError("historical training/prompt helper changed after prepare")
    if manifest.get("runner_sha256") != _sha256_file(Path(__file__).resolve()):
        raise RunnerError("runner changed after prepare")
    from asr_postclean.prompting import instruction_sha256

    prompt = manifest.get("prompt", {})
    if prompt.get("version") != PROMPT_VERSION or prompt.get("sha256") != instruction_sha256(PROMPT_VERSION):
        raise RunnerError("sealed prompt provenance changed")
    parent = validate_parent_provenance(
        manifest["parent"]["parent_manifest"],
        manifest["parent"]["parent_adapter"],
    )
    for key in ("parent_manifest_sha256", "parent_adapter_sha256", "parent_dataset_revision"):
        if parent.get(key) != manifest["parent"].get(key):
            raise RunnerError(f"parent {key} changed after prepare")
    child = validate_child_dataset(
        manifest["child_dataset"]["path"],
        expected_train=256,
        expected_valid=32,
        train_file="train.jsonl",
        valid_file=Path(manifest["child_dataset"]["valid_path"]).name,
    )
    for key in ("revision", "train_sha256", "valid_sha256", "manifest_sha256"):
        if child.get(key) != manifest["child_dataset"].get(key):
            raise RunnerError(f"child dataset {key} changed after prepare")
    old_validation = manifest.get("old_validation")
    if not isinstance(old_validation, Mapping):
        raise RunnerError("prepared manifest does not seal the old validation split")
    verify_old_validation(old_validation.get("path", ""), old_validation)


def _write_child_resource_telemetry(path: str | Path, **extra: Any) -> None:
    snapshot = take_resource_snapshot(os.getpid(), Path(path).parent)
    payload = {
        "pid": os.getpid(),
        "timestamp": time.monotonic(),
        "rss_bytes": snapshot.rss_bytes,
        "mps_driver_bytes": snapshot.mps_driver_bytes,
        **extra,
    }
    destination = _resolve(path)
    temporary = destination.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(destination)


class ResourceHeartbeat:
    """Keep child-owned resource and case timing fresh during long ML operations."""

    def __init__(self, telemetry_path: str | Path, *, interval_seconds: float = 1.0):
        if interval_seconds <= 0:
            raise ValueError("heartbeat interval must be positive")
        self.telemetry_path = _resolve(telemetry_path)
        self.interval_seconds = interval_seconds
        self._stop_event = threading.Event()
        self._write_lock = threading.Lock()
        self._case_started_at: float | None = None
        self._thread: threading.Thread | None = None
        self._thread_error: BaseException | None = None

    def _write(self) -> None:
        with self._write_lock:
            _write_child_resource_telemetry(
                self.telemetry_path,
                case_started_at=self._case_started_at,
            )

    def _run(self) -> None:
        while not self._stop_event.wait(self.interval_seconds):
            try:
                self._write()
            except BaseException as error:
                self._thread_error = error
                return

    def start(self) -> "ResourceHeartbeat":
        if self._thread is not None:
            raise RunnerError("resource heartbeat can only be started once")
        self._write()
        self._thread = threading.Thread(
            target=self._run,
            name="gemma3-resource-heartbeat",
            daemon=True,
        )
        self._thread.start()
        return self

    def mark_case_started(self) -> None:
        with self._write_lock:
            self._case_started_at = time.monotonic()
            _write_child_resource_telemetry(
                self.telemetry_path,
                case_started_at=self._case_started_at,
            )

    def mark_case_finished(self) -> None:
        with self._write_lock:
            self._case_started_at = None
            _write_child_resource_telemetry(
                self.telemetry_path,
                case_started_at=None,
            )

    def pulse(self) -> None:
        self._write()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=max(1.0, self.interval_seconds * 2))
            if self._thread.is_alive():
                self._thread_error = RunnerError("resource heartbeat thread did not stop")

    def __enter__(self) -> "ResourceHeartbeat":
        return self.start()

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        del exc_type, exc_value, traceback
        self.stop()


def _read_child_resource_telemetry(path: str | Path, pid: int) -> dict[str, Any] | None:
    try:
        value = _read_json(path)
    except RunnerError:
        return None
    if value.get("pid") != pid:
        return None
    if value.get("mps_driver_bytes") is None:
        return None
    return value


def _train_worker(prepared_dir: str | Path) -> int:
    """Worker body, reached only by an explicit supervised train launch."""
    require_execution_reviewed("training")
    prepared = _resolve(prepared_dir)
    manifest = _read_prepared_manifest(prepared)
    parent_baseline = _require_generated_parent_baseline(prepared, manifest)
    _validate_prepared_integrity(manifest)
    enforce_offline_environment()
    preflight = _read_json(prepared / "preflight" / "report.json")
    if preflight.get("prepared_manifest_sha256") != _sha256_file(prepared / "prepare" / "manifest.json"):
        raise RunnerError("preflight is bound to a different prepared manifest")
    sealed_snapshot = preflight.get("model_snapshot")
    if not isinstance(sealed_snapshot, Mapping):
        raise RunnerError("preflight lacks a sealed local model snapshot")
    _require_local_snapshot(sealed_snapshot)
    config = load_candidate_config(manifest["config_path"])
    child = validate_child_dataset(
        manifest["child_dataset"]["path"],
        expected_train=256,
        expected_valid=32,
        train_file=config["child_dataset"]["train_file"],
        valid_file=config["child_dataset"]["valid_file"],
    )
    if child["revision"] != manifest["child_dataset"]["revision"]:
        raise RunnerError("child dataset changed after prepare")
    train_records = _load_jsonl(child["train_path"])
    valid_records = _load_jsonl(child["valid_path"])
    training_dir = prepared / "training"
    if (training_dir / "manifest.json").exists() or (training_dir / "best").exists():
        raise OutputExistsError(f"refusing to overwrite a completed training phase: {training_dir}")
    training_dir.mkdir(parents=True, exist_ok=True)
    telemetry_path = training_dir / "resource_telemetry.json"
    with ResourceHeartbeat(telemetry_path) as heartbeat:
        return _run_training_worker_body(
            manifest=manifest,
            config=config,
            child=child,
            train_records=train_records,
            valid_records=valid_records,
            training_dir=training_dir,
            heartbeat=heartbeat,
            parent_baseline=parent_baseline,
        )


def _run_training_worker_body(
    *,
    manifest: Mapping[str, Any],
    config: Mapping[str, Any],
    child: Mapping[str, Any],
    train_records: list[dict[str, Any]],
    valid_records: list[dict[str, Any]],
    training_dir: Path,
    heartbeat: ResourceHeartbeat,
    parent_baseline: Mapping[str, Any],
) -> int:
    training = _historical_training_module()
    from asr_postclean import gemma3_mlp_adapter
    from transformers import Trainer, TrainerCallback, set_seed

    train_config = build_training_config(config)
    set_seed(train_config.seed)
    model, tokenizer, resolved_revision, device, expansion = gemma3_mlp_adapter.load_expanded_training_model(
        train_config,
        revision=MODEL_REVISION,
        initial_adapter=manifest["parent"]["parent_adapter"],
        device=training.select_device(),
    )
    train_dataset = training.prepare_split(
        train_records, tokenizer, train_config.max_length, prompt_version=PROMPT_VERSION
    )
    valid_dataset = training.prepare_split(
        valid_records, tokenizer, train_config.max_length, prompt_version=PROMPT_VERSION
    )
    arguments = training.build_training_arguments(train_config, training_dir)

    class MPSMemoryCallback(TrainerCallback):
        def __init__(self, path: Path):
            self.path = path

        def on_step_end(self, args, state, control, **kwargs):
            del args, state, control, kwargs
            training.clear_mps_cache()
            heartbeat.pulse()

        def on_epoch_end(self, args, state, control, **kwargs):
            del args, control, kwargs
            training.clear_mps_cache()
            heartbeat.pulse()
            with self.path.open("a", encoding="utf-8", newline="\n") as stream:
                stream.write(json.dumps({"epoch": state.epoch, "global_step": state.global_step, **training.mps_memory_snapshot()}) + "\n")
                stream.flush()
                os.fsync(stream.fileno())

    trainer_kwargs: dict[str, Any] = {
        "model": model,
        "args": arguments,
        "train_dataset": train_dataset,
        "eval_dataset": valid_dataset,
        "data_collator": training.CompletionCollator(tokenizer.pad_token_id, None),
        "callbacks": [MPSMemoryCallback(training_dir / "mps_memory.jsonl")],
    }
    if "processing_class" in __import__("inspect").signature(Trainer.__init__).parameters:
        trainer_kwargs["processing_class"] = tokenizer
    else:
        trainer_kwargs["tokenizer"] = tokenizer
    trainer = Trainer(**trainer_kwargs)
    trainer.train()
    completed_updates = int(trainer.state.global_step)
    try:
        require_complete_updates(completed_updates)
    except IncompleteTrainingError as error:
        (training_dir / "status.json").write_text(
            json.dumps({"status": "incomplete", "completed_updates": completed_updates, "error": str(error)}, indent=2) + "\n",
            encoding="utf-8",
        )
        return 2
    best = training_dir / "best"
    trainer.save_model(best)
    tokenizer.save_pretrained(best)
    effective_adapter_config = gemma3_mlp_adapter.validate_expanded_adapter_config(
        best,
        expected_config=train_config,
    )
    if set(effective_adapter_config.get("target_modules", ())) != set(gemma3_mlp_adapter.ALL_TARGET_MODULES):
        raise RunnerError("saved candidate adapter does not contain all seven LoRA target families")
    from asr_postclean.io import sha256_directory
    from asr_postclean.manifest import build_manifest
    from asr_postclean.prompting import instruction_sha256

    child_revision = child["revision"]
    final_manifest = build_manifest(
        model_id=MODEL_ID,
        model_revision=resolved_revision,
        dataset_revision=child_revision,
        config={
            **dataclasses.asdict(train_config),
            "target_modules": list(gemma3_mlp_adapter.ALL_TARGET_MODULES),
            "device": str(device),
            "prompt_sha256": instruction_sha256(PROMPT_VERSION),
            "expected_updates": 64,
        },
        package_versions=training.package_versions(),
    )
    final_manifest.update(
        {
            "schema_version": RUNNER_SCHEMA,
            "status": "completed",
            "phase": "training",
            "parent": manifest["parent"],
            "child_dataset": child,
            "completed_updates": completed_updates,
            "checkpoint_policy": "one final candidate only after exactly 64 updates",
            "artifacts": {"adapter_dir": "best", "adapter_sha256": sha256_directory(best)},
            "adapter_expansion": {
                **expansion,
                "effective_lora_config": {
                    "r": int(effective_adapter_config["r"]),
                    "lora_alpha": int(effective_adapter_config["lora_alpha"]),
                    "lora_dropout": float(effective_adapter_config["lora_dropout"]),
                    "bias": effective_adapter_config["bias"],
                    "target_modules": sorted(effective_adapter_config["target_modules"]),
                },
                "base_weights_merged": False,
                "adapter_sha256": sha256_directory(best),
            },
            "parent_evaluation": {
                "manifest_sha256": parent_baseline["manifest_sha256"],
                "predictions_sha256": parent_baseline["predictions_sha256"],
            },
        }
    )
    _write_json_once(training_dir / "manifest.json", final_manifest)
    return 0


def run_training(prepared_dir: str | Path, *, execute: bool = False) -> dict[str, Any]:
    prepared = _resolve(prepared_dir)
    manifest = _read_prepared_manifest(prepared)
    parent_baseline = _require_generated_parent_baseline(prepared, manifest)
    _validate_prepared_integrity(manifest)
    training_dir = prepared / "training"
    supervisor_dir = prepared / "supervisor"
    for phase_dir in (training_dir, supervisor_dir):
        if phase_dir.exists() or phase_dir.is_symlink():
            raise OutputExistsError(f"refusing to overwrite training phase: {phase_dir}")
    enforce_offline_environment()
    if not execute:
        return {"status": "dry_run", "phase": "training", "model_loaded": False, "prepared_dir": str(prepared)}
    require_execution_reviewed("training")
    output = prepared / "preflight"
    with exclusive_run_lock(GLOBAL_ML_LOCK):
        check_process_conflicts(_process_list(), own_pid=os.getpid())
        if output.exists() or output.is_symlink():
            raise OutputExistsError(f"refusing to overwrite preflight phase: {output}")
        snapshot = take_resource_snapshot(None, prepared)
        validate_launch_resources(snapshot)
        model_snapshot = _require_local_snapshot(parent_baseline["model_snapshot"])
        output.mkdir(parents=True)
        preflight = {
            "status": "passed",
            "phase": "preflight",
            "model_loaded": False,
            "snapshot": dataclasses.asdict(snapshot),
            "model_snapshot": model_snapshot,
            "limits": dataclasses.asdict(RUNTIME_LIMITS),
            "offline_environment": enforce_offline_environment(),
            "prepared_manifest_sha256": _sha256_file(prepared / "prepare" / "manifest.json"),
            "runner_sha256": manifest["runner_sha256"],
            "parent_evaluation": {
                "manifest_sha256": parent_baseline["manifest_sha256"],
                "predictions_sha256": parent_baseline["predictions_sha256"],
            },
        }
        _write_json_once(output / "report.json", preflight)
        command = [sys.executable, str(Path(__file__).resolve()), "_train-worker", "--prepared-dir", str(prepared)]
        supervisor_dir.mkdir(parents=True)
        try:
            supervisor = run_with_existing_supervisor(
                command=command,
                output_dir=supervisor_dir,
                log_path=supervisor_dir / "supervisor.log",
                telemetry_path=training_dir / "resource_telemetry.json",
            )
            completed = supervisor.get("status") == "completed" and supervisor.get("returncode") == 0
            training_manifest_path = training_dir / "manifest.json"
            if completed and training_manifest_path.is_file():
                try:
                    training_manifest = _read_json(training_manifest_path)
                    adapter = _resolve(training_dir / str(training_manifest.get("artifacts", {}).get("adapter_dir", "best")))
                    completed = (
                        training_manifest.get("status") == "completed"
                        and training_manifest.get("completed_updates") == 64
                        and adapter.is_dir()
                        and _sha256_directory(adapter)
                        == training_manifest.get("artifacts", {}).get("adapter_sha256")
                        and training_manifest.get("parent_evaluation")
                        == {
                            "manifest_sha256": parent_baseline["manifest_sha256"],
                            "predictions_sha256": parent_baseline["predictions_sha256"],
                        }
                    )
                except RunnerError:
                    completed = False
            else:
                completed = False
            status = {
                "status": "completed" if completed else "failed",
                "phase": "training",
                "supervisor": supervisor,
                "completed_updates": 64 if completed else None,
                "adapter_sha256": training_manifest.get("artifacts", {}).get("adapter_sha256")
                if completed
                else None,
            }
            _write_json_once(supervisor_dir / "supervisor_report.json", status)
            return status
        except Exception as error:
            _write_json_once(
                supervisor_dir / "status.json",
                {"phase": "training", "status": "failed", "error_type": type(error).__name__, "error": str(error)},
            )
            raise


def validate_evaluation_records(
    new_records: list[dict[str, Any]],
    old_records: list[dict[str, Any]] | None = None,
    *,
    expected_new: int = 32,
    expected_old: int = 76,
) -> list[dict[str, Any]]:
    old_records = [] if old_records is None else list(old_records)
    if len(new_records) != expected_new or len(old_records) != expected_old:
        raise RunnerError(f"evaluation requires {expected_new} new and {expected_old} old valid records")
    new_ids = {str(row["id"]) for row in new_records}
    old_ids = {str(row["id"]) for row in old_records}
    if new_ids & old_ids:
        raise RunnerError("new development IDs overlap old V3 valid IDs")
    return [*new_records, *old_records]


def require_generation_budget(max_gold_tokens: int, *, max_new_tokens: int = 256) -> int:
    if max_gold_tokens < 0 or max_gold_tokens + 1 > max_new_tokens:
        raise EvaluationBudgetError(
            f"gold target needs {max_gold_tokens + 1} tokens including EOS; max_new_tokens={max_new_tokens}"
        )
    return max_gold_tokens


def validate_evaluation_result(row: Mapping[str, Any], *, expected_id: str) -> dict[str, Any]:
    if row.get("id") != expected_id:
        raise RunnerError("evaluation result ID does not match locked input")
    stop_status = row.get("stop_status")
    if stop_status != "eos" or row.get("eos") is not True:
        return {**dict(row), "status": "rejected_truncation"}
    if not str(row.get("prediction", "")):
        return {**dict(row), "status": "missing_response"}
    return {**dict(row), "status": "complete"}


def _apply_case_deadline(
    row: Mapping[str, Any],
    *,
    elapsed_seconds: float,
    deadline_seconds: float = EVALUATION_CASE_DEADLINE_SECONDS,
) -> dict[str, Any]:
    result = dict(row)
    result["case_elapsed_seconds"] = float(elapsed_seconds)
    if elapsed_seconds > deadline_seconds:
        result["stop_status"] = "timeout"
        result["eos"] = False
    return validate_evaluation_result(result, expected_id=str(result.get("id", "")))


def _validate_generated_predictions(predictions_path: str | Path, expected_ids: Sequence[str]) -> bool:
    path = _resolve(predictions_path)
    if not path.is_file():
        return False
    try:
        rows = load_frozen_predictions(path, expected_ids, _sha256_file(path))
        return len(rows) == len(expected_ids) and all(
            validate_evaluation_result(row, expected_id=expected_id)["status"] == "complete"
            for row, expected_id in zip(rows, expected_ids, strict=True)
        )
    except (RunnerError, OSError):
        return False


def load_frozen_predictions(path: str | Path, expected_ids: Sequence[str], expected_sha256: str) -> list[dict[str, Any]]:
    predictions_path = _resolve(path)
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256 or ""):
        raise RunnerError("frozen predictions require a valid SHA-256")
    if _sha256_file(predictions_path) != expected_sha256:
        raise RunnerError("frozen predictions SHA-256 does not match provenance")
    rows: list[dict[str, Any]] = []
    try:
        for line_number, line in enumerate(predictions_path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError("row is not an object")
            rows.append(value)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        raise RunnerError(f"invalid frozen predictions JSONL: {predictions_path}") from error
    ids = [str(row.get("id", "")) for row in rows]
    if ids != list(expected_ids) or len(set(ids)) != len(ids):
        raise RunnerError("frozen predictions IDs/order differ from locked evaluation records")
    return rows


def _evaluation_records(prepared_manifest: Mapping[str, Any]) -> list[dict[str, Any]]:
    child = validate_child_dataset(
        prepared_manifest["child_dataset"]["path"],
        expected_train=256,
        expected_valid=32,
        train_file="train.jsonl",
        valid_file=Path(prepared_manifest["child_dataset"]["valid_path"]).name,
    )
    if child["revision"] != prepared_manifest["child_dataset"]["revision"]:
        raise RunnerError("child dataset changed after prepare")
    new_records = _load_jsonl(child["valid_path"])
    old_validation = prepared_manifest.get("old_validation")
    if not isinstance(old_validation, Mapping):
        raise RunnerError("prepared manifest does not seal the old validation split")
    old_records = _load_jsonl(verify_old_validation(old_validation.get("path", ""), old_validation)["path"])
    return validate_evaluation_records(new_records, old_records)


def _evaluation_adapter(prepared: Path, manifest: Mapping[str, Any], model_kind: str) -> Path:
    parent = manifest["parent"]
    validate_parent_provenance(parent["parent_manifest"], parent["parent_adapter"])
    if model_kind == "parent":
        return _resolve(parent["parent_adapter"])
    training_manifest_path = prepared / "training" / "manifest.json"
    if not training_manifest_path.is_file():
        raise RunnerError("candidate evaluation requires a completed training manifest")
    training_manifest = _read_json(training_manifest_path)
    if training_manifest.get("status") != "completed" or int(training_manifest.get("completed_updates", -1)) != 64:
        raise RunnerError("candidate evaluation requires exactly 64 completed updates")
    from asr_postclean.gemma3_mlp_adapter import ALL_TARGET_MODULES, validate_expanded_adapter_config

    expected_training = load_candidate_config(manifest["config_path"])["training"]
    effective_training = training_manifest.get("config", {})
    if (
        effective_training.get("target_modules") != list(ALL_TARGET_MODULES)
        or effective_training.get("lora_r") != expected_training.get("lora_r")
        or effective_training.get("lora_alpha") != expected_training.get("lora_alpha")
        or effective_training.get("lora_dropout") != expected_training.get("lora_dropout")
    ):
        raise RunnerError("candidate training manifest differs from the locked seven-target recipe")

    expansion = training_manifest.get("adapter_expansion", {})
    if (
        expansion.get("target_modules") != list(ALL_TARGET_MODULES)
        or expansion.get("inherited_attention_exact_after_load") is not True
        or expansion.get("new_mlp_b_zero") is not True
        or expansion.get("base_weights_merged") is not False
    ):
        raise RunnerError("candidate training manifest lacks exact V3-to-MLP expansion evidence")
    if training_manifest.get("dataset", {}).get("revision") != manifest["child_dataset"]["revision"]:
        raise RunnerError("candidate manifest is bound to a different child corpus")
    parent_baseline = _require_generated_parent_baseline(prepared, manifest)
    if training_manifest.get("parent_evaluation") != {
        "manifest_sha256": parent_baseline["manifest_sha256"],
        "predictions_sha256": parent_baseline["predictions_sha256"],
    }:
        raise RunnerError("candidate manifest is bound to a different generated parent baseline")
    adapter = _resolve(prepared / "training" / str(training_manifest.get("artifacts", {}).get("adapter_dir", "best")))
    if not adapter.is_dir():
        raise RunnerError("candidate adapter directory is absent")
    expected_sha = training_manifest.get("artifacts", {}).get("adapter_sha256")
    if not isinstance(expected_sha, str) or _sha256_directory(adapter) != expected_sha:
        raise RunnerError("candidate adapter SHA-256 does not match its completed manifest")
    try:
        candidate_config = validate_expanded_adapter_config(
            adapter,
            expected_config=expected_training,
        )
    except Exception as error:
        raise RunnerError("candidate adapter does not have the V2 seven-target architecture") from error
    if set(candidate_config.get("target_modules", ())) != set(ALL_TARGET_MODULES):
        raise RunnerError("candidate adapter target modules differ from the V2 architecture")
    return adapter


def _load_local_tokenizer():
    enforce_offline_environment()
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(
        MODEL_ID,
        revision=MODEL_REVISION,
        local_files_only=True,
    )
    if tokenizer.pad_token_id is None:
        if tokenizer.eos_token is None:
            raise RunnerError("local tokenizer has no pad or EOS token")
        tokenizer.pad_token = tokenizer.eos_token
    return tokenizer


def _gold_target_token_count(record: Mapping[str, Any], tokenizer) -> int:
    features = prepare_training_features([dict(record)], tokenizer)
    feature = features[0]
    return len(feature["input_ids"]) - int(feature["prompt_length"]) - 1


def _generate_one(model, tokenizer, record: Mapping[str, Any]) -> dict[str, Any]:
    """Greedy native-EOS generation used only by the reviewed future phase."""
    import torch

    from asr_postclean.evaluation import _generation_end_ids
    from asr_postclean.prompting import build_prompt_text

    prompt = build_prompt_text(
        tokenizer,
        str(record["source"]),
        prompt_version=PROMPT_VERSION,
        protected_terms=record.get("protected_terms"),
    )
    encoded = tokenizer(prompt, return_tensors="pt", add_special_tokens=False)
    device = next(model.parameters()).device
    encoded = {key: value.to(device) for key, value in encoded.items()}
    input_length = int(encoded["input_ids"].shape[-1])
    started = time.monotonic()
    model.eval()
    end_ids = _generation_end_ids(tokenizer)
    with torch.no_grad():
        generated = model.generate(
            **encoded,
            do_sample=False,
            num_beams=1,
            max_new_tokens=256,
            repetition_penalty=1.0,
            eos_token_id=end_ids,
            pad_token_id=getattr(tokenizer, "pad_token_id", None),
            use_cache=True,
        )
    duration = time.monotonic() - started
    new_ids = generated[0, input_length:].tolist()
    eos_ids = end_ids if isinstance(end_ids, (list, tuple)) else [end_ids]
    eos_set = {int(item) for item in eos_ids}
    eos_index = next((index for index, token_id in enumerate(new_ids) if token_id in eos_set), None)
    visible_ids = new_ids if eos_index is None else new_ids[:eos_index]
    prediction = tokenizer.decode(visible_ids, skip_special_tokens=True).strip()
    return {
        "prediction": prediction,
        "stop_status": "eos" if eos_index is not None else "limit",
        "eos": eos_index is not None,
        "duration_seconds": duration,
    }


def _evaluation_worker(
    prepared_dir: str | Path,
    *,
    model_kind: str,
    output_dir: str | Path,
) -> int:
    """Load and evaluate one model entirely inside the externally supervised child."""
    require_execution_reviewed("evaluation")
    prepared = _resolve(prepared_dir)
    output = _resolve(output_dir)
    manifest = _read_prepared_manifest(prepared)
    enforce_offline_environment()
    _validate_prepared_integrity(manifest)
    if model_kind == "candidate":
        _require_generated_parent_baseline(prepared, manifest)
    records = _evaluation_records(manifest)
    if output.exists() and not output.is_dir():
        raise RunnerError("evaluation worker output path is not a directory")
    output.mkdir(parents=True, exist_ok=True)
    predictions_path = output / "predictions.jsonl"
    if predictions_path.exists() or predictions_path.is_symlink():
        raise OutputExistsError(f"refusing to overwrite evaluation predictions: {predictions_path}")
    telemetry_path = output / "resource_telemetry.json"

    with ResourceHeartbeat(telemetry_path) as heartbeat:
        adapter = _evaluation_adapter(prepared, manifest, model_kind)
        training = _historical_training_module()
        train_config = build_training_config(load_candidate_config(manifest["config_path"]))
        preflight_tokenizer = _load_local_tokenizer()
        for record in records:
            require_generation_budget(
                _gold_target_token_count(record, preflight_tokenizer),
                max_new_tokens=256,
            )
        del preflight_tokenizer
        if model_kind == "parent":
            model, tokenizer, _, _ = training.load_model_and_tokenizer(
                train_config,
                revision=MODEL_REVISION,
                initial_adapter=adapter,
            )
        else:
            from asr_postclean import gemma3_mlp_adapter

            model, tokenizer = gemma3_mlp_adapter.load_expanded_candidate_model_and_tokenizer(
                train_config,
                revision=MODEL_REVISION,
                adapter_dir=adapter,
                device=training.select_device(),
            )
        all_complete = True
        rows_written = 0
        with predictions_path.open("x", encoding="utf-8", newline="\n") as stream:
            for record in records:
                case_started_at = time.monotonic()
                row_started_at = stream.tell()
                heartbeat.mark_case_started()
                try:
                    result = dict(_generate_one(model, tokenizer, record))
                    result.update(
                        {
                            "id": record["id"],
                            "source": record["source"],
                            "target": record["target"],
                            "model_kind": model_kind,
                        }
                    )
                    result = _apply_case_deadline(
                        result,
                        elapsed_seconds=time.monotonic() - case_started_at,
                    )
                    encoded_row = json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n"
                    stream.write(encoded_row)
                    stream.flush()
                    os.fsync(stream.fileno())
                    final_case_elapsed = time.monotonic() - case_started_at
                    if final_case_elapsed > EVALUATION_CASE_DEADLINE_SECONDS and result["status"] == "complete":
                        result = _apply_case_deadline(
                            result,
                            elapsed_seconds=final_case_elapsed,
                        )
                        stream.seek(row_started_at)
                        stream.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")
                        stream.truncate()
                        stream.flush()
                        os.fsync(stream.fileno())
                    rows_written += 1
                    if result["status"] != "complete":
                        all_complete = False
                        break
                finally:
                    heartbeat.mark_case_finished()
        return 0 if all_complete and rows_written == len(records) else 2


def run_evaluation(
    prepared_dir: str | Path,
    *,
    model_kind: str,
    output_dir: str | Path,
    predictions: str | Path | None = None,
    prediction_sha256: str | None = None,
    execute: bool = False,
    tokenizer: Any | None = None,
    generator: Callable[[Mapping[str, Any]], Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    """Run one generated evaluation phase under the shared ML lock and supervisor."""
    spec = EvaluationSpec(model_kind=model_kind, output_dir=_resolve(output_dir))
    if spec.output_dir.exists() or spec.output_dir.is_symlink():
        raise OutputExistsError(f"refusing to overwrite evaluation output: {spec.output_dir}")
    prepared = _resolve(prepared_dir)
    manifest = _read_prepared_manifest(prepared)
    records = _evaluation_records(manifest)
    ids = [str(record["id"]) for record in records]
    if predictions is not None:
        if not prediction_sha256:
            raise RunnerError("frozen evaluation predictions require --prediction-sha256")
        rows = load_frozen_predictions(predictions, ids, prediction_sha256)
        validated_rows = [validate_evaluation_result(row, expected_id=row_id) for row, row_id in zip(rows, ids, strict=True)]
        status = "completed" if all(row["status"] == "complete" for row in validated_rows) else "incomplete"
        spec.output_dir.mkdir(parents=True)
        output_predictions = spec.output_dir / "predictions.jsonl"
        _write_jsonl_once(output_predictions, validated_rows)
        _write_json_once(
            spec.output_dir / "manifest.json",
            {
                "schema_version": RUNNER_SCHEMA,
                "phase": "evaluation",
                "status": status,
                "execution_mode": "imported",
                "model_kind": model_kind,
                "model_loaded": False,
                "records_expected": len(records),
                "records_written": len(validated_rows),
                "prediction_source": str(_resolve(predictions)),
                "prediction_source_sha256": prediction_sha256,
                "prediction_sha256": _sha256_file(output_predictions),
                "eos_count": sum(row.get("stop_status") == "eos" for row in rows),
                "truncation_count": sum(row.get("stop_status") != "eos" for row in rows),
                "no_global_score_on_incomplete": True,
            },
        )
        return {
            "status": "imported" if status == "completed" else "incomplete",
            "execution_mode": "imported",
            "model_kind": model_kind,
            "records": len(records),
            "model_loaded": False,
        }
    if not execute:
        return {
            "status": "dry_run",
            "model_kind": model_kind,
            "records": len(records),
            "model_loaded": False,
            "prediction_source": None,
        }
    if tokenizer is not None or generator is not None:
        raise RunnerError("real evaluations must use the externally supervised model worker")
    require_execution_reviewed("evaluation")
    phase_status = "failed"
    supervisor: dict[str, Any] | None = None
    error: BaseException | None = None
    baseline: dict[str, Any] | None = None
    snapshot: dict[str, Any] | None = None
    phase_started = False
    prediction_path = spec.output_dir / "predictions.jsonl"
    phase_manifest_path = spec.output_dir / "manifest.json"

    with exclusive_run_lock(GLOBAL_ML_LOCK):
        check_process_conflicts(_process_list(), own_pid=os.getpid())
        _validate_prepared_integrity(manifest)
        if model_kind == "parent":
            if (prepared / "training").exists():
                raise RunnerError("parent evaluation must complete before training starts")
        else:
            baseline = _require_generated_parent_baseline(prepared, manifest)
            _evaluation_adapter(prepared, manifest, model_kind)
        remaining = _begin_evaluation_phase(
            prepared,
            model_kind,
            total_budget_seconds=float(manifest["evaluation"]["global_timeout_seconds"]),
        )
        phase_started = True
        try:
            spec.output_dir.mkdir(parents=True)
            expected_snapshot = baseline["model_snapshot"] if baseline is not None else None
            snapshot = _require_local_snapshot(expected_snapshot)
            launch_snapshot = take_resource_snapshot(None, prepared)
            validate_launch_resources(launch_snapshot)
            phase_state = _read_evaluation_budget(prepared)
            started_at = float(phase_state["phases"][model_kind]["started_monotonic"])
            phase_elapsed = _monotonic_seconds() - started_at
            poll_seconds = 1.0
            terminate_grace_seconds = 15.0
            cleanup_reserve = poll_seconds + terminate_grace_seconds
            worker_runtime_limit = (
                remaining
                - phase_elapsed
                - cleanup_reserve
                - EVALUATION_FINALIZATION_RESERVE_SECONDS
            )
            if worker_runtime_limit <= 0:
                raise EvaluationBudgetError(
                    "evaluation budget cannot cover worker startup and bounded process cleanup"
                )
            preflight = {
                "status": "passed",
                "phase": f"evaluation_{model_kind}",
                "execution_mode": "supervised_generated",
                "model_loaded": False,
                "prepared_manifest_sha256": _sha256_file(prepared / "prepare" / "manifest.json"),
                "runner_sha256": manifest["runner_sha256"],
                "helper_sha256": manifest["helper_sha256"],
                "model_snapshot": snapshot,
                "launch_snapshot": dataclasses.asdict(launch_snapshot),
                "parent_evaluation": None if baseline is None else {
                    "manifest_sha256": baseline["manifest_sha256"],
                    "predictions_sha256": baseline["predictions_sha256"],
                },
                "global_budget_seconds": remaining,
                "phase_start_monotonic": started_at,
            }
            _write_json_once(spec.output_dir / "preflight.json", preflight)
            supervisor_dir = spec.output_dir / "supervisor"
            supervisor_dir.mkdir()
            command = [
                sys.executable,
                str(Path(__file__).resolve()),
                "_evaluate-worker",
                "--prepared-dir",
                str(prepared),
                "--model",
                model_kind,
                "--output-dir",
                str(spec.output_dir),
            ]
            phase_limits = dataclasses.replace(
                RUNTIME_LIMITS,
                max_training_seconds=worker_runtime_limit,
            )
            supervisor = run_with_existing_supervisor(
                command=command,
                output_dir=supervisor_dir,
                log_path=supervisor_dir / "supervisor.log",
                telemetry_path=spec.output_dir / "resource_telemetry.json",
                case_deadline_seconds=EVALUATION_CASE_DEADLINE_SECONDS,
                limits=phase_limits,
                poll_seconds=poll_seconds,
                terminate_grace_seconds=terminate_grace_seconds,
            )
            worker_ok = supervisor.get("status") == "completed" and supervisor.get("returncode") == 0
            if worker_ok:
                worker_ok = _validate_generated_predictions(prediction_path, ids)
            phase_status = "completed" if worker_ok else "incomplete"
        except KeyboardInterrupt as interrupted:
            error = interrupted
            phase_status = "incomplete"
        except BaseException as failure:
            error = failure
            phase_status = "incomplete"

        raw_rows: list[dict[str, Any]] = []
        malformed_rows = False
        if prediction_path.is_file():
            try:
                for line in prediction_path.read_text(encoding="utf-8").splitlines():
                    if not line.strip():
                        continue
                    row = json.loads(line)
                    if not isinstance(row, dict):
                        raise ValueError("prediction row is not an object")
                    raw_rows.append(row)
            except (OSError, json.JSONDecodeError, ValueError):
                malformed_rows = True
                phase_status = "incomplete"
        eos_count = sum(row.get("stop_status") == "eos" and row.get("eos") is True for row in raw_rows)
        prediction_sha = _sha256_file(prediction_path) if prediction_path.is_file() else None
        phase_elapsed = _monotonic_seconds() - float(
            _read_evaluation_budget(prepared)["phases"][model_kind]["started_monotonic"]
        )
        state_after = None
        summary = {
            "schema_version": RUNNER_SCHEMA,
            "phase": "evaluation",
            "status": phase_status,
            "execution_mode": "supervised_generated",
            "model_kind": model_kind,
            "model_loaded": bool(supervisor and supervisor.get("returncode") == 0 and phase_status == "completed"),
            "records_expected": len(records),
            "records_written": len(raw_rows),
            "eos_count": eos_count,
            "limit_or_timeout_count": max(0, len(raw_rows) - eos_count),
            "global_elapsed_seconds": phase_elapsed,
            "global_budget_seconds": float(manifest["evaluation"]["global_timeout_seconds"]),
            "no_global_score_on_incomplete": True,
            "prediction_sha256": prediction_sha,
            "prepared_manifest_sha256": _sha256_file(prepared / "prepare" / "manifest.json"),
            "child_dataset_revision": manifest["child_dataset"]["revision"],
            "old_validation_sha256": manifest["old_validation"]["sha256"],
            "config_sha256": manifest["config_sha256"],
            "model_snapshot": snapshot,
            "parent_evaluation": None if baseline is None else {
                "manifest_sha256": baseline["manifest_sha256"],
                "predictions_sha256": baseline["predictions_sha256"],
            },
            "case_deadline_seconds": EVALUATION_CASE_DEADLINE_SECONDS,
            "supervisor_poll_seconds": 1.0,
            "terminate_grace_seconds": 15.0,
            "cleanup_reserved_seconds": 16.0,
            "finalization_reserved_seconds": EVALUATION_FINALIZATION_RESERVE_SECONDS,
            "supervisor": supervisor,
            "error_type": type(error).__name__ if error is not None else None,
            "error": str(error) if error is not None else None,
            "malformed_partial_predictions": malformed_rows,
        }
        _write_json_once(phase_manifest_path, summary)
        if phase_started:
            state_after = _finish_evaluation_phase(
                prepared,
                model_kind,
                status=phase_status,
                output_dir=spec.output_dir,
                manifest_path=phase_manifest_path,
                predictions_path=prediction_path,
            )
            finalized_phase = state_after["phases"][model_kind]
            if finalized_phase["status"] != summary["status"]:
                summary["status"] = finalized_phase["status"]
                summary["model_loaded"] = False
                summary["error_type"] = summary["error_type"] or "EvaluationBudgetError"
                summary["error"] = summary["error"] or (
                    "evaluation result and cleanup exceeded the combined parent/candidate budget"
                )
                _replace_json(phase_manifest_path, summary)
                state_after["phases"][model_kind]["manifest_sha256"] = _sha256_file(phase_manifest_path)
                _replace_json(_evaluation_budget_path(prepared), state_after)
        summary["combined_parent_candidate_elapsed_seconds"] = (
            state_after["total_elapsed_seconds"] if state_after is not None else None
        )
        summary["combined_parent_candidate_remaining_seconds"] = (
            max(0.0, summary["global_budget_seconds"] - state_after["total_elapsed_seconds"])
            if state_after is not None
            else None
        )
        if phase_started:
            _replace_json(phase_manifest_path, summary)
            state_after["phases"][model_kind]["manifest_sha256"] = _sha256_file(phase_manifest_path)
            _replace_json(_evaluation_budget_path(prepared), state_after)
        if error is not None and isinstance(error, KeyboardInterrupt):
            raise error
        return summary


def _process_list() -> list[dict[str, Any]]:
    output = _run_text(("ps", "-axo", "pid=,command="))
    rows = []
    for line in output.splitlines():
        match = re.match(r"\s*(\d+)\s+(.*)", line)
        if match:
            rows.append({"pid": int(match.group(1)), "command": match.group(2)})
    return rows


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare = subparsers.add_parser("prepare", help="seal config and child provenance without loading a model")
    prepare.add_argument("--config", type=Path, default=CONFIG_DEFAULT)
    prepare.add_argument("--child-dataset", type=Path, default=CHILD_DATASET_DEFAULT)
    prepare.add_argument("--old-valid", type=Path, default=OLD_VALID_DEFAULT)
    prepare.add_argument("--parent-manifest", type=Path, default=PARENT_MANIFEST_DEFAULT)
    prepare.add_argument("--parent-adapter", type=Path, default=PARENT_ADAPTER_DEFAULT)
    prepare.add_argument("--output-dir", type=Path, required=True)

    train = subparsers.add_parser("train", help="preview or explicitly supervise the one continuation")
    train.add_argument("--prepared-dir", type=Path, required=True)
    train.add_argument("--execute", action="store_true", help="explicitly create the bounded ML child")

    train_worker = subparsers.add_parser("_train-worker", help=argparse.SUPPRESS)
    train_worker.add_argument("--prepared-dir", type=Path, required=True)

    evaluate = subparsers.add_parser("evaluate", help="run one future parent or candidate evaluation")
    evaluate.add_argument("--prepared-dir", type=Path, required=True)
    evaluate.add_argument("--model", choices=("parent", "candidate"), required=True)
    evaluate.add_argument("--output-dir", type=Path, required=True)
    evaluate.add_argument("--predictions", type=Path)
    evaluate.add_argument("--prediction-sha256")
    evaluate.add_argument("--execute", action="store_true", help="explicitly load the locked local model")

    evaluation_worker = subparsers.add_parser("_evaluate-worker", help=argparse.SUPPRESS)
    evaluation_worker.add_argument("--prepared-dir", type=Path, required=True)
    evaluation_worker.add_argument("--model", choices=("parent", "candidate"), required=True)
    evaluation_worker.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.command == "prepare":
        result = prepare_run(
            config_path=args.config,
            child_dataset=args.child_dataset,
            old_valid_path=args.old_valid,
            parent_manifest=args.parent_manifest,
            parent_adapter=args.parent_adapter,
            output_dir=args.output_dir,
        )
        print(json.dumps({"status": result["status"], "output_dir": str(args.output_dir)}, ensure_ascii=False))
        return 0
    if args.command == "train":
        result = run_training(args.prepared_dir, execute=args.execute)
        print(json.dumps(result, ensure_ascii=False, default=str))
        if args.execute and result.get("status") != "completed":
            return 2
        return 0
    if args.command == "_train-worker":
        return _train_worker(args.prepared_dir)
    if args.command == "_evaluate-worker":
        return _evaluation_worker(
            args.prepared_dir,
            model_kind=args.model,
            output_dir=args.output_dir,
        )
    if args.command == "evaluate":
        result = run_evaluation(
            args.prepared_dir,
            model_kind=args.model,
            output_dir=args.output_dir,
            predictions=args.predictions,
            prediction_sha256=args.prediction_sha256,
            execute=args.execute,
        )
        print(json.dumps(result, ensure_ascii=False, default=str))
        if args.execute and result.get("status") != "completed":
            return 2
        if args.predictions is not None and result.get("status") == "incomplete":
            return 2
        return 0
    raise RunnerError("unknown runner command")


if __name__ == "__main__":
    raise SystemExit(main())
