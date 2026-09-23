from __future__ import annotations

import importlib.util
import json
import re
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
TOOLS = ROOT / "tools/gemma3-repair-mlp"
WORKFLOW = ROOT / ".github/workflows/train-gemma3-repair-mlp.yml"
CI_CONFIG = TOOLS / "ci_config.json"
V1_LOCK = ROOT / "tools/gemma3-repair/ci_config.json"
V2_RUNTIME_CONFIG = TOOLS / "configs/gemma3_repair_mlp_v2.json"
V1_PAYLOAD_SPEC = importlib.util.spec_from_file_location(
    "gemma3_repair_mlp_v1_payload", ROOT / "tools/gemma3-repair/payload.py"
)
assert V1_PAYLOAD_SPEC is not None and V1_PAYLOAD_SPEC.loader is not None
v1_payload = importlib.util.module_from_spec(V1_PAYLOAD_SPEC)
sys.modules[V1_PAYLOAD_SPEC.name] = v1_payload
V1_PAYLOAD_SPEC.loader.exec_module(v1_payload)


def _workflow(case: unittest.TestCase) -> str:
    case.assertTrue(WORKFLOW.is_file(), "the bounded V2 workflow has not been added")
    return WORKFLOW.read_text(encoding="utf-8")


class Gemma3RepairMlpWorkflowContractTests(unittest.TestCase):
    def test_v2_lock_starts_not_ready_and_can_be_enabled_without_changing_its_source_asset(self):
        self.assertTrue(CI_CONFIG.is_file(), "the separate V2 CI lock has not been added")
        config = json.loads(CI_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(config.get("schema_version"), 1)
        self.assertEqual(config.get("source_payload_lock"), "tools/gemma3-repair/ci_config.json")
        self.assertIn(config.get("status"), {"not_ready", "ready"})
        if config["status"] == "not_ready":
            self.assertNotIn("release_id", config)
            self.assertNotIn("archive_sha256", config)
            self.assertNotIn("archive_bytes", config)
        else:
            v1_payload.load_release_lock(V1_LOCK)

    def test_v2_config_keeps_the_reviewed_data_training_evaluation_and_resource_budgets(self):
        self.assertTrue(V2_RUNTIME_CONFIG.is_file(), "the versioned MLP V2 config has not been added")
        config = json.loads(V2_RUNTIME_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(config["model"], {
            "id": "google/gemma-3-270m-it",
            "revision": "ac82b4e820549b854eebf28ce6dedaf9fdfa17b3",
        })
        self.assertEqual(config["parent"]["manifest"], "runs/gemma-3-270m-it-lora-v3/manifest.json")
        self.assertEqual(config["parent"]["adapter_dir"], "runs/gemma-3-270m-it-lora-v3/best")
        self.assertTrue(config["parent"]["dataset_revision_from_manifest"])
        self.assertEqual(config["child_dataset"]["train_records"], 256)
        self.assertEqual(config["child_dataset"]["valid_records"], 32)

        training = config["training"]
        self.assertEqual(training["epochs"], 1)
        self.assertEqual(training["seed"], 42)
        self.assertEqual(training["max_length"], 768)
        self.assertTrue(training["dynamic_padding"])
        self.assertEqual(training["per_device_train_batch_size"], 1)
        self.assertEqual(training["per_device_eval_batch_size"], 1)
        self.assertEqual(training["gradient_accumulation_steps"], 4)
        self.assertEqual(training["expected_updates"], 64)
        self.assertEqual(training["learning_rate"], 0.00002)
        self.assertEqual(training["lora_r"], 16)
        self.assertEqual(training["lora_alpha"], 32)
        self.assertEqual(training["lora_dropout"], 0.0)
        self.assertEqual(
            set(training["target_modules"]),
            {"q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"},
        )
        self.assertTrue(training["start_from_adapter"])
        self.assertEqual(training["prompt_version"], "v3")

        evaluation = config["evaluation"]
        self.assertEqual(evaluation["new_dev_records"], 32)
        self.assertEqual(evaluation["old_valid_records"], 76)
        self.assertEqual(evaluation["max_new_tokens"], 256)
        self.assertEqual(evaluation["per_case_timeout_seconds"], 20)
        self.assertEqual(evaluation["global_timeout_seconds"], 1200)
        self.assertEqual(evaluation["decoding"], "greedy")
        self.assertTrue(evaluation["native_eos_required"])
        self.assertTrue(evaluation["parent_and_candidate_are_separate_runs"])

        runtime = config["runtime"]
        self.assertTrue(runtime["offline"])
        self.assertEqual(runtime["min_launch_available_bytes"], 4 * 1024**3)
        self.assertEqual(runtime["min_runtime_available_bytes"], int(1.5 * 1024**3))
        self.assertEqual(runtime["min_free_disk_bytes"], 10 * 1024**3)
        self.assertEqual(runtime["rss_limit_bytes"], 5 * 1024**3)
        self.assertEqual(runtime["mps_driver_limit_bytes"], 5 * 1024**3)
        self.assertEqual(runtime["swap_delta_limit_bytes"], 512 * 1024**2)
        self.assertEqual(runtime["max_training_seconds"], 900)
        self.assertFalse(runtime["automatic_retry"])
        self.assertTrue(config["privacy"]["synthetic_only"])
        self.assertFalse(config["privacy"]["upload"])
        self.assertFalse(config["privacy"]["private_dictations_used"])

    def test_workflow_is_a_separate_bounded_push_job_with_its_own_marker(self):
        workflow = _workflow(self)
        self.assertIn("name: Gemma 3 Attention + MLP V2 CPU pilot", workflow)
        self.assertIn("branches:\n      - codex/gemma4-layout-validation", workflow)
        self.assertIn("tools/gemma3-repair-mlp/**", workflow)
        self.assertIn(".github/workflows/train-gemma3-repair-mlp.yml", workflow)
        self.assertIn("runs-on: ubuntu-24.04", workflow)
        self.assertRegex(workflow, r"timeout-minutes:\s*60\b")
        jobs = workflow.split("jobs:", 1)[1]
        self.assertEqual(len(re.findall(r"^  [a-z0-9_-]+:\s*$", jobs, re.MULTILINE)), 1)
        self.assertNotIn("strategy:", workflow)
        self.assertNotRegex(workflow, r"(?im)^\s*retry:\s*[1-9]")
        self.assertIn("[gemma3-mlp-authorized]", workflow)
        self.assertNotIn("[gemma3-repair-authorized]", workflow)
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertNotIn("gradlew", workflow)
        self.assertIn("contents: read", workflow)
        self.assertIn("contents: write", workflow)

    def test_workspace_setup_and_not_ready_gate_precede_asset_download(self):
        workflow = _workflow(self)
        job = re.search(r"(?ms)^  gemma3-repair-mlp:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)", workflow)
        self.assertIsNotNone(job)
        job_setup = job.group("body").split("    steps:", 1)[0]
        self.assertNotIn("${{ runner.temp }}", job_setup)
        self.assertNotRegex(job_setup, r"(?m)^    env:\s*$")

        init_start = workflow.index("      - name: Initialize Gemma 3 MLP CI workspace paths")
        checkout_start = workflow.index("      - name: Check out workflow and V1 helpers without persisting credentials")
        gate_start = workflow.index("      - name: Require reviewed V2 run lock")
        release_lock_start = workflow.index("tools/gemma3-repair/payload.py release-lock")
        download_start = workflow.index("      - name: Download the exact reviewed V1 draft payload")
        self.assertEqual(workflow.index("      - name: "), init_start)
        self.assertLess(init_start, checkout_start)
        self.assertLess(checkout_start, gate_start)
        self.assertLess(gate_start, release_lock_start)
        self.assertLess(release_lock_start, download_start)

        init_step = workflow[init_start:checkout_start]
        for variable in ("GEMMA3_WORKSPACE", "GEMMA3_PREPARED_DIR", "GEMMA3_RESULTS_DIR", "PYTHONPATH"):
            self.assertRegex(init_step, rf"printf '[^']*{variable}=")
        self.assertIn('"$RUNNER_TEMP"', init_step)
        self.assertEqual(init_step.count('>> "$GITHUB_ENV"'), 1)
        self.assertIn('"status") != "ready"', workflow)

    def test_v1_payload_is_downloaded_and_extracted_unchanged_before_new_files_are_staged(self):
        workflow = _workflow(self)
        lock_gate = workflow.index("      - name: Require reviewed V2 run lock")
        dependencies = workflow.index("      - name: Install pinned CPU dependencies without a wheel cache")
        tests_label = "      - name: Run V2 transport and workflow tests before download"
        self.assertIn(tests_label, workflow)
        tests = workflow.index(tests_label)
        release_lock = workflow.index("tools/gemma3-repair/payload.py release-lock")
        download = workflow.index("python tools/gemma4-eval/scripts/download_release_asset.py")
        extract = workflow.index("python tools/gemma3-repair/payload.py extract")
        stage = workflow.index("python tools/gemma3-repair-mlp/stage_runtime.py")
        smoke = workflow.index("Smoke import only from the extracted V1 workspace")
        cpu_tests = workflow.index("      - name: Run tiny-model MLP V2 CPU tests from the extracted workspace")
        prepare = workflow.index("Prepare sealed MLP V2 inputs without loading a model")
        self.assertLess(lock_gate, dependencies)
        self.assertLess(dependencies, tests)
        self.assertLess(tests, release_lock)
        self.assertIn("python -m pytest -q", workflow)
        self.assertIn("tools/gemma3-repair-mlp/tests/test_workflow_contract.py", workflow)
        self.assertIn("tools/gemma3-repair-mlp/tests/test_runtime_staging.py", workflow)
        self.assertLess(release_lock, download)
        self.assertLess(download, extract)
        self.assertLess(extract, stage)
        self.assertLess(stage, smoke)
        self.assertLess(smoke, cpu_tests)
        self.assertLess(cpu_tests, prepare)
        self.assertIn('python -m pytest -q "$GITHUB_WORKSPACE/tools/gemma3-repair-mlp/tests/test_gemma3_repair_mlp_v2.py"', workflow)
        self.assertIn("GEMMA3_WORKSPACE: ${{ runner.temp }}/gemma3-repair-mlp-workspace", workflow)
        self.assertIn("PYTHONPATH: ${{ runner.temp }}/gemma3-repair-mlp-workspace:${{ runner.temp }}/gemma3-repair-mlp-workspace/src:${{ runner.temp }}/gemma3-repair-mlp-workspace/scripts", workflow)
        self.assertIn("--release-id '${{ steps.release-lock.outputs.release_id }}'", workflow)
        self.assertIn("--sha256 '${{ steps.release-lock.outputs.archive_sha256 }}'", workflow)
        self.assertIn("--bytes '${{ steps.release-lock.outputs.archive_bytes }}'", workflow)
        self.assertIn("--max-archive-bytes 2000000000", workflow)
        self.assertIn("--max-expanded-bytes 1500000000", workflow)
        self.assertIn("python -m pip install --no-cache-dir", workflow)
        self.assertIn("--requirement tools/gemma3-repair/requirements-linux-cpu.txt", workflow)
        self.assertIn("persist-credentials: false", workflow)

    def test_runtime_paths_are_versioned_and_preparation_uses_the_extracted_workspace(self):
        workflow = _workflow(self)
        for relative in (
            "scripts/run_gemma3_repair_mlp_v2.py",
            "configs/gemma3_repair_mlp_v2.json",
        ):
            self.assertIn(relative, workflow)
        stager = (TOOLS / "stage_runtime.py").read_text(encoding="utf-8")
        self.assertIn('"src/asr_postclean/gemma3_mlp_adapter.py"', stager)
        self.assertIn('--config "$GEMMA3_WORKSPACE/configs/gemma3_repair_mlp_v2.json"', workflow)
        self.assertIn('"$GEMMA3_WORKSPACE/scripts/run_gemma3_repair_mlp_v2.py" prepare', workflow)
        self.assertIn('"$GEMMA3_WORKSPACE/scripts/run_gemma3_repair_mlp_v2.py" --help', workflow)
        self.assertIn("asr_postclean.gemma3_mlp_adapter", workflow)
        self.assertIn("Refuse to replace V1 files", workflow)

    def test_parent_training_candidate_order_and_v1_result_packager_contract(self):
        workflow = _workflow(self)
        parent = workflow.index("--model parent")
        parent_gate = workflow.index("Require the complete parent baseline before training")
        train = workflow.index("--prepared-dir \"$GEMMA3_PREPARED_DIR\" --execute", workflow.index("Train exactly one 64-update MLP continuation"))
        train_gate = workflow.index("Require the completed 64-update MLP adapter")
        candidate = workflow.index("--model candidate")
        package = workflow.index("tools/gemma3-repair/package_results.py")
        upload = workflow.index("Upload diagnostic artifact, including incomplete-run reports")
        self.assertLess(parent, parent_gate)
        self.assertLess(parent_gate, train)
        self.assertLess(train, train_gate)
        self.assertLess(train_gate, candidate)
        self.assertLess(candidate, package)
        self.assertLess(package, upload)
        self.assertEqual(workflow.count("HF_HUB_OFFLINE: '1'"), 7)
        self.assertIn("if: always()", workflow)
        self.assertIn("if-no-files-found: ignore", workflow)
        self.assertIn("gemma3-repair-mlp-v2-review", workflow)

        package_path = ROOT / "tools/gemma3-repair/package_results.py"
        spec = importlib.util.spec_from_file_location("gemma3_mlp_v1_results", package_path)
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        package_results = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = package_results
        spec.loader.exec_module(package_results)
        self.assertEqual(
            tuple(Path(item).name for item in package_results.ADAPTER_OUTPUT_PATHS),
            (
                "README.md",
                "adapter_config.json",
                "adapter_model.safetensors",
                "chat_template.jinja",
                "tokenizer.json",
                "tokenizer_config.json",
                "training_args.bin",
            ),
        )

    def test_only_fetch_step_receives_github_token_and_model_steps_are_offline(self):
        workflow = _workflow(self)
        self.assertEqual(workflow.count("${{ secrets.GITHUB_TOKEN }}"), 1)
        self.assertIn("GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}", workflow)
        self.assertIn("HF_HUB_OFFLINE: '1'", workflow)
        self.assertIn("TRANSFORMERS_OFFLINE: '1'", workflow)
        self.assertIn("unset GH_TOKEN GITHUB_TOKEN HF_TOKEN HUGGINGFACE_HUB_TOKEN", workflow)
        self.assertIn("OMP_NUM_THREADS: '4'", workflow)
        self.assertIn("OPENBLAS_NUM_THREADS: '4'", workflow)

    def test_android_build_skip_marker_is_present_and_v1_workflow_does_not_trigger_on_v2_paths(self):
        build = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertIn("!contains(github.event.head_commit.message, '[gemma3-training-only]')", build)
        v1_workflow = (ROOT / ".github/workflows/train-gemma3-repair.yml").read_text(encoding="utf-8")
        self.assertIn("tools/gemma3-repair/**", v1_workflow)
        self.assertNotIn("tools/gemma3-repair-mlp/**", v1_workflow)


if __name__ == "__main__":
    unittest.main()
