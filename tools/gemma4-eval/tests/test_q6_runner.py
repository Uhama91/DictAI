from __future__ import annotations

import importlib.util
import json
import signal
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("q6_runner", ROOT / "q6_runner.py")
assert SPEC is not None and SPEC.loader is not None
q6 = importlib.util.module_from_spec(SPEC)
sys.modules["q6_runner"] = q6
SPEC.loader.exec_module(q6)


class FakeProcess:
    def __init__(self, returncode: int | None = None, *, fail_terminate: bool = False) -> None:
        self.returncode = returncode
        self.pid = None
        self.terminated = False
        self.killed = False
        self.fail_terminate = fail_terminate

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        if self.returncode is None:
            self.returncode = 0
        return self.returncode

    def terminate(self):
        if self.fail_terminate:
            raise RuntimeError("synthetic cleanup failure")
        self.terminated = True
        self.returncode = -signal.SIGTERM

    def kill(self):
        self.killed = True
        self.returncode = -signal.SIGKILL


class AdvancingClock:
    def __init__(self) -> None:
        self.value = 0.0

    def __call__(self) -> float:
        self.value += 1.0
        return self.value


class Q6RunnerContractTests(unittest.TestCase):
    def test_preflight_requires_eight_gib_ram_and_seven_gib_disk(self):
        good = q6.ResourceSnapshot(8 * 1024**3, 7 * 1024**3, 0)
        q6.validate_preflight(good)
        with self.assertRaises(q6.Q6RunnerError):
            q6.validate_preflight(q6.ResourceSnapshot(8 * 1024**3 - 1, 7 * 1024**3, 0))
        with self.assertRaises(q6.Q6RunnerError):
            q6.validate_preflight(q6.ResourceSnapshot(8 * 1024**3, 7 * 1024**3 - 1, 0))

    def test_hash_mismatch_and_destination_refusal(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source.bin"
            source.write_bytes(b"source")
            with self.assertRaises(q6.Q6RunnerError):
                q6.verify_file_sha(source, "0" * 64, "source")
            destination = root / "output"
            destination.mkdir()
            with self.assertRaises(q6.Q6RunnerError):
                q6.require_new_path(destination, "output")

    def test_input_provenance_is_verified_before_a_run(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            files = {}
            for name in ("client.py", "selection.json", "records.jsonl", "prompt.py", "source_last.py", "model.gguf"):
                path = root / name
                path.write_bytes(name.encode("ascii"))
                files[name] = q6.sha256_file(path)
            tokenizer = root / "tokenizer"
            tokenizer.mkdir()
            (tokenizer / "tokenizer.json").write_bytes(b"tokenizer")
            artifacts = q6.verify_evaluation_inputs(
                client=root / "client.py",
                client_sha256=files["client.py"],
                selection=root / "selection.json",
                selection_sha256=files["selection.json"],
                records=root / "records.jsonl",
                records_sha256=files["records.jsonl"],
                prompt_v6=root / "prompt.py",
                prompt_v6_sha256=files["prompt.py"],
                prompt_v6_source_last=root / "source_last.py",
                prompt_v6_source_last_sha256=files["source_last.py"],
                tokenizer=tokenizer,
                tokenizer_sha256=q6.sha256_directory(tokenizer),
                model=root / "model.gguf",
                model_sha256=files["model.gguf"],
            )
            self.assertEqual(set(artifacts), {"client", "selection", "records", "prompt_v6", "prompt_v6_source_last", "tokenizer", "model"})

    def test_parts_manifest_rejects_order_size_and_hash_errors(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            first = root / "model.part-000"
            second = root / "model.part-001"
            first.write_bytes(b"abc")
            second.write_bytes(b"def")
            manifest = q6.make_parts_manifest([first, second], source_sha256=None)
            self.assertEqual(manifest["total_bytes"], 6)
            q6.verify_parts_manifest([first, second], manifest)
            broken = dict(manifest)
            broken["parts"] = list(reversed(manifest["parts"]))
            with self.assertRaises(q6.Q6RunnerError):
                q6.verify_parts_manifest([first, second], broken)
            broken = dict(manifest)
            broken["parts"] = [dict(entry) for entry in manifest["parts"]]
            broken["parts"][0]["sha256"] = "0" * 64
            with self.assertRaises(q6.Q6RunnerError):
                q6.verify_parts_manifest([first, second], broken)

    def test_safe_archive_extraction_rejects_traversal_and_symlink(self):
        import zipfile

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "unsafe.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("../escape.txt", b"bad")
            with self.assertRaises(q6.Q6RunnerError):
                q6.extract_bounded_archive(archive, root / "dest", expected_names=("safe.txt",))

            symlink_archive = root / "symlink.zip"
            info = zipfile.ZipInfo("safe.txt")
            info.external_attr = (0o120777 << 16)
            with zipfile.ZipFile(symlink_archive, "w") as zf:
                zf.writestr(info, b"target")
            with self.assertRaises(q6.Q6RunnerError):
                q6.extract_bounded_archive(symlink_archive, root / "dest2", expected_names=("safe.txt",))

    def test_readiness_failure_terminates_only_owned_child(self):
        process = FakeProcess()
        with self.assertRaises(q6.Q6RunnerError):
            q6.wait_for_readiness(
                process,
                lambda: False,
                timeout_seconds=0.01,
                poll_seconds=0,
            )
        q6.terminate_owned_process(process, grace_seconds=0)
        self.assertTrue(process.terminated or process.killed)

    def test_supervisor_records_normal_termination_and_timeout(self):
        server = FakeProcess()
        client = FakeProcess(0)
        result = q6.summarize_child_results(server, client, timed_out=False)
        self.assertEqual(result["status"], "completed")
        self.assertEqual(result["client_returncode"], 0)
        timeout = q6.summarize_child_results(FakeProcess(), FakeProcess(), timed_out=True)
        self.assertEqual(timeout["status"], "timeout")

    def test_orchestration_timeout_cleans_server_and_client_and_writes_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            children = []

            def spawn(command, log_path):
                log_path.write_text("", encoding="utf-8")
                child = FakeProcess()
                children.append(child)
                return child

            limits = q6.RuntimeLimits(
                preflight_memory_bytes=1,
                preflight_disk_bytes=1,
                max_runtime_seconds=1,
                min_available_memory_bytes=1,
                max_rss_bytes=1024**3,
                max_swap_growth_bytes=1024**3,
            )
            clock = AdvancingClock()
            result = q6.run_server_client(
                server_command=("fake-server",),
                client_command=("fake-client",),
                output_dir=root / "output",
                report_dir=root / "report",
                readiness=lambda: True,
                limits=limits,
                spawn=spawn,
                snapshot=lambda _: q6.ResourceSnapshot(2, 2, 0),
                rss_reader=lambda _: 1,
                clock=clock,
                sleep=lambda _: None,
            )
            self.assertEqual(result["status"], "timeout")
            self.assertEqual(len(children), 2)
            self.assertTrue(all(child.terminated or child.killed for child in children))
            report = json.loads((root / "report/supervisor_report.json").read_text())
            self.assertEqual(report["status"], "timeout")
            self.assertEqual(len(report["cleanup"]), 2)

    def test_orchestration_readiness_failure_cleans_server_and_never_starts_client(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            children = []

            def spawn(command, log_path):
                log_path.write_text("", encoding="utf-8")
                child = FakeProcess()
                children.append(child)
                return child

            result = q6.run_server_client(
                server_command=("fake-server",),
                client_command=("fake-client",),
                output_dir=root / "output",
                report_dir=root / "report",
                readiness=lambda: False,
                limits=q6.RuntimeLimits(preflight_memory_bytes=1, preflight_disk_bytes=1),
                spawn=spawn,
                snapshot=lambda _: q6.ResourceSnapshot(2, 2, 0),
                rss_reader=lambda _: 1,
                clock=AdvancingClock(),
                sleep=lambda _: None,
                readiness_timeout_seconds=0,
            )
            self.assertEqual(result["status"], "failed")
            self.assertEqual(len(children), 1)
            self.assertTrue(children[0].terminated or children[0].killed)

    def test_cleanup_failure_does_not_skip_other_child_or_terminal_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            children = []

            def spawn(command, log_path):
                log_path.write_text("", encoding="utf-8")
                child = FakeProcess(fail_terminate=command == ("fake-client",))
                children.append(child)
                return child

            result = q6.run_server_client(
                server_command=("fake-server",),
                client_command=("fake-client",),
                output_dir=root / "output",
                report_dir=root / "report",
                readiness=lambda: True,
                limits=q6.RuntimeLimits(
                    preflight_memory_bytes=1,
                    preflight_disk_bytes=1,
                    max_runtime_seconds=1,
                    min_available_memory_bytes=1,
                    max_rss_bytes=1024**3,
                    max_swap_growth_bytes=1024**3,
                ),
                spawn=spawn,
                snapshot=lambda _: q6.ResourceSnapshot(2, 2, 0),
                rss_reader=lambda _: 1,
                clock=AdvancingClock(),
                sleep=lambda _: None,
            )
            self.assertEqual(result["status"], "failed")
            self.assertEqual(len(children), 2)
            self.assertTrue(children[0].terminated or children[0].killed)
            self.assertFalse(children[1].terminated or children[1].killed)
            report = json.loads((root / "report/supervisor_report.json").read_text())
            self.assertEqual(report["status"], "failed")
            self.assertEqual({entry["child"] for entry in report["cleanup"]}, {"client", "server"})
            self.assertEqual(report["cleanup_errors"][0]["child"], "client")

    def test_preflight_refusal_persists_terminal_report_without_spawning(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            spawned = []

            def spawn(command, log_path):
                spawned.append(command)
                raise AssertionError("spawn must not happen after preflight refusal")

            result = q6.run_server_client(
                server_command=("fake-server",),
                client_command=("fake-client",),
                output_dir=root / "output",
                report_dir=root / "report",
                readiness=lambda: True,
                limits=q6.RuntimeLimits(preflight_memory_bytes=8, preflight_disk_bytes=8),
                spawn=spawn,
                snapshot=lambda _: q6.ResourceSnapshot(1, 1, 0),
            )
            self.assertEqual(result["status"], "failed")
            self.assertEqual(spawned, [])
            persisted = json.loads((root / "report/supervisor_report.json").read_text())
            self.assertEqual(persisted["error_type"], "Q6RunnerError")

    def test_runtime_limits_include_swap_and_memory_bounds(self):
        limits = q6.DEFAULT_LIMITS
        self.assertEqual(limits.max_runtime_seconds, 45 * 60)
        self.assertEqual(limits.max_rss_bytes, 8 * 1024**3)
        self.assertEqual(limits.max_swap_growth_bytes, 1024**3)
        self.assertEqual(limits.min_available_memory_bytes, 2 * 1024**3)
        with self.assertRaises(q6.Q6RunnerError):
            q6.validate_runtime_snapshot(
                q6.ResourceSnapshot(2 * 1024**3, 1, None),
                baseline_swap_used_bytes=0,
                rss_bytes=None,
            )

    def test_rss_guard_requires_fifteen_continuous_seconds_above_limit(self):
        limits = q6.RuntimeLimits(rss_observation_seconds=15.0, max_rss_bytes=100)
        over_since, exceeded = q6.update_rss_guard(101, 10.0, None, limits)
        self.assertEqual(over_since, 10.0)
        self.assertFalse(exceeded)
        over_since, exceeded = q6.update_rss_guard(101, 24.9, over_since, limits)
        self.assertEqual(over_since, 10.0)
        self.assertFalse(exceeded)
        over_since, exceeded = q6.update_rss_guard(101, 25.0, over_since, limits)
        self.assertEqual(over_since, 10.0)
        self.assertTrue(exceeded)
        over_since, exceeded = q6.update_rss_guard(99, 25.1, over_since, limits)
        self.assertIsNone(over_since)
        self.assertFalse(exceeded)


if __name__ == "__main__":
    unittest.main()
