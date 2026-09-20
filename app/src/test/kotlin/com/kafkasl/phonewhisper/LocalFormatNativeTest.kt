package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class LocalFormatNativeTest {
    private class Bindings(private val events: MutableList<String>) : LocalFormatNativeApi {
        override val runtimeName = "test"
        var failure: Throwable? = null
        var lastProfile: LocalFormatDecodingProfile? = null
        var lastGrammar: ByteArray? = null
        override fun open(path: ByteArray, contextSize: Int, threads: Int) = 1L
        override fun generate(handle: Long, generation: Long, prompt: ByteArray, grammar: ByteArray?,
            profile: LocalFormatDecodingProfile, maxTokens: Int, timeoutMs: Long,
            sink: LocalFormatChunkSink): ByteArray {
            events.add("generate")
            lastProfile = profile
            lastGrammar = grammar
            failure?.let { throw it }
            sink.onBytes("écho".toByteArray(Charsets.UTF_8))
            return "écho".toByteArray(Charsets.UTF_8)
        }
        override fun cancel(handle: Long, generation: Long) { events.add("cancel") }
        override fun close(handle: Long) { events.add("close") }
    }

    @Test fun nativeStartImmediatelyFollowsTheCancellationGuardAndPrecedesBindings() {
        val events = mutableListOf<String>()
        val bindings = Bindings(events)
        LocalFormatNative.forTesting(bindings).use { native ->
            val result = native.generate("prompt", 32, 1000, { events.add("chunk:$it") },
                onNativeStart = { events.add("started") },
                isCancelled = { events.add("guard"); false })
            assertEquals("écho", result)
            assertEquals(listOf("guard", "started", "guard", "generate", "chunk:écho"), events)
            assertEquals(LocalFormatDecodingProfile.Historical, bindings.lastProfile)
            assertEquals(0, LocalFormatDecodingProfile.Historical.nativeId)
            native.cancel()
            assertFalse(events.contains("cancel"))
        }
    }

    @Test fun explicitGemmaGreedyProfileReachesBindings() {
        val events = mutableListOf<String>()
        val bindings = Bindings(events)
        LocalFormatNative.forTesting(bindings).use { native ->
            assertEquals(
                "écho",
                native.generate(
                    prompt = "prompt",
                    maxTokens = 32,
                    timeoutMs = 1000,
                    onChunk = { events.add("chunk:$it") },
                    profile = LocalFormatDecodingProfile.GemmaFineTunedGreedy,
                ),
            )
            assertEquals(LocalFormatDecodingProfile.GemmaFineTunedGreedy, bindings.lastProfile)
            assertEquals(1, LocalFormatDecodingProfile.GemmaFineTunedGreedy.nativeId)
        }
    }

    @Test fun historicalProfilePreservesGrammarInput() {
        val events = mutableListOf<String>()
        val bindings = Bindings(events)
        val grammar = "root ::= \"ok\""
        LocalFormatNative.forTesting(bindings).use { native ->
            assertEquals(
                "écho",
                native.generate(
                    prompt = "prompt",
                    maxTokens = 32,
                    timeoutMs = 1000,
                    onChunk = {},
                    grammar = grammar,
                    profile = LocalFormatDecodingProfile.Historical,
                ),
            )
            assertArrayEquals(grammar.toByteArray(Charsets.UTF_8), bindings.lastGrammar)
        }
    }

    @Test fun gemmaGreedyRejectsGrammarBeforeNativeCall() {
        val events = mutableListOf<String>()
        val bindings = Bindings(events)
        LocalFormatNative.forTesting(bindings).use { native ->
            assertNull(
                native.generate(
                    prompt = "prompt",
                    maxTokens = 32,
                    timeoutMs = 1000,
                    onChunk = {},
                    grammar = "root ::= \"ok\"",
                    profile = LocalFormatDecodingProfile.GemmaFineTunedGreedy,
                ),
            )
            assertTrue(events.isEmpty())
            assertNull(bindings.lastProfile)
        }
    }

    @Test fun cancelledGenerationNeverReportsNativeStart() {
        val events = mutableListOf<String>()
        LocalFormatNative.forTesting(Bindings(events)).use { native ->
            assertNull(native.generate("prompt", 32, 1000, {},
                onNativeStart = { events.add("started") },
                isCancelled = { events.add("guard"); true }))
            assertEquals(listOf("guard"), events)
            native.cancel()
            assertFalse(events.contains("cancel"))
        }
    }

    @Test fun closedOrInvalidBudgetGenerationNeverReportsNativeStart() {
        val events = mutableListOf<String>()
        val native = LocalFormatNative.forTesting(Bindings(events))
        assertNull(native.generate("prompt", 0, 1000, {}, onNativeStart = { events.add("started") }))
        assertNull(native.generate("prompt", 32, 0, {}, onNativeStart = { events.add("started") }))
        assertTrue(events.isEmpty())
        native.close()
        assertNull(native.generate("prompt", 32, 1000, {}, onNativeStart = { events.add("started") }))
        assertEquals(listOf("close"), events)
    }

    @Test fun bindingsFailureStillCountsAsNativeInvocationAndClearsCancellationOwnership() {
        val events = mutableListOf<String>()
        val bindings = Bindings(events).apply { failure = IllegalStateException("native failed") }
        LocalFormatNative.forTesting(bindings).use { native ->
            val result = runCatching {
                native.generate("prompt", 32, 1000, {}, onNativeStart = { events.add("started") })
            }
            assertTrue(result.isFailure)
            assertEquals(listOf("started", "generate"), events)
            native.cancel()
            assertFalse(events.contains("cancel"))
        }
    }

    @Test fun cancellationIsRecheckedAfterNativeStartBeforeBindings() {
        val events = mutableListOf<String>()
        val cancelled = AtomicBoolean(false)
        LocalFormatNative.forTesting(Bindings(events)).use { native ->
            assertNull(
                native.generate(
                    prompt = "prompt",
                    maxTokens = 32,
                    timeoutMs = 1000,
                    onChunk = {},
                    onNativeStart = { cancelled.set(true) },
                    isCancelled = { cancelled.get() },
                ),
            )
        }
        assertFalse(events.contains("generate"))
    }

    @Test fun cancellingFinishedGenerationCannotCancelLaterGeneration() {
        val bindings = TwoGenerationBindings()
        val native = LocalFormatNative.forTesting(bindings)
        val firstGeneration = AtomicLong()
        val firstResult = arrayOfNulls<String>(1)
        val first = thread(start = true) {
            firstResult[0] = native.generate(
                prompt = "first",
                maxTokens = 32,
                timeoutMs = 1000,
                onChunk = {},
                onNativeGeneration = { firstGeneration.set(it) },
            )
        }
        first.join(2_000L)
        assertFalse(first.isAlive)
        assertEquals("first", firstResult[0])

        val secondResult = arrayOfNulls<String>(1)
        val second = thread(start = true) {
            secondResult[0] = native.generate(
                prompt = "second",
                maxTokens = 32,
                timeoutMs = 1000,
                onChunk = {},
            )
        }
        assertTrue(bindings.secondStarted.await(2, TimeUnit.SECONDS))
        native.cancel(firstGeneration.get())
        assertTrue(bindings.cancelledGenerations.isEmpty())
        bindings.releaseSecond.countDown()
        second.join(2_000L)

        assertFalse(second.isAlive)
        assertEquals("second", secondResult[0])
        native.close()
    }

    private class TwoGenerationBindings : LocalFormatNativeApi {
        override val runtimeName: String = "two-generation"
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val cancelledGenerations = CopyOnWriteArrayList<Long>()
        private val calls = AtomicInteger()

        override fun open(path: ByteArray, contextSize: Int, threads: Int): Long = 1L

        override fun generate(
            handle: Long,
            generation: Long,
            prompt: ByteArray,
            grammar: ByteArray?,
            profile: LocalFormatDecodingProfile,
            maxTokens: Int,
            timeoutMs: Long,
            sink: LocalFormatChunkSink,
        ): ByteArray {
            if (calls.incrementAndGet() == 2) {
                secondStarted.countDown()
                releaseSecond.await(2, TimeUnit.SECONDS)
            }
            return prompt.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8)
        }

        override fun cancel(handle: Long, generation: Long) {
            cancelledGenerations += generation
        }

        override fun close(handle: Long) = Unit
    }
}
