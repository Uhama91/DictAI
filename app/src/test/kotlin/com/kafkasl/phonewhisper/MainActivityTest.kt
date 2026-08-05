package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @Test
    fun OpenRouter_credential_deletion_feedback_respects_the_storage_result() {
        assertEquals("Clé supprimée", MainActivity.credentialDeletionFeedback(true))
        assertEquals("Suppression de la clé impossible", MainActivity.credentialDeletionFeedback(false))
    }
}
