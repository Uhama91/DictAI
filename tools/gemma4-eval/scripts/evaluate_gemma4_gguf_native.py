#!/usr/bin/env python3
"""Evaluate frozen ASR records through a local llama-server GGUF endpoint.

The client is deliberately narrow: it rebuilds the frozen source-last V6
prompt, checks that llama.cpp tokenizes the HF-rendered prompt identically,
then sends only those prompt IDs to ``/completion``.  It does not start a
server, build a model, access a holdout, or score semantic quality.
"""

from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import os
import sys
from collections import defaultdict
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(ROOT))

from asr_postclean.prompt_v6_source_last import (  # noqa: E402
    PROMPT_V6_SOURCE_LAST_VERSION,
    build_prompt_v6_source_last,
)
from asr_postclean.prompt_v6 import PROMPT_V6_VERSION  # noqa: E402


DEFAULT_SELECTION = ROOT / "reports" / "gemma4_v6" / "usage_audit_v1" / "selection.json"
DEFAULT_RECORDS = ROOT / "data_v6" / "records" / "valid.jsonl"
DEFAULT_TOKENIZER = ROOT / "exports" / "gemma4-e2b-v6-1956-hf-bf16-attempt-1"
DEFAULT_BASE_URL = "http://127.0.0.1:8080"
EXPECTED_SELECTION_SHA256 = "ea5ba2fc6138764326f4bd46bb6ffe8d85fc59079c1d6365153804a13d90ec54"
EXPECTED_RECORDS_SHA256 = "4edc9d3d4b9bc7dd0562f0768ca1ea677abace355a1efadd5076db9a23cc74ff"
EXPECTED_SELECTION_COUNT = 60
EXPECTED_RECORD_COUNT = 196
EXPECTED_SMOKE_COUNT = 12
MAX_TOKENS = 512
MODES = ("corrected", "list", "email")
PHASES = ("final", "partial")
COHORTS = ("v4_valid", "v5_new")
PROMPT_V6_PATH = ROOT / "src" / "asr_postclean" / "prompt_v6.py"
PROMPT_V6_SOURCE_LAST_PATH = ROOT / "src" / "asr_postclean" / "prompt_v6_source_last.py"
EXPECTED_PROMPT_V6_SHA256 = "af0d7c808150cd27960cd4506261e6b29fc6ac45df13db2694c6c870affbb0d6"
EXPECTED_PROMPT_V6_SOURCE_LAST_SHA256 = "234b6fb22392caa6fc05b9bfb39ceae3373d4bac46b625cb881a69175b8bb72d"
REQUEST_OPTIONS = {
    "temperature": 0.0,
    "repeat_penalty": 1.0,
    "presence_penalty": 0.0,
    "frequency_penalty": 0.0,
    "n_predict": MAX_TOKENS,
    "cache_prompt": False,
    "stream": False,
    "return_tokens": True,
    "stop": [],
}
# These are the fields emitted by llama.cpp's generation_params.to_json().
# cache_prompt and return_tokens are request-only options and are deliberately
# not required in the server's generation_settings response.
REPORTED_SETTINGS = {
    "temperature": 0.0,
    "repeat_penalty": 1.0,
    "presence_penalty": 0.0,
    "frequency_penalty": 0.0,
    "n_predict": MAX_TOKENS,
    "stream": False,
    "stop": [],
}


class NativeClientError(ValueError):
    """Raised when the local GGUF generation contract cannot be trusted."""


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_directory(path: str | Path) -> str:
    """Hash a tokenizer directory with relative names and file bytes."""

    directory = _require_directory(path, "tokenizer")
    files = sorted(directory.rglob("*"), key=lambda candidate: candidate.relative_to(directory).as_posix())
    regular_files = []
    for candidate in files:
        if candidate.is_symlink():
            raise NativeClientError(f"tokenizer contains a symlink: {candidate}")
        if candidate.is_file():
            regular_files.append(candidate)
    if not regular_files:
        raise NativeClientError(f"tokenizer directory is empty: {directory}")
    digest = hashlib.sha256()
    for candidate in regular_files:
        relative = candidate.relative_to(directory).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        with candidate.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def _require_file(path: str | Path, label: str) -> Path:
    candidate = Path(path).expanduser()
    if candidate.is_symlink() or not candidate.is_file():
        raise NativeClientError(f"{label} is absent or symlinked: {candidate}")
    return candidate.resolve()


def _require_directory(path: str | Path, label: str) -> Path:
    candidate = Path(path).expanduser()
    if candidate.is_symlink() or not candidate.is_dir():
        raise NativeClientError(f"{label} is absent or symlinked: {candidate}")
    return candidate.resolve()


def _require_sha(path: Path, expected: str, label: str) -> str:
    if not isinstance(expected, str) or len(expected) != 64:
        raise NativeClientError(f"{label} SHA-256 must be a 64-character hexadecimal string")
    try:
        int(expected, 16)
    except ValueError as error:
        raise NativeClientError(f"{label} SHA-256 must be hexadecimal") from error
    actual = sha256_file(path)
    if actual != expected:
        raise NativeClientError(f"{label} SHA-256 mismatch: expected {expected}, got {actual}")
    return actual


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8", newline="") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as error:
                raise NativeClientError(f"invalid JSON at {path}:{line_number}") from error
            if not isinstance(value, dict):
                raise NativeClientError(f"record at {path}:{line_number} is not an object")
            rows.append(value)
    if not rows:
        raise NativeClientError(f"records file is empty: {path}")
    return rows


def load_frozen_selection(
    path: str | Path = DEFAULT_SELECTION,
    *,
    expected_sha256: str = EXPECTED_SELECTION_SHA256,
) -> tuple[dict[str, Any], str]:
    selection_path = _require_file(path, "frozen selection")
    selection_sha256 = _require_sha(selection_path, expected_sha256, "frozen selection")
    try:
        selection = json.loads(selection_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise NativeClientError(f"invalid selection JSON: {selection_path}") from error
    if not isinstance(selection, dict):
        raise NativeClientError("frozen selection must be an object")
    selected = selection.get("selected")
    if selection.get("selected_count") != EXPECTED_SELECTION_COUNT:
        raise NativeClientError("frozen selection must contain exactly 60 records")
    if not isinstance(selected, list) or len(selected) != EXPECTED_SELECTION_COUNT:
        raise NativeClientError("frozen selection selected list must contain exactly 60 records")
    ids = [row.get("id") if isinstance(row, dict) else None for row in selected]
    if any(not isinstance(identifier, str) or not identifier for identifier in ids):
        raise NativeClientError("frozen selection contains an invalid ID")
    if len(set(ids)) != EXPECTED_SELECTION_COUNT:
        raise NativeClientError("frozen selection contains duplicate IDs")
    inputs = selection.get("inputs")
    if not isinstance(inputs, dict) or inputs.get("valid_sha256") != EXPECTED_RECORDS_SHA256:
        raise NativeClientError("frozen selection does not pin the expected valid source")
    return selection, selection_sha256


def _declared_records_path(selection_path: Path, selection: Mapping[str, Any]) -> Path | None:
    inputs = selection.get("inputs")
    if not isinstance(inputs, Mapping) or not isinstance(inputs.get("valid_path"), str):
        return None
    declared = Path(inputs["valid_path"]).expanduser()
    if not declared.is_absolute():
        declared = ROOT / declared
    return declared.resolve()


def load_locked_records(
    selection_path: str | Path = DEFAULT_SELECTION,
    records_path: str | Path = DEFAULT_RECORDS,
    *,
    expected_selection_sha256: str = EXPECTED_SELECTION_SHA256,
    expected_records_sha256: str = EXPECTED_RECORDS_SHA256,
) -> tuple[list[dict[str, Any]], str, str]:
    """Load only the selected valid rows and verify their frozen fields."""

    selection_file = _require_file(selection_path, "frozen selection")
    selection, selection_sha256 = load_frozen_selection(
        selection_file,
        expected_sha256=expected_selection_sha256,
    )
    records_file = _require_file(records_path, "valid records")
    records_sha256 = _require_sha(records_file, expected_records_sha256, "valid records")
    declared_path = _declared_records_path(selection_file, selection)
    if declared_path is not None and declared_path != records_file:
        raise NativeClientError("valid records path differs from the frozen selection")
    rows = _load_jsonl(records_file)
    if len(rows) != EXPECTED_RECORD_COUNT:
        raise NativeClientError(f"valid source must contain {EXPECTED_RECORD_COUNT} records")
    by_id: dict[str, dict[str, Any]] = {}
    for row in rows:
        identifier = row.get("id")
        if not isinstance(identifier, str) or not identifier or identifier in by_id:
            raise NativeClientError("valid source contains a missing or duplicate ID")
        if row.get("split") != "valid":
            raise NativeClientError(f"non-valid record found for {identifier}")
        by_id[identifier] = row

    locked: list[dict[str, Any]] = []
    fields = (
        "id",
        "mode",
        "phase",
        "source",
        "target",
        "context_before",
        "episode_id",
        "family",
        "scenario_id",
    )
    for selection_index, selected in enumerate(selection["selected"]):
        identifier = selected["id"]
        try:
            source_row = by_id[identifier]
        except KeyError as error:
            raise NativeClientError(f"selected ID is absent from valid records: {identifier}") from error
        for field in fields:
            if selected.get(field) != source_row.get(field):
                raise NativeClientError(f"frozen field changed for {identifier}: {field}")
        expected_source_sha = selected.get("source_sha256")
        expected_target_sha = selected.get("target_sha256")
        if isinstance(expected_source_sha, str) and sha256_text(source_row["source"]) != expected_source_sha:
            raise NativeClientError(f"source hash changed for {identifier}")
        if isinstance(expected_target_sha, str) and sha256_text(source_row["target"]) != expected_target_sha:
            raise NativeClientError(f"target hash changed for {identifier}")
        if selected.get("cohort") not in COHORTS:
            raise NativeClientError(f"invalid cohort for {identifier}")
        if source_row.get("mode") not in MODES or source_row.get("phase") not in PHASES:
            raise NativeClientError(f"invalid mode or phase for {identifier}")
        row = dict(source_row)
        row["cohort"] = selected["cohort"]
        row["selection_index"] = selection_index
        row["selection_sha256"] = selected.get("selection_sha256")
        locked.append(row)
    return locked, selection_sha256, records_sha256


def sha256_text(value: str) -> str:
    if not isinstance(value, str):
        raise NativeClientError("hash input must be text")
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def select_smoke_records(records: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Select the first SHA-256(ID) record from each of 12 frozen cells."""

    cells: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        row = dict(record)
        cell = (row.get("cohort"), row.get("mode"), row.get("phase"))
        if cell[0] not in COHORTS or cell[1] not in MODES or cell[2] not in PHASES:
            raise NativeClientError(f"record has invalid smoke cell: {cell}")
        if not isinstance(row.get("id"), str) or not row["id"]:
            raise NativeClientError("record has no valid ID")
        cells[cell].append(row)
    expected_cells = {(cohort, mode, phase) for cohort in COHORTS for mode in MODES for phase in PHASES}
    if set(cells) != expected_cells:
        raise NativeClientError("smoke selection does not cover all 12 cohort/mode/phase cells")
    selected: list[dict[str, Any]] = []
    for cell in sorted(expected_cells):
        candidates = cells[cell]
        selected.append(min(candidates, key=lambda row: sha256_text(row["id"])))
    return selected


def _token_ids(value: Any, label: str) -> list[int]:
    if isinstance(value, Mapping):
        value = value.get("input_ids")
    if hasattr(value, "tolist"):
        value = value.tolist()
    if isinstance(value, list) and len(value) == 1 and isinstance(value[0], list):
        value = value[0]
    if not isinstance(value, list) or any(isinstance(token, bool) or not isinstance(token, int) for token in value):
        raise NativeClientError(f"{label} are not a list of integer IDs")
    return list(value)


def build_hf_prompt(tokenizer: Any, record: Mapping[str, Any]) -> dict[str, Any]:
    """Render the source-last prompt and its HF token IDs without target data."""

    user_content = build_prompt_v6_source_last(record)
    probe = dict(record)
    probe["target"] = "__TARGET_SENTINEL_MUST_NOT_ENTER_PROMPT__"
    probe["annotation_notes"] = "__ANNOTATION_SENTINEL_MUST_NOT_ENTER_PROMPT__"
    if build_prompt_v6_source_last(probe) != user_content:
        raise NativeClientError(f"prompt depends on target or annotations: {record.get('id')}")
    messages = [{"role": "user", "content": user_content}]
    try:
        rendered = tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True,
            enable_thinking=False,
        )
        encoded = tokenizer.apply_chat_template(
            messages,
            tokenize=True,
            add_generation_prompt=True,
            enable_thinking=False,
        )
    except Exception as error:
        raise NativeClientError(f"HF chat template failed for {record.get('id')}") from error
    if not isinstance(rendered, str):
        raise NativeClientError("HF chat template did not return prompt text")
    hf_prompt_ids = _token_ids(encoded, "HF prompt token IDs")
    return {
        "user_content": user_content,
        "rendered_prompt": rendered,
        "hf_token_ids": hf_prompt_ids,
    }


def build_completion_payload(native_prompt_ids: Sequence[int]) -> dict[str, Any]:
    ids = _token_ids(list(native_prompt_ids), "native prompt token IDs")
    if not ids:
        raise NativeClientError("native prompt token IDs cannot be empty")
    return {"prompt": ids, **REQUEST_OPTIONS}


def validate_completion_response(response: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(response, Mapping):
        raise NativeClientError("completion response must be an object")
    content = response.get("content")
    if not isinstance(content, str):
        raise NativeClientError("completion response has no text content")
    tokens = _token_ids(response.get("tokens"), "generated token IDs")
    if "stop_type" not in response:
        raise NativeClientError("completion response is missing stop_type")
    if "truncated" not in response:
        raise NativeClientError("completion response is missing truncated")
    stop_type = response.get("stop_type")
    if stop_type != "eos":
        raise NativeClientError(f"completion did not stop on EOS: {stop_type!r}")
    if response.get("stop") is False:
        raise NativeClientError("completion response does not report a stop")
    if not isinstance(response["truncated"], bool):
        raise NativeClientError("completion response has an invalid truncated field")
    if response["truncated"] is True:
        raise NativeClientError("completion response is truncated")
    settings = response.get("generation_settings")
    if not isinstance(settings, Mapping):
        raise NativeClientError("completion response is missing generation_settings")
    for key, expected in REPORTED_SETTINGS.items():
        if key not in settings:
            raise NativeClientError(f"completion generation_settings is missing {key}")
        if settings[key] != expected:
            raise NativeClientError(
                f"completion generation setting differs for {key}: expected {expected!r}, got {settings[key]!r}"
            )
    validated = dict(response)
    validated.update(
        {
            "content": content,
            "tokens": tokens,
            "stop_type": "eos",
            "eos_reached": True,
            "truncated": False,
        "generation_tokens": len(tokens),
        }
    )
    return validated


class _NoRedirectHandler(HTTPRedirectHandler):
    """Reject redirects so a loopback client cannot leave the pinned endpoint."""

    def _reject(self, request, *args, **kwargs):  # noqa: ANN001 - urllib callback signature
        raise NativeClientError(f"llama-server redirect refused: {request.full_url}")

    http_error_301 = _reject
    http_error_302 = _reject
    http_error_303 = _reject
    http_error_307 = _reject
    http_error_308 = _reject


class _NoProxyHandler(ProxyHandler):
    """Keep an explicit empty proxy handler in the opener."""

    def __init__(self):
        super().__init__({})

    def http_open(self, request):  # noqa: ANN001 - urllib callback signature
        return None

    def https_open(self, request):  # noqa: ANN001 - urllib callback signature
        return None


class LocalLlamaServerTransport:
    """Minimal JSON transport restricted to the local IPv4 llama-server."""

    def __init__(self, base_url: str = DEFAULT_BASE_URL, *, timeout: float = 120.0):
        parsed = urlparse(base_url)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.username or parsed.password:
            raise NativeClientError("llama-server URL must use http://127.0.0.1")
        if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
            raise NativeClientError("llama-server URL must not contain a path or query")
        try:
            port = parsed.port or 8080
        except ValueError as error:
            raise NativeClientError("llama-server URL has an invalid port") from error
        self.base_url = f"http://127.0.0.1:{port}"
        self.timeout = timeout
        self._opener = build_opener(_NoProxyHandler(), _NoRedirectHandler())

    def post(self, endpoint: str, payload: Mapping[str, Any]) -> dict[str, Any]:
        if endpoint not in ("/tokenize", "/completion"):
            raise NativeClientError(f"unsupported llama-server endpoint: {endpoint}")
        request = Request(
            self.base_url + endpoint,
            data=json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                body = response.read()
                status = response.status
        except Exception as error:
            raise NativeClientError(f"llama-server request failed: {endpoint}") from error
        if status != 200:
            raise NativeClientError(f"llama-server returned HTTP {status} for {endpoint}")
        try:
            value = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise NativeClientError(f"llama-server returned invalid JSON for {endpoint}") from error
        if not isinstance(value, dict):
            raise NativeClientError(f"llama-server response for {endpoint} is not an object")
        return value

    def get(self, endpoint: str) -> dict[str, Any]:
        if endpoint not in ("/props", "/v1/models"):
            raise NativeClientError(f"unsupported llama-server endpoint: {endpoint}")
        request = Request(self.base_url + endpoint, method="GET")
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                body = response.read()
                status = response.status
        except Exception as error:
            raise NativeClientError(f"llama-server request failed: {endpoint}") from error
        if status != 200:
            raise NativeClientError(f"llama-server returned HTTP {status} for {endpoint}")
        try:
            value = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise NativeClientError(f"llama-server returned invalid JSON for {endpoint}") from error
        if not isinstance(value, dict):
            raise NativeClientError(f"llama-server response for {endpoint} is not an object")
        return value


def verify_server_identity(
    transport: Any,
    *,
    expected_model_path: str | None = None,
    expected_model_alias: str | None = None,
) -> dict[str, Any]:
    """Verify the model loaded by the local server before any generation."""

    if bool(expected_model_path) == bool(expected_model_alias):
        raise NativeClientError("provide exactly one expected model path or alias")
    props = transport.get("/props")
    if not isinstance(props, Mapping):
        raise NativeClientError("llama-server /props response is not an object")
    observed_path = props.get("model_path")
    if not isinstance(observed_path, str) or not observed_path:
        raise NativeClientError("llama-server /props has no model_path")
    observed_alias: str | None = None
    settings = props.get("default_generation_settings")
    if isinstance(settings, Mapping) and isinstance(settings.get("model"), str):
        observed_alias = settings["model"]
    if expected_model_path is not None and observed_path != expected_model_path:
        raise NativeClientError(
            f"llama-server model path differs: expected {expected_model_path!r}, got {observed_path!r}"
        )
    models_response: dict[str, Any] | None = None
    if expected_model_alias is not None:
        if observed_alias != expected_model_alias:
            models_response = transport.get("/v1/models")
            data = models_response.get("data") if isinstance(models_response, Mapping) else None
            if isinstance(data, list) and len(data) == 1 and isinstance(data[0], Mapping):
                if isinstance(data[0].get("id"), str):
                    observed_alias = data[0]["id"]
        if observed_alias != expected_model_alias:
            raise NativeClientError(
                f"llama-server model alias differs: expected {expected_model_alias!r}, got {observed_alias!r}"
            )
    return {
        "model_path": observed_path,
        "model_alias": observed_alias,
        "props": dict(props),
        "models": models_response,
    }


def _prompt_provenance() -> dict[str, Any]:
    source = inspect.getsourcefile(build_prompt_v6_source_last)
    if not source:
        raise NativeClientError("cannot locate source-last prompt builder")
    path = _require_file(source, "source-last prompt builder")
    return {
        "version": PROMPT_V6_SOURCE_LAST_VERSION,
        "builder": "asr_postclean.prompt_v6_source_last.build_prompt_v6_source_last",
        "path": str(path),
        "sha256": sha256_file(path),
    }


def _validate_expected_sha(value: str, label: str) -> str:
    if not isinstance(value, str) or len(value) != 64:
        raise NativeClientError(f"{label} SHA-256 must be a 64-character hexadecimal string")
    try:
        int(value, 16)
    except ValueError as error:
        raise NativeClientError(f"{label} SHA-256 must be hexadecimal") from error
    return value.lower()


def verify_provenance(
    gguf_path: str | Path,
    gguf_sha256: str,
    tokenizer_path: str | Path,
    tokenizer_sha256: str,
    *,
    prompt_v6_path: str | Path = PROMPT_V6_PATH,
    prompt_v6_sha256: str = EXPECTED_PROMPT_V6_SHA256,
    prompt_v6_source_last_path: str | Path = PROMPT_V6_SOURCE_LAST_PATH,
    prompt_v6_source_last_sha256: str = EXPECTED_PROMPT_V6_SOURCE_LAST_SHA256,
) -> dict[str, Any]:
    """Verify every input lock before contacting llama-server."""

    expected_gguf = _validate_expected_sha(gguf_sha256, "GGUF")
    expected_tokenizer = _validate_expected_sha(tokenizer_sha256, "tokenizer")
    expected_prompt = _validate_expected_sha(prompt_v6_sha256, "V6 prompt helper")
    expected_source_last = _validate_expected_sha(
        prompt_v6_source_last_sha256,
        "source-last prompt helper",
    )
    gguf = _require_file(gguf_path, "GGUF")
    tokenizer = _require_directory(tokenizer_path, "HF tokenizer")
    actual_gguf = _require_sha(gguf, expected_gguf, "GGUF")
    actual_tokenizer = sha256_directory(tokenizer)
    if actual_tokenizer != expected_tokenizer:
        raise NativeClientError(
            f"tokenizer SHA-256 mismatch: expected {expected_tokenizer}, got {actual_tokenizer}"
        )
    prompt_path = _require_file(prompt_v6_path, "V6 prompt helper")
    actual_prompt = _require_sha(prompt_path, expected_prompt, "V6 prompt helper")
    source_last_path = _require_file(prompt_v6_source_last_path, "source-last prompt helper")
    actual_source_last = _require_sha(
        source_last_path,
        expected_source_last,
        "source-last prompt helper",
    )
    return {
        "gguf": {"path": str(gguf), "sha256": actual_gguf},
        "tokenizer": {"path": str(tokenizer), "sha256": actual_tokenizer},
        "prompt_v6": {
            "version": PROMPT_V6_VERSION,
            "path": str(prompt_path),
            "sha256": actual_prompt,
        },
        "prompt_v6_source_last": {
            "version": PROMPT_V6_SOURCE_LAST_VERSION,
            "path": str(source_last_path),
            "sha256": actual_source_last,
        },
    }


def _load_tokenizer(path: Path) -> Any:
    try:
        from transformers import AutoTokenizer
    except ImportError as error:  # pragma: no cover - environment-specific
        raise NativeClientError("transformers is required for the local HF tokenizer") from error
    _require_directory(path, "HF tokenizer")
    try:
        return AutoTokenizer.from_pretrained(
            str(path),
            local_files_only=True,
            trust_remote_code=True,
        )
    except Exception as error:  # pragma: no cover - environment-specific
        raise NativeClientError(f"could not load local HF tokenizer: {path}") from error


def _append_json_line(stream: Any, event: Mapping[str, Any]) -> None:
    stream.write(json.dumps(event, ensure_ascii=False, allow_nan=False) + "\n")
    stream.flush()
    os.fsync(stream.fileno())


def _load_journal(path: Path) -> tuple[list[dict[str, Any]], str]:
    if path.is_symlink() or not path.is_file():
        raise NativeClientError(f"resume journal is absent or symlinked: {path}")
    raw = path.read_bytes()
    events: list[dict[str, Any]] = []
    for line_number, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as error:
            raise NativeClientError(f"invalid resume journal JSON at {path}:{line_number}") from error
        if not isinstance(event, dict):
            raise NativeClientError(f"resume journal event is not an object: {path}:{line_number}")
        events.append(event)
    if not events or events[0].get("kind") != "run_started":
        raise NativeClientError("resume journal must begin with run_started")
    return events, hashlib.sha256(raw).hexdigest()


def _record_row(
    record: Mapping[str, Any],
    *,
    selection_sha256: str,
    prompt: Mapping[str, Any],
    native_prompt_ids: list[int],
    completion: Mapping[str, Any],
) -> dict[str, Any]:
    identifier = record["id"]
    return {
        "id": identifier,
        "selection_index": record.get("selection_index"),
        "selection_sha256": selection_sha256,
        "cohort": record.get("cohort"),
        "scenario_id": record.get("scenario_id"),
        "episode_id": record.get("episode_id"),
        "family": record.get("family"),
        "mode": record.get("mode"),
        "phase": record.get("phase"),
        "split": record.get("split"),
        "source": record.get("source"),
        "target": record.get("target"),
        "required_spans": record.get("required_spans"),
        "partial_tail": record.get("partial_tail"),
        "prediction": completion["content"],
        "prediction_after": completion["content"],
        "prompt": {
            "version": PROMPT_V6_SOURCE_LAST_VERSION,
            "rendered_prompt": prompt["rendered_prompt"],
            "hf_token_ids": prompt["hf_token_ids"],
            "native_token_ids": native_prompt_ids,
            "token_ids_match": True,
            "target_free": True,
        },
        "generation": dict(completion),
        "eos_reached": True,
        "truncated": False,
        "adapter_sha256": "none",
    }


def _effective_settings(settings: Mapping[str, Any]) -> dict[str, Any]:
    return {key: settings[key] for key in REPORTED_SETTINGS}


def _resume_server_identity_matches(
    source_identity: Any,
    current_identity: Mapping[str, Any],
) -> bool:
    """Compare restart provenance while tolerating llama.cpp's dynamic marker.

    ``/props.media_marker`` is regenerated for every server process. It may be
    ignored only when both captured identities explicitly report all three
    modalities as unavailable. Every other identity field remains strict, and
    neither input mapping is mutated so the raw ``/props`` payload stays in
    the journals.
    """

    if source_identity == current_identity:
        return True
    if not isinstance(source_identity, Mapping):
        return False
    source_props = source_identity.get("props")
    current_props = current_identity.get("props")
    if not isinstance(source_props, Mapping) or not isinstance(current_props, Mapping):
        return False
    source_modalities = source_props.get("modalities")
    current_modalities = current_props.get("modalities")
    required_modalities = ("vision", "video", "audio")
    if not (
        isinstance(source_modalities, Mapping)
        and isinstance(current_modalities, Mapping)
        and all(
            source_modalities.get(name) is False
            and current_modalities.get(name) is False
            for name in required_modalities
        )
    ):
        return False
    if "media_marker" not in source_props or "media_marker" not in current_props:
        return False
    source_without_marker = dict(source_identity)
    current_without_marker = dict(current_identity)
    source_props_without_marker = dict(source_props)
    current_props_without_marker = dict(current_props)
    source_props_without_marker.pop("media_marker")
    current_props_without_marker.pop("media_marker")
    source_without_marker["props"] = source_props_without_marker
    current_without_marker["props"] = current_props_without_marker
    return source_without_marker == current_without_marker


def _resume_state(
    resume_from: str | Path,
    *,
    records: Sequence[Mapping[str, Any]],
    scope: str,
    selection_sha256: str,
    records_sha256: str | None,
    server_identity: Mapping[str, Any],
    provenance: Mapping[str, Any],
    request_options: Mapping[str, Any],
    prompt_hashes: Mapping[str, str],
    diagnostic_only: bool,
) -> tuple[list[dict[str, Any]], set[str], dict[str, Any]]:
    source = Path(resume_from).expanduser()
    if source.is_symlink() or not source.is_dir():
        raise NativeClientError(f"resume source is absent or symlinked: {source}")
    events, journal_sha256 = _load_journal(source / "journal.jsonl")
    header = events[0]
    expected_ids = [record["id"] for record in records]
    source_ids = header.get("ids")
    if not isinstance(source_ids, list) or any(not isinstance(identifier, str) for identifier in source_ids):
        raise NativeClientError("resume journal has invalid IDs")
    if len(set(source_ids)) != len(source_ids):
        raise NativeClientError("resume journal has duplicate IDs")
    source_scope = header.get("scope")
    expansion = source_scope == "smoke12" and scope == "selection60"
    if expansion:
        if len(source_ids) != EXPECTED_SMOKE_COUNT or not set(source_ids).issubset(expected_ids):
            raise NativeClientError("smoke resume IDs are not a subset of selection60")
        if not any(event.get("kind") == "run_finished" and event.get("status") == "completed" for event in events):
            raise NativeClientError("selection60 expansion requires a completed smoke12 source")
    elif source_scope != scope or source_ids != expected_ids:
        raise NativeClientError("resume scope or IDs differ from the requested evaluation")
    source_prompt_hashes = header.get("prompt_hashes")
    if not isinstance(source_prompt_hashes, Mapping):
        raise NativeClientError("resume journal has no prompt hashes")
    prompt_hash_check = {
        identifier: prompt_hashes[identifier]
        for identifier in source_ids
        if identifier in prompt_hashes
    }
    if len(prompt_hash_check) != len(source_ids) or dict(source_prompt_hashes) != prompt_hash_check:
        raise NativeClientError("resume prompt hashes differ")
    checks = {
        "selection_sha256": selection_sha256,
        "records_sha256": records_sha256,
        "server_identity": dict(server_identity),
        "provenance": dict(provenance),
        "request_options": dict(request_options),
        "diagnostic_only": diagnostic_only,
    }
    for key, expected in checks.items():
        matches = (
            _resume_server_identity_matches(header.get(key), expected)
            if key == "server_identity"
            else header.get(key) == expected
        )
        if not matches:
            raise NativeClientError(f"resume provenance mismatch for {key}")
    if not expansion and any(
        event.get("kind") == "run_finished"
        and event.get("status") == "completed"
        and not event.get("imported_from")
        for event in events
    ):
        raise NativeClientError("resume source is already completed")
    completed_ids: set[str] = set()
    for event in events[1:]:
        kind = event.get("kind")
        identifier = event.get("id")
        if kind == "record_completed":
            if not isinstance(identifier, str) or not isinstance(event.get("row"), Mapping):
                raise NativeClientError("resume journal has an invalid completed record")
            completed_ids.add(identifier)
        elif kind == "record_failed":
            if not isinstance(identifier, str):
                raise NativeClientError("resume journal has an invalid failed record")
        elif kind in {"resume_imported", "resume_started", "run_finished"}:
            continue
        else:
            raise NativeClientError(f"resume journal has unknown event kind: {kind!r}")
    if any(identifier not in expected_ids for identifier in completed_ids):
        raise NativeClientError("resume journal contains an ID outside the requested selection")
    source_finished = any(
        event.get("kind") == "run_finished"
        and event.get("status") == "completed"
        and not event.get("imported_from")
        for event in events
    )
    if source_finished and completed_ids != set(source_ids):
        raise NativeClientError("completed resume journal does not cover every source ID")
    return events, completed_ids, {
        "source": str(source.resolve()),
        "journal_sha256": journal_sha256,
        "source_scope": source_scope,
        "scope_transition": "smoke12_to_selection60" if expansion else None,
    }


def _load_completed_rows(journal_path: Path, records: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    events, _ = _load_journal(journal_path)
    by_id: dict[str, dict[str, Any]] = {}
    for event in events:
        if event.get("kind") == "record_completed" and isinstance(event.get("row"), Mapping):
            identifier = event.get("id")
            if isinstance(identifier, str):
                by_id[identifier] = dict(event["row"])
    rows: list[dict[str, Any]] = []
    for record in records:
        identifier = record.get("id")
        if identifier not in by_id:
            raise NativeClientError(f"journal has no completed row for {identifier}")
        rows.append(by_id[identifier])
    return rows


def _write_final_outputs(
    output_dir: Path,
    rows: Sequence[Mapping[str, Any]],
    manifest: Mapping[str, Any],
) -> dict[str, Any]:
    predictions_path = output_dir / "predictions.jsonl"
    manifest_path = output_dir / "manifest.json"
    with predictions_path.open("x", encoding="utf-8", newline="") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
    with manifest_path.open("x", encoding="utf-8", newline="") as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    return {
        "status": "completed",
        "records": len(rows),
        "predictions": str(predictions_path),
        "manifest": str(manifest_path),
        "journal": str(output_dir / "journal.jsonl"),
    }


def run_records(
    records: Sequence[Mapping[str, Any]],
    *,
    tokenizer: Any,
    transport: Any,
    output_dir: str | Path,
    selection_sha256: str,
    scope: str,
    records_sha256: str | None = None,
    server_identity: Mapping[str, Any] | None = None,
    provenance: Mapping[str, Any] | None = None,
    resume_from: str | Path | None = None,
    diagnostic_only: bool = False,
) -> dict[str, Any]:
    """Run a pre-locked list with one durable journal event per record."""

    output_raw = Path(output_dir).expanduser()
    if output_raw.exists() or output_raw.is_symlink():
        raise NativeClientError(f"refusing to overwrite output directory: {output_raw}")
    output = output_raw.resolve()
    if output.exists() or output.is_symlink():
        raise NativeClientError(f"refusing to overwrite output directory: {output}")
    if scope not in {"smoke12", "selection60"}:
        raise NativeClientError(f"unsupported evaluation scope: {scope}")
    if not records:
        raise NativeClientError("cannot evaluate an empty record list")
    if not isinstance(server_identity, Mapping):
        raise NativeClientError("verified server identity is required")
    if not isinstance(provenance, Mapping):
        raise NativeClientError("verified GGUF/tokenizer/prompt provenance is required")
    if len({record.get("id") for record in records}) != len(records):
        raise NativeClientError("evaluation records contain duplicate IDs")

    request_options = dict(REQUEST_OPTIONS)
    prompt_hashes: dict[str, str] = {}
    prompt_cache: dict[str, dict[str, Any]] = {}
    for record in records:
        identifier = record.get("id")
        if not isinstance(identifier, str) or not identifier:
            raise NativeClientError("evaluation record has no ID")
        prompt = build_hf_prompt(tokenizer, record)
        prompt_hashes[identifier] = sha256_text(prompt["rendered_prompt"])
        prompt_cache[identifier] = prompt

    journal_path = output / "journal.jsonl"
    header = {
        "kind": "run_started",
        "scope": scope,
        "ids": [record["id"] for record in records],
        "selection_sha256": selection_sha256,
        "records_sha256": records_sha256,
        "server_identity": dict(server_identity),
        "provenance": dict(provenance),
        "request_options": request_options,
        "prompt_hashes": prompt_hashes,
        "diagnostic_only": bool(diagnostic_only),
    }
    completed_ids: set[str] = set()
    resume_metadata: dict[str, Any] | None = None
    source_events: list[dict[str, Any]] = []
    if resume_from is not None:
        source_events, completed_ids, resume_metadata = _resume_state(
            resume_from,
            records=records,
            scope=scope,
            selection_sha256=selection_sha256,
            records_sha256=records_sha256,
            server_identity=server_identity,
            provenance=provenance,
            request_options=request_options,
            prompt_hashes=prompt_hashes,
            diagnostic_only=diagnostic_only,
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir()
    effective_decoding: dict[str, Any] | None = None
    with journal_path.open("x", encoding="utf-8", newline="") as journal:
        _append_json_line(journal, header)
        if source_events:
            _append_json_line(
                journal,
                {
                    "kind": "resume_imported",
                    **(resume_metadata or {}),
                    "source_event_count": len(source_events),
                },
            )
            for event in source_events[1:]:
                imported = dict(event)
                imported["imported_from"] = (resume_metadata or {}).get("source")
                imported["source_journal_sha256"] = (resume_metadata or {}).get("journal_sha256")
                _append_json_line(journal, imported)
            _append_json_line(journal, {"kind": "resume_started", **(resume_metadata or {})})
            for event in source_events:
                settings = event.get("effective_decoding")
                if isinstance(settings, Mapping):
                    current = dict(settings)
                    if effective_decoding is None:
                        effective_decoding = current
                    elif current != effective_decoding:
                        raise NativeClientError("resume journal has inconsistent effective settings")
        for record in records:
            identifier = record["id"]
            if identifier in completed_ids:
                continue
            prompt = prompt_cache[identifier]
            prompt_hash = sha256_text(prompt["rendered_prompt"])
            try:
                if prompt_hash != prompt_hashes[identifier]:
                    raise NativeClientError(f"prompt changed before request for {identifier}")
                tokenize_response = transport.post(
                    "/tokenize",
                    {"content": prompt["rendered_prompt"], "add_special": False, "parse_special": True},
                )
                native_prompt_ids = _token_ids(tokenize_response.get("tokens"), "native prompt token IDs")
                if native_prompt_ids != prompt["hf_token_ids"]:
                    raise NativeClientError(f"native and HF token IDs differ for {identifier}")
                completion = validate_completion_response(
                    transport.post("/completion", build_completion_payload(native_prompt_ids))
                )
                current_decoding = _effective_settings(completion["generation_settings"])
                if effective_decoding is None:
                    effective_decoding = current_decoding
                elif current_decoding != effective_decoding:
                    raise NativeClientError(f"effective generation settings changed during {identifier}")
                row = _record_row(
                    record,
                    selection_sha256=selection_sha256,
                    prompt=prompt,
                    native_prompt_ids=native_prompt_ids,
                    completion=completion,
                )
                completed_ids.add(identifier)
                _append_json_line(
                    journal,
                    {
                        "kind": "record_completed",
                        "status": "completed",
                        "id": identifier,
                        "selection_index": record.get("selection_index"),
                        "prompt_sha256": prompt_hash,
                        "effective_decoding": current_decoding,
                        "row": row,
                    },
                )
            except (NativeClientError, OSError, TypeError, ValueError) as error:
                _append_json_line(
                    journal,
                    {
                        "kind": "record_failed",
                        "status": "failed",
                        "id": identifier,
                        "selection_index": record.get("selection_index"),
                        "prompt_sha256": prompt_hash,
                        "error": f"{type(error).__name__}: {error}",
                    },
                )
                raise

    rows = _load_completed_rows(journal_path, records)
    prompt_provenance = _prompt_provenance()
    manifest = {
        "status": "completed",
        "scope": scope,
        "records": len(rows),
        "ids": [row["id"] for row in rows],
        "selection": {
            "sha256": selection_sha256,
            "records": EXPECTED_SELECTION_COUNT,
            "test_read": False,
            "selection_test_used": False,
        },
        "valid_source": {"sha256": records_sha256, "records": EXPECTED_RECORD_COUNT},
        "prompt": prompt_provenance,
        "provenance": dict(provenance),
        "transport": {
            "endpoints": ["/tokenize", "/completion"],
            "native_prompt_ids_from_hf": True,
            "prompt_target_free": True,
            "redirects": "refused",
            "proxies": "disabled",
        },
        "server_identity": dict(server_identity),
        "request_options": request_options,
        "effective_decoding": effective_decoding,
        "resume": resume_metadata,
        "quality": {
            "semantic_quality_claim": False,
            "mobile_latency_validated": False,
            "diagnostic_only": bool(diagnostic_only),
        },
    }
    result = _write_final_outputs(output, rows, manifest)
    with journal_path.open("a", encoding="utf-8", newline="") as journal:
        _append_json_line(journal, {"kind": "run_finished", "status": "completed", "records": len(rows)})
    return result


def run_evaluation(
    *,
    output_dir: str | Path,
    gguf_path: str | Path | None = None,
    gguf_sha256: str | None = None,
    selection_path: str | Path = DEFAULT_SELECTION,
    records_path: str | Path = DEFAULT_RECORDS,
    tokenizer_path: str | Path = DEFAULT_TOKENIZER,
    tokenizer_sha256: str | None = None,
    prompt_v6_sha256: str = EXPECTED_PROMPT_V6_SHA256,
    prompt_v6_source_last_sha256: str = EXPECTED_PROMPT_V6_SOURCE_LAST_SHA256,
    base_url: str = DEFAULT_BASE_URL,
    expected_model_path: str | None = None,
    expected_model_alias: str | None = None,
    smoke_only: bool = False,
    resume_from: str | Path | None = None,
    diagnostic_only: bool = False,
    tokenizer: Any | None = None,
    transport: Any | None = None,
) -> dict[str, Any]:
    records, selection_sha256, records_sha256 = load_locked_records(selection_path, records_path)
    selected = select_smoke_records(records) if smoke_only else records
    if smoke_only and len(selected) != EXPECTED_SMOKE_COUNT:
        raise NativeClientError("smoke selection must contain exactly 12 records")
    if not smoke_only and len(selected) != EXPECTED_SELECTION_COUNT:
        raise NativeClientError("full selection must contain exactly 60 records")
    if gguf_path is None or gguf_sha256 is None:
        raise NativeClientError("GGUF path and SHA-256 are required before requests")
    if tokenizer_sha256 is None:
        raise NativeClientError("tokenizer SHA-256 is required before requests")
    provenance = verify_provenance(
        gguf_path,
        gguf_sha256,
        tokenizer_path,
        tokenizer_sha256,
        prompt_v6_sha256=prompt_v6_sha256,
        prompt_v6_source_last_sha256=prompt_v6_source_last_sha256,
    )
    actual_tokenizer = tokenizer if tokenizer is not None else _load_tokenizer(Path(tokenizer_path).expanduser().resolve())
    actual_transport = transport if transport is not None else LocalLlamaServerTransport(base_url)
    server_identity = verify_server_identity(
        actual_transport,
        expected_model_path=expected_model_path,
        expected_model_alias=expected_model_alias,
    )
    return run_records(
        selected,
        tokenizer=actual_tokenizer,
        transport=actual_transport,
        output_dir=output_dir,
        selection_sha256=selection_sha256,
        records_sha256=records_sha256,
        server_identity=server_identity,
        scope="smoke12" if smoke_only else "selection60",
        provenance=provenance,
        resume_from=resume_from,
        diagnostic_only=diagnostic_only,
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new output directory")
    parser.add_argument("--selection", type=Path, default=DEFAULT_SELECTION)
    parser.add_argument("--records", type=Path, default=DEFAULT_RECORDS)
    parser.add_argument("--tokenizer", type=Path, default=DEFAULT_TOKENIZER)
    parser.add_argument("--tokenizer-sha256", required=True, help="SHA-256 of the complete tokenizer directory")
    parser.add_argument("--gguf", type=Path, required=True, help="GGUF file loaded by the already-running server")
    parser.add_argument("--gguf-sha256", required=True, help="SHA-256 of the GGUF file")
    parser.add_argument("--prompt-v6-sha256", default=EXPECTED_PROMPT_V6_SHA256)
    parser.add_argument("--prompt-v6-source-last-sha256", default=EXPECTED_PROMPT_V6_SOURCE_LAST_SHA256)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    identity = parser.add_mutually_exclusive_group(required=True)
    identity.add_argument("--expected-model-path")
    identity.add_argument("--expected-model-alias")
    parser.add_argument("--smoke", action="store_true", help="run the deterministic 12-record smoke selection")
    parser.add_argument("--resume-from", type=Path, help="immutable failed run directory to resume into --output")
    parser.add_argument(
        "--diagnostic-only",
        action="store_true",
        help="mark this run as a diagnostic; record count does not determine this flag",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        result = run_evaluation(
            output_dir=args.output,
            gguf_path=args.gguf,
            gguf_sha256=args.gguf_sha256,
            selection_path=args.selection,
            records_path=args.records,
            tokenizer_path=args.tokenizer,
            tokenizer_sha256=args.tokenizer_sha256,
            prompt_v6_sha256=args.prompt_v6_sha256,
            prompt_v6_source_last_sha256=args.prompt_v6_source_last_sha256,
            base_url=args.base_url,
            expected_model_path=args.expected_model_path,
            expected_model_alias=args.expected_model_alias,
            smoke_only=args.smoke,
            resume_from=args.resume_from,
            diagnostic_only=args.diagnostic_only,
        )
    except (NativeClientError, OSError, TypeError, ValueError) as error:
        print(f"GGUF native evaluation refused: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
