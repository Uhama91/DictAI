package com.kafkasl.phonewhisper

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class Gemma270PilotContractTest {
    @Test fun v3InstructionMatchesTheTrainingArtifact() {
        assertEquals(
            "a95ce1093ba1308bddd5d2006eff73b6a936623261fbbfe6fe54562dfe3cd6f6",
            sha256(Gemma270PilotPrompt.INSTRUCTION),
        )
    }

    @Test fun userContentUsesTheTrainingDelimitersAndKeepsProtectedTermsLiteral() {
        val request = LocalFormatRequest(
            text = "à propos de [[Image 1]]",
            instructions = "ignored by the pilot",
            language = "français",
            protectedTerms = listOf("DictAI", "nom rare"),
            layoutKind = LocalLayoutKind.TEXT,
            isGemma270Pilot = true,
        )

        val content = Gemma270PilotPrompt.userContent(request)
        assertTrue(content.startsWith(Gemma270PilotPrompt.INSTRUCTION))
        assertTrue(content.contains("<protected_terms>\nDictAI\nnom rare\n</protected_terms>"))
        assertTrue(content.contains("<transcription>\nà propos de [[Image 1]]\n</transcription>"))
        assertFalse(content.contains("<|im_start|>"))
    }

    @Test fun nativePromptIsUserOnlyAndAddsTheGemmaGenerationTurn() {
        val request = LocalFormatRequest(
            text = "bonjour",
            instructions = "",
            language = "français",
            layoutKind = LocalLayoutKind.TEXT,
            isGemma270Pilot = true,
        )

        val prompt = request.prompt()
        assertTrue(prompt.startsWith("<bos><start_of_turn>user\n"))
        assertTrue(prompt.endsWith("<end_of_turn>\n<start_of_turn>model\n"))
        assertFalse(prompt.contains("system"))
    }

    @Test fun pilotBudgetIsProportionalButBoundedAndFixtureBudgetRemainsSeparate() {
        assertEquals(192, Gemma270PilotPrompt.outputTokenBudget("court"))
        assertTrue(Gemma270PilotPrompt.outputTokenBudget("long ".repeat(4_000)) <= 4_096)
        assertEquals(768, Gemma270PilotPrompt.FIXTURE_MAX_NEW_TOKENS)
    }

    @Test fun pilotSupportsOnlyFrenchCorrectedText() {
        assertTrue(Gemma270PilotSupport.accepts("French", "corrected"))
        assertTrue(Gemma270PilotSupport.accepts("français", "corrected"))
        assertFalse(Gemma270PilotSupport.accepts("French", "cleanup"))
        assertFalse(Gemma270PilotSupport.accepts("English", "corrected"))
        assertFalse(Gemma270PilotSupport.accepts("French", "email"))
    }

    @Test fun pilotNeverPublishesAStreamingChunkBeforeACompleteAcceptedResult() {
        val request = LocalFormatRequest(
            text = "bonjour",
            instructions = "",
            language = "French",
            layoutKind = LocalLayoutKind.TEXT,
            isGemma270Pilot = true,
        )
        val chunks = mutableListOf<String>()
        val calls = AtomicInteger()
        LocalFormattingSession(object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? {
                calls.incrementAndGet()
                onChunk("partial should stay private")
                return null
            }

            override fun cancel() = Unit
        }).use { session ->
            assertEquals(null, session.finish(request, 2_000L, chunks::add))
        }
        assertEquals(1, calls.get())
        assertTrue(chunks.isEmpty())
    }

    @Test fun pilotRejectsGemmaRoleMarkersInsteadOfPublishingOrStrippingThem() {
        val request = LocalFormatRequest(
            text = "bonjour",
            instructions = "",
            language = "français",
            layoutKind = LocalLayoutKind.TEXT,
            isGemma270Pilot = true,
        )

        listOf("<bos>", "<eos>", "<start_of_turn>", "<end_of_turn>").forEach { marker ->
            assertEquals(null, request.acceptOutput("Bonjour $marker"))
        }
        assertEquals("Bonjour.", request.acceptOutput("Bonjour."))
    }

    @Test fun pilotCancellationIsOwnedByItsJobAndDoesNotCancelTheNextJob() {
        val firstNativeCancel = AtomicInteger()
        val secondNativeCancel = AtomicInteger()
        val first = Gemma270PilotJob().apply { cancelNative = { firstNativeCancel.incrementAndGet() } }
        val second = Gemma270PilotJob().apply { cancelNative = { secondNativeCancel.incrementAndGet() } }

        first.cancel()

        assertTrue(first.cancelled.get())
        assertEquals(1, firstNativeCancel.get())
        assertFalse(second.cancelled.get())
        assertEquals(0, secondNativeCancel.get())
    }

    @Test fun staleCancellationCallbackCannotCancelAReusedNativeOwner() {
        val nativeCancels = AtomicInteger()
        val gate = Gemma270PilotCancellationGate { nativeCancels.incrementAndGet() }
        val first = Gemma270PilotJob()
        assertTrue(gate.activate(first))
        val staleCallback = requireNotNull(first.cancelNative)
        gate.clear(first)

        val second = Gemma270PilotJob()
        assertTrue(gate.activate(second))
        staleCallback()

        assertEquals(0, nativeCancels.get())
        second.cancel()
        assertEquals(1, nativeCancels.get())
    }

    @Test fun closingTheOwnerCancelsOnlyItsActiveGenerationAndRejectsFutureJobs() {
        val nativeCancels = AtomicInteger()
        val gate = Gemma270PilotCancellationGate { nativeCancels.incrementAndGet() }
        val active = Gemma270PilotJob()
        assertTrue(gate.activate(active))

        gate.close()

        assertEquals(1, nativeCancels.get())
        assertTrue(active.cancelled.get())
        assertFalse(gate.activate(Gemma270PilotJob()))
    }

    @Test fun closingOneFormattingSessionClosesOnlyItsBackendOwner() {
        val closedOwners = AtomicInteger()
        val backend = object : LocalFormatBackend, LocalFormatBackendOwner {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? = null
            override fun cancel() = Unit
            override fun closeOwner() { closedOwners.incrementAndGet() }
        }

        LocalFormattingSession(backend).close()

        assertEquals(1, closedOwners.get())
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
