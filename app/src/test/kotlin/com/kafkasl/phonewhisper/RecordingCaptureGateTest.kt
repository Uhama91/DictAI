package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class RecordingCaptureGateTest {
    @Test fun `paused audio reaches neither pcm history nor streaming and resume appends`() {
        val gate = RecordingCaptureGate()
        val pcm = mutableListOf<Int>()
        val asr = mutableListOf<Int>()
        fun deliver(sample: Int) = gate.deliver { pcm += sample; asr += sample }
        assertTrue(deliver(1))
        gate.pause()
        assertFalse(deliver(999))
        assertFalse(deliver(998))
        gate.resume()
        assertTrue(deliver(2))
        gate.pause()
        assertFalse(deliver(997))
        gate.resume()
        assertTrue(deliver(3))
        assertEquals(listOf(1, 2, 3), pcm)
        assertEquals(pcm, asr)
    }

    @Test fun `pause waits for in-flight delivery then closes admission atomically`() {
        val gate = RecordingCaptureGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val pausing = CountDownLatch(1)
        val reader = thread { gate.deliver { entered.countDown(); release.await() } }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val pauser = thread { pausing.countDown(); gate.pause(); paused.countDown() }
        try {
            assertTrue(pausing.await(1, TimeUnit.SECONDS))
            assertFalse(paused.await(50, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        reader.join(1000)
        pauser.join(1000)
        assertTrue(paused.await(1, TimeUnit.SECONDS))
        assertFalse(gate.deliver { fail("Late audio entered paused session") })
    }
}
