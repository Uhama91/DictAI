#!/usr/bin/env python3
"""Build and verify the allowlisted, portable Gemma 3 Repair V1 payload."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import stat
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from typing import Mapping


PAYLOAD_ROOT_NAME = "gemma3-repair-v1"
PAYLOAD_MANIFEST_NAME = "payload_manifest.json"
PAYLOAD_ID = "gemma3-repair-v1"
TOOL_ROOT = Path(__file__).resolve().parent
RELEASE_LOCK_PATH = TOOL_ROOT / "ci_config.json"
TRANSPORT_DECLARATION = {
    "destination": "GitHub draft release asset",
    "status": "authorized_by_plan_not_yet_uploaded",
    "public_release": False,
    "authorization_reference": "docs/superpowers/plans/2026-09-23-gemma3-pilot-execution.md",
}
MODEL_ID = "google/gemma-3-270m-it"
MODEL_REVISION = "ac82b4e820549b854eebf28ce6dedaf9fdfa17b3"
MODEL_SNAPSHOT_RELATIVE = "models--google--gemma-3-270m-it/snapshots/" + MODEL_REVISION
MODEL_SNAPSHOT_FILES = (
    "added_tokens.json",
    "chat_template.jinja",
    "config.json",
    "generation_config.json",
    "model.safetensors",
    "special_tokens_map.json",
    "tokenizer.json",
    "tokenizer.model",
    "tokenizer_config.json",
)
PARENT_ADAPTER_FILES = (
    "README.md",
    "adapter_config.json",
    "adapter_model.safetensors",
    "chat_template.jinja",
    "tokenizer.json",
    "tokenizer_config.json",
    "training_args.bin",
)
SOURCE_TO_PAYLOAD = {
    "configs/gemma3_repair_v1.json": "configs/gemma3_repair_v1.json",
    "data_gemma3_repair_v1/manifest.json": "data_gemma3_repair_v1/manifest.json",
    "data_gemma3_repair_v1/manifest.sha256": "data_gemma3_repair_v1/manifest.sha256",
    "data_gemma3_repair_v1/provenance.json": "data_gemma3_repair_v1/provenance.json",
    "data_gemma3_repair_v1/train.jsonl": "data_gemma3_repair_v1/train.jsonl",
    "data_gemma3_repair_v1/valid.jsonl": "data_gemma3_repair_v1/valid.jsonl",
    "data_v3/valid.jsonl": "data_v3/valid.jsonl",
    "runs/gemma-3-270m-it-lora-v3/manifest.json": "parent/manifest.json",
    "scripts/run_gemma3_repair_v1.py": "scripts/run_gemma3_repair_v1.py",
    "scripts/supervise_gemma4_parent1956_export.py": "scripts/supervise_gemma4_parent1956_export.py",
    "src/asr_postclean/__init__.py": "src/asr_postclean/__init__.py",
    "src/asr_postclean/edit_weighting.py": "src/asr_postclean/edit_weighting.py",
    "src/asr_postclean/evaluation.py": "src/asr_postclean/evaluation.py",
    "src/asr_postclean/io.py": "src/asr_postclean/io.py",
    "src/asr_postclean/manifest.py": "src/asr_postclean/manifest.py",
    "src/asr_postclean/metrics.py": "src/asr_postclean/metrics.py",
    "src/asr_postclean/prompting.py": "src/asr_postclean/prompting.py",
    "src/asr_postclean/tokenization.py": "src/asr_postclean/tokenization.py",
    "src/asr_postclean/training.py": "src/asr_postclean/training.py",
}
for filename in PARENT_ADAPTER_FILES:
    SOURCE_TO_PAYLOAD[
        f"runs/gemma-3-270m-it-lora-v3/best/{filename}"
    ] = f"parent/best/{filename}"
TRAINING_REPO_PATHS = tuple(sorted(SOURCE_TO_PAYLOAD))
LEGAL_PATHS = (
    "legal/Gemma-Terms-of-Use.html",
    "legal/NOTICE.txt",
    "legal/SOURCE.txt",
)
BASE_PATHS = tuple(
    f"base/hub/{MODEL_SNAPSHOT_RELATIVE}/{filename}"
    for filename in MODEL_SNAPSHOT_FILES
)
PAYLOAD_CONTENT_PATHS = tuple(
    sorted((*SOURCE_TO_PAYLOAD.values(), *LEGAL_PATHS, *BASE_PATHS))
)
MAX_ARCHIVE_BYTES = 2_000_000_000
MAX_EXPANDED_BYTES = 1_500_000_000
MAX_MANIFEST_BYTES = 1_048_576
CHUNK_BYTES = 1024 * 1024
MAX_TAR_OVERHEAD_BYTES = 1_048_576
ROOT = Path(__file__).resolve().parents[2]

EXPECTED_SOURCE_SHA256 = {
    "configs/gemma3_repair_v1.json": "791d08c4e9efacfeff522d5afc76add4ca31bb9c8f383b503a83c63ad4929364",
    "data_gemma3_repair_v1/manifest.json": "49737105494d7c4fa45aa65f55efac6a245ebdfcc593decbbb59aa7559bc9b90",
    "data_gemma3_repair_v1/manifest.sha256": "65cda33be59b7aa099d5989dd8ac184472cb9c0db0b7988235667d0bb58b6797",
    "data_gemma3_repair_v1/provenance.json": "5cf7a3c25e156318851e36cf474c2d56d166f9d2a58763479e371031265a3eee",
    "data_gemma3_repair_v1/train.jsonl": "0fcdfb8ba6174748e7a5a14a0e8bcd5314b03eaaff1cbfaaf79898da2550fbc0",
    "data_gemma3_repair_v1/valid.jsonl": "8e96dd879588f92224c68719ddaecdfad0a8108bf9328ca7bf8bdac27989faf1",
    "data_v3/valid.jsonl": "e8d55886f5f04c1a564fef0814b95f003d3f7c077d86053f85b3385a134f47d1",
    "parent/manifest.json": "632eb66fd1688f9e68f843917031f1cbea286e25845fb580f2ed19771cd9fc1d",
    "legal/Gemma-Terms-of-Use.html": "4973a253f308763e6b21da4242f8bf5220aed9c283d0276ff92ff11958a9555e",
    "legal/NOTICE.txt": "304ad4def675e60a8516871cc471468a93410f6dd9c8664f60269e4e02b7d4e3",
    "legal/SOURCE.txt": "ef3ffbac63610be150ca9ae4462a366982c0410ca70cce0092e0f38aaa27b757",
}
EXPECTED_PARENT_ADAPTER_SHA256 = "4cd49c499c441e582dd1e297f211a53075b999d4a62c63e164a87d576d669511"
EXPECTED_CHILD_MANIFEST_SHA256 = "49737105494d7c4fa45aa65f55efac6a245ebdfcc593decbbb59aa7559bc9b90"
EXPECTED_CHILD_TRAIN_SHA256 = "0fcdfb8ba6174748e7a5a14a0e8bcd5314b03eaaff1cbfaaf79898da2550fbc0"
EXPECTED_CHILD_VALID_SHA256 = "8e96dd879588f92224c68719ddaecdfad0a8108bf9328ca7bf8bdac27989faf1"
EXPECTED_CHILD_REVISION = "49737105494d7c4fa45aa65f55efac6a245ebdfcc593decbbb59aa7559bc9b90"
EXPECTED_RUNNER_SHA256 = "b296eb0f91409ba775f754c74526cd5a3d58dadd688eea3a402b28e8356a5ddf"
EXPECTED_BASE_FILES_SHA256 = "287a119fc3857f6ff7f0ba8a0cb2d54479cae81a91f44628ed1efd66dfb7f3ef"
EXPECTED_TERMS_SHA256 = "4973a253f308763e6b21da4242f8bf5220aed9c283d0276ff92ff11958a9555e"


def load_release_lock(path: Path = RELEASE_LOCK_PATH) -> dict[str, object]:
    """Read only a root-reviewed draft asset lock; pending means no transfer."""
    try:
        lock = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PayloadError("release lock is unreadable") from error
    if not isinstance(lock, dict) or lock.get("schema_version") != 1:
        raise PayloadError("release lock schema is unsupported")
    if lock.get("status") != "ready":
        raise PayloadError("release lock is not ready; draft transfer and execution remain disabled")
    if lock.get("release_repository") != "Uhama91/DictAI":
        raise PayloadError("release destination differs from the reviewed repository")
    if lock.get("archive_name") != "gemma3-repair-v1-payload.tar.gz":
        raise PayloadError("release asset name differs from the reviewed payload")
    release_id = lock.get("release_id")
    if not isinstance(release_id, (str, int)) or not str(release_id).isdigit() or int(release_id) <= 0:
        raise PayloadError("draft release ID is malformed")
    archive_sha = lock.get("archive_sha256")
    if not isinstance(archive_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", archive_sha):
        raise PayloadError("draft archive SHA-256 is malformed")
    archive_bytes = lock.get("archive_bytes")
    if not isinstance(archive_bytes, int) or isinstance(archive_bytes, bool):
        raise PayloadError("draft archive byte count is malformed")
    if lock.get("max_archive_bytes") != MAX_ARCHIVE_BYTES:
        raise PayloadError("compressed payload cap differs from the reviewed value")
    if not 0 < archive_bytes < MAX_ARCHIVE_BYTES:
        raise PayloadError("draft archive exceeds the reviewed size cap")
    if lock.get("max_expanded_bytes") != MAX_EXPANDED_BYTES:
        raise PayloadError("expanded payload cap differs from the reviewed value")
    fixed_values = {
        "model_id": MODEL_ID,
        "model_revision": MODEL_REVISION,
        "child_dataset_revision": EXPECTED_CHILD_REVISION,
        "parent_manifest_sha256": EXPECTED_SOURCE_SHA256["parent/manifest.json"],
        "parent_adapter_sha256": EXPECTED_PARENT_ADAPTER_SHA256,
        "old_validation_records": 76,
        "new_evaluation_records": 32,
        "evaluation_records_per_model": 108,
        "training_updates": 64,
        "max_training_seconds": 900,
        "max_cumulative_evaluation_seconds": 1200,
        "per_case_timeout_seconds": 20,
    }
    if any(lock.get(key) != value for key, value in fixed_values.items()):
        raise PayloadError("release lock pilot identity or phase limits changed")
    return lock


class PayloadError(RuntimeError):
    """The staged payload is incomplete, changed, unsafe, or too large."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _bytes_sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class _DigestReader:
    """Hash bytes while they are streamed into the tar archive."""

    def __init__(self, stream):
        self.stream = stream
        self.digest = hashlib.sha256()
        self.bytes_read = 0

    def read(self, size: int = -1) -> bytes:
        data = self.stream.read(size)
        self.digest.update(data)
        self.bytes_read += len(data)
        return data


def _require_regular_source(path: Path, *, allow_symlink: bool = False) -> Path:
    if path.is_symlink() and not allow_symlink:
        raise PayloadError(f"symlink is not allowed for source file: {path.name}")
    try:
        resolved = path.resolve(strict=True)
        info = resolved.stat()
    except OSError as error:
        raise PayloadError(f"source file is absent: {path}") from error
    if not stat.S_ISREG(info.st_mode):
        raise PayloadError(f"source is not a regular file: {path}")
    return resolved


def _reject_symlink_components(base: Path, relative: str) -> Path:
    candidate = base / relative
    current = base
    for component in Path(relative).parts:
        current = current / component
        if current.is_symlink():
            raise PayloadError(f"symlink is not allowed in source path: {relative}")
    return candidate


def collect_payload_sources(
    training_root: Path,
    hf_hub_cache: Path,
    legal_root: Path | None = None,
) -> dict[str, Path]:
    """Resolve the reviewed sources while copying HF snapshot links as bytes."""
    training_root = training_root.resolve(strict=True)
    hf_hub_cache = hf_hub_cache.resolve(strict=True)
    legal_root = (legal_root or TOOL_ROOT / "legal").resolve(strict=True)
    sources: dict[str, Path] = {}

    for source_relative, payload_relative in SOURCE_TO_PAYLOAD.items():
        source_path = _reject_symlink_components(training_root, source_relative)
        source = _require_regular_source(source_path)
        try:
            source.relative_to(training_root)
        except ValueError as error:
            raise PayloadError(f"training source escaped its reviewed root: {source_relative}") from error
        sources[payload_relative] = source

    parent_best = training_root / "runs/gemma-3-270m-it-lora-v3/best"
    if parent_best.is_symlink() or not parent_best.is_dir():
        raise PayloadError("parent adapter directory is not a real directory")
    try:
        actual_parent_names = {entry.name for entry in parent_best.iterdir()}
    except OSError as error:
        raise PayloadError("parent adapter directory is absent") from error
    if actual_parent_names != set(PARENT_ADAPTER_FILES):
        raise PayloadError("parent adapter file set differs from the reviewed allowlist")

    snapshot = hf_hub_cache / MODEL_SNAPSHOT_RELATIVE
    try:
        actual_snapshot_names = {entry.name for entry in snapshot.iterdir()}
    except OSError as error:
        raise PayloadError(f"Gemma base snapshot is absent: revision {MODEL_REVISION}") from error
    if actual_snapshot_names != set(MODEL_SNAPSHOT_FILES):
        raise PayloadError("base snapshot file set differs from the reviewed nine-file allowlist")
    for filename in MODEL_SNAPSHOT_FILES:
        entry = snapshot / filename
        resolved = _require_regular_source(entry, allow_symlink=True)
        try:
            resolved.relative_to(hf_hub_cache)
        except ValueError as error:
            raise PayloadError(f"model symlink target is outside the HF cache: {filename}") from error
        sources[f"base/hub/{MODEL_SNAPSHOT_RELATIVE}/{filename}"] = resolved

    for relative in LEGAL_PATHS:
        source = _require_regular_source(legal_root / Path(relative).name)
        sources[relative] = source

    if set(sources) != set(PAYLOAD_CONTENT_PATHS):
        raise PayloadError("source paths differ from the reviewed payload allowlist")
    return sources


def _directory_sha256(sources: Mapping[str, Path], prefix: str) -> str:
    digest = hashlib.sha256()
    selected = sorted(path for path in sources if path.startswith(prefix + "/"))
    if not selected:
        raise PayloadError(f"allowlisted directory is empty: {prefix}")
    for payload_path in selected:
        relative = payload_path[len(prefix) + 1 :]
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        with sources[payload_path].open("rb") as stream:
            for chunk in iter(lambda: stream.read(CHUNK_BYTES), b""):
                digest.update(chunk)
        digest.update(b"\0")
    return digest.hexdigest()


def verify_locked_sources(
    sources: Mapping[str, Path],
    *,
    local_snapshot_path: Path,
) -> dict[str, object]:
    """Check immutable data/model identities without importing ML libraries."""
    if set(sources) != set(PAYLOAD_CONTENT_PATHS):
        raise PayloadError("source paths differ from the reviewed payload allowlist")
    for relative, expected in EXPECTED_SOURCE_SHA256.items():
        if sha256_file(sources[relative]) != expected:
            raise PayloadError(f"locked input SHA-256 mismatch: {relative}")
    if _directory_sha256(sources, "parent/best") != EXPECTED_PARENT_ADAPTER_SHA256:
        raise PayloadError("parent V3 adapter directory SHA-256 mismatch")
    runner_sha = sha256_file(sources["scripts/run_gemma3_repair_v1.py"])
    if runner_sha != EXPECTED_RUNNER_SHA256:
        raise PayloadError("supervised Gemma 3 runner differs from the reviewed final SHA")

    child_manifest_bytes = sources["data_gemma3_repair_v1/manifest.json"].read_bytes()
    child_manifest = json.loads(child_manifest_bytes.decode("utf-8"))
    declared_manifest = sources["data_gemma3_repair_v1/manifest.sha256"].read_text(encoding="ascii").strip()
    if declared_manifest != EXPECTED_CHILD_MANIFEST_SHA256:
        raise PayloadError("child corpus checksum file differs from the reviewed manifest SHA")
    if child_manifest.get("files", {}).get("train.jsonl", {}).get("sha256") != EXPECTED_CHILD_TRAIN_SHA256:
        raise PayloadError("child train split is not the reviewed 256-record split")
    if child_manifest.get("files", {}).get("valid.jsonl", {}).get("sha256") != EXPECTED_CHILD_VALID_SHA256:
        raise PayloadError("child development split is not the reviewed 32-record split")
    child_revision = hashlib.sha256(child_manifest_bytes).hexdigest()
    if child_revision != EXPECTED_CHILD_REVISION:
        raise PayloadError("child corpus revision changed")
    if sources["legal/Gemma-Terms-of-Use.html"].stat().st_size > 8 * 1024 * 1024:
        raise PayloadError("Gemma Terms page exceeds its reviewed size limit")
    terms_sha = sha256_file(sources["legal/Gemma-Terms-of-Use.html"])
    if terms_sha != EXPECTED_TERMS_SHA256:
        raise PayloadError("official Gemma Terms copy SHA-256 mismatch")
    source_text = sources["legal/SOURCE.txt"].read_text(encoding="utf-8")
    notice_text = sources["legal/NOTICE.txt"].read_text(encoding="utf-8")
    if EXPECTED_TERMS_SHA256 not in source_text or "https://ai.google.dev/gemma/terms" not in source_text:
        raise PayloadError("Gemma Terms source record does not match the included page")
    required_notice = "Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms"
    if required_notice not in notice_text:
        raise PayloadError("Gemma NOTICE is missing the required terms notice")

    local_snapshot_path = Path(local_snapshot_path).resolve(strict=True)
    if not local_snapshot_path.is_dir():
        raise PayloadError("reviewed local HF snapshot path is absent")
    snapshot_records = {}
    for relative in BASE_PATHS:
        source = sources[relative]
        snapshot_records[Path(relative).name] = {
            "sha256": sha256_file(source),
            "size": source.stat().st_size,
        }
    snapshot_summary = {
        # This absolute source path exists only in the local verification
        # result. The portable archive manifest below records revision and
        # file digests without claiming the CI extraction used this path.
        "path": str(local_snapshot_path),
        "revision": MODEL_REVISION,
        "files": snapshot_records,
    }
    snapshot_fingerprint = _bytes_sha256(
        json.dumps(snapshot_summary, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )
    if snapshot_fingerprint != EXPECTED_BASE_FILES_SHA256:
        raise PayloadError("local base snapshot differs from the reviewed nine-file fingerprint")
    return {
        "model_snapshot_sha256": snapshot_fingerprint,
        "model_snapshot_source_path": str(local_snapshot_path),
        "model_snapshot_files": snapshot_records,
        "parent_manifest_sha256": EXPECTED_SOURCE_SHA256["parent/manifest.json"],
        "parent_adapter_sha256": EXPECTED_PARENT_ADAPTER_SHA256,
        "runner_sha256": runner_sha,
        "child_manifest_sha256": EXPECTED_CHILD_MANIFEST_SHA256,
        "child_dataset_revision": child_revision,
        "old_valid_sha256": EXPECTED_SOURCE_SHA256["data_v3/valid.jsonl"],
        "legal_terms_sha256": terms_sha,
    }


def _manifest_for_sources(sources: Mapping[str, Path]) -> dict[str, object]:
    entries: dict[str, dict[str, object]] = {}
    for relative in sorted(PAYLOAD_CONTENT_PATHS):
        source = sources[relative]
        entries[relative] = {"bytes": source.stat().st_size, "sha256": sha256_file(source)}
    if set(sources) != set(PAYLOAD_CONTENT_PATHS):
        raise PayloadError("source paths differ from the reviewed payload allowlist")
    return {
        "schema_version": 1,
        "payload_id": PAYLOAD_ID,
        "model": {"id": MODEL_ID, "revision": MODEL_REVISION},
        "child_dataset_revision": EXPECTED_CHILD_REVISION,
        "parent_manifest_sha256": EXPECTED_SOURCE_SHA256["parent/manifest.json"],
        "parent_adapter_sha256": EXPECTED_PARENT_ADAPTER_SHA256,
        "runner_sha256": entries["scripts/run_gemma3_repair_v1.py"]["sha256"],
        "old_validation_records": 76,
        "model_snapshot_sha256": EXPECTED_BASE_FILES_SHA256,
        "transport": TRANSPORT_DECLARATION,
        "files": entries,
    }


def _write_archive(sources: Mapping[str, Path], archive_path: Path) -> None:
    manifest = _manifest_for_sources(sources)
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    with archive_path.open("xb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0, compresslevel=6) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as archive:
                manifest_info = _tar_info(f"{PAYLOAD_ROOT_NAME}/{PAYLOAD_MANIFEST_NAME}", len(manifest_bytes))
                archive.addfile(manifest_info, io.BytesIO(manifest_bytes))
                for relative in sorted(PAYLOAD_CONTENT_PATHS):
                    source = sources[relative]
                    info = _tar_info(f"{PAYLOAD_ROOT_NAME}/{relative}", source.stat().st_size)
                    with source.open("rb") as stream:
                        reader = _DigestReader(stream)
                        archive.addfile(info, reader)
                    expected = manifest["files"][relative]
                    if reader.bytes_read != info.size or reader.digest.hexdigest() != expected["sha256"]:
                        raise PayloadError(f"source changed while building payload: {relative}")


def _tar_info(name: str, size: int) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.size = size
    info.mode = 0o644
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    info.mtime = 0
    return info


def build_payload_archive(sources: Mapping[str, Path], archive_path: Path) -> dict[str, object]:
    """Create one deterministic archive; never overwrite an existing artifact."""
    if set(sources) != set(PAYLOAD_CONTENT_PATHS):
        raise PayloadError("source paths differ from the reviewed payload allowlist")
    resolved_sources: dict[str, Path] = {}
    expanded_bytes = 0
    for relative, source in sources.items():
        resolved_sources[relative] = _require_regular_source(Path(source))
        expanded_bytes += resolved_sources[relative].stat().st_size
        if expanded_bytes > MAX_EXPANDED_BYTES:
            raise PayloadError("payload expanded size exceeds its 1.5 GB cap")

    archive_path = Path(archive_path)
    if archive_path.exists() or archive_path.is_symlink():
        raise PayloadError(f"refusing to overwrite existing archive: {archive_path}")
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="gemma3-repair-payload-", dir=archive_path.parent) as temporary:
        staged = Path(temporary) / archive_path.name
        _write_archive(resolved_sources, staged)
        size = staged.stat().st_size
        if size <= 0 or size >= MAX_ARCHIVE_BYTES:
            raise PayloadError("compressed payload archive must be smaller than 2 GB")
        # A hard link gives an atomic create-if-absent publication on the same filesystem.
        try:
            os.link(staged, archive_path)
        except FileExistsError as error:
            raise PayloadError(f"refusing to overwrite existing archive: {archive_path}") from error
    return {"path": str(archive_path), "bytes": size, "sha256": sha256_file(archive_path)}


def validate_archive_member_name(name: str) -> str:
    """Return the path below the payload root, rejecting traversal syntax."""
    if not name or "\\" in name or name.startswith("/"):
        raise PayloadError("unsafe archive member path")
    path = PurePosixPath(name)
    if (
        path.is_absolute()
        or path.as_posix() != name
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise PayloadError("unsafe archive member path")
    if len(path.parts) < 2 or path.parts[0] != PAYLOAD_ROOT_NAME:
        raise PayloadError("archive member is outside the Gemma 3 payload root")
    relative = "/".join(path.parts[1:])
    if relative != PAYLOAD_MANIFEST_NAME and relative not in PAYLOAD_CONTENT_PATHS:
        raise PayloadError(f"archive member is not allowlisted: {relative}")
    return relative


def _read_payload_manifest(archive: tarfile.TarFile, member: tarfile.TarInfo) -> dict[str, object]:
    if member.size < 1 or member.size > MAX_MANIFEST_BYTES:
        raise PayloadError("payload manifest exceeds its size limit")
    stream = archive.extractfile(member)
    if stream is None:
        raise PayloadError("payload manifest has no file content")
    try:
        data = stream.read(MAX_MANIFEST_BYTES + 1)
    finally:
        stream.close()
    if len(data) != member.size or len(data) > MAX_MANIFEST_BYTES:
        raise PayloadError("payload manifest size is inconsistent")
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PayloadError("payload manifest is not valid UTF-8 JSON") from error
    if not isinstance(value, dict) or value.get("schema_version") != 1 or value.get("payload_id") != PAYLOAD_ID:
        raise PayloadError("unsupported Gemma 3 payload manifest")
    return value


def extract_payload_archive(
    archive_path: Path,
    destination: Path,
    *,
    expected_sha256: str,
    expected_bytes: int,
    expected_runner_sha256: str | None = EXPECTED_RUNNER_SHA256,
    max_archive_bytes: int = MAX_ARCHIVE_BYTES,
    max_total_bytes: int = MAX_EXPANDED_BYTES,
) -> dict[str, object]:
    """Verify archive identity, members and hashes before publishing extraction."""
    archive_path = Path(archive_path)
    destination = Path(destination)
    if archive_path.is_symlink() or not archive_path.is_file():
        raise PayloadError("payload archive must be a regular file")
    if destination.exists() or destination.is_symlink():
        raise PayloadError(f"extraction destination already exists: {destination}")
    archive_bytes = archive_path.stat().st_size
    if expected_bytes <= 0 or archive_bytes != expected_bytes:
        raise PayloadError("payload archive byte length mismatch")
    if archive_bytes >= min(max_archive_bytes, MAX_ARCHIVE_BYTES):
        raise PayloadError("compressed payload archive exceeds 2 GB")
    if len(expected_sha256) != 64 or any(c not in "0123456789abcdef" for c in expected_sha256.lower()):
        raise PayloadError("expected archive SHA-256 is malformed")
    actual_sha256 = sha256_file(archive_path)
    if actual_sha256 != expected_sha256.lower():
        raise PayloadError("payload archive SHA-256 mismatch")

    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(prefix="gemma3-repair-extract-", dir=destination.parent) as temporary:
            expanded_tar_path = Path(temporary) / "payload.tar"
            max_tar_bytes = max_total_bytes + MAX_TAR_OVERHEAD_BYTES
            with archive_path.open("rb") as raw, gzip.GzipFile(fileobj=raw, mode="rb") as compressed, expanded_tar_path.open("xb") as expanded:
                expanded_bytes = 0
                while True:
                    chunk = compressed.read(CHUNK_BYTES)
                    if not chunk:
                        break
                    expanded_bytes += len(chunk)
                    if expanded_bytes > max_tar_bytes:
                        raise PayloadError("decompressed archive exceeds the expanded-size limit")
                    expanded.write(chunk)
                expanded.flush()
                os.fsync(expanded.fileno())
            with tarfile.open(expanded_tar_path, mode="r:") as archive:
                members = archive.getmembers()
                if len(members) != len(PAYLOAD_CONTENT_PATHS) + 1:
                    raise PayloadError("payload archive member count differs from its allowlist")
                member_by_relative: dict[str, tarfile.TarInfo] = {}
                total_size = 0
                for member in members:
                    relative = validate_archive_member_name(member.name)
                    if relative in member_by_relative:
                        raise PayloadError(f"duplicate archive member: {relative}")
                    if not member.isfile() or member.issparse() or member.pax_headers:
                        raise PayloadError(f"archive member is not a plain regular file: {relative}")
                    if member.size < 0:
                        raise PayloadError(f"archive member has a negative size: {relative}")
                    total_size += member.size
                    if total_size > max_total_bytes:
                        raise PayloadError("payload expanded size exceeds its limit")
                    member_by_relative[relative] = member
                expected_names = set(PAYLOAD_CONTENT_PATHS) | {PAYLOAD_MANIFEST_NAME}
                if set(member_by_relative) != expected_names:
                    raise PayloadError("payload archive files differ from the reviewed allowlist")

                manifest = _read_payload_manifest(archive, member_by_relative[PAYLOAD_MANIFEST_NAME])
                files = manifest.get("files")
                if not isinstance(files, dict) or set(files) != set(PAYLOAD_CONTENT_PATHS):
                    raise PayloadError("payload manifest file set differs from the reviewed allowlist")
                if manifest.get("model") != {"id": MODEL_ID, "revision": MODEL_REVISION}:
                    raise PayloadError("payload manifest model identity differs from the reviewed revision")
                if manifest.get("child_dataset_revision") != EXPECTED_CHILD_REVISION:
                    raise PayloadError("payload manifest child corpus identity differs from the reviewed revision")
                if manifest.get("parent_manifest_sha256") != EXPECTED_SOURCE_SHA256["parent/manifest.json"]:
                    raise PayloadError("payload manifest parent identity differs from the reviewed V3 manifest")
                if manifest.get("parent_adapter_sha256") != EXPECTED_PARENT_ADAPTER_SHA256:
                    raise PayloadError("payload manifest parent adapter differs from the reviewed V3 adapter")
                runner_record = files.get("scripts/run_gemma3_repair_v1.py")
                if (
                    not isinstance(runner_record, dict)
                    or manifest.get("runner_sha256") != runner_record.get("sha256")
                ):
                    raise PayloadError("payload manifest runner SHA does not match its file record")
                if expected_runner_sha256 is not None and runner_record.get("sha256") != expected_runner_sha256:
                    raise PayloadError("payload manifest runner differs from the reviewed final SHA")
                if manifest.get("old_validation_records") != 76:
                    raise PayloadError("payload manifest old validation size differs from the reviewed set")
                if manifest.get("model_snapshot_sha256") != EXPECTED_BASE_FILES_SHA256:
                    raise PayloadError("payload manifest base snapshot differs from the reviewed fingerprint")
                if manifest.get("transport") != TRANSPORT_DECLARATION:
                    raise PayloadError("payload manifest draft-transfer declaration differs from the reviewed plan")

                staging = Path(temporary) / PAYLOAD_ROOT_NAME
                staging.mkdir()
                for relative in sorted(PAYLOAD_CONTENT_PATHS):
                    member = member_by_relative[relative]
                    record = files[relative]
                    if not isinstance(record, dict) or record.get("bytes") != member.size:
                        raise PayloadError(f"payload manifest size mismatch: {relative}")
                    expected_file_sha = record.get("sha256")
                    if not isinstance(expected_file_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_file_sha):
                        raise PayloadError(f"payload manifest SHA is malformed: {relative}")
                    stream = archive.extractfile(member)
                    if stream is None:
                        raise PayloadError(f"archive member has no content: {relative}")
                    target = staging / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    digest = hashlib.sha256()
                    written = 0
                    try:
                        with target.open("xb") as output:
                            while True:
                                chunk = stream.read(CHUNK_BYTES)
                                if not chunk:
                                    break
                                written += len(chunk)
                                if written > member.size:
                                    raise PayloadError(f"archive member exceeds its declared size: {relative}")
                                output.write(chunk)
                                digest.update(chunk)
                            output.flush()
                            os.fsync(output.fileno())
                    finally:
                        stream.close()
                    if written != member.size or digest.hexdigest() != expected_file_sha:
                        raise PayloadError(f"payload member SHA-256 or size mismatch: {relative}")

                if destination.exists() or destination.is_symlink():
                    raise PayloadError(f"extraction destination already exists: {destination}")
                staging.rename(destination)
    except PayloadError:
        raise
    except (OSError, tarfile.TarError, EOFError, gzip.BadGzipFile, ValueError) as error:
        raise PayloadError(f"cannot safely extract Gemma 3 payload: {type(error).__name__}") from error

    return {
        "path": str(destination),
        "archive_bytes": archive_bytes,
        "archive_sha256": actual_sha256,
        "file_count": len(PAYLOAD_CONTENT_PATHS),
        "files": manifest["files"],
    }


def _build_command(args: argparse.Namespace) -> int:
    sources = collect_payload_sources(args.training_root, args.hf_hub_cache, args.legal_root)
    local_snapshot_path = args.hf_hub_cache.resolve(strict=True) / MODEL_SNAPSHOT_RELATIVE
    identities = verify_locked_sources(sources, local_snapshot_path=local_snapshot_path)
    result = build_payload_archive(sources, args.output)
    print(json.dumps({**result, "identities": identities}, ensure_ascii=False, sort_keys=True))
    return 0


def _verify_command(args: argparse.Namespace) -> int:
    sources = collect_payload_sources(args.training_root, args.hf_hub_cache, args.legal_root)
    local_snapshot_path = args.hf_hub_cache.resolve(strict=True) / MODEL_SNAPSHOT_RELATIVE
    identities = verify_locked_sources(sources, local_snapshot_path=local_snapshot_path)
    print(json.dumps({"status": "verified_sources", "identities": identities}, ensure_ascii=False, sort_keys=True))
    return 0


def _extract_command(args: argparse.Namespace) -> int:
    result = extract_payload_archive(
        args.archive,
        args.output,
        expected_sha256=args.sha256,
        expected_bytes=args.bytes,
        max_archive_bytes=args.max_archive_bytes,
        max_total_bytes=args.max_expanded_bytes,
    )
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


def _release_lock_command(args: argparse.Namespace) -> int:
    lock = load_release_lock(args.config)
    output = Path(args.github_output)
    values = {
        "release_id": str(lock["release_id"]),
        "archive_name": str(lock["archive_name"]),
        "archive_sha256": str(lock["archive_sha256"]),
        "archive_bytes": str(lock["archive_bytes"]),
    }
    if any("\n" in value or "\r" in value for value in values.values()):
        raise PayloadError("release lock contains an invalid workflow output value")
    with output.open("a", encoding="utf-8", newline="\n") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")
    print(json.dumps({"status": "ready", "release_id": values["release_id"]}, sort_keys=True))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    for command_name, help_text in (
        ("verify-sources", "verify only allowlisted local inputs without staging an archive"),
        ("build", "create one local payload archive from the locked inputs"),
    ):
        command = commands.add_parser(command_name, help=help_text)
        command.add_argument("--training-root", type=Path, required=True)
        command.add_argument("--hf-hub-cache", type=Path, default=Path.home() / ".cache/huggingface/hub")
        command.add_argument("--legal-root", type=Path, default=TOOL_ROOT / "legal")
        if command_name == "build":
            command.add_argument("--output", type=Path, required=True)

    extract = commands.add_parser("extract", help="verify and safely extract the immutable release payload")
    extract.add_argument("--archive", type=Path, required=True)
    extract.add_argument("--output", type=Path, required=True)
    extract.add_argument("--sha256", required=True)
    extract.add_argument("--bytes", type=int, required=True)
    extract.add_argument("--max-archive-bytes", type=int, default=MAX_ARCHIVE_BYTES)
    extract.add_argument("--max-expanded-bytes", type=int, default=MAX_EXPANDED_BYTES)
    lock = commands.add_parser("release-lock", help="validate the reviewed draft asset lock for Actions")
    lock.add_argument("--config", type=Path, default=RELEASE_LOCK_PATH)
    lock.add_argument("--github-output", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "release-lock":
            return _release_lock_command(args)
        if args.command == "build":
            return _build_command(args)
        if args.command == "verify-sources":
            return _verify_command(args)
        return _extract_command(args)
    except (PayloadError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Gemma 3 payload refused: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
