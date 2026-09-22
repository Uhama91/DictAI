from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("gemma3_repair_results", ROOT / "package_results.py")
assert SPEC is not None and SPEC.loader is not None
results = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = results
SPEC.loader.exec_module(results)


def _json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value) + "\n", encoding="utf-8")


class PackageResultsTests(unittest.TestCase):
    def test_packages_only_completed_synthetic_outputs_and_adapter(self):
        self.assertEqual(
            {Path(name).name for name in results.ADAPTER_OUTPUT_PATHS},
            {
                "README.md",
                "adapter_config.json",
                "adapter_model.safetensors",
                "chat_template.jinja",
                "tokenizer.json",
                "tokenizer_config.json",
                "training_args.bin",
            },
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = root / "prepared"
            output = root / "artifact"
            adapter = prepared / "training/best"
            adapter.mkdir(parents=True)
            adapter_weight = adapter / "adapter_model.safetensors"
            adapter_weight.write_bytes(b"synthetic adapter bytes")
            (adapter / "adapter_config.json").write_text("{}\n", encoding="utf-8")
            for name in (
                "README.md",
                "chat_template.jinja",
                "tokenizer.json",
                "tokenizer_config.json",
                "training_args.bin",
            ):
                (adapter / name).write_text("candidate artifact\n", encoding="utf-8")

            for relative in results.RESULT_SOURCE_PATHS:
                source = prepared / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_bytes(b"{}\n" if relative.endswith(".json") else b'{"id":"synthetic-1"}\n')

            _json(prepared / "prepare/manifest.json", {"status": "prepared"})
            _json(prepared / "prepare/config_snapshot.json", {"model_loaded": False})
            _json(
                prepared / "training/manifest.json",
                {
                    "status": "completed",
                    "completed_updates": 64,
                    "artifacts": {"adapter_dir": "best", "adapter_sha256": results._directory_sha256(adapter)},
                },
            )
            parent_manifest = None
            for phase, model_kind in (("parent_evaluation", "parent"), ("candidate_evaluation", "candidate")):
                phase_dir = prepared / phase
                _json(
                    phase_dir / "manifest.json",
                    {
                        "status": "completed",
                        "model_kind": model_kind,
                        "execution_mode": "supervised_generated",
                        "model_loaded": True,
                        "records_expected": 108,
                        "records_written": 108,
                    },
                )
                if phase == "parent_evaluation":
                    parent_manifest = (phase_dir / "manifest.json").read_bytes()
                (phase_dir / "predictions.jsonl").write_text('{"id":"synthetic-1"}\n', encoding="utf-8")
                (phase_dir / "preflight.json").write_text("{}\n", encoding="utf-8")
                (phase_dir / "resource_telemetry.json").write_text("{}\n", encoding="utf-8")
            _json(
                prepared / "evaluation_budget.json",
                {
                    "total_budget_seconds": 1200.0,
                    "total_elapsed_seconds": 300.0,
                    "phases": {
                        "parent": {"status": "completed", "elapsed_seconds": 150.0},
                        "candidate": {"status": "completed", "elapsed_seconds": 150.0},
                    },
                },
            )

            result = results.package_completed_results(prepared, output)

            expected = set(results.RESULT_PATHS)
            actual = {
                path.relative_to(output).as_posix()
                for path in output.rglob("*")
                if path.is_file()
            }
            self.assertEqual(actual, expected)
            artifact_manifest = json.loads((output / "artifact_manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(
                artifact_manifest["files"]["parent_evaluation/manifest.json"]["sha256"],
                hashlib.sha256(parent_manifest).hexdigest(),
            )
            self.assertIn("candidate_evaluation/predictions.jsonl", actual)
            self.assertIn("legal/NOTICE.txt", actual)
            self.assertIn("legal/Gemma-Terms-of-Use.html", actual)
            self.assertNotIn("base", "/".join(actual))
            self.assertNotIn("parent/best", "/".join(actual))
            self.assertIn("training/best/tokenizer.json", actual)
            self.assertEqual(result["status"], "completed")

    def test_empty_failed_run_produces_no_artifact_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = root / "prepared"
            prepared.mkdir()
            output = root / "artifact"
            result = results.package_completed_results(prepared, output)
            self.assertEqual(result["status"], "empty")
            self.assertFalse(output.exists())

    def test_incomplete_parent_keeps_synthetic_diagnostics_but_no_model_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = root / "prepared"
            phase = prepared / "parent_evaluation"
            phase.mkdir(parents=True)
            _json(phase / "manifest.json", {"status": "incomplete", "model_kind": "parent", "error": "synthetic diagnostic"})
            (phase / "predictions.jsonl").write_text('{"id":"synthetic-1"}\n', encoding="utf-8")
            output = root / "artifact"

            result = results.package_completed_results(prepared, output)

            self.assertEqual(result["status"], "incomplete")
            relative = {
                path.relative_to(output).as_posix()
                for path in output.rglob("*")
                if path.is_file()
            }
            self.assertEqual(
                relative,
                {"parent_evaluation/manifest.json", "parent_evaluation/predictions.jsonl", "artifact_manifest.json"},
            )
            report = json.loads((output / "parent_evaluation/manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(report["error"], "synthetic diagnostic")
            self.assertFalse(any("adapter" in name or "base" in name for name in relative))


if __name__ == "__main__":
    unittest.main()
