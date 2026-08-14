package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationRunCompletionGateTest {
    @Test
    fun raw_recording_teardown_returns_without_an_unstarted_worker() {
        val gate = DictationRunCompletionGate()

        gate.awaitWorkerIfStarted()
    }

    @Test
    fun teardown_waits_for_a_started_worker_even_when_the_reader_was_present() {
        val gate = DictationRunCompletionGate()
        gate.markWorkerStarted()
        val enteredAwait = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val waiter = Thread {
            enteredAwait.countDown()
            gate.awaitWorkerIfStarted()
            returned.countDown()
        }
        waiter.start()

        assertTrue(enteredAwait.await(1, TimeUnit.SECONDS))
        assertFalse(returned.await(250, TimeUnit.MILLISECONDS))
        gate.markWorkerDone()
        assertTrue(returned.await(1, TimeUnit.SECONDS))
        waiter.join(1_000)
        assertFalse(waiter.isAlive)
    }
}
