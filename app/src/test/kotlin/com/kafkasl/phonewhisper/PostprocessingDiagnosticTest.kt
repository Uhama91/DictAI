package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class PostprocessingDiagnosticTest {
    @Test fun plainTextWithoutNativeCallIsExpectedRatherThanAnEngineFailure() {
        val value = report(local = null, applied = PostprocessingDiagnostic.Applied.ORIGINAL,
            format = "cleanup", runtime = "litert-lm-gpu-mtp-thinking-off")
        assertTrue(value.contains("mode Texte : aucun appel au LLM prévu"))
        assertTrue(value.contains("Appel natif pour ce résultat : non"))
        assertFalse(value.contains("traitement non exécuté ou indisponible"))
        val missingMail = report(local = null, applied = PostprocessingDiagnostic.Applied.ORIGINAL)
        assertTrue(missingMail.contains("traitement non exécuté ou indisponible"))
        assertFalse(missingMail.contains("aucun appel au LLM prévu"))
    }

    @Test fun gemmaGpuConfigurationAndMissingModelAreExplicit() {
        val value = report(runtime = "litert-lm-gpu-mtp-thinking-off")
        assertTrue(value.contains("gemma-4-E2B-it.litertlm"))
        assertTrue(value.contains("Thinking : désactivé · budget 0 · MTP activé"))
        val unavailable = report(LocalFinishDiagnostic("generated", "backend_error", false, 2),
            PostprocessingDiagnostic.Applied.ORIGINAL, runtime = "model-missing")
        assertTrue(unavailable.contains("model-missing"))
        assertTrue(unavailable.contains("transcription conservée"))
        assertFalse(unavailable.contains("LLM local appliqué"))
    }

    private fun report(
        local: LocalFinishDiagnostic? = LocalFinishDiagnostic("generated", "applied", true, 700),
        applied: PostprocessingDiagnostic.Applied = PostprocessingDiagnostic.Applied.LOCAL_LLM,
        requested: PostprocessingDiagnostic.Requested = PostprocessingDiagnostic.Requested.LOCAL,
        format: String = "email",
        text: String = "Contenu privé à ne pas enregistrer.",
        runtime: String = "arm64-dotprod-fp16",
        suppressed: Boolean = false,
    ) = PostprocessingDiagnostic.report(
        "test", 0, format, requested, applied, local, runtime, 800, 900,
        text, InjectionResult.Copied, suppressed,
    )

    @Test fun reportsAppliedSingleParagraphWithoutClaimingGoodGrouping() {
        val value = report()
        assertTrue(value.contains("LLM local appliqué"))
        assertTrue(value.contains("Appel natif pour ce résultat : oui"))
        assertTrue(value.contains("qualité du découpage non garantie"))
        assertTrue(value.contains("Lignes non vides : 1 ; puces : 0"))
        assertTrue(value.contains("Attente finale locale : 700 ms"))
        assertTrue(value.contains("Publication : copié"))
        assertFalse(value.contains("Contenu privé"))
    }

    @Test fun timeoutAndRejectionsRemainDistinctFromAppliedOutput() {
        val timeout = report(LocalFinishDiagnostic("in_flight", "wait_timeout", true, 5000),
            PostprocessingDiagnostic.Applied.ORIGINAL)
        assertTrue(timeout.contains("transcription conservée"))
        assertTrue(timeout.contains("délai d’attente finale dépassé"))
        val fidelity = report(LocalFinishDiagnostic("generated", "fidelity_rejected", true, 400),
            PostprocessingDiagnostic.Applied.ORIGINAL)
        assertTrue(fidelity.contains("texte non conservé"))
        val vocabulary = report(LocalFinishDiagnostic("generated", "vocabulary_rejected", true, 400),
            PostprocessingDiagnostic.Applied.ORIGINAL)
        assertTrue(vocabulary.contains("vocabulaire non conservé"))
    }

    @Test fun directAndCachedResultsDescribeWhetherNativeWasUsedForTheirResult() {
        val direct = report(LocalFinishDiagnostic("direct", "applied", false, 0),
            PostprocessingDiagnostic.Applied.LOCAL_DIRECT)
        assertTrue(direct.contains("traitement direct, sans appel LLM"))
        assertTrue(direct.contains("Appel natif pour ce résultat : non"))
        val cached = report(LocalFinishDiagnostic("cache", "applied", true, 0))
        assertTrue(cached.contains("résultat déjà calculé pour ce texte et ce format"))
    }

    @Test fun personalFormatDictationAndUnexpectedRuntimeCannotEnterDiagnostic() {
        val value = report(format = "Nom personnel confidentiel", runtime = "secret-runtime",
            text = "• Mot privé\n• Clé secrète\n\nSignature privée")
        assertTrue(value.contains("Format : Personnalisé"))
        assertTrue(value.contains("Lignes non vides : 3 ; puces : 2"))
        for (secret in listOf("Nom personnel", "secret-runtime", "Mot privé", "Clé secrète", "Signature privée"))
            assertFalse(value.contains(secret))
    }

    @Test fun cloudAndOffDoNotClaimLocalInference() {
        val cloud = report(null, PostprocessingDiagnostic.Applied.CLOUD, PostprocessingDiagnostic.Requested.CLOUD)
        assertTrue(cloud.contains("cloud appliqué"))
        assertFalse(cloud.contains("Appel natif"))
        val suppressed = report(null, PostprocessingDiagnostic.Applied.ORIGINAL,
            PostprocessingDiagnostic.Requested.CLOUD, suppressed = true)
        assertTrue(suppressed.contains("Cloud désactivé pour ce champ sensible"))
    }
}
