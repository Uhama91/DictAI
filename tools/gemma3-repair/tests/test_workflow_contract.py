from __future__ import annotations

import json
import importlib.util
import re
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/train-gemma3-repair.yml"
BUILD_WORKFLOW = ROOT / ".github/workflows/build.yml"
CI_CONFIG = ROOT / "tools/gemma3-repair/ci_config.json"
PAYLOAD_SPEC = importlib.util.spec_from_file_location("gemma3_workflow_payload", ROOT / "tools/gemma3-repair/payload.py")
assert PAYLOAD_SPEC is not None and PAYLOAD_SPEC.loader is not None
payload = importlib.util.module_from_spec(PAYLOAD_SPEC)
sys.modules[PAYLOAD_SPEC.name] = payload
PAYLOAD_SPEC.loader.exec_module(payload)


class WorkflowContractTests(unittest.TestCase):
    def test_training_workflow_stays_pending_until_root_authorizes_it(self):
        config = json.loads(CI_CONFIG.read_text(encoding="utf-8"))
        if config["status"] == "not_ready":
            self.assertIsNone(config["release_id"])
            self.assertIsNone(config["archive_sha256"])
            self.assertIsNone(config["archive_bytes"])
        elif config["status"] == "ready":
            payload.load_release_lock(CI_CONFIG)
        else:
            self.fail("CI lock must stay pending or contain a complete reviewed release identity")

    def test_job_is_single_bounded_x64_public_runner_with_narrow_push_paths(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("branches:\n      - codex/gemma4-layout-validation", workflow)
        self.assertIn("tools/gemma3-repair/**", workflow)
        self.assertIn(".github/workflows/train-gemma3-repair.yml", workflow)
        self.assertIn("runs-on: ubuntu-24.04", workflow)
        self.assertRegex(workflow, r"timeout-minutes:\s*60\b")
        jobs = workflow.split("jobs:", 1)[1]
        self.assertEqual(len(re.findall(r"^  [a-z0-9_-]+:\s*$", jobs, re.MULTILINE)), 1)
        self.assertNotIn("strategy:", workflow)
        self.assertNotRegex(workflow, r"(?im)^\s*retry:\s*[1-9]")
        self.assertIn("contents: read", workflow)
        self.assertIn("contents: write", workflow)
        self.assertIn("[gemma3-repair-authorized]", workflow)
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertNotIn("gradlew", workflow)
        self.assertNotIn("gh release create", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("pip install --no-cache-dir", workflow)
        self.assertIn('--config "$GEMMA3_WORKSPACE/configs/gemma3_repair_v1.json"', workflow)

    def test_runner_temp_paths_are_initialized_in_first_step_not_job_env(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        job = re.search(r"(?ms)^  gemma3-repair:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)", workflow)
        self.assertIsNotNone(job)
        job_setup = job.group("body").split("    steps:", 1)[0]
        self.assertNotIn("${{ runner.temp }}", job_setup)
        self.assertNotRegex(job_setup, r"(?m)^    env:\s*$")

        init_start = workflow.index("      - name: Initialize Gemma 3 CI workspace paths")
        checkout_start = workflow.index("      - name: Check out workflow and download helper without persisting credentials")
        self.assertEqual(workflow.index("      - name: "), init_start)
        self.assertLess(init_start, checkout_start)
        init_step = workflow[init_start:checkout_start]
        for variable in ("GEMMA3_WORKSPACE", "GEMMA3_PREPARED_DIR", "GEMMA3_RESULTS_DIR", "PYTHONPATH"):
            self.assertRegex(init_step, rf"printf '[^']*{variable}=")
        self.assertEqual(init_step.count('>> "$GITHUB_ENV"'), 1)
        self.assertIn('"$RUNNER_TEMP"', init_step)

    def test_workflow_orders_parent_training_candidate_and_always_packages_safe_outputs(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        parent = workflow.index("--model parent")
        parent_gate = workflow.index("Require the complete parent baseline before training")
        train = workflow.index("--prepared-dir \"$GEMMA3_PREPARED_DIR\" --execute", workflow.index("Train exactly one"))
        train_gate = workflow.index("Require the completed 64-update adapter")
        candidate = workflow.index("--model candidate")
        package = workflow.index("Package allowlisted reports and synthetic predictions")
        upload = workflow.index("Upload diagnostic artifact, including incomplete-run reports")
        self.assertLess(parent, parent_gate)
        self.assertLess(parent_gate, train)
        self.assertLess(train, train_gate)
        self.assertLess(train_gate, candidate)
        self.assertLess(candidate, package)
        self.assertLess(package, upload)
        self.assertEqual(workflow.count("HF_HUB_OFFLINE: '1'"), 6)
        self.assertIn("if: always()", workflow)
        self.assertIn("if-no-files-found: ignore", workflow)
        self.assertIn("package_results.py", workflow)

    def test_android_build_skips_only_the_marked_push_event(self):
        build = BUILD_WORKFLOW.read_text(encoding="utf-8")
        build_job = re.search(r"(?ms)^  build:\n(.*?)(?=^  [a-z0-9_-]+:|\Z)", build)
        self.assertIsNotNone(build_job)
        condition = re.search(r"(?m)^    if: (.+)$", build_job.group(1))
        self.assertIsNotNone(condition)
        expression = condition.group(1)
        self.assertIn("github.event_name != 'push'", expression)
        self.assertIn("!contains(github.event.head_commit.message, '[gemma3-training-only]')", expression)
        self.assertIn("workflow_dispatch:", build)
        self.assertIn("branches: [ \"**\" ]", build)

        def should_build(event_name: str, message: str) -> bool:
            return event_name != "push" or "[gemma3-training-only]" not in message

        self.assertFalse(should_build("push", "chore: CI [gemma3-training-only]"))
        self.assertTrue(should_build("push", "fix: Android overlay"))
        self.assertTrue(should_build("workflow_dispatch", "chore: CI [gemma3-training-only]"))

    def test_download_is_the_only_secret_scoped_step_and_ml_steps_are_offline(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(workflow.count("${{ secrets.GITHUB_TOKEN }}"), 1)
        self.assertIn("GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}", workflow)
        self.assertIn("HF_HUB_OFFLINE: '1'", workflow)
        self.assertIn("TRANSFORMERS_OFFLINE: '1'", workflow)
        self.assertIn("HF_TOKEN", workflow)
        self.assertIn("unset GH_TOKEN", workflow)
        self.assertIn("OMP_NUM_THREADS: '4'", workflow)


if __name__ == "__main__":
    unittest.main()
