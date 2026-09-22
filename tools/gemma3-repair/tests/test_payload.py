from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import sys
import tarfile
import tempfile
import unittest
import gzip
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("gemma3_repair_payload", ROOT / "payload.py")
assert SPEC is not None and SPEC.loader is not None
payload = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = payload
SPEC.loader.exec_module(payload)


def _fixture_sources(directory: Path) -> dict[str, Path]:
    sources: dict[str, Path] = {}
    for relative in payload.PAYLOAD_CONTENT_PATHS:
        path = directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(("fixture:" + relative + "\n").encode("utf-8"))
        sources[relative] = path
    return sources


def _archive_with_members(path: Path, members: list[tuple[str, bytes, bytes]]) -> None:
    """Write (name, type, data) entries to a tiny adversarial archive."""
    with tarfile.open(path, "w:gz", format=tarfile.PAX_FORMAT) as archive:
        for name, entry_type, data in members:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.type = entry_type
            archive.addfile(info, io.BytesIO(data) if entry_type == tarfile.REGTYPE else None)


class PayloadContractTests(unittest.TestCase):
    def test_pending_release_lock_cannot_download_or_execute(self):
        with tempfile.TemporaryDirectory() as temporary:
            config = json.loads((ROOT / "ci_config.json").read_text(encoding="utf-8"))
            config.update({"status": "not_ready", "release_id": None, "archive_sha256": None, "archive_bytes": None})
            pending = Path(temporary) / "pending.json"
            pending.write_text(json.dumps(config), encoding="utf-8")
            with self.assertRaisesRegex(payload.PayloadError, "not ready"):
                payload.load_release_lock(pending)

    def test_release_lock_exports_only_validated_draft_asset_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = json.loads((ROOT / "ci_config.json").read_text(encoding="utf-8"))
            config.update(
                {
                    "status": "ready",
                    "release_id": 123456,
                    "archive_sha256": "a" * 64,
                    "archive_bytes": 1234,
                }
            )
            config_path = root / "lock.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            lock = payload.load_release_lock(config_path)
            self.assertEqual(lock["release_id"], 123456)
            self.assertEqual(lock["archive_bytes"], 1234)
            config["max_cumulative_evaluation_seconds"] = 1201
            config_path.write_text(json.dumps(config), encoding="utf-8")
            with self.assertRaisesRegex(payload.PayloadError, "limits changed"):
                payload.load_release_lock(config_path)

    def test_allowlist_contains_only_the_reviewed_portable_inputs(self):
        allowed = set(payload.PAYLOAD_CONTENT_PATHS)
        self.assertEqual(len(allowed), len(payload.PAYLOAD_CONTENT_PATHS))
        self.assertEqual(
            {Path(name).name for name in allowed if "/snapshots/" in name},
            {
                "added_tokens.json",
                "chat_template.jinja",
                "config.json",
                "generation_config.json",
                "model.safetensors",
                "special_tokens_map.json",
                "tokenizer.json",
                "tokenizer.model",
                "tokenizer_config.json",
            },
        )
        self.assertEqual(
            {name for name in allowed if name.startswith("data_gemma3_repair_v1/")},
            {
                "data_gemma3_repair_v1/manifest.json",
                "data_gemma3_repair_v1/manifest.sha256",
                "data_gemma3_repair_v1/provenance.json",
                "data_gemma3_repair_v1/train.jsonl",
                "data_gemma3_repair_v1/valid.jsonl",
            },
        )
        self.assertIn("data_v3/valid.jsonl", allowed)
        self.assertIn("parent/manifest.json", allowed)
        self.assertTrue(any(name.startswith("parent/best/") for name in allowed))
        self.assertFalse(any("private" in name.lower() for name in allowed))
        self.assertFalse(any("holdout" in name.lower() for name in allowed))
        self.assertFalse(any(name.startswith("runs/") for name in allowed))

    def test_source_collection_resolves_model_symlinks_to_regular_payload_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            training = root / "training"
            cache = root / "cache"
            legal = root / "legal"
            legal.mkdir()
            for relative in payload.TRAINING_REPO_PATHS:
                source = training / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_bytes(b"fixture\n")
            for relative in payload.LEGAL_PATHS:
                source = legal / Path(relative).name
                source.write_bytes(b"fixture\n")

            snapshot = cache / payload.MODEL_SNAPSHOT_RELATIVE
            snapshot.mkdir(parents=True)
            blob = cache / "blobs" / "model-fixture"
            blob.parent.mkdir(parents=True)
            blob.write_bytes(b"resolved model bytes")
            for filename in payload.MODEL_SNAPSHOT_FILES:
                link = snapshot / filename
                if filename == "model.safetensors":
                    link.symlink_to(blob)
                else:
                    link.write_bytes(("fixture:" + filename).encode())

            sources = payload.collect_payload_sources(training, cache, legal)
            model_path = (
                "base/hub/"
                + payload.MODEL_SNAPSHOT_RELATIVE
                + "/model.safetensors"
            )
            self.assertEqual(sources[model_path].resolve(), blob.resolve())
            self.assertEqual(sources[model_path].read_bytes(), b"resolved model bytes")
            self.assertEqual(set(sources), set(payload.PAYLOAD_CONTENT_PATHS))

    def test_source_collection_rejects_model_symlink_outside_hub_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            training, cache, legal = root / "training", root / "cache", root / "legal"
            legal.mkdir()
            for relative in payload.TRAINING_REPO_PATHS:
                source = training / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_bytes(b"fixture\n")
            for relative in payload.LEGAL_PATHS:
                (legal / Path(relative).name).write_bytes(b"fixture\n")
            snapshot = cache / payload.MODEL_SNAPSHOT_RELATIVE
            snapshot.mkdir(parents=True)
            outside = root / "outside-model.safetensors"
            outside.write_bytes(b"must not escape cache")
            for filename in payload.MODEL_SNAPSHOT_FILES:
                link = snapshot / filename
                if filename == "model.safetensors":
                    link.symlink_to(outside)
                else:
                    link.write_bytes(b"fixture")

            with self.assertRaisesRegex(payload.PayloadError, "outside the HF cache"):
                payload.collect_payload_sources(training, cache, legal)

    def test_source_collection_rejects_symlinked_training_package_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            training, cache, legal = root / "training", root / "cache", root / "legal"
            legal.mkdir()
            for relative in payload.TRAINING_REPO_PATHS:
                source = training / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_bytes(b"fixture\n")
            for relative in payload.LEGAL_PATHS:
                (legal / Path(relative).name).write_bytes(b"fixture\n")
            snapshot = cache / payload.MODEL_SNAPSHOT_RELATIVE
            snapshot.mkdir(parents=True)
            for filename in payload.MODEL_SNAPSHOT_FILES:
                (snapshot / filename).write_bytes(b"fixture")
            package = training / "src/asr_postclean"
            external = root / "external-package"
            package.rename(external)
            package.symlink_to(external, target_is_directory=True)
            with self.assertRaisesRegex(payload.PayloadError, "symlink is not allowed"):
                payload.collect_payload_sources(training, cache, legal)

    def test_archive_round_trip_verifies_sha_sizes_and_regular_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = _fixture_sources(root / "sources")
            archive_path = root / "payload.tar.gz"
            result = payload.build_payload_archive(sources, archive_path)
            with tarfile.open(archive_path, "r:gz") as archive:
                manifest = json.load(archive.extractfile("gemma3-repair-v1/payload_manifest.json"))
            self.assertEqual(
                manifest["transport"],
                {
                    "destination": "GitHub draft release asset",
                    "status": "authorized_by_plan_not_yet_uploaded",
                    "public_release": False,
                    "authorization_reference": "docs/superpowers/plans/2026-09-23-gemma3-pilot-execution.md",
                },
            )
            self.assertNotIn(str(root), json.dumps(manifest))
            destination = root / "extracted"
            extracted = payload.extract_payload_archive(
                archive_path,
                destination,
                expected_sha256=result["sha256"],
                expected_bytes=result["bytes"],
                expected_runner_sha256=None,
            )

            self.assertEqual(extracted["file_count"], len(payload.PAYLOAD_CONTENT_PATHS))
            self.assertEqual(set(extracted["files"]), set(payload.PAYLOAD_CONTENT_PATHS))
            self.assertEqual(
                (destination / "parent/best/adapter_model.safetensors").read_bytes(),
                sources["parent/best/adapter_model.safetensors"].read_bytes(),
            )
            self.assertEqual(
                (destination / "legal/NOTICE.txt").read_bytes(),
                sources["legal/NOTICE.txt"].read_bytes(),
            )
            for path in destination.rglob("*"):
                self.assertFalse(path.is_symlink(), str(path))
                if path.is_file():
                    self.assertTrue(path.stat().st_mode & 0o170000 == 0o100000)

    def test_archive_builder_refuses_missing_or_unreviewed_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = _fixture_sources(root / "sources")
            sources.pop("data_v3/valid.jsonl")
            with self.assertRaisesRegex(payload.PayloadError, "allowlist"):
                payload.build_payload_archive(sources, root / "payload.tar.gz")

            sources["data_v3/valid.jsonl"] = root / "sources/data_v3/valid.jsonl"
            sources["private/dictations.jsonl"] = root / "sources/private.jsonl"
            with self.assertRaisesRegex(payload.PayloadError, "allowlist"):
                payload.build_payload_archive(sources, root / "payload.tar.gz")

    def test_member_name_validation_rejects_traversal_and_absolute_paths(self):
        for unsafe in (
            "../outside",
            "/absolute",
            "a/../../outside",
            "a\\..\\outside",
            "gemma3-repair-v1//legal/NOTICE.txt",
            "gemma3-repair-v1/./legal/NOTICE.txt",
        ):
            with self.subTest(unsafe=unsafe), self.assertRaises(payload.PayloadError):
                payload.validate_archive_member_name(unsafe)

    def test_extraction_rejects_symlink_members_before_writing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive_path = root / "bad.tar.gz"
            _archive_with_members(
                archive_path,
                [("gemma3-repair-v1/inside", tarfile.SYMTYPE, "../outside".encode())],
            )
            destination = root / "out"
            with self.assertRaises(payload.PayloadError):
                payload.extract_payload_archive(
                    archive_path,
                    destination,
                    expected_sha256=hashlib.sha256(archive_path.read_bytes()).hexdigest(),
                    expected_bytes=archive_path.stat().st_size,
                )
            self.assertFalse(destination.exists())

    def test_extraction_rejects_outer_identity_mismatch_before_writing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = _fixture_sources(root / "sources")
            archive_path = root / "payload.tar.gz"
            payload.build_payload_archive(sources, archive_path)
            destination = root / "out"
            with self.assertRaisesRegex(payload.PayloadError, "SHA-256"):
                payload.extract_payload_archive(
                    archive_path,
                    destination,
                    expected_sha256="0" * 64,
                    expected_bytes=archive_path.stat().st_size,
                )
            self.assertFalse(destination.exists())

    def test_extraction_rejects_expansion_limit_and_existing_destination(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = _fixture_sources(root / "sources")
            archive_path = root / "payload.tar.gz"
            result = payload.build_payload_archive(sources, archive_path)
            destination = root / "out"
            with self.assertRaisesRegex(payload.PayloadError, "expanded size"):
                payload.extract_payload_archive(
                    archive_path,
                    destination,
                    expected_sha256=result["sha256"],
                    expected_bytes=result["bytes"],
                    max_total_bytes=1,
                )
            self.assertFalse(destination.exists())

            destination.mkdir()
            with self.assertRaisesRegex(payload.PayloadError, "already exists"):
                payload.extract_payload_archive(
                    archive_path,
                    destination,
                    expected_sha256=result["sha256"],
                    expected_bytes=result["bytes"],
                )

    def test_extraction_caps_decompressed_tar_stream_before_scanning_members(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive_path = root / "gzip-bomb.tar.gz"
            tar_bytes = b"not a tar archive\n" + (b"\0" * (2 * 1024 * 1024))
            archive_path.write_bytes(gzip.compress(tar_bytes, mtime=0))
            with self.assertRaisesRegex(payload.PayloadError, "decompressed archive exceeds"):
                payload.extract_payload_archive(
                    archive_path,
                    root / "out",
                    expected_sha256=hashlib.sha256(archive_path.read_bytes()).hexdigest(),
                    expected_bytes=archive_path.stat().st_size,
                    max_total_bytes=10,
                )
            self.assertFalse((root / "out").exists())

    def test_legal_notice_and_source_record_are_in_the_payload(self):
        notice = ROOT / "legal/NOTICE.txt"
        source_record = ROOT / "legal/SOURCE.txt"
        terms = ROOT / "legal/Gemma-Terms-of-Use.html"
        self.assertTrue(notice.is_file())
        self.assertTrue(source_record.is_file())
        self.assertTrue(terms.is_file())
        self.assertIn(
            "Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms",
            notice.read_text(encoding="utf-8"),
        )
        source_text = source_record.read_text(encoding="utf-8")
        self.assertIn("https://ai.google.dev/gemma/terms", source_text)
        self.assertIn(hashlib.sha256(terms.read_bytes()).hexdigest(), source_text)


if __name__ == "__main__":
    unittest.main()
