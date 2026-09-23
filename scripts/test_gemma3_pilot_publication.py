"""Contract tests for the Gemma 3 V3 experimental APK path."""

from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]
WORKFLOW = ROOT / ".github/workflows/build.yml"
BUILD_GRADLE = ROOT / "app/build.gradle.kts"
NOTICE = ROOT / "app/src/gemma3RepairPilot/assets/local-format/gemma3-repair-pilot-NOTICE.txt"
MAIN_NOTICE = ROOT / "app/src/main/assets/local-format/gemma3-repair-pilot-NOTICE.txt"
TEST_GUIDE = ROOT / "docs/gemma3-repair-pilot-test-guide.md"


class Gemma3PilotPublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_build_has_an_exclusive_gemma3_route_and_artifact(self) -> None:
        workflow = self.workflow
        build_job = workflow.split("  build:", 1)[1].split("  publish-test:", 1)[0]
        self.assertIn("gemma3_repair_pilot:", workflow)
        self.assertIn("GEMMA3_PILOT:", workflow)
        self.assertIn("[gemma3-latency-test]", workflow)
        self.assertIn("[gemma3-training-only]", build_job)
        self.assertIn("args+=(-Pgemma3RepairPilot=true)", build_job)
        self.assertIn("variant=gemma3-pilot", build_job)
        self.assertIn("dictai-gemma3-test", build_job)
        build_gradle = BUILD_GRADLE.read_text(encoding="utf-8")
        self.assertIn("gemma3RepairPilot -> 41", build_gradle)
        self.assertIn('gemma3RepairPilot -> "0.9.12-dictai-gemma3-test"', build_gradle)

        conflict = build_job.index("Reject conflicting Gemma pilot selections")
        compile_step = build_job.index("Run unit tests and build selected APK")
        self.assertLess(conflict, compile_step, "conflicting pilot flags must fail before Gradle compiles")
        self.assertIn(
            '"$GEMMA3_PILOT" == true && ( "$GEMMA4_PILOT" == true || "$PROTOTYPE" == true )',
            build_job,
        )

        identity_check = build_job.index("Verify Gemma 3 APK identity")
        package_step = build_job.index("Verify packaged model and prepare download")
        upload_step = build_job.index("Upload APK artifact")
        self.assertLess(identity_check, package_step)
        self.assertLess(identity_check, upload_step)
        self.assertIn("check_gemma3_apk_identity.py", build_job)
        identity_script = (ROOT / "scripts/check_gemma3_apk_identity.py").read_text(encoding="utf-8")
        for expected in (
            "com.uhama.whisperpin",
            "0.9.12-dictai-gemma3-test",
            "EXPECTED_VERSION_CODE = 41",
            "6b37c02704d31553b275a9a5f23c8eb650df04cd59f7b28074e6f2dcadbf9539",
        ):
            self.assertIn(expected, identity_script)

        gpu_publish = workflow.split("  publish-test:", 1)[1].split("  publish-gemma4-pilot:", 1)[0]
        self.assertIn("!inputs.gemma3_repair_pilot", gpu_publish)
        self.assertIn("[gemma3-latency-test]", gpu_publish)

    def test_prerelease_is_gemma3_only_and_describes_the_v3_reference(self) -> None:
        self.assertIn("publish-gemma3-pilot:", self.workflow)
        job = self.workflow.split("publish-gemma3-pilot:", 1)[1]
        for phrase in (
            "needs: build",
            "contents: write",
            "inputs.gemma3_repair_pilot",
            "inputs.publish_prerelease",
            "[gemma3-latency-test]",
            "name: dictai-gemma3-test",
            'tag="gemma3-test-$GITHUB_RUN_ID"',
            "--prerelease --latest=false",
            "DictAI 0.9.12 — corrections Gemma 3",
            "Cette version ajuste la validation des corrections proposées par Gemma 3 et corrige le libellé du diagnostic final.",
            "Les poids V3 et la limite d’attente restent inchangés.",
            "Les gains sur téléphone restent à vérifier.",
            "Gemma 3 270M V3",
            "291 545 280",
            "6c4b7b6654c9638287c31e50fd0bf849f33a2ff2dd93bee685bdd9632f20ecf5",
            "gemma270-v3-model",
            "poids Gemma 3 270M V3 existants, sans les modifier",
            "3 secondes",
            "candidat MLP rejeté lors de l’évaluation du 23 septembre",
            "même identifiant d'application com.uhama.whisperpin",
        ):
            self.assertIn(phrase, job)
        for prohibited_claim in ("amélioration de qualité démontrée", "validé sur Poco", "modèle MLP retenu"):
            self.assertNotIn(prohibited_claim, job)

    def test_notice_and_guide_identify_the_model_without_claiming_phone_results(self) -> None:
        notice = NOTICE.read_text(encoding="utf-8")
        guide = " ".join(TEST_GUIDE.read_text(encoding="utf-8").lower().split())
        self.assertFalse(MAIN_NOTICE.exists(), "the G3-only notice must not change other APK variants")
        for phrase in (
            "Gemma 3 270M V3",
            "291545280",
            "6c4b7b6654c9638287c31e50fd0bf849f33a2ff2dd93bee685bdd9632f20ecf5",
            "gemma270-v3-model",
            "aucun poids",
            "gemma terms",
            "1511ce3bc3f087376c8526b4ad07100bfabb277f",
        ):
            self.assertIn(phrase.lower(), notice.lower())
        for phrase in (
            "l'apk de test comme mise à jour",
            "0.9.12-dictai-gemma3-test** (code 41)",
            "mise en forme",
            "texte corrigé",
            "ne désinstallez pas",
            "3 secondes",
            "après la finalisation de la transcription asr",
            "le texte reconnu reste disponible",
            "les listes, les mails",
            "ne qualifie pas la qualité ou la latence générale du modèle",
            "la version 0.9.12 corrige ce libellé",
            "mlp a été rejeté",
        ):
            self.assertIn(phrase, guide)


if __name__ == "__main__":
    unittest.main(verbosity=2)
