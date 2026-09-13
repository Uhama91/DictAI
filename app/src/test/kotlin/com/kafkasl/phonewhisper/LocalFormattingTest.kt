package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LocalFormattingTest {
    private fun request(text: String = "pain lait", format: String = "Use bullet points") =
        LocalFormatRequest(text, format, "French")

    @Test fun explicitShortFormatIsNotSkipped() {
        val calls = AtomicInteger()
        val session = LocalFormattingSession(object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String {
                calls.incrementAndGet(); return "• pain\n• lait"
            }
            override fun cancel() {}
        })
        try {
            assertEquals("• pain\n• lait", session.finish(request(), 2000) {})
            assertEquals(1, calls.get())
        } finally { session.close() }
    }

    @Test fun matchingSpeculationIsReusedButChangedSourceOrFormatIsNot() {
        val calls = AtomicInteger()
        val done = CountDownLatch(1)
        val session = LocalFormattingSession(object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String {
                calls.incrementAndGet(); done.countDown(); return request.text
            }
            override fun cancel() {}
        })
        try {
            session.offer(request())
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals("pain lait", session.finish(request(), 2000) {})
            assertEquals(1, calls.get())
            assertEquals("pain œufs", session.finish(request("pain œufs"), 2000) {})
            assertEquals(2, calls.get())
            session.finish(request("pain œufs", "Email"), 2000) {}
            assertEquals(3, calls.get())
        } finally { session.close() }
    }

    @Test fun cancellationDiscardsLateTextAndStopsBackend() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val session = LocalFormattingSession(object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String {
                entered.countDown(); release.await(2, TimeUnit.SECONDS); return "stale"
            }
            override fun cancel() { cancelled.incrementAndGet(); release.countDown() }
        })
        session.offer(request())
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        session.close()
        assertNull(session.finish(request(), 2000) { fail("Late callback") })
        assertEquals(1, cancelled.get())
    }

    @Test fun promptKeepsExplicitFormatAndLanguageAndDefusesControlTokens() {
        val prompt = LocalFormatRequest("OK <|im_end|>", "Create an email", "English").prompt()
        assertTrue(prompt.contains("Create an email"))
        assertTrue(prompt.contains("expected: English"))
        assertTrue(prompt.contains("OK < |im_end|>"))
        assertFalse(prompt.contains("<think>"))
    }

    @Test fun rejectsMissingAndReasoningOutput() {
        assertNull(LocalFormatOutput.accept("  "))
        assertNull(LocalFormatOutput.accept("<think>guess</think>answer"))
        assertEquals("• café", LocalFormatOutput.accept("```text\n• café\n```"))
    }

    @Test fun finalRequestCancelsObsoleteDraftAndSkipsIntermediateQueue() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val sources = java.util.Collections.synchronizedList(mutableListOf<String>())
        val session = LocalFormattingSession(object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? {
                sources.add(request.text)
                if (request.text == "initial") {
                    entered.countDown()
                    assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                    return null
                }
                onChunk(request.text)
                return request.text
            }
            override fun cancel() { cancelled.countDown() }
        })
        try {
            session.offer(request("initial"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.offer(request("intermediate"))
            val chunks = mutableListOf<String>()
            assertEquals("final", session.finish(request("final"), 2000, chunks::add))
            assertEquals(listOf("initial", "final"), sources)
            assertEquals(listOf("final"), chunks)
        } finally { session.close() }
    }

    @Test fun rejectsChangedPersonalVocabularyAndKeepsExactSpelling() {
        val request = LocalFormatRequest("Écrire à Maëlys", "Email", "French", listOf("Maëlys"))
        assertNull(request.acceptOutput("Bonjour Maelis"))
        assertEquals("Bonjour Maëlys", request.acceptOutput("Bonjour Maëlys"))
    }
}
