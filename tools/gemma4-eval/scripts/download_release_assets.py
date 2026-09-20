#!/usr/bin/env python3
"""Download signed GitHub release assets without retaining a second model copy.

Release metadata is listed through the GitHub API; binary bodies are streamed
through ``gh api`` stdout.  The token is supplied only as the child process's
``GH_TOKEN`` environment value and is never included in argv, metadata, or
error messages.  Direct urllib redirects to a different GitHub CDN host still
drop the Authorization header.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import select
import sys
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse


ALLOWED_API_HOST = "api.github.com"
ALLOWED_REDIRECT_HOSTS = frozenset(
    {
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    }
)
GH_API_CHUNK_BYTES = 8 * 1024 * 1024
GH_API_TIMEOUT_SECONDS = 300.0
GH_API_STDERR_MAX_BYTES = 64 * 1024


class AssetDownloadError(RuntimeError):
    """Raised when a release asset cannot be verified safely."""


def _safe_url(url: str, *, allow_cdn: bool = False) -> None:
    parsed = urlparse(url)
    allowed = ALLOWED_REDIRECT_HOSTS if allow_cdn else frozenset({ALLOWED_API_HOST})
    if parsed.scheme != "https" or parsed.hostname not in allowed:
        raise AssetDownloadError(f"refusing non-GitHub HTTPS URL: {parsed.hostname!r}")


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Keep auth on api.github.com only and strip it on CDN redirects."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        _safe_url(newurl, allow_cdn=True)
        old_host = urlparse(req.full_url).hostname
        new_host = urlparse(newurl).hostname
        request_headers = {
            key: value
            for key, value in req.header_items()
            if key.lower() != "authorization"
        }
        if old_host == new_host:
            authorization = req.get_header("Authorization")
            if authorization:
                request_headers["Authorization"] = authorization
        return urllib.request.Request(
            newurl,
            headers=request_headers,
            method=req.get_method(),
        )


def _opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(SafeRedirectHandler())


def _request(url: str, token: str, *, accept: str) -> urllib.request.Request:
    _safe_url(url, allow_cdn=False)
    if not token:
        raise AssetDownloadError("GITHUB_TOKEN is required")
    return urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": accept,
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "gemma4-q6-evaluation",
        },
        method="GET",
    )


def _sha256_stream(response, output, *, max_bytes: int | None = None) -> tuple[str, int]:  # type: ignore[no-untyped-def]
    digest = hashlib.sha256()
    total = 0
    while True:
        block = response.read(8 * 1024 * 1024)
        if not block:
            break
        total += len(block)
        if max_bytes is not None and total > max_bytes:
            raise AssetDownloadError("asset exceeds its declared size")
        output.write(block)
        digest.update(block)
    return digest.hexdigest(), total


def _drain_stderr(stream, buffer: bytearray) -> None:  # type: ignore[no-untyped-def]
    """Drain child diagnostics without retaining or exposing untrusted text."""
    try:
        while True:
            block = stream.read(4096)
            if not block:
                return
            remaining = GH_API_STDERR_MAX_BYTES - len(buffer)
            if remaining > 0:
                buffer.extend(block[:remaining])
    except (OSError, ValueError):
        return


def _http_status_from_stderr(buffer: bytearray) -> int | None:
    match = re.search(rb"\bHTTP(?:Error)?\s+(\d{3})\b", bytes(buffer))
    return int(match.group(1)) if match else None


def _terminate_owned_process(process) -> None:  # type: ignore[no-untyped-def]
    """Stop only the gh child created by this downloader."""
    if process.poll() is not None:
        return
    try:
        process.terminate()
    except (OSError, ProcessLookupError):
        return
    try:
        process.wait(timeout=5.0)
    except subprocess.TimeoutExpired:
        try:
            process.kill()
        except (OSError, ProcessLookupError):
            return
        try:
            process.wait(timeout=5.0)
        except (OSError, subprocess.TimeoutExpired):
            return


def _run_gh_api_stream(
    url: str,
    token: str,
    sink,
    *,
    expected_sha256: str | None = None,
    expected_bytes: int | None = None,
    popen=subprocess.Popen,
    select_fn=select.select,
    clock=time.monotonic,
    timeout_seconds: float = GH_API_TIMEOUT_SECONDS,
) -> dict[str, object]:  # type: ignore[no-untyped-def]
    """Stream one API asset through gh without buffering its body in Python."""
    _safe_url(url, allow_cdn=False)
    if not token:
        raise AssetDownloadError("GITHUB_TOKEN is required")
    command = ["gh", "api", url, "--header", "Accept: application/octet-stream"]
    environment = os.environ.copy()
    environment["GH_TOKEN"] = token
    process = None
    stderr_buffer = bytearray()
    stderr_thread = None
    digest = hashlib.sha256()
    total = 0
    try:
        try:
            process = popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=environment,
                close_fds=True,
                start_new_session=True,
                bufsize=0,
            )
        except OSError as error:
            raise AssetDownloadError("gh api executable unavailable") from error
        if process.stdout is None or process.stderr is None:
            raise AssetDownloadError("gh api did not provide stream handles")
        stderr_thread = threading.Thread(
            target=_drain_stderr,
            args=(process.stderr, stderr_buffer),
            daemon=True,
        )
        stderr_thread.start()
        deadline = clock() + timeout_seconds
        while True:
            remaining = deadline - clock()
            if remaining <= 0:
                raise AssetDownloadError(f"gh api asset transfer timed out after {timeout_seconds:g} seconds")
            readable, _, _ = select_fn([process.stdout], [], [], remaining)
            if not readable:
                raise AssetDownloadError(f"gh api asset transfer timed out after {timeout_seconds:g} seconds")
            block = process.stdout.read(GH_API_CHUNK_BYTES)
            if not block:
                break
            total += len(block)
            if expected_bytes is not None and total > expected_bytes:
                raise AssetDownloadError("asset exceeds its declared size")
            sink(block)
            digest.update(block)
        remaining = deadline - clock()
        if remaining <= 0:
            raise AssetDownloadError(f"gh api asset transfer timed out after {timeout_seconds:g} seconds")
        try:
            exit_code = process.wait(timeout=remaining)
        except subprocess.TimeoutExpired as error:
            raise AssetDownloadError(f"gh api asset transfer timed out after {timeout_seconds:g} seconds") from error
        if stderr_thread is not None:
            stderr_thread.join(timeout=1.0)
        if exit_code != 0:
            http_status = _http_status_from_stderr(stderr_buffer)
            status_text = str(http_status) if http_status is not None else "unknown"
            raise AssetDownloadError(
                f"gh api asset transfer failed: exit_code={exit_code} http_status={status_text}"
            )
    except AssetDownloadError:
        raise
    except (OSError, ValueError) as error:
        raise AssetDownloadError(f"gh api asset stream failed: {type(error).__name__}") from error
    finally:
        if process is not None:
            _terminate_owned_process(process)
            for stream_name in ("stdout", "stderr"):
                stream = getattr(process, stream_name, None)
                if stream is not None:
                    try:
                        stream.close()
                    except (OSError, ValueError):
                        pass
        if stderr_thread is not None:
            stderr_thread.join(timeout=1.0)
    actual_sha256 = digest.hexdigest()
    if expected_bytes is not None and total != expected_bytes:
        raise AssetDownloadError(f"asset byte count differs: expected {expected_bytes}, got {total}")
    if expected_sha256 is not None and actual_sha256 != expected_sha256.lower():
        raise AssetDownloadError(f"asset SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256}")
    return {"bytes": total, "sha256": actual_sha256}


def _download_to_new_path(
    opener: urllib.request.OpenerDirector | None,
    url: str,
    destination: Path,
    token: str,
    *,
    expected_sha256: str | None = None,
    expected_bytes: int | None = None,
    popen=subprocess.Popen,
    select_fn=select.select,
    clock=time.monotonic,
    timeout_seconds: float = GH_API_TIMEOUT_SECONDS,
) -> dict[str, object]:
    if destination.exists() or destination.is_symlink():
        raise AssetDownloadError(f"refusing to overwrite {destination}")
    partial = destination.with_name(destination.name + ".partial")
    if partial.exists() or partial.is_symlink():
        raise AssetDownloadError(f"refusing to reuse partial download {partial}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        with partial.open("xb") as output:
            result = _run_gh_api_stream(
                url,
                token,
                output.write,
                expected_sha256=expected_sha256,
                expected_bytes=expected_bytes,
                popen=popen,
                select_fn=select_fn,
                clock=clock,
                timeout_seconds=timeout_seconds,
            )
            output.flush()
            os.fsync(output.fileno())
    except AssetDownloadError:
        raise
    except OSError as error:
        raise AssetDownloadError(f"asset download failed: {type(error).__name__}") from error
    os.replace(partial, destination)
    return {"path": str(destination), **result}


def _check_server_digest(asset: dict[str, object], expected_sha256: str, label: str) -> None:
    digest = asset.get("server_digest")
    if digest is None:
        return
    if not isinstance(digest, str) or not digest.startswith("sha256:"):
        raise AssetDownloadError(f"{label} has an unsupported server digest")
    server_sha256 = digest.removeprefix("sha256:").lower()
    if server_sha256 != expected_sha256.lower():
        raise AssetDownloadError(f"{label} server digest differs from its pinned SHA-256")


def _append_response(
    opener: urllib.request.OpenerDirector | None,
    url: str,
    output,
    token: str,
    *,
    expected_sha256: str,
    expected_bytes: int,
    global_digest: hashlib._Hash,
    popen=subprocess.Popen,
    select_fn=select.select,
    clock=time.monotonic,
    timeout_seconds: float = GH_API_TIMEOUT_SECONDS,
) -> dict[str, object]:  # type: ignore[attr-defined,no-untyped-def]
    def sink(block: bytes) -> None:
        output.write(block)
        global_digest.update(block)

    return _run_gh_api_stream(
        url,
        token,
        sink,
        expected_sha256=expected_sha256,
        expected_bytes=expected_bytes,
        popen=popen,
        select_fn=select_fn,
        clock=clock,
        timeout_seconds=timeout_seconds,
    )


def _release_assets(opener, repo: str, release_id: str, token: str) -> dict[str, dict[str, object]]:  # type: ignore[no-untyped-def]
    if not release_id.isdigit():
        raise AssetDownloadError("release_id must be numeric; tags are not accepted")
    url = f"https://{ALLOWED_API_HOST}/repos/{repo}/releases/{release_id}/assets?per_page=100"
    try:
        with opener.open(_request(url, token, accept="application/vnd.github+json"), timeout=30) as response:
            payload = json.load(response)
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        raise AssetDownloadError(f"release asset listing failed: {type(error).__name__}") from error
    if not isinstance(payload, list):
        raise AssetDownloadError("release asset listing is not an array")
    result: dict[str, dict[str, object]] = {}
    for asset in payload:
        if not isinstance(asset, dict) or not isinstance(asset.get("name"), str):
            raise AssetDownloadError("release asset listing contains an invalid entry")
        if asset["name"] in result:
            raise AssetDownloadError(f"duplicate release asset name: {asset['name']}")
        url = asset.get("url")
        if not isinstance(url, str):
            raise AssetDownloadError(f"release asset has no API URL: {asset['name']}")
        _safe_url(url)
        result[asset["name"]] = {
            "name": asset["name"],
            "url": url,
            "bytes": asset.get("size"),
            "server_digest": asset.get("digest"),
        }
    return result


def download_model_from_release(
    *,
    repo: str,
    release_id: str,
    manifest_name: str,
    manifest_destination: Path,
    manifest_sha256: str,
    assembled_destination: Path,
    expected_source_sha256: str,
    expected_source_bytes: int,
    token: str,
) -> dict[str, object]:
    opener = _opener()
    assets = _release_assets(opener, repo, release_id, token)
    manifest_asset = assets.get(manifest_name)
    if manifest_asset is None:
        raise AssetDownloadError(f"missing release asset: {manifest_name}")
    _check_server_digest(manifest_asset, manifest_sha256, "model asset manifest")
    _download_to_new_path(
        opener,
        str(manifest_asset["url"]),
        manifest_destination,
        token,
        expected_sha256=manifest_sha256,
        expected_bytes=int(manifest_asset["bytes"]),
    )
    manifest = json.loads(manifest_destination.read_text(encoding="utf-8"))
    parts = manifest.get("parts") if isinstance(manifest, dict) else None
    if not isinstance(parts, list) or not parts:
        raise AssetDownloadError("model asset manifest has no parts")
    if manifest.get("source_sha256") != expected_source_sha256 or manifest.get("total_bytes") != expected_source_bytes:
        raise AssetDownloadError("model asset manifest source identity differs")
    if assembled_destination.exists() or assembled_destination.is_symlink():
        raise AssetDownloadError(f"refusing to overwrite assembled model: {assembled_destination}")
    assembled_destination.parent.mkdir(parents=True, exist_ok=True)
    global_digest = hashlib.sha256()
    total = 0
    with assembled_destination.open("xb") as output:
        for index, part in enumerate(parts):
            if not isinstance(part, dict) or part.get("index") != index:
                raise AssetDownloadError("model asset manifest parts are not sequential")
            name = part.get("name")
            part_url = assets.get(name) if isinstance(name, str) else None
            if part_url is None:
                raise AssetDownloadError(f"missing release asset part: {name!r}")
            _check_server_digest(part_url, str(part.get("sha256")), f"model part {name}")
            part_result = _append_response(
                opener,
                str(part_url["url"]),
                output,
                token,
                expected_sha256=str(part.get("sha256")),
                expected_bytes=int(part.get("bytes")),
                global_digest=global_digest,
            )
            total += int(part_result["bytes"])
        output.flush()
        os.fsync(output.fileno())
    actual = global_digest.hexdigest()
    if total != expected_source_bytes or actual != expected_source_sha256:
        raise AssetDownloadError("assembled model identity differs from the pinned source")
    return {
        "manifest": {"path": str(manifest_destination), "sha256": manifest_sha256},
        "assembled": {"path": str(assembled_destination), "bytes": total, "sha256": actual},
        "parts": len(parts),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--manifest-name", required=True)
    parser.add_argument("--manifest-destination", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--assembled-destination", type=Path, required=True)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--source-bytes", type=int, required=True)
    args = parser.parse_args(argv)
    token = os.environ.get("GH_TOKEN", "")
    try:
        result = download_model_from_release(
            repo=args.repo,
            release_id=args.release_id,
            manifest_name=args.manifest_name,
            manifest_destination=args.manifest_destination,
            manifest_sha256=args.manifest_sha256,
            assembled_destination=args.assembled_destination,
            expected_source_sha256=args.source_sha256,
            expected_source_bytes=args.source_bytes,
            token=token,
        )
    except (AssetDownloadError, OSError, ValueError) as error:
        print(f"Q6 asset download refused: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
