"""Contract tests for the experimental Gemma 4 V6 APK publication path."""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]
WORKFLOW = ROOT / ".github/workflows/build.yml"
BUILD_GRADLE = ROOT / "app/build.gradle.kts"
NOTICE = ROOT / "app/src/main/assets/local-format/gemma4-v6-pilot-NOTICE.txt"
HISTORICAL_NOTICE = ROOT / "app/src/main/assets/local-format/gemma-NOTICE.txt"


class Gemma4PilotPublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.build_gradle = BUILD_GRADLE.read_text(encoding="utf-8")
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
        self.assertIn("DictAI 0.9.10", pilot_job)

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

    def test_latency_diagnostic_version_is_pilot_only(self) -> None:
        self.assertIn("gemma4FineTunedPilot -> 39", self.build_gradle)
        self.assertIn("else -> 35", self.build_gradle)
        self.assertIn('gemma4FineTunedPilot -> "0.9.10-dictai-latency-test"', self.build_gradle)
        self.assertIn('localFormatPrototype -> "0.9.6-dictai-gemma-test"', self.build_gradle)
        self.assertIn('else -> "0.9.6-dictai"', self.build_gradle)

    def test_latency_diagnostic_notes_describe_bounded_poco_measurement(self) -> None:
        pilot_job = " ".join(self.workflow.split("publish-gemma4-pilot:", 1)[1].split()).lower()
        for phrase in (
            "dictai 0.9.10 — diagnostic des dictées et des notes",
            "ce diagnostic concerne les dictées et les notes",
            "ne revendique aucune accélération ni amélioration de qualité nouvelle",
            "mêmes poids gemma4 v6 du checkpoint 1956",
            "la même signature",
            "l'applicationid com.uhama.whisperpin",
            "apk ne contient aucun poids",
            "poids q6_k avec les matrices q/o en f16",
            "gemma4-v6-1956-q6-evaluation-20260920",
            "mesurer la latence — 6 textes",
            "18 appels synthétiques",
            "quelques minutes",
            "copier le rapport",
            "banc utilise uniquement des textes fictifs",
            "diagnostic d'une dictée personnelle",
            "durées et compteurs",
            "sans son texte",
            "temps asr",
            "correction",
            "partiels",
            "corrige le diagnostic resté ancien après une prise de notes",
            "après terminer, la note dispose de son propre rapport à jour",
            "le diagnostic concerne aussi une note",
            "version installée",
            "dernière dictée",
            "affiche par défaut",
            "accès au dernier formatage",
            "mise à jour en code 39",
            "poco f7",
            "tablette pad 7",
        ):
            self.assertIn(phrase, pilot_job)
        for prohibited_claim in (
            "comparaison avec gemma3 disponible",
            "gemma3 est sélectionné",
            "gpu activé",
            "qualité validée",
            "performance garantie",
        ):
            self.assertNotIn(prohibited_claim, pilot_job)

    def test_latency_diagnostic_preserves_existing_artifact_and_release_marker(self) -> None:
        workflow = self.workflow
        self.assertIn("contains(github.event.head_commit.message, '[gemma4-v6-test]')", workflow)
        self.assertIn("'dictai-gemma4-v6-test'", workflow)
        self.assertIn('tag="gemma4-v6-test-$GITHUB_RUN_ID"', workflow)
        self.assertIn("--prerelease --latest=false", workflow)
        self.assertNotIn("dictai-gemma4-latency-test", workflow)

    def test_latency_diagnostic_keeps_application_identity_and_nonpilot_versions(self) -> None:
        self.assertIn('applicationId = "com.uhama.whisperpin"', self.build_gradle)
        self.assertIn("gemma4FineTunedPilot -> 39", self.build_gradle)
        self.assertIn("else -> 35", self.build_gradle)
        self.assertIn('localFormatPrototype -> "0.9.6-dictai-gemma-test"', self.build_gradle)
        self.assertIn('else -> "0.9.6-dictai"', self.build_gradle)
        pilot_job = " ".join(self.workflow.split("publish-gemma4-pilot:", 1)[1].split()).lower()
        self.assertIn("mêmes poids gemma4 v6", pilot_job)
        self.assertIn("la même signature", pilot_job)
        self.assertIn("l'applicationid com.uhama.whisperpin", pilot_job)

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
