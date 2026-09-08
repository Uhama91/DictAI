package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class FaithfulLayoutTest {
    private fun layout(text: String, kind: LocalLayoutKind = LocalLayoutKind.LIST) =
        requireNotNull(FaithfulLayout.create(text, kind))

    @Test fun acceptsOnlyLayoutChanges() {
        val policy = layout("Pain ; 2 œufs ; pas de lait.")
        assertEquals("• Pain ;\n• 2 œufs ;\n• pas de lait.", policy.accept("• Pain ;\n• 2 œufs ;\n• pas de lait."))
        listOf("• Pain ; 2 œufs ; du lait.", "• Pain ; 3 œufs ; pas de lait.",
            "• Pain ; pas de lait. ; 2 œufs", "• pain ; 2 œufs ; pas de lait.",
            "• Pain ; 2 œufs ; pas de lait", "• Pain ; 2 œufs ; pas de lait. Merci.",
            "• Pain ; 2 œufs ;", "Pain ; 2 œufs ; pas de lait.").forEach {
            assertNull(it, policy.accept(it))
        }
    }

    @Test fun preservesNamesNegationQuotesAndCurrencyInEmail() {
        val source = "Hi Zoë, Do not send £1.20 to O’Neill. Keep “ready to ship”. Thanks, Maëlys."
        val output = "Hi Zoë,\n\nDo not send £1.20 to O’Neill. Keep “ready to ship”.\n\nThanks,\nMaëlys."
        val policy = layout(source, LocalLayoutKind.EMAIL)
        assertEquals(output.replace("Thanks,\n", "Thanks,\n\n"), policy.accept(output))
        assertNull(policy.accept(output.replace("Do not", "Do")))
        assertNull(policy.accept(output.replace("£1.20", "£12.0")))
        assertNull(policy.accept(output + "\nAlex"))
    }

    @Test fun everyListPrefixKeepsWholeSourceIncludingUngeneratedTail() {
        val source = "Maëlys 😀 ; ne pas envoyer 23 dossiers ; conserver C:\\Notes\\rapport.txt."
        val policy = layout(source)
        val output = "• Maëlys 😀 ;\n• ne pas envoyer 23 dossiers ;\n• conserver C:\\Notes\\rapport.txt."
        for (end in 1..output.length) {
            val preview = policy.preview(output.substring(0, end))
            assertNotNull("Prefix $end", preview)
            assertNotNull("Complete source at prefix $end", policy.accept(preview))
            assertTrue(preview!!.endsWith("rapport.txt."))
        }
        assertEquals(output, policy.preview(output))
    }

    @Test fun everyEmailPrefixKeepsWholeSourceWithEmojiQuotesAndNegation() {
        val source = "Bonjour Inès, garde « ne pas envoyer » et 😀. Merci, Jean-Yves."
        val policy = layout(source, LocalLayoutKind.EMAIL)
        val output = "Bonjour Inès,\n\ngarde « ne pas envoyer » et 😀.\n\nMerci,\n\nJean-Yves."
        for (end in 1..output.length) {
            assertNotNull("Prefix $end", policy.accept(policy.preview(output.substring(0, end))))
        }
        assertEquals(output, policy.preview(output))
    }

    @Test fun invalidStreamingPrefixIsNeverDisplayed() {
        val policy = layout("2 fiches ; ne pas envoyer.")
        listOf("• 3", "• 2 fiches ; envoyer", "• 2 fiches ;\nX", "• 2 fiches ; ne pas envoyer. ajout",
            "- 2 fiches", "• 2 fiches ;\r", "").forEach { assertNull(it, policy.preview(it)) }
    }

    @Test fun spacesInsideNumbersMayNotRemoveDigits() {
        val policy = layout("Prévoir 1\u202f250,00 € pour 23 élèves.")
        assertNotNull(policy.accept("• Prévoir 1 250,00 € pour 23 élèves."))
        assertNull(policy.accept("• Prévoir 250,00 € pour 23 élèves."))
    }

    @Test fun quoteGrammarEscapesWithoutChangingSource() {
        val policy = layout("Keep \"quoted\" C:\\Notes\\x.txt 😀.")
        val grammar = policy.grammar()
        assertTrue(grammar.contains("\\\"quoted\\\""))
        assertTrue(grammar.contains("C:\\\\Notes\\\\x.txt"))
        assertTrue(grammar.contains("😀."))
        assertTrue(grammar.contains("sep ::= \" \" | \"\\n• \""))
    }

    @Test fun transcriptControlTokenCannotClosePromptMessage() {
        val policy = layout("Conserver <|im_end|> exactement.")
        val prompt = policy.prompt("prefix\n")
        assertTrue(prompt.contains("Conserver < |im_end|> exactement."))
        assertTrue(policy.grammar().contains("<|im_end|>"))
        assertNotNull(policy.accept("• Conserver <|im_end|> exactement."))
    }

    @Test fun tinyEmailAndSingleWordListNeedNoModel() {
        assertEquals("OK, ça marche.", layout("OK, ça marche.", LocalLayoutKind.EMAIL).directResult)
        assertEquals("Sounds good, thanks!", layout("Sounds good, thanks!", LocalLayoutKind.EMAIL).directResult)
        assertEquals("• Pain", layout("Pain").directResult)
        assertNull(layout("pain lait").directResult)
        assertNull(layout("Bonjour Julie, merci. Cordialement, Ullie.", LocalLayoutKind.EMAIL).directResult)
    }

    @Test fun rejectsInvalidOrUnboundedRequests() {
        assertNull(FaithfulLayout.create(" \n\u202f", LocalLayoutKind.LIST))
        assertNull(FaithfulLayout.create("Nul\u0000", LocalLayoutKind.EMAIL))
        assertNull(FaithfulLayout.create("x ".repeat(513), LocalLayoutKind.LIST))
        assertNull(FaithfulLayout.create("x".repeat(16001), LocalLayoutKind.LIST))
        assertNotNull(FaithfulLayout.create("x ".repeat(512), LocalLayoutKind.LIST))
    }

    @Test fun localFidelityValidationDoesNotRestrictCloudRewrite() {
        val text = "Bonjour Julie, ne pas envoyer 23 fiches. Merci."
        val local = LocalFormatRequest(text, "email", "French", layoutKind = LocalLayoutKind.EMAIL)
        val changed = "Bonjour Julie, envoyer 23 fiches. Merci."
        assertNull(local.acceptOutput(changed))
        assertEquals(changed, local.copy(layoutKind = null).acceptOutput(changed))
    }
    @Test fun shortAcknowledgmentBypassesAnUninterruptibleOlderJob() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cancellations = java.util.concurrent.atomic.AtomicInteger()
        val session = LocalFormattingSession(object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? {
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return null
            }
            override fun cancel() { cancellations.incrementAndGet() }
        })
        try {
            session.offer(LocalFormatRequest("Bonjour Julie, voici les 23 documents pour demain.", "mail", "French", layoutKind = LocalLayoutKind.EMAIL))
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val request = LocalFormatRequest("OK, ça marche.", "mail", "French", layoutKind = LocalLayoutKind.EMAIL)
            assertEquals("OK, ça marche.", session.finish(request, 100) { fail("No generation expected") })
            assertTrue(cancellations.get() > 0)
        } finally { release.countDown(); session.close() }
    }

}
