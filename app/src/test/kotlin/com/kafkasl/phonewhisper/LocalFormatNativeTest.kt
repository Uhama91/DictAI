package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class LocalFormatNativeTest {
    private class Bindings(private val events: MutableList<String>) : LocalFormatNativeApi {
        override val runtimeName = "test"
        var failure: Throwable? = null
        override fun open(path: ByteArray, contextSize: Int, threads: Int) = 1L
        override fun generate(handle: Long, generation: Long, prompt: ByteArray, grammar: ByteArray?, maxTokens: Int,
            timeoutMs: Long, sink: LocalFormatChunkSink): ByteArray {
            events.add("generate")
            failure?.let { throw it }
            sink.onBytes("écho".toByteArray(Charsets.UTF_8))
            return "écho".toByteArray(Charsets.UTF_8)
        }
        override fun cancel(handle: Long, generation: Long) { events.add("cancel") }
        override fun close(handle: Long) { events.add("close") }
    }

    @Test fun nativeStartImmediatelyFollowsTheCancellationGuardAndPrecedesBindings() {
        val events = mutableListOf<String>()
        LocalFormatNative.forTesting(Bindings(events)).use { native ->
            val result = native.generate("prompt", 32, 1000, { events.add("chunk:$it") },
                onNativeStart = { events.add("started") },
                isCancelled = { events.add("guard"); false })
            assertEquals("écho", result)
            assertEquals(listOf("guard", "started", "generate", "chunk:écho"), events)
            native.cancel()
            assertFalse(events.contains("cancel"))
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
}
