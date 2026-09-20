#!/usr/bin/env python3
"""Bounded Linux launcher helpers for the frozen Gemma 4 Q6 evaluation.

This module deliberately owns process supervision and asset verification only.
It never loads a model, imports MLX, or changes the frozen native client.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable, Mapping, Sequence


GIB = 1024**3
PART_MAX_BYTES = 1_800_000_000
DEFAULT_Q6_BYTES = 3_931_578_880


class Q6RunnerError(RuntimeError):
    """Raised when the remote Q6 execution contract cannot be trusted."""


@dataclass(frozen=True)
class ResourceSnapshot:
    available_memory_bytes: int | None
    disk_free_bytes: int | None
    swap_used_bytes: int | None


@dataclass(frozen=True)
class RuntimeLimits:
    preflight_memory_bytes: int = 8 * GIB
    preflight_disk_bytes: int = 7 * GIB
    max_rss_bytes: int = 8 * GIB
    min_available_memory_bytes: int = 2 * GIB
    max_swap_growth_bytes: int = 1 * GIB
    max_runtime_seconds: float = 45 * 60
    rss_observation_seconds: float = 15.0


DEFAULT_LIMITS = RuntimeLimits()


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_directory(path: str | Path) -> str:
    directory = Path(path)
    if directory.is_symlink() or not directory.is_dir():
        raise Q6RunnerError(f"directory is absent or symlinked: {directory}")
    files = sorted(
        directory.rglob("*"),
        key=lambda candidate: candidate.relative_to(directory).as_posix(),
    )
    digest = hashlib.sha256()
    regular_count = 0
    for candidate in files:
        if candidate.is_symlink():
            raise Q6RunnerError(f"directory contains a symlink: {candidate}")
        if not candidate.is_file():
            continue
        regular_count += 1
        digest.update(candidate.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with candidate.open("rb") as stream:
            for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    if regular_count == 0:
        raise Q6RunnerError(f"directory is empty: {directory}")
    return digest.hexdigest()


def require_new_path(path: str | Path, label: str) -> Path:
    candidate = Path(path).expanduser()
    if candidate.exists() or candidate.is_symlink():
        raise Q6RunnerError(f"refusing to overwrite {label}: {candidate}")
    return candidate


def verify_file_sha(path: str | Path, expected: str, label: str) -> dict[str, Any]:
    candidate = Path(path).expanduser()
    if candidate.is_symlink() or not candidate.is_file():
        raise Q6RunnerError(f"{label} is absent or symlinked: {candidate}")
    actual = sha256_file(candidate)
    if actual != expected.lower():
        raise Q6RunnerError(f"{label} SHA-256 mismatch: expected {expected}, got {actual}")
    return {"path": str(candidate.resolve()), "bytes": candidate.stat().st_size, "sha256": actual}


def verify_evaluation_inputs(
    *,
    client: str | Path,
    client_sha256: str,
    selection: str | Path,
    selection_sha256: str,
    records: str | Path,
    records_sha256: str,
    prompt_v6: str | Path,
    prompt_v6_sha256: str,
    prompt_v6_source_last: str | Path,
    prompt_v6_source_last_sha256: str,
    tokenizer: str | Path,
    tokenizer_sha256: str,
    model: str | Path,
    model_sha256: str,
) -> dict[str, Any]:
    """Verify all immutable inputs before a fresh resource measurement or spawn."""

    artifacts = {
        "client": verify_file_sha(client, client_sha256, "native client"),
        "selection": verify_file_sha(selection, selection_sha256, "selection"),
        "records": verify_file_sha(records, records_sha256, "valid records"),
        "prompt_v6": verify_file_sha(prompt_v6, prompt_v6_sha256, "V6 prompt helper"),
        "prompt_v6_source_last": verify_file_sha(
            prompt_v6_source_last,
            prompt_v6_source_last_sha256,
            "source-last prompt helper",
        ),
        "model": verify_file_sha(model, model_sha256, "assembled GGUF"),
    }
    tokenizer_path = Path(tokenizer).expanduser()
    if tokenizer_path.is_symlink() or not tokenizer_path.is_dir():
        raise Q6RunnerError(f"tokenizer is absent or symlinked: {tokenizer_path}")
    actual_tokenizer = sha256_directory(tokenizer_path)
    if actual_tokenizer != tokenizer_sha256.lower():
        raise Q6RunnerError(
            f"tokenizer SHA-256 mismatch: expected {tokenizer_sha256}, got {actual_tokenizer}"
        )
    artifacts["tokenizer"] = {
        "path": str(tokenizer_path.resolve()),
        "sha256": actual_tokenizer,
    }
    return artifacts


def _validate_part_name(name: str, index: int) -> None:
    expected = f"model.part-{index:03d}"
    if name != expected:
        raise Q6RunnerError(f"part {index} has unexpected name: {name!r}, expected {expected!r}")


def make_parts_manifest(
    paths: Sequence[str | Path],
    *,
    source_sha256: str | None,
    source_bytes: int | None = None,
) -> dict[str, Any]:
    if not paths:
        raise Q6RunnerError("at least one model part is required")
    entries: list[dict[str, Any]] = []
    total = 0
    for index, raw_path in enumerate(paths):
        path = Path(raw_path)
        if path.is_symlink() or not path.is_file():
            raise Q6RunnerError(f"model part is absent or symlinked: {path}")
        _validate_part_name(path.name, index)
        size = path.stat().st_size
        if size <= 0 or size > PART_MAX_BYTES:
            raise Q6RunnerError(f"model part has invalid size: {path} ({size})")
        total += size
        entries.append(
            {
                "index": index,
                "name": path.name,
                "bytes": size,
                "sha256": sha256_file(path),
            }
        )
    if source_bytes is not None and total != source_bytes:
        raise Q6RunnerError(f"model part total differs: expected {source_bytes}, got {total}")
    return {
        "schema_version": 1,
        "part_max_bytes": PART_MAX_BYTES,
        "total_bytes": total,
        "source_sha256": source_sha256,
        "parts": entries,
    }


def verify_parts_manifest(paths: Sequence[str | Path], manifest: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(manifest, Mapping) or not isinstance(manifest.get("parts"), list):
        raise Q6RunnerError("invalid model parts manifest")
    expected = manifest["parts"]
    if len(paths) != len(expected):
        raise Q6RunnerError("model part count differs from manifest")
    total = 0
    for index, raw_path in enumerate(paths):
        path = Path(raw_path)
        entry = expected[index]
        if not isinstance(entry, Mapping):
            raise Q6RunnerError("model part manifest entry is not an object")
        _validate_part_name(path.name, index)
        if entry.get("index") != index or entry.get("name") != path.name:
            raise Q6RunnerError(f"model part order differs at index {index}")
        checked = verify_file_sha(path, str(entry.get("sha256", "")), f"model part {index}")
        if checked["bytes"] != entry.get("bytes"):
            raise Q6RunnerError(f"model part byte count differs at index {index}")
        if checked["bytes"] > PART_MAX_BYTES:
            raise Q6RunnerError(f"model part exceeds maximum at index {index}")
        total += checked["bytes"]
    if total != manifest.get("total_bytes"):
        raise Q6RunnerError("model part total differs from manifest")
    return {"parts": len(paths), "total_bytes": total, "source_sha256": manifest.get("source_sha256")}


def _safe_member_name(name: str) -> str:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or not name or name.endswith("/"):
        raise Q6RunnerError(f"archive member path is unsafe: {name!r}")
    if str(path) != name or "\\" in name:
        raise Q6RunnerError(f"archive member path is unsafe: {name!r}")
    return name


def extract_bounded_archive(
    archive: str | Path,
    destination: str | Path,
    *,
    expected_names: Sequence[str],
    max_total_bytes: int = 128 * 1024 * 1024,
) -> dict[str, Any]:
    """Extract an exact, regular-file-only ZIP into a new directory."""

    archive_path = Path(archive)
    if archive_path.is_symlink() or not archive_path.is_file():
        raise Q6RunnerError(f"archive is absent or symlinked: {archive_path}")
    destination_path = require_new_path(destination, "archive destination")
    expected = tuple(expected_names)
    if len(set(expected)) != len(expected):
        raise Q6RunnerError("archive expected names contain duplicates")
    with zipfile.ZipFile(archive_path) as source:
        infos = source.infolist()
        names = tuple(_safe_member_name(info.filename) for info in infos)
        if names != expected or set(names) != set(expected):
            raise Q6RunnerError(f"archive members differ: expected {expected}, got {names}")
        total = sum(info.file_size for info in infos)
        if total > max_total_bytes:
            raise Q6RunnerError("archive expands beyond the bounded size")
        for info in infos:
            mode = (info.external_attr >> 16) & 0o170000
            if mode == 0o120000 or info.is_dir():
                raise Q6RunnerError(f"archive member is not a regular file: {info.filename}")
        destination_path.mkdir(parents=True)
        for info in infos:
            target = destination_path / info.filename
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(info) as input_stream, target.open("xb") as output:
                shutil.copyfileobj(input_stream, output, length=1024 * 1024)
                output.flush()
                os.fsync(output.fileno())
    return {"path": str(destination_path.resolve()), "files": list(expected), "bytes": total}


def _read_proc_value(pid: int, key: str) -> int | None:
    try:
        text = Path(f"/proc/{pid}/status").read_text(encoding="utf-8")
    except (FileNotFoundError, OSError):
        return None
    for line in text.splitlines():
        if line.startswith(key + ":"):
            fields = line.split()
            if len(fields) >= 2:
                try:
                    return int(fields[1]) * 1024
                except ValueError:
                    return None
    return None


def _read_meminfo_value(key: str) -> int | None:
    try:
        text = Path("/proc/meminfo").read_text(encoding="ascii")
    except (FileNotFoundError, OSError):
        return None
    for line in text.splitlines():
        if line.startswith(key + ":"):
            fields = line.split()
            if len(fields) < 2:
                return None
            try:
                value = int(fields[1])
            except ValueError:
                return None
            return value * 1024 if len(fields) >= 3 and fields[2] == "kB" else value
    return None


def take_resource_snapshot(disk_path: str | Path = ".") -> ResourceSnapshot:
    swap_total = _read_meminfo_value("SwapTotal")
    swap_free = _read_meminfo_value("SwapFree")
    swap_used = None if swap_total is None or swap_free is None else swap_total - swap_free
    return ResourceSnapshot(
        available_memory_bytes=_read_meminfo_value("MemAvailable"),
        disk_free_bytes=shutil.disk_usage(disk_path).free,
        swap_used_bytes=swap_used,
    )


def validate_preflight(snapshot: ResourceSnapshot, limits: RuntimeLimits = DEFAULT_LIMITS) -> None:
    if snapshot.available_memory_bytes is None or snapshot.available_memory_bytes < limits.preflight_memory_bytes:
        raise Q6RunnerError("preflight refused: available RAM is below 8 GiB or unknown")
    if snapshot.disk_free_bytes is None or snapshot.disk_free_bytes < limits.preflight_disk_bytes:
        raise Q6RunnerError("preflight refused: free disk is below 7 GiB or unknown")


def validate_runtime_snapshot(
    snapshot: ResourceSnapshot,
    *,
    baseline_swap_used_bytes: int | None,
    rss_bytes: int | None,
    limits: RuntimeLimits = DEFAULT_LIMITS,
) -> None:
    if snapshot.available_memory_bytes is None or snapshot.available_memory_bytes < limits.min_available_memory_bytes:
        raise Q6RunnerError("runtime memory guard exceeded: available RAM below 2 GiB or unknown")
    if rss_bytes is None:
        raise Q6RunnerError("runtime RSS guard unavailable")
    if baseline_swap_used_bytes is None or snapshot.swap_used_bytes is None:
        raise Q6RunnerError("runtime swap guard unavailable")
    if snapshot.swap_used_bytes - baseline_swap_used_bytes > limits.max_swap_growth_bytes:
        raise Q6RunnerError("runtime swap guard exceeded: growth above 1 GiB")


def update_rss_guard(
    rss_bytes: int | None,
    now: float,
    over_limit_since: float | None,
    limits: RuntimeLimits = DEFAULT_LIMITS,
) -> tuple[float | None, bool]:
    """Track a continuous RSS breach and stop only after its grace interval."""

    if rss_bytes is None:
        raise Q6RunnerError("runtime RSS guard unavailable")
    if rss_bytes <= limits.max_rss_bytes:
        return None, False
    if over_limit_since is None:
        over_limit_since = now
    return over_limit_since, now - over_limit_since >= limits.rss_observation_seconds


def wait_for_readiness(
    process: Any,
    ready: Callable[[], bool],
    *,
    timeout_seconds: float = 60.0,
    poll_seconds: float = 0.25,
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    observe: Callable[[], None] | None = None,
) -> None:
    deadline = clock() + timeout_seconds
    while clock() < deadline:
        if process.poll() is not None:
            raise Q6RunnerError("server exited before readiness")
        if observe is not None:
            observe()
        try:
            if ready():
                return
        except (OSError, urllib.error.URLError):
            pass
        sleep(max(0.0, poll_seconds))
    raise Q6RunnerError("server readiness timeout")


def http_readiness(base_url: str) -> Callable[[], bool]:
    if not base_url.startswith("http://127.0.0.1:"):
        raise Q6RunnerError("readiness URL must target loopback HTTP")

    def check() -> bool:
        with urllib.request.urlopen(base_url.rstrip("/") + "/health", timeout=2.0) as response:
            return response.status == 200

    return check


def terminate_owned_process(process: Any, *, grace_seconds: float = 15.0) -> dict[str, Any]:
    if process is None or process.poll() is not None:
        return {"status": "already_stopped"}
    pid = getattr(process, "pid", None)
    signal_sent = "SIGTERM"
    try:
        if isinstance(pid, int):
            os.killpg(pid, signal.SIGTERM)
        else:
            process.terminate()
    except (OSError, ProcessLookupError):
        process.terminate()
    try:
        process.wait(timeout=grace_seconds)
    except (subprocess.TimeoutExpired, TimeoutError):
        signal_sent = "SIGKILL"
        try:
            if isinstance(pid, int):
                os.killpg(pid, signal.SIGKILL)
            else:
                process.kill()
        except (OSError, ProcessLookupError):
            process.kill()
        process.wait(timeout=max(1.0, grace_seconds))
    return {"status": "stopped", "pid": pid, "signal": signal_sent}


def summarize_child_results(server: Any, client: Any, *, timed_out: bool) -> dict[str, Any]:
    server_code = server.poll() if server is not None else None
    client_code = client.poll() if client is not None else None
    if timed_out:
        status = "timeout"
    elif client_code == 0 and server_code in (0, -signal.SIGTERM, None):
        status = "completed"
    else:
        status = "failed"
    return {
        "status": status,
        "server_returncode": server_code,
        "client_returncode": client_code,
    }


def build_server_command(server: str | Path, model: str | Path, *, port: int) -> list[str]:
    return [
        str(server),
        "--model", str(model),
        "--host", "127.0.0.1",
        "--port", str(port),
        "--threads", "2",
        "--threads-batch", "2",
        "--ctx-size", "4096",
        "--batch-size", "128",
        "--ubatch-size", "128",
        "--parallel", "1",
        "--n-gpu-layers", "0",
        "--device", "none",
        "--no-warmup",
        "--offline",
    ]


def build_client_command(
    python: str | Path,
    client: str | Path,
    *,
    output: str | Path,
    selection: str | Path,
    records: str | Path,
    tokenizer: str | Path,
    tokenizer_sha256: str,
    gguf: str | Path,
    gguf_sha256: str,
    prompt_v6_sha256: str,
    prompt_v6_source_last_sha256: str,
    base_url: str,
) -> list[str]:
    return [
        str(python), str(client),
        "--output", str(output),
        "--selection", str(selection),
        "--records", str(records),
        "--tokenizer", str(tokenizer),
        "--tokenizer-sha256", tokenizer_sha256,
        "--gguf", str(gguf),
        "--gguf-sha256", gguf_sha256,
        "--prompt-v6-sha256", prompt_v6_sha256,
        "--prompt-v6-source-last-sha256", prompt_v6_source_last_sha256,
        "--base-url", base_url,
        "--expected-model-path", str(gguf),
        "--diagnostic-only",
    ]


def _write_once(path: Path, payload: Mapping[str, Any]) -> None:
    if path.exists() or path.is_symlink():
        raise Q6RunnerError(f"refusing to overwrite report: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def _default_spawn(command: Sequence[str], log_path: Path) -> subprocess.Popen[Any]:
    log = log_path.open("x", encoding="utf-8", newline="")
    try:
        process = subprocess.Popen(
            list(command),
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            close_fds=True,
            text=True,
        )
    except BaseException:
        log.close()
        raise
    process._q6_log_handle = log  # type: ignore[attr-defined]
    return process


def _close_process_log(process: Any) -> None:
    handle = getattr(process, "_q6_log_handle", None)
    if handle is not None:
        handle.close()


def run_server_client(
    *,
    server_command: Sequence[str],
    client_command: Sequence[str],
    output_dir: str | Path,
    report_dir: str | Path,
    readiness: Callable[[], bool],
    disk_path: str | Path = ".",
    limits: RuntimeLimits = DEFAULT_LIMITS,
    spawn: Callable[[Sequence[str], Path], Any] = _default_spawn,
    snapshot: Callable[[str | Path], ResourceSnapshot] = take_resource_snapshot,
    rss_reader: Callable[[int | None], int | None] = lambda pid: (
        None if pid is None else _read_proc_value(pid, "VmRSS")
    ),
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    readiness_timeout_seconds: float = 120.0,
    input_artifacts: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Run only two owned children and leave a terminal report on every path."""

    report_path = require_new_path(report_dir, "evaluation report directory")
    report_path.mkdir(parents=True)
    server_log = report_path / "server.log"
    client_log = report_path / "client.log"
    server: Any = None
    client: Any = None
    terminal: dict[str, Any] = {"status": "failed"}
    timed_out = False
    cleanup: list[dict[str, Any]] = []
    started = clock()
    initial: ResourceSnapshot | None = None
    baseline_swap: int | None = None
    server_started: float | None = None
    resource_stats: dict[str, Any] = {
        "max_rss_bytes": 0,
        "min_available_memory_bytes": None,
        "swap_used_start_bytes": None,
        "swap_used_end_bytes": None,
        "rss_observed_seconds": 0.0,
        "rss_over_limit_seconds": 0.0,
    }
    rss_over_limit_since: float | None = None
    try:
        require_new_path(output_dir, "evaluation output directory")
        initial = snapshot(disk_path)
        validate_preflight(initial, limits)
        baseline_swap = initial.swap_used_bytes
        resource_stats["swap_used_start_bytes"] = baseline_swap
        _write_once(
            report_path / "preflight.json",
            {
                "resource": asdict(initial),
                "limits": asdict(limits),
                "inputs": dict(input_artifacts or {}),
            },
        )

        def observe_runtime() -> None:
            nonlocal rss_over_limit_since
            if server is None:
                raise Q6RunnerError("runtime RSS guard unavailable")
            current = snapshot(disk_path)
            rss = rss_reader(getattr(server, "pid", None))
            validate_runtime_snapshot(
                current,
                baseline_swap_used_bytes=baseline_swap,
                rss_bytes=rss,
                limits=limits,
            )
            now = clock()
            rss_over_limit_since, rss_exceeded = update_rss_guard(
                rss,
                now,
                rss_over_limit_since,
                limits,
            )
            resource_stats["rss_over_limit_seconds"] = (
                0.0
                if rss_over_limit_since is None
                else max(0.0, now - rss_over_limit_since)
            )
            if rss_exceeded:
                raise Q6RunnerError(
                    "runtime RSS guard exceeded: server above 8 GiB for 15 seconds"
                )
            resource_stats["max_rss_bytes"] = max(resource_stats["max_rss_bytes"], rss or 0)
            if current.available_memory_bytes is not None:
                previous = resource_stats["min_available_memory_bytes"]
                resource_stats["min_available_memory_bytes"] = (
                    current.available_memory_bytes
                    if previous is None
                    else min(previous, current.available_memory_bytes)
                )
            resource_stats["swap_used_end_bytes"] = current.swap_used_bytes
            resource_stats["rss_observed_seconds"] = max(
                0.0,
                now - (server_started if server_started is not None else started),
            )

        server = spawn(server_command, server_log)
        server_started = clock()
        wait_for_readiness(
            server,
            readiness,
            timeout_seconds=readiness_timeout_seconds,
            clock=clock,
            sleep=sleep,
            observe=observe_runtime,
        )
        client = spawn(client_command, client_log)
        while client.poll() is None:
            elapsed = clock() - started
            if elapsed > limits.max_runtime_seconds:
                timed_out = True
                raise Q6RunnerError("server/client runtime exceeded 45 minutes")
            if server.poll() is not None:
                raise Q6RunnerError("server exited while client was running")
            observe_runtime()
            sleep(0.25)
        client.wait(timeout=5.0)
        if client.returncode != 0:
            raise Q6RunnerError(f"client exited with status {client.returncode}")
        terminal = summarize_child_results(server, client, timed_out=False)
    except BaseException as error:
        terminal = {
            "status": "timeout" if timed_out else "failed",
            "error_type": type(error).__name__,
            "error": str(error),
            "server_returncode": server.poll() if server is not None else None,
            "client_returncode": client.poll() if client is not None else None,
        }
    finally:
        cleanup_errors: list[dict[str, Any]] = []
        for label, process in (("client", client), ("server", server)):
            if process is None:
                continue
            try:
                result = terminate_owned_process(process)
            except BaseException as cleanup_error:
                cleanup.append(
                    {
                        "child": label,
                        "status": "cleanup_failed",
                        "error_type": type(cleanup_error).__name__,
                        "error": str(cleanup_error),
                    }
                )
                cleanup_errors.append(cleanup[-1])
            else:
                if result.get("status") != "already_stopped":
                    cleanup.append({"child": label, **result})
        if client is not None:
            _close_process_log(client)
        if server is not None:
            _close_process_log(server)
        if cleanup_errors:
            terminal["status"] = "failed"
            terminal["cleanup_errors"] = cleanup_errors
    terminal["cleanup"] = cleanup
    terminal["resources"] = resource_stats
    terminal["elapsed_seconds"] = max(0.0, clock() - started)
    _write_once(report_path / "supervisor_report.json", terminal)
    return terminal


def main(argv: Sequence[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--client", required=True)
    parser.add_argument("--python", default=sys.executable)
    parser.add_argument("--output", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--selection", required=True)
    parser.add_argument("--records", required=True)
    parser.add_argument("--tokenizer", required=True)
    parser.add_argument("--tokenizer-sha256", required=True)
    parser.add_argument("--client-sha256", required=True)
    parser.add_argument("--selection-sha256", required=True)
    parser.add_argument("--records-sha256", required=True)
    parser.add_argument("--prompt-v6", required=True)
    parser.add_argument("--prompt-v6-source-last", required=True)
    parser.add_argument("--gguf-sha256", required=True)
    parser.add_argument("--prompt-v6-sha256", required=True)
    parser.add_argument("--prompt-v6-source-last-sha256", required=True)
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args(argv)
    try:
        input_artifacts = verify_evaluation_inputs(
            client=args.client,
            client_sha256=args.client_sha256,
            selection=args.selection,
            selection_sha256=args.selection_sha256,
            records=args.records,
            records_sha256=args.records_sha256,
            prompt_v6=args.prompt_v6,
            prompt_v6_sha256=args.prompt_v6_sha256,
            prompt_v6_source_last=args.prompt_v6_source_last,
            prompt_v6_source_last_sha256=args.prompt_v6_source_last_sha256,
            tokenizer=args.tokenizer,
            tokenizer_sha256=args.tokenizer_sha256,
            model=args.model,
            model_sha256=args.gguf_sha256,
        )
    except (Q6RunnerError, OSError, ValueError) as error:
        report_path = require_new_path(args.report, "evaluation report directory")
        report_path.mkdir(parents=True)
        _write_once(
            report_path / "supervisor_report.json",
            {"status": "failed", "error_type": type(error).__name__, "error": str(error)},
        )
        print(json.dumps({"status": "failed", "error": str(error)}, ensure_ascii=False))
        return 2
    server_command = build_server_command(args.server, args.model, port=args.port)
    client_command = build_client_command(
        args.python,
        args.client,
        output=args.output,
        selection=args.selection,
        records=args.records,
        tokenizer=args.tokenizer,
        tokenizer_sha256=args.tokenizer_sha256,
        gguf=args.model,
        gguf_sha256=args.gguf_sha256,
        prompt_v6_sha256=args.prompt_v6_sha256,
        prompt_v6_source_last_sha256=args.prompt_v6_source_last_sha256,
        base_url=f"http://127.0.0.1:{args.port}",
    )
    result = run_server_client(
        server_command=server_command,
        client_command=client_command,
        output_dir=args.output,
        report_dir=args.report,
        readiness=http_readiness(f"http://127.0.0.1:{args.port}"),
        input_artifacts=input_artifacts,
    )
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result.get("status") == "completed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
