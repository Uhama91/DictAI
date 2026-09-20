from __future__ import annotations

import importlib.util
import hashlib
import io
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from urllib.request import Request


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("download_release_assets", ROOT / "scripts/download_release_assets.py")
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
sys.modules["download_release_assets"] = module
SPEC.loader.exec_module(module)


class _FakeStream:
    def __init__(self, chunks: list[bytes], error: BaseException | None = None):
        self._chunks = list(chunks)
        self._error = error

    def read(self, _size: int = -1) -> bytes:
        if self._error is not None:
            raise self._error
        if self._chunks:
            return self._chunks.pop(0)
        return b""

    def close(self) -> None:
        return None


class _FakeProcess:
    def __init__(
        self,
        payload: bytes,
        *,
        returncode: int | None = 0,
        stderr: bytes = b"",
        read_error: BaseException | None = None,
    ):
        self.stdout = _FakeStream([payload], read_error)
        self.stderr = _FakeStream([stderr])
        self.returncode = returncode
        self.terminated = False
        self.killed = False
        self.wait_calls: list[float | None] = []

    def poll(self) -> int | None:
        return self.returncode

    def wait(self, timeout: float | None = None) -> int:
        self.wait_calls.append(timeout)
        if self.returncode is None:
            self.returncode = 0
        return self.returncode

    def terminate(self) -> None:
        self.terminated = True
        self.returncode = -15

    def kill(self) -> None:
        self.killed = True
        self.returncode = -9


def _ready(readables, _writable, _exceptional, _timeout):
    return readables, [], []


def _never_ready(_readables, _writable, _exceptional, _timeout):
    return [], [], []


def _popen_for(process: _FakeProcess, calls: list[tuple[list[str], dict[str, object]]]):
    def popen(command, **kwargs):
        calls.append((command, kwargs))
        return process

    return popen


class ReleaseDownloadContractTests(unittest.TestCase):
    def test_api_redirect_drops_authorization_on_cdn(self):
        request = Request(
            "https://api.github.com/repos/example/repo/releases/1/assets/2",
            headers={"Authorization": "Bearer secret", "Accept": "application/octet-stream"},
        )
        redirected = module.SafeRedirectHandler().redirect_request(
            request,
            None,
            302,
            "found",
            {},
            "https://objects.githubusercontent.com/release-asset/signed",
        )
        self.assertIsNotNone(redirected)
        self.assertIsNone(redirected.get_header("Authorization"))

    def test_same_api_host_keeps_authorization_and_external_host_is_rejected(self):
        request = Request(
            "https://api.github.com/repos/example/repo/releases/1/assets/2",
            headers={"Authorization": "Bearer secret"},
        )
        redirected = module.SafeRedirectHandler().redirect_request(
            request,
            None,
            307,
            "temporary",
            {},
            "https://api.github.com/repos/example/repo/releases/1/assets/2?page=2",
        )
        self.assertEqual(redirected.get_header("Authorization"), "Bearer secret")
        with self.assertRaises(module.AssetDownloadError):
            module._safe_url("http://example.com/file", allow_cdn=True)

    def test_release_id_must_be_numeric(self):
        with self.assertRaises(module.AssetDownloadError):
            module._release_assets(None, "repo", "draft-tag", "secret")

    def test_server_digest_mismatch_is_rejected(self):
        with self.assertRaises(module.AssetDownloadError):
            module._check_server_digest(
                {"server_digest": "sha256:" + "0" * 64},
                "1" * 64,
                "asset",
            )

    def test_gh_api_stream_verifies_bytes_and_never_puts_token_in_argv(self):
        payload = b"manifest-bytes"
        process = _FakeProcess(payload, stderr=b"ignored diagnostic https://signed.example/secret")
        calls: list[tuple[list[str], dict[str, object]]] = []
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "manifest.json"
            result = module._download_to_new_path(
                None,
                "https://api.github.com/repos/example/repo/releases/assets/2",
                destination,
                "secret-token",
                expected_sha256=hashlib.sha256(payload).hexdigest(),
                expected_bytes=len(payload),
                popen=_popen_for(process, calls),
                select_fn=_ready,
                clock=lambda: 0.0,
                timeout_seconds=30.0,
            )
            self.assertEqual(destination.read_bytes(), payload)
            self.assertEqual(result["bytes"], len(payload))
        self.assertEqual(calls[0][0][:2], ["gh", "api"])
        self.assertNotIn("secret-token", calls[0][0])
        self.assertEqual(calls[0][1]["env"]["GH_TOKEN"], "secret-token")
        self.assertEqual(calls[0][1]["bufsize"], 0)

    def test_real_pipe_writing_one_byte_then_stalling_hits_deadline_and_kills_child(self):
        children: list[subprocess.Popen] = []

        def controlled_popen(_command, **kwargs):
            child = subprocess.Popen(
                [
                    sys.executable,
                    "-c",
                    "import sys, time; sys.stdout.buffer.write(b'x'); sys.stdout.flush(); time.sleep(5)",
                ],
                **kwargs,
            )
            children.append(child)
            return child

        started = time.monotonic()
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "manifest.json"
            with self.assertRaisesRegex(module.AssetDownloadError, "timed out"):
                module._download_to_new_path(
                    None,
                    "https://api.github.com/repos/example/repo/releases/assets/2",
                    destination,
                    "secret-token",
                    popen=controlled_popen,
                    timeout_seconds=0.2,
                )
            self.assertFalse(destination.exists())
        elapsed = time.monotonic() - started
        self.assertEqual(len(children), 1)
        self.assertLess(elapsed, 2.0)
        self.assertIsNotNone(children[0].returncode)
        self.assertLess(children[0].returncode, 0)

    def test_gh_api_failure_is_sanitized_but_keeps_exit_and_http_code(self):
        process = _FakeProcess(
            b"",
            returncode=1,
            stderr=b"gh: HTTP 404: Not Found https://signed.example/private secret-token",
        )
        calls: list[tuple[list[str], dict[str, object]]] = []
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(module.AssetDownloadError, r"exit_code=1.*http_status=404") as raised:
                module._download_to_new_path(
                    None,
                    "https://api.github.com/repos/example/repo/releases/assets/2",
                    Path(directory) / "manifest.json",
                    "secret-token",
                    popen=_popen_for(process, calls),
                    select_fn=_ready,
                    clock=lambda: 0.0,
                    timeout_seconds=30.0,
                )
        self.assertNotIn("signed.example", str(raised.exception))
        self.assertNotIn("secret-token", str(raised.exception))

    def test_gh_api_timeout_terminates_owned_child_and_does_not_finalize_output(self):
        process = _FakeProcess(b"partial", returncode=None)
        calls: list[tuple[list[str], dict[str, object]]] = []
        times = iter((0.0, 0.0, 2.0))
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "manifest.json"
            with self.assertRaisesRegex(module.AssetDownloadError, "timed out"):
                module._download_to_new_path(
                    None,
                    "https://api.github.com/repos/example/repo/releases/assets/2",
                    destination,
                    "secret-token",
                    popen=_popen_for(process, calls),
                    select_fn=_never_ready,
                    clock=lambda: next(times),
                    timeout_seconds=1.0,
                )
            self.assertFalse(destination.exists())
        self.assertTrue(process.terminated or process.killed)
        self.assertTrue(process.wait_calls)

    def test_gh_api_stream_exception_cleans_up_owned_child(self):
        process = _FakeProcess(b"", returncode=None, read_error=OSError("broken pipe"))
        calls: list[tuple[list[str], dict[str, object]]] = []
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(module.AssetDownloadError):
                module._download_to_new_path(
                    None,
                    "https://api.github.com/repos/example/repo/releases/assets/2",
                    Path(directory) / "manifest.json",
                    "secret-token",
                    popen=_popen_for(process, calls),
                    select_fn=_ready,
                    clock=lambda: 0.0,
                    timeout_seconds=30.0,
                )
        self.assertTrue(process.terminated or process.killed)

    def test_model_part_stream_updates_assembled_sink_and_global_digest(self):
        payload = b"part-zero"
        process = _FakeProcess(payload)
        calls: list[tuple[list[str], dict[str, object]]] = []
        assembled = io.BytesIO()
        global_digest = hashlib.sha256()
        result = module._append_response(
            None,
            "https://api.github.com/repos/example/repo/releases/assets/3",
            assembled,
            "secret-token",
            expected_sha256=hashlib.sha256(payload).hexdigest(),
            expected_bytes=len(payload),
            global_digest=global_digest,
            popen=_popen_for(process, calls),
            select_fn=_ready,
            clock=lambda: 0.0,
            timeout_seconds=30.0,
        )
        self.assertEqual(assembled.getvalue(), payload)
        self.assertEqual(result, {"bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()})
        self.assertEqual(global_digest.hexdigest(), hashlib.sha256(payload).hexdigest())

    def test_missing_token_is_rejected_before_spawning_gh(self):
        spawned = False

        def unexpected_popen(*_args, **_kwargs):
            nonlocal spawned
            spawned = True
            raise AssertionError("gh must not start without GH_TOKEN")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(module.AssetDownloadError, "GITHUB_TOKEN is required"):
                module._download_to_new_path(
                    None,
                    "https://api.github.com/repos/example/repo/releases/assets/2",
                    Path(directory) / "manifest.json",
                    "",
                    popen=unexpected_popen,
                    select_fn=_ready,
                    clock=lambda: 0.0,
                )
        self.assertFalse(spawned)


if __name__ == "__main__":
    unittest.main()
