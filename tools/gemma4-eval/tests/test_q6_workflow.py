from __future__ import annotations

import unittest
from pathlib import Path


PACKAGE = Path(__file__).resolve().parents[1]
WORKFLOW = PACKAGE.parents[1] / ".github/workflows/evaluate-gemma4-q6.yml"


class WorkflowContractTests(unittest.TestCase):
    def setUp(self):
        self.text = WORKFLOW.read_text(encoding="utf-8")

    def test_workflow_is_branch_and_path_scoped(self):
        self.assertIn("codex/gemma4-layout-validation", self.text)
        self.assertIn(".github/workflows/evaluate-gemma4-q6.yml", self.text)
        self.assertIn("tools/gemma4-eval/**", self.text)
        self.assertNotIn("workflow_dispatch", self.text)

    def test_workflow_uses_cpu_arm_runner_and_bounded_job(self):
        self.assertIn("runs-on: ubuntu-24.04-arm", self.text)
        self.assertIn("timeout-minutes: 60", self.text)
        self.assertIn("--target llama-server --parallel 2", self.text)
        runner = (PACKAGE / "q6_runner.py").read_text(encoding="utf-8")
        for option in (
            '"--threads", "2"',
            '"--ctx-size", "4096"',
            '"--batch-size", "128"',
            '"--ubatch-size", "128"',
            '"--parallel", "1"',
            '"--n-gpu-layers", "0"',
        ):
            self.assertIn(option, runner)
        self.assertNotIn("-DLLAMA_NATIVE=OFF", self.text)
        self.assertIn("-DGGML_NATIVE=OFF", self.text)

    def test_workflow_never_creates_release_or_persists_token(self):
        self.assertIn("persist-credentials: false", self.text)
        self.assertIn("GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}", self.text)
        self.assertNotIn("gh release create", self.text)
        self.assertNotIn("git push", self.text)
        self.assertNotIn("Authorization", self.text)
        self.assertNotIn("--smoke", self.text)

    def test_workflow_pins_model_client_prompts_and_external_release_identity(self):
        config = (PACKAGE / "q6_release_config.json").read_text(encoding="utf-8")
        for value in (
            "4d8a18db6843337832cebf3f230183241fc3f83ddf5ffbe9f0c798923a6e3cf4",
            "8e6f0ea79068b354980270ba0bc57d3dd36848d3c04210d0fe464802358719f4",
            "af0d7c808150cd27960cd4506261e6b29fc6ac45df13db2694c6c870affbb0d6",
            "234b6fb22392caa6fc05b9bfb39ceae3373d4bac46b625cb881a69175b8bb72d",
            "1511ce3bc3f087376c8526b4ad07100bfabb277f",
        ):
            self.assertIn(value, config)
        self.assertIn("tools/gemma4-eval/q6_release_config.json", self.text)
        self.assertIn('Path(os.environ["GITHUB_ENV"])', self.text)
        self.assertNotIn("vars.GEMMA4_", self.text)


if __name__ == "__main__":
    unittest.main()
