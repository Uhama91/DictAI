package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscribeCppNativeTest {
    @Test
    fun closing_twice_frees_the_native_handle_once_and_blocks_further_calls() {
        val bindings = FakeBindings()
        val session = TranscribeCppNative.forTesting(handle = 7L, bindings = bindings)

        session.close()
        session.close()

        assertEquals(listOf(7L), bindings.freed)
        assertThrows(IllegalStateException::class.java) { session.begin() }
    }

    @Test
    fun feed_and_finish_return_the_native_text_snapshot_without_reconstructing_it() {
        val bindings = FakeBindings()
        val session = TranscribeCppNative.forTesting(handle = 7L, bindings = bindings)

        assertEquals(
            TranscribeCppNative.Text(full = "bonjour le monde", committed = "bonjour", tentative = " le monde"),
            session.feed(floatArrayOf(.1f, -.1f)),
        )
        assertEquals(
            TranscribeCppNative.Text(full = "bonjour le monde", committed = "bonjour le monde", tentative = ""),
            session.finish(),
        )
    }

    private class FakeBindings : TranscribeCppNative.Bindings {
        val freed = mutableListOf<Long>()

        override fun open(modelPath: String): Long = 7L
        override fun begin(handle: Long) = Unit
        override fun feed(handle: Long, samples: FloatArray): Array<String> =
            arrayOf("bonjour le monde", "bonjour", " le monde")

        override fun getText(handle: Long): Array<String> =
            arrayOf("bonjour le monde", "bonjour", " le monde")

        override fun finish(handle: Long): Array<String> =
            arrayOf("bonjour le monde", "bonjour le monde", "")

        override fun reset(handle: Long) = Unit
        override fun free(handle: Long) { freed += handle }
    }
}
