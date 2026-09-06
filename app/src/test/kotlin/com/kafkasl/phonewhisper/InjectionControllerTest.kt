package com.kafkasl.phonewhisper

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun `present controller always copies even when insertion succeeds or fails`() {
        InjectionResult.values().forEach { controllerResult ->
            var externalClipboardWrites = 0
            val controller = object : InjectionController {
                override fun inject(text: String): InjectionResult = controllerResult
            }

            val result = injectOrCopy(controller, "texte dicté") {
                externalClipboardWrites += 1
                true
            }

            assertEquals(if (controllerResult == InjectionResult.Inserted) InjectionResult.Inserted else InjectionResult.Copied, result)
            assertEquals(1, externalClipboardWrites)
        }
    }

    @Test fun `throwing controller keeps recoverable clipboard text`() {
        val controller = object : InjectionController {
            override fun inject(text: String): InjectionResult = error("Disconnected")
        }
        assertEquals(InjectionResult.Copied, injectOrCopy(controller, "bonjour") { true })
    }

    @Test fun `clipboard failure does not prevent successful insertion`() {
        val controller = object : InjectionController {
            override fun inject(text: String): InjectionResult = InjectionResult.Inserted
        }
        assertEquals(InjectionResult.Inserted, injectOrCopy(controller, "bonjour") { false })
    }

    @Test fun `unfocused target requests focus then refreshes before reading fresh state`() {
        val events = mutableListOf<String>()

        val result = focusAndReadFresh(
            initiallyFocused = false,
            requestFocus = { events += "focus"; true },
            refresh = { events += "refresh"; true },
            readFresh = { events += "read"; "focused" },
        )

        assertEquals("focused", result)
        assertEquals(listOf("focus", "refresh", "read"), events)
    }

    @Test fun `refused focus stops before refresh and fresh read`() {
        val events = mutableListOf<String>()

        val result = focusAndReadFresh(
            initiallyFocused = false,
            requestFocus = { events += "focus"; false },
            refresh = { events += "refresh"; true },
            readFresh = { events += "read"; "stale" },
        )

        assertNull(result)
        assertEquals(listOf("focus"), events)
    }

    @Test fun `refused refresh stops before fresh read`() {
        val events = mutableListOf<String>()

        val result = focusAndReadFresh(
            initiallyFocused = false,
            requestFocus = { events += "focus"; true },
            refresh = { events += "refresh"; false },
            readFresh = { events += "read"; "stale" },
        )

        assertNull(result)
        assertEquals(listOf("focus", "refresh"), events)
    }

    @Test fun `focused target refreshes without requesting focus`() {
        val events = mutableListOf<String>()

        val result = focusAndReadFresh(
            initiallyFocused = true,
            requestFocus = { events += "focus"; true },
            refresh = { events += "refresh"; true },
            readFresh = { events += "read"; "focused" },
        )

        assertEquals("focused", result)
        assertEquals(listOf("refresh", "read"), events)
    }

    @Test fun `focused candidate scores ahead of unfocused custom paste candidate`() {
        val focusedScore = injectionCandidateScore(
            isFocused = true,
            isEditable = false,
            isEditText = false,
            isTerminalView = false,
            hasCustomPasteAction = false,
        )
        val unfocusedCustomPasteScore = injectionCandidateScore(
            isFocused = false,
            isEditable = false,
            isEditText = false,
            isTerminalView = false,
            hasCustomPasteAction = true,
        )

        assertTrue(focusedScore > unfocusedCustomPasteScore)
    }

    @Test fun `focused editable target outranks focused custom paste target`() {
        val focusedEditableScore = injectionCandidateScore(
            isFocused = true,
            isEditable = true,
            isEditText = false,
            isTerminalView = false,
            hasCustomPasteAction = false,
        )
        val focusedCustomPasteScore = injectionCandidateScore(
            isFocused = true,
            isEditable = false,
            isEditText = false,
            isTerminalView = false,
            hasCustomPasteAction = true,
        )

        assertTrue(focusedEditableScore > focusedCustomPasteScore)
    }

    @Test fun `focused Keep-like body is selected instead of unfocused title`() {
        val title = TestInjectionTarget(
            name = "title",
            isFocused = false,
            isKnownEditable = true,
            isKnownFallback = true,
        )
        val body = TestInjectionTarget(
            name = "body",
            isFocused = true,
            isKnownEditable = true,
            isKnownFallback = true,
        )

        val selected = selectInjectionTarget(
            candidates = listOf(title, body),
            isFocused = { it.isFocused },
            isKnownEditable = { it.isKnownEditable },
            isKnownFallback = { it.isKnownFallback },
        )

        assertSame(body, selected)
    }

    @Test fun `target selection returns one original candidate for direct and fallback`() {
        val body = TestInjectionTarget(
            name = "body",
            isFocused = true,
            isKnownEditable = true,
            isKnownFallback = true,
        )
        val title = TestInjectionTarget(
            name = "title",
            isFocused = false,
            isKnownEditable = true,
            isKnownFallback = true,
        )

        val selected = selectInjectionTarget(
            candidates = listOf(title, body),
            isFocused = { it.isFocused },
            isKnownEditable = { it.isKnownEditable },
            isKnownFallback = { it.isKnownFallback },
        )
        val directTarget = selected
        val fallbackTarget = selected

        assertSame(body, directTarget)
        assertSame(directTarget, fallbackTarget)
    }

    @Test fun `fresh fallback that is no longer known is unknown and never prepares clipboard`() {
        val events = mutableListOf<String>()

        val result = orchestrateInjection(
            directInsert = { events += "direct"; false },
            targetSafety = {
                events += "safety"
                freshFallbackTargetSafety(
                    isKnownFallbackTarget = false,
                    isPassword = false,
                    inputType = InputType.TYPE_CLASS_TEXT,
                )
            },
            prepareClipboard = { events += "clipboard"; true },
            paste = { events += "paste"; true },
        )

        assertEquals(InjectionResult.Failed, result)
        assertEquals(listOf("direct", "safety"), events)
    }

    @Test fun `fresh known ordinary fallback is safe`() {
        assertEquals(
            InjectionTargetSafety.Safe,
            freshFallbackTargetSafety(
                isKnownFallbackTarget = true,
                isPassword = false,
                inputType = InputType.TYPE_CLASS_TEXT,
            ),
        )
    }

    @Test fun `fresh known password fallback is sensitive`() {
        assertEquals(
            InjectionTargetSafety.Sensitive,
            freshFallbackTargetSafety(
                isKnownFallbackTarget = true,
                isPassword = true,
                inputType = InputType.TYPE_CLASS_TEXT,
            ),
        )
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

    private data class TestInjectionTarget(
        val name: String,
        val isFocused: Boolean,
        val isKnownEditable: Boolean,
        val isKnownFallback: Boolean,
    )
}
