package com.kafkasl.phonewhisper

import android.content.ComponentName
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilitySetupTest {
    @Test fun enabledButDisconnectedServiceIsNotReportedAsPermissionMissing() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, WhisperAccessibilityService::class.java).flattenToString())
        assertNull(InjectionGateway.current())
        assertEquals(AccessibilityServiceStatus.ENABLED_NOT_CONNECTED, AccessibilitySetup.status(context))
    }
}
