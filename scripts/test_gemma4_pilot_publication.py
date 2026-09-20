"""Contract tests for the experimental Gemma 4 V6 APK publication path."""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]
WORKFLOW = ROOT / ".github/workflows/build.yml"
NOTICE = ROOT / "app/src/main/assets/local-format/gemma4-v6-pilot-NOTICE.txt"
HISTORICAL_NOTICE = ROOT / "app/src/main/assets/local-format/gemma-NOTICE.txt"


class Gemma4PilotPublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.notice = NOTICE.read_text(encoding="utf-8") if NOTICE.exists() else ""

    def test_dedicated_pilot_job_publishes_the_pilot_artifact(self) -> None:
        workflow = self.workflow
        self.assertIn("publish-gemma4-pilot:", workflow)
        build_job = workflow.split("jobs:", 1)[1].split("publish-test:", 1)[0]
        self.assertIn("args+=(-Pgemma4FineTunedPilot=true)", build_job)
        self.assertIn('prepare_ci_apk.py --variant "$variant"', build_job)
        pilot_job = workflow.split("publish-gemma4-pilot:", 1)[1]
        self.assertIn("needs: build", pilot_job)
        self.assertIn("contents: write", pilot_job)
        self.assertIn("inputs.gemma4_finetuned_pilot", pilot_job)
        self.assertIn("inputs.publish_prerelease", pilot_job)
        self.assertIn("[gemma4-v6-test]", pilot_job)
        self.assertIn("name: dictai-gemma4-v6-test", pilot_job)
        self.assertIn('tag="gemma4-v6-test-$GITHUB_RUN_ID"', pilot_job)
        self.assertIn("--prerelease --latest=false", pilot_job)
        self.assertIn("DictAI Gemma 4 V6", pilot_job)

    def test_pilot_notes_state_scope_and_limits(self) -> None:
        pilot_job = " ".join(self.workflow.split("publish-gemma4-pilot:", 1)[1].split()).lower()
        for phrase in (
            "pilote expérimental",
            "progressif",
            "textes, les listes et les e-mails",
            "aucun score",
            "latence sur téléphone",
        ):
            self.assertIn(phrase, pilot_job)
        for prohibited_claim in ("validé sur téléphone", "performance garantie"):
            self.assertNotIn(prohibited_claim, pilot_job)

    def test_gpu_publication_path_remains_separate(self) -> None:
        gpu_job = self.workflow.split("publish-test:", 1)[1].split("publish-gemma4-pilot:", 1)[0]
        self.assertIn("dictai-local-layout-test", gpu_job)
        self.assertIn("DictAI 0.9.5", gpu_job)
        self.assertNotIn("dictai-gemma4-v6-test", gpu_job)

    def test_pilot_notice_records_provenance_and_preserves_litert_notice(self) -> None:
        notice = " ".join(self.notice.split())
        for phrase in (
            "238767527555cb75a05732a84dff5d6ba0dd6809",
            "70af34e20bd4b7a91f0de6b22675850c43922a03",
            "mlx_vlm.convert",
            "adaptateur V6",
            "llama.cpp",
            "Apache-2.0",
            "LiteRT",
            "pas un artefact officiel Google",
        ):
            self.assertIn(phrase, notice)
        self.assertNotIn("poids sont embarqués", notice)
        self.assertTrue(HISTORICAL_NOTICE.is_file())
        result = subprocess.run(
            ["git", "diff", "--quiet", "--", str(HISTORICAL_NOTICE)],
            cwd=ROOT,
            check=False,
        )
        self.assertEqual(result.returncode, 0, "the historical LiteRT notice must remain unchanged")


if __name__ == "__main__":
    unittest.main(verbosity=2)
