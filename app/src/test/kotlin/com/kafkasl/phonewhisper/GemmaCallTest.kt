package com.kafkasl.phonewhisper

import com.google.ai.edge.litertlm.Message
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Host-only transport checks: these do not assert Android GPU availability or model quality. */
class GemmaCallTest {
    private val request = LocalFormatRequest("Bonjour Léa", "Mail", "French", layoutKind = LocalLayoutKind.EMAIL)

    @Test fun streamingConsumersReceiveTheCompletePrefixForProjection() {
        val prefixes = mutableListOf<String>()
        val call = GemmaCall(request, prefixes::add, {}, { 0L }) { false }
        call.callback.onMessage(Message.model("Bonjour"))
        call.callback.onMessage(Message.model(" Léa"))
        assertEquals(listOf("Bonjour", "Bonjour Léa"), prefixes)
        assertEquals("Bonjour Léa", call.output())
    }

    @Test fun unsupportedOrOversizedLayoutsCannotStartNativeWork() {
        val inputs = listOf(
            request.copy(layoutKind = null),
            request.copy(text = "mot ".repeat(513)),
            request.copy(text = "Bonjour\u0000Léa"),
        )
        inputs.forEach { input ->
            val call = GemmaCall(input, {}, {}, { 0L }) { false }
            assertNull(call.policy)
            assertFalse(call.start { fail("Invalid layout must be rejected before native entry") })
        }
    }

    @Test fun cancellationDiscardsCompletionAndLateFragments() {
        val chunks = mutableListOf<String>()
        val call = GemmaCall(request, chunks::add, {}, { 0L }) { false }
        call.callback.onMessage(Message.model("Bonjour"))
        call.cancel()
        call.callback.onMessage(Message.model(" une modification tardive"))
        call.callback.onDone()
        assertNull(call.result.get(1, TimeUnit.SECONDS))
        assertEquals(listOf("Bonjour"), chunks)
        assertEquals("Bonjour", call.output())
        assertTrue(call.terminal.await(1, TimeUnit.SECONDS))
    }

    @Test fun changingTheSessionEpochSuppressesAnAlreadyQueuedCallback() {
        val stale = AtomicBoolean()
        val chunks = mutableListOf<String>()
        val call = GemmaCall(request, chunks::add, {}, { 0L }) { stale.get() }
        stale.set(true)
        call.callback.onMessage(Message.model("Bonjour"))
        assertEquals(emptyList<String>(), chunks)
        assertEquals("", call.output())
        assertFalse(call.start { fail("A stale request must not enter native inference") })
    }

    @Test fun deadlineRejectsFragmentsEvenBeforeTheWorkerPollsCancellation() {
        val now = AtomicLong(10L)
        val chunks = mutableListOf<String>()
        val call = GemmaCall(request, chunks::add, {}, now::get) { false }
        now.addAndGet(LocalFormatEngine.GENERATION_DEADLINE_MS)
        call.callback.onMessage(Message.model("Bonjour"))
        assertTrue(call.expired())
        assertEquals(emptyList<String>(), chunks)
        assertFalse(call.start { fail("An expired request must not enter native inference") })
    }

    @Test fun aThoughtChannelFailsInsteadOfExposingReasoningAsFinalText() {
        val chunks = mutableListOf<String>()
        val call = GemmaCall(request, chunks::add, {}, { 0L }) { false }
        call.callback.onMessage(Message.model(channels = mapOf("thought" to "unexpected reasoning")))
        call.callback.onMessage(Message.model("Bonjour Léa"))
        assertTrue(call.rejectedThinking)
        assertNotNull(call.nativeFailure)
        assertEquals(emptyList<String>(), chunks)
        assertEquals("", call.output())
    }

    @Test fun cancellationDoesNotWaitForAConsumerHoldingAnotherLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = GemmaCall(request, {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
        }, {}, { 0L }) { false }
        val callback = thread {
            call.callback.onMessage(Message.model("Bonjour"))
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            call.cancel()
            assertNull(call.result.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            callback.join(2_000L)
        }
        assertFalse(callback.isAlive)
    }

    @Test fun nativeErrorUnblocksConversationCleanupWithoutPublishingText() {
        val call = GemmaCall(request, {}, {}, { 0L }) { false }
        call.callback.onError(IllegalStateException("synthetic native failure"))
        assertTrue(call.terminal.await(1, TimeUnit.SECONDS))
        assertNotNull(call.nativeFailure)
        assertFalse(call.result.isDone)
    }
}
