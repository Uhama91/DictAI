package com.kafkasl.phonewhisper

import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsertionDiagnosticsTest {
    @Test fun disconnectedServiceKeepsManualCopyAndRecordsNoPrivateText() {
        val context = RuntimeEnvironment.getApplication()
        val text = "Contenu privé de la dictée 7391"
        assertEquals(InjectionResult.Copied, InsertionDiagnostics.insert(context, text, false))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text)
        val report = InsertionDiagnostics.report(context)
        assertTrue(report.contains("copié uniquement"))
        assertFalse(report.contains(text))
        assertFalse(report.contains("7391"))
    }
}
