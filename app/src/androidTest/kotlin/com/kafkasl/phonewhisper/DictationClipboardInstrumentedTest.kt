package com.kafkasl.phonewhisper

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictationClipboardInstrumentedTest {

    private companion object {
        const val READ_CLIPBOARD_IN_BACKGROUND =
            "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
    }

    @Test fun dictationClipIsReadableAndHasNoPrivacyExtras() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val uiAutomation = instrumentation.uiAutomation
        var shellIdentityAdopted = false

        try {
            var copied = false
            instrumentation.runOnMainSync {
                copied = DictationClipboard.copy(context, "dictée lisible")
            }
            assertTrue(copied)

            uiAutomation.adoptShellPermissionIdentity(READ_CLIPBOARD_IN_BACKGROUND)
            shellIdentityAdopted = true
            var clip: ClipData? = null
            instrumentation.runOnMainSync {
                clip = clipboard.primaryClip
            }

            assertNotNull(clip)
            val systemClip = checkNotNull(clip)
            assertEquals("dictée lisible", systemClip.getItemAt(0).coerceToText(context).toString())
            assertNull(systemClip.description.extras)
        } finally {
            try {
                instrumentation.runOnMainSync {
                    clipboard.clearPrimaryClip()
                }
            } finally {
                if (shellIdentityAdopted) {
                    uiAutomation.dropShellPermissionIdentity()
                }
            }
        }
    }
}
