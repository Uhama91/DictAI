package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityServiceStatusTest {
    @Test fun connectedServiceIsReportedAsActive() {
        assertEquals(
            AccessibilityServiceStatus.CONNECTED,
            AccessibilityServiceStatus.resolve(enabledInAndroid = true, connected = true),
        )
    }

    @Test fun enabledAndroidServiceCanBeDisconnectedDuringRestart() {
        assertEquals(
            AccessibilityServiceStatus.ENABLED_NOT_CONNECTED,
            AccessibilityServiceStatus.resolve(enabledInAndroid = true, connected = false),
        )
    }

    @Test fun disabledAndroidServiceNeedsActivation() {
        assertEquals(
            AccessibilityServiceStatus.DISABLED,
            AccessibilityServiceStatus.resolve(enabledInAndroid = false, connected = false),
        )
    }
}
