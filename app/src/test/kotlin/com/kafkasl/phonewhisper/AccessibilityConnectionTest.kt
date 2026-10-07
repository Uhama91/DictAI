package com.kafkasl.phonewhisper

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityConnectionTest {
    @Test fun unbindingClearsTheConnectionBeforeServiceDestruction() {
        val controller = Robolectric.buildService(WhisperAccessibilityService::class.java).create()
        try {
            val service = controller.get()
            WhisperAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
            assertSame(service, InjectionGateway.current())
            service.onUnbind(Intent())
            assertNull(InjectionGateway.current())
            assertNull(WhisperAccessibilityService.connected)
        } finally { controller.destroy() }
    }
}
