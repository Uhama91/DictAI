package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRestartPolicyTest {
    @Test
    fun `boot completed is accepted`() {
        assertTrue(OverlayRestartPolicy.decide(OverlayRestartPolicy.ACTION_BOOT_COMPLETED).shouldStart)
    }

    @Test
    fun `package replaced is accepted`() {
        assertTrue(OverlayRestartPolicy.decide(OverlayRestartPolicy.ACTION_MY_PACKAGE_REPLACED).shouldStart)
    }

    @Test
    fun `xiaomi quick boot is accepted`() {
        assertTrue(OverlayRestartPolicy.decide(OverlayRestartPolicy.ACTION_QUICKBOOT_POWERON).shouldStart)
    }

    @Test
    fun `unknown action is rejected`() {
        val descriptor = OverlayRestartPolicy.decide("com.example.UNRELATED_ACTION")

        assertFalse(descriptor.shouldStart)
        assertNull(descriptor.serviceAction)
    }

    @Test
    fun `accepted action creates start descriptor without service action`() {
        listOf(
            OverlayRestartPolicy.ACTION_BOOT_COMPLETED,
            OverlayRestartPolicy.ACTION_MY_PACKAGE_REPLACED,
            OverlayRestartPolicy.ACTION_QUICKBOOT_POWERON,
        ).forEach { action ->
            val descriptor = OverlayRestartPolicy.decide(action)

            assertEquals(true, descriptor.shouldStart)
            assertNull(descriptor.serviceAction)
        }
    }
}
