package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InjectionControllerTest {

    @Test fun `direct text composition accepts only safe null text and valid selections`() {
        assertEquals(
            "texte dicté",
            composeDirectSetText(
                currentText = null,
                selectionStart = 0,
                selectionEnd = 0,
                dictatedText = "texte dicté",
            ),
        )
        assertNull(composeDirectSetText(null, -1, -1, "texte dicté"))
        assertNull(composeDirectSetText(null, 1, 1, "texte dicté"))
        assertNull(composeDirectSetText(null, 0, 1, "texte dicté"))

        assertEquals("bonsoir", composeDirectSetText("bonjour", 3, 7, "soir"))
        assertEquals("abXef", composeDirectSetText("abcdef", 4, 2, "X"))
        assertNull(composeDirectSetText("bonjour", -1, 0, "texte dicté"))
        assertNull(composeDirectSetText("bonjour", 0, 8, "texte dicté"))
    }

    @Test fun `direct success completes without clipboard preparation or paste`() {
        val events = mutableListOf<String>()

        val result = orchestrateInjection(
            directInsert = { events += "direct"; true },
            targetSafety = { events += "safety"; InjectionTargetSafety.Safe },
            prepareClipboard = { events += "clipboard"; true },
            paste = { events += "paste"; true },
        )

        assertEquals(InjectionResult.Inserted, result)
        assertEquals(listOf("direct"), events)
    }

    @Test fun `safe fallback prepares clipboard exactly once before paste`() {
        val events = mutableListOf<String>()

        val result = orchestrateInjection(
            directInsert = { events += "direct"; false },
            targetSafety = { events += "safety"; InjectionTargetSafety.Safe },
            prepareClipboard = { events += "clipboard"; true },
            paste = { events += "paste"; true },
        )

        assertEquals(InjectionResult.Inserted, result)
        assertEquals(listOf("direct", "safety", "clipboard", "paste"), events)
        assertEquals(1, events.count { it == "clipboard" })
    }

    @Test fun `missing controller copies exactly once and reports copy outcome`() {
        listOf(
            true to InjectionResult.Copied,
            false to InjectionResult.Failed,
        ).forEach { (copySucceeds, expected) ->
            var clipboardWrites = 0

            val result = injectOrCopy(
                controller = null,
                text = "texte dicté",
                copyToClipboard = { copiedText ->
                    assertEquals("texte dicté", copiedText)
                    clipboardWrites += 1
                    copySucceeds
                },
            )

            assertEquals(expected, result)
            assertEquals(1, clipboardWrites)
        }
    }

    @Test fun `present controller never uses external clipboard fallback`() {
        InjectionResult.values().forEach { controllerResult ->
            var externalClipboardWrites = 0
            val controller = object : InjectionController {
                override fun inject(text: String): InjectionResult = controllerResult
            }

            val result = injectOrCopy(controller, "texte dicté") {
                externalClipboardWrites += 1
                true
            }

            assertEquals(controllerResult, result)
            assertEquals(0, externalClipboardWrites)
        }
    }

    @Test fun `sensitive clipboard key uses platform constant from API 33`() {
        assertEquals(
            "platform-sensitive-key",
            sensitiveClipboardExtraKey(apiLevel = 33, platformKey = "platform-sensitive-key"),
        )
        assertEquals(
            "android.content.extra.IS_SENSITIVE",
            sensitiveClipboardExtraKey(apiLevel = 32, platformKey = "platform-sensitive-key"),
        )
    }

    @Test fun `failed refresh prevents reading the direct text snapshot`() {
        var snapshotReads = 0

        val rejected = readAfterSuccessfulRefresh(
            refresh = { false },
            read = { snapshotReads += 1; "stale" },
        )
        val accepted = readAfterSuccessfulRefresh(
            refresh = { true },
            read = { snapshotReads += 1; "fresh" },
        )

        assertNull(rejected)
        assertEquals("fresh", accepted)
        assertEquals(1, snapshotReads)
    }

    @Test fun `clipboard preparation failure returns failed without paste`() {
        var pasteAttempts = 0

        val result = orchestrateInjection(
            directInsert = { false },
            targetSafety = { InjectionTargetSafety.Safe },
            prepareClipboard = { false },
            paste = { pasteAttempts += 1; true },
        )

        assertEquals(InjectionResult.Failed, result)
        assertEquals(0, pasteAttempts)
    }

    @Test fun `paste failure reports copied after one clipboard preparation`() {
        var clipboardPreparations = 0

        val result = orchestrateInjection(
            directInsert = { false },
            targetSafety = { InjectionTargetSafety.Safe },
            prepareClipboard = { clipboardPreparations += 1; true },
            paste = { false },
        )

        assertEquals(InjectionResult.Copied, result)
        assertEquals(1, clipboardPreparations)
    }

    @Test fun `sensitive or unknown target never prepares clipboard`() {
        listOf(InjectionTargetSafety.Sensitive, InjectionTargetSafety.Unknown).forEach { safety ->
            var clipboardPreparations = 0
            var pasteAttempts = 0

            val result = orchestrateInjection(
                directInsert = { false },
                targetSafety = { safety },
                prepareClipboard = { clipboardPreparations += 1; true },
                paste = { pasteAttempts += 1; true },
            )

            assertEquals(InjectionResult.Failed, result)
            assertEquals(0, clipboardPreparations)
            assertEquals(0, pasteAttempts)
        }
    }

    @Test fun `end feedback is silent for inserted and explicit otherwise`() {
        assertNull(injectionFeedbackMessage(InjectionResult.Inserted))
        assertEquals(
            "Insertion impossible — texte copié",
            injectionFeedbackMessage(InjectionResult.Copied),
        )
        assertEquals("Insertion impossible", injectionFeedbackMessage(InjectionResult.Failed))
    }
}
