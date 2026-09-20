package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @Test
    fun OpenRouter_credential_deletion_feedback_respects_the_storage_result() {
        assertEquals("Clé supprimée", MainActivity.credentialDeletionFeedback(true))
        assertEquals("Suppression de la clé impossible", MainActivity.credentialDeletionFeedback(false))
    }

    @Test
    fun gemma_install_labels_come_from_the_store_contract() {
        assertEquals("Installer ${GemmaModelStore.MODEL_TITLE}", MainActivity.gemmaInstallTitle())
        assertEquals(
            "${GemmaModelStore.formatBytes(GemmaModelStore.EXPECTED_SIZE_BYTES)} · téléchargement reprenable · puis utilisation hors ligne",
            MainActivity.gemmaInstallSubtitle(installed = false),
        )
        assertEquals(
            "Installé · hors ligne · texte corrigé, listes et mails",
            MainActivity.gemmaInstallSubtitle(installed = true),
        )
    }

    @Test
    fun benchmark_subtitle_names_the_selected_runtime() {
        assertEquals("CPU · pilote Gemma · sans thinking", MainActivity.benchmarkSubtitle(pilot = true))
        assertEquals("GPU · LiteRT-LM · sans thinking", MainActivity.benchmarkSubtitle(pilot = false))
    }
}
