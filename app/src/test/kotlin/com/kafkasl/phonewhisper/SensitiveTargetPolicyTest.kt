package com.kafkasl.phonewhisper

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveTargetPolicyTest {
    @Test fun `password flag and text web visible and numeric password variations are sensitive`() {
        assertTrue(SensitiveInputPolicy.isSensitive(isPassword = true, InputType.TYPE_CLASS_TEXT))
        assertTrue(SensitiveInputPolicy.isSensitive(false, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(SensitiveInputPolicy.isSensitive(false, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(SensitiveInputPolicy.isSensitive(false, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(SensitiveInputPolicy.isSensitive(false, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test fun `ordinary text email and numeric fields are not sensitive`() {
        assertFalse(SensitiveInputPolicy.isSensitive(false, InputType.TYPE_CLASS_TEXT))
        assertFalse(SensitiveInputPolicy.isSensitive(false, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(SensitiveInputPolicy.isSensitive(false, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL))
    }

    @Test fun `cloud target policy snapshots requested allowed and sensitive suppression states`() {
        assertFalse(CloudSensitiveTargetPolicy.snapshot(cloudRequested = false, targetSensitive = true).cloudAllowed)
        assertFalse(CloudSensitiveTargetPolicy.snapshot(cloudRequested = false, targetSensitive = true).suppressedForSensitiveTarget)
        assertTrue(CloudSensitiveTargetPolicy.snapshot(cloudRequested = true, targetSensitive = false).cloudAllowed)
        assertFalse(CloudSensitiveTargetPolicy.snapshot(cloudRequested = true, targetSensitive = false).suppressedForSensitiveTarget)
        assertFalse(CloudSensitiveTargetPolicy.snapshot(cloudRequested = true, targetSensitive = true).cloudAllowed)
        assertTrue(CloudSensitiveTargetPolicy.snapshot(cloudRequested = true, targetSensitive = true).suppressedForSensitiveTarget)
    }

    @Test fun `legacy fake controllers default to sensitive without requiring a new override`() {
        val fake = object : InjectionController {
            override fun inject(text: String): Boolean = true
        }

        assertTrue(fake.isActiveTargetSensitive())
    }
}
