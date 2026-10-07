package com.kafkasl.phonewhisper

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Requires the service enabled on an isolated emulator; never modifies a user's permissions. */
class AccessibilityInsertionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var targetToken = ""

    @Test fun insertsIntoAnEmptyExternalFieldWithoutItsPlaceholder() {
        launchTarget("")
        assertInsertion("Bonjour.", "Bonjour.")
    }

    @Test fun preservesExistingTextInAnExternalField() {
        launchTarget("Déjà écrit. ")
        assertInsertion("Bonjour.", "Déjà écrit. Bonjour.")
    }

    @Test fun insertsIntoAnEmptyHintFieldEvenWhenTheAppDoesNotImplementPaste() {
        launchTarget("", withoutPaste = true)
        assertInsertion("Bonjour.", "Bonjour.")
    }

    private fun launchTarget(initial: String, withoutPaste: Boolean = false) {
        targetToken = java.util.UUID.randomUUID().toString()
        instrumentation.targetContext.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context.packageName, InjectionTargetActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("initial", initial)
            putExtra("withoutPaste", withoutPaste)
            putExtra("token", targetToken)
        })
        waitFor { focusedText() != null }
    }

    private fun assertInsertion(text: String, expected: String) {
        var result: InjectionResult? = null
        instrumentation.runOnMainSync { result = InjectionGateway.current()?.inject(text) }
        assertEquals("Accessibility service must insert into the focused external editor", InjectionResult.Inserted, result)
        waitFor { focusedText() == expected }
        assertEquals(expected, focusedText())
    }

    private fun focusedText(): String? {
        val service = WhisperAccessibilityService.connected ?: return null
        val root = service.rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != instrumentation.context.packageName) return null
            val matches = root.findAccessibilityNodeInfosByText(targetToken)
            val isCurrentTarget = matches.isNotEmpty()
            matches.forEach { it.recycle() }
            if (!isCurrentTarget) return null
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            return try { node.text?.toString().orEmpty() } finally { node.recycle() }
        } finally { root.recycle() }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("External editor did not reach the expected state (service connected=${InjectionGateway.current() != null})", predicate())
    }
}
