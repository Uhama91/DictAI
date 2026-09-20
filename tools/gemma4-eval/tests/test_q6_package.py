from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class FrozenPackageTests(unittest.TestCase):
    def test_frozen_client_and_prompt_helpers_are_byte_identical(self):
        expected = {
            "scripts/evaluate_gemma4_gguf_native.py": "8e6f0ea79068b354980270ba0bc57d3dd36848d3c04210d0fe464802358719f4",
            "src/asr_postclean/prompt_v6.py": "af0d7c808150cd27960cd4506261e6b29fc6ac45df13db2694c6c870affbb0d6",
            "src/asr_postclean/prompt_v6_source_last.py": "234b6fb22392caa6fc05b9bfb39ceae3373d4bac46b625cb881a69175b8bb72d",
            "src/asr_postclean/multiformat.py": "16e9f5bfed640da87d2139ab831262188e57fc3931bed5d70b8069f5bc9c30a3",
            "reports/gemma4_v6/usage_audit_v1/selection.json": "ea5ba2fc6138764326f4bd46bb6ffe8d85fc59079c1d6365153804a13d90ec54",
            "data_v6/records/valid.jsonl": "4edc9d3d4b9bc7dd0562f0768ca1ea677abace355a1efadd5076db9a23cc74ff",
        }
        for relative, digest in expected.items():
            self.assertEqual(sha256(ROOT / relative), digest, relative)

    def test_selection_is_synthetic_subset_of_the_196_valid_records(self):
        selection = json.loads((ROOT / "reports/gemma4_v6/usage_audit_v1/selection.json").read_text())
        valid = [
            json.loads(line)
            for line in (ROOT / "data_v6/records/valid.jsonl").read_text().splitlines()
            if line
        ]
        self.assertEqual(len(valid), 196)
        self.assertEqual(selection["selected_count"], 60)
        by_id = {row["id"]: row for row in valid}
        selected_ids = [row["id"] for row in selection["selected"]]
        self.assertEqual(len(set(selected_ids)), 60)
        self.assertTrue(set(selected_ids) <= set(by_id))
        self.assertTrue(all(row.get("synthetic") is True for row in valid))
        self.assertTrue(all(by_id[identifier].get("synthetic") is True for identifier in selected_ids))
        for record in [
            json.loads(line)
            for line in (ROOT / "data_v6/records/valid.jsonl").read_text().splitlines()
            if line
        ]:
            self.assertNotIn(record.get("split"), {"test", "holdout"})

    def test_package_contains_no_weight_or_secret_files(self):
        forbidden_suffixes = {".gguf", ".safetensors", ".bin", ".pt", ".pth"}
        forbidden_names = {".env", "credentials.json", "private"}
        for path in ROOT.rglob("*"):
            self.assertFalse(path.is_symlink(), path)
            self.assertNotIn(path.name, forbidden_names, path)
            self.assertNotIn(path.suffix.lower(), forbidden_suffixes, path)
        requirements = "\n".join(
            line for line in (ROOT / "requirements.txt").read_text(encoding="utf-8").lower().splitlines()
            if not line.lstrip().startswith("#")
        )
        self.assertNotIn("torch", requirements)
        self.assertNotIn("mlx", requirements)

    def test_client_defaults_resolve_inside_isolated_package(self):
        script = ROOT / "scripts/evaluate_gemma4_gguf_native.py"
        spec = importlib.util.spec_from_file_location("isolated_native_client", script)
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        module = importlib.util.module_from_spec(spec)
        sys.modules["isolated_native_client"] = module
        spec.loader.exec_module(module)
        self.assertEqual(module.ROOT, ROOT)
        records, selection_sha, records_sha = module.load_locked_records()
        self.assertEqual(len(records), 60)
        self.assertEqual(
            selection_sha,
            "ea5ba2fc6138764326f4bd46bb6ffe8d85fc59079c1d6365153804a13d90ec54",
        )
        self.assertEqual(
            records_sha,
            "4edc9d3d4b9bc7dd0562f0768ca1ea677abace355a1efadd5076db9a23cc74ff",
        )


if __name__ == "__main__":
    unittest.main()
