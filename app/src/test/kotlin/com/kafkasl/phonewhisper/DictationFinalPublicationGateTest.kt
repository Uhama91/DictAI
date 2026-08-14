package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationFinalPublicationGateTest {
    @Test
    fun processing_tap_defers_final_and_valid_double_tap_cancels_before_publication() {
        val detector = DoubleTapDetector(windowMs = 280)
        val gate = DictationFinalPublicationGate(windowMs = 280)
        var published = false

        assertEquals(DoubleTapDetector.Tap.SINGLE, detector.registerTap(1_000))
        val window = gate.armProcessingTap(atMs = 1_000)

        assertEquals(
            DictationFinalPublicationGate.Submission.DEFERRED,
            gate.submit(atMs = 1_100) { published = true },
        )
        assertFalse(published)

        assertEquals(DoubleTapDetector.Tap.DOUBLE, detector.registerTap(1_279))
        gate.cancel()

        assertEquals(
            DictationFinalPublicationGate.Submission.IGNORED,
            gate.release(window, nowMs = 1_280),
        )
        assertFalse(published)
    }

    @Test
    fun final_publishes_at_the_window_boundary_when_no_second_tap_arrives() {
        val gate = DictationFinalPublicationGate(windowMs = 280)
        var published = false
        val window = gate.armProcessingTap(atMs = 2_000)

        assertEquals(
            DictationFinalPublicationGate.Submission.DEFERRED,
            gate.submit(atMs = 2_100) { published = true },
        )
        assertFalse(published)

        assertEquals(
            DictationFinalPublicationGate.Submission.PUBLISHED,
            gate.release(window, nowMs = 2_280),
        )
        assertTrue(published)
    }

    @Test
    fun final_publishes_immediately_when_no_processing_tap_occurred() {
        val gate = DictationFinalPublicationGate(windowMs = 280)
        var published = false

        assertEquals(
            DictationFinalPublicationGate.Submission.PUBLISHED,
            gate.submit(atMs = 3_000) { published = true },
        )
        assertTrue(published)
    }
}
