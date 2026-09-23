from __future__ import annotations

import hashlib
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
STAGER_PATH = ROOT / "tools/gemma3-repair-mlp/stage_runtime.py"
STAGER_SPEC = (
    importlib.util.spec_from_file_location("gemma3_mlp_runtime_stager", STAGER_PATH)
    if STAGER_PATH.is_file()
    else None
)
if STAGER_SPEC is not None and STAGER_SPEC.loader is not None:
    stager = importlib.util.module_from_spec(STAGER_SPEC)
    sys.modules[STAGER_SPEC.name] = stager
    STAGER_SPEC.loader.exec_module(stager)
else:
    stager = None


class RuntimeStagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "checkout/tools/gemma3-repair-mlp"
        self.workspace = self.root / "extracted/gemma3-repair-v1"
        self.workspace.mkdir(parents=True)
        self.allowed = (
            "scripts/run_gemma3_repair_mlp_v2.py",
            "configs/gemma3_repair_mlp_v2.json",
            "src/asr_postclean/gemma3_mlp_adapter.py",
        )
        for relative in self.allowed:
            source = self.source / relative
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(("reviewed:" + relative).encode("utf-8"))
            destination_parent = self.workspace / relative
            destination_parent.parent.mkdir(parents=True, exist_ok=True)

        self.v1_runner = self.workspace / "scripts/run_gemma3_repair_v1.py"
        self.v1_runner.write_text("immutable v1 runner", encoding="utf-8")
        self.v1_dataset = self.workspace / "data_gemma3_repair_v1/train.jsonl"
        self.v1_dataset.parent.mkdir(parents=True)
        self.v1_dataset.write_text("immutable v1 dataset", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def test_stager_exists_as_a_versioned_ci_entrypoint(self):
        self.assertIsNotNone(stager, "stage_runtime.py must provide the reviewed copy API")

    @unittest.skipIf(stager is None, "stager API is introduced after this RED contract")
    def test_copies_only_the_three_new_versioned_files_and_reports_runtime_hashes(self):
        result = stager.stage_runtime(self.source, self.workspace)
        self.assertEqual(set(result), set(self.allowed))
        for relative in self.allowed:
            expected = (self.source / relative).read_bytes()
            destination = self.workspace / relative
            self.assertEqual(destination.read_bytes(), expected)
            self.assertEqual(result[relative]["bytes"], len(expected))
            self.assertEqual(result[relative]["sha256"], hashlib.sha256(expected).hexdigest())
        self.assertEqual(self.v1_runner.read_text(encoding="utf-8"), "immutable v1 runner")
        self.assertEqual(self.v1_dataset.read_text(encoding="utf-8"), "immutable v1 dataset")

    @unittest.skipIf(stager is None, "stager API is introduced after this RED contract")
    def test_preflights_all_destinations_and_refuses_to_overwrite_any_v1_extraction_file(self):
        sentinel = self.workspace / self.allowed[1]
        sentinel.write_text("preserve existing file", encoding="utf-8")
        with self.assertRaises(stager.RuntimeStageError):
            stager.stage_runtime(self.source, self.workspace)
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "preserve existing file")
        self.assertFalse((self.workspace / self.allowed[0]).exists())
        self.assertFalse((self.workspace / self.allowed[2]).exists())

    @unittest.skipIf(stager is None, "stager API is introduced after this RED contract")
    def test_refuses_symlink_sources_and_symlinked_destination_directories(self):
        relative = self.allowed[0]
        source_path = self.source / relative
        target = self.root / "outside.py"
        target.write_text("outside", encoding="utf-8")
        source_path.unlink()
        source_path.symlink_to(target)
        with self.assertRaises(stager.RuntimeStageError):
            stager.stage_runtime(self.source, self.workspace)
        self.assertFalse((self.workspace / relative).exists())

        source_path.unlink()
        source_path.write_text("safe source", encoding="utf-8")
        destination_dir = self.workspace / "src/asr_postclean"
        destination_dir.rmdir()
        destination_dir.symlink_to(self.root / "outside-dir")
        (self.root / "outside-dir").mkdir()
        with self.assertRaises(stager.RuntimeStageError):
            stager.stage_runtime(self.source, self.workspace)


if __name__ == "__main__":
    unittest.main()
